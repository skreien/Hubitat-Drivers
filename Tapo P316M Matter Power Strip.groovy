metadata {
    definition(name: "Tapo P316M Matter Power Strip", namespace: "codex", author: "OpenAI") {
        capability "Switch"
        capability "Power Meter"
        capability "Energy Meter"
        capability "Voltage Measurement"
        capability "CurrentMeter"
        capability "Refresh"
        capability "Configuration"
        capability "Initialize"
        attribute "driverVersion", "string"
        command "discover"
        command "allOn"
        command "allOff"
        command "onOutlet", [[name: "Outlet number", type: "NUMBER", description: "1 through 6"]]
        command "offOutlet", [[name: "Outlet number", type: "NUMBER", description: "1 through 6"]]
        command "toggleOutlet", [[name: "Outlet number", type: "NUMBER", description: "1 through 6"]]
        command "setOutletName", [[name: "Outlet number", type: "NUMBER"], [name: "Name", type: "STRING"]]
    }
    preferences {
        input name: "endpointList", type: "string", title: "Outlet Matter endpoints", description: "Comma-separated endpoint IDs. Discover fills this from the device when available.", defaultValue: "1,2,3,4,5,6"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

private Integer onOffCluster() { 0x0006 }
private Integer powerCluster() { 0x0090 }
private Integer energyCluster() { 0x0091 }
private Integer maxOutlets() { 6 }

void installed() {
    discover()
}

void updated() {
    if (logEnable) runIn(1800, "logsOff")
    discover()
}

void initialize() {
    discover()
}

void configure() {
    discover()
}

void logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

void discover() {
    sendEvent(name: "driverVersion", value: "1.0.2", displayed: false, isStateChange: true)
    List<Integer> eps = detectOutletEndpoints()
    if (!eps) eps = parseEndpointList(settings.endpointList)
    if (!eps) eps = (1..maxOutlets()).toList()
    eps = eps.unique().findAll { it > 0 }.take(maxOutlets()).sort()
    if (!eps) {
        log.warn "${device.displayName}: No outlet endpoints found. Set the endpoint list preference and run Discover."
        return
    }
    log.info "${device.displayName}: Discovery selected outlet endpoints ${eps} (configured list: ${settings.endpointList ?: 'empty'})."
    device.updateSetting("endpointList", [value: eps.join(","), type: "string"])
    syncChildren(eps)
    log.info "${device.displayName}: Discovery finished; Hubitat reports ${getChildDevices().size()} child device(s)."
    subscribeToReports()
    refresh()
}

private List<Integer> detectOutletEndpoints() {
    def fps = matter.getMatterFingerprints()
    List<Integer> found = []
    fps?.each { fp ->
        String endpoint = "${fp.endpointId ?: ''}"
        Integer ep = parseEndpoint(endpoint)
        def advertised = fp.inClusters != null ? [fp.inClusters] : []
        boolean hasOnOff = advertised.any { clusterList ->
            def tokens = clusterList instanceof Collection ? clusterList : clusterList.toString().split(/[,;\s\[\]]+/).findAll { it }
            tokens.any { token ->
                if (token instanceof Number) return token.intValue() == onOffCluster()
                String t = token.toString().trim()
                Integer id = t.startsWith("0x") || t.length() == 4 ? parseMatterId(t) : parseEndpoint(t)
                id == onOffCluster()
            }
        }
        if (ep != null && ep > 0 && hasOnOff) found << ep
    }
    if (logEnable) log.debug "Fingerprint endpoint scan found ${found}"
    return found.unique().sort()
}

private List<Integer> detectMeterEndpoints() {
    def fps = matter.getMatterFingerprints()
    List<Integer> found = []
    fps?.each { fp ->
        Integer ep = parseEndpoint(fp.endpointId)
        def advertised = fp.inClusters != null ? [fp.inClusters] : []
        boolean hasElectrical = advertised.any { clusterList ->
            def tokens = clusterList instanceof Collection ? clusterList : clusterList.toString().split(/[,;\s\[\]]+/).findAll { it }
            tokens.any { token ->
                Integer id = parseClusterId(token)
                id == powerCluster() || id == energyCluster()
            }
        }
        if (ep != null && ep > 0 && hasElectrical) found << ep
    }
    return found.unique().sort()
}

private Integer parseClusterId(def value) {
    if (value instanceof Number) return value.intValue()
    String s = value?.toString()?.trim()
    if (!s) return null
    if (s ==~ /0x[0-9a-fA-F]+/) return Integer.parseInt(s.substring(2), 16)
    if (s ==~ /[0-9]+/) return Integer.parseInt(s, 10)
    return parseMatterId(s)
}

private Integer meterEndpointFor(Integer outletEp, List<Integer> outletEps) {
    List<Integer> meterEps = detectMeterEndpoints()
    if (meterEps.size() == outletEps.size()) {
        Integer index = outletEps.indexOf(outletEp)
        if (index >= 0) return meterEps[index]
    }
    // P316M presents outlet switches on endpoints 1-6 and their meters on 7-12.
    return outletEp + 6
}

private Integer parseEndpoint(def value) {
    if (value == null) return null
    String s = value.toString().trim()
    if (s ==~ /0x[0-9a-fA-F]+/) return Integer.parseInt(s.substring(2), 16)
    if (s ==~ /(?i)[0-9a-f]*[a-f][0-9a-f]*/) return Integer.parseInt(s, 16)
    if (s ==~ /[0-9]+/) return Integer.parseInt(s, 10)
    return null
}

private Integer parseMatterId(def value) {
    if (value == null) return null
    if (value instanceof Number) return value.intValue()
    String s = value.toString().trim()
    String hex = s.startsWith("0x") ? s.substring(2) : s
    if (!(hex ==~ /[0-9a-fA-F]+/)) return null
    return Integer.parseInt(hex, 16)
}

private List<Integer> parseEndpointList(def value) {
    if (!value) return []
    return value.toString().split(/[,;\s]+/).collect { parseEndpoint(it) }.findAll { it != null && it > 0 }
}

private void syncChildren(List<Integer> endpoints) {
    endpoints.each { Integer ep ->
        String dni = "${device.deviceNetworkId}-outlet-${ep}"
        def child = getChildDevice(dni)
        if (!child) {
            log.info "${device.displayName}: Creating outlet ${ep} with child driver codex:Tapo P316M Outlet."
            child = addChildDevice("codex", "Tapo P316M Outlet", dni, [name: "${device.displayName} Outlet ${ep}", label: "${device.displayName} Outlet ${ep}", isComponent: true])
        }
        if (!child) {
            log.error "${device.displayName}: Child creation returned no device for outlet endpoint ${ep}. Confirm codex:Tapo P316M Outlet is saved in Drivers Code."
            return
        }
        child.updateDataValue("endpoint", ep.toString())
        child.updateDataValue("outletNumber", (endpoints.indexOf(ep) + 1).toString())
        child.updateDataValue("meterEndpoint", meterEndpointFor(ep, endpoints).toString())
        child.sendEvent(name: "driverVersion", value: "1.0.0", displayed: false, isStateChange: true)
    }
    def currentChildren = getChildDevices() ?: []
    log.info "${device.displayName}: Child sync sees ${currentChildren.size()} child device(s): ${currentChildren.collect { it.deviceNetworkId }}."
}

void on() { allOn() }
void off() { allOff() }

void allOn() { outletEndpoints().each { invokeOutlet(it, 0x01) } }
void allOff() { outletEndpoints().each { invokeOutlet(it, 0x00) } }
void onOutlet(def number) { withOutlet(number) { ep -> invokeOutlet(ep, 0x01) } }
void offOutlet(def number) { withOutlet(number) { ep -> invokeOutlet(ep, 0x00) } }
void toggleOutlet(def number) { withOutlet(number) { ep -> invokeOutlet(ep, 0x02) } }

void setOutletName(def number, String name) {
    Integer index = parseEndpoint(number)
    def child = childByOutletNumber(index)
    if (!child || !name?.trim()) return
    child.setLabel(name.trim())
}

private void withOutlet(def number, Closure action) {
    Integer n = parseEndpoint(number)
    List<Integer> eps = outletEndpoints()
    if (n == null || n < 1 || n > eps.size()) {
        log.warn "${device.displayName}: Outlet number must be between 1 and ${eps.size()}"
        return
    }
    action.call(eps[n - 1])
}

private def childByOutletNumber(Integer number) {
    getChildDevices().find { it.getDataValue("outletNumber") == number?.toString() }
}

private def childByMeterEndpoint(Integer endpoint) {
    getChildDevices().find { it.getDataValue("meterEndpoint") == endpoint?.toString() }
}

private List<Integer> outletEndpoints() {
    List<Integer> eps = parseEndpointList(settings.endpointList)
    return eps ?: (1..maxOutlets()).toList()
}

private void invokeOutlet(Integer endpoint, Integer commandId) {
    String cmd = matter.invoke(endpoint, onOffCluster(), commandId)
    sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.MATTER))
    if (logEnable) log.debug "Outlet endpoint ${endpoint}: Matter OnOff command ${commandId}"
}

