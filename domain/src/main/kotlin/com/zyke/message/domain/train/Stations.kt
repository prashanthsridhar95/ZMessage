package com.zyke.message.domain.train

import com.zyke.message.domain.flight.Place
import java.time.ZoneId

/**
 * Indian Railways station codes and the names people say, for the stations trains stop at most (a short table, not the
 * whole network: a code that is not here is still shown as the code, and a name that is not here as typed). Every Indian train
 * runs on one clock, [ZONE].
 */
object Stations {
    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    private val table: Map<String, String> = """
        NDLS|New Delhi;NZM|Hazrat Nizamuddin;DLI|Old Delhi;ANVT|Anand Vihar Terminal;DEE|Delhi Sarai Rohilla;CSMT|Mumbai CSMT;BCT|Mumbai Central;
        LTT|Lokmanya Tilak Terminus;BDTS|Bandra Terminus;DR|Dadar;KYN|Kalyan;PUNE|Pune;HWH|Howrah;SDAH|Sealdah;KOAA|Kolkata;
        MAS|Chennai Central;MS|Chennai Egmore;TBM|Tambaram;CGL|Chengalpattu;MMC|Mambalam;SBC|Bengaluru City;YPR|Yesvantpur;KJM|Krishnarajapuram;
        MYS|Mysuru;CBE|Coimbatore;TUP|Tiruppur;ED|Erode;SA|Salem;KPD|Katpadi;AJJ|Arakkonam;TMV|Tindivanam;VM|Villupuram;VRI|Vriddhachalam;
        ALU|Ariyalur;TPJ|Tiruchirappalli;TJ|Thanjavur;MV|Mayiladuthurai;KUV|Kumbakonam;DG|Dindigul;MDU|Madurai;VPT|Virudhunagar;CLN|Kovilpatti;
        TEN|Tirunelveli;NCJ|Nagercoil;CAPE|Kanniyakumari;TVC|Thiruvananthapuram;QLN|Kollam;KTYM|Kottayam;ERS|Ernakulam;TCR|Thrissur;
        SRR|Shoranur;PGT|Palakkad;CLT|Kozhikode;CAN|Kannur;MAQ|Mangaluru Central;MAO|Madgaon;UD|Udupi;HYB|Hyderabad;SC|Secunderabad;
        KCG|Kacheguda;BZA|Vijayawada;GNT|Guntur;NLR|Nellore;TPTY|Tirupati;RJY|Rajahmundry;VSKP|Visakhapatnam;BBS|Bhubaneswar;PURI|Puri;
        CTC|Cuttack;PNBE|Patna;DNR|Danapur;GAYA|Gaya;LKO|Lucknow;CNB|Kanpur Central;ALD|Prayagraj;BSB|Varanasi;GKP|Gorakhpur;
        JP|Jaipur;JU|Jodhpur;UDZ|Udaipur;AII|Ajmer;KOTA|Kota;ADI|Ahmedabad;BRC|Vadodara;ST|Surat;RJT|Rajkot;BPL|Bhopal;INDB|Indore;
        NGP|Nagpur;JBP|Jabalpur;R|Raipur;BSP|Bilaspur;ASR|Amritsar;CDG|Chandigarh;UMB|Ambala Cantt;LDH|Ludhiana;JAT|Jammu Tawi;
        HW|Haridwar;DDN|Dehradun;GHY|Guwahati;RNC|Ranchi;TATA|Tatanagar;DHN|Dhanbad;AGC|Agra Cantt;GWL|Gwalior;JHS|Jhansi;MTJ|Mathura;
        BE|Bareilly;MB|Moradabad;KGP|Kharagpur;PDY|Puducherry;NJP|New Jalpaiguri;SUR|Solapur;WL|Warangal;KZJ|Kazipet;RU|Renigunta;GTL|Guntakal;
        BWT|Bangarapet;JTJ|Jolarpettai;RMM|Rameswaram;KKDI|Karaikudi;PDKT|Pudukkottai
    """.trimIndent().replace("\n", "").split(';').mapNotNull { it.trim().takeIf { e -> '|' in e }?.split('|') }.associate { it[0] to it[1] }

    /** Names people also say or that SMS print for a station, mapped to its code. */
    private val aliases = mapOf(
        "chennai" to "MAS", "chennai central" to "MAS", "chennai egmore" to "MS", "egmore" to "MS", "madras" to "MAS",
        "bangalore" to "SBC", "bengaluru" to "SBC", "ksr bengaluru" to "SBC", "bangalore city" to "SBC", "delhi" to "NDLS",
        "new delhi" to "NDLS", "mumbai" to "CSMT", "mumbai cst" to "CSMT", "trichy" to "TPJ", "tiruchirapalli" to "TPJ",
        "tiruchchirappalli" to "TPJ", "tiruchirappalli jn" to "TPJ", "trivandrum" to "TVC", "kochi" to "ERS", "cochin" to "ERS",
        "ernakulam jn" to "ERS", "calicut" to "CLT", "mangalore" to "MAQ", "mysore" to "MYS", "vizag" to "VSKP", "kolkata" to "KOAA",
        "calcutta" to "KOAA", "kanyakumari" to "CAPE", "cape comorin" to "CAPE", "pondicherry" to "PDY", "prayagraj" to "ALD",
        "allahabad" to "ALD", "varanasi" to "BSB", "banaras" to "BSB", "secunderabad" to "SC", "hyderabad" to "HYB", "nizamuddin" to "NZM",
        "hazrat nizamuddin" to "NZM", "tirupati" to "TPTY", "villupuram" to "VM", "viluppuram" to "VM"
    )

    private val byLowerName: Map<String, String> = table.entries.associate { it.value.lowercase() to it.key } + aliases

    fun isKnown(code: String?): Boolean = code != null && code.uppercase() in table

    fun name(code: String?): String? = code?.let { table[it.uppercase()] }

    /** The code of a station named in words ("Chennai Egmore", "Trichy Jn"), or null when it is not a name in the table. */
    fun codeForName(name: String): String? {
        val n = name.lowercase().replace(Regex("\\b(?:junction|jn\\.?|cantt\\.?|central|terminus|railway station|rly stn|stn)\\b\\.?"), " ").replace(Regex("\\s+"), " ").trim()
        val full = name.lowercase().replace(Regex("\\s+"), " ").trim()
        return byLowerName[full] ?: byLowerName[n] ?: byLowerName[n.removeSuffix(" jn")]
    }

    /** Every name worth searching for in a text, longest first so "Chennai Egmore" wins over "Chennai". */
    val names: List<String> = byLowerName.keys.sortedByDescending { it.length }

    /** The station as the card draws it: code (when known) and the name people say. */
    fun place(code: String): Place = Place(code.uppercase(), name(code) ?: code.uppercase())

    /** A station named in words. */
    fun placeForName(name: String): Place {
        val code = codeForName(name)
        return if (code != null) Place(code, Stations.name(code) ?: name.trim()) else Place(null, name.trim().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
    }
}
