package org.diggio.obdiggio.core.obd

data class Pid(
    val code: Int,
    val name: String,
    val unit: String,
    val numBytes: Int,
    val minValue: Double,
    val maxValue: Double,
    val decoder: (IntArray) -> Double
) {
    val key: String get() = name.lowercase().replace(" ", "_").replace("'", "")

    fun command() = "01%02X".format(code)

    fun decode(data: IntArray): PidResult = runCatching {
        PidResult(this, decoder(data), unit)
    }.getOrElse { PidResult(this, null, unit) }
}

data class PidResult(val pid: Pid, val value: Double?, val unit: String) {
    val name: String get() = pid.name
}

object Pids {
    private val percent: (IntArray) -> Double = { it[0] * 100.0 / 255.0 }
    private val temp: (IntArray) -> Double = { it[0] - 40.0 }
    private val rpm: (IntArray) -> Double = { ((it[0] * 256) + it[1]) / 4.0 }
    private val speed: (IntArray) -> Double = { it[0].toDouble() }
    private val timingAdvance: (IntArray) -> Double = { it[0] / 2.0 - 64.0 }
    private val maf: (IntArray) -> Double = { ((it[0] * 256) + it[1]) / 100.0 }
    private val intakePressure: (IntArray) -> Double = { it[0].toDouble() }
    private val controlModuleVoltage: (IntArray) -> Double = { ((it[0] * 256) + it[1]) / 1000.0 }
    private val fuelTrim: (IntArray) -> Double = { it[0] / 1.28 - 100.0 }
    private val runTime: (IntArray) -> Double = { ((it[0] * 256) + it[1]).toDouble() }
    private val distance: (IntArray) -> Double = { ((it[0] * 256) + it[1]).toDouble() }
    private val railPressure: (IntArray) -> Double = { ((it[0] * 256) + it[1]) * 10.0 }
    private val egrError: (IntArray) -> Double = { it[0] / 1.28 - 100.0 }

    val all: List<Pid> = listOf(
        Pid(4,  "Carico motore",           "%",    1, 0.0,      100.0,    percent),
        Pid(5,  "Temp refrigerante",        "°C",   1, -40.0,    215.0,    temp),
        Pid(6,  "Fuel trim breve B1",       "%",    1, -100.0,   99.0,     fuelTrim),
        Pid(7,  "Fuel trim lungo B1",       "%",    1, -100.0,   99.0,     fuelTrim),
        Pid(10, "Pressione carburante",     "kPa",  1, 0.0,      765.0,    { it[0] * 3.0 }),
        Pid(11, "Pressione aspirazione",    "kPa",  1, 0.0,      255.0,    intakePressure),
        Pid(12, "RPM",                      "rpm",  2, 0.0,      8000.0,   rpm),
        Pid(13, "Velocita'",                "km/h", 1, 0.0,      255.0,    speed),
        Pid(14, "Anticipo accensione",      "°",    1, -64.0,    63.0,     timingAdvance),
        Pid(15, "Temp aria aspirata",       "°C",   1, -40.0,    215.0,    temp),
        Pid(16, "MAF",                      "g/s",  2, 0.0,      655.0,    maf),
        Pid(17, "Posizione farfalla",       "%",    1, 0.0,      100.0,    percent),
        Pid(31, "Tempo motore acceso",      "s",    2, 0.0,      65535.0,  runTime),
        Pid(33, "Distanza con MIL",         "km",   2, 0.0,      65535.0,  distance),
        Pid(34, "Pressione rail (rel.)",    "kPa",  2, 0.0,      5177.0,   { ((it[0] * 256) + it[1]) * 0.079 }),
        Pid(35, "Pressione rail",           "kPa",  2, 0.0,      655350.0, railPressure),
        Pid(44, "EGR comandata",            "%",    1, 0.0,      100.0,    percent),
        Pid(45, "Errore EGR",               "%",    1, -100.0,   99.0,     egrError),
        Pid(47, "Livello carburante",       "%",    1, 0.0,      100.0,    percent),
        Pid(49, "Distanza da azzeramento",  "km",   2, 0.0,      65535.0,  distance),
        Pid(51, "Pressione barometrica",    "kPa",  1, 0.0,      255.0,    intakePressure),
        Pid(66, "Tensione modulo",          "V",    2, 0.0,      65.0,     controlModuleVoltage),
        Pid(67, "Carico assoluto",          "%",    2, 0.0,      25700.0,  { (((it[0] * 256) + it[1]) * 100.0) / 255.0 }),
        Pid(69, "Farfalla relativa",        "%",    1, 0.0,      100.0,    percent),
        Pid(70, "Temp ambiente",            "°C",   1, -40.0,    215.0,    temp),
        Pid(73, "Pedale acceleratore",      "%",    1, 0.0,      100.0,    percent),
        Pid(76, "Farfalla comandata",       "%",    1, 0.0,      100.0,    percent),
        Pid(92, "Temp olio motore",         "°C",   1, -40.0,    215.0,    temp),
        Pid(94, "Consumo carburante",       "L/h",  2, 0.0,      3277.0,   { ((it[0] * 256) + it[1]) * 0.05 })
    )

    private val byCode: Map<Int, Pid> = all.associateBy { it.code }

    operator fun get(code: Int): Pid? = byCode[code]
}
