/**
 * Reolink Camera (Component Driver)
 * Version: 1.4.5
 *
 * Thin device: no HTTP of its own. Delegates everything to the parent app via
 * parent.componentX(this, ...), using data values sourceId/channel to
 * identify which source/channel this device maps to.
 *
 * v1.4.2 -- HOTFIX: bare paragraph("text") calls in preferences are App-DSL
 * only and don't exist on a driver's compiled script -- caused a fatal
 * "No signature of method: Script1.paragraph()" on save/update, blocking the
 * 1.4.1 update entirely. Fixed via input(type: "paragraph").
 * v1.4.1 -- Added chargingStatus attribute (charging/not_charging/unknown)
 * from GetBatteryInfo's Battery.chargeStatus, confirmed against real
 * hardware (plugged in vs. unplugged). batteryMode self-heal was app-side
 * only, no change needed here.
 * v1.3.9 -- batteryMode can be backfilled by the app's scheduler if ever
 * left unset; no change needed in this file for that.
 * Full history prior to 1.3.9 is in GitHub commit history.
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
        // v1.4.2 hotfix note: switched from bare paragraph("text") (App-DSL-
        // only) to the driver-compatible input(type: "paragraph") form.
        // v1.4.2 follow-up: reordered so each header is the FIRST of its own
        // 3-item row in this 3-column grid. Unlike an App's paragraph(),
        // input(type: "paragraph") on a driver does NOT span the full row --
        // it's an ordinary single-column cell -- so a header only lines up
        // correctly with its own toggle+interval when the group is exactly
        // 3 items long and starts at column 1. Poll/Snapshot interval moved
        // to the end for the same reason (2 items, cleanly fills the last row).
        input name: "battChkHdr", type: "paragraph", title: "<b>Scheduled battery check</b>"
        input name: "batteryCheckEnabled", type: "bool", title: "Enable auto battery check", defaultValue: false,
            description: "Battery devices only, OFF by default. When ON, auto-checks and updates battery level " +
                "on the interval below. Checking briefly wakes the device (negligible power at default " +
                "interval). Ignored for wired devices. Check Battery still works manually any time regardless " +
                "of this setting."
        input name: "batteryCheckIntervalHours", type: "number", title: "Auto battery check interval (hours)", defaultValue: 12,
            description: "Only used if the setting above is ON."
        input name: "eventWakeHdr", type: "paragraph", title: "<b>Event-triggered battery check</b>"
        input name: "checkBatteryOnEventWake", type: "bool", title: "Also check battery/charging on real motion/AI events", defaultValue: false,
            description: "Battery devices only, OFF by default. When ON, a real motion/AI event (device " +
                "already awake) also triggers a battery/charging check -- free, unlike the interval above, " +
                "since it doesn't force an extra wakeup."
        input name: "eventWakeBatteryThrottleSec", type: "number", title: "Minimum seconds between event-triggered checks", defaultValue: 60,
            description: "Only used if the setting above is ON. Keeps a burst of events (motion, person, " +
                "vehicle in seconds) from triggering more than one check."
        input name: "pollIntervalSec", type: "number", title: "Poll interval (sec)", defaultValue: 30,
            description: "Controls how often motion/AI state is polled. Does NOT control snapshot image " +
                "freshness -- see Snapshot interval below."
        input name: "snapshotIntervalSec", type: "number", title: "Snapshot interval (sec)", defaultValue: 30,
            description: "Controls how often the cached dashboard snapshot image refreshes. A dashboard tile's " +
                "own refresh rate does NOT make the image any fresher than this -- it just re-displays whatever " +
                "was last cached at this interval. Kept separate from poll interval so motion detection can " +
                "stay fast without forcing a full image download that often."
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
 * v1.3.9: called by the app at device creation time with the discovery-time
 * battery probe result (batteryMode was declared but never populated before
 * this). v1.4.1: the app's scheduler can also call this later to backfill a
 * device that ended up without batteryMode set -- see ParentApp.groovy's
 * schedulerTick(). Method's job is unchanged either way.
 */
def receiveBatteryMode(String mode) {
    sendEvent(name: "batteryMode", value: mode)
}

/**
 * Called by the app after GetBatteryInfo. battery% ("2026-08-17" fix) reads
 * the confirmed reolink_aio field Battery.batteryPercent nested first (flat
 * fallbacks kept for firmware variants that return it unnested). chargingStatus
 * reads Battery.chargeStatus (1=charging, 0=not_charging -- both confirmed
 * against real hardware, corroborated by current's sign flip and adapterStatus;
 * any other value maps to "unknown"). adapterStatus itself isn't its own
 * attribute yet.
 */
def receiveBatteryInfo(battInfo) {
    def pct = battInfo?.Battery?.batteryPercent ?: battInfo?.batteryPercent ?: battInfo?.batteryPercentage
    if (pct != null) sendEvent(name: "battery", value: pct)

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
