/**
 * Reolink Camera (Component Driver)
 * Version: 1.3.0 -- kept in sync with the parent app's version.
 * Thin device: no HTTP of its own. Everything delegates to the parent app via
 * parent.componentX(this, ...). The app knows which source/channel this device
 * maps to (stored as data values sourceId/channel) and does the actual API call.
 *
 * v1.3.0 -- added the supportedFeatures attribute and checkAbilities command
 * (see receiveSupportedFeatures() below). Populated by the app from Reolink's
 * GetAbility API, informational only -- see the app's Tips page for details.
 * No other functional change from 1.2.5.
 *
 * v1.2.4 -- parseReolinkState()/markAsleep() now only call sendEvent() when
 * a value actually changed, instead of unconditionally on every poll. Found
 * after a user on a lower-spec hub (base C-8, vs. a C-8 Pro on the hub this
 * was developed against) hit repeated "excessive hub load" errors on this
 * method -- 6 unconditional sendEvent() calls every poll (every 3s on a
 * wired camera) is real, avoidable load, more likely to trip Hubitat's
 * governor on a hub with less headroom. See sendIfChanged() below.
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
        attribute "sleepStatus", "enum", ["awake", "asleep", "unknown"]
        attribute "spotlight", "enum", ["on", "off"]
        attribute "nightVision", "enum", ["auto", "on", "off"]
        attribute "siren", "enum", ["on", "off"]
        attribute "ptzCalibrationStatus", "enum", ["unknown", "required", "running", "done"]
        attribute "supportedFeatures", "string"

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

def checkBattery() {
    parent?.componentCheckBattery(this, device.deviceNetworkId)
}

/** Called by the app after GetBatteryInfo. Field names are a TODO -- see app comment. */
def receiveBatteryInfo(battInfo) {
    // TODO confirm field names once seen against a real battery-mode device
    def pct = battInfo?.batteryPercent ?: battInfo?.batteryPercentage
    if (pct != null) sendEvent(name: "battery", value: pct)
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

/** Called by the app after it polls GetAiState/GetMdState for this channel. */
def parseReolinkState(aiState, mdState) {
    sendIfChanged("sleepStatus", "awake")

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
 * current state. Found in the field: parseReolinkState() was calling
 * sendEvent() unconditionally for 6 attributes on EVERY poll (every 3s for a
 * wired camera), whether or not anything changed. sendEvent() isn't free --
 * event history, subscribed rule evaluation, etc. -- and a hub with less
 * headroom (e.g. a base C-8 vs. a C-8 Pro) can trip Hubitat's own
 * "excessive hub load" protection on that volume of calls where a
 * higher-spec hub doesn't. This cuts sendEvent() volume to only what
 * actually changes, on every hub regardless of spec.
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
