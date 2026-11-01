package com.zyke.message.domain.markets

/** What a listed thing is, in words a person knows. */
enum class SuggestionType(val label: String) {
    STOCK("Stock"), ETF("ETF"), INDEX("Index"), CRYPTO("Crypto"), FX("FX"), COMMODITY("Commodity"), FUND("Fund"), OTHER("Other")
}

/** One name the price service offered for what the person typed. */
data class StockSuggestion(
    /** The ticker the price service knows it by, such as RELIANCE.NS, 7203.T, VOD.L or ^GSPC. */
    val symbol: String,
    /** The name to show and to keep with the ticker. */
    val name: String,
    /** Short name of the exchange (NSE, NASDAQ, LSE, TSE...), or blank. */
    val exchange: String,
    val type: SuggestionType
) {
    /** What the watch list stores for this suggestion. */
    fun toWatchSymbol(): WatchSymbol = WatchSymbol(symbol, MarketNames.short(name).ifBlank { MarketSymbols.defaultLabel(symbol) }, exchange)
}

/** Shortens company names for the strip ("Reliance Industries Limited" becomes "Reliance Industries") and tidies exchange names. */
object MarketNames {
    private val legal = setOf(
        "limited", "ltd", "inc", "incorporated", "corporation", "corp", "co", "company", "plc", "ag", "se", "nv", "sa", "llc", "lp", "spa", "oyj", "ab", "asa"
    )

    fun short(name: String): String {
        var words = name.trim().replace(Regex("\\s+"), " ").split(' ').filter { it.isNotEmpty() }
        // Yahoo's short names are often SHOUTED; make them readable.
        if (words.isNotEmpty() && words.none { w -> w.any { it.isLowerCase() } } && words.any { w -> w.length > 3 }) {
            words = words.map { w -> if (w.length <= 3 || !w.all { it.isLetter() }) w else w.lowercase().replaceFirstChar { it.uppercase() } }
        }
        while (words.size > 1 && words.last().trimEnd(',', '.').lowercase().replace(".", "") in legal) {
            words = words.dropLast(1).let { rest -> rest.dropLast(1) + rest.last().trimEnd(',', '.') }
        }
        return words.joinToString(" ").trimEnd(',', '.', ' ').take(MAX_LABEL)
    }

    /** A short exchange name for Yahoo's codes; unknown codes are shown as they came. */
    fun exchange(code: String?, display: String?): String {
        val c = code?.trim().orEmpty()
        EXCHANGES[c.uppercase()]?.let { return it }
        return (display?.trim().takeUnless { it.isNullOrEmpty() } ?: c).take(MAX_EXCHANGE)
    }

    private const val MAX_LABEL = 28
    private const val MAX_EXCHANGE = 14

    private val EXCHANGES = mapOf(
        "NSI" to "NSE", "BOM" to "BSE", "NMS" to "NASDAQ", "NGM" to "NASDAQ", "NCM" to "NASDAQ", "NAS" to "NASDAQ",
        "NYQ" to "NYSE", "NYS" to "NYSE", "ASE" to "NYSE American", "PCX" to "NYSE Arca", "BTS" to "Cboe", "LSE" to "LSE", "IOB" to "LSE",
        "JPX" to "TSE", "OSA" to "OSE", "HKG" to "HKEX", "SHH" to "SSE", "SHZ" to "SZSE", "TOR" to "TSX", "VAN" to "TSXV",
        "GER" to "XETRA", "FRA" to "Frankfurt", "PAR" to "Euronext Paris", "AMS" to "Euronext Amsterdam", "BRU" to "Euronext Brussels",
        "LIS" to "Euronext Lisbon", "MIL" to "Borsa Italiana", "MCE" to "BME", "EBS" to "SIX", "STO" to "Nasdaq Stockholm",
        "CPH" to "Nasdaq Copenhagen", "HEL" to "Nasdaq Helsinki", "OSL" to "Oslo", "ASX" to "ASX", "NZE" to "NZX", "KSC" to "KRX",
        "KOE" to "KOSDAQ", "TAI" to "TWSE", "TWO" to "TPEx", "SES" to "SGX", "KLS" to "Bursa Malaysia", "JKT" to "IDX", "SET" to "SET",
        "SAO" to "B3", "MEX" to "BMV", "BUE" to "BCBA", "JNB" to "JSE", "TLV" to "TASE", "CCC" to "Crypto", "CCY" to "FX",
        "CME" to "CME", "CMX" to "COMEX", "NYM" to "NYMEX", "CBT" to "CBOT", "SNP" to "S&P", "DJI" to "Dow Jones", "WCB" to "Cboe"
    )
}

