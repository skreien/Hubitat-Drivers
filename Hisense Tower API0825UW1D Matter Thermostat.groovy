/*
 * Hisense Tower API0825UW1D Matter Thermostat
 * Version: 0.4.0 (2026-10-05)
 *
 * Hubitat driver for the Matter Thermostat endpoint exposed by this unit.
 * The endpoint advertises Thermostat (0x0201) and Thermostat User Interface
 * Configuration (0x0204), but does not advertise Fan Control (0x0202).
 *
 * This driver uses Matter reports and supports Hubitat's Refresh command.
 * A separate Device Poller can continue calling refresh() while reports are
 * being confirmed on the hub.
 */

import groovy.transform.Field
import hubitat.matter.DataType

@Field static final Integer THERMOSTAT_CLUSTER = 0x0201
@Field static final Integer UI_CONFIG_CLUSTER = 0x0204
@Field static final Integer ENDPOINT = 0x01

metadata {
    definition(name: 'Hisense Tower Matter Thermostat', namespace: 'codex', author: 'OpenAI') {
        capability 'Thermostat'
        capability 'Temperature Measurement'
        capability 'Initialize'
        capability 'Refresh'

        attribute 'lastMatterUpdate', 'string'

        command 'discoverMatter'
        command 'fanOnlyMode'
        command 'dryMode'
        command 'sleepMode'
        command 'createTemperatureChild'

        fingerprint endpointId: '01', inClusters: '0003,0004,001D,0201,0204', outClusters: '', model: 'AirConditioner', manufacturer: 'HisenseGroupCo.Ltd', controllerType: 'MAT'
    }

    preferences {
        input name: 'logEnable', type: 'bool', title: 'Enable debug logging', defaultValue: false
    }
}

void installed() {
    initialize()
}

void updated() {
    initialize()
}

void initialize() {
    if (logEnable) log.debug 'initialize(): setting supported thermostat modes and Matter subscription'
    // Current Hubitat Matter callbacks use parse(Map). Support the older
    // parse(String) path too, so this driver remains compatible either way.
    if (device.getDataValue('newParse') != 'true') device.updateDataValue('newParse', 'true')

    // Emergency Heat value 5 was accepted but behaved like the prior mode on this unit.
    sendEvent(name: 'supportedThermostatModes', value: ['off', 'auto', 'cool', 'heat', 'fan', 'dry', 'sleep'], isStateChange: true)
    sendEvent(name: 'supportedThermostatFanModes', value: [], isStateChange: true)

    ensureTemperatureChild()
    subscribeMatter()
    refresh()
}

private void ensureTemperatureChild() {
    String childDni = "${device.deviceNetworkId}-temperature"
    try {
        def child = getChildDevice(childDni)
        if (child) {
            log.info "Temperature child already exists: '${child.displayName}'"
            return
        }

        String childLabel = "${device.displayName ?: device.name} Temperature"
        child = addChildDevice('codex', 'Hisense Tower Temperature Sensor', childDni, [
            name: childLabel,
            label: childLabel,
            isComponent: false
        ])
        if (!child) {
            log.warn "Hubitat did not return a child device for '${childLabel}'. Check that the child driver is installed and saved."
            return
        }
        log.info "Created temperature child device '${childLabel}'"

        def currentTemp = device.currentValue('temperature')
        if (currentTemp != null) {
            String unit = location.temperatureScale ?: 'F'
            child.sendEvent(name: 'temperature', value: currentTemp, unit: unit)
        }
    } catch (Exception e) {
        log.warn "Could not create temperature child (namespace 'codex', driver 'Hisense Tower Temperature Sensor', DNI '${childDni}'): ${e.message}"
    }
}

void createTemperatureChild() {
    ensureTemperatureChild()
}

void parse(String description) {
    Map data
    try {
        data = matter.parseDescriptionAsMap(description)
    } catch (Exception e) {
        log.warn "Could not parse Matter report: ${e.message}"
        return
    }

    if (logEnable) log.debug "Matter report: ${data}"
    String cluster = normalizeHex(data.cluster ?: data.clusterId)
    String attr = normalizeHex(data.attrId ?: data.attributeId)
    String raw = data.value?.toString()
    if (!cluster || !attr || raw == null) return

    if (cluster == '0201') handleThermostatAttribute(Integer.parseInt(attr, 16), raw, false)
}

void parse(Map message) {
    if (logEnable) log.debug "Matter callback map: ${message}"
    Integer endpoint = safeDecimalInt(message.endpointInt)
    Integer cluster = safeDecimalInt(message.clusterInt)
    Integer attr = safeDecimalInt(message.attrInt)
    if (endpoint != ENDPOINT || cluster != THERMOSTAT_CLUSTER || attr == null || message.value == null) return
    handleThermostatAttribute(attr, message.value, true)
}

