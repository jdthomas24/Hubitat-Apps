/**
 * Reolink Integration (Parent App)
 * Version: 1.3.6
 *
 * Architecture notes:
 *  - A "source" is anything that answers the Reolink HTTP/JSON API: a standalone
 *    camera, a PoE NVR, or a network Home Hub. Each source has its own IP + creds.
 *  - A source with N paired channels (NVR/Home Hub) reports channels 0..N-1.
 *    A standalone camera is a degenerate source with exactly one channel: 0.
 *  - Every child device is tagged with (sourceId, channel). Children never talk
 *    HTTP directly -- they call parent.componentX() and this app does the call.
 *  - Poll interval is per-child, not global, because wired-mode doorbells/cams
 *    can be polled tight (2-5s) while battery-mode devices should be polled
 *    looser to avoid hammering a sleeping device.
 *
 * Device-specific findings, known limitations, and setup gotchas are documented
 * in the README and the in-app Tips page -- not duplicated here to avoid the
 * two drifting out of sync.
 *
 * TODO markers throughout mark spots that need exact command/param names
 * verified against your firmware's API guide (GetMdState / GetAiState /
 * GetChannelstatus / Snap / PtzCtrl / Login field names can drift by version).
 *
 * Full version history prior to 1.3.0 (the 1.1.x scheduler/logging plumbing,
 * the 1.2.x snapshot relay redesign, the central scheduler rewrite, sendEvent
 * gating, and tiered logging) is in the GitHub commit history and past
 * release notes -- not duplicated here. This header only documents the
 * CURRENT version going forward, trimmed down from the full inline changelog
 * that used to live here (it had grown to roughly 10% of this file's total
 * lines with no benefit to anyone running the current version).
 *
 * v1.3.6 -- Two related fixes to the device-discovery/toggle page
 * (discoverPage()), both found via real-world use:
 *  1. FIXED: unchecking an EXISTING device on a single-channel (standalone
 *     camera) source never actually deleted it. The auto-apply check for
 *     single-channel sources only called createSelectedChildren() when the
 *     checkbox was TRUE (`if (settings[...]) { createSelectedChildren(...) }`)
 *     -- unchecking an existing device sets that setting to FALSE, which
 *     never entered the block, so the delete branch inside
 *     createSelectedChildren() (the actual removal logic) never ran. Changed
 *     the condition to fire whenever the checkbox state disagrees with
 *     whether the device currently exists (either direction: check an
 *     absent device to create it, OR uncheck a present device to remove
 *     it), not just the create direction.
 *  2. Multi-channel (NVR/Home Hub) sources technically already applied
 *     removal correctly through the "Create selected devices" toggle, but
 *     that toggle's label only described creation -- nothing indicated it
 *     ALSO applies removal for anything unchecked, which is exactly the
 *     kind of thing that looks like "unchecking didn't do anything."
 *     Relabeled to "Apply changes (create checked / remove unchecked)" and
 *     reworded the explanatory paragraph to say so explicitly.
 *  3. Every per-channel row now says outright whether it's an
 *     "(Existing Device)" or a "(New Device)" right in the checkbox title,
 *     instead of only showing a checkmark for existing ones and nothing at
 *     all for new ones -- removes the previous "is this actually new or
 *     did I lose track of something" ambiguity.
 *  4. Reworded the "Danger zone" remove-source toggle to spell out that it
 *     removes the ENTIRE source and ALL of its child devices in one shot --
 *     this is a completely separate, all-or-nothing action from the
 *     per-channel checkboxes above it, and the old wording ("Remove this
 *     source (deletes its child devices too)") didn't make that scope
 *     clear enough to avoid it reading like it might apply to whatever's
 *     currently toggled in the list above instead.
 *  5. Minor: Hub/NVR channel device-type detection (camera vs doorbell)
 *     now checks the channel's own name instead of a model field the
 *     Hub/NVR API never returns, fixing a mislabeled doorbell channel.
 *  6. Minor: added a short note on the discover page about removing
 *     devices from this page rather than Hubitat's Devices page directly.
 *
 * v1.3.5 -- Capability-detection corrections and additions, cross-checked
 * against Reolink's own officially-backed reolink_aio library:
 *  1. CORRECTED a mistake from v1.3.1: supportDoorbellLight was folded into
 *     the Spotlight feature gate based on real hardware showing it nonzero
 *     on a doorbell with a light -- but that doorbell's only lights are IR
 *     night vision and a button/ring status light, no true spotlight.
 *     Confirmed via reolink_aio that this key actually governs the
 *     status/ring indicator LED, not illumination. Split into its own
 *     "Status LED" feature, removed from Spotlight.
 *  2. Added "Night Vision" feature (ledControl > 0) -- a real prior gap,
 *     field found via reolink_aio, confirmed against a real doorbell.
 *  3. Added floodLight back into the Spotlight OR chain as a defensive
 *     fallback alongside supportFLswitch, matching reolink_aio's own logic,
 *     even though floodLight alone has stayed unreliable on every device
 *     tested so far.
 *  4. abilityPermit() now checks both the "permit" and "ver" sub-fields of
 *     every GetAbility entry (previously permit only). reolink_aio checks
 *     ver exclusively; these move together on every field tested except
 *     ptzType, where permit stays 0 on confirmed PTZ cameras but ver
 *     correctly shows nonzero -- avoided in our case by keying PTZ presence
 *     off ptzCtrl instead, but checking both closes off the same risk for
 *     any other field not yet tested against divergent hardware.
 *  5. Confirmed (not a guess) the battery percentage field name via
 *     reolink_aio: Battery.batteryPercent. See CameraDriver's
 *     receiveBatteryInfo().
 */

import groovy.transform.Field


definition(
    name: "Reolink Integration",
    namespace: "jdthomas24",
    author: "Jason",
    description: "Discovers and manages Reolink cameras, doorbells, NVRs, and Home Hubs",
    category: "Convenience",
    menu: "Integrations", // groups this app under the "Integrations" section of Add User App
    iconUrl: "",
    iconX2Url: "",
    singleThreaded: true,
    oauth: true // required for createAccessToken()/local endpoint access used by the snapshot relay
)

@Field static final String APP_VERSION = "1.3.6"

@Field static final List LOG_LEVELS = ["Errors Only", "Normal", "Full"]

// Poll interval is a device-level setting ONLY -- these are just the one-time
// default applied to a newly created device, not user-configurable at the app
// level. To change an existing device's interval, use its own device page (or
// the Set Poll Interval / Set Snapshot Interval commands).
@Field static final Integer DEFAULT_WIRED_POLL_SEC = 3
@Field static final Integer DEFAULT_BATTERY_POLL_SEC = 30

preferences {
    page(name: "mainPage")
    page(name: "addSourcePage")
    page(name: "discoverPage")
    page(name: "tipsPage")
}

// Local (non-cloud) endpoint the dashboard image tile hits on every refresh.
// See componentTakeSnapshot() / handleSnapshotRequest() below.
mappings {
    path("/snap/:dni") {
        action: [GET: "handleSnapshotRequest"]
    }
}

def mainPage() {
    if (newLabel && newHost && newUser && newPass) {
        addSource()
        app.removeSetting("newLabel")
        app.removeSetting("newHost")
        app.removeSetting("newUser")
        app.removeSetting("newPass")
    }
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section {
            paragraph pillHeader("Sources")
            paragraph "<b>A source is one camera, one NVR, or one Home Hub -- anything with its own IP/login.</b>"
            (state.sources ?: []).each { src ->
                href name: "src_${src.id}", title: "${src.label} (${src.host})",
                    description: "${src.isHub ? 'Hub/NVR' : 'Standalone'} · ${childrenForSource(src.id).size()} device(s)",
                    page: "discoverPage", params: [sourceId: src.id]
            }
        }
        section {
            href name: "addSource", title: "➕ Add a source...",
                description: "Standalone camera, NVR, or Home Hub", page: "addSourcePage"
        }
        section {
            paragraph pillHeader("Logging")
            input "logLevel", "enum", title: "Log level", options: LOG_LEVELS,
                defaultValue: "Errors Only", submitOnChange: true
            paragraph logLevelPill("Errors Only") + " Warnings and errors only. Default -- matches the " +
                "old debug-logging-off behavior, so nothing changes for anyone who hasn't touched this setting."
            paragraph logLevelPill("Normal") + " Adds meaningful one-time events and state transitions " +
                "(login, asleep/awake changes, devices created, config changes) on top of Errors Only."
            paragraph logLevelPill("Full") + " Everything, including every routine poll step. Useful for " +
                "actively chasing something intermittent. <b>Automatically reverts to Normal after 60 " +
                "minutes</b> -- Errors Only and Normal have no timer, since neither is noisy enough to need one."
        }
        section {
            href name: "tips", title: "Tips, limitations & what works so far", page: "tipsPage",
                description: "Troubleshooting, known device quirks, and confirmed capabilities"
        }
        section("<b>Help & Support</b>") {
            paragraph rawHtml: true, """
<div style='padding:4px 0;'>
  <a href='https://community.hubitat.com/t/release-reolink-integration-cameras-doorbells-nvrs-home-hubs/165352' target='_blank'
     style='display:block; background:#f8f8f8; border:1px solid #ddd; border-radius:6px; padding:10px 14px; text-decoration:none; color:#333; margin-bottom:6px;'>
    <span style='font-size:14px;'>\uD83D\uDCAC <b>Hubitat Community Thread</b></span><br>
    <span style='font-size:12px; color:#888;'>Questions, feedback, bug reports, and release notes</span>
  </a>
  <a href='https://www.paypal.com/paypalme/jdthomas24?locale.x=en_US&country.x=US' target='_blank'
     style='display:block; background:#f8f8f8; border:1px solid #ddd; border-radius:6px; padding:10px 14px; text-decoration:none; color:#333;'>
    <span style='font-size:14px;'>\u2615 <b>Buy Me a Coffee</b></span><br>
    <span style='font-size:12px; color:#888;'>Enjoying the app? Any amount is appreciated -- thank you!</span>
  </a>
</div>
"""
        }
        section {
            paragraph "<div style='text-align:center;color:#999;font-size:11px;margin-top:10px;'>" +
                "Reolink Integration v${APP_VERSION}</div>"
        }
    }
}

