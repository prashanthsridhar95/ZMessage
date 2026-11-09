package com.zyke.message.domain.flight

import java.time.ZoneId

/** An airline by its two-character IATA code, as it prefixes a flight number ("6E 2345"). */
data class Airline(val code: String, val name: String)

object Airlines {
    private val all = listOf(
        Airline("6E", "IndiGo"), Airline("AI", "Air India"), Airline("UK", "Vistara"), Airline("SG", "SpiceJet"),
        Airline("QP", "Akasa Air"), Airline("IX", "Air India Express"), Airline("I5", "AIX Connect"), Airline("S5", "Star Air"),
        Airline("9I", "Alliance Air"), Airline("G8", "Go First"), Airline("2T", "TruJet"), Airline("OP", "Fly91"),
        Airline("EK", "Emirates"), Airline("QR", "Qatar Airways"), Airline("EY", "Etihad"), Airline("SQ", "Singapore Airlines"),
        Airline("TR", "Scoot"), Airline("TG", "Thai Airways"), Airline("BA", "British Airways"), Airline("LH", "Lufthansa"),
        Airline("AF", "Air France"), Airline("KL", "KLM"), Airline("SV", "Saudia"), Airline("WY", "Oman Air"),
        Airline("G9", "Air Arabia"), Airline("FZ", "flydubai"), Airline("UL", "SriLankan"), Airline("MH", "Malaysia Airlines"),
        Airline("AK", "AirAsia"), Airline("CX", "Cathay Pacific"), Airline("QF", "Qantas"), Airline("UA", "United"),
        Airline("AA", "American"), Airline("DL", "Delta"), Airline("TK", "Turkish Airlines")
    ).associateBy { it.code }

    fun byCode(code: String): Airline? = all[code.uppercase()]

    /** The airline codes the message parser accepts in front of a flight number. */
    val codes: Set<String> get() = all.keys

    /** The airline named in words ("IndiGo", "Air India Express"), longest name first so "Air India Express" is not read as "Air India". */
    fun byNameIn(text: String): Airline? =
        all.values.sortedByDescending { it.name.length }.firstOrNull { Regex("(?i)\\b${Regex.escape(it.name)}\\b").containsMatchIn(text) }
}

/** An airport: its IATA code, the city people say, and the time zone its clock is in. */
data class Airport(val iata: String, val city: String, val zone: ZoneId)

object Airports {
    private val IST = ZoneId.of("Asia/Kolkata")

    private fun india(iata: String, city: String) = Airport(iata, city, IST)

    private val all = listOf(
        india("DEL", "Delhi"), india("BOM", "Mumbai"), india("MAA", "Chennai"), india("BLR", "Bengaluru"), india("HYD", "Hyderabad"),
        india("CCU", "Kolkata"), india("COK", "Kochi"), india("AMD", "Ahmedabad"), india("PNQ", "Pune"), india("GOI", "Goa"),
        india("JAI", "Jaipur"), india("LKO", "Lucknow"), india("TRV", "Thiruvananthapuram"), india("CJB", "Coimbatore"),
        india("IXM", "Madurai"), india("TRZ", "Tiruchirappalli"), india("VTZ", "Visakhapatnam"), india("PAT", "Patna"),
        india("GAU", "Guwahati"), india("IXC", "Chandigarh"), india("SXR", "Srinagar"), india("ATQ", "Amritsar"),
        india("BBI", "Bhubaneswar"), india("NAG", "Nagpur"), india("IDR", "Indore"), india("IXB", "Bagdogra"),
        india("IXE", "Mangaluru"), india("CCJ", "Kozhikode"), india("VGA", "Vijayawada"), india("RPR", "Raipur"),
        india("BHO", "Bhopal"), india("VNS", "Varanasi"), india("IXR", "Ranchi"), india("IXJ", "Jammu"), india("IXL", "Leh"),
        india("UDR", "Udaipur"), india("JDH", "Jodhpur"), india("DED", "Dehradun"), india("IXZ", "Port Blair"), india("TIR", "Tirupati"),
        india("HBX", "Hubballi"), india("IMF", "Imphal"), india("DIB", "Dibrugarh"), india("AGR", "Agra"), india("SHL", "Shillong"),
        Airport("DXB", "Dubai", ZoneId.of("Asia/Dubai")), Airport("AUH", "Abu Dhabi", ZoneId.of("Asia/Dubai")),
        Airport("SHJ", "Sharjah", ZoneId.of("Asia/Dubai")), Airport("DOH", "Doha", ZoneId.of("Asia/Qatar")),
        Airport("SIN", "Singapore", ZoneId.of("Asia/Singapore")), Airport("BKK", "Bangkok", ZoneId.of("Asia/Bangkok")),
        Airport("KUL", "Kuala Lumpur", ZoneId.of("Asia/Kuala_Lumpur")), Airport("CMB", "Colombo", ZoneId.of("Asia/Colombo")),
        Airport("KTM", "Kathmandu", ZoneId.of("Asia/Kathmandu")), Airport("DAC", "Dhaka", ZoneId.of("Asia/Dhaka")),
        Airport("LHR", "London", ZoneId.of("Europe/London")), Airport("JFK", "New York", ZoneId.of("America/New_York")),
        Airport("SFO", "San Francisco", ZoneId.of("America/Los_Angeles")), Airport("HKG", "Hong Kong", ZoneId.of("Asia/Hong_Kong")),
        Airport("MCT", "Muscat", ZoneId.of("Asia/Muscat")), Airport("RUH", "Riyadh", ZoneId.of("Asia/Riyadh")),
        Airport("JED", "Jeddah", ZoneId.of("Asia/Riyadh"))
    ).associateBy { it.iata }

    /** Other names people write for the same city. */
    private val aliases = mapOf(
        "bangalore" to "BLR", "bengaluru" to "BLR", "bombay" to "BOM", "madras" to "MAA", "calcutta" to "CCU", "cochin" to "COK",
        "trivandrum" to "TRV", "new delhi" to "DEL", "goa" to "GOI", "trichy" to "TRZ", "vizag" to "VTZ", "mangalore" to "IXE",
        "calicut" to "CCJ", "poona" to "PNQ", "benares" to "VNS", "banaras" to "VNS", "gurgaon" to "DEL", "abu dhabi" to "AUH"
    )

    fun byIata(code: String): Airport? = all[code.uppercase()]

    fun isKnown(code: String): Boolean = code.uppercase() in all

    fun zone(code: String?): ZoneId? = code?.let { byIata(it)?.zone }

    /** The city for a code, or the code itself when it is not on the list. */
    fun city(code: String): String = byIata(code)?.city ?: code.uppercase()

    /** "Chennai", "Bangalore", "New Delhi" to its code; null for a name that is not a known airport city. */
    fun iataForCity(name: String): String? {
        val key = name.trim().lowercase()
        aliases[key]?.let { return it }
        return all.values.firstOrNull { it.city.lowercase() == key }?.iata
    }

    /** Every city word the parser may find, longest first so "New Delhi" wins over "Delhi". */
    val cityNames: List<String> by lazy {
        (all.values.map { it.city.lowercase() } + aliases.keys).distinct().sortedByDescending { it.length }
    }
}
