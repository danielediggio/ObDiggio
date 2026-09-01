package org.diggio.obdiggio.core.obd

data class Dtc(val code: String) {

    val description: String get() = Companion.describe(code)

    override fun toString() = "$code — $description"

    companion object {
        private val SYSTEM_LETTERS = mapOf(0 to "P", 1 to "C", 2 to "B", 3 to "U")

        val DESCRIPTIONS: Map<String, String> = mapOf(
            "P0100" to "Circuito sensore portata aria (MAF)",
            "P0101" to "Portata aria/MAF fuori range",
            "P0102" to "Segnale MAF troppo basso",
            "P0103" to "Segnale MAF troppo alto",
            "P0104" to "Circuito MAF intermittente",
            "P0105" to "Circuito sensore pressione collettore (MAP)",
            "P0106" to "Pressione collettore (MAP) fuori range",
            "P0107" to "Sensore pressione collettore (MAP) troppo basso",
            "P0108" to "Sensore pressione collettore (MAP) troppo alto",
            "P0110" to "Circuito sensore temperatura aria aspirata (IAT)",
            "P0111" to "Sensore temperatura aria aspirata (IAT) fuori range",
            "P0112" to "Sensore temperatura aria aspirata (IAT) basso",
            "P0113" to "Sensore temperatura aria aspirata (IAT) alto",
            "P0115" to "Circuito sensore temperatura refrigerante (ECT)",
            "P0116" to "Sensore temperatura refrigerante (ECT) fuori range",
            "P0117" to "Sensore temperatura refrigerante (ECT) basso",
            "P0118" to "Sensore temperatura refrigerante (ECT) alto",
            "P0120" to "Circuito sensore posizione farfalla/pedale (TPS)",
            "P0121" to "Sensore posizione farfalla/pedale fuori range",
            "P0122" to "Sensore posizione farfalla/pedale basso",
            "P0123" to "Sensore posizione farfalla/pedale alto",
            "P0128" to "Refrigerante sotto la temperatura di regolazione (termostato)",
            "P0130" to "Circuito sonda lambda (B1S1)",
            "P0131" to "Sonda lambda tensione bassa (B1S1)",
            "P0132" to "Sonda lambda tensione alta (B1S1)",
            "P0133" to "Sonda lambda risposta lenta (B1S1)",
            "P0134" to "Sonda lambda nessuna attività (B1S1)",
            "P0135" to "Riscaldatore sonda lambda (B1S1)",
            "P0136" to "Circuito sonda lambda (B1S2)",
            "P0137" to "Sonda lambda tensione bassa (B1S2)",
            "P0138" to "Sonda lambda tensione alta (B1S2)",
            "P0140" to "Sonda lambda nessuna attività (B1S2)",
            "P0141" to "Riscaldatore sonda lambda (B1S2)",
            "P0170" to "Correzione carburante anomala (Banco 1)",
            "P0171" to "Miscela troppo magra (Banco 1)",
            "P0172" to "Miscela troppo ricca (Banco 1)",
            "P0174" to "Miscela troppo magra (Banco 2)",
            "P0175" to "Miscela troppo ricca (Banco 2)",
            "P0180" to "Circuito sensore temperatura carburante A",
            "P0182" to "Sensore temperatura carburante basso",
            "P0183" to "Sensore temperatura carburante alto",
            "P0087" to "Pressione rail/carburante troppo bassa",
            "P0088" to "Pressione rail/carburante troppo alta",
            "P0089" to "Regolatore pressione carburante — prestazioni",
            "P0090" to "Circuito regolatore pressione carburante",
            "P0091" to "Regolatore pressione carburante tensione bassa",
            "P0092" to "Regolatore pressione carburante tensione alta",
            "P0093" to "Perdita grande nel circuito carburante",
            "P0201" to "Circuito iniettore cilindro 1",
            "P0202" to "Circuito iniettore cilindro 2",
            "P0203" to "Circuito iniettore cilindro 3",
            "P0204" to "Circuito iniettore cilindro 4",
            "P0234" to "Sovrapressione turbo (overboost)",
            "P0235" to "Circuito sensore pressione turbo",
            "P0236" to "Sensore pressione turbo fuori range",
            "P0243" to "Circuito valvola wastegate turbo",
            "P0245" to "Valvola wastegate turbo bassa",
            "P0246" to "Valvola wastegate turbo alta",
            "P0299" to "Sottopressione turbo (underboost)",
            "P0300" to "Mancata combustione (misfire) casuale/multipla",
            "P0301" to "Misfire cilindro 1",
            "P0302" to "Misfire cilindro 2",
            "P0303" to "Misfire cilindro 3",
            "P0304" to "Misfire cilindro 4",
            "P0325" to "Circuito sensore di detonazione (Banco 1)",
            "P0335" to "Circuito sensore posizione albero motore (CKP)",
            "P0336" to "Sensore posizione albero motore fuori range",
            "P0340" to "Circuito sensore posizione albero a camme (CMP)",
            "P0341" to "Sensore posizione albero a camme fuori range",
            "P0380" to "Circuito candelette di preriscaldo 'A'",
            "P0381" to "Spia candelette di preriscaldo",
            "P0400" to "Ricircolo gas di scarico (EGR) — flusso",
            "P0401" to "Flusso EGR insufficiente",
            "P0402" to "Flusso EGR eccessivo",
            "P0403" to "Circuito controllo valvola EGR",
            "P0404" to "Valvola EGR fuori range",
            "P0405" to "Sensore posizione EGR basso",
            "P0406" to "Sensore posizione EGR alto",
            "P0407" to "Sensore posizione EGR 'B' basso",
            "P0409" to "Circuito sensore posizione EGR",
            "P0420" to "Efficienza catalizzatore sotto soglia (Banco 1)",
            "P0430" to "Efficienza catalizzatore sotto soglia (Banco 2)",
            "P0442" to "Piccola perdita sistema evaporativo (EVAP)",
            "P0446" to "Circuito controllo sfiato EVAP",
            "P0455" to "Grande perdita sistema evaporativo (EVAP)",
            "P0470" to "Circuito sensore pressione gas di scarico",
            "P0471" to "Sensore pressione gas di scarico fuori range",
            "P0472" to "Sensore pressione gas di scarico basso",
            "P0473" to "Sensore pressione gas di scarico alto",
            "P0475" to "Valvola controllo pressione scarico",
            "P0480" to "Circuito relè ventola raffreddamento 1",
            "P0487" to "Circuito sensore posizione farfalla EGR",
            "P0488" to "Regolazione farfalla EGR",
            "P0489" to "Circuito controllo EGR 'A' basso",
            "P0490" to "Circuito controllo EGR 'A' alto",
            "P0500" to "Circuito sensore velocità veicolo (VSS)",
            "P0501" to "Sensore velocità veicolo fuori range",
            "P0503" to "Sensore velocità veicolo intermittente",
            "P0504" to "Correlazione interruttori freno 'A'/'B'",
            "P0505" to "Controllo del minimo (IAC) — malfunzionamento",
            "P0506" to "Regime minimo troppo basso",
            "P0507" to "Regime minimo troppo alto",
            "P0562" to "Tensione impianto troppo bassa",
            "P0563" to "Tensione impianto troppo alta",
            "P0565" to "Segnale cruise control",
            "P0603" to "Memoria interna centralina (KAM) — errore",
            "P0605" to "Memoria ROM centralina — errore",
            "P0606" to "Processore centralina — guasto",
            "P062F" to "Memoria EEPROM centralina — errore",
            "P0627" to "Circuito controllo pompa carburante",
            "P0629" to "Pompa carburante — tensione alta",
            "P0670" to "Circuito modulo controllo candelette",
            "P0671" to "Circuito candeletta cilindro 1",
            "P0672" to "Circuito candeletta cilindro 2",
            "P0673" to "Circuito candeletta cilindro 3",
            "P0674" to "Circuito candeletta cilindro 4",
            "P0700" to "Sistema controllo trasmissione — malfunzionamento",
            "P0705" to "Sensore posizione marcia (PRNDL)",
            "P0715" to "Sensore velocità turbina/ingresso cambio",
            "P0720" to "Sensore velocità uscita cambio",
            "P0730" to "Rapporto marcia errato",
            "P0740" to "Frizione convertitore (TCC) — circuito",
            "P0741" to "Frizione convertitore (TCC) — bloccata aperta",
            "P2002" to "Efficienza filtro antiparticolato (FAP/DPF) sotto soglia",
            "P2003" to "Efficienza filtro antiparticolato (Banco 2)",
            "P242F" to "Filtro antiparticolato intasato (accumulo ceneri)",
            "P2100" to "Circuito motore controllo farfalla",
            "P2101" to "Motore controllo farfalla — range/prestazioni",
            "P2122" to "Sensore pedale acceleratore 'D' basso",
            "P2123" to "Sensore pedale acceleratore 'D' alto",
            "P2127" to "Sensore pedale acceleratore 'E' basso",
            "P2128" to "Sensore pedale acceleratore 'E' alto",
            "P2138" to "Correlazione sensori pedale acceleratore D/E",
            "P2195" to "Sonda lambda bloccata magra (B1S1)",
            "P2196" to "Sonda lambda bloccata ricca (B1S1)",
            "P2237" to "Circuito pompaggio sonda lambda (B1S1)",
            "P2263" to "Sistema turbo/compressore — prestazioni",
            "P2299" to "Correlazione freno / pedale acceleratore",
            "P2452" to "Circuito sensore pressione differenziale DPF",
            "P2453" to "Sensore pressione differenziale DPF fuori range",
            "P2454" to "Sensore pressione differenziale DPF basso",
            "P2455" to "Sensore pressione differenziale DPF alto",
            "P244A" to "Pressione differenziale DPF troppo bassa",
            "P244B" to "Pressione differenziale DPF troppo alta",
            "P2458" to "Durata rigenerazione DPF anomala",
            "P2459" to "Frequenza rigenerazione DPF anomala",
            "P226C" to "Fuoricampo pressione turbo (deviazione)",
            "U0001" to "Bus CAN alta velocità",
            "U0100" to "Persa comunicazione con centralina motore (ECM/PCM)",
            "U0101" to "Persa comunicazione con centralina cambio (TCM)",
            "U0121" to "Persa comunicazione con centralina ABS",
            "U0155" to "Persa comunicazione con quadro strumenti",
            "U0401" to "Dati non validi dalla centralina motore"
        )

        fun describe(code: String): String = DESCRIPTIONS[code] ?: structural(code)

        private fun structural(code: String): String {
            if (code.length < 5) return "Codice diagnostico"
            val area = when (code[0]) {
                'P' -> "Motore/trasmissione"
                'C' -> "Telaio (ABS, sterzo, sospensioni)"
                'B' -> "Carrozzeria (airbag, clima, comfort)"
                'U' -> "Rete di comunicazione (CAN/bus)"
                else -> "Sistema"
            }
            val maker = if (code[1] == '1' || code[1] == '3') " — specifico del costruttore" else ""
            if (code[0] != 'P') return area + maker
            val sub = when (code[2]) {
                '0', '1', '2' -> "gestione aria/carburante e dosaggio"
                '3' -> "accensione o mancata combustione (misfire)"
                '4' -> "controllo emissioni (EGR, EVAP, catalizzatore, aria secondaria)"
                '5' -> "controllo minimo, velocità veicolo e ingressi ausiliari"
                '6' -> "centralina, uscite e comunicazioni interne"
                '7', '8', '9' -> "cambio/trasmissione"
                'A', 'B', 'C' -> "propulsione ibrida"
                else -> "sistema motore"
            }
            return "Motore: $sub$maker — consultare il manuale"
        }

        fun decode(byteA: Int, byteB: Int): Dtc? {
            if (byteA == 0 && byteB == 0) return null
            val letter = SYSTEM_LETTERS[(byteA and 0xC0) shr 6] ?: "P"
            val d1 = (byteA and 0x30) shr 4
            val d2 = byteA and 0x0F
            val d3 = (byteB and 0xF0) shr 4
            val d4 = byteB and 0x0F
            return Dtc("%s%d%X%X%X".format(letter, d1, d2, d3, d4))
        }

        fun decodeBytes(data: IntArray): List<Dtc> {
            val out = mutableListOf<Dtc>()
            var i = 0
            while (i < data.size - 1) {
                decode(data[i], data[i + 1])?.let { out.add(it) }
                i += 2
            }
            return out
        }
    }
}
