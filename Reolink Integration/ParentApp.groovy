/**
 * Reolink Integration (Parent App)
 * Version: 1.3.9
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
 *  - Each source is fronted by a "Reolink Device Bridge" device (one per
 *    source). The bridge holds the persistent real-time event subscription
 *    AND is the real parent that creates Camera/Doorbell as its own children,
 *    so they nest under the bridge in the Devices list. Event-driven updates
 *    are the standard path; a source falls back to polling automatically if
 *    its event connection can't be established or drops, and resumes event
 *    mode silently on reconnect.
 *
 * Device-specific findings, known limitations, and setup gotchas are documented
 * in the README and the in-app Tips page -- not duplicated here to avoid the
 * two drifting out of sync.
 *
 * TODO markers throughout mark spots that need exact command/param names
 * verified against your firmware's API guide (GetMdState / GetAiState /
 * GetChannelstatus / Snap / PtzCtrl / SetPirInfo field names can drift by
 * firmware version).
 *
 * Full version history prior to 1.3.6 is in the GitHub commit history and
 * past release notes -- not duplicated here.
 *
 * ============================================================================
 * BREAKING CHANGE NOTICE (v1.3.8): this release restructures how child
 * devices are organized -- every camera/doorbell is now created as a child
 * of a "Reolink Device Bridge" device (one per source) instead of a child of
 * this app directly. This is required to support the new event-driven
 * update path and to get proper nesting/grouping in the Devices list.
 * EXISTING INSTALLS MUST delete their current camera/doorbell devices and
 * re-run discovery under each source to recreate them under the new bridge.
 * Dashboards, Rule Machine rules, and dashboard tiles that reference the old
 * device IDs will need to be repointed at the newly created devices. See the
 * forum release notes for step-by-step upgrade instructions.
 * ============================================================================
 *
 * v1.3.9 -- post-release fixes and clarity improvements from real-world use
 * behind a 23-channel NVR:
 *  1. FIXED: the discovery-time battery probe (guessIsBattery(), used to
 *     guess wired vs battery for a newly-added channel) was marking a
 *     source's whole connection "unreachable" whenever it failed on a
 *     wired camera -- which is the EXPECTED outcome for roughly half of all
 *     cameras, not a real connectivity problem. This produced a misleading
 *     warn immediately followed by "connection restored" once the next
 *     unrelated command succeeded, even though the source was never
 *     actually down. doReolinkApiCall() now takes a `quiet` flag; a quiet
 *     probe failure logs at Full tier only and never touches source-
 *     reachable state.
 *  2. FIXED: Check Battery could succeed (a real response came back) but
 *     the battery attribute never updated on the device page --
 *     CameraDriver.groovy's receiveBatteryInfo() was reading
 *     battInfo.batteryPercent flat, when the confirmed reolink_aio field
 *     (already documented in that same file's own comment) is nested under
 *     Battery.batteryPercent. Now checks the nested path first.
 *  3. NEW: the bridge's 25-second keepalive now logs a Full-tier heartbeat
 *     line each time it fires. With event-driven updates, a quiet source
 *     (nothing has triggered a real push in a while) could look identical
 *     in the logs whether it was working or silently stuck, even at Full --
 *     this gives a predictable "still alive" signal without reintroducing
 *     per-camera poll spam, since it's throttled to once per connection.
 *  4. Reverted the "action: 0" field on the Login request body (added in
 *     1.3.8, never confirmed against real hardware for Login specifically
 *     -- see reolinkLogin()). Initially suspected as the cause of a real
 *     rspCode:-7 "login failed" rejection seen across multiple production
 *     sources -- DISPROVEN by direct evidence: the same rejection recurred
 *     on a source tested WITHOUT this field. Reverted anyway since it was
 *     always unconfirmed and every other command's action:0 stays
 *     unaffected, but the actual login-rejection root cause turned out to
 *     be unrelated (see item 6).
 *  5. FIXED: ensureSourceBridge() could throw a raw
 *     DuplicateDNIException and crash the ENTIRE app page ("Unexpected
 *     Error") if a device with that bridge's DNI existed anywhere else on
 *     the hub but wasn't reachable through the two places a bridge is
 *     supposed to live (Hubitat enforces DNIs as globally unique across
 *     the whole hub, not just unique among one parent's children). Now
 *     catches that specific exception, logs a clear actionable warning
 *     telling the user exactly what DNI to search for and delete, and
 *     returns null so callers can handle a missing bridge gracefully
 *     instead of the whole page throwing.
 *  6. NEW: added an explicit uninstalled() that walks removeSource() for
 *     every known source on full app removal, instead of relying purely on
 *     Hubitat's own automatic cascade-delete of app-owned children -- see
 *     uninstalled() for the full reasoning. Root-caused (with reasonable
 *     confidence, not fully platform-verified) as the likely source of the
 *     orphaned-bridge DuplicateDNIException in item 5: the 1.3.8 bridge
 *     restructuring made the device tree 2-3 levels deep for the first
 *     time (previously flat, one level), which may not survive a full app
 *     removal as reliably under heavy/rapid reinstall churn.
 *
 * v1.3.8:
 *  1. NEW: real-time event-driven updates. Each source now maintains a
 *     persistent connection to the camera/Hub/NVR and receives motion/AI/
 *     visitor changes as they happen, instead of relying solely on polling.
 *     Falls back to polling automatically if the event connection can't be
 *     established or drops, and silently resumes event mode on reconnect.
 *     Per-source on/off toggle on the discover page (defaults on).
 *  2. NEW: PIR enable/disable for cameras -- pirOn()/pirOn() commands plus a
 *     pirEnabled attribute, so a battery camera's motion trigger can be
 *     paused without removing the device (e.g. via a Rule Machine rule tied
 *     to battery level). Scoped to cameras; doorbells unaffected.
 *  3. FIXED: a login/token bug where the "new token acquired" log line fired
 *     even when the Login response didn't actually contain a usable token
 *     (e.g. a bad password, or a response shape this app doesn't expect on
 *     some firmware) -- previously this looked like a successful login in
 *     the log while every subsequent API call silently failed with "no
 *     token available," repeating forever. The success log now only fires
 *     when a real token comes back; a failure now logs the raw response so
 *     the actual cause is visible instead of a misleading success line.
 *  4. FIXED: the Login API call was missing the "action" field that every
 *     other command in this app sends -- added for consistency, and because
 *     it's plausible some firmware is stricter about requiring it than
 *     others.
 *  5. Corruption-recovery logging for the event connection (magic-header
 *     resync) now stays at debug tier for both the routine and repeated-in-
 *     60s cases, since real-world soak testing confirmed this pattern is
 *     benign, self-recovering Hub-side noise under load, not a client bug.
 *     Still logs at warn for a buffer that fails to resync after 20
 *     attempts, and for a genuinely unrecognized message type.
 *  6. FIXED: the bridge device logged its own connection status and every
 *     routine event push directly to the hub log (log.info/log.debug),
 *     completely bypassing the app's Log level setting -- so switching the
 *     app to Errors Only did nothing to quiet the bridge, and Full
 *     reproduced the exact log-flooding pattern from BETA testing. The
 *     bridge now routes its logging through the app's existing
 *     logNormal()/logFull() tiers via parent?.logNormal(...)/
 *     parent?.logFull(...) -- connection-status transitions (starting,
 *     connected, reconnecting) are Normal tier, routine per-push/resync
 *     detail is Full tier, and genuine failures (socket errors, give-up-
 *     after-20-resync, decrypt failure, unrecognized message type) remain
 *     unconditional warnings regardless of log level, same as the rest of
 *     this app.
 *  7. NEW: standalone (non-Hub) cameras/doorbells now nest together under a
 *     shared "Reolink Standalone Devices" entry in the Devices list instead
 *     of each source's bridge appearing as its own separate, unnested
 *     entry -- matching how an NVR/Home Hub's channels already group under
 *     one bridge. Each standalone camera still holds its own independent
 *     event connection underneath (Hubitat's rawSocket interface is one-
 *     connection-per-driver-instance, so that part can't be shared) -- only
 *     where the bridge nests in the Devices list changed, not how many
 *     connections exist. See ensureStandaloneGroupDevice()/getSourceBridge().
 *
 * v1.3.6 -- Two related fixes to the device-discovery/toggle page
 * (discoverPage()), both found via real-world use:
 *  1. FIXED: unchecking an EXISTING device on a single-channel (standalone
 *     camera) source never actually deleted it. The auto-apply check only
 *     fired when the checkbox was TRUE -- unchecking an existing device sets
 *     that setting to FALSE, which never triggered the delete branch. Now
 *     fires on either direction: checking an absent device to create it, or
 *     unchecking a present one to remove it.
 *  2. Multi-channel (NVR/Home Hub) sources already applied removal
 *     correctly through the "Create selected devices" toggle, but the label
 *     only described creation -- relabeled to "Apply changes (create
 *     checked / remove unchecked)" and reworded the explanatory paragraph.
 *  3. Every per-channel row now says outright whether it's an "(Existing
 *     Device)" or "(New Device)".
 *  4. Reworded the "Danger zone" remove-source toggle to spell out that it
 *     removes the ENTIRE source and ALL of its child devices, separate from
 *     the per-channel checkboxes above it.
 *  5. Hub/NVR channel device-type detection (camera vs doorbell) now checks
 *     the channel's own name instead of a model field the Hub/NVR API never
 *     returns, fixing a mislabeled doorbell channel.
 *  6. Added a short note on the discover page about removing devices from
 *     this page rather than Hubitat's Devices page directly.
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

@Field static final String APP_VERSION = "1.3.9"

@Field static final List LOG_LEVELS = ["Errors Only", "Normal", "Full"]

// Poll interval is a device-level setting ONLY -- these are just the one-time
// default applied to a newly created device, not user-configurable at the app
// level. To change an existing device's interval, use its own device page (or
// the Set Poll Interval / Set Snapshot Interval commands).
@Field static final Integer DEFAULT_WIRED_POLL_SEC = 3
@Field static final Integer DEFAULT_BATTERY_POLL_SEC = 30

// The real-time event-subscription protocol (Baichuan) always runs on port
// 9000, completely separate from each source's own configurable HTTPS API
// port (src.port, default 443, used for GetAiState/GetChannelstatus/etc.).
// Always use this constant for the event socket, never src.port.
@Field static final Integer BAICHUAN_PORT = 9000

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
        // FIXED (2026-08-17): newPort and newIsHub were never cleared here,
        // unlike the other four fields -- so a value typed for one source
        // (even a typo, e.g. "440" instead of "443") silently persisted
        // and got reused for every SUBSEQUENT "Add a source" too, since
        // Hubitat only shows an input's defaultValue when the setting has
        // never been set at all. Real-world impact: a single mistyped port
        // early in a rapid add/remove testing session caused every later
        // source to silently connect to the wrong port and fail outright,
        // with no indication anything carried over. Every field this page
        // collects now resets cleanly after each add.
        app.removeSetting("newLabel")
        app.removeSetting("newHost")
        app.removeSetting("newPort")
        app.removeSetting("newUser")
        app.removeSetting("newPass")
        app.removeSetting("newIsHub")
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
            paragraph logLevelPill("Errors Only") + " Default. Warnings and errors only."
            paragraph logLevelPill("Normal") + " Errors, plus meaningful one-time events and changes " +
                "(logins, asleep/awake, devices created, config changes)."
            paragraph logLevelPill("Full") + " Everything, including every routine poll step. " +
                "<b>Automatically reverts to Normal after 60 minutes.</b>"
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
 * the two don't get visually confused. Color signals severity/verbosity at a glance: red for
 * the errors-only default, grey for the middle tier, dark blue for the noisiest/temporary one.
 */