private String pillHeader(String text) {
    "<div style='display:inline-block;background:#E3F2FD;color:#1565C0;font-weight:700;" +
    "font-size:12px;letter-spacing:0.5px;padding:4px 16px;border-radius:14px;" +
    "margin-bottom:6px;'>${text.toUpperCase()}</div>"
}

/**
 * Small colored pill for a log level name, distinct from pillHeader's section-title style so
 * the two don't get visually confused. Color signals severity/verbosity at a glance: grey for
 * the quietest tier, blue for the default, orange for the noisiest/temporary one.
 */
private String logLevelPill(String level) {
    def colors = [
        "Errors Only": [bg: "#ECEFF1", fg: "#455A64"],
        "Normal":      [bg: "#E3F2FD", fg: "#1565C0"],
        "Full":        [bg: "#FFF3E0", fg: "#E65100"]
    ]
    def c = colors[level] ?: [bg: "#ECEFF1", fg: "#455A64"]
    "<span style='display:inline-block;background:${c.bg};color:${c.fg};font-weight:700;" +
    "font-size:11px;letter-spacing:0.3px;padding:2px 10px;border-radius:10px;'>${level}</span>"
}

def tipsPage() {
    dynamicPage(name: "tipsPage", title: "Tips & Notes") {
        section {
            paragraph pillHeader("What a source is")
            paragraph "A source is one camera, one NVR, or one Home Hub -- anything with its own IP/login."
            paragraph "A standalone camera always has one channel: 0."
            paragraph "An NVR/Home Hub has one channel per paired camera. Run discovery to see what it finds."
        }
        section {
            paragraph pillHeader("Before adding any camera")
            paragraph "Check the camera's own Network > Advanced (or Server) settings and make sure HTTP, " +
                "HTTPS, and ONVIF are enabled."
            paragraph "These are often off by default on every model tested so far -- not just Reolink's E1 " +
                "line. This is the single most common reason a source fails to connect, and it's worth " +
                "checking before assuming a device needs a Hub/NVR or isn't supported."
        }
        section {
            paragraph pillHeader("Why a device's ID number looks out of order")
            paragraph "Each device's internal ID (visible in Hubitat's device list as part of its DNI, e.g. " +
                "'reolink-4-0') is just a counter that only ever goes up. It never reuses a number, even after " +
                "you delete a source and its device."
            paragraph "So gaps in the numbering (4, 5, 8, 9 instead of 1, 2, 3, 4) just mean some source got " +
                "removed and re-added at some point along the way -- normal, and nothing to fix. It doesn't " +
                "affect how anything works."
        }
        section {
            paragraph pillHeader("Devices that won't work standalone")
            paragraph "⚠️ <b>Battery-class cameras/doorbells</b> (Argus line, Doorbell Battery, Gen 2 " +
                "doorbells). This is a <b>deliberate, permanent firmware exclusion across Reolink's entire " +
                "battery-powered product line</b> -- confirmed via Reolink's own community forum, not a " +
                "quirk of any specific unit."
            paragraph "It does NOT depend on how the device is powered. Even a battery-class device running " +
                "continuously on a DC adapter (not just trickle-charging) still has no local HTTP/ONVIF API, " +
                "because the firmware itself never includes that server stack on battery models. \"Wired " +
                "Power Mode\" in the Reolink app only changes charging behavior, never the network API."
            paragraph "They only become reachable once paired to a Home Hub or NVR. Add the Hub/NVR as the " +
                "source instead, and the device shows up as one of its channels."
            paragraph "⚠️ <b>E1, E1 Pro, and Lumus</b> -- Reolink's own docs on whether these support local " +
                "HTTP/HTTPS are inconsistent, and don't fully agree with each other model to model."
            paragraph "Don't rely on the model name to decide. <b>Check the camera's own Network > Advanced " +
                "(or Server) settings</b> for HTTP/HTTPS/ONVIF toggles -- that tells you directly whether this " +
                "specific unit can do it, regardless of what any doc claims for the line in general. That's " +
                "exactly how a real E1 Pro here turned out to support HTTP fine, just off by default."
            paragraph "Everything else -- PoE cameras, WiFi cameras outside the E1 line -- works standalone."
        }
        section {
            paragraph pillHeader("Poll interval")
            paragraph "Wired devices can be polled tight: a few seconds is fine."
            paragraph "Battery devices should be polled loose -- they only wake for their own events or an " +
                "occasional self check-in, at most once an hour or so."
            paragraph "Polling a battery device harder doesn't get fresher data -- it just drains the battery " +
                "for no benefit."
            paragraph "This still holds once a battery device is behind a Hub: you're asking the Hub for its " +
                "last-known state, not the device directly. The device's own check-in cadence is still the " +
                "real limit."
        }
        section {
            paragraph pillHeader("Sleep status")
            paragraph "<b>Awake</b> -- the last poll actually got a response."
            paragraph "<b>Asleep</b> -- the last poll got no response at all."
            paragraph "For a battery device, asleep is normal, not an error -- it just hasn't checked in since " +
                "its last event or self-wake."
            paragraph "⚠️ For a <b>wired/PoE device</b>, asleep is NOT normal -- it means a poll genuinely got " +
                "no response, which points to a real connectivity or load issue worth investigating."
            paragraph "Motion/person/vehicle/etc. keep their last-known value when this happens, rather than " +
                "resetting to inactive."
        }
        section {
            paragraph pillHeader("Known older-firmware bug: false 'asleep' from garbled responses")
            paragraph "⚠️ Some E1-series cameras on ~2021-era firmware (e.g. build 21120806, v3.0.0.748) have a " +
                "known bug where the camera's web server intermittently returns corrupted/garbled data instead " +
                "of a real response -- not encryption, not a real connectivity problem, just bad data back from " +
                "the camera itself. This app can't tell that apart from a genuinely unreachable device, so it " +
                "gets reported as <b>asleep</b> even though the camera is actually online and responding."
            paragraph "How to tell: if a wired/PoE device keeps flipping to asleep with no real pattern, and Full " +
                "logging shows parse errors on GetAiState/GetMdState rather than plain timeouts, this is likely " +
                "it rather than an actual network issue."
            paragraph "Confirmed via a 2021-firmware E1 Outdoor -- newer firmware (e.g. 2024-era, v3.1.0.3429) on " +
                "the same camera line does not show this problem."
            paragraph "Two things worth trying, in order: (1) In the Reolink app, turn off HTTP/HTTPS under this " +
                "camera's Network settings, reboot the camera, then turn HTTP/HTTPS back on -- this reinitializes " +
                "the camera's web server and can clear it up without a firmware change. (2) If that doesn't help, " +
                "check for a firmware update for this exact camera/hardware version via the Reolink desktop app's " +
                "Download Center, or contact Reolink support directly with your model and firmware version. When " +
                "updating, avoid any 'reset configuration' option unless you actually want to reset the camera."
        }
        section {
            paragraph pillHeader("PTZ")
            paragraph "Reolink has no 'Home' command. The real equivalent is a saved preset."
            paragraph "Use <b>savePresetHere</b> once (commonly preset ID 1) to save wherever the camera is " +
                "currently pointed."
            paragraph "Use <b>ptzGoToPreset</b> with that same ID any time you want it to return there."
        }
        section {
            paragraph pillHeader("PTZ calibration")
            paragraph "⚠️ <b>Only applies to PTZ-capable cameras</b> (e.g. Trackmix, E1 Zoom). Non-PTZ cameras " +
                "will just return an error if you try it -- harmless, but there's nothing to calibrate."
            paragraph "Use <b>calibratePtz</b> if preset recall starts drifting off target over time. Check " +
                "progress with <b>checkPtzCalibrationStatus</b> -- Required means it hasn't been calibrated, " +
                "Running means it's in progress (takes a few seconds), Done means it's ready."
        }
        section {
            paragraph pillHeader("Snapshot tiles on dashboards")
            paragraph "Snapshot URLs point at a local relay endpoint on this app, not directly at the camera. " +
                "The camera itself is only ever contacted on its own snapshot interval (device preference, " +
                "separate from poll interval) -- the relay endpoint just serves whatever image was cached " +
                "from that last fetch."
            paragraph "That means a dashboard tile can refresh as often as you like, but the picture it shows " +
                "only actually changes as often as that device's snapshot interval. A dashboard tile's own " +
                "refresh setting has no effect on how often the image itself changes."
            paragraph "Snapshot interval is intentionally kept separate from poll interval. Poll interval " +
                "controls motion/AI state and should generally stay tight for responsive automations. " +
                "Snapshot interval controls image freshness only, and can stay looser (default 30s) without " +
                "affecting motion responsiveness at all."
            paragraph "If a camera's tile feels slow to update, lower that device's snapshot interval (device " +
                "page, or the setSnapshotInterval command) -- not the poll interval, and not the dashboard " +
                "tile's own refresh setting."
        }
        section {
            paragraph pillHeader("Log levels")
            paragraph logLevelPill("Errors Only") + " Default. Warnings and errors only -- matches the old " +
                "debug-logging-off behavior, so nothing changes for anyone who hasn't touched this setting."
            paragraph logLevelPill("Normal") + " Adds meaningful one-time events and state " +
                "transitions: a fresh login, a device flipping asleep/awake, a device created, a config " +
                "change. Routine polls that succeed with no change don't log anything."
            paragraph logLevelPill("Full") + " Everything, including every routine poll step. Useful for " +
                "actively chasing something intermittent. <b>Automatically reverts to Normal after 60 " +
                "minutes</b> so it doesn't stay noisy indefinitely."
            paragraph "⚠️ It's normal for <b>Errors Only</b> and <b>Normal</b> to show nothing at all for " +
                "long stretches -- that means nothing worth flagging has happened, not that the app has " +
                "stopped working. If you want to confirm it's actually running, switch to <b>Full</b> " +
                "temporarily and you'll see continuous poll activity."
        }
        section {
            paragraph pillHeader("Supported Features (new in v1.3.0)")
            paragraph "Every device now has a read-only <b>supportedFeatures</b> attribute, populated " +
                "automatically at discovery time from the camera's own reported capabilities (Reolink's " +
                "GetAbility API) -- e.g. \"PTZ, Spotlight, Night Vision, Person Detection, Vehicle Detection, " +
                "Pet Detection\" for a full-featured PTZ camera with a light, or \"Night Vision, Status LED, " +
                "Person Detection, Vehicle Detection, Package Detection\" for a doorbell with IR night vision " +
                "and a button light but no true spotlight."
            paragraph "⚠️ This is informational only -- it does NOT hide or disable any commands. Hubitat " +
                "has no way to remove a command from an individual device instance, so every command still " +
                "appears on every device regardless of what supportedFeatures says. Trying a command the " +
                "device doesn't actually support (e.g. PTZ on a fixed camera) will just harmlessly error, " +
                "same as before -- check supportedFeatures first to know what's actually worth trying."
            paragraph "Use the <b>Check Abilities</b> command any time to refresh this -- useful after a " +
                "firmware update that might add a capability, or for a device created before this feature " +
                "existed."
            paragraph "Package detection is doorbell-specific (cameras don't have it) and its exact field " +
                "name is still being confirmed against real doorbell hardware -- it may not always show up " +
                "correctly on every doorbell yet. Battery-status detection isn't part of this feature yet " +
                "either (still relies on the existing wired/battery detection at discovery time, unrelated " +
                "to supportedFeatures)."
        }
        section {
            paragraph pillHeader("Confidence level on newer commands")
            paragraph "<b>Confirmed working</b> against real hardware: PtzCtrl -- move, and ToPos (preset recall)."
            paragraph "<b>Built but not yet tested</b> against this setup's actual firmware: SetPtzPreset " +
                "(save), SetWhiteLed (spotlight), SetIrLights (night vision), AudioAlarmPlay (siren), " +
                "GetBatteryInfo (battery %), PtzCheck/GetPtzCheckState (calibration)."
            paragraph "These are built from consistent patterns across several independent Reolink API " +
                "references. If one doesn't work as expected, check Logs with the log level set to Full -- " +
                "the exact response usually points to which field name needs adjusting for this device."
        }
    }
}

