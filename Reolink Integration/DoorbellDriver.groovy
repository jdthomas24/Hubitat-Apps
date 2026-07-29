/**
 * Reolink Doorbell (Component Driver)
 * Version: 1.1.1 -- kept in sync with the parent app's version.
 * Same delegation pattern as Reolink Camera, plus a "visitor" (button press)
 * event so Rule Machine can trigger straight off "pushed 1" for a doorbell ring,
 * separate from AI person/motion detection.
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
    }
    preferences {
        input name: "pollIntervalSec", type: "number", title: "Poll interval (sec)", defaultValue: 5
    }
}

def installed() {
    sendEvent(name: "numberOfButtons", value: 1)
}

def refresh() {
    parent?.componentRefresh(this)
}

def takeSnapshot() {
    parent?.componentTakeSnapshot(this)
}

def setPollInterval(seconds) {
    parent?.componentSetPollInterval(this, seconds as Integer)
}

/** Called by the app after it polls GetAiState/GetMdState/visitor state for this channel. */
def parseReolinkState(aiState, mdState) {
    sendEvent(name: "sleepStatus", value: "awake")

    // TODO confirm the visitor/doorbell-press field name in your firmware's GetAiState/GetMdState payload
    def visitorPressed = aiState?.visitor?.alarm_state == 1
    if (visitorPressed) {
        sendEvent(name: "pushed", value: "1", isStateChange: true)
    }

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

/** Called by the app when a poll gets no response -- see camera driver for the reasoning. */
def markAsleep() {
    sendEvent(name: "sleepStatus", value: "asleep")
}

def receiveSnapshotUrl(url) {
    sendEvent(name: "snapshotUrl", value: url)
}

