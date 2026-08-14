/**
 * Reolink Standalone Devices (Internal Group Driver)
 * Version: 1.3.8
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
 * Deliberately does nothing else -- no attributes, no commands, no state.
 * Hub/NVR sources are entirely unaffected by this driver; their bridge
 * still parents directly off the app, unchanged.
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
