/*
SmartThings Motion Sensor Enhanced

Version: 1.8.0
Author: jdthomas24
Namespace: jdthomas24

Supported Models:
- STS-IRM-250 (motionv4)
- STS-IRM-251 (motionv5)
- GP-AEOMSSUS (Aeotec Zigbee motion)
- GP-U999SJVLBAA (Samsung SmartThings motion)

Enhancements:
- Motion auto reset with race condition fix
- Optional temperature reporting (enableTemp)
- Battery voltage curve with 5% increments & smoothing
- Battery reporting interval in minutes (converted to seconds for Zigbee)
- LQI/RSSI signal monitoring with route health rating
- Health Check ping() implementation
- Debug logging auto-disables after 30 minutes
- Temperature logging suppressed when enableTemp is off

Changes in 1.7.8:
- Updated battery voltage curve for CR2 lithium chemistry
  Previous curve was tuned for CR2450/CR2477 coin cells — incorrect for these devices
  New curve reflects CR2 discharge profile: 3.0V fresh, ~2.8V plateau, 2.0V cutoff
  Steeper end-of-life drop below 2.5V matches CR2 real-world behavior
- Default battery reporting interval changed from 60 to 240 minutes
  Reduces overly frequent reporting that caused Battery Monitor drain outlier rejection
  Existing installs are unaffected — only applies on fresh install

Changes in 1.7.7:
- configure() now only fires when enableTemp or batteryReportMinutes change —
  not on every updated() save, which could interrupt the device reporting cycle
- Toggling enableTemp off sends a null temperature event with isStateChange: true
  so dashboards and apps see the attribute clear immediately
- Toggling enableTemp on calls configure() + refresh() so temperature populates
  immediately without waiting for the next natural device report
- batteryVoltage event now only fires on change, consistent with battery %
- Removed presenceTimeoutCheck() stub — safe to drop now that all devices
  running v1.7.4 will have saved preferences and cleared the stale timer

Changes in 1.7.6:
- Removed presence detection — was causing interference with device reporting
  due to aggressive runIn() scheduling on every parse event
- Removed lastCheckin attribute — redundant with Hubitat built-in Last Activity
- Removed zigbeeHealth and missedCheckins — were presence-driven, no data source
- Tightened battery voltage curve to 5% increments for cleaner reporting
- Temperature events fully suppressed (no log, no event) when enableTemp is off
- Fixed driverVersion() returning "1.7.5" (was mismatched with header)
- Added isStateChange: true to temperature sendEvent so repeated identical
  readings are still logged — prevents gaps in home page temperature graphs
*/

import hubitat.zigbee.clusters.iaszone.ZoneStatus
import hubitat.zigbee.zcl.DataType

def driverVersion() { return "1.8.0" }

metadata {
    definition(
        name: "SmartThings Motion Sensor Enhanced",
        namespace: "jdthomas24",
        author: "jdthomas24"
    ) {
        capability "Battery"
        capability "Configuration"
        capability "MotionSensor"
        capability "Initialize"
        capability "Refresh"
        capability "Sensor"
        capability "TemperatureMeasurement"
        capability "Health Check"

        attribute "batteryVoltage", "number"
        attribute "lqi",            "number"
        attribute "rssi",           "number"
        attribute "routeHealth",    "string"

        fingerprint inClusters:"0000,0001,0003,000F,0020,0402,0500", model:"motionv4",        manufacturer:"SmartThings"
        fingerprint inClusters:"0000,0001,0003,000F,0020,0402,0500", model:"motionv5",        manufacturer:"SmartThings"
        fingerprint inClusters:"0000,0001,0003,000F,0020,0402,0500", model:"GP-AEOMSSUS",     manufacturer:"Aeotec"
        fingerprint inClusters:"0000,0001,0003,000F,0020,0402,0500", model:"GP-U999SJVLBAA", manufacturer:"Samsung"
    }

    preferences {
        input name: "motionReset",          type: "number",  title: "Motion Reset Time (seconds)",         defaultValue: 30
        input name: "enableTemp",           type: "bool",    title: "Enable Temperature Reporting",        defaultValue: true
        input name: "tempAdj",              type: "decimal", title: "Temperature Offset",                  defaultValue: 0
        input name: "batteryReportMinutes", type: "enum",
              title: "Battery Reporting Interval (minutes)",
              description: "How often the device reports battery. Converted to seconds for Zigbee reporting.",
              options: ["30m","60m","120m","240m","360m"],
              defaultValue: "240m"
        input name: "infoLogging",  type: "bool", title: "Enable Info Logging",                              defaultValue: true
        input name: "debugLogging", type: "bool", title: "Enable Debug Logging (auto-disables after 30 min)", defaultValue: false
    }
}

