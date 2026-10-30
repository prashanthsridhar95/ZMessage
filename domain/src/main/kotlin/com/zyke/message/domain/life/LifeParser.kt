package com.zyke.message.domain.life

import java.time.LocalDate
import java.time.LocalTime

enum class LifeKind(val label: String) {
    DELIVERY("Order"),
    TRAVEL("Trip"),
    APPOINTMENT("Appointment"),
    RENEWAL("Renewal")
}

/** What a non-money message is about: an order on its way, a trip, an appointment, something that expires. */
data class LifeFacts(
    val kind: LifeKind,
    /** Short, human: "Out for delivery", "Booking confirmed", "Insurance expires". */
    val status: String,
    val date: LocalDate?,
    val time: LocalTime?,
    /** PNR, booking or order number when the message has one. */
    val reference: String?,
    /** A delivery that has already arrived, or an event that is over, is no longer "ahead". */
    val isDone: Boolean = false
)

/**
 * Reads orders, bookings, appointments and renewals from the way such messages are worded. Rules only, so it
 * behaves the same on every phone; a message that does not clearly fit is left alone rather than guessed at.
 */
object LifeParser {

    fun parse(body: String, receivedOn: LocalDate): LifeFacts? {
        val text = body.replace(Regex("\\s+"), " ").trim()
        return renewal(text, receivedOn) ?: appointment(text, receivedOn) ?: travel(text, receivedOn) ?: delivery(text)
    }

    // ---- deliveries

    private val DELIVERED = Regex("(?i)\\b(?:has been |was |successfully )?delivered\\b|\\bdelivery (?:completed|successful)\\b")
    private val OUT_FOR_DELIVERY = Regex("(?i)\\bout for delivery\\b|\\barriving (?:today|tomorrow)\\b|\\bwill be delivered (?:today|tomorrow)\\b")
    private val SHIPPED = Regex("(?i)\\b(?:has been |is )?(?:shipped|dispatched)\\b|\\bon (?:its|the) way\\b|\\bin transit\\b")
    private val ORDER_PLACED = Regex("(?i)\\border (?:has been )?(?:placed|confirmed|received)\\b|\\bthanks for (?:your order|shopping)\\b")
    private val ORDER_WORD = Regex("(?i)\\b(?:order|shipment|parcel|package|awb|consignment|delivery)\\b")
    private val ORDER_REF = Regex("(?i)\\b(?:order|awb|shipment|tracking)(?: ?(?:id|no\\.?|number|#))?\\s*[:\\-#]?\\s*([A-Z0-9][A-Z0-9-]{5,24})\\b")

    private fun delivery(text: String): LifeFacts? {
        if (!ORDER_WORD.containsMatchIn(text)) return null
        val (status, done) = when {
            DELIVERED.containsMatchIn(text) -> "Delivered" to true
            OUT_FOR_DELIVERY.containsMatchIn(text) -> "Out for delivery" to false
            SHIPPED.containsMatchIn(text) -> "On the way" to false
            ORDER_PLACED.containsMatchIn(text) -> "Order placed" to false
            else -> return null
        }
        return LifeFacts(LifeKind.DELIVERY, status, null, null, ORDER_REF.find(text)?.groupValues?.get(1), done)
    }

    // ---- travel

    private val PNR = Regex("(?i)\\bPNR\\s*(?:no\\.?|number|:)?\\s*[:\\-]?\\s*(\\d{10})\\b")
    private val BOOKING_ID = Regex("(?i)\\b(?:booking|ticket|reservation)\\s*(?:id|no\\.?|number|ref(?:erence)?)\\s*[:\\-]?\\s*([A-Z0-9]{5,20})\\b")
    private val TRAVEL_CONFIRMED = Regex(
        "(?i)\\b(?:flight|train|bus|journey|ticket|hotel|stay|check-?in|boarding|trip|booking)\\b.*\\b(?:confirmed|booked|issued|scheduled|reminder)\\b|" +
            "\\b(?:confirmed|booked)\\b.*\\b(?:flight|train|bus|journey|ticket|hotel)\\b|\\bweb check-?in\\b"
    )
    private val JOURNEY_DATE = Regex("(?i)\\b(?:doj|date of journey|journey date|travel date|departure|departs|dep|check-?in|on)\\b[^0-9A-Za-z]{0,6}(.{0,24})")

    private fun travel(text: String, receivedOn: LocalDate): LifeFacts? {
        val reference = PNR.find(text)?.groupValues?.get(1) ?: BOOKING_ID.find(text)?.groupValues?.get(1)
        if (reference == null && !TRAVEL_CONFIRMED.containsMatchIn(text)) return null
        if (reference == null && !Regex("(?i)flight|train|bus|hotel|journey|boarding").containsMatchIn(text)) return null
        val date = JOURNEY_DATE.findAll(text).firstNotNullOfOrNull { dateIn(it.groupValues[1], receivedOn) }
            ?: dateIn(text, receivedOn)
        val status = when {
            Regex("(?i)\\bcancel").containsMatchIn(text) -> "Cancelled"
            Regex("(?i)web check-?in|check-?in").containsMatchIn(text) -> "Check-in"
            Regex("(?i)\\bboarding\\b").containsMatchIn(text) -> "Boarding"
            else -> "Booking confirmed"
        }
        return LifeFacts(LifeKind.TRAVEL, status, date, timeIn(text), reference, isDone = status == "Cancelled")
    }

    // ---- appointments

