/**
 * Reolink Standalone Devices (Internal Group Driver)
 * Version: 1.4.1
 *
 * NOT user-facing. Created and managed automatically by the Reolink
 * Integration parent app -- exactly ONE instance total, shared across every
 * standalone (non-Hub/NVR) source. Users never add this manually.
 *
 * Purpose: purely a nesting anchor in the Devices list. Each standalone
 * camera/doorbell still gets its own independent "Reolink Device Bridge"
 * (its own persistent event connection -- Hubitat's rawSocket interface is
 * one-connection-per-driver-instance, so that part genuinely can't be
 * shared across cameras). What CAN be shared is where those bridges nest:
 * instead of each standalone bridge sitting directly under the app
 * (unnested, one per source, unlike an NVR/Hub's channels which all nest
 * under one shared bridge), every standalone source's bridge becomes a
 * child of THIS device instead. Net effect: one collapsible "Reolink
 * Standalone Devices" entry in the Devices list holding every standalone
 * camera/doorbell's bridge (and, under each of those, its camera/doorbell),
 * instead of N separate unnested bridges.
 *
 * v1.4.1 FIX: a standalone source's "Reolink Device Bridge" has THIS
 * device as its real Hubitat parent (not the app directly -- see
 * createBridgeDevice() below), so its parent?.logNormal(...)/
 * parent?.logFull(...) calls (used throughout ReolinkDeviceBridge.groovy
 * for its own connection-status/routine logging, see that file's v1.3.8
 * notes) resolve to THIS device, not the app. This driver had no such
 * methods at all, so every one of those calls threw a
 * MissingMethodException the moment a standalone source's bridge tried to
 * log anything -- including the very first line of startEventSubscription()
 * itself, which meant the actual socket connection attempt never even
 * started for a standalone source. A Hub/NVR source's bridge was never
 * affected, since its parent is the app directly, which always had these
 * methods. Found via real-hardware testing of a standalone battery camera
 * (Argus 4 Pro) whose HTTP CGI API is fully absent (Argus line has no
 * local HTTP/ONVIF stack at all -- see the app's Tips page), where testing
 * whether its separate Baichuan event-subscription port might still
 * respond surfaced this as a blocking bug before the socket attempt could
 * even happen. Both methods now simply forward up to this device's OWN
 * parent (the app), which already has the real logNormal()/logFull()
 * tiered-logging implementation -- identical pattern to how this driver's
 * createBridgeDevice()/removeBridgeDevice() already forward bridge
 * creation/removal to the app.
 */
metadata {
    definition(name: "Reolink Standalone Devices", namespace: "jdthomas24", author: "Jason", component: true) {
        capability "Actuator"
    }
}
def installed() {}
def updated() {}
/**
 * Creates a standalone source's "Reolink Device Bridge" as THIS device's
 * own child, called by the app's ensureSourceBridge(). Mirrors the exact
 * pattern ReolinkDeviceBridge.groovy itself uses for createChannelDevice()
 * -- addChildDevice() must run in the owning device's own execution
 * context, so this method exists here rather than the app calling
 * addChildDevice() on a held reference from outside.
 */
def createBridgeDevice(String dni, String label, Integer sourceId) {
    def bridge = addChildDevice("jdthomas24", "Reolink Device Bridge", dni, [
        name: label, label: label, isComponent: true
    ])
    bridge.updateDataValue("sourceId", "${sourceId}")
    return bridge
}
def removeBridgeDevice(String dni) {
    deleteChildDevice(dni)
}

/**
 * v1.4.1 NEW -- logging passthrough for standalone bridges. See this
 * file's v1.4.1 FIX note above for why this is needed. This device's OWN
 * parent is always the app (never another group device), so this simply
 * forwards up one level -- same forwarding pattern already used elsewhere
 * in this integration (e.g. ReolinkDeviceBridge.groovy's componentX()
 * passthrough methods).
 */
void logNormal(msg) {
    parent?.logNormal(msg)
}

/** See logNormal() above -- same reasoning, Full-tier tier. */
void logFull(msg) {
    parent?.logFull(msg)
}
