/**
 * Reolink Doorbell (Component Driver)
 * Version: 1.2.4 -- kept in sync with the parent app's version.
 * Same delegation pattern as Reolink Camera, plus a "visitor" (button press)
 * event so Rule Machine can trigger straight off "pushed 1" for a doorbell ring,
 * separate from AI person/motion detection.
 *
 * No functional change from 1.2.1 -- see the app's 1.2.2/1.2.3 notes for the
 * snapshot relay fix. This driver still just displays whatever URL the app
 * hands back via receiveSnapshotUrl().
 * Also (still 1.2.3): added an inline description under pollIntervalSec
 * clarifying that it, not a dashboard tile's own refresh rate, controls
 * snapshot image freshness.
 *
 * v1.2.4 -- see camera driver's 1.2.4 note -- same sendIfChanged() fix
 * applied here to cut redundant sendEvent() load on lower-spec hubs.
 */
metadata {
    definition(name: "Reolink Doorbell", namespace: "jdthomas24", author: "Jason", component: true) {
        capability "Motion Sensor"
        capability "PushableButton"
        capability "Refresh"
        capability "Sensor"
        attribute "person", "enum", ["active", "inactive"]
        attribute "vehicle", "enum", ["active", "inactive"]
        attribute "pet", "enum", ["active", "inactive"]
        attribute "package", "enum", ["active", "inactive"]
        attribute "snapshotUrl", "string"
        attribute "batteryMode", "enum", ["wired", "battery", "unknown"]
        attribute "sleepStatus", "enum", ["awake", "asleep", "unknown"]
        command "takeSnapshot"
        command "setPollInterval", [[name: "seconds", type: "NUMBER"]]
        command "setSnapshotInterval", [[name: "seconds", type: "NUMBER"]]
    }
    preferences {
        input name: "pollIntervalSec", type: "number", title: "Poll interval (sec)", defaultValue: 5,
            description: "Controls how often motion/AI/visitor state is polled. Does NOT control snapshot image " +
                "freshness -- see Snapshot interval below."
        input name: "snapshotIntervalSec", type: "number", title: "Snapshot interval (sec)", defaultValue: 30,
            description: "Controls how often the cached dashboard snapshot image refreshes. A dashboard tile's " +
                "own refresh rate does NOT make the image any fresher than this -- it just re-displays whatever " +
                "was last cached at this interval. Kept separate from poll interval so motion/visitor detection " +
                "can stay fast without forcing a full image download that often."
    }
}
def installed() {
    sendEvent(name: "numberOfButtons", value: 1)
}
def refresh() {
    parent?.componentRefresh(this, device.deviceNetworkId)
}
def takeSnapshot() {
    parent?.componentTakeSnapshot(this, device.deviceNetworkId)
}
def setPollInterval(seconds) {
    parent?.componentSetPollInterval(this, seconds as Integer, device.deviceNetworkId)
}
def setSnapshotInterval(seconds) {
    parent?.componentSetSnapshotInterval(this, seconds as Integer, device.deviceNetworkId)
}
/** Called by the app after it polls GetAiState/GetMdState/visitor state for this channel. */
def parseReolinkState(aiState, mdState) {
    sendIfChanged("sleepStatus", "awake")
    // TODO confirm the visitor/doorbell-press field name in your firmware's GetAiState/GetMdState payload
    def visitorPressed = aiState?.visitor?.alarm_state == 1
    if (visitorPressed) {
        sendEvent(name: "pushed", value: "1", isStateChange: true)
    }
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
/** See camera driver for why this exists -- cuts redundant sendEvent() calls to reduce load on lower-spec hubs. */
private void sendIfChanged(String name, value) {
    if (device.currentValue(name)?.toString() != value?.toString()) {
        sendEvent(name: name, value: value)
    }
}
/** Called by the app when a poll gets no response -- see camera driver for the reasoning. */
def markAsleep() {
    sendIfChanged("sleepStatus", "asleep")
}
def receiveSnapshotUrl(url) {
    sendEvent(name: "snapshotUrl", value: url)
}