void refresh() {
    List<Map<String, String>> paths = []
    List<Integer> outlets = outletEndpoints()
    outlets.each { Integer ep ->
        Integer meterEp = meterEndpointFor(ep, outlets)
        paths << matter.attributePath(ep, onOffCluster(), 0x0000)
        paths << matter.attributePath(meterEp, powerCluster(), 0x0008)
        paths << matter.attributePath(meterEp, powerCluster(), 0x000B)
        paths << matter.attributePath(meterEp, powerCluster(), 0x000C)
        paths << matter.attributePath(meterEp, energyCluster(), 0x0001)
    }
    if (paths) sendHubCommand(new hubitat.device.HubAction(matter.readAttributes(paths), hubitat.device.Protocol.MATTER))
}

private void subscribeToReports() {
    String unsubscribeCommand = matter.unsubscribe()
    if (unsubscribeCommand) {
        sendHubCommand(new hubitat.device.HubAction(unsubscribeCommand, hubitat.device.Protocol.MATTER))
    }
    unschedule("establishSubscriptions")
    runIn(2, "establishSubscriptions", [overwrite: true])
}

void establishSubscriptions() {
    List<Map<String, String>> switchPaths = []
    List<Map<String, String>> meterPaths = []
    List<Integer> outlets = outletEndpoints()
    outlets.each { Integer ep ->
        Integer meterEp = meterEndpointFor(ep, outlets)
        switchPaths << matter.attributePath(ep, onOffCluster(), 0x0000)
        meterPaths << matter.attributePath(meterEp, powerCluster(), 0x0008)
        meterPaths << matter.attributePath(meterEp, powerCluster(), 0x000B)
        meterPaths << matter.attributePath(meterEp, powerCluster(), 0x000C)
        meterPaths << matter.attributePath(meterEp, energyCluster(), 0x0001)
    }
    if (switchPaths) {
        String switchCommand = matter.subscribe(1, 3600, switchPaths)
        sendHubCommand(new hubitat.device.HubAction(switchCommand, hubitat.device.Protocol.MATTER))
    }
    if (meterPaths) {
        String meterCommand = matter.subscribe(30, 300, meterPaths)
        sendHubCommand(new hubitat.device.HubAction(meterCommand, hubitat.device.Protocol.MATTER))
    }
    if (logEnable) {
        log.debug "Subscribed to ${switchPaths.size()} switch attributes and ${meterPaths.size()} metering attributes."
    }
}

