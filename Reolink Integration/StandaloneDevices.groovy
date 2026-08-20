/**
 * Reolink Standalone Devices (Internal Group Driver)
 * Version: 1.4.2
 *
 * NOT user-facing. Created and managed automatically by the Reolink
 * Integration parent app -- exactly ONE instance total, shared across every
 * standalone (non-Hub/NVR) source.
 *
 * Purpose: purely a nesting anchor in the Devices list. Each standalone
 * camera/doorbell still gets its own independent "Reolink Device Bridge"
 * (its own persistent event connection -- Hubitat's rawSocket is one-
 * connection-per-driver-instance, so that part can't be shared). What CAN
 * be shared is where those bridges nest: instead of each standalone bridge
 * sitting directly under the app (unnested, unlike an NVR/Hub's channels
 * which all nest under one shared bridge), every standalone source's bridge
 * becomes a child of THIS device instead -- one collapsible entry holding
 * every standalone camera/doorbell's bridge, instead of N separate unnested
 * bridges.
 *
 * v1.4.2 -- No functional change (version kept in sync with the app).
 * v1.4.1 -- FIX: a standalone source's bridge has THIS device as its real
 * Hubitat parent (not the app directly), so its parent?.logNormal()/
 * logFull() calls (used throughout ReolinkDeviceBridge.groovy, including
 * the first line of startEventSubscription()) threw a MissingMethodException
 * every time -- this driver had no such methods at all, meaning the actual
 * socket connection never even started for ANY standalone source. Hub/NVR
 * bridges (parented directly off the app) were never affected. Found via
 * real-hardware testing of a standalone battery camera (Argus 4 Pro, whose
 * HTTP CGI API is fully absent -- see the app's Tips page) while testing
 * whether its Baichuan event port might still respond, which surfaced this
 * as a blocking bug before the socket attempt could even happen. Fixed by
 * adding logNormal()/logFull() passthroughs that forward to this device's
 * own parent (the app) -- same pattern already used for
 * createBridgeDevice()/removeBridgeDevice() below.
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
 * v1.4.1: logging passthrough for standalone bridges -- see the header note
 * above. This device's own parent is always the app (never another group
 * device), so it simply forwards up one level, same pattern used elsewhere
 * in this integration (e.g. ReolinkDeviceBridge.groovy's componentX()
 * passthroughs).
 */
void logNormal(msg) {
    parent?.logNormal(msg)
}

/** See logNormal() above -- same reasoning, Full-tier. */
void logFull(msg) {
    parent?.logFull(msg)
}
