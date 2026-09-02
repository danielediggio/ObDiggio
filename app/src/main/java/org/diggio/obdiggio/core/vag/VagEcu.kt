package org.diggio.obdiggio.core.vag

/**
 * Logical VAG diagnostic addresses for the Audi A6 C6/4F pre-facelift.
 * These are TP2.0/KWP2000 controller addresses, not guessed ISO-TP IDs.
 */
data class VagEcu(
    val id: String,
    val name: String,
    val logicalAddress: Int,
    val note: String = ""
)

object VagEcus {
    val ENGINE = VagEcu("engine", "Motore", 0x01, "Motore, emissioni, valori misurati")
    val GEARBOX = VagEcu("gearbox", "Cambio automatico", 0x02, "Tiptronic/Multitronic, se installato")
    val ABS = VagEcu("abs", "ABS / ESP", 0x03, "Ruote, sterzo, frenata, stabilita'")
    val CLIMATE = VagEcu("climate", "Climatronic", 0x08, "Sensori, pressioni e attuatori clima")
    val AIRBAG = VagEcu("airbag", "Airbag / SRS", 0x15, "Diagnostica guasti passiva")
    val CLUSTER = VagEcu("cluster", "Quadro strumenti", 0x17, "Strumenti e rete")
    val GATEWAY = VagEcu("gateway", "Gateway J533", 0x19, "Reti e lista moduli installati")
    val STEERING = VagEcu("steering", "Sterzo", 0x44, "Angolo volante e assistenza")
    val COMFORT = VagEcu("comfort", "Comfort centrale", 0x46, "Porte, serrature, vetri")
    val PARKING = VagEcu("parking", "Sensori parcheggio", 0x76, "PDC, se installato")

    val all: List<VagEcu> = listOf(
        ENGINE, GEARBOX, ABS, CLIMATE, AIRBAG, CLUSTER, GATEWAY, STEERING, COMFORT, PARKING
    )
}
