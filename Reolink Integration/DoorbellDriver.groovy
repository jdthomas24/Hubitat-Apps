/**
 * Reolink Doorbell (Component Driver)
 * Version: 1.4.3
 *
 * Same delegation pattern as Reolink Camera, plus a "visitor" (button press)
 * event so Rule Machine can trigger straight off "pushed 1" for a doorbell
 * ring, separate from AI person/motion detection.
 *
 * v1.4.2 -- HOTFIX: bare paragraph("text") calls in preferences are App-DSL
 * only and don't exist on a driver's compiled script -- caused a fatal
 * "No signature of method: Script1.paragraph()" on save/update, blocking the
 * 1.4.1 update entirely (same bug as CameraDriver.groovy). Fixed via
 * input(type: "paragraph").
 * v1.4.1 -- Added chargingStatus attribute (charging/not_charging/unknown)
 * from GetBatteryInfo's Battery.chargeStatus, same confirmed field as the
 * camera driver. batteryMode self-heal was app-side only, no change needed
 * here.
 * v1.3.9 -- Added Battery capability so a battery-powered doorbell can show
 * a percentage and get pulled into the app's auto battery-check scheduler
 * (keyed off hasCapability("Battery"), no app-side change needed for that
 * part). Added receiveBatteryInfo() with the confirmed nested
 * Battery.batteryPercent field, and receiveBatteryMode() (called once at
 * device creation).
 * v1.3.8 -- No functional change (app-side: event-driven updates, PIR,
 * login/action fixes, all camera-only). Added lastUpdateSource attribute
 * (event/poll), same as the camera driver.
 * v1.3.6 -- No functional change (app-side only).
 */
metadata {
    definition(name: "Reolink Doorbell", namespace: "jdthomas24", author: "Jason", component: true) {
        capability "Motion Sensor"
        capability "PushableButton"
        capability "Refresh"
        capability "Sensor"
        // v1.3.9: added so a battery-powered doorbell can show a % and get
        // pulled into the app's auto battery-check scheduler, which keys
        // off hasCapability("Battery") rather than device type.
        capability "Battery"
        attribute "person", "enum", ["active", "inactive"]
        attribute "vehicle", "enum", ["active", "inactive"]
        attribute "pet", "enum", ["active", "inactive"]
        attribute "package", "enum", ["active", "inactive"]
        attribute "snapshotUrl", "string"
        attribute "batteryMode", "enum", ["wired", "battery", "unknown"]
        // Both "charging" and "not_charging" confirmed against real
        // hardware -- see CameraDriver.groovy's matching attribute comment.
        attribute "chargingStatus", "enum", ["unknown", "not_charging", "charging"]
        attribute "sleepStatus", "enum", ["awake", "asleep", "unknown"]
        // Tracks whether the most recent state update came from the
        // real-time event push path or the plain polling fallback.
        attribute "lastUpdateSource", "enum", ["event", "poll"]
        attribute "supportedFeatures", "string"
        command "takeSnapshot"
        command "checkAbilities", [[name: "Refreshes the supportedFeatures attribute from the doorbell's current GetAbility data"]]
        command "checkBattery", [[name: "Battery-mode devices only"]]
        command "setPollInterval", [[name: "seconds", type: "NUMBER"]]
        command "setSnapshotInterval", [[name: "seconds", type: "NUMBER"]]
    }
    preferences {
        // v1.4.2 follow-up: reordered so each paragraph header is the FIRST
        // of its own 3-item row in this 3-column grid -- see
        // CameraDriver.groovy's matching preferences comment for why
        // (input(type: "paragraph") doesn't span the full row on a driver
        // the way App-DSL paragraph() does).
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
def checkBattery() {
    parent?.componentCheckBattery(this, device.deviceNetworkId)
}
/**
 * v1.3.9: battery% reads the confirmed nested reolink_aio field
 * Battery.batteryPercent first, same fix already validated on the camera
 * driver, with flat fallbacks kept for firmware variants that return it
 * unnested. chargingStatus (v1.4.1) reads Battery.chargeStatus -- see
 * CameraDriver.groovy's matching comment for the confirmed hardware detail.
 */
def receiveBatteryInfo(battInfo) {
    def pct = battInfo?.Battery?.batteryPercent ?: battInfo?.batteryPercent ?: battInfo?.batteryPercentage
    if (pct != null) sendEvent(name: "battery", value: pct)

    def chargeStatus = battInfo?.Battery?.chargeStatus
    def chargingLabel = (chargeStatus == 1) ? "charging" : (chargeStatus == 0) ? "not_charging" : "unknown"
    if (chargeStatus != null) sendEvent(name: "chargingStatus", value: chargingLabel)
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
/** Called by the app after either a poll or a real-time event push -- see CameraDriver.groovy's matching note. */
def parseReolinkState(aiState, mdState, String source = "poll") {
    sendIfChanged("sleepStatus", "awake")
    sendIfChanged("lastUpdateSource", source)
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
/**
 * v1.3.9: called once by the app at device creation time with the discovery-
 * time battery probe result -- see CameraDriver.groovy's matching note.
 * v1.4.1: the app's scheduler can also call this later to backfill a device
 * that ended up without batteryMode set -- no change needed here either way.
 */
def receiveBatteryMode(String mode) {
    sendEvent(name: "batteryMode", value: mode)
}
def receiveSnapshotUrl(url) {
    sendEvent(name: "snapshotUrl", value: url)
}
