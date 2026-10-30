package com.zyke.message.domain.markets

/** A listed name the person can add by typing part of it. [symbol] is the ticker the price service knows it by. */
data class StockEntry(val symbol: String, val name: String) {
    /** What it is called in the watch list. */
    val label: String get() = name
}

/**
 * The names people most often want to watch, so typing "reli" or "tata" offers them to choose from, by company name
 * or by ticker. Anything not here can still be added by typing its ticker.
 */
object StockDirectory {
    private val ENTRIES: List<StockEntry> = listOf(
        StockEntry("^NSEI", "Nifty 50"), StockEntry("^BSESN", "Sensex"), StockEntry("^NSEBANK", "Nifty Bank"), StockEntry("^CNXIT", "Nifty IT"),
        StockEntry("RELIANCE.NS", "Reliance Industries"), StockEntry("TCS.NS", "Tata Consultancy Services"), StockEntry("HDFCBANK.NS", "HDFC Bank"),
        StockEntry("ICICIBANK.NS", "ICICI Bank"), StockEntry("INFY.NS", "Infosys"), StockEntry("SBIN.NS", "State Bank of India"),
        StockEntry("BHARTIARTL.NS", "Bharti Airtel"), StockEntry("ITC.NS", "ITC"), StockEntry("LT.NS", "Larsen & Toubro"),
        StockEntry("KOTAKBANK.NS", "Kotak Mahindra Bank"), StockEntry("AXISBANK.NS", "Axis Bank"), StockEntry("HINDUNILVR.NS", "Hindustan Unilever"),
        StockEntry("BAJFINANCE.NS", "Bajaj Finance"), StockEntry("BAJAJFINSV.NS", "Bajaj Finserv"), StockEntry("MARUTI.NS", "Maruti Suzuki"),
        StockEntry("SUNPHARMA.NS", "Sun Pharmaceutical"), StockEntry("TITAN.NS", "Titan Company"), StockEntry("ASIANPAINT.NS", "Asian Paints"),
        StockEntry("ULTRACEMCO.NS", "UltraTech Cement"), StockEntry("NESTLEIND.NS", "Nestle India"), StockEntry("WIPRO.NS", "Wipro"),
        StockEntry("HCLTECH.NS", "HCL Technologies"), StockEntry("TECHM.NS", "Tech Mahindra"), StockEntry("POWERGRID.NS", "Power Grid Corporation"),
        StockEntry("NTPC.NS", "NTPC"), StockEntry("ONGC.NS", "Oil and Natural Gas Corporation"), StockEntry("COALINDIA.NS", "Coal India"),
        StockEntry("TATAMOTORS.NS", "Tata Motors"), StockEntry("TATASTEEL.NS", "Tata Steel"), StockEntry("TATAPOWER.NS", "Tata Power"),
        StockEntry("TATACONSUM.NS", "Tata Consumer Products"), StockEntry("JSWSTEEL.NS", "JSW Steel"), StockEntry("HINDALCO.NS", "Hindalco Industries"),
        StockEntry("ADANIENT.NS", "Adani Enterprises"), StockEntry("ADANIPORTS.NS", "Adani Ports"), StockEntry("ADANIGREEN.NS", "Adani Green Energy"),
        StockEntry("ADANIPOWER.NS", "Adani Power"), StockEntry("M&M.NS", "Mahindra & Mahindra"), StockEntry("EICHERMOT.NS", "Eicher Motors"),
        StockEntry("BAJAJ-AUTO.NS", "Bajaj Auto"), StockEntry("HEROMOTOCO.NS", "Hero MotoCorp"), StockEntry("TVSMOTOR.NS", "TVS Motor Company"),
        StockEntry("DRREDDY.NS", "Dr Reddy's Laboratories"), StockEntry("CIPLA.NS", "Cipla"), StockEntry("DIVISLAB.NS", "Divi's Laboratories"),
        StockEntry("APOLLOHOSP.NS", "Apollo Hospitals"), StockEntry("LUPIN.NS", "Lupin"), StockEntry("INDUSINDBK.NS", "IndusInd Bank"),
        StockEntry("SBILIFE.NS", "SBI Life Insurance"), StockEntry("HDFCLIFE.NS", "HDFC Life Insurance"), StockEntry("ICICIPRULI.NS", "ICICI Prudential Life"),
        StockEntry("ICICIGI.NS", "ICICI Lombard"), StockEntry("BAJAJHLDNG.NS", "Bajaj Holdings"), StockEntry("GRASIM.NS", "Grasim Industries"),
        StockEntry("BRITANNIA.NS", "Britannia Industries"), StockEntry("DABUR.NS", "Dabur India"), StockEntry("GODREJCP.NS", "Godrej Consumer Products"),
        StockEntry("COLPAL.NS", "Colgate-Palmolive India"), StockEntry("MARICO.NS", "Marico"), StockEntry("PIDILITIND.NS", "Pidilite Industries"),
        StockEntry("BERGEPAINT.NS", "Berger Paints"), StockEntry("HAVELLS.NS", "Havells India"), StockEntry("SIEMENS.NS", "Siemens"),
        StockEntry("ABB.NS", "ABB India"), StockEntry("BHEL.NS", "Bharat Heavy Electricals"), StockEntry("HAL.NS", "Hindustan Aeronautics"),
        StockEntry("BEL.NS", "Bharat Electronics"), StockEntry("IRCTC.NS", "IRCTC"), StockEntry("INDIGO.NS", "InterGlobe Aviation (IndiGo)"),
        StockEntry("ZOMATO.NS", "Zomato"), StockEntry("PAYTM.NS", "Paytm (One 97 Communications)"), StockEntry("NYKAA.NS", "Nykaa (FSN E-Commerce)"),
        StockEntry("POLICYBZR.NS", "PB Fintech (Policybazaar)"), StockEntry("DMART.NS", "Avenue Supermarts (DMart)"), StockEntry("TRENT.NS", "Trent"),
        StockEntry("JIOFIN.NS", "Jio Financial Services"), StockEntry("LICI.NS", "Life Insurance Corporation of India"), StockEntry("VEDL.NS", "Vedanta"),
        StockEntry("BPCL.NS", "Bharat Petroleum"), StockEntry("IOC.NS", "Indian Oil Corporation"), StockEntry("GAIL.NS", "GAIL (India)"),
        StockEntry("HINDPETRO.NS", "Hindustan Petroleum"), StockEntry("PNB.NS", "Punjab National Bank"), StockEntry("BANKBARODA.NS", "Bank of Baroda"),
        StockEntry("CANBK.NS", "Canara Bank"), StockEntry("UNIONBANK.NS", "Union Bank of India"), StockEntry("IDFCFIRSTB.NS", "IDFC First Bank"),
        StockEntry("FEDERALBNK.NS", "Federal Bank"), StockEntry("YESBANK.NS", "Yes Bank"), StockEntry("RBLBANK.NS", "RBL Bank"),
        StockEntry("AUBANK.NS", "AU Small Finance Bank"), StockEntry("BANDHANBNK.NS", "Bandhan Bank"), StockEntry("PFC.NS", "Power Finance Corporation"),
        StockEntry("RECLTD.NS", "REC"), StockEntry("MUTHOOTFIN.NS", "Muthoot Finance"), StockEntry("CHOLAFIN.NS", "Cholamandalam Investment"),
        StockEntry("SHRIRAMFIN.NS", "Shriram Finance"), StockEntry("SBICARD.NS", "SBI Cards and Payment Services"), StockEntry("LTIM.NS", "LTIMindtree"),
        StockEntry("PERSISTENT.NS", "Persistent Systems"), StockEntry("COFORGE.NS", "Coforge"), StockEntry("MPHASIS.NS", "Mphasis"),
        StockEntry("DLF.NS", "DLF"), StockEntry("GODREJPROP.NS", "Godrej Properties"), StockEntry("LODHA.NS", "Macrotech Developers (Lodha)"),
        StockEntry("AMBUJACEM.NS", "Ambuja Cements"), StockEntry("SHREECEM.NS", "Shree Cement"), StockEntry("ACC.NS", "ACC"),
        StockEntry("SRF.NS", "SRF"), StockEntry("UPL.NS", "UPL"), StockEntry("PIIND.NS", "PI Industries"), StockEntry("TORNTPHARM.NS", "Torrent Pharmaceuticals"),
        StockEntry("ZYDUSLIFE.NS", "Zydus Lifesciences"), StockEntry("MANKIND.NS", "Mankind Pharma"), StockEntry("MAXHEALTH.NS", "Max Healthcare"),
        StockEntry("VOLTAS.NS", "Voltas"), StockEntry("PAGEIND.NS", "Page Industries"), StockEntry("JUBLFOOD.NS", "Jubilant FoodWorks"),
        StockEntry("BOSCHLTD.NS", "Bosch"), StockEntry("MRF.NS", "MRF"), StockEntry("ASHOKLEY.NS", "Ashok Leyland"), StockEntry("MOTHERSON.NS", "Samvardhana Motherson"),
        StockEntry("IDEA.NS", "Vodafone Idea"), StockEntry("SUZLON.NS", "Suzlon Energy"), StockEntry("IREDA.NS", "Indian Renewable Energy Development Agency"),
        StockEntry("RVNL.NS", "Rail Vikas Nigam"), StockEntry("IRFC.NS", "Indian Railway Finance Corporation"), StockEntry("NHPC.NS", "NHPC"),
        StockEntry("SJVN.NS", "SJVN"), StockEntry("NMDC.NS", "NMDC"), StockEntry("SAIL.NS", "Steel Authority of India"), StockEntry("JINDALSTEL.NS", "Jindal Steel & Power")
    )

    /** The first few, for when nothing has been typed. */
    val popular: List<StockEntry> get() = ENTRIES.take(POPULAR)

    /**
     * Names that match what was typed, best first: a ticker or a word of the name that starts with it, then those that
     * only contain it. Spaces and punctuation are ignored, so "hdfc bank" and "HDFCBANK" both find HDFC Bank.
     */
    fun search(query: String, limit: Int = 6): List<StockEntry> {
        val q = squash(query)
        if (q.isEmpty()) return popular.take(limit)
        return ENTRIES.mapNotNull { entry ->
            val symbol = squash(entry.symbol.substringBefore('.'))
            val name = squash(entry.name)
            val words = entry.name.lowercase().split(' ', '-', '&', '(', ')').map { squash(it) }.filter { it.isNotEmpty() }
            val rank = when {
                symbol == q -> 0
                symbol.startsWith(q) -> 1
                name.startsWith(q) -> 2
                words.any { it.startsWith(q) } -> 3
                name.contains(q) || symbol.contains(q) -> 4
                else -> return@mapNotNull null
            }
            rank to entry
        }.sortedBy { it.first }.map { it.second }.take(limit)
    }

    private fun squash(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private const val POPULAR = 8
}