void parse(String description) {
    Map msg = matter.parseDescriptionAsMap(description)
    if (!msg) return
    if (logEnable) log.debug "Matter report: ${msg}"
    Integer ep = parseEndpoint(msg.endpoint ?: msg.endpointId)
    Integer cluster = parseMatterId(msg.cluster)
    Integer attr = parseMatterId(msg.attrId ?: msg.attribute)
    if (ep == null || cluster == null || attr == null) return
    boolean isOnOff = cluster == onOffCluster()
    def child = isOnOff ? getChildDevice("${device.deviceNetworkId}-outlet-${ep}") : childByMeterEndpoint(ep)
    if (!child) return
    def value = msg.value
    if (cluster == onOffCluster() && attr == 0x0000) {
        Boolean onState = truthValue(value)
        if (onState != null) child.parse([[name: "switch", value: onState ? "on" : "off"]])
        updateAggregateSwitch()
    } else if (cluster == powerCluster() && attr == 0x0008) {
        BigDecimal watts = numericMatterValue(value)
        if (watts != null) child.parse([[name: "power", value: (watts / 1000G).setScale(3, BigDecimal.ROUND_HALF_UP), unit: "W"]])
        updateAggregatePower()
    } else if (cluster == powerCluster() && attr == 0x000B) {
        BigDecimal millivolts = numericMatterValue(value)
        if (millivolts != null) child.parse([[name: "voltage", value: (millivolts / 1000G).setScale(3, BigDecimal.ROUND_HALF_UP), unit: "V"]])
        updateAggregateVoltage()
    } else if (cluster == powerCluster() && attr == 0x000C) {
        BigDecimal milliamps = numericMatterValue(value)
        if (milliamps != null) child.parse([[name: "amperage", value: (milliamps / 1000G).setScale(3, BigDecimal.ROUND_HALF_UP), unit: "A"]])
        updateAggregateCurrent()
    } else if (cluster == energyCluster() && attr == 0x0001) {
        BigDecimal kwh = energyKwh(value)
        if (kwh != null) child.parse([[name: "energy", value: kwh.setScale(6, BigDecimal.ROUND_HALF_UP), unit: "kWh"]])
        updateAggregateEnergy()
    }
}

