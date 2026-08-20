/**
 * Reolink Camera (Component Driver)
 * Version: 1.4.1
 *
 * Thin device: no HTTP of its own. Everything delegates to the parent app via
 * parent.componentX(this, ...). The app knows which source/channel this device
 * maps to (stored as data values sourceId/channel) and does the actual API call.
 *
 * v1.4.1 -- chargingStatus attribute added (see receiveBatteryInfo()'s
 * comment for full details on the confirmed GetBatteryInfo chargeStatus
 * field). Everything else in this driver is unchanged from the last release -- that
 * release's fix (batteryMode self-heal in schedulerTick()) was app-side
 * only; receiveBatteryMode() already worked correctly as-is.
 *
 * v1.3.8:
 *  1. NEW: PIR enable/disable -- pirOn()/pirOff() commands plus a pirEnabled
 *     attribute (real boolean, usable directly in Rule Machine conditions)
 *     and a pirStatusNote attribute (plain-text readable note, clears when
 *     PIR is back on). Manual on/off only, no auto-revert timer -- an
 *     "auto re-enable above threshold Y" need is already fully served by
 *     Rule Machine using the existing battery attribute, no extra driver
 *     logic needed. Note: turning PIR off does NOT stop an in-progress
 *     recording -- it removes the trigger that would have woken a battery
 *     camera to record in the first place.
 *  2. NEW: "lastUpdateSource" attribute (event/poll) -- shows at a glance
 *     whether this device's current state came from the real-time event
 *     push path or the polling fallback. Set inside parseReolinkState() via
 *     an optional third parameter that defaults to "poll".
 *
 * v1.3.6 -- kept in sync with the parent app's version. No functional
 * change to this driver -- v1.3.6's changes (discovery-page toggle fix and
 * clarity improvements) are app-side only.
 */
