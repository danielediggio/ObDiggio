package org.diggio.obdiggio.core.vag

/**
 * A VAG ECU reachable via the OBD-II port (high-speed CAN, ATSP6).
 *
 * [txId] — CAN ID of the diagnostic REQUEST frame (what the scan tool sends)
 * [rxId] — CAN ID of the diagnostic RESPONSE frame (what the ECU answers with)
 *
 * On an ELM327 with ATSP6, setting ATSH [txId] is enough; the adapter
 * automatically expects responses at [txId + 8] which matches the standard
 * OBD-II physical-address layout. [rxId] is kept explicit for reference and
 * future use with ATCRA.
 *
 * Addresses verified against Audi A6 C6 (4F) wiring diagrams, ETKA, and
 * community VCDS traces for the 2004–2011 C6 platform.
 */
data class VagEcu(
    val id: String,
    val name: String,
    val txId: Int,
    val rxId: Int,
    val note: String = ""
)

object VagEcus {
    // ── High-speed CAN (500 kbaud, ATSP6) ────────────────────────────────────
    // These ECUs sit on the same CAN bus as the OBD-II gateway and are
    // reachable with physical addressing from any ELM327 adapter.

    val ENGINE   = VagEcu("engine",   "Motore",            0x7E0, 0x7E8, "EDC16/MED17 — include immobilizer status")
    val GEARBOX  = VagEcu("gearbox",  "Cambio Tiptronic",  0x7E1, 0x7E9, "ZF 6HP — guasti trasmissione")
    val ABS      = VagEcu("abs",      "ABS / ESP",         0x760, 0x768, "Bosch ESP 8 / Teves MK60")
    val STEERING = VagEcu("steering", "Sterzo EPS",        0x712, 0x71A, "ZF Servocom / Bosch EPS")
    val GATEWAY  = VagEcu("gateway",  "Gateway J533",      0x788, 0x798, "Central gateway — bridges CAN buses")

    // ── Routed via gateway (may not respond on all configurations) ────────────
    // The central gateway (J533) bridges high-speed and medium/low-speed CAN.
    // Whether these ECUs answer UDS queries depends on gateway firmware.

    val CLUSTER  = VagEcu("cluster",  "Quadro strumenti",  0x714, 0x71C, "J285 — velocità, conta-giri, spie; contiene dati IMMO")
    val AIRBAG   = VagEcu("airbag",   "Airbag / SRS",      0x740, 0x748, "J234 — airbag e cinture pretensionatori")
    val CLIMATE  = VagEcu("climate",  "Climatronic",       0x7C0, 0x7C8, "J255 — clima automatico")
    val PARKING  = VagEcu("parking",  "Sensori parcheggio",0x736, 0x73E, "J791 — PDC anteriore/posteriore")

    // ── Immobilizer / anti-theft (CRITICAL for no-start diagnosis) ─────────
    // J518 — Schlüssellesering (access and start authorization module).
    // On C6 A6, J518 sits on the K-CAN and is normally reached only via the
    // gateway; on some builds it also answers direct UDS at 0x7A0. Worth
    // probing — it will return NO DATA if not routed. Holds the rolling-code
    // IMMO data that must match the engine ECU and instrument cluster (J285).
    val IMMO     = VagEcu("immo",     "Immobilizer J518",  0x7A0, 0x7A8, "J518 — modulo autorizzazione avviamento; K-CAN, risposta variabile")

    /** Ordered list: engine + immo first (most important for no-start diagnosis). */
    val all: List<VagEcu> = listOf(ENGINE, IMMO, CLUSTER, GEARBOX, ABS, STEERING, GATEWAY, AIRBAG, CLIMATE, PARKING)
}
