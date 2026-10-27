package com.zyke.message.domain.life

/** How a trip is made, so a plan can be shown in a way that suits it. */
enum class TravelMode(val label: String) {
    TRAIN("Train"),
    FLIGHT("Flight"),
    BUS("Bus"),
    CAB("Cab"),
    HOTEL("Stay"),
    OTHER("Trip")
}

/** A trip as far as its message says: how it is made and, when stated, where from and to. */
data class Journey(val mode: TravelMode, val from: String?, val to: String?) {
    val route: String? get() = if (from != null && to != null) "$from → $to" else null
}

object Journeys {
    private val FLIGHT = Regex("(?i)\\b(?:flight|airlines?|airways|airport|terminal|boarding pass|indigo|air india|vistara|spicejet|akasa|air asia|web check-?in|gate)\\b")
    private val TRAIN = Regex("(?i)\\b(?:train|irctc|railway|rly|coach|berth|rajdhani|shatabdi|express|chart prepared|sleeper|sl|3a|2a|1a|cc|ec)\\b")
    private val BUS = Regex("(?i)\\b(?:bus|redbus|abhibus|volvo|seat no|boarding point|tnstc|ksrtc|msrtc)\\b")
    private val CAB = Regex("(?i)\\b(?:cab|ride|uber|ola|rapido|driver|pickup)\\b")
    private val HOTEL = Regex("(?i)\\b(?:hotel|stay|oyo|resort|room|check-?in at|booking\\.com)\\b")
    private val TEN_DIGIT_PNR = Regex("(?i)\\bPNR\\s*(?:no\\.?|number|:)?\\s*[:\\-]?\\s*\\d{10}\\b")

    private val FROM_TO = Regex(
        "(?i)\\b(?:from|frm)\\s*[:\\-]?\\s*([A-Za-z][A-Za-z .]{1,24}?)\\s*(?:to|->|→|-)\\s*([A-Za-z][A-Za-z .]{1,24}?)(?=\\s+(?:on|at|dep|for|doj|date|train|pnr|flight|bus|via)\\b|[.,;]|\\d|$)"
    )
    private val AIRPORTS = Regex("\\b([A-Z]{3})\\s*(?:-|to|→|>)\\s*([A-Z]{3})\\b")
    private val NOT_A_PLACE = setOf("the", "your", "you", "this", "that")

    fun of(body: String, sender: String = ""): Journey {
        val text = "$sender $body".replace(Regex("\\s+"), " ")
        val mode = when {
            FLIGHT.containsMatchIn(text) -> TravelMode.FLIGHT
            TEN_DIGIT_PNR.containsMatchIn(text) || TRAIN.containsMatchIn(text) -> TravelMode.TRAIN
            BUS.containsMatchIn(text) -> TravelMode.BUS
            HOTEL.containsMatchIn(text) -> TravelMode.HOTEL
            CAB.containsMatchIn(text) -> TravelMode.CAB
            else -> TravelMode.OTHER
        }
        val (from, to) = route(text)
        return Journey(mode, from, to)
    }

    private fun route(text: String): Pair<String?, String?> {
        FROM_TO.find(text)?.let { match ->
            val from = place(match.groupValues[1])
            val to = place(match.groupValues[2])
            if (from != null && to != null) return from to to
        }
        AIRPORTS.find(text)?.let { return it.groupValues[1] to it.groupValues[2] }
        return null to null
    }

    private fun place(raw: String): String? {
        val clean = raw.trim().trimEnd('-', '.', ',').trim()
        return clean.takeIf { it.length >= 2 && it.lowercase() !in NOT_A_PLACE }
    }
}