// ============================================================
// ===================== LIFECYCLE ===========================
// ============================================================
def installed() {
    log.info "${device.displayName}: Installed driver v${driverVersion()}"
    scheduleDebugAutoOff()
    initialize()
}

def initialize() {
    runIn(2, configure)
    runIn(7, refresh)
}

def updated() {
    log.info "${device.displayName}: Updated driver v${driverVersion()}"

    unschedule()

    // Clear stale attributes from pre-1.7.6 driver versions
    ["presence", "lastCheckin", "checkinInterval", "presenceTimeout",
     "missedCheckins", "zigbeeHealth"].each { attr ->
        device.deleteCurrentState(attr)
    }

    // Clear stale state variables from pre-1.7.6 presence logic
    state.remove("lastCheckin")
    state.remove("checkinHistory")
    state.remove("avgCheckin")
    state.remove("missed")

    scheduleDebugAutoOff()

    // v1.7.7: configure() only fires when settings that affect Zigbee reporting
    // actually change — avoids interrupting the device reporting cycle on every save
    def prevTemp    = state.prevEnableTemp
    def prevBattInt = state.prevBatteryReportMinutes
    def tempChanged = (prevTemp    != null && prevTemp    != enableTemp)
    def battChanged = (prevBattInt != null && prevBattInt != batteryReportMinutes)

    // Always configure on first save (no previous state recorded)
    def firstSave = (prevTemp == null)

    if (firstSave || tempChanged || battChanged) {
        if (debugLogging) log.debug "${device.displayName}: configure() triggered — firstSave:${firstSave} tempChanged:${tempChanged} battChanged:${battChanged}"
        configure()
    } else {
        if (debugLogging) log.debug "${device.displayName}: Skipping configure() — no relevant settings changed"
    }

    // v1.7.7: handle enableTemp toggle
    if (tempChanged) {
        if (enableTemp) {
            // Toggled on — refresh immediately so attribute populates without
            // waiting for the next natural device report (up to 30 min)
            if (infoLogging) log.info "${device.displayName}: Temperature reporting enabled — refreshing"
            runIn(2, refresh)
        } else {
            // Toggled off — clear attribute immediately so dashboards and apps
            // see it go blank rather than showing a stale reading indefinitely
            if (infoLogging) log.info "${device.displayName}: Temperature reporting disabled — clearing attribute"
            sendEvent(name: "temperature", value: null, isStateChange: true)
        }
    }

    // Record current settings for comparison on next save
    state.prevEnableTemp            = enableTemp
    state.prevBatteryReportMinutes  = batteryReportMinutes
}

def configure() {
    def battInterval = ((batteryReportMinutes ?: "240m") - "m").toInteger() * 60

    def cmds = []
    cmds += zigbee.batteryConfig()
    cmds += zigbee.configureReporting(0x0500, 0x0002, DataType.BITMAP16, 30, 3600, null)
    cmds += zigbee.configureReporting(0x0001, 0x0020, DataType.UINT8, 30, battInterval, 1)

    if (enableTemp) {
        cmds += zigbee.temperatureConfig(30, 1800)
    }

    cmds += zigbee.enrollResponse()
    sendZigbeeCommands(cmds)
}

def refresh() {
    def cmds = []
    cmds += zigbee.readAttribute(0x0001, 0x0020)  // battery voltage
    cmds += zigbee.readAttribute(0x0500, 0x0002)  // zone status
    if (enableTemp) cmds += zigbee.readAttribute(0x0402, 0x0000)  // temperature
    sendZigbeeCommands(cmds)
}

// ============================================================
// ===================== DEBUG AUTO-OFF ======================
// ============================================================
private void scheduleDebugAutoOff() {
    unschedule("disableDebugLogging")
    if (debugLogging) {
        log.warn "${device.displayName}: Debug logging enabled — will auto-disable in 30 minutes"
        runIn(1800, "disableDebugLogging")
    }
}

def disableDebugLogging() {
    log.info "${device.displayName}: Auto-disabling debug logging after 30 minutes"
    device.updateSetting("debugLogging", [value: "false", type: "bool"])
}

// ============================================================
// ===================== HEALTH CHECK ========================
// ============================================================
def ping() {
    if (debugLogging) log.debug "${device.displayName}: ping() — refreshing device state"
    refresh()
}

