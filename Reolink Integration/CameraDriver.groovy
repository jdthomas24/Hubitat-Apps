/**
 * Reolink Camera (Component Driver)
 * Version: 1.2.3 -- kept in sync with the parent app's version.
 * Thin device: no HTTP of its own. Everything delegates to the parent app via
 * parent.componentX(this, ...). The app knows which source/channel this device
 * maps to (stored as data values sourceId/channel) and does the actual API call.
 *
 * No functional change from 1.2.1 -- the snapshot fixes in 1.2.2/1.2.3 are
 * entirely on the app side (snapshotUrl points at a local relay endpoint
 * instead of a camera URL with a baked-in token, and the relay endpoint
 * itself now correctly drains the image stream). This driver still just
 * displays whatever URL the app hands back via receiveSnapshotUrl().
 * Also (still 1.2.3): added an inline description under pollIntervalSec
 * clarifying that it, not a dashboard tile's own refresh rate, controls
 * snapshot image freshness -- came up after a user assumed the tile's
 * refresh setting alone would keep the image live.
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

        // ---- Core ----
        command "takeSnapshot"
        command "checkBattery", [[name: "Battery-mode devices only"]]
        command "setPollInterval", [[name: "seconds", type: "NUMBER"]]

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
            description: "Controls how often this device is polled AND how often its snapshot image refreshes. " +
                "A dashboard tile's own refresh rate does NOT make the image any fresher than this -- it just " +
                "re-displays whatever was last cached at this interval."
    }
}

def refresh() {
    parent?.componentRefresh(this)
}

def takeSnapshot() {
    parent?.componentTakeSnapshot(this, device.deviceNetworkId)
}

def ptz(direction) {
    parent?.componentPtz(this, direction)
}

def ptzGoToPreset(presetId) {
    parent?.componentPtzGoToPreset(this, presetId as Integer)
}

def savePresetHere(presetId, name = null) {
    parent?.componentSavePreset(this, presetId as Integer, name)
}

def spotlightOn() {
    parent?.componentSetSpotlight(this, true)
    sendEvent(name: "spotlight", value: "on")
}

def spotlightOff() {
    parent?.componentSetSpotlight(this, false)
    sendEvent(name: "spotlight", value: "off")
}

def setNightVision(mode) {
    parent?.componentSetNightVision(this, mode)
    sendEvent(name: "nightVision", value: mode)
}

def sirenOn() {
    parent?.componentSetSiren(this, true)
    sendEvent(name: "siren", value: "on")
}

def sirenOff() {
    parent?.componentSetSiren(this, false)
    sendEvent(name: "siren", value: "off")
}

def checkBattery() {
    parent?.componentCheckBattery(this)
}

/** Called by the app after GetBatteryInfo. Field names are a TODO -- see app comment. */
def receiveBatteryInfo(battInfo) {
    // TODO confirm field names once seen against a real battery-mode device
    def pct = battInfo?.batteryPercent ?: battInfo?.batteryPercentage
    if (pct != null) sendEvent(name: "battery", value: pct)
}

def calibratePtz() {
    parent?.componentCalibratePtz(this)
}

def checkPtzCalibrationStatus() {
    parent?.componentCheckPtzCalibrationStatus(this)
}

/** Called by the app after GetPtzCheckState. 0=required, 1=running, 2=done. */
def receivePtzCalibrationState(state) {
    def statusMap = [0: "required", 1: "running", 2: "done"]
    sendEvent(name: "ptzCalibrationStatus", value: statusMap[state] ?: "unknown")
}

def setPollInterval(seconds) {
    parent?.componentSetPollInterval(this, seconds as Integer)
}

/** Called by the app after it polls GetAiState/GetMdState for this channel. */
def parseReolinkState(aiState, mdState) {
    sendEvent(name: "sleepStatus", value: "awake")

    // TODO map real field names once GetAiState/GetMdState payloads are confirmed
    def motionActive = mdState?.state == 1
    sendEvent(name: "motion", value: motionActive ? "active" : "inactive")

    ["people", "vehicle", "dog_cat"].each { key ->
        def attr = key == "people" ? "person" : (key == "dog_cat" ? "pet" : key)
        def active = aiState?.getAt(key)?.alarm_state == 1
        sendEvent(name: attr, value: active ? "active" : "inactive")
    }
    def pkgActive = aiState?.package?.alarm_state == 1
    sendEvent(name: "package", value: pkgActive ? "active" : "inactive")
}

/**
 * Called by the app when a poll gets no response at all. For a wired device this
 * usually means a real problem; for a battery device it usually just means it
 * hasn't checked in since its last event or self-wake. This does NOT flip
 * motion/person/etc back to inactive -- those keep their last-known value,
 * since "no response" isn't the same as "no longer detected."
 */
def markAsleep() {
    sendEvent(name: "sleepStatus", value: "asleep")
}

def receiveSnapshotUrl(url) {
    sendEvent(name: "snapshotUrl", value: url)
}