def addSourcePage(params) {
    if (params?.cancel) {
        app.removeSetting("newLabel")
        app.removeSetting("newHost")
        app.removeSetting("newUser")
        app.removeSetting("newPass")
        return mainPage()
    }
    dynamicPage(name: "addSourcePage", title: "Add a Reolink Source", nextPage: "mainPage") {
        section {
            href name: "cancelAddSource", title: "Cancel", description: "Back to Sources without saving",
                page: "addSourcePage", params: [cancel: true]
        }
        section {
            paragraph "<span style='display:inline-block;background:#FFF3E0;color:#E65100;font-weight:700;" +
                "padding:2px 10px;border-radius:10px;font-size:11px;margin-right:6px;'>NOTE</span>" +
                "<b>Before adding: check the camera's own Network > Advanced (or Server) settings and make " +
                "sure HTTP, HTTPS, and ONVIF are enabled.</b> These are often off by default on every model " +
                "tested so far, not just Reolink's E1 line -- this is the single most common reason a source " +
                "fails to connect."
            paragraph "Battery-class cameras/doorbells and Reolink's E1 line have additional connection quirks " +
                "worth knowing about -- see the Tips page (link on the Sources list) if this one still refuses " +
                "to connect after enabling the ports above."
            input "newLabel", "text", title: "Label (e.g. 'Front Door Hub', 'Garage Cam')"
            input "newHost", "text", title: "IP address"
            input "newPort", "number", title: "HTTPS port", defaultValue: 443
            input "newUser", "text", title: "Username"
            input "newPass", "password", title: "Password"
            input "newIsHub", "bool", title: "This is an NVR or Home Hub (multiple channels)", defaultValue: false
            paragraph "Fill in Label, IP address, Username, and Password, then tap Next to save. " +
                "Leaving any of those blank just returns you to the Sources list without creating anything."
        }
    }
}

def discoverPage(params) {
    def sourceId = params?.sourceId ?: state.currentDiscoverySourceId
    state.currentDiscoverySourceId = sourceId
    def src = getSource(sourceId)

    // Auto-run discovery the first time this source's Discover page is opened
    // (no cached results yet for this source), in addition to an explicit
    // "Re-run discovery" click. Removes the old requirement to manually run
    // discovery once before anything showed up after adding a source.
    def alreadyCachedForThisSource = (state.lastDiscoverySourceId == sourceId)
    if (src && (params?.run || !alreadyCachedForThisSource)) {
        state.lastDiscovery = discoverChannels(sourceId)
        state.lastDiscoverySourceId = sourceId
    }

    def cachedForThisSource = (state.lastDiscoverySourceId == sourceId)
    def lastDiscovery = cachedForThisSource ? (state.lastDiscovery ?: []) : []
    def channelCount = lastDiscovery.size()

    if (confirmCreate) {
        createSelectedChildren(sourceId)
        app.updateSetting("confirmCreate", [type: "bool", value: false])
    }

    // v1.3.6 FIX: this used to only fire when the checkbox was TRUE
    // (`if (settings[...]) { createSelectedChildren(...) }`), which meant
    // unchecking an EXISTING single-channel device to remove it never
    // triggered anything -- createSelectedChildren()'s delete branch simply
    // never ran. Now fires on EITHER direction: checking an absent device
    // (create) or unchecking a present one (remove), by comparing the
    // checkbox state against whether the device currently exists.
    if (channelCount == 1 && src) {
        def ch = lastDiscovery[0]
        def dni = childDni(sourceId, ch.channel)
        def existing = getChildDevice(dni) != null
        def wantIt = settings["create_${sourceId}_${ch.channel}"]
        if ((wantIt ?: false) != existing) {
            createSelectedChildren(sourceId)
        }
    }

    if (confirmRemoveSource && src) {
        removeSource(sourceId)
        app.updateSetting("confirmRemoveSource", [type: "bool", value: false])
        return mainPage()
    }

    return dynamicPage(name: "discoverPage", title: "Discover Channels - ${src?.label ?: '(source removed)'}", nextPage: "mainPage") {
        if (!src) {
            section {
                paragraph "This source has been removed. Go back and use Add a source... if this was a mistake."
            }
        } else {
            section {
                // v1.3.6: explicit warning added after a real-world case where a
                // device deleted from Hubitat's Devices page (instead of this
                // page) left the app's own checkbox state stale. Trimmed to one
                // sentence after real-world feedback that the original 3-sentence
                // version explaining both the standalone and multi-channel cases
                // in full was slower to read than it needed to be.
                paragraph "<span style='display:inline-block;background:#FFEBEE;color:#C62828;font-weight:700;" +
                    "padding:2px 10px;border-radius:10px;font-size:11px;margin-right:6px;'>HEADS UP</span>" +
                    "<b>Remove devices from THIS page, not Hubitat's Devices page.</b> Deleting there can " +
                    "bring a device back on its own or leave it stuck toggled-on."
                href name: "runDiscovery", title: "Re-run discovery",
                    description: "Discovery already ran automatically when this page opened. Use this to " +
                        "refresh the channel list, e.g. after pairing a new camera to an NVR/Home Hub.",
                    page: "discoverPage", params: [sourceId: sourceId, run: true]

                if (state.lastDiscoveryError) {
                    paragraph "⚠️ ${state.lastDiscoveryError}"
                }

                lastDiscovery.eachWithIndex { ch, idx ->
                    def dni = childDni(sourceId, ch.channel)
                    def exists = getChildDevice(dni) != null
                    // v1.3.6: dropped the generic "(camera)" tag from every row --
                    // it added noise without disambiguating anything, since almost
                    // every device already IS a camera. Only "(Doorbell)" earns a
                    // tag now, since that's the one case worth calling out.
                    // Existing/New status moved out of the checkbox title entirely
                    // and into a colored pill directly below each toggle. Hubitat's
                    // native toggle can't be wrapped in custom markup, so the pill
                    // is pulled tight against it (negative top margin) and given a
                    // colored left-border accent + indent, so it visually reads as
                    // "belonging to" the toggle above it rather than a floating
                    // line.
                    //
                    // Two-column grid via Hubitat's width: attribute (out of 12),
                    // same technique used elsewhere -- puts two channels side by
                    // side instead of one long vertical list, which matters once a
                    // source has a lot of channels. A full-width divider follows
                    // every second device (by position in this list, not channel
                    // number -- channel numbers can have gaps since offline
                    // channels are already filtered out before this loop runs) so
                    // it separates ROWS rather than fighting the side-by-side
                    // layout. Pill label shortened to just "EXISTING"/"NEW"
                    // (dropped "DEVICE") since the columns are narrower now.
                    def doorbellTag = ch.deviceType == "doorbell" ? " (Doorbell)" : ""
                    input "create_${sourceId}_${ch.channel}", "bool",
                        title: "Ch ${ch.channel}: ${ch.name}${doorbellTag}",
                        defaultValue: exists, submitOnChange: true, width: 6
                    if (exists) {
                        paragraph width: 6, "<div style='margin:-8px 0 0 32px;border-left:3px solid #378ADD;" +
                            "padding-left:10px;'><span style='display:inline-block;background:#B5D4F4;color:#042C53;" +
                            "font-weight:500;font-size:11px;letter-spacing:0.3px;padding:2px 10px;" +
                            "border-radius:20px;margin-right:6px;'>EXISTING</span>" +
                            "<span style='color:#5F5E5A;font-size:13px;'>Toggle off + apply to remove</span></div>"
                    } else {
                        paragraph width: 6, "<div style='margin:-8px 0 0 32px;border-left:3px solid #639922;" +
                            "padding-left:10px;'><span style='display:inline-block;background:#C0DD97;color:#173404;" +
                            "font-weight:500;font-size:11px;letter-spacing:0.3px;padding:2px 10px;" +
                            "border-radius:20px;margin-right:6px;'>NEW</span>" +
                            "<span style='color:#5F5E5A;font-size:13px;'>Toggle on + apply to create</span></div>"
                    }
                    if (idx % 2 == 1 || idx == lastDiscovery.size() - 1) {
                        paragraph width: 12, "<div style='height:1px;background:#e0e0e0;margin:12px 0;'></div>"
                    }
                }

                if (channelCount > 1) {
                    // v1.3.6: "Apply changes" pulled visually out of the device list --
                    // it's an action, not another device toggle, and was easy to mistake
                    // for one sitting in the same list with the same control style. The
                    // highlighted box above the toggle is the closest Hubitat's own form
                    // rendering allows to visually separating it (the toggle input itself
                    // can't be wrapped inside a custom container).
                    paragraph rawHtml: true, """
<div style='border:2px solid #185FA5;border-radius:8px;background:#E6F1FB;padding:10px 14px;margin-top:14px;'>
  <div style='color:#042C53;font-weight:700;font-size:14px;'>Apply changes</div>
  <div style='color:#0C447C;font-size:12px;margin-top:2px;'>Creates every device toggled on above and removes every device toggled off. Toggling a device by itself does not apply anything until you toggle this.</div>
</div>
"""
                    input "confirmCreate", "bool", title: "Apply changes now",
                        defaultValue: false, submitOnChange: true
                } else if (channelCount == 1) {
                    paragraph "Standalone source, one channel -- toggling it applies immediately (toggled on " +
                        "creates it, toggled off removes it), no separate apply step needed."
                }
            }
            section {
                paragraph pillHeader("Danger zone")
                // v1.3.6: reworded to make the scope unmistakable -- this is a completely
                // separate, all-or-nothing action from the per-channel checkboxes above it.
                // It does NOT apply to whatever's currently toggled in that list; it always
                // removes this ENTIRE source and every one of its child devices, regardless
                // of any per-channel selection state.
                input "confirmRemoveSource", "bool",
                    title: "Remove this ENTIRE source and ALL ${childrenForSource(sourceId as Integer).size()} of its device(s) -- unrelated to the toggles above",
                    defaultValue: false, submitOnChange: true
            }
        }
    }
}