private void handleThermostatAttribute(Integer attr, Object raw, Boolean alreadyDecoded) {
    switch (attr) {
        case 0x0000:
            Double localC = alreadyDecoded ? decodedMapTemperature(raw) : decodeSignedTemperature(raw.toString())
            if (localC != null) emitTemperature(localC)
            break
        case 0x0011:
            Double coolC = alreadyDecoded ? decodedMapTemperature(raw) : decodeSignedTemperature(raw.toString())
            if (coolC != null) emitSetpoint('coolingSetpoint', coolC)
            break
        case 0x0012:
            Double heatC = alreadyDecoded ? decodedMapTemperature(raw) : decodeSignedTemperature(raw.toString())
            if (heatC != null) emitSetpoint('heatingSetpoint', heatC)
            break
        case 0x001C:
            Integer mode = alreadyDecoded ? safeDecimalInt(raw) : decodeUnsigned(raw.toString())
            if (mode != null) emitMode(mode)
            break
        case 0x0029:
            Integer running = alreadyDecoded ? safeDecimalInt(raw) : decodeUnsigned(raw.toString())
            if (running != null) emitOperatingState(running)
            break
    }
    sendEvent(name: 'lastMatterUpdate', value: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", location.timeZone ?: TimeZone.getDefault()), isStateChange: true)
}

void refresh() {
    List<Map<String, String>> paths = [
        matter.attributePath(ENDPOINT, THERMOSTAT_CLUSTER, 0x0000), // LocalTemperature
        matter.attributePath(ENDPOINT, THERMOSTAT_CLUSTER, 0x0011), // OccupiedCoolingSetpoint
        matter.attributePath(ENDPOINT, THERMOSTAT_CLUSTER, 0x0012), // OccupiedHeatingSetpoint
        matter.attributePath(ENDPOINT, THERMOSTAT_CLUSTER, 0x001B), // ControlSequenceOfOperation
        matter.attributePath(ENDPOINT, THERMOSTAT_CLUSTER, 0x001C)  // SystemMode
    ]
    sendMatter(matter.readAttributes(paths), 'refresh')
}

void discoverMatter() {
    // Reads the global attribute lists and feature maps so the hub log can
    // show which optional attributes this particular endpoint implements.
    List<Map<String, String>> paths = [
        matter.attributePath(ENDPOINT, THERMOSTAT_CLUSTER, 0xFFFB), // AttributeList
        matter.attributePath(ENDPOINT, THERMOSTAT_CLUSTER, 0xFFFC), // FeatureMap
        matter.attributePath(ENDPOINT, THERMOSTAT_CLUSTER, 0xFFFD), // ClusterRevision
        matter.attributePath(ENDPOINT, THERMOSTAT_CLUSTER, 0xFFF8), // GeneratedCommandList
        matter.attributePath(ENDPOINT, THERMOSTAT_CLUSTER, 0xFFF9), // AcceptedCommandList
        matter.attributePath(ENDPOINT, THERMOSTAT_CLUSTER, 0x001B), // ControlSequenceOfOperation
        matter.attributePath(ENDPOINT, THERMOSTAT_CLUSTER, 0x001C), // SystemMode
        matter.attributePath(ENDPOINT, UI_CONFIG_CLUSTER, 0xFFFB),
        matter.attributePath(ENDPOINT, UI_CONFIG_CLUSTER, 0xFFFC),
        matter.attributePath(ENDPOINT, UI_CONFIG_CLUSTER, 0xFFF8),
        matter.attributePath(ENDPOINT, UI_CONFIG_CLUSTER, 0xFFF9),
        matter.attributePath(ENDPOINT, UI_CONFIG_CLUSTER, 0x0000), // TemperatureDisplayMode
        matter.attributePath(ENDPOINT, UI_CONFIG_CLUSTER, 0x0001)  // KeypadLockout
    ]
    sendMatter(matter.readAttributes(paths), 'discoverMatter')
}

void subscribeMatter() {
    List<Map<String, String>> paths = []
    paths.add(matter.attributePath(ENDPOINT, THERMOSTAT_CLUSTER, 0x0000))
    paths.add(matter.attributePath(ENDPOINT, THERMOSTAT_CLUSTER, 0x0011))
    paths.add(matter.attributePath(ENDPOINT, THERMOSTAT_CLUSTER, 0x0012))
    paths.add(matter.attributePath(ENDPOINT, THERMOSTAT_CLUSTER, 0x001C))
    // One subscription covers all selected attributes; the 5-minute maximum
    // report interval matches the user's existing poll cadence.
    sendMatter(matter.cleanSubscribe(1, 300, paths), 'subscribe')
}

void setThermostatMode(String mode) {
    String normalized = mode?.trim()?.toLowerCase()
    normalized = normalized?.replace('_', ' ')?.replace('-', ' ')
    if (normalized == 'fan only') normalized = 'fan'

    Integer matterMode
    switch (normalized) {
        case 'off': matterMode = 0; break
        case 'auto': matterMode = 1; break
        case 'cool': matterMode = 3; break
        case 'heat': matterMode = 4; break
        case 'fan': matterMode = 7; break
        case 'dry': matterMode = 8; break
        case 'sleep': matterMode = 9; break
        default:
            log.warn "Unsupported thermostat mode '${mode}'. Supported modes: off, auto, cool, heat, fan, dry, sleep"
            return
    }
    if (matterMode == null) {
        return
    }

    List<Map<String, String>> writes = []
    writes.add(matter.attributeWriteRequest(ENDPOINT, THERMOSTAT_CLUSTER, 0x001C, DataType.UINT8, byteHex(matterMode)))
    sendMatter(matter.writeAttributes(writes), "setThermostatMode(${normalized})")
    runInMillis(1500, 'refresh')
}

void off() { setThermostatMode('off') }
void auto() { setThermostatMode('auto') }
void heat() { setThermostatMode('heat') }
void cool() { setThermostatMode('cool') }
void fanOnlyMode() { setThermostatMode('fan') }
void dryMode() { setThermostatMode('dry') }
void sleepMode() { setThermostatMode('sleep') }
void emergencyHeat() {
    log.warn 'Emergency Heat is disabled: this unit treated Matter SystemMode 5 as its prior mode.'
}

void setHeatingSetpoint(Object value) {
    writeSetpoint(0x0012, value, 'heatingSetpoint')
}

void setCoolingSetpoint(Object value) {
    writeSetpoint(0x0011, value, 'coolingSetpoint')
}

void setThermostatSetpoint(Object value) {
    String mode = device.currentValue('thermostatMode')?.toString()
    if (mode == 'heat') {
        setHeatingSetpoint(value)
    } else {
        setCoolingSetpoint(value)
    }
}

void setThermostatFanMode(String mode) {
    log.warn "Fan speed '${mode}' cannot be set over this Matter endpoint: it does not advertise the Fan Control cluster (0x0202)."
}

void fanAuto() { setThermostatFanMode('auto') }
void fanCirculate() { setThermostatFanMode('circulate') }
void fanOn() { setThermostatFanMode('on') }

private void writeSetpoint(Integer attribute, Object value, String eventName) {
    if (value == null) return
    BigDecimal userValue
    try {
        userValue = new BigDecimal(value.toString())
    } catch (Exception e) {
        log.warn "Invalid setpoint '${value}'"
        return
    }

    BigDecimal celsius = (location.temperatureScale == 'C') ? userValue : (userValue - 32G) * 5G / 9G
    Integer centiC = celsius.multiply(100G).setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
    List<Map<String, String>> writes = []
    writes.add(matter.attributeWriteRequest(ENDPOINT, THERMOSTAT_CLUSTER, attribute, DataType.INT16, shortHex(centiC)))
    sendMatter(matter.writeAttributes(writes), "${eventName}=${userValue}")
    runInMillis(1500, 'refresh')
}

private void sendMatter(String command, String label) {
    if (!command) {
        log.warn "Matter command was empty (${label})"
        return
    }
    if (logEnable) log.debug "Sending ${label}: ${command}"
    sendHubCommand(new hubitat.device.HubAction(command, hubitat.device.Protocol.MATTER))
}

private void emitTemperature(Double celsius) {
    BigDecimal value = toHubitatTemperature(celsius)
    String unit = location.temperatureScale ?: 'F'
    sendEvent(name: 'temperature', value: value, unit: unit, descriptionText: "${device.displayName} temperature is ${value}°${unit}")

    String childDni = "${device.deviceNetworkId}-temperature"
    def child = getChildDevice(childDni)
    child?.sendEvent(name: 'temperature', value: value, unit: unit, descriptionText: "${device.displayName} temperature is ${value}°${unit}")
}

private void emitSetpoint(String name, Double celsius) {
    BigDecimal value = toHubitatTemperature(celsius)
    String unit = location.temperatureScale ?: 'F'
    sendEvent(name: name, value: value, unit: unit, descriptionText: "${device.displayName} ${name} is ${value}°${unit}")
    String mode = device.currentValue('thermostatMode')?.toString()
    if ((name == 'heatingSetpoint' && mode == 'heat') || (name == 'coolingSetpoint' && mode != 'heat')) {
        sendEvent(name: 'thermostatSetpoint', value: value, unit: unit, descriptionText: "${device.displayName} thermostat setpoint is ${value}°${unit}")
    }
}

private void emitMode(Integer matterMode) {
    String mode
    switch (matterMode) {
        case 0: mode = 'off'; break
        case 1: mode = 'auto'; break
        case 3: mode = 'cool'; break
        case 4: mode = 'heat'; break
        case 7: mode = 'fan'; break
        case 8: mode = 'dry'; break
        case 9: mode = 'sleep'; break
    }
    if (!mode) {
        if (logEnable) log.debug "Unmapped Matter thermostat SystemMode ${matterMode}"
        return
    }
    sendEvent(name: 'thermostatMode', value: mode, descriptionText: "${device.displayName} thermostat mode is ${mode}")

    if (mode == 'heat') {
        def setpoint = device.currentValue('heatingSetpoint')
        if (setpoint != null) sendEvent(name: 'thermostatSetpoint', value: setpoint, unit: location.temperatureScale ?: 'F')
    } else {
        def setpoint = device.currentValue('coolingSetpoint')
        if (setpoint != null) sendEvent(name: 'thermostatSetpoint', value: setpoint, unit: location.temperatureScale ?: 'F')
    }
}

private void emitOperatingState(Integer running) {
    String value = 'idle'
    if ((running & 0x01) != 0) value = 'heating'
    else if ((running & 0x02) != 0) value = 'cooling'
    else if ((running & 0x04) != 0) value = 'fan only'
    sendEvent(name: 'thermostatOperatingState', value: value, descriptionText: "${device.displayName} operating state is ${value}")
}

private BigDecimal toHubitatTemperature(Double celsius) {
    BigDecimal result = (location.temperatureScale == 'C') ? new BigDecimal(celsius.toString()) : new BigDecimal((celsius * 9.0d / 5.0d + 32.0d).toString())
    return result.setScale(1, BigDecimal.ROUND_HALF_UP)
}

private Double decodeSignedTemperature(String raw) {
    Integer value = decodeSigned(raw, 2)
    if (value == null || value == -32768) return null
    return value / 100.0d
}

private Integer decodeUnsigned(String raw) {
    String value = raw?.trim()?.replaceFirst('^(0[xX])', '')
    if (!value) return null
    try {
        // Matter numeric payload bytes are little-endian in parsed TLV values.
        return new BigInteger(reverseBytePairs(value), 16).intValue()
    } catch (Exception e) {
        try { return Integer.parseInt(value) } catch (Exception ignored) { return null }
    }
}

private Integer safeDecimalInt(Object raw) {
    if (raw == null) return null
    try {
        return raw instanceof Number ? ((Number) raw).intValue() : Integer.parseInt(raw.toString(), 10)
    } catch (Exception ignored) {
        return null
    }
}

private Integer signedNumber(Object raw) {
    Integer value = safeDecimalInt(raw)
    if (value == null) return null
    // Matter Temperature is a signed 16-bit centi-Celsius value.
    value = value & 0xFFFF
    return (value & 0x8000) ? value - 0x10000 : value
}

private Double decodedMapTemperature(Object raw) {
    Integer value = signedNumber(raw)
    return (value == null || value == -32768) ? null : value / 100.0d
}

private Integer decodeSigned(String raw, Integer byteCount) {
    Integer value = decodeUnsigned(raw)
    if (value == null) return null
    Integer bits = byteCount * 8
    Integer signBit = 1 << (bits - 1)
    Integer mask = (1 << bits) - 1
    value = value & mask
    return (value & signBit) ? value - (1 << bits) : value
}

private String reverseBytePairs(String value) {
    String normalized = value.replaceAll('[^0-9A-Fa-f]', '')
    if (normalized.length() <= 2 || normalized.length() % 2 != 0) return normalized
    List<String> bytes = []
    for (Integer i = 0; i < normalized.length(); i += 2) bytes.add(normalized.substring(i, i + 2))
    return bytes.reverse().join('')
}

private String shortHex(Integer value) {
    Integer normalized = value & 0xFFFF
    return String.format('%04X', normalized)
}

private String byteHex(Integer value) {
    return String.format('%02X', value & 0xFF)
}

private String normalizeHex(Object value) {
    if (value == null) return null
    return value.toString().replaceFirst('^(0[xX])', '').padLeft(4, '0').toLowerCase()
}