private String logLevelPill(String level) {
    def colors = [
        "Errors Only": [bg: "#FFEBEE", fg: "#C62828"],
        "Normal":      [bg: "#ECEFF1", fg: "#455A64"],
        "Full":        [bg: "#E8EAF6", fg: "#283593"]
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
            paragraph "When a source's event connection is active, its children are updated in real time and " +
                "polling is skipped entirely for as long as that connection stays healthy -- polling only " +
                "resumes automatically if the event connection drops."
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
            paragraph pillHeader("PIR (motion trigger) on/off -- cameras only")
            paragraph "Use <b>pirOn</b>/<b>pirOff</b> to enable or disable a camera's PIR motion trigger " +
                "without removing the device. This does NOT stop an in-progress recording -- it removes the " +
                "trigger that would have woken a battery camera to record in the first place. If anything else " +
                "on that camera is separately configured for continuous/scheduled recording outside PIR " +
                "triggering, that recording is unaffected."
            paragraph "Manual on/off only -- there's no auto-revert timer. For something like \"turn PIR off " +
                "below battery threshold X and back on above threshold Y,\" build that with Rule Machine using " +
                "the existing battery attribute; no extra plumbing is needed here."
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
            paragraph logLevelPill("Errors Only") + " Default. Warnings and errors only."
            paragraph logLevelPill("Normal") + " Errors, plus meaningful one-time events and changes: " +
                "a fresh login, a device flipping asleep/awake, a device created, a config change. Routine " +
                "polls that succeed with no change don't log anything."
            paragraph logLevelPill("Full") + " Everything, including every routine poll step. " +
                "<b>Automatically reverts to Normal after 60 minutes</b> so it doesn't stay noisy indefinitely."
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
                "GetBatteryInfo (battery %), PtzCheck/GetPtzCheckState (calibration), SetPirInfo (PIR on/off)."
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
        app.removeSetting("newPort")
        app.removeSetting("newUser")
        app.removeSetting("newPass")
        app.removeSetting("newIsHub")
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
            paragraph "<span style='color:#5F5E5A;font-size:12px;'>ℹ️ After tapping Next, especially for an " +
                "NVR/Home Hub with several channels, it can take up to 30 seconds or so before the discover " +
                "page finishes loading -- it's checking each channel individually. This is normal, not stuck.</span>"
        }
    }
}