// ---------- Source management ----------

def addSource() {
    state.sources = state.sources ?: []
    state.nextSourceId = (state.nextSourceId ?: 0) + 1
    def id = state.nextSourceId
    state.sources << [
        id: id, label: newLabel, host: newHost, port: newPort ?: 443,
        username: newUser, password: newPass, isHub: newIsHub ?: false,
        token: null, tokenExpires: 0
    ]
    logNormal "Added source ${id}: ${newLabel} (${newHost})"
}

def getSource(id) {
    (state.sources ?: []).find { it.id == (id as Integer) }
}

def childrenForSource(sourceId) {
    getChildDevices().findAll { it.getDataValue("sourceId") as Integer == sourceId }
}

def removeSource(id) {
    childrenForSource(id as Integer).each {
        forgetSchedulingState(it.deviceNetworkId)
        deleteChildDevice(it.deviceNetworkId)
    }
    state.sources.removeAll { it.id == (id as Integer) }
    state.sourceUnreachable?.remove(id.toString())
    logNormal "Removed source ${id}"
}

/** Drops a device's entries from the central scheduler's due-time maps once it's deleted, so state doesn't accumulate dead DNIs forever. */
private forgetSchedulingState(String dni) {
    state.nextPollDue?.remove(dni)
    state.nextSnapshotDue?.remove(dni)
}

// ---------- Auth ----------

private String reolinkLogin(sourceId) {
    def src = getSource(sourceId)
    if (src.token && now() < src.tokenExpires) {
        logFull "Reolink source ${sourceId}: reusing cached token, expires in ${(src.tokenExpires - now()) / 1000}s"
        return src.token
    }

    logFull "Reolink source ${sourceId}: cached token missing/expired, logging in fresh"
    def body = [[cmd: "Login", param: [User: [userName: src.username, password: src.password]]]]
    def resp = reolinkRawPost(src, body)
    if (resp == null) {
        // reolinkRawPost() already logged (or suppressed, if this source is
        // already known-unreachable) the underlying connection failure --
        // nothing more to log here, and nothing to parse out of a response
        // that never arrived.
        return null
    }
    def first = firstResultValue(resp, src)
    def token = first?.Token?.name
    def leaseSec = (first?.Token?.leaseTime ?: 3600) as Integer

    src.token = token
    src.tokenExpires = now() + (leaseSec * 1000L) - 30000L
    logNormal "Reolink source ${sourceId}: new token acquired, leaseTime=${leaseSec}s"
    markSourceReachable(sourceId)
    return token
}

private firstResultValue(resp, src) {
    try {
        return resp[0]?.value
    } catch (e) {
        log.warn "Reolink unexpected response shape from ${src.host} (source ${src.id}): " +
            "${e.message} -- raw: ${resp?.toString()?.take(300)}"
        return null
    }
}

private reolinkRawPost(src, bodyList) {
    def cmd = bodyList?.getAt(0)?.cmd ?: ""
    def uri = "https://${src.host}:${src.port}/cgi-bin/api.cgi?cmd=${cmd}"
    def params = [uri: uri, ignoreSSLIssues: true, requestContentType: "application/json",
                  body: groovy.json.JsonOutput.toJson(bodyList), timeout: 10]
    def result = null
    try {
        httpPost(params) { resp -> result = parseReolinkResponse(resp) }
    } catch (e) {
        markSourceUnreachable(src.id, "POST failed (${src.host}): ${e.message}")
    }
    return result
}

private parseReolinkResponse(resp) {
    def raw = resp?.data?.toString()
    return raw ? new groovy.json.JsonSlurper().parseText(raw) : null
}

/**
 * A source going unreachable (host down, network issue, etc.) is ONE
 * condition, not a fresh event every poll cycle -- but before this, every
 * layer that touched the failure (the raw POST, the "no token" abort, the
 * response-shape parse) logged its own warning independently, every single
 * poll, for as long as the outage lasted. A camera down for a few minutes
 * could produce dozens of near-identical warnings.
 *
 * These two helpers gate on a per-source state flag so only the TRANSITION
 * into/out of unreachable gets logged -- same "log transitions, not steady
 * state" idea as sleepStatus/sendIfChanged elsewhere in this app. Full-tier
 * logging still shows every individual attempt via the existing logFull()
 * calls elsewhere, for anyone actively troubleshooting.
 */
private void markSourceUnreachable(sourceId, String reason) {
    def map = state.sourceUnreachable ?: [:]
    def key = sourceId.toString()
    if (map[key] != true) {
        log.warn "Reolink source ${sourceId}: ${reason} -- further identical warnings for this source are " +
            "suppressed until it recovers (switch to Full logging to see every attempt)"
        map[key] = true
        state.sourceUnreachable = map
    } else {
        logFull "Reolink source ${sourceId}: still unreachable -- ${reason}"
    }
}

private void markSourceReachable(sourceId) {
    def map = state.sourceUnreachable ?: [:]
    def key = sourceId.toString()
    if (map[key] == true) {
        log.info "Reolink source ${sourceId}: connection restored"
        map[key] = false
        state.sourceUnreachable = map
    }
}