metadata {
    definition(name: "Reolink Camera", namespace: "jdthomas24", author: "Jason", component: true) {
        capability "Motion Sensor"
        capability "Refresh"
        capability "Sensor"
        capability "Battery"

        attribute "person", "enum", ["active", "inactive"]
        attribute "vehicle", "enum", ["active", "inactive"]
        attribute "pet", "enum", ["active", "inactive"]
        attribute "package", "enum", ["active", "inactive"]
        attribute "snapshotUrl", "string"
        attribute "batteryMode", "enum", ["wired", "battery", "unknown"]
        // NEW (2026-08-19): both "charging" and "not_charging" confirmed
        // against real hardware -- see receiveBatteryInfo()'s comment.
        attribute "chargingStatus", "enum", ["unknown", "not_charging", "charging"]
        attribute "sleepStatus", "enum", ["awake", "asleep", "unknown"]
        // Tracks whether the most recent state update came from the
        // real-time event push path or the plain polling fallback.
        attribute "lastUpdateSource", "enum", ["event", "poll"]
        attribute "spotlight", "enum", ["on", "off"]
        attribute "nightVision", "enum", ["auto", "on", "off"]
        attribute "siren", "enum", ["on", "off"]
        attribute "ptzCalibrationStatus", "enum", ["unknown", "required", "running", "done"]
        attribute "supportedFeatures", "string"
        attribute "pirEnabled", "enum", ["true", "false"]
        attribute "pirStatusNote", "string"

        // ---- Core ----
        command "takeSnapshot"
        command "checkBattery", [[name: "Battery-mode devices only"]]
        command "checkAbilities", [[name: "Refreshes the supportedFeatures attribute from the camera's current GetAbility data"]]
        command "setPollInterval", [[name: "seconds", type: "NUMBER"]]
        command "setSnapshotInterval", [[name: "seconds", type: "NUMBER"]]

        // ---- PTZ (pan/tilt/zoom cameras only, e.g. Trackmix, E1 Zoom) ----
        command "ptz", [[name: "direction", type: "ENUM",
            constraints: ["Left", "Right", "Up", "Down", "ZoomInc", "ZoomDec", "Stop"]]]
        command "ptzGoToPreset", [[name: "presetId", type: "NUMBER",
            description: "Preset ID set up in the Reolink app (e.g. 1 for your 'home' position)"]]
        command "savePresetHere", [[name: "presetId", type: "NUMBER",
            description: "Saves the camera's CURRENT position as this preset ID"],
            [name: "name", type: "STRING", description: "Optional preset name"]]
        command "calibratePtz", [[name: "PTZ cameras only -- recalibrates pan/tilt to fix preset drift over time"]]
        command "checkPtzCalibrationStatus", [[name: "PTZ cameras only"]]

        // ---- Accessories (model-dependent -- not every camera has these) ----
        command "spotlightOn", [[name: "Spotlight-equipped cameras only"]]
        command "spotlightOff", [[name: "Spotlight-equipped cameras only"]]
        command "setNightVision", [[name: "mode", type: "ENUM", constraints: ["auto", "on", "off"]]]
        command "sirenOn", [[name: "Siren-equipped cameras only"]]
        command "sirenOff", [[name: "Siren-equipped cameras only"]]
        command "pirOn", [[name: "Enables the PIR motion trigger"]]
        command "pirOff", [[name: "Disables the PIR motion trigger -- does not stop an in-progress recording"]]
    }
    preferences {
        input name: "pollIntervalSec", type: "number", title: "Poll interval (sec)", defaultValue: 30,
            description: "Controls how often motion/AI state is polled. Does NOT control snapshot image " +
                "freshness -- see Snapshot interval below."
        input name: "snapshotIntervalSec", type: "number", title: "Snapshot interval (sec)", defaultValue: 30,
            description: "Controls how often the cached dashboard snapshot image refreshes. A dashboard tile's " +
                "own refresh rate does NOT make the image any fresher than this -- it just re-displays whatever " +
                "was last cached at this interval. Kept separate from poll interval so motion detection can " +
                "stay fast without forcing a full image download that often."
        input name: "batteryCheckEnabled", type: "bool", title: "Enable auto battery check", defaultValue: false,
            description: "Battery devices only, OFF by default. When ON, auto-checks and updates battery level " +
                "on the interval below. Checking briefly wakes the device (negligible power at default " +
                "interval). Ignored for wired devices. Check Battery still works manually any time regardless " +
                "of this setting."
        input name: "batteryCheckIntervalHours", type: "number", title: "Auto battery check interval (hours)", defaultValue: 12,
            description: "Only used if the setting above is ON."
        input name: "checkBatteryOnEventWake", type: "bool", title: "Also check battery/charging on real motion/AI events", defaultValue: false,
            description: "Battery devices only, OFF by default. When ON, a real motion/AI event (device " +
                "already awake) also triggers a battery/charging check -- free, unlike the interval above, " +
                "since it doesn't force an extra wakeup."
        input name: "eventWakeBatteryThrottleSec", type: "number", title: "Minimum seconds between event-triggered checks", defaultValue: 60,
            description: "Only used if the setting above is ON. Keeps a burst of events (motion, person, " +
                "vehicle in seconds) from triggering more than one check."
    }
}

def refresh() {
    parent?.componentRefresh(this, device.deviceNetworkId)
}

def takeSnapshot() {
    parent?.componentTakeSnapshot(this, device.deviceNetworkId)
}

def ptz(direction) {
    parent?.componentPtz(this, direction, device.deviceNetworkId)
}

def ptzGoToPreset(presetId) {
    parent?.componentPtzGoToPreset(this, presetId as Integer, device.deviceNetworkId)
}

def savePresetHere(presetId, name = null) {
    parent?.componentSavePreset(this, presetId as Integer, name, device.deviceNetworkId)
}

def spotlightOn() {
    parent?.componentSetSpotlight(this, true, device.deviceNetworkId)
    sendEvent(name: "spotlight", value: "on")
}