def discoverPage(params) {
    def sourceId = params?.sourceId ?: state.currentDiscoverySourceId
    state.currentDiscoverySourceId = sourceId
    def src = getSource(sourceId)

    // v1.3.9 FIX: this previously called ensureSourceBridge() unconditionally
    // on EVERY page load, including the very first time this page is opened
    // before any channel has ever been toggled on -- meaning just VIEWING
    // the discover page created a real device and attempted a live socket
    // connection, before the user had expressed any intent to add anything.
    // Every read on this page (existing/new pill status, single-channel
    // auto-apply check, connection status display) already handles a
    // missing bridge safely via getSourceBridge()'s null-safe lookup, so
    // nothing here actually needs the bridge to exist yet. It's created
    // lazily now, at the moment real intent exists -- createSelectedChildren()
    // already calls ensureSourceBridge() itself, right before creating the
    // first camera/doorbell, which is the natural point for it to exist.
    // This also meaningfully reduces exposure to orphaned-device risk: fewer
    // needless bridge creations means less surface area for something to go
    // wrong during a botched removal/reinstall (see uninstalled() above for
    // the actual guarantee against that, which this doesn't replace).

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

    // v1.3.6 FIX: this used to only fire when the checkbox was TRUE, which
    // meant unchecking an EXISTING single-channel device to remove it never
    // triggered anything. Now fires on EITHER direction: checking an absent
    // device (create) or unchecking a present one (remove), by comparing the
    // checkbox state against whether the device currently exists.
    if (channelCount == 1 && src) {
        def ch = lastDiscovery[0]
        def dni = childDni(sourceId, ch.channel)
        def bridge0 = getSourceBridge(sourceId)
        def existing = bridge0?.getChildDevice(dni) != null
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
                paragraph pillHeader("Event Connection")
                def connStatus = state.sourceConnMode?.get(sourceId.toString()) ?: "not started"
                def statusColor = connStatus == "connected" ? "#22c55e" : connStatus == "reconnecting" ? "#f97316" : "#94a3b8"
                input "useEventSubscription_${sourceId}", "bool",
                    title: "Use event-driven updates for this source (falls back to polling automatically if it can't connect)",
                    defaultValue: true, submitOnChange: true
                paragraph "<span style='color:${statusColor};font-weight:700;font-size:12px;'>Status: ${connStatus}</span>"
            }
            section {
                // v1.3.6: explicit warning added after a real-world case where a
                // device deleted from Hubitat's Devices page (instead of this
                // page) left the app's own checkbox state stale.
                paragraph "<span style='display:inline-block;background:#FFEBEE;color:#C62828;font-weight:700;" +
                    "padding:2px 10px;border-radius:10px;font-size:11px;margin-right:6px;'>HEADS UP</span>" +
                    "<b>Remove devices from THIS page, not Hubitat's Devices page.</b> Deleting there can " +
                    "bring a device back on its own or leave it stuck toggled-on."
                href name: "runDiscovery", title: "Re-run discovery",
                    description: "Discovery already ran automatically when this page opened. Use this to " +
                        "refresh the channel list, e.g. after pairing a new camera to an NVR/Home Hub.",
                    page: "discoverPage", params: [sourceId: sourceId, run: true]
                paragraph "<span style='color:#5F5E5A;font-size:12px;'>ℹ️ Discovery can take up to 30 " +
                    "seconds or so on a brand-new source, especially one with several channels -- it's " +
                    "checking each channel individually. This is normal, not stuck; already-added channels " +
                    "re-discover much faster on future runs.</span>"

                if (state.lastDiscoveryError) {
                    paragraph "⚠️ ${state.lastDiscoveryError}"
                }

                lastDiscovery.each { ch ->
                    def dni = childDni(sourceId, ch.channel)
                    def bridgeForList = getSourceBridge(sourceId)
                    def exists = bridgeForList?.getChildDevice(dni) != null
                    def doorbellTag = ch.deviceType == "doorbell" ? " (Doorbell)" : ""
                    input "create_${sourceId}_${ch.channel}", "bool",
                        title: "Ch ${ch.channel}: ${ch.name}${doorbellTag}",
                        defaultValue: exists, submitOnChange: true
                    if (exists) {
                        paragraph "<div style='margin:-8px 0 0 32px;border-left:3px solid #378ADD;" +
                            "padding-left:10px;'><span style='display:inline-block;background:#B5D4F4;color:#042C53;" +
                            "font-weight:500;font-size:11px;letter-spacing:0.3px;padding:2px 10px;" +
                            "border-radius:20px;margin-right:6px;'>EXISTING DEVICE</span>" +
                            "<span style='color:#5F5E5A;font-size:13px;'>Toggle off + apply to remove it</span></div>"
                    } else {
                        paragraph "<div style='margin:-8px 0 0 32px;border-left:3px solid #639922;" +
                            "padding-left:10px;'><span style='display:inline-block;background:#C0DD97;color:#173404;" +
                            "font-weight:500;font-size:11px;letter-spacing:0.3px;padding:2px 10px;" +
                            "border-radius:20px;margin-right:6px;'>NEW DEVICE</span>" +
                            "<span style='color:#5F5E5A;font-size:13px;'>Toggle on + apply to create it</span></div>"
                    }
                    paragraph "<div style='height:1px;background:#e0e0e0;margin:12px 0 12px 32px;'></div>"
                }

                if (channelCount > 1) {
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
    def bridge = getSourceBridge(sourceId)
    // A DEVICE's getChildDevices() (unlike an app's) can return null instead
    // of an empty list when it has zero children -- guard against that.
    return bridge ? (bridge.getChildDevices() ?: []) : []
}

private String bridgeDni(sourceId) {
    "reolink-bridge-${sourceId}"
}

@Field static final String STANDALONE_GROUP_DNI = "reolink-standalone-group"

/**
 * Looks up (but does not create) the shared "Reolink Standalone Devices"
 * group device -- the nesting-only parent that every standalone (non-Hub)
 * source's bridge lives under, so multiple standalone cameras/doorbells
 * group together in the Devices list instead of each bridge appearing as
 * its own separate unnested entry. Hub/NVR sources never use this; their
 * bridge always parents directly off the app. Returns null if no
 * standalone source has been added yet.
 */
private getStandaloneGroupDevice() {
    getChildDevice(STANDALONE_GROUP_DNI)
}

/** Looks up OR creates the shared standalone-devices group device -- lazily created the first time a standalone source needs a bridge. */
private ensureStandaloneGroupDevice() {
    def group = getStandaloneGroupDevice()
    if (!group) {
        group = addChildDevice("jdthomas24", "Reolink Standalone Devices", STANDALONE_GROUP_DNI, [
            name: "Reolink Standalone Devices",
            label: "Reolink Standalone Devices",
            isComponent: true
        ])
        logNormal "Reolink Integration: standalone-devices group device created"
    }
    return group
}

/**
 * Looks up an existing source's bridge device. A Hub/NVR source's bridge is
 * app-owned directly; a standalone source's bridge instead lives under the
 * shared standalone-devices group device (see ensureStandaloneGroupDevice()
 * above) -- this checks both locations so every other call site can look up
 * a bridge without needing to know the source type. Returns null if not yet
 * created.
 */
private getSourceBridge(sourceId) {
    def dni = bridgeDni(sourceId)
    getChildDevice(dni) ?: getStandaloneGroupDevice()?.getChildDevice(dni)
}

/**
 * Camera/Doorbell DNIs are "reolink-{sourceId}-{channel}" -- given one, finds
 * the bridge that owns it without needing sourceId passed separately. Used
 * anywhere only a dni string is available (e.g. runIn(...) callback data).
 */
private getSourceBridgeForChannelDni(String dni) {
    def parts = dni?.tokenize("-")
    if (!parts || parts.size() < 2) return null
    def sourceId = parts[1] as Integer
    return getSourceBridge(sourceId)
}

def removeSource(id) {
    def src = getSource(id as Integer)
    def bridge = getSourceBridge(id as Integer)
    if (bridge) {
        try { bridge.stopEventSubscription() } catch (e) { /* best effort */ }
        // Deleting the bridge cascades to delete its own children
        // (Camera/Doorbell) -- standard Hubitat parent/child device
        // behavior, same as deleting any multi-endpoint parent removes its
        // child endpoints too.
        bridge.getChildDevices()?.each { forgetSchedulingState(it.deviceNetworkId) }
        // Delete via whichever device actually owns this bridge -- a
        // Hub/NVR bridge is the app's own direct child, a standalone
        // bridge is the group device's child.
        if (src?.isHub) {
            deleteChildDevice(bridge.deviceNetworkId)
        } else {
            getStandaloneGroupDevice()?.removeBridgeDevice(bridge.deviceNetworkId)
        }
    }
    state.sources.removeAll { it.id == (id as Integer) }
    state.sourceUnreachable?.remove(id.toString())
    state.sourceConnMode?.remove(id.toString())
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
    // v1.3.9 REVERT (2026-08-17): the "action: 0" field added here in 1.3.8
    // was NEVER actually confirmed against real hardware for Login
    // specifically -- it was added purely by inference/symmetry with every
    // OTHER command, which all genuinely do send action:0 and have since
    // been independently confirmed working across 5+ real devices. Login
    // never got that same confirmation. Real-world reports on 2026-08-17
    // (multiple sources, confirmed-correct credentials, a hard rspCode:-7
    // "login failed" rejection -- not a timeout, not a parsing gap, an
    // explicit reject) match exactly what you'd expect if some Reolink
    // firmware is stricter about an unexpected field on Login than this app
    // assumed. Reverted to the pre-1.3.8 body (no action field) as the
    // prime regression suspect -- every OTHER command keeps action:0
    // unchanged, since those are separately confirmed and unrelated.
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

    // v1.3.8 FIX: this success log previously fired unconditionally, as soon
    // as the response parsed at all -- so a Login response that came back
    // WITHOUT a usable Token.name (bad credentials, or an unexpected shape
    // on some firmware) still logged "new token acquired" every time,
    // masking the real failure and making every following "no token
    // available" abort look inexplicable. Now only logs success when a real
    // token came back; otherwise logs the raw response so the actual field
    // shape/error is visible.
    if (token) {
        logNormal "Reolink source ${sourceId}: new token acquired, leaseTime=${leaseSec}s"
        markSourceReachable(sourceId)
    } else {
        log.warn "Reolink source ${sourceId}: Login response parsed but no Token.name found (check " +
            "credentials) -- raw: ${resp?.toString()?.take(500)}"
    }
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
 * condition, not a fresh event every poll cycle -- these two helpers gate
 * on a per-source state flag so only the TRANSITION into/out of unreachable
 * gets logged. Full-tier logging still shows every individual attempt via
 * the existing logFull() calls elsewhere, for anyone actively
 * troubleshooting.
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
        // retry of the SAME call often succeeds right after a failed one.
        logNormal "Reolink source ${sourceId}: ${cmd} (ch ${channel}) returned unparseable data (known older-firmware " +
            "bug, see Tips page), retrying once immediately"
        outcome = doReolinkApiCall(src, sourceId, cmd, token, param, channel)
    }
    return outcome.value
}

/**
 * quiet=true suppresses the usual failure escalation (markSourceUnreachable
 * warn, or the JSON-parse-failure warn) and logs at Full tier instead. Used
 * by guessIsBattery() below -- see that method's comment for why a failed
 * GetBatteryInfo probe must NOT be treated as evidence the whole SOURCE is
 * unreachable.
 */
private Map doReolinkApiCall(src, sourceId, String cmd, String token, Map param, Integer channel, boolean quiet = false) {
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
        if (quiet) {
            logFull "Reolink source ${sourceId} ch ${channel}: ${cmd} returned unparseable data (quiet probe) -- ${e.message}"
        } else {
            log.warn "Reolink cmd ${cmd} failed for source ${sourceId} ch ${channel}: ${e.message}"
        }
        return [value: null, rspCode: null, parseFailure: true]
    } catch (e) {
        if (quiet) {
            logFull "Reolink source ${sourceId} ch ${channel}: ${cmd} failed (quiet probe, not treated as source-unreachable) -- ${e.message}"
        } else {
            markSourceUnreachable(sourceId, "cmd ${cmd} (ch ${channel}) failed: ${e.message}")
        }
        return [value: null, rspCode: null, parseFailure: false]
    }
}

