/**
 * Reolink Doorbell (Component Driver)
 * Version: 1.3.1 -- kept in sync with the parent app's version.
 * Same delegation pattern as Reolink Camera, plus a "visitor" (button press)
 * event so Rule Machine can trigger straight off "pushed 1" for a doorbell ring,
 * separate from AI person/motion detection.
 *
 * v1.3.1 -- Fixed a bug: this driver declares capability "PushableButton"
 * (needed for the pushed/numberOfButtons attributes) but never implemented
 * the push() command the capability requires. Declaring a capability adds
 * its commands to the device page -- it does NOT auto-implement them, so
 * clicking Push on the Commands tab (or any app/rule calling push()) threw
 * a MissingMethodException. Added push(buttonNumber) below and routed the
 * real doorbell-press event through it instead of duplicating the same
 * sendEvent() call in two places.
 * Also (still v1.3.1, app-side): fixed the app's supportedFeatures
 * detection under-reporting a doorbell's light -- doorbells report it under
 * supportDoorbellLight, not supportFLswitch like cameras do. No change
 * needed in this driver file for that fix, noted here for the version
 * history.
 *
 * v1.3.0 -- added the supportedFeatures attribute and checkAbilities command
 * (see receiveSupportedFeatures() below), same as the camera driver. Package
 * detection's exact GetAbility field name (supportAiPackage) has since been
 * confirmed against real doorbell hardware. No other functional change from
 * 1.2.5.
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
        attribute "supportedFeatures", "string"
        command "takeSnapshot"
        command "checkAbilities", [[name: "Refreshes the supportedFeatures attribute from the doorbell's current GetAbility data"]]
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
def checkAbilities() {
    parent?.componentCheckAbilities(this, device.deviceNetworkId)
}
/**
 * Required by the PushableButton capability -- declaring the capability adds
 * the Push command/attributes to the device page, but does NOT auto-implement
 * this method; without it, clicking Push (or any app/rule calling push())
 * throws MissingMethodException. Untyped buttonNumber parameter deliberately
 * -- Hubitat's own Commands-tab test UI can pass this as a String rather than
 * a Number, and a typed/coerced parameter would reject that.
 */
def push(buttonNumber) {
    sendEvent(name: "pushed", value: buttonNumber, isStateChange: true)
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
/** Called by the app after it polls GetAiState/GetMdState/visitor state for this channel. */
def parseReolinkState(aiState, mdState) {
    sendIfChanged("sleepStatus", "awake")
    // TODO confirm the visitor/doorbell-press field name in your firmware's GetAiState/GetMdState payload
    def visitorPressed = aiState?.visitor?.alarm_state == 1
    if (visitorPressed) {
        push(1)
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
