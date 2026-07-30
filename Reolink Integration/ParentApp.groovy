/**
 * Reolink Integration (Parent App)
 * Version: 1.2.3
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
 * v1.2.2 -- Fixed dashboard snapshot tiles going to a broken-image icon on
 * refresh (rspCode -6 "please login first"). The old snapshotUrl had the
 * camera's session token baked directly into a static URL that the dashboard
 * tile just kept re-fetching on its own timer -- fine on first load, broken
 * forever once that token expired or got invalidated by another client. The
 * fix: snapshotUrl now points at a local relay endpoint on this app instead
 * (/snap/:dni). Every dashboard refresh hits the hub, the hub fetches a fresh
 * snapshot from the camera at that moment (with the same rspCode -6
 * relogin-and-retry protection reolinkApiCall() already has), and streams the
 * image back. No token is ever exposed to or cached by the browser.
 *
 * v1.2.3 -- Fixed the 1.2.2 relay endpoint itself returning a blank white
 * image instead of the snapshot. doFetchSnapshot() was reading resp.data.bytes
 * directly, but for a binary response Hubitat's httpGet hands back resp.data
 * as a raw InputStream, which has no .bytes property -- so that line was
 * silently returning null/empty instead of the image, while render() still
 * responded 200 with the right content-type, producing a blank page rather
 * than an obvious error. Now explicitly drains the InputStream into a byte
 * array via ByteArrayOutputStream before rendering.
 * Also (still 1.2.3, not yet released): componentTakeSnapshot() now guards
 * against a null/missing deviceNetworkId instead of silently building a
 * broken "/snap/null" URL, and handleSnapshotRequest() explicitly detects
 * and logs a null/blank dni in the request path so a stale cached URL from
 * before this fix shows up clearly in the logs instead of just a blank page.
 * Both drivers' takeSnapshot() now pass device.deviceNetworkId explicitly
 * rather than relying on the app to read deviceNetworkId off the driver's
 * "this" reference, which doesn't reliably expose it.
 *
 * Also (still 1.2.3): the camera is now only ever fetched from pollChild()'s
 * normal poll cycle (and once, immediately, on a manual takeSnapshot); the
 * result is cached to local hub file storage via uploadHubFile(), and the
 * relay endpoint just serves that cached file, no camera round-trip in the
 * request path at all.
 *
 * Also (still 1.2.3): snapshot caching now runs on its OWN interval
 * (snapshotIntervalSec, device preference, defaults to 30s), decoupled from
 * pollIntervalSec. Originally it piggybacked on the same tight AI/motion poll
 * cycle, which meant a full JPEG download on every single poll -- fine at a
 * loose interval, but a real risk of re-triggering the exact semaphore/
 * queueing problem this whole redesign exists to fix if someone runs several
 * wired cameras at a fast (e.g. 3s) poll interval for motion responsiveness.
 * Motion detection should stay fast; a dashboard image doesn't need to be.
 * cacheSnapshot() also no longer logs on every successful write, only on
 * failure, since a line every cycle added up to real log noise for no
 * diagnostic benefit once snapshot caching became a recurring background job.
 *
 * Also (still 1.2.3): fixed createSelectedChildren() unconditionally applying
 * defaultBatteryPoll to every newly created device, wired or battery --
 * defaultWiredPoll existed as a setting but nothing ever actually used it, so
 * every PoE/wired camera has been defaulting to the loose battery interval
 * since creation. Discovery now tags each channel as battery or wired (via
 * guessIsBattery(), using GetBatteryInfo as the signal), and the correct
 * default gets applied at creation time. This only affects newly created
 * devices going forward -- existing devices already on the wrong interval
 * need their poll interval corrected by hand (device page, or the
 * setPollInterval command).
 *
 * Also (still 1.2.3): full audit of every componentX(child, ...) callback
 * after componentSetPollInterval turned out to have the same underlying bug
 * as the deviceNetworkId issue, just on updateSetting() instead. Every
 * driver command that calls into this app now passes its own
 * device.deviceNetworkId explicitly, and every componentX callback resolves
 * a proper reference via the new resolveChild() helper instead of operating
 * on the raw driver-passed "this". Confirmed broken via this path so far:
 * deviceNetworkId (property), updateSetting() (method). Confirmed working:
 * getDataValue(), user-defined driver methods like receiveSnapshotUrl(). This
 * also caught componentRefresh(), which was silently doing nothing on every
 * refresh command (pollChild(dni: null) getChildDevice()'s to null and
 * no-ops) -- unrelated to anything from this session, this one predates all
 * of 1.2.2/1.2.3 and just never surfaced because nobody had reason to notice
 * a refresh silently failing.
 *
 * Also (still 1.2.3): fixed a much bigger scheduling bug found testing with
 * 4 cameras -- only 1 of 4 was actually polling; the other 3 had silently
 * stopped. Root cause: scheduleChildPoll()/scheduleChildSnapshot() scheduled
 * every device through runIn(interval, "pollChild"/"pollChildSnapshot",
 * [data: [dni: ...], overwrite: true]) -- ALL devices shared the same
 * handler name, and Hubitat's overwrite: true cancels ANY pending call to
 * that handler, not just the calling device's own. Whichever device
 * (re)scheduled last silently canceled every other device's pending timer,
 * with no error anywhere. Replaced with a single central scheduler
 * (schedulerTick(), ticking every 1s) that tracks each device's own due time
 * independently in state (nextPollDue/nextSnapshotDue, keyed by DNI) --
 * nothing can cancel another device's schedule because there is now only
 * one schedule, system-wide. Also added markPollDueNow()/markSnapshotDueNow()
 * so an interval change via setPollInterval/setSnapshotInterval takes effect
 * on the very next tick instead of waiting out the old interval, and
 * forgetSchedulingState() so deleted devices don't leave stale entries in
 * state forever.
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

@Field static final String APP_VERSION = "1.2.3"

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
            paragraph pillHeader("Polling")
            input "defaultWiredPoll", "number", title: "Default poll interval (sec) - wired/plugged-in devices", defaultValue: 3
            input "defaultBatteryPoll", "number", title: "Default poll interval (sec) - battery devices", defaultValue: 30
        }
        section {
            paragraph pillHeader("Logging")
            input "debugLogging", "bool", title: "Enable debug logging (auto-off after 90 min)", defaultValue: false
        }
        section {
            href name: "tips", title: "Tips, limitations & what works so far", page: "tipsPage"
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
            paragraph "⚠️ <b>Battery-class cameras/doorbells</b> (Argus line, Doorbell Battery). No local " +
                "HTTP/ONVIF server standalone, even in \"Wired Power Mode\" -- that setting only changes " +
                "charging, not the network API."
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
            paragraph pillHeader("Confidence level on newer commands")
            paragraph "<b>Confirmed working</b> against real hardware: PtzCtrl -- move, and ToPos (preset recall)."
            paragraph "<b>Built but not yet tested</b> against this setup's actual firmware: SetPtzPreset " +
                "(save), SetWhiteLed (spotlight), SetIrLights (night vision), AudioAlarmPlay (siren), " +
                "GetBatteryInfo (battery %), PtzCheck/GetPtzCheckState (calibration)."
            paragraph "These are built from consistent patterns across several independent Reolink API " +
                "references. If one doesn't work as expected, check Logs with debug on -- the exact response " +
                "usually points to which field name needs adjusting for this device."
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

    if (params?.run && src) {
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

    if (channelCount == 1 && src) {
        def ch = lastDiscovery[0]
        if (settings["create_${sourceId}_${ch.channel}"]) {
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
                href name: "runDiscovery", title: "Run discovery now",
                    description: "Calls the source and lists available channels",
                    page: "discoverPage", params: [sourceId: sourceId, run: true]

                if (!params?.run && !cachedForThisSource) {
                    paragraph "No discovery results yet for this source -- tap 'Run discovery now' above."
                }

                if (state.lastDiscoveryError) {
                    paragraph "⚠️ ${state.lastDiscoveryError}"
                }

                lastDiscovery.each { ch ->
                    def dni = childDni(sourceId, ch.channel)
                    def exists = getChildDevice(dni) != null
                    input "create_${sourceId}_${ch.channel}", "bool",
                        title: "Ch ${ch.channel}: ${ch.name} (${ch.deviceType})",
                        defaultValue: exists, submitOnChange: true
                    if (exists) {
                        paragraph "<span style='color:#1a73e8'>✔ Added to device list</span>"
                    }
                }

                if (channelCount > 1) {
                    input "confirmCreate", "bool", title: "Create selected devices", defaultValue: false, submitOnChange: true
                    paragraph "This source has multiple channels (NVR/Home Hub) -- check the ones you want, " +
                        "then toggle 'Create selected devices' once to create them all together."
                } else if (channelCount == 1) {
                    paragraph "Standalone source, one channel -- toggling it on creates the device immediately, no separate confirm needed."
                }
            }
            section {
                paragraph pillHeader("Danger zone")
                input "confirmRemoveSource", "bool",
                    title: "Remove this source (deletes its child devices too)",
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
    logDebug "Added source ${id}: ${newLabel} (${newHost})"
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
    logDebug "Removed source ${id}"
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
        logDebug "Reolink source ${sourceId}: reusing cached token, expires in ${(src.tokenExpires - now()) / 1000}s"
        return src.token
    }

    logDebug "Reolink source ${sourceId}: cached token missing/expired, logging in fresh"
    def body = [[cmd: "Login", param: [User: [userName: src.username, password: src.password]]]]
    def resp = reolinkRawPost(src, body)
    def first = firstResultValue(resp, src)
    def token = first?.Token?.name
    def leaseSec = (first?.Token?.leaseTime ?: 3600) as Integer

    src.token = token
    src.tokenExpires = now() + (leaseSec * 1000L) - 30000L
    logDebug "Reolink source ${sourceId}: new token acquired, leaseTime=${leaseSec}s"
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
        log.warn "Reolink POST failed for source ${src.id} (${src.host}): ${e.message}"
    }
    return result
}

private parseReolinkResponse(resp) {
    def raw = resp?.data?.toString()
    return raw ? new groovy.json.JsonSlurper().parseText(raw) : null
}

def reolinkApiCall(sourceId, String cmd, Map param = [:], Integer channel = null) {
    def src = getSource(sourceId)
    def token = reolinkLogin(sourceId)
    if (!token) {
        logDebug "Reolink source ${sourceId}: no token available, aborting ${cmd}"
        return null
    }

    def outcome = doReolinkApiCall(src, sourceId, cmd, token, param, channel)

    // rspCode -6 ("please login first") means the camera invalidated our session
    // before our local tokenExpires said it should -- most likely a competing
    // client (Reolink app/NVR viewing this camera) forced a fresh login on the
    // camera side. Don't wait for the next poll cycle to notice; force our own
    // fresh login and retry once now.
    if (outcome.value == null && outcome.rspCode == -6) {
        logDebug "Reolink source ${sourceId}: token rejected by camera (please login first), forcing re-login"
        src.token = null
        src.tokenExpires = 0
        def freshToken = reolinkLogin(sourceId)
        if (freshToken) {
            outcome = doReolinkApiCall(src, sourceId, cmd, freshToken, param, channel)
        }
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
            logDebug "Reolink source ${sourceId}: ${cmd} (ch ${channel}) HTTP ok but no usable value -- raw: ${result?.toString()?.take(300)}"
        } else {
            logDebug "Reolink source ${sourceId}: ${cmd} (ch ${channel}) succeeded"
        }
        return [value: value, rspCode: rspCode]
    } catch (e) {
        log.warn "Reolink cmd ${cmd} failed for source ${sourceId} ch ${channel}: ${e.message}"
        return [value: null, rspCode: null]
    }
}

// ---------- Discovery ----------

def discoverChannels(sourceId) {
    def src = getSource(sourceId)
    def channels = []
    state.lastDiscoveryError = null

    if (!src.isHub) {
        def info = reolinkApiCall(sourceId, "GetDevInfo")
        if (info == null) {
            state.lastDiscoveryError = "No response from ${src.host}. If this is a battery-class " +
                "camera or doorbell (not PoE/plug-in WiFi), it may not run a local HTTP/ONVIF " +
                "server at all -- those typically only become reachable once paired to a Home Hub or NVR."
            return channels
        }
        channels << [channel: 0, name: info?.DevInfo?.name ?: src.label, deviceType: guessDeviceType(info),
            isBattery: guessIsBattery(sourceId, 0)]
    } else {
        def status = reolinkApiCall(sourceId, "GetChannelstatus")
        if (status == null) {
            state.lastDiscoveryError = "No response from ${src.host}. Check IP/credentials."
            return channels
        }
        status?.status?.each { ch ->
            if (ch.online) {
                channels << [channel: ch.channel, name: ch.name ?: "Channel ${ch.channel}", deviceType: guessDeviceType(ch),
                    isBattery: guessIsBattery(sourceId, ch.channel)]
            }
        }
    }
    return channels
}

private String guessDeviceType(info) {
    def model = (info?.DevInfo?.model ?: info?.model ?: "").toLowerCase()
    return model.contains("doorbell") ? "doorbell" : "camera"
}

/**
 * Battery vs wired isn't reported directly by GetDevInfo/GetChannelstatus, so
 * this uses GetBatteryInfo as a signal instead: a battery-class device
 * answers it with real data, a wired/PoE device returns nothing usable.
 * TODO: verify this holds across all channel types (standalone vs behind a
 * Hub/NVR) once tested against real battery hardware -- if a wired device
 * ever answers GetBatteryInfo with a non-null placeholder value, this will
 * misclassify it and default it to the loose battery poll interval.
 */