// ---------- Discovery ----------

def discoverChannels(sourceId) {
    def src = getSource(sourceId)
    def channels = []
    state.lastDiscoveryError = null
    def bridgeForDiscovery = getSourceBridge(sourceId)

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
        // Skip the GetBatteryInfo round-trip entirely for a channel that
        // already has a child device -- isBattery is ONLY ever read at
        // device CREATION time, so recomputing it on every discovery run for
        // an existing channel is a wasted HTTP round-trip.
        def existing0 = bridgeForDiscovery?.getChildDevice(childDni(sourceId, 0)) != null
        channels << [channel: 0, name: info?.DevInfo?.name ?: src.label, deviceType: guessDeviceType(info),
            isBattery: existing0 ? null : guessIsBattery(sourceId, 0),
            supportedFeatures: computeSupportedFeatures(abilityChnList?.getAt(0))]
    } else {
        def status = reolinkApiCall(sourceId, "GetChannelstatus")
        if (status == null) {
            state.lastDiscoveryError = "No response from ${src.host}. Check IP/credentials."
            return channels
        }
        status?.status?.each { ch ->
            if (ch.online) {
                def existing = bridgeForDiscovery?.getChildDevice(childDni(sourceId, ch.channel)) != null
                channels << [channel: ch.channel, name: ch.name ?: "Channel ${ch.channel}", deviceType: guessChannelDeviceType(ch),
                    isBattery: existing ? null : guessIsBattery(sourceId, ch.channel),
                    supportedFeatures: computeSupportedFeatures(abilityChnList?.getAt(ch.channel as Integer))]
            }
        }
    }
    return channels
}