def reolinkApiCall(sourceId, String cmd, Map param = [:], Integer channel = null) {
    def src = getSource(sourceId)
    def token = reolinkLogin(sourceId)
    if (!token) {
        // Login already logged (or suppressed) the actual connection failure
        // above -- this is just the downstream consequence, not a new fact,
        // so it only needs Full-tier visibility, not its own warning.
        logFull "Reolink source ${sourceId}: no token available, aborting ${cmd}"
        return null
    }

    def outcome = doReolinkApiCall(src, sourceId, cmd, token, param, channel)

    // rspCode -6 ("please login first") means the camera invalidated our session
    // before our local tokenExpires said it should -- most likely a competing
    // client (Reolink app/NVR viewing this camera) forced a fresh login on the
    // camera side. Don't wait for the next poll cycle to notice; force our own
    // fresh login and retry once now.
    if (outcome.value == null && outcome.rspCode == -6) {
        logNormal "Reolink source ${sourceId}: token rejected by camera (please login first), forcing re-login"
        src.token = null
        src.tokenExpires = 0
        def freshToken = reolinkLogin(sourceId)
        if (freshToken) {
            outcome = doReolinkApiCall(src, sourceId, cmd, freshToken, param, channel)
        }
    } else if (outcome.value == null && outcome.parseFailure) {
        // Known bug on some older firmware (e.g. 2021-era E1 -- see Tips page):
        // the camera's web server intermittently returns corrupted/garbled data
        // instead of a real response, NOT an auth problem, so a fresh LOGIN
        // wouldn't help -- confirmed via real-world testing that an immediate
        // retry of the SAME call often succeeds right after a failed one. Retry
        // once with the same token before giving up; if it fails again, this
        // poll cycle reports no response as usual.
        logNormal "Reolink source ${sourceId}: ${cmd} (ch ${channel}) returned unparseable data (known older-firmware " +
            "bug, see Tips page), retrying once immediately"
        outcome = doReolinkApiCall(src, sourceId, cmd, token, param, channel)
    }
    return outcome.value
}

private Map doReolinkApiCall(src, sourceId, String cmd, String token, Map param, Integer channel) {
    def p = channel != null ? param + [channel: channel] : param
    def uri = "https://${src.host}:${src.port}/cgi-bin/api.cgi?cmd=${cmd}&token=${token}"
    def body = [[cmd: cmd, action: 0, param: p]]
    def result = null
    try {
        httpPost([uri: uri, ignoreSSLIssues: true, requestContentType: "application/json",
                  body: groovy.json.JsonOutput.toJson(body), timeout: 10]) { resp -> result = parseReolinkResponse(resp) }
        def value = firstResultValue(result, src)
        def rspCode = result?.getAt(0)?.error?.rspCode
        if (value == null) {
            logFull "Reolink source ${sourceId}: ${cmd} (ch ${channel}) HTTP ok but no usable value -- raw: ${result?.toString()?.take(300)}"
        } else {
            logFull "Reolink source ${sourceId}: ${cmd} (ch ${channel}) succeeded"
        }
        markSourceReachable(sourceId)
        return [value: value, rspCode: rspCode, parseFailure: false]
    } catch (groovy.json.JsonException e) {
        // Distinguished from other exceptions below -- this specific exception
        // type means the HTTP call itself succeeded but the body wasn't valid
        // JSON (the known older-firmware garbled-response bug, not a
        // connectivity issue). Always logged, not suppressed -- it's
        // intermittent by nature rather than a sustained outage, so it
        // doesn't produce the same repeated-spam problem, and each
        // occurrence is genuinely useful for the Tips-page troubleshooting.
        log.warn "Reolink cmd ${cmd} failed for source ${sourceId} ch ${channel}: ${e.message}"
        return [value: null, rspCode: null, parseFailure: true]
    } catch (e) {
        markSourceUnreachable(sourceId, "cmd ${cmd} (ch ${channel}) failed: ${e.message}")
        return [value: null, rspCode: null, parseFailure: false]
    }
}

// ---------- Discovery ----------

def discoverChannels(sourceId) {
    def src = getSource(sourceId)
    def channels = []
    state.lastDiscoveryError = null

    // One GetAbility call per source covers ALL channels at once (the response
    // includes an abilityChn[] array indexed by channel) -- confirmed against
    // real hardware, so this is NOT called again per-channel below.
    def abilityChnList = fetchAbilityChnList(sourceId)

    if (!src.isHub) {
        def info = reolinkApiCall(sourceId, "GetDevInfo")
        if (info == null) {
            state.lastDiscoveryError = "No response from ${src.host}. If this is a battery-class " +
                "camera or doorbell (not PoE/plug-in WiFi), it may not run a local HTTP/ONVIF " +
                "server at all -- those typically only become reachable once paired to a Home Hub or NVR."
            return channels
        }
        channels << [channel: 0, name: info?.DevInfo?.name ?: src.label, deviceType: guessDeviceType(info),
            isBattery: guessIsBattery(sourceId, 0),
            supportedFeatures: computeSupportedFeatures(abilityChnList?.getAt(0))]
    } else {
        def status = reolinkApiCall(sourceId, "GetChannelstatus")
        if (status == null) {
            state.lastDiscoveryError = "No response from ${src.host}. Check IP/credentials."
            return channels
        }
        status?.status?.each { ch ->
            if (ch.online) {
                channels << [channel: ch.channel, name: ch.name ?: "Channel ${ch.channel}", deviceType: guessChannelDeviceType(ch),
                    isBattery: guessIsBattery(sourceId, ch.channel),
                    supportedFeatures: computeSupportedFeatures(abilityChnList?.getAt(ch.channel as Integer))]
            }
        }
    }
    return channels
}

/**
 * Standalone-source detection (GetDevInfo shape, has a real model field).
 * Unchanged from prior versions -- confirmed reliable against every
 * standalone camera/doorbell tested so far.
 */
private String guessDeviceType(info) {
    def model = (info?.DevInfo?.model ?: info?.model ?: "").toLowerCase()
    return model.contains("doorbell") ? "doorbell" : "camera"
}

/**
 * Hub/NVR-channel detection. FIXED in v1.3.6: this previously called
 * guessDeviceType(ch) using the SAME model-field check as standalone
 * sources -- but GetChannelstatus (the Hub/NVR API) never returns a model
 * field at all, only GetDevInfo (the standalone API) does. That meant
 * every single Hub/NVR-relayed channel silently fell through to "camera",
 * always, regardless of what the device actually was. Confirmed in the
 * field: a doorbell channel named "Doorbell" behind a Home Hub Pro showed
 * up labeled "(camera)" on the discover page.
 *
 * This isn't just a display bug -- deviceType also selects the DRIVER at
 * creation time (createSelectedChildren()), so a new doorbell discovered
 * behind a Hub/NVR would have been created with the Reolink Camera driver
 * instead of Reolink Doorbell, silently missing PushableButton/the visitor
 * (ring) event entirely.
 *
 * Fix: since GetChannelstatus has no model field, use the channel's own
 * name instead -- Reolink's own Hub/NVR channel naming already reflects
 * the device type (confirmed: a paired doorbell channel is named
 * "Doorbell" by default). Falls back to "camera" only when the name gives
 * no signal either way, same safe default as before, but now actually
 * checking for the doorbell case first instead of skipping it entirely.
 */
private String guessChannelDeviceType(ch) {
    def name = (ch?.name ?: "").toLowerCase()
    return name.contains("doorbell") ? "doorbell" : "camera"
}

/**
 * Battery vs wired isn't reported directly by GetDevInfo/GetChannelstatus, so
 * this uses GetBatteryInfo as a signal instead: a battery-class device
 * answers it with real data, a wired/PoE device returns nothing usable.
 * TODO: GetAbility's battery/batAnalysis fields are a candidate to replace
 * this once tested against a real battery device -- every camera tested so
 * far (7 across 5 models) is wired, so battery/batAnalysis have only ever
 * shown permit:0, an unconfirmed negative case. guessIsBattery() remains the
 * source of truth for batteryMode until that's verified against real
 * hardware, to avoid swapping a working heuristic for an untested one.
 */
private Boolean guessIsBattery(sourceId, channel) {
    def batt = reolinkApiCall(sourceId, "GetBatteryInfo", [:], channel)
    return batt != null
}

/**
 * Fetches GetAbility for a source and returns the abilityChn[] array (one
 * entry per channel, index-aligned with the channel number). Returns null on
 * failure or an unexpected response shape -- callers must handle that by
 * falling back to an empty/unknown feature set, not by failing discovery
 * entirely, since capability detection is informational and should never
 * block a device from being creatable.
 *
 * Confirmed against real hardware (7 cameras, 5 models, firmware 2021-2024):
 * a single GetAbility call with no channel param returns ALL channels' data
 * at once under value.Ability.abilityChn[] -- calling it per-channel would
 * be redundant, every call returns the same full array regardless of any
 * channel param.
 */
private List fetchAbilityChnList(sourceId) {
    def src = getSource(sourceId)
    def result = reolinkApiCall(sourceId, "GetAbility", [User: [userName: src?.username]])
    def abilityChn = result?.Ability?.abilityChn
    if (!(abilityChn instanceof List)) {
        logFull "Reolink source ${sourceId}: GetAbility did not return the expected " +
            "Ability.abilityChn[] shape -- capability detection unavailable for this source"
        return null
    }
    return abilityChn
}

/**
 * Safe lookup: treats a missing key the SAME as unsupported. Confirmed
 * necessary -- older firmware omits some keys entirely rather than
 * reporting them as unsupported (e.g. supportPtzCalibration was entirely
 * absent on a 2021-firmware camera that had it present in 2024 firmware on
 * the same model).
 *
 * Checks BOTH the "permit" and "ver" sub-fields, not permit alone. Every
 * GetAbility entry has both. Reolink's own officially-backed reolink_aio
 * library checks "ver" exclusively; we'd been checking "permit" exclusively.
 * These move together on every field tested EXCEPT ptzType, where permit
 * stays 0 even on confirmed PTZ cameras while ver correctly shows nonzero --
 * we avoided that specific divergence by keying PTZ presence off ptzCtrl
 * instead of ptzType, but there's no guarantee some other field doesn't
 * diverge the same way on hardware not yet tested. Checking both costs
 * nothing and closes off that whole risk class.
 */