def spotlightOff() {
    parent?.componentSetSpotlight(this, false, device.deviceNetworkId)
    sendEvent(name: "spotlight", value: "off")
}

def setNightVision(mode) {
    parent?.componentSetNightVision(this, mode, device.deviceNetworkId)
    sendEvent(name: "nightVision", value: mode)
}

def sirenOn() {
    parent?.componentSetSiren(this, true, device.deviceNetworkId)
    sendEvent(name: "siren", value: "on")
}

def sirenOff() {
    parent?.componentSetSiren(this, false, device.deviceNetworkId)
    sendEvent(name: "siren", value: "off")
}

/**
 * Disables the PIR motion trigger. Logged at warn the moment it's toggled
 * off, since this is a meaningful change to the device's behavior worth
 * seeing even at default logging. Does NOT stop an in-progress recording --
 * only removes the trigger that would have woken a battery camera to record
 * in the first place. If anything else on this camera is separately
 * configured for continuous/scheduled recording outside PIR triggering,
 * that recording is unaffected.
 */
def pirOff() {
    parent?.componentSetPir(this, false, device.deviceNetworkId)
    sendEvent(name: "pirEnabled", value: "false")
    sendEvent(name: "pirStatusNote", value: "⏸️ PIR off, motion suppressed")
    log.warn "${device.displayName}: PIR disabled -- motion trigger suppressed until turned back on"
}

def pirOn() {
    parent?.componentSetPir(this, true, device.deviceNetworkId)
    sendEvent(name: "pirEnabled", value: "true")
    sendEvent(name: "pirStatusNote", value: "")
}

def checkBattery() {
    parent?.componentCheckBattery(this, device.deviceNetworkId)
}

/**
 * Called by the app after GetBatteryInfo. Field name confirmed via Reolink's
 * own officially-backed reolink_aio library: response value is
 * Battery.batteryPercent. The batteryPercentage fallback is kept just in
 * case a firmware variant uses it.
 */
/**
 * v1.3.9: called once by the app at device creation time with the result of
 * the discovery-time battery probe -- the batteryMode attribute was
 * declared but never actually populated before this. Not called again
 * afterward under normal circumstances; batteryMode reflects what was true
 * at creation. (v1.4.1: the app's scheduler can now also call this once,
 * later, to backfill a device that somehow ended up without batteryMode set
 * at all -- see ParentApp.groovy's schedulerTick() v1.4.1 note. No change
 * needed here either way, this method's job stays the same.)
 */
def receiveBatteryMode(String mode) {
    sendEvent(name: "batteryMode", value: mode)
}

def receiveBatteryInfo(battInfo) {
    // FIXED (2026-08-17): a real Check Battery run against known battery
    // hardware showed "GetBatteryInfo succeeded" in the app's log, but the
    // battery attribute here never updated -- this comment has always
    // documented the confirmed reolink_aio field as nested under
    // "Battery.batteryPercent", but the code below was reading it flat
    // (battInfo.batteryPercent) instead. Checking the nested path first
    // fixes real data; the flat checks stay as fallbacks in case some
    // firmware variant genuinely returns it unnested.
    def pct = battInfo?.Battery?.batteryPercent ?: battInfo?.batteryPercent ?: battInfo?.batteryPercentage
    if (pct != null) sendEvent(name: "battery", value: pct)

    // NEW (2026-08-19): chargeStatus field confirmed via two real GetBatteryInfo
    // responses captured back-to-back on the same device, plugged in vs
    // unplugged: [Battery:[adapterStatus:1, batteryPercent:42, batteryVersion:2,
    // chargeStatus:1, current:216, ...]] while charging, and
    // [Battery:[adapterStatus:0, batteryPercent:43, ..., chargeStatus:0,
    // current:-379, ...]] once unplugged. Both chargeStatus values (1=charging,
    // 0=not charging) are now directly confirmed, not inferred -- the current
    // field's sign flip (+216 vs -379) and adapterStatus's matching 1/0 flip
    // independently corroborate the same conclusion. Any other chargeStatus
    // value maps to "unknown" rather than guessing a label for it.
    // adapterStatus (whether external power is connected at all, independent
    // of active charging) is present in the same response but not yet
    // exposed as its own attribute -- worth adding later if useful, now that
    // its behavior is also confirmed here.
    def chargeStatus = battInfo?.Battery?.chargeStatus
    def chargingLabel = (chargeStatus == 1) ? "charging" : (chargeStatus == 0) ? "not_charging" : "unknown"
    if (chargeStatus != null) sendEvent(name: "chargingStatus", value: chargingLabel)
}