/**
 * Standalone-source detection (GetDevInfo shape, has a real model field).
 * Confirmed reliable against every standalone camera/doorbell tested so far.
 */
private String guessDeviceType(info) {
    def model = (info?.DevInfo?.model ?: info?.model ?: "").toLowerCase()
    return model.contains("doorbell") ? "doorbell" : "camera"
}

/**
 * Hub/NVR-channel detection. GetChannelstatus (the Hub/NVR API) never
 * returns a model field, only GetDevInfo (the standalone API) does -- so
 * this uses the channel's own name instead. Reolink's own Hub/NVR channel
 * naming already reflects the device type (a paired doorbell channel is
 * named "Doorbell" by default). Falls back to "camera" if the name gives no
 * signal either way.
 */
private String guessChannelDeviceType(ch) {
    def name = (ch?.name ?: "").toLowerCase()
    return name.contains("doorbell") ? "doorbell" : "camera"
}

/**
 * Battery vs wired isn't reported directly by GetDevInfo/GetChannelstatus, so
 * this uses GetBatteryInfo as a signal instead: a battery-class device
 * answers it with real data, a wired/PoE device returns nothing usable --
 * and on some wired firmware, "nothing usable" is an outright timeout
 * rather than a clean unsupported-command response.
 *
 * FIXED (2026-08-17): a real PoE camera timing out on this specific probe
 * was marking its whole SOURCE unreachable (markSourceUnreachable() is a
 * per-source flag, not per-command), which then immediately flipped back to
 * "connection restored" on the very next unrelated successful call -- noisy
 * and misleading, since every other command for that source was working
 * fine the whole time. This probe is EXPECTED to fail for roughly half of
 * all cameras (any wired one) -- that's not a source-health signal, it's
 * routine. Calls doReolinkApiCall() directly with quiet=true instead of
 * going through the public reolinkApiCall() wrapper, so a failure here logs
 * at Full tier only and never touches source-reachable state.
 */
private Boolean guessIsBattery(sourceId, channel) {
    def src = getSource(sourceId)
    def token = reolinkLogin(sourceId)
    if (!token) return false
    def outcome = doReolinkApiCall(src, sourceId, "GetBatteryInfo", token, [:], channel, true)
    return outcome.value != null
}

/**
 * Fetches GetAbility for a source and returns the abilityChn[] array (one
 * entry per channel, index-aligned with the channel number). Returns null on
 * failure or an unexpected response shape -- callers must handle that by
 * falling back to an empty/unknown feature set, not by failing discovery
 * entirely, since capability detection is informational and should never
 * block a device from being creatable.
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
 * Safe lookup: treats a missing key the SAME as unsupported. Checks BOTH the
 * "permit" and "ver" sub-fields, not permit alone -- these move together on
 * every field tested except ptzType, where permit stays 0 even on confirmed
 * PTZ cameras while ver correctly shows nonzero. PTZ presence is keyed off
 * ptzType specifically BECAUSE of this behavior (see computeSupportedFeatures()
 * below) -- checking both here is what makes ptzType usable at all as a PTZ
 * signal, and closes off the same risk for any other field.
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
 *   - PTZ: ptzType > 0 (checked via abilityPermit()'s existing max(permit,
 *     ver) logic). CORRECTED 2026-08-14: previously keyed off ptzCtrl > 0,
 *     which turned out to report whether ANY PTZ-style command channel
 *     exists (apparently including basic digital zoom on some fixed
 *     cameras), not actual pan-tilt hardware -- confirmed via a real
 *     RLC-1240A (no physical PTZ) showing ptzCtrl permit:7, ver:64, both
 *     nonzero, a false positive. ptzType correctly read permit:0, ver:0 on
 *     that same camera. The E1 Pro's documented ptzType values (permit:0,
 *     ver nonzero) are exactly the case abilityPermit()'s check-both logic
 *     was built for, so switching to ptzType keeps the E1 Pro correctly
 *     detected while fixing the RLC-1240A false positive.
 *   - PTZ Calibration: supportPtzCheck > 0 OR supportPtzCalibration > 0.
 *   - Spotlight: supportFLswitch > 0 OR floodLight > 0 (camera-only).
 *   - Night Vision (IR): ledControl > 0.
 *   - Status LED: supportDoorbellLight > 0 (doorbell button/ring light, NOT
 *     a spotlight).
 *   - Siren: alarmAudio > 0.
 *   - Person / Vehicle: supportAiPeople / supportAiVehicle > 0.
 *   - Pet: supportAiDogCat OR supportAiAnimal > 0.
 *   - Package: supportAiPackage > 0 (doorbell-specific).
 *   - Basic/older models can be missing entire families of keys --
 *     abilityPermit()'s missing-key-as-0 handling covers this correctly.
 */