private int abilityPermit(Map abilityChn, String key) {
    def entry = abilityChn?.getAt(key)
    def permit = (entry?.permit ?: 0) as int
    def ver = (entry?.ver ?: 0) as int
    return Math.max(permit, ver)
}

/**
 * Maps GetAbility data to a human-readable feature list for the
 * supportedFeatures device attribute. Confirmed against real hardware across
 * 8+ cameras / 6+ models / firmware 2021-2024, cross-checked against
 * Reolink's own officially-backed reolink_aio library:
 *   - PTZ: ptzCtrl > 0. The exact value varies by camera (1 on a
 *     pan-tilt-only E1 Pro, 7 on full PTZ cameras) but >0 vs 0 reliably
 *     splits PTZ-capable from fixed cameras every time tested.
 *   - PTZ Calibration: supportPtzCheck > 0 OR supportPtzCalibration > 0 --
 *     confirmed a false negative on supportPtzCalibration alone (real-world
 *     contradicted it), switched supportPtzCheck to primary. This EXACT OR
 *     pattern is independently confirmed in reolink_aio's own capability
 *     logic.
 *   - Spotlight: supportFLswitch > 0 OR floodLight > 0. Camera-only --
 *     confirmed no camera or doorbell tested actually maps a true
 *     spotlight/floodlight to supportDoorbellLight (see Night Vision /
 *     Status LED below for what that key actually means on a doorbell).
 *     floodLight alone stayed 0 on every device tested including confirmed
 *     spotlight-equipped ones (independently corroborated by a Reolink
 *     community report of floodLight being unreliable even on genuine
 *     floodlight hardware) but reolink_aio's own logic ORs it in alongside
 *     supportFLswitch, so it's kept as a defensive fallback in case some
 *     future device reports it correctly.
 *   - Night Vision (IR): ledControl > 0 -- field found via reolink_aio,
 *     previously unmapped. Confirmed against a real doorbell that has an IR
 *     light but no spotlight.
 *   - Status LED: supportDoorbellLight > 0 -- CORRECTED. Originally (v1.3.1)
 *     folded into the Spotlight gate based on real hardware showing it
 *     nonzero on a doorbell with a light -- but that doorbell's only lights
 *     are IR (night vision) and a button/ring status light, no true
 *     spotlight. Confirmed via reolink_aio that this key governs the
 *     status/ring indicator LED (grouped with powerLed/indicatorLight in
 *     their code), not illumination. Split into its own feature.
 *   - Siren: alarmAudio > 0. Every camera tested had this until an older
 *     basic model (RLC-410W, no built-in speaker) finally gave a real
 *     negative case (permit:0, "talk" key absent entirely) -- confirms this
 *     field is trustworthy, not just uniformly present by coincidence.
 *   - Person / Vehicle: supportAiPeople / supportAiVehicle > 0.
 *   - Pet: supportAiDogCat OR supportAiAnimal > 0 -- field NAME varies by
 *     firmware (older firmware uses one, newer sometimes reports both
 *     simultaneously), so both are checked rather than picking one.
 *   - Package: supportAiPackage > 0 -- confirmed against a real doorbell
 *     (package detection is doorbell-specific; cameras don't have it at all,
 *     which is why no camera tested ever showed this key). Still matched via
 *     a defensive "ackage"-substring scan rather than the literal key name,
 *     since that already caught the real name correctly and costs nothing
 *     to leave in place as a safety net for any differently-named variant.
 *   - Basic/older models (e.g. RLC-410W) can be missing entire FAMILIES of
 *     keys (no AI keys at all, predating that feature) -- abilityPermit()'s
 *     missing-key-as-0 handling covers this correctly.
 *
 * Deliberately NOT included: battery status (see guessIsBattery() TODO,
 * unconfirmed against real hardware), NVR/Home Hub channel-specific ability
 * behavior (untested -- everything confirmed so far is a directly-connected
 * standalone camera).
 */
private List<String> computeSupportedFeatures(Map abilityChn) {
    if (abilityChn == null) return []
    def features = []
    if (abilityPermit(abilityChn, "ptzCtrl") > 0) features << "PTZ"
    if (abilityPermit(abilityChn, "supportPtzCheck") > 0 || abilityPermit(abilityChn, "supportPtzCalibration") > 0) {
        features << "PTZ Calibration"
    }
    if (abilityPermit(abilityChn, "supportFLswitch") > 0 || abilityPermit(abilityChn, "floodLight") > 0) {
        features << "Spotlight"
    }
    if (abilityPermit(abilityChn, "ledControl") > 0) features << "Night Vision"
    if (abilityPermit(abilityChn, "supportDoorbellLight") > 0) features << "Status LED"
    if (abilityPermit(abilityChn, "alarmAudio") > 0) features << "Siren"
    if (abilityPermit(abilityChn, "supportAiPeople") > 0) features << "Person Detection"
    if (abilityPermit(abilityChn, "supportAiVehicle") > 0) features << "Vehicle Detection"
    if (abilityPermit(abilityChn, "supportAiDogCat") > 0 || abilityPermit(abilityChn, "supportAiAnimal") > 0) {
        features << "Pet Detection"
    }
    def packageKey = abilityChn.keySet().find { it.toLowerCase().contains("ackage") }
    if (packageKey && abilityPermit(abilityChn, packageKey) > 0) features << "Package Detection"
    return features
}

// ---------- Child creation ----------

private String childDni(sourceId, channel) {
    "reolink-${sourceId}-${channel}"
}

def createSelectedChildren(sourceId) {
    (state.lastDiscovery ?: []).each { ch ->
        def wantIt = settings["create_${sourceId}_${ch.channel}"]
        def dni = childDni(sourceId, ch.channel)
        def existing = getChildDevice(dni)
        if (wantIt && !existing) {
            def driverName = ch.deviceType == "doorbell" ? "Reolink Doorbell" : "Reolink Camera"
            def child = addChildDevice("jdthomas24", driverName, dni, [
                name: ch.name, label: ch.name, isComponent: true
            ])
            child.updateDataValue("sourceId", "${sourceId}")
            child.updateDataValue("channel", "${ch.channel}")
            // Poll interval is device-only, configurable ONLY on the device page (or via
            // setPollInterval) -- these are just the one-time defaults applied at creation.
            def pollDefault = ch.isBattery ? DEFAULT_BATTERY_POLL_SEC : DEFAULT_WIRED_POLL_SEC
            child.updateSetting("pollIntervalSec", [type: "number", value: pollDefault])
            child.receiveSupportedFeatures(ch.supportedFeatures ?: [])
            logNormal "Created child ${dni} (${driverName}), poll interval defaulted to ${pollDefault}s (${ch.isBattery ? 'battery' : 'wired'}), features: ${ch.supportedFeatures ? ch.supportedFeatures.join(', ') : 'none detected'}"
        } else if (!wantIt && existing) {
            deleteChildDevice(dni)
            forgetSchedulingState(dni)
            logNormal "Removed child ${dni} (unchecked in discovery list)"
        }
    }
    initializePolling()
}

// ---------- Polling ----------

def installed() { initialize() }
def updated() { initialize() }

/**
 * Ensures polling resumes automatically after a hub reboot. Hubitat does not
 * guarantee runIn schedules survive a restart on their own, and nothing else
 * in this app gets called on boot -- without this, a hub reboot could leave
 * every camera silently un-polled until someone happened to open the app and
 * hit Done/Update, with no error or indication anything was wrong.
 */
def systemStartHandler(evt) {
    logNormal "Reolink Integration: hub restarted, resuming polling"
    initialize()
}

def initialize() {
    unschedule()
    unsubscribe()
    subscribe(location, "systemStart", "systemStartHandler")
    if (!state.accessToken) {
        try {
            createAccessToken()
            logNormal "Access token created for local snapshot relay endpoint"
        } catch (e) {
            log.warn "Reolink Integration: could not create access token (needed for dashboard snapshot tiles) -- ${e.message}. " +
                "If this persists, check that OAuth is enabled for this app under Apps Code."
        }
    }
    initializePolling()
    if (logLevel == "Full") {
        runIn(3600, "revertToNormalLogging")
    }
}

/** Auto-reverts Full back to Normal after 60 minutes -- Full is meant for actively chasing something, not a steady state. Errors Only and Normal have no timer. */
def revertToNormalLogging() {
    app.updateSetting("logLevel", [type: "enum", value: "Normal"])
    log.info "Reolink Integration: log level auto-reverted from Full to Normal after 60 minutes"
}

/**
 * Backward-compat stub for the pre-1.2.5 debugLogging system's scheduled
 * callback name. An install that had the old "Enable debug logging" toggle
 * on before updating to 1.2.5 would have a runIn(5400, "disableDebugLogging")
 * job already pending on the hub -- Hubitat's scheduler persists jobs by
 * method name independently of the code text, so updating the code (which
 * renamed this method to revertToNormalLogging()) left that stale job
 * pointing at a name that no longer existed, throwing
 * MissingMethodExceptionNoStack when it fired. This stub just gives that
 * leftover job somewhere safe to land instead of erroring; it has no other
 * purpose and nothing schedules a job under this name going forward.
 */
def disableDebugLogging() {
    log.info "Reolink Integration: leftover pre-1.2.5 logging job fired, no action needed (see disableDebugLogging() comment)"
}

def initializePolling() {
    // Seed every child so it's due immediately on the first tick, then start
    // (or restart) the single central scheduler.
    def now = now()
    def pollDue = state.nextPollDue ?: [:]
    def snapDue = state.nextSnapshotDue ?: [:]
    getChildDevices().each { child ->
        def dni = child.deviceNetworkId
        if (!pollDue.containsKey(dni)) pollDue[dni] = now
        if (!snapDue.containsKey(dni)) snapDue[dni] = now
    }
    state.nextPollDue = pollDue
    state.nextSnapshotDue = snapDue
    runIn(1, "schedulerTick", [overwrite: true])
}

