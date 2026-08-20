/**
 * Reolink Doorbell (Component Driver)
 * Version: 1.4.1
 *
 * Same delegation pattern as Reolink Camera, plus a "visitor" (button press)
 * event so Rule Machine can trigger straight off "pushed 1" for a doorbell
 * ring, separate from AI person/motion detection.
 *
 * v1.4.1 -- chargingStatus attribute added (see receiveBatteryInfo()'s
 * comment for full details on the confirmed GetBatteryInfo chargeStatus
 * field). Everything else in this driver is unchanged from the last release -- that
 * release's fix (batteryMode self-heal in schedulerTick()) was app-side
 * only; receiveBatteryMode()/receiveBatteryInfo() already worked correctly
 * as-is.
 *
 * v1.3.8 -- kept in sync with the parent app's version. No functional change
 * to this driver -- v1.3.8's changes (event-driven updates, PIR, login/action
 * fixes) are app-side and camera-only. "lastUpdateSource" (event/poll) was
 * added here as well, same as the camera driver, so it's visible at a glance
 * which mechanism produced this doorbell's current state.
 *
 * v1.3.6 -- kept in sync with the parent app's version. No functional
 * change to this driver -- v1.3.6's changes (discovery-page toggle fix and
 * clarity improvements) are app-side only.
 */
metadata {
    definition(name: "Reolink Doorbell", namespace: "jdthomas24", author: "Jason", component: true) {
        capability "Motion Sensor"
        capability "PushableButton"
        capability "Refresh"
        capability "Sensor"
        // v1.3.9 NEW: previously this driver had no way to check or show a
        // battery level at all, even for genuinely battery-powered doorbell
        // hardware (e.g. Doorbell Battery, Gen 2 doorbells) -- batteryMode
        // could say "battery" but there was nowhere to see the actual
        // percentage. Adding this capability also automatically pulls this
        // driver into the app's existing auto battery-check scheduler,
        // which keys off hasCapability("Battery") rather than device type,
        // so no app-side changes were needed for that part.
        capability "Battery"
        attribute "person", "enum", ["active", "inactive"]
        attribute "vehicle", "enum", ["active", "inactive"]
        attribute "pet", "enum", ["active", "inactive"]
        attribute "package", "enum", ["active", "inactive"]
        attribute "snapshotUrl", "string"
        attribute "batteryMode", "enum", ["wired", "battery", "unknown"]
        // NEW (2026-08-19): see CameraDriver.groovy's matching attribute
        // comment -- both "charging" and "not_charging" confirmed against
        // real hardware.
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
        input name: "pollIntervalSec", type: "number", title: "Poll interval (sec)", defaultValue: 5,
            description: "Controls how often motion/AI/visitor state is polled. Does NOT control snapshot image " +
                "freshness -- see Snapshot interval below."
        input name: "snapshotIntervalSec", type: "number", title: "Snapshot interval (sec)", defaultValue: 30,
            description: "Controls how often the cached dashboard snapshot image refreshes. A dashboard tile's " +
                "own refresh rate does NOT make the image any fresher than this -- it just re-displays whatever " +
                "was last cached at this interval. Kept separate from poll interval so motion/visitor detection " +
                "can stay fast without forcing a full image download that often."
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
 * v1.3.9 NEW: previously this driver had no way to receive a battery
 * result at all -- calling Check Battery on a doorbell would have thrown,
 * since the app's componentCheckBattery() calls c.receiveBatteryInfo(...)
 * generically on whatever child it's given. Same field-nesting fix already
 * validated on the camera driver: the confirmed reolink_aio field is
 * Battery.batteryPercent, checked first, with flat fallbacks kept in case
 * some firmware variant returns it unnested.
 */
def receiveBatteryInfo(battInfo) {
    def pct = battInfo?.Battery?.batteryPercent ?: battInfo?.batteryPercent ?: battInfo?.batteryPercentage
    if (pct != null) sendEvent(name: "battery", value: pct)

    // NEW (2026-08-19): see CameraDriver.groovy's matching receiveBatteryInfo()
    // comment for full context -- both chargeStatus values (1=charging,
    // 0=not charging) confirmed via real hardware, captured back-to-back
    // plugged in vs unplugged on the same device.
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
 * v1.3.9 NEW: called once by the app at device creation time with the
 * result of the discovery-time battery probe -- see CameraDriver.groovy's
 * matching note. Now that this driver has capability Battery, a battery-
 * mode doorbell also gets the same periodic auto-check as cameras. (v1.4.1:
 * the app's scheduler can now also call this once, later, to backfill a
 * device that somehow ended up without batteryMode set at all -- see
 * ParentApp.groovy's schedulerTick() v1.4.1 note. No change needed here
 * either way.)
 */
def receiveBatteryMode(String mode) {
    sendEvent(name: "batteryMode", value: mode)
}
def receiveSnapshotUrl(url) {
    sendEvent(name: "snapshotUrl", value: url)
}