private List<String> computeSupportedFeatures(Map abilityChn) {
    if (abilityChn == null) return []
    def features = []
    if (abilityPermit(abilityChn, "ptzType") > 0) features << "PTZ"
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

// ---------- Event connection ----------

/**
 * The bridge device ALWAYS needs to exist (it's the real parent of Camera/
 * Doorbell, not just an optional event-mode extra) -- this always creates
 * the bridge if missing, then separately starts/stops the event
 * subscription ON that bridge based on the per-source toggle. Idempotent --
 * safe to call on every page load or initialize().
 *
 * A Hub/NVR source's bridge is created as a direct child of the app, same
 * as always. A standalone source's bridge is instead created as a child of
 * the shared "Reolink Standalone Devices" group device (lazily created on
 * first use) -- since each standalone camera still needs its own
 * independent event connection (Hubitat's rawSocket interface is one-
 * connection-per-driver-instance, so that part can't be shared), but
 * nesting them all under one shared parent avoids N separate unnested
 * bridges cluttering the Devices list the way they would otherwise.
 */
def ensureSourceBridge(sourceId) {
    def src = getSource(sourceId)
    if (!src) return null
    def dni = bridgeDni(sourceId)
    def bridge = getSourceBridge(sourceId)
    if (!bridge) {
        def label = "Reolink Device Bridge (${src.label})"
        try {
            if (src.isHub) {
                bridge = addChildDevice("jdthomas24", "Reolink Device Bridge", dni, [
                    name: label, label: label, isComponent: true
                ])
                bridge.updateDataValue("sourceId", "${sourceId}")
            } else {
                def group = ensureStandaloneGroupDevice()
                bridge = group.createBridgeDevice(dni, label, sourceId as Integer)
            }
            logNormal "Reolink source ${sourceId}: bridge device created"
        } catch (com.hubitat.device.exception.DuplicateDNIException e) {
            // FIXED (2026-08-17): Hubitat enforces device network IDs as
            // GLOBALLY unique across the ENTIRE hub, not just unique among
            // one parent's children -- but getSourceBridge() above only
            // checks the two places a bridge is supposed to live (direct
            // app child, or under the standalone group device). If a device
            // with this exact DNI exists ANYWHERE else on the hub (most
            // likely an orphan left behind by a partial removal, a stale
            // HPM-vs-manual driver mismatch, or an interrupted
            // reinstall/wipe), that lookup finds nothing, concludes no
            // bridge exists, tries to create one, and Hubitat rejects it --
            // which previously crashed the ENTIRE page render with a bare
            // "Unexpected Error," giving no indication what actually went
            // wrong or how to fix it. Now fails loudly but safely instead:
            // logs a clear, actionable warning and returns null so the
            // caller can handle a missing bridge gracefully rather than the
            // whole page throwing.
            log.warn "Reolink source ${sourceId}: a device with DNI '${dni}' already exists somewhere on " +
                "this hub but isn't reachable as this source's bridge -- likely an orphaned device from an " +
                "earlier partial removal or reinstall. Search your full Devices list for Device Network Id " +
                "'${dni}' and delete it, then re-run discovery for this source. (${e.message})"
            return null
        }
    }
    // Unconditional -- always keeps the bridge's connection config in sync
    // with state.sources, independent of whether the subscription is
    // actually wanted right now.
    bridge.configureConnection(src.host, BAICHUAN_PORT, src.username, src.password, sourceId as Integer)

    def wantEvent = settings["useEventSubscription_${sourceId}"] != false  // default true
    def currentStatus = state.sourceConnMode?.get(sourceId.toString())
    def currentlyRunning = currentStatus in ["connected", "connecting", "reconnecting"]
    if (wantEvent && !currentlyRunning) {
        bridge.startEventSubscription()
        logNormal "Reolink source ${sourceId}: event subscription starting"
    } else if (!wantEvent && currentlyRunning) {
        try { bridge.stopEventSubscription() } catch (e) { /* best effort */ }
        state.sourceConnMode?.remove(sourceId.toString())
        logNormal "Reolink source ${sourceId}: event subscription stopped (polling only)"
    }
    return bridge
}

/** Called by the bridge whenever its event-subscription status changes. */
def componentEventConnectionStatus(child, sourceId, String status) {
    def map = state.sourceConnMode ?: [:]
    def key = sourceId.toString()
    def prev = map[key]
    map[key] = status
    state.sourceConnMode = map
    if (prev != status) {
        logNormal "Reolink source ${sourceId}: event connection ${status}"
    }
    if (status != "connected") {
        // Falling back to polling -- mark this source's children due
        // immediately instead of waiting out whatever interval they were on,
        // so there's no extra gap on top of the drop itself.
        childrenForSource(sourceId as Integer).each { markPollDueNow(it.deviceNetworkId) }
    }
}

/** True while a source's event connection is confirmed healthy -- schedulerTick() skips active polling for its children while this holds. */
private boolean isSourceEventConnected(sourceId) {
    return state.sourceConnMode?.get(sourceId.toString()) == "connected"
}

/**
 * Called by the bridge for every genuine per-channel motion/AI/visitor
 * change. Routes into the SAME parseReolinkState() the polling path already
 * calls -- the camera/doorbell drivers have no idea this came from a push
 * instead of a poll. The target child is looked up ON THE BRIDGE (its real
 * parent), not the app.
 */
def componentEventChannelUpdate(child, sourceId, channelId, String status, String aiType) {
    def bridge = getSourceBridge(sourceId)
    def dni = childDni(sourceId, channelId)
    def target = bridge?.getChildDevice(dni)
    if (!target) return  // channel not added as a device, or not yet discovered -- nothing to update
    def shapes = translateToLegacyShape(status, aiType)
    target.parseReolinkState(shapes.aiState, shapes.mdState, "event")
    logFull "Reolink source ${sourceId} ch ${channelId}: event push -- status='${status}', AItype='${aiType}'"
}

/** Called by the bridge for sleep-status pushes (cmd_id=145). Logged only for now -- not yet wired to markAsleep()/awake. */
def componentEventSleepUpdate(child, sourceId, channelId, String sleepState) {
    logFull "Reolink source ${sourceId} ch ${channelId}: event sleep push -- '${sleepState}' (not yet acted on)"
}

/**
 * Reshapes a pushed status/AItype pair into the same Map shape
 * parseReolinkState() already expects from POLLING (GetAiState/GetMdState
 * JSON).
 */
private Map translateToLegacyShape(String status, String aiType) {
    def aiActive = aiType && aiType != "none"
    def motionActive = (status == "MD") || aiActive
    return [
        mdState: [state: motionActive ? 1 : 0],
        aiState: [
            people:  [alarm_state: (aiType == "people")  ? 1 : 0],
            vehicle: [alarm_state: (aiType == "vehicle") ? 1 : 0],
            dog_cat: [alarm_state: (aiType == "dog_cat") ? 1 : 0],
            package: [alarm_state: (aiType == "package") ? 1 : 0],
            visitor: [alarm_state: (status == "visitor") ? 1 : 0]
        ]
    ]
}

def createSelectedChildren(sourceId) {
    // The bridge -- not the app -- creates/removes Camera/Doorbell, via
    // createChannelDevice()/removeChannelDevice(), so they end up as ITS
    // children (nested in the Devices list).
    def bridge = ensureSourceBridge(sourceId)
    if (!bridge) {
        log.warn "Reolink source ${sourceId}: no bridge device available, cannot create/remove children"
        return
    }
    (state.lastDiscovery ?: []).each { ch ->
        def wantIt = settings["create_${sourceId}_${ch.channel}"]
        def dni = childDni(sourceId, ch.channel)
        def existing = bridge.getChildDevice(dni)
        if (wantIt && !existing) {
            def driverName = ch.deviceType == "doorbell" ? "Reolink Doorbell" : "Reolink Camera"
            def pollDefault = ch.isBattery ? DEFAULT_BATTERY_POLL_SEC : DEFAULT_WIRED_POLL_SEC
            bridge.createChannelDevice(driverName, dni, ch.name, pollDefault as Integer, ch.supportedFeatures ?: [])
            logNormal "Created child ${dni} (${driverName}) via bridge, poll interval defaulted to ${pollDefault}s (${ch.isBattery ? 'battery' : 'wired'}), features: ${ch.supportedFeatures ? ch.supportedFeatures.join(', ') : 'none detected'}"
        } else if (!wantIt && existing) {
            bridge.removeChannelDevice(dni)
            forgetSchedulingState(dni)
            logNormal "Removed child ${dni} via bridge (unchecked in discovery list)"
        }
    }
    initializePolling()
}

// ---------- Polling ----------

def installed() { initialize() }
def updated() { initialize() }

/**
 * v1.3.9 NEW: explicit teardown on full app removal. Without this,
 * removing the entire app instance (via Hubitat's Apps list, NOT the
 * in-app "Remove this ENTIRE source" toggle) relied purely on Hubitat's
 * own built-in cascade-delete of app-owned children -- platform behavior
 * this app doesn't control or fully verify, especially now that the
 * v1.3.8 bridge restructuring made the device tree 2-3 levels deep (App ->
 * Bridge -> Camera, or for standalone: App -> Group Device -> Bridge ->
 * Camera) instead of the flat one-level tree this app had before. A
 * genuine production DuplicateDNIException was traced to an orphaned
 * bridge device surviving what should have been a full removal -- unclear
 * whether that's a platform edge case with multi-level cascade delete
 * under heavy/rapid churn, or something else, but this closes the gap
 * either way: every source now goes through the SAME explicit,
 * already-defensive removeSource() teardown (stop subscription, delete
 * children, delete bridge) that the per-source Danger Zone toggle already
 * uses and has been reliable, rather than trusting an implicit mechanism
 * this app can't inspect or guarantee.
 */
def uninstalled() {
    // FIXED (2026-08-17, same day as introduced): removeSource() mutates
    // state.sources internally (state.sources.removeAll {...}) -- iterating
    // that SAME live list here while it's being mutated mid-loop is exactly
    // what ConcurrentModificationException guards against. .collect() snapshots
    // the list once up front, so removeSource()'s mutation of the real
    // state.sources no longer affects the iteration in progress.
    (state.sources ?: []).collect().each { src ->
        try {
            removeSource(src.id)
        } catch (e) {
            log.warn "Reolink Integration: cleanup failed for source ${src.id} during uninstall -- ${e.message}"
        }
    }
}

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
 * callback name. Gives any leftover pending job from an old install
 * somewhere safe to land instead of erroring; nothing schedules a job under
 * this name going forward.
 */
def disableDebugLogging() {
    log.info "Reolink Integration: leftover pre-1.2.5 logging job fired, no action needed (see disableDebugLogging() comment)"
}

def initializePolling() {
    // Children live under each source's bridge, not the app directly --
    // iterate sources -> bridge -> its real (Camera/Doorbell) children.
    // Also (re)establishes each source's bridge/event-subscription state
    // for sources that already have one.
    def now = now()
    def pollDue = state.nextPollDue ?: [:]
    def snapDue = state.nextSnapshotDue ?: [:]
    (state.sources ?: []).each { src ->
        // v1.3.9 FIX: this previously called ensureSourceBridge() for
        // EVERY configured source unconditionally, on every Done/Update
        // click -- creating a real bridge device (and attempting a live
        // connection) for a source that had just been added, before the
        // user had ever opened its discover page or selected a single
        // channel. Same class of premature-creation bug as the one fixed
        // in discoverPage() above, just triggered by "Done" instead of by
        // viewing the page. Now only re-establishes a bridge that ALREADY
        // exists (a source that was genuinely set up before this
        // Done/Update cycle) -- a brand-new source with nothing selected
        // yet stays completely untouched until real intent exists via
        // createSelectedChildren(), which is the only place a bridge
        // should ever get created.
        if (!getSourceBridge(src.id)) return
        def bridge = ensureSourceBridge(src.id)
        bridge?.getChildDevices()?.each { child ->
            def dni = child.deviceNetworkId
            if (!pollDue.containsKey(dni)) pollDue[dni] = now
            if (!snapDue.containsKey(dni)) snapDue[dni] = now
        }
    }
    state.nextPollDue = pollDue
    state.nextSnapshotDue = snapDue
    runIn(1, "schedulerTick", [overwrite: true])
}

/**
 * Single central scheduler -- exactly ONE recurring timer exists for the
 * whole app (this method, ticking every second), and each device's own
 * due-time is tracked independently in state (nextPollDue / nextSnapshotDue,
 * keyed by DNI). Nothing here can ever cancel another device's schedule,
 * because there is only one schedule.
 *
 * Two robustness measures, since this single tick is the ONE thing every
 * device's polling depends on:
 *  1. Each device is processed in its own try/catch. One device throwing
 *     logs a warning and moves on instead of aborting the whole tick.
 *  2. The next tick is re-armed in a finally block, so even an unexpected
 *     failure outside the per-device loop still can't prevent the scheduler
 *     from continuing to run.
 */
def schedulerTick() {
    try {
        def nowMs = now()
        def pollDue = state.nextPollDue ?: [:]
        def snapDue = state.nextSnapshotDue ?: [:]

        (state.sources ?: []).each { src ->
            def bridge = getSourceBridge(src.id)
            if (!bridge) return
            def sourceConnected = isSourceEventConnected(src.id)
            (bridge.getChildDevices() ?: []).each { child ->
                def dni = child.deviceNetworkId
                try {
                    // While this source has a confirmed-healthy event
                    // connection, skip active polling for it -- the push path
                    // is already delivering its state via
                    // componentEventChannelUpdate(). nextPollDue is
                    // deliberately left untouched here so if the connection
                    // drops, componentEventConnectionStatus() marking it
                    // due-now takes effect immediately.
                    if (sourceConnected) return
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
                    def interval = (child.getSetting("pollIntervalSec") ?: 30) as Integer
                    pollDue[dni] = nowMs + (interval * 1000L)
                }
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
    def bridge = getSourceBridgeForChannelDni(data.dni)
    def child = bridge?.getChildDevice(data.dni)
    if (!child) return
    pollChildNow(child)
    markPollDueNow(child.deviceNetworkId)
}

/**
 * Checks the child's CURRENT sleepStatus before logging, so "marking asleep"/
 * "marking awake" only hits Normal-tier logging on a real transition. A
 * device that's already asleep and stays asleep (or already awake and stays
 * awake) only logs at Full tier, since that's routine and not worth
 * surfacing by default.
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
 * Motion detection benefits from being fast; a dashboard image does not need
 * to be refreshed nearly that often, and pulling a full JPEG every few
 * seconds across several cameras against this app's singleThreaded
 * execution model risks a semaphore/queueing problem. Defaults to a much
 * looser interval than the poll interval.
 */
def pollChildSnapshot(data) {
    def bridge = getSourceBridgeForChannelDni(data.dni)
    def child = bridge?.getChildDevice(data.dni)
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
 * request path.
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
    } catch (e) {
        log.warn "Reolink source ${sourceId} ch ${channel}: failed to write snapshot to hub file storage -- ${e.message}"
    }
}

private String snapshotFileName(dni) {
    "reolink-snap-${dni}.jpg"
}

/**
 * Resolves a proper child device reference given a dni passed explicitly
 * from the driver (device.deviceNetworkId). Falls back to the raw passed
 * reference only for callers that haven't been updated to pass dni yet.
 * These calls arrive via the bridge's passthrough layer (Camera/Doorbell's
 * real parent), not directly from the child -- the lookup routes through
 * whichever bridge actually owns this dni.
 */
private resolveChild(child, String dni) {
    def effectiveDni = dni ?: child?.deviceNetworkId
    if (!effectiveDni) return child
    def bridge = getSourceBridgeForChannelDni(effectiveDni)
    return bridge ? (bridge.getChildDevice(effectiveDni) ?: child) : child
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
 * app's local relay endpoint (see mappings + handleSnapshotRequest() below).
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
 * in local hub file storage for this device. Deliberately does NOT talk to
 * the camera itself on every request.
 */
def handleSnapshotRequest() {
    def dni = params?.dni
    if (!dni || dni == "null") {
        log.warn "Reolink Integration: snapshot endpoint hit with no/null device id, this URL is stale -- run takeSnapshot again to regenerate it"
        render status: 400, data: "Missing or stale device id, run takeSnapshot again to regenerate this URL", contentType: "text/plain"
        return
    }
    def bridge = getSourceBridgeForChannelDni(dni)
    def child = bridge?.getChildDevice(dni)
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
 * InputStream on resp.data, which must be drained explicitly. On failure
 * the camera returns a small JSON error payload instead, detected via
 * content-type.
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

/**
 * v1.3.8 NEW: PIR enable/disable, cameras only. Field names unconfirmed
 * against real hardware -- built following the same naming convention as
 * GetIrLights/SetIrLights, see the Tips page's "built but not tested" list.
 */
def componentSetPir(child, Boolean on, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "SetPirInfo", [PirInfo: [channel: channel, enable: (on ? 1 : 0)]], null)
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

/**
 * Logs at Normal tier and above (Normal, Full). Meaningful one-time events
 * and state transitions -- not routine unchanged polls.
 *
 * NOT private -- the Reolink Device Bridge device calls this via
 * parent?.logNormal(...) so its own connection-status logging (starting,
 * connected, reconnecting) obeys the app's Log level setting instead of
 * writing to the hub log unconditionally, same as everything else in this
 * app. (v1.3.8 FIX: the bridge previously used raw log.info/log.debug
 * throughout, bypassing this tiering entirely -- at Full this reproduced
 * the exact log-flooding pattern from BETA testing, and switching the app
 * back to Errors Only did nothing to quiet it, since the bridge never
 * checked that setting at all.)
 */
void logNormal(msg) {
    if (logLevelRank() >= 1) log.debug msg
}

/**
 * Logs only at Full tier. Routine poll-by-poll / push-by-push detail --
 * token reuse, individual API calls succeeding, unchanged state repeats,
 * and (via the bridge) every routine event push and corruption-resync
 * detail. NOT private -- see logNormal()'s note above; same reasoning
 * applies here, and this is the tier that actually floods if left
 * unconditional.
 */
void logFull(msg) {
    if (logLevelRank() >= 2) log.debug msg
}