/**
 * Single central scheduler, replacing the old per-device runIn(interval,
 * "pollChild", [data: [dni: ...], overwrite: true]) pattern.
 *
 * That pattern was broken: every device's call shared the SAME handler name
 * ("pollChild"), and Hubitat's overwrite: true cancels ANY pending call to
 * that handler, not just the calling device's own previous one. With 4
 * cameras, whichever device happened to (re)schedule last silently canceled
 * every other device's pending timer -- no error, nothing in the logs, they
 * just stopped polling. Found in the field: only 1 of 4 cameras was still
 * polling, the other 3 had gone silent since before this session started.
 * Same bug applied to pollChildSnapshot().
 *
 * The fix: exactly ONE recurring timer exists for the whole app (this
 * method, ticking every second), and each device's own due-time is tracked
 * independently in state (nextPollDue / nextSnapshotDue, keyed by DNI).
 * Nothing here can ever cancel another device's schedule, because there is
 * only one schedule.
 *
 * Two more robustness measures, since this single tick is now the ONE thing
 * every device's polling depends on -- a failure here has a much bigger
 * blast radius than the old per-device timers did, so it can't be allowed to
 * silently die:
 *  1. Each device is processed in its own try/catch. One device throwing
 *     (bad API response, unexpected null, etc.) logs a warning and moves on
 *     instead of aborting the whole tick and silently stopping every camera.
 *  2. The next tick is re-armed in a finally block, so even an unexpected
 *     failure outside the per-device loop still can't prevent the scheduler
 *     from continuing to run.
 */
def schedulerTick() {
    try {
        def nowMs = now()
        def pollDue = state.nextPollDue ?: [:]
        def snapDue = state.nextSnapshotDue ?: [:]

        getChildDevices().each { child ->
            def dni = child.deviceNetworkId
            try {
                if (nowMs >= ((pollDue[dni] ?: 0) as Long)) {
                    pollChildNow(child)
                    def interval = (child.getSetting("pollIntervalSec") ?: 30) as Integer
                    pollDue[dni] = nowMs + (interval * 1000L)
                }
                if (nowMs >= ((snapDue[dni] ?: 0) as Long)) {
                    pollChildSnapshotNow(child)
                    def sInterval = (child.getSetting("snapshotIntervalSec") ?: 30) as Integer
                    snapDue[dni] = nowMs + (sInterval * 1000L)
                }
            } catch (e) {
                log.warn "Reolink Integration: schedulerTick() failed for device ${dni} -- ${e.message}. Skipping this device this tick, will retry next tick."
                // Push this device's due time forward by its own interval anyway, so a
                // persistently failing device can't get retried every single tick forever.
                def interval = (child.getSetting("pollIntervalSec") ?: 30) as Integer
                pollDue[dni] = nowMs + (interval * 1000L)
            }
        }

        state.nextPollDue = pollDue
        state.nextSnapshotDue = snapDue
    } catch (e) {
        log.warn "Reolink Integration: schedulerTick() failed outside the per-device loop -- ${e.message}"
    } finally {
        runIn(1, "schedulerTick", [overwrite: true])
    }
}

/** Marks a device due on the very next tick (within ~1s) -- used after a poll-interval change so it takes effect immediately rather than waiting out the old interval. */
private markPollDueNow(String dni) {
    def pollDue = state.nextPollDue ?: [:]
    pollDue[dni] = now()
    state.nextPollDue = pollDue
}

/** See markPollDueNow() -- same idea for the snapshot schedule. */
private markSnapshotDueNow(String dni) {
    def snapDue = state.nextSnapshotDue ?: [:]
    snapDue[dni] = now()
    state.nextSnapshotDue = snapDue
}

def pollChild(data) {
    def child = getChildDevice(data.dni)
    if (!child) return
    pollChildNow(child)
    markPollDueNow(child.deviceNetworkId)
}

/**
 * Checks the child's CURRENT sleepStatus before logging, so "marking asleep"/
 * "marking awake" only hits Normal-tier logging on a real transition -- same
 * idea as the drivers' sendIfChanged(), applied to log lines instead of
 * sendEvent() calls. A device that's already asleep and stays asleep (or
 * already awake and stays awake) only logs at Full tier, since that's routine
 * and not worth surfacing by default.
 */
private void pollChildNow(child) {
    def sourceId = child.getDataValue("sourceId") as Integer
    def channel = child.getDataValue("channel") as Integer

    def aiState = reolinkApiCall(sourceId, "GetAiState", [:], channel)
    def mdState = reolinkApiCall(sourceId, "GetMdState", [:], channel)
    def wasAsleep = child.currentValue("sleepStatus") == "asleep"

    if (aiState == null && mdState == null) {
        if (wasAsleep) {
            logFull "Reolink source ${sourceId} ch ${channel}: still no response, still asleep"
        } else {
            logNormal "Reolink source ${sourceId} ch ${channel}: no response, marking asleep"
        }
        child.markAsleep()
    } else {
        if (wasAsleep) {
            logNormal "Reolink source ${sourceId} ch ${channel}: response received, marking awake"
        } else {
            logFull "Reolink source ${sourceId} ch ${channel}: response received (still awake)"
        }
        child.parseReolinkState(aiState, mdState)
    }
}

/**
 * Snapshot caching runs on its OWN schedule (nextSnapshotDue, see
 * schedulerTick() above), separate from AI/motion polling (nextPollDue).
 * Motion detection benefits from being fast (a few seconds); a dashboard
 * image does not need to be refreshed nearly that often, and pulling a full
 * JPEG every few seconds across several cameras against this app's
 * singleThreaded execution model risks the exact semaphore/queueing problem
 * the caching redesign was meant to fix in the first place. Defaults to a
 * much looser interval than the poll interval.
 */
def pollChildSnapshot(data) {
    def child = getChildDevice(data.dni)
    if (!child) return
    pollChildSnapshotNow(child)
    markSnapshotDueNow(child.deviceNetworkId)
}

private void pollChildSnapshotNow(child) {
    def sourceId = child.getDataValue("sourceId") as Integer
    def channel = child.getDataValue("channel") as Integer
    cacheSnapshot(child, sourceId, channel)
}

/**
 * Fetches a fresh snapshot and writes it to local hub file storage, keyed by
 * device DNI. This is the ONLY place that hits the camera for a snapshot --
 * the dashboard-facing relay endpoint (handleSnapshotRequest) just serves
 * whatever's cached here, instantly, with no camera round-trip in the
 * request path. That's what keeps a busy dashboard (multiple tiles, fast
 * refresh) from backing up this app's single-threaded execution queue.
 *
 * TODO: verify uploadHubFile()/downloadHubFile() byte-array signatures and
 * any file size ceiling against your hub's actual platform version -- these
 * are standard Hubitat File Manager APIs (2.2.8+) but haven't been tested
 * against real hardware in this codebase yet.
 */
private void cacheSnapshot(child, sourceId, channel) {
    def src = getSource(sourceId)
    if (!src) return
    def imageBytes = fetchSnapshotBytes(src, sourceId, channel)
    if (imageBytes == null) {
        logNormal "Reolink source ${sourceId} ch ${channel}: snapshot cache refresh failed, keeping last cached image (if any)"
        return
    }
    try {
        uploadHubFile(snapshotFileName(child.deviceNetworkId), imageBytes)
        // Deliberately not logging the success case -- with snapshot caching
        // running on its own recurring schedule, a line every cycle adds up
        // to real log noise for no diagnostic benefit. Failures above and
        // below are still logged, since those are the cases worth seeing.
    } catch (e) {
        log.warn "Reolink source ${sourceId} ch ${channel}: failed to write snapshot to hub file storage -- ${e.message}"
    }
}

private String snapshotFileName(dni) {
    "reolink-snap-${dni}.jpg"
}

/**
 * Resolves a proper app-side child device reference via getChildDevice(),
 * given a dni passed explicitly from the driver (device.deviceNetworkId).
 * Falls back to the raw passed reference only for callers that haven't been
 * updated to pass dni yet.
 *
 * Why this exists: a driver's raw "this" reference does not reliably support
 * every platform-provided device method/property when called externally by
 * this app. Confirmed broken so far: deviceNetworkId (property) and
 * updateSetting() (method, throws MissingMethodException). Confirmed working
 * in the field: getDataValue() and user-defined driver methods like
 * receiveSnapshotUrl()/receiveBatteryInfo() (ordinary Groovy method dispatch,
 * not platform-injected). Rather than track which specific calls happen to
 * work on the raw reference, every componentX callback now resolves through
 * here whenever a dni is available, closing off the whole risk class at once.
 */
private resolveChild(child, String dni) {
    def effectiveDni = dni ?: child?.deviceNetworkId
    return effectiveDni ? (getChildDevice(effectiveDni) ?: child) : child
}

// ---------- Component callbacks (children call these via parent.X()) ----------

/** dni passed explicitly (device.deviceNetworkId from the driver) -- see resolveChild(). */
def componentRefresh(child, String dni = null) {
    def effectiveDni = dni ?: child?.deviceNetworkId
    if (!effectiveDni) {
        log.warn "Reolink Integration: componentRefresh() called with a device that has no deviceNetworkId"
        return
    }
    pollChild([dni: effectiveDni])
}