    private val APPOINTMENT_WORD = Regex("(?i)\\b(?:appointment|appt|slot|consultation|visit|session|test booking|sample collection|service (?:booking|due)|pickup scheduled|inspection)\\b")
    private val APPOINTMENT_VERB = Regex("(?i)\\b(?:scheduled|confirmed|booked|reminder|is on|fixed|rescheduled|remind)\\b")

    private fun appointment(text: String, receivedOn: LocalDate): LifeFacts? {
        if (!APPOINTMENT_WORD.containsMatchIn(text) || !APPOINTMENT_VERB.containsMatchIn(text)) return null
        val date = dateIn(text, receivedOn) ?: Regex("(?i)\\btoday\\b").takeIf { it.containsMatchIn(text) }?.let { receivedOn }
            ?: Regex("(?i)\\btomorrow\\b").takeIf { it.containsMatchIn(text) }?.let { receivedOn.plusDays(1) }
        val status = when {
            Regex("(?i)\\bresched").containsMatchIn(text) -> "Rescheduled"
            Regex("(?i)\\bcancel").containsMatchIn(text) -> "Cancelled"
            Regex("(?i)\\breminder|remind").containsMatchIn(text) -> "Reminder"
            else -> "Appointment confirmed"
        }
        return LifeFacts(LifeKind.APPOINTMENT, status, date, timeIn(text), null, isDone = status == "Cancelled")
    }

    // ---- renewals and expiries

    private val EXPIRY = Regex(
        "(?i)\\b(?:expires?|expiring|expiry|expired|valid (?:till|until|upto|up to)|validity (?:till|ends?|expires?)|renew(?:al)?(?: is)? due|" +
            "due for renewal|renew (?:by|before|your)|ends on|lapses?)\\b"
    )
    private val RENEWAL_SUBJECT = Regex("(?i)\\b(?:insurance|policy|puc|pollution|licen[cs]e|registration|rc\\b|warranty|subscription|membership|plan|pack|recharge|validity|domain|passport|visa|fastag|prime|amc)\\b")

    private fun renewal(text: String, receivedOn: LocalDate): LifeFacts? {
        if (!EXPIRY.containsMatchIn(text) || !RENEWAL_SUBJECT.containsMatchIn(text)) return null
        val after = EXPIRY.find(text)
        val date = after?.let { dateIn(text.substring(it.range.first), receivedOn) } ?: dateIn(text, receivedOn)
        val subject = RENEWAL_SUBJECT.find(text)!!.value.lowercase().replaceFirstChar { it.uppercase() }
        val expired = Regex("(?i)\\bexpired\\b|\\bhas lapsed\\b").containsMatchIn(text)
        return LifeFacts(LifeKind.RENEWAL, "$subject ${if (expired) "expired" else "expires"}", date, null, null, isDone = false)
    }

    // ---- dates and times

    private val MONTHS = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
    private val NUMERIC_DATE = Regex("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2,4})\\b")
    private val NAMED_DATE = Regex("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)?[ -]?($MONTHS)[a-z]*[ ,-]*(\\d{2,4})?\\b")
    private val NAMED_DATE_MONTH_FIRST = Regex("(?i)\\b($MONTHS)[a-z]* (\\d{1,2})(?:st|nd|rd|th)?(?:,? (\\d{4}))?\\b")

    /** The first date written in [text]; a missing year means the next time that day comes round. */
    internal fun dateIn(text: String, reference: LocalDate): LocalDate? {
        val candidates = buildList {
            NUMERIC_DATE.find(text)?.let { add(it.range.first to numeric(it)) }
            NAMED_DATE.find(text)?.let { add(it.range.first to named(it.groupValues[1], it.groupValues[2], it.groupValues[3], reference)) }
            NAMED_DATE_MONTH_FIRST.find(text)?.let { add(it.range.first to named(it.groupValues[2], it.groupValues[1], it.groupValues[3], reference)) }
        }
        return candidates.filter { it.second != null }.minByOrNull { it.first }?.second
    }

    /** Day first, as bank and booking messages write it: 12/11/2026. */
    private fun numeric(match: MatchResult): LocalDate? {
        val (d, m, y) = match.destructured
        val year = y.toInt().let { if (it < 100) 2000 + it else it }
        return runCatching { LocalDate.of(year, m.toInt(), d.toInt()) }.getOrNull()
    }

    private fun named(day: String, month: String, year: String, reference: LocalDate): LocalDate? {
        val monthNumber = MONTH_INDEX[month.lowercase().take(3)] ?: return null
        val d = day.toIntOrNull() ?: return null
        val explicit = year.takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
        if (explicit != null) return runCatching { LocalDate.of(explicit, monthNumber, d) }.getOrNull()
        val thisYear = runCatching { LocalDate.of(reference.year, monthNumber, d) }.getOrNull() ?: return null
        // A day with no year that has already passed by more than a month is next year's.
        return if (thisYear.isBefore(reference.minusMonths(1))) thisYear.plusYears(1) else thisYear
    }

    private val MONTH_INDEX = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        .withIndex().associate { it.value to it.index + 1 }

    private val CLOCK_12 = Regex("(?i)\\b(\\d{1,2})(?::(\\d{2}))?\\s?(am|pm)\\b")
    private val CLOCK_24 = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b")

    internal fun timeIn(text: String): LocalTime? {
        CLOCK_12.find(text)?.let {
            val hour = it.groupValues[1].toInt() % 12 + if (it.groupValues[3].lowercase() == "pm") 12 else 0
            return runCatching { LocalTime.of(hour, it.groupValues[2].ifEmpty { "0" }.toInt()) }.getOrNull()
        }
        CLOCK_24.find(text)?.let { return runCatching { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.getOrNull() }
        return null
    }
}
