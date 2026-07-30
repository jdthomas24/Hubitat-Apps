/**
 * Reolink Integration (Parent App)
 * Version: 1.1.2
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
    singleThreaded: true
)

@Field static final String APP_VERSION = "1.1.2"

preferences {
    page(name: "mainPage")
    page(name: "addSourcePage")
    page(name: "discoverPage")
    page(name: "tipsPage")
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
            paragraph pillHeader("Confidence level on newer commands")
            paragraph "<b>Confirmed working</b> against real hardware: PtzCtrl -- move, and ToPos (preset recall)."
            paragraph "<b>Built but not yet tested</b> against this setup's actual firmware: SetPtzPreset " +
                "(save), SetWhiteLed (spotlight), SetIrLights (night vision), AudioAlarmPlay (siren), " +
                "GetBatteryInfo (battery %)."
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
    childrenForSource(id as Integer).each { deleteChildDevice(it.deviceNetworkId) }
    state.sources.removeAll { it.id == (id as Integer) }
    logDebug "Removed source ${id}"
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

    def p = channel != null ? param + [channel: channel] : param
    def uri = "https://${src.host}:${src.port}/cgi-bin/api.cgi?cmd=${cmd}&token=${token}"
    def body = [[cmd: cmd, action: 0, param: p]]
    def result = null
    try {
        httpPost([uri: uri, ignoreSSLIssues: true, requestContentType: "application/json",
                  body: groovy.json.JsonOutput.toJson(body), timeout: 10]) { resp -> result = parseReolinkResponse(resp) }
        logDebug "Reolink source ${sourceId}: ${cmd} (ch ${channel}) succeeded"
    } catch (e) {
        log.warn "Reolink cmd ${cmd} failed for source ${sourceId} ch ${channel}: ${e.message}"
    }
    return firstResultValue(result, src)
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
        channels << [channel: 0, name: info?.DevInfo?.name ?: src.label, deviceType: guessDeviceType(info)]
    } else {
        def status = reolinkApiCall(sourceId, "GetChannelstatus")
        if (status == null) {
            state.lastDiscoveryError = "No response from ${src.host}. Check IP/credentials."
            return channels
        }
        status?.status?.each { ch ->
            if (ch.online) {
                channels << [channel: ch.channel, name: ch.name ?: "Channel ${ch.channel}", deviceType: guessDeviceType(ch)]
            }
        }
    }
    return channels
}

private String guessDeviceType(info) {
    def model = (info?.DevInfo?.model ?: info?.model ?: "").toLowerCase()
    return model.contains("doorbell") ? "doorbell" : "camera"
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
            child.updateSetting("pollIntervalSec", [type: "number",
                value: defaultBatteryPoll ?: 30])
            logDebug "Created child ${dni} (${driverName})"
        } else if (!wantIt && existing) {
            deleteChildDevice(dni)
        }
    }
    initializePolling()
}

// ---------- Polling ----------

def installed() { initialize() }
def updated() { initialize() }

def initialize() {
    unschedule()
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
    getChildDevices().each { child ->
        scheduleChildPoll(child)
    }
}

def scheduleChildPoll(child) {
    def interval = (child.getSetting("pollIntervalSec") ?: 30) as Integer
    runIn(interval, "pollChild", [data: [dni: child.deviceNetworkId], overwrite: true])
}

def pollChild(data) {
    def child = getChildDevice(data.dni)
    if (!child) return
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

    scheduleChildPoll(child)
}

// ---------- Component callbacks (children call these via parent.X()) ----------

def componentRefresh(child) {
    pollChild([dni: child.deviceNetworkId])
}

def componentTakeSnapshot(child) {
    def sourceId = child.getDataValue("sourceId") as Integer
    def channel = child.getDataValue("channel") as Integer
    def src = getSource(sourceId)
    def token = reolinkLogin(sourceId)
    def url = "https://${src.host}:${src.port}/cgi-bin/api.cgi?cmd=Snap&channel=${channel}&token=${token}"
    logDebug "Reolink source ${sourceId} ch ${channel}: snapshot URL built, token expires in ${(src.tokenExpires - now()) / 1000}s"
    child.receiveSnapshotUrl(url)
}

def componentPtz(child, String direction) {
    def sourceId = child.getDataValue("sourceId") as Integer
    def channel = child.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "PtzCtrl", [op: direction, speed: 32], channel)
}

def componentPtzGoToPreset(child, Integer presetId) {
    def sourceId = child.getDataValue("sourceId") as Integer
    def channel = child.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "PtzCtrl", [op: "ToPos", id: presetId, speed: 32], channel)
}

def componentSavePreset(child, Integer presetId, String name) {
    def sourceId = child.getDataValue("sourceId") as Integer
    def channel = child.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "SetPtzPreset",
        [PtzPreset: [channel: channel, enable: 1, id: presetId, name: name ?: "Preset${presetId}"]], null)
}

def componentSetSpotlight(child, Boolean on) {
    def sourceId = child.getDataValue("sourceId") as Integer
    def channel = child.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "SetWhiteLed", [WhiteLed: [channel: channel, state: (on ? 1 : 0)]], null)
}

def componentSetNightVision(child, String mode) {
    def sourceId = child.getDataValue("sourceId") as Integer
    def channel = child.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "SetIrLights", [IrLights: [channel: channel, state: mode]], null)
}

def componentSetSiren(child, Boolean on) {
    def sourceId = child.getDataValue("sourceId") as Integer
    def channel = child.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "AudioAlarmPlay", [alarm_mode: "manul", manual_switch: (on ? 1 : 0), times: 2], channel)
}

def componentCheckBattery(child) {
    def sourceId = child.getDataValue("sourceId") as Integer
    def channel = child.getDataValue("channel") as Integer
    def battInfo = reolinkApiCall(sourceId, "GetBatteryInfo", [:], channel)
    child.receiveBatteryInfo(battInfo)
}

def componentSetPollInterval(child, Integer seconds) {
    child.updateSetting("pollIntervalSec", [type: "number", value: seconds])
    logDebug "Set poll interval for ${child.deviceNetworkId} to ${seconds}s"
}

// ---------- Logging ----------

private logDebug(msg) {
    if (debugLogging) log.debug msg
}
