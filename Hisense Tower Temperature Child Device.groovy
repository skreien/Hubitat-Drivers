/*
 * Hisense Tower Temperature Child Device
 * Version: 0.3.8 (2026-10-05)
 *
 * Temperature-only child device created and updated by the parent
 * Hisense Tower Matter Thermostat driver. It does not communicate with Matter.
 */

metadata {
    definition(name: 'Hisense Tower Temperature Sensor', namespace: 'codex', author: 'OpenAI') {
        capability 'Temperature Measurement'
    }
}