private Boolean truthValue(def value) {
    if (value instanceof Boolean) return value
    if (value instanceof Number) return value.intValue() != 0
    if (value == null) return null
    String v = value.toString().trim().toLowerCase()
    if (v in ["true", "on", "1", "01"]) return true
    if (v in ["false", "off", "0", "00"]) return false
    if (!(v ==~ /[0-9a-f]+/)) return null
    return Integer.parseInt(v, 16) != 0
}

private BigDecimal numericMatterValue(def value) {
    if (value instanceof Number) return new BigDecimal(value.toString())
    if (value instanceof Map) {
        def candidate = value.energy ?: value.value ?: value.rawValue ?: value[0] ?: value["0"]
        if (candidate != null) return numericMatterValue(candidate)
    }
    if (value instanceof List && value.size() == 1) return numericMatterValue(value[0])
    if (value == null) return null
    String s = value.toString().trim()
    String hex = s.startsWith("0x") || s.startsWith("0X") ? s.substring(2) : s
    hex = hex.replaceAll(/[^0-9a-fA-F]/, "")
    if (!hex || hex.length() % 2 != 0) return null
    // Matter reports are hexadecimal strings even when every character is a decimal digit.
    return new BigInteger(hex, 16) as BigDecimal
}

private BigDecimal energyKwh(def value) {
    if (value instanceof Map) {
        def candidate = value.energy ?: value.CumulativeEnergyImported ?: value.value ?: value[0] ?: value["0"]
        if (candidate != null) return energyKwh(candidate)
    }
    if (value instanceof List && value.size() == 1) return energyKwh(value[0])
    if (value instanceof Number) return new BigDecimal(value.toString()) / 1000000G
    if (value == null) return null
    String s = value.toString().trim()
    if (s ==~ /-?[0-9]+(\.[0-9]+)?([eE][+-]?[0-9]+)?/) {
        return new BigDecimal(s) / 1000000G
    }
    String hex = s.replaceAll(/[^0-9a-fA-F]/, "")
    if (hex.length() >= 8 && hex.startsWith("15")) {
        Integer control = Integer.parseInt(hex.substring(2, 4), 16)
        Integer tagControl = (control >> 5) & 0x07
        Integer type = control & 0x1F
        Integer cursor = 4
        if (tagControl == 1 && hex.substring(cursor, cursor + 2) == "00" && type >= 0 && type <= 7) {
            cursor += 2
            Integer byteCount = type in [0, 4] ? 1 : (type in [1, 5] ? 2 : (type in [2, 6] ? 4 : 8))
            Integer end = cursor + (byteCount * 2)
            if (hex.length() >= end) {
                String bytes = hex.substring(cursor, end)
                String bigEndian = (0..<byteCount).collect { i -> bytes.substring(i * 2, i * 2 + 2) }.reverse().join("")
                BigInteger raw = new BigInteger(bigEndian, 16)
                if (type <= 3 && raw.testBit((byteCount * 8) - 1)) raw = raw - (BigInteger.ONE << (byteCount * 8))
                return (raw as BigDecimal) / 1000000G
            }
        }
    }
    // Some Hubitat versions expose an already-decoded scalar instead of the TLV struct.
    if (hex ==~ /[0-9a-fA-F]{2,16}/) {
        return (new BigInteger(hex, 16) as BigDecimal) / 1000000G
    }
    if (logEnable) log.warn "Could not decode Matter cumulative energy value: ${value}"
    return null
}