/**
 * Builds the dashboard-facing snapshot URL and, since the person explicitly
 * asked for a snapshot right now, immediately refreshes the cached image
 * rather than waiting for the next poll cycle. The URL itself points at this
 * app's local relay endpoint (see mappings + handleSnapshotRequest() below),
 * which serves the cached file directly -- no camera round-trip happens on
 * the dashboard's own refresh timer, only here and during normal polling.
 *
 * dni is passed explicitly by the driver (via its own device.deviceNetworkId)
 * rather than read off child.deviceNetworkId. Found in the field: a driver's
 * "this" reference reliably exposes methods like getDataValue(), but does NOT
 * reliably expose deviceNetworkId as a property when passed into another app's
 * method this way -- child?.deviceNetworkId was silently coming back null,
 * building a broken "/snap/null" URL. child.deviceNetworkId is kept as a
 * fallback only for callers that haven't been updated to pass dni explicitly.
 */
def componentTakeSnapshot(child, String dni = null) {
    def effectiveDni = dni ?: child?.deviceNetworkId
    if (!effectiveDni) {
        log.warn "Reolink Integration: componentTakeSnapshot() called with a device that has no deviceNetworkId, refusing to build a snapshot URL"
        return
    }
    if (!state.accessToken) {
        try {
            createAccessToken()
        } catch (e) {
            log.warn "Reolink Integration: no access token available, snapshot relay endpoint will not work -- ${e.message}"
            return
        }
    }
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    cacheSnapshot(c, sourceId, channel)
    def url = "${getFullLocalApiServerUrl()}/snap/${effectiveDni}?access_token=${state.accessToken}"
    logNormal "Reolink ${effectiveDni}: snapshot URL built (local relay endpoint, cache refreshed on demand)"
    c.receiveSnapshotUrl(url)
}

/**
 * Handler for GET /snap/:dni?access_token=... -- called by the browser every
 * time a dashboard image tile refreshes. Serves whatever's currently cached
 * in local hub file storage for this device (kept fresh by cacheSnapshot(),
 * called from pollChild() and from componentTakeSnapshot()). Deliberately
 * does NOT talk to the camera itself -- doing that on every request is what
 * caused the semaphore/queueing problem in 1.2.2 when multiple dashboard
 * tiles refresh often against this app's singleThreaded execution model.
 */
def handleSnapshotRequest() {
    def dni = params?.dni
    if (!dni || dni == "null") {
        log.warn "Reolink Integration: snapshot endpoint hit with no/null device id, this URL is stale -- run takeSnapshot again to regenerate it"
        render status: 400, data: "Missing or stale device id, run takeSnapshot again to regenerate this URL", contentType: "text/plain"
        return
    }
    def child = getChildDevice(dni)
    if (!child) {
        render status: 404, data: "Unknown device: ${dni}", contentType: "text/plain"
        return
    }

    byte[] cached = null
    try {
        cached = downloadHubFile(snapshotFileName(dni))
    } catch (e) {
        logFull "Reolink Integration: no cached snapshot yet for ${dni} -- ${e.message}"
    }
    if (!cached || cached.length == 0) {
        render status: 404, data: "No snapshot cached yet for this device -- wait for the next poll cycle or run takeSnapshot", contentType: "text/plain"
        return
    }
    render contentType: "image/jpeg", data: cached
}

/** Fetches a live snapshot, retrying once with a forced fresh login on auth failure. */
private byte[] fetchSnapshotBytes(src, sourceId, channel) {
    def token = reolinkLogin(sourceId)
    def bytes = doFetchSnapshot(src, sourceId, token, channel)
    if (bytes == null) {
        logFull "Reolink source ${sourceId} ch ${channel}: snapshot fetch failed, forcing re-login and retrying once"
        src.token = null
        src.tokenExpires = 0
        def freshToken = reolinkLogin(sourceId)
        if (freshToken) {
            bytes = doFetchSnapshot(src, sourceId, freshToken, channel)
        }
    }
    return bytes
}

/**
 * Low-level Snap GET. On success the camera returns raw JPEG bytes as an
 * InputStream on resp.data -- NOT something with a usable .bytes property,
 * so it has to be drained explicitly (this was the 1.2.2 bug: relying on
 * resp.data.bytes silently produced null/empty and rendered as a blank
 * white page instead of an error). On failure (e.g. rspCode -6) the camera
 * returns a small JSON error payload instead, detected via content-type.
 */
private byte[] doFetchSnapshot(src, sourceId, token, channel) {
    def uri = "https://${src.host}:${src.port}/cgi-bin/api.cgi?cmd=Snap&channel=${channel}&token=${token}"
    byte[] result = null
    try {
        httpGet([uri: uri, ignoreSSLIssues: true, timeout: 10]) { resp ->
            def ct = resp?.contentType?.toString()?.toLowerCase() ?: ""
            if (ct.contains("json")) {
                def raw = resp?.data?.toString()
                logNormal "Reolink source ${sourceId} ch ${channel}: snapshot request returned JSON instead of an image -- ${raw?.take(300)}"
            } else if (resp?.data != null) {
                def bos = new ByteArrayOutputStream()
                bos << resp.data
                result = bos.toByteArray()
                if (!result || result.length == 0) {
                    logNormal "Reolink source ${sourceId} ch ${channel}: snapshot stream drained to 0 bytes"
                    result = null
                }
            }
        }
    } catch (e) {
        markSourceUnreachable(sourceId, "snapshot fetch (ch ${channel}) failed: ${e.message}")
    }
    return result
}

def componentPtz(child, String direction, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "PtzCtrl", [op: direction, speed: 32], channel)
}

def componentPtzGoToPreset(child, Integer presetId, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "PtzCtrl", [op: "ToPos", id: presetId, speed: 32], channel)
}

def componentSavePreset(child, Integer presetId, String name, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "SetPtzPreset",
        [PtzPreset: [channel: channel, enable: 1, id: presetId, name: name ?: "Preset${presetId}"]], null)
}

def componentSetSpotlight(child, Boolean on, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "SetWhiteLed", [WhiteLed: [channel: channel, state: (on ? 1 : 0)]], null)
}

def componentSetNightVision(child, String mode, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "SetIrLights", [IrLights: [channel: channel, state: mode]], null)
}

def componentSetSiren(child, Boolean on, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "AudioAlarmPlay", [alarm_mode: "manul", manual_switch: (on ? 1 : 0), times: 2], channel)
}

def componentCheckBattery(child, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    def battInfo = reolinkApiCall(sourceId, "GetBatteryInfo", [:], channel)
    c.receiveBatteryInfo(battInfo)
}

/**
 * Manual recheck for a single device's supportedFeatures attribute -- useful
 * after a firmware update that might add capabilities, or if the device was
 * created before this feature existed. Re-fetches GetAbility fresh rather
 * than relying on anything cached from the original discovery.
 */
def componentCheckAbilities(child, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    def abilityChnList = fetchAbilityChnList(sourceId)
    def features = computeSupportedFeatures(abilityChnList?.getAt(channel))
    c.receiveSupportedFeatures(features)
    logNormal "Reolink source ${sourceId} ch ${channel}: capabilities rechecked -- ${features ? features.join(', ') : 'none detected'}"
}

def componentCalibratePtz(child, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "PtzCheck", [:], channel)
    logNormal "Reolink source ${sourceId} ch ${channel}: PTZ calibration triggered"
}

def componentCheckPtzCalibrationStatus(child, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    def result = reolinkApiCall(sourceId, "GetPtzCheckState", [:], channel)
    def state = result?.PtzCheckState
    logNormal "Reolink source ${sourceId} ch ${channel}: PTZ calibration state = ${state}"
    c.receivePtzCalibrationState(state)
}

/** dni resolved via resolveChild() -- see that method's doc comment for why. */
def componentSetPollInterval(child, Integer seconds, String dni = null) {
    def c = resolveChild(child, dni)
    if (!c) {
        log.warn "Reolink Integration: componentSetPollInterval() could not resolve a device"
        return
    }
    c.updateSetting("pollIntervalSec", [type: "number", value: seconds])
    logNormal "Set poll interval for ${c.deviceNetworkId ?: dni} to ${seconds}s"
    if (c.deviceNetworkId) markPollDueNow(c.deviceNetworkId)
}

/** dni resolved via resolveChild() -- see that method's doc comment for why. */
def componentSetSnapshotInterval(child, Integer seconds, String dni = null) {
    def c = resolveChild(child, dni)
    if (!c) {
        log.warn "Reolink Integration: componentSetSnapshotInterval() could not resolve a device"
        return
    }
    c.updateSetting("snapshotIntervalSec", [type: "number", value: seconds])
    logNormal "Set snapshot interval for ${c.deviceNetworkId ?: dni} to ${seconds}s"
    if (c.deviceNetworkId) markSnapshotDueNow(c.deviceNetworkId)
}

// ---------- Logging ----------

/** Rank of the current logLevel setting within LOG_LEVELS (0=Errors Only, 1=Normal, 2=Full). Defaults to Normal if unset/unrecognized. */
private int logLevelRank() {
    def idx = LOG_LEVELS.indexOf(logLevel ?: "Errors Only")
    return idx < 0 ? 0 : idx
}

/** Logs at Normal tier and above (Normal, Full). Meaningful one-time events and state transitions -- not routine unchanged polls. */
private void logNormal(msg) {
    if (logLevelRank() >= 1) log.debug msg
}

/** Logs only at Full tier. Routine poll-by-poll detail -- token reuse, individual API calls succeeding, unchanged state repeats. */
private void logFull(msg) {
    if (logLevelRank() >= 2) log.debug msg
}