def checkAbilities() {
    parent?.componentCheckAbilities(this, device.deviceNetworkId)
}

/**
 * Called by the app after GetAbility, both at discovery/creation time and on
 * a manual checkAbilities command. Informational only -- see the app's Tips
 * page ("Supported Features") for what this does and doesn't mean. Does NOT
 * hide or disable any command on this device; Hubitat has no way to do that
 * for an individual device instance.
 */
def receiveSupportedFeatures(List features) {
    sendEvent(name: "supportedFeatures", value: features ? features.join(", ") : "None detected")
}

def calibratePtz() {
    parent?.componentCalibratePtz(this, device.deviceNetworkId)
}

def checkPtzCalibrationStatus() {
    parent?.componentCheckPtzCalibrationStatus(this, device.deviceNetworkId)
}

/** Called by the app after GetPtzCheckState. 0=required, 1=running, 2=done. */
def receivePtzCalibrationState(state) {
    def statusMap = [0: "required", 1: "running", 2: "done"]
    sendEvent(name: "ptzCalibrationStatus", value: statusMap[state] ?: "unknown")
}

def setPollInterval(seconds) {
    parent?.componentSetPollInterval(this, seconds as Integer, device.deviceNetworkId)
}

def setSnapshotInterval(seconds) {
    parent?.componentSetSnapshotInterval(this, seconds as Integer, device.deviceNetworkId)
}

/**
 * Called by the app after either a poll (GetAiState/GetMdState) or a real-
 * time event push -- source defaults to "poll" so the existing polling call
 * site needs no change; the app's event path explicitly passes "event".
 */
def parseReolinkState(aiState, mdState, String source = "poll") {
    sendIfChanged("sleepStatus", "awake")
    sendIfChanged("lastUpdateSource", source)

    // TODO map real field names once GetAiState/GetMdState payloads are confirmed
    def motionActive = mdState?.state == 1
    sendIfChanged("motion", motionActive ? "active" : "inactive")

    ["people", "vehicle", "dog_cat"].each { key ->
        def attr = key == "people" ? "person" : (key == "dog_cat" ? "pet" : key)
        def active = aiState?.getAt(key)?.alarm_state == 1
        sendIfChanged(attr, active ? "active" : "inactive")
    }
    def pkgActive = aiState?.package?.alarm_state == 1
    sendIfChanged("package", pkgActive ? "active" : "inactive")
}

/**
 * Only calls sendEvent() when the value actually changed from the device's
 * current state -- sendEvent() isn't free (event history, subscribed rule
 * evaluation, etc.), and calling it unconditionally on every poll can trip
 * Hubitat's "excessive hub load" protection on a lower-spec hub.
 */
private void sendIfChanged(String name, value) {
    if (device.currentValue(name)?.toString() != value?.toString()) {
        sendEvent(name: name, value: value)
    }
}

/**
 * Called by the app when a poll gets no response at all. For a wired device this
 * usually means a real problem; for a battery device it usually just means it
 * hasn't checked in since its last event or self-wake. This does NOT flip
 * motion/person/etc back to inactive -- those keep their last-known value,
 * since "no response" isn't the same as "no longer detected."
 */
def markAsleep() {
    sendIfChanged("sleepStatus", "asleep")
}

def receiveSnapshotUrl(url) {
    sendEvent(name: "snapshotUrl", value: url)
}