private void updateAggregateSwitch() {
    List vals = getChildDevices().collect { it.currentValue("switch") }.findAll { it in ["on", "off"] }
    if (vals) sendEvent(name: "switch", value: vals.contains("on") ? "on" : "off")
}
private void updateAggregatePower() {
    List vals = getChildDevices().collect { it.currentValue("power") }.findAll { it != null }
    if (vals) sendEvent(name: "power", value: vals.collect { it as BigDecimal }.sum().setScale(3, BigDecimal.ROUND_HALF_UP), unit: "W")
}
private void updateAggregateEnergy() {
    List vals = getChildDevices().collect { it.currentValue("energy") }.findAll { it != null }
    if (vals) sendEvent(name: "energy", value: vals.collect { it as BigDecimal }.sum().setScale(6, BigDecimal.ROUND_HALF_UP), unit: "kWh")
}
private void updateAggregateVoltage() {
    List vals = getChildDevices().collect { it.currentValue("voltage") }.findAll { it != null }
    if (vals) sendEvent(name: "voltage", value: (vals.collect { it as BigDecimal }.sum() / vals.size()).setScale(3, BigDecimal.ROUND_HALF_UP), unit: "V")
}
private void updateAggregateCurrent() {
    List vals = getChildDevices().collect { it.currentValue("amperage") }.findAll { it != null }
    if (vals) sendEvent(name: "amperage", value: vals.collect { it as BigDecimal }.sum().setScale(3, BigDecimal.ROUND_HALF_UP), unit: "A")
}

void componentOn(def child) { Integer ep = parseEndpoint(child.getDataValue("endpoint")); if (ep != null) invokeOutlet(ep, 0x01) }
void componentOff(def child) { Integer ep = parseEndpoint(child.getDataValue("endpoint")); if (ep != null) invokeOutlet(ep, 0x00) }
void componentRefresh(def child) { Integer ep = parseEndpoint(child.getDataValue("endpoint")); if (ep != null) readEndpoint(ep) }
void componentInitialize(def child) { componentRefresh(child) }

private void readEndpoint(Integer ep) {
    def child = getChildDevice("${device.deviceNetworkId}-outlet-${ep}")
    Integer meterEp = parseEndpoint(child?.getDataValue("meterEndpoint")) ?: (ep + 6)
    List<Map<String, String>> paths = [
        matter.attributePath(ep, onOffCluster(), 0x0000),
        matter.attributePath(meterEp, powerCluster(), 0x0008),
        matter.attributePath(meterEp, powerCluster(), 0x000B),
        matter.attributePath(meterEp, powerCluster(), 0x000C),
        matter.attributePath(meterEp, energyCluster(), 0x0001)
    ]
    sendHubCommand(new hubitat.device.HubAction(matter.readAttributes(paths), hubitat.device.Protocol.MATTER))
}