/**
 * Reads Yahoo Finance's search reply ("quotes": [...]). The service is unofficial and may change, so only the few fields
 * wanted are looked at, anything unexpected is skipped, and garbage gives an empty list rather than an error.
 */
object YahooSearchParser {
    private const val MAX_RESULTS = 10
    private const val MAX_SYMBOL = 30

    fun parse(json: String): List<StockSuggestion> {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return emptyList()
        val quotes = root["quotes"] as? List<*> ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<StockSuggestion>()
        for (item in quotes) {
            val q = item as? Map<*, *> ?: continue
            val symbol = (q["symbol"] as? String)?.trim()?.uppercase().orEmpty()
            if (symbol.isEmpty() || symbol.length > MAX_SYMBOL || !MarketSymbols.isValidSymbol(symbol) || !seen.add(symbol)) continue
            val shortName = (q["shortname"] as? String)?.trim().orEmpty()
            val longName = (q["longname"] as? String)?.trim().orEmpty()
            val name = when {
                shortName.isEmpty() -> longName
                longName.isNotEmpty() && shortName.none { it.isLowerCase() } -> longName
                else -> shortName
            }.ifEmpty { symbol }
            out += StockSuggestion(
                symbol = symbol,
                name = name,
                exchange = MarketNames.exchange(q["exchange"] as? String, q["exchDisp"] as? String),
                type = typeOf(q["quoteType"] as? String)
            )
            if (out.size >= MAX_RESULTS) break
        }
        return out
    }

    private fun typeOf(quoteType: String?): SuggestionType = when (quoteType?.trim()?.uppercase()) {
        "EQUITY" -> SuggestionType.STOCK
        "ETF" -> SuggestionType.ETF
        "INDEX" -> SuggestionType.INDEX
        "CURRENCY" -> SuggestionType.FX
        "CRYPTOCURRENCY" -> SuggestionType.CRYPTO
        "FUTURE" -> SuggestionType.COMMODITY
        "MUTUALFUND" -> SuggestionType.FUND
        else -> SuggestionType.OTHER
    }
}

/** Puts the offline list and the service's answer together for the dropdown. */
object SuggestionMerge {
    fun fromLocal(entry: StockEntry): StockSuggestion {
        val symbol = entry.symbol
        val exchange = when {
            symbol.endsWith(".NS") -> "NSE"
            symbol.endsWith(".BO") -> "BSE"
            else -> ""
        }
        return StockSuggestion(symbol, entry.name, exchange, if (symbol.startsWith("^")) SuggestionType.INDEX else SuggestionType.STOCK)
    }

    /** Service results first, then the local ones, each ticker once, nothing already watched. */
    fun merge(service: List<StockSuggestion>, local: List<StockSuggestion>, watched: Set<String>, limit: Int = 10): List<StockSuggestion> {
        val seen = HashSet<String>(watched)
        return (service + local).filter { seen.add(it.symbol) }.take(limit)
    }
}

/**
 * A small, forgiving JSON reader: objects become maps, arrays lists, numbers doubles, plus strings, booleans and null.
 * It exists because the domain has no JSON library; any malformed input gives null.
 */
internal object MiniJson {
    private const val MAX_DEPTH = 24
    private const val MAX_LENGTH = 512 * 1024

    fun parse(text: String): Any? {
        if (text.length > MAX_LENGTH) return null
        return runCatching {
            val reader = Reader(text)
            reader.skip()
            val value = reader.value(0)
            reader.skip()
            if (reader.atEnd()) value else null
        }.getOrNull()
    }

    private class Reader(val s: String) {
        var i = 0
        fun atEnd() = i >= s.length
        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun peek(): Char = if (i < s.length) s[i] else throw IllegalStateException("end")

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw IllegalStateException("deep")
            skip()
            return when (val c = peek()) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalStateException("bad")
            }
        }

        fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalStateException("bad")
            i += word.length
            return v
        }

        fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }

        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                val c = peek()
                i++
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = peek()
                        i++
                        when (e) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skip()
            if (peek() == ']') { i++; return out }
            while (true) {
                out += value(depth + 1)
                skip()
                when (peek()) { ',' -> i++; ']' -> { i++; return out }; else -> throw IllegalStateException("bad") }
            }
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skip()
            if (peek() == '}') { i++; return out }
            while (true) {
                skip()
                if (peek() != '"') throw IllegalStateException("bad")
                val key = str()
                skip()
                if (peek() != ':') throw IllegalStateException("bad")
                i++
                out[key] = value(depth + 1)
                skip()
                when (peek()) { ',' -> i++; '}' -> { i++; return out }; else -> throw IllegalStateException("bad") }
            }
        }
    }
}