// ============================================================
// ===================== PARSE ===============================
// ============================================================
def parse(String description) {
    if (!description) return

    Map descMap = zigbee.parseDescriptionAsMap(description)
    if (descMap?.lqi)  { sendEvent(name: "lqi",  value: descMap.lqi);  updateRouteHealth(descMap.lqi.toInteger()) }
    if (descMap?.rssi) { sendEvent(name: "rssi", value: descMap.rssi) }

    // Zone status — motion
    if (description.startsWith("zone status")) {
        ZoneStatus status = zigbee.parseZoneStatus(description)
        processMotion(status)
        return
    }

    // Battery voltage — cluster 0x0001 attribute 0x0020
    if (descMap?.cluster == "0001" && descMap?.attrId == "0020") {
        def rawVolts = Integer.parseInt(descMap.value, 16) / 10.0
        def volts    = smoothBattery(rawVolts)
        // v1.7.7: only fire event if voltage changed — consistent with battery %
        if (device.currentValue("batteryVoltage") != volts) {
            sendEvent(name: "batteryVoltage", value: volts, unit: "V")
        }
        def pct = calculateBattery(volts)
        if (device.currentValue("battery") != pct) {
            if (infoLogging) log.info "${device.displayName}: Battery ${pct}% (${volts}V)"
            sendEvent(name: "battery", value: pct, unit: "%")
        }
        return
    }

    // Temperature and other events
    def evt = zigbee.getEvent(description)
    if (!evt) return

    if (evt.name == "temperature") {
        // Fully suppressed when enableTemp is off — no log, no event
        if (!enableTemp) return
        Double offset = tempAdj ?: 0
        def temp = (evt.value + offset).round(2)
        if (infoLogging) log.info "${device.displayName}: Temperature ${temp}°${evt.unit}"
        // isStateChange: true ensures repeated identical readings are still logged
        // as events — prevents gaps in home page temperature graphs
        sendEvent(name: "temperature", value: temp, unit: evt.unit, isStateChange: true)
        return
    }
}

// ============================================================
// ===================== MOTION ==============================
// ============================================================
def processMotion(ZoneStatus status) {
    if (status.isAlarm1Set()) {
        // Cancel any pending reset before starting a new one — prevents
        // inactive firing mid-motion if device triggers rapidly
        unschedule("motionInactive")
        sendEvent(name: "motion", value: "active", isStateChange: true)
        if (infoLogging) log.info "${device.displayName}: Motion active"

        // Guard against 0 or negative motionReset values
        def resetTime = (motionReset ?: 30).toInteger()
        if (resetTime > 0) runIn(resetTime, motionInactive)
    } else {
        // Hardware inactive message ignored — motionReset timer controls
        // when motion goes inactive so the user-configured hold time is respected
        if (debugLogging) log.debug "${device.displayName}: Hardware inactive received — waiting for motionReset timer (${motionReset ?: 30}s)"
    }
}

def motionInactive() {
    sendEvent(name: "motion", value: "inactive", isStateChange: true)
    if (infoLogging) log.info "${device.displayName}: Motion inactive"
}

// ============================================================
// ===================== ROUTE HEALTH ========================
// ============================================================
def updateRouteHealth(Integer lqi) {
    def health = lqi >= 150 ? "Excellent" :
                 lqi >= 100 ? "Good" :
                 lqi >= 60  ? "Weak" : "Poor"
    sendEvent(name: "routeHealth", value: health)
    if (debugLogging) log.debug "${device.displayName}: LQI=${lqi} routeHealth=${health}"
}

// ============================================================
// ===================== BATTERY =============================
// ============================================================

/**
 * Voltage-to-percentage curve for CR2 lithium batteries.
 * Based on ~750-800mAh capacity, 3.0V fresh, ~2.8V nominal plateau, 2.0V cutoff.
 * Low-drain Zigbee sensor load profile.
 * CR2 holds voltage well through the plateau then drops steeply below 2.5V.
 * 5% increments with tighter steps at end-of-life cliff.
 */
def calculateBattery(Double voltage) {
    if (voltage >= 3.00) return 100
    if (voltage >= 2.95) return 99  // brief initial drop from fresh cell
    if (voltage >= 2.90) return 95
    if (voltage >= 2.85) return 90
    if (voltage >= 2.80) return 85  // enters flat plateau ~2.8V
    if (voltage >= 2.75) return 75
    if (voltage >= 2.70) return 65
    if (voltage >= 2.65) return 55
    if (voltage >= 2.60) return 45
    if (voltage >= 2.55) return 35
    if (voltage >= 2.50) return 28
    if (voltage >= 2.45) return 22
    if (voltage >= 2.40) return 17
    if (voltage >= 2.35) return 13
    if (voltage >= 2.30) return 9
    if (voltage >= 2.25) return 6
    if (voltage >= 2.20) return 4
    if (voltage >= 2.10) return 2
    if (voltage >= 2.00) return 1
    return 0
}

def smoothBattery(Double voltage) {
    if (!state.lastVolt) { state.lastVolt = voltage; return voltage }
    def smoothed = (state.lastVolt + voltage) / 2
    state.lastVolt = smoothed
    return smoothed.round(2)
}

// ============================================================
// ===================== ZIGBEE SEND =========================
// ============================================================
void sendZigbeeCommands(List cmds) {
    if (!cmds) return
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}