private Boolean guessIsBattery(sourceId, channel) {
    def batt = reolinkApiCall(sourceId, "GetBatteryInfo", [:], channel)
    return batt != null
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
            def pollDefault = ch.isBattery ? (defaultBatteryPoll ?: 30) : (defaultWiredPoll ?: 3)
            child.updateSetting("pollIntervalSec", [type: "number", value: pollDefault])
            logDebug "Created child ${dni} (${driverName}), poll interval defaulted to ${pollDefault}s (${ch.isBattery ? 'battery' : 'wired'})"
        } else if (!wantIt && existing) {
            deleteChildDevice(dni)
            forgetSchedulingState(dni)
        }
    }
    initializePolling()
}

// ---------- Polling ----------

def installed() { initialize() }
def updated() { initialize() }

def initialize() {
    unschedule()
    if (!state.accessToken) {
        try {
            createAccessToken()
            logDebug "Access token created for local snapshot relay endpoint"
        } catch (e) {
            log.warn "Reolink Integration: could not create access token (needed for dashboard snapshot tiles) -- ${e.message}. " +
                "If this persists, check that OAuth is enabled for this app under Apps Code."
        }
    }
    initializePolling()
    if (debugLogging) {
        runIn(5400, "disableDebugLogging")
    }
}

def disableDebugLogging() {
    app.updateSetting("debugLogging", [type: "bool", value: false])
    log.info "Reolink Integration: debug logging auto-disabled after 90 minutes"
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
 */
def schedulerTick() {
    def nowMs = now()
    def pollDue = state.nextPollDue ?: [:]
    def snapDue = state.nextSnapshotDue ?: [:]

    getChildDevices().each { child ->
        def dni = child.deviceNetworkId
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
    }

    state.nextPollDue = pollDue
    state.nextSnapshotDue = snapDue
    runIn(1, "schedulerTick", [overwrite: true])
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

private void pollChildNow(child) {
    def sourceId = child.getDataValue("sourceId") as Integer
    def channel = child.getDataValue("channel") as Integer

    def aiState = reolinkApiCall(sourceId, "GetAiState", [:], channel)
    def mdState = reolinkApiCall(sourceId, "GetMdState", [:], channel)
    if (aiState == null && mdState == null) {
        logDebug "Reolink source ${sourceId} ch ${channel}: no response, marking asleep"
        child.markAsleep()
    } else {
        logDebug "Reolink source ${sourceId} ch ${channel}: response received, marking awake"
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
        logDebug "Reolink source ${sourceId} ch ${channel}: snapshot cache refresh failed, keeping last cached image (if any)"
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
    logDebug "Reolink ${effectiveDni}: snapshot URL built (local relay endpoint, cache refreshed on demand)"
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
        logDebug "Reolink Integration: no cached snapshot yet for ${dni} -- ${e.message}"
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
        logDebug "Reolink source ${sourceId} ch ${channel}: snapshot fetch failed, forcing re-login and retrying once"
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
                logDebug "Reolink source ${sourceId} ch ${channel}: snapshot request returned JSON instead of an image -- ${raw?.take(300)}"
            } else if (resp?.data != null) {
                def bos = new ByteArrayOutputStream()
                bos << resp.data
                result = bos.toByteArray()
                if (!result || result.length == 0) {
                    logDebug "Reolink source ${sourceId} ch ${channel}: snapshot stream drained to 0 bytes"
                    result = null
                }
            }
        }
    } catch (e) {
        log.warn "Reolink snapshot fetch failed for source ${sourceId} ch ${channel}: ${e.message}"
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

def componentCalibratePtz(child, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "PtzCheck", [:], channel)
    logDebug "Reolink source ${sourceId} ch ${channel}: PTZ calibration triggered"
}

def componentCheckPtzCalibrationStatus(child, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    def result = reolinkApiCall(sourceId, "GetPtzCheckState", [:], channel)
    def state = result?.PtzCheckState
    logDebug "Reolink source ${sourceId} ch ${channel}: PTZ calibration state = ${state}"
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
    logDebug "Set poll interval for ${c.deviceNetworkId ?: dni} to ${seconds}s"
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
    logDebug "Set snapshot interval for ${c.deviceNetworkId ?: dni} to ${seconds}s"
    if (c.deviceNetworkId) markSnapshotDueNow(c.deviceNetworkId)
}

// ---------- Logging ----------

private logDebug(msg) {
    if (debugLogging) log.debug msg
}
