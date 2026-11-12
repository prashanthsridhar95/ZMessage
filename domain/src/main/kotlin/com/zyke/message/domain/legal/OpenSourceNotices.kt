package com.zyke.message.domain.legal

/** What a notice is about, so the page can group it. */
enum class NoticeKind(val label: String) {
    LIBRARY("Libraries in the app"),
    OPTIONAL("Only in the optional watch build"),
    BUILD("Used to build and test, not in the app"),
    ASSET("Fonts and icons"),
    SERVICE("Services the app can ask")
}

/**
 * One third-party item: what it is, its licence and where to find the project. [coordinates] are the `group:artifact` names
 * from gradle/libs.versions.toml (or the build files) that it covers; a test checks that every library there is covered.
 */
data class Notice(
    val name: String,
    val what: String,
    val licence: String,
    val url: String,
    val kind: NoticeKind,
    val coordinates: List<String> = emptyList()
)

/**
 * The third-party software, font, icons and services behind the app, for the Open-source licences page and the NOTICE file.
 * Licence names are given to the best of our knowledge and were not checked by a lawyer; the projects' own licence files win.
 */
object OpenSourceNotices {
    const val APACHE = "Apache License 2.0"
    const val OFL = "SIL Open Font License 1.1"

    val all: List<Notice> = listOf(
        Notice("AndroidX AppCompat, Core KTX, Activity, Lifecycle", "Android support libraries", APACHE, "https://developer.android.com/jetpack/androidx", NoticeKind.LIBRARY, listOf(
            "androidx.appcompat:appcompat", "androidx.core:core-ktx", "androidx.activity:activity-compose",
            "androidx.lifecycle:lifecycle-runtime-ktx", "androidx.lifecycle:lifecycle-runtime-compose", "androidx.lifecycle:lifecycle-viewmodel-ktx"
        )),
        Notice("Jetpack Compose (UI, Graphics, Tooling preview, BOM)", "The user-interface toolkit", APACHE, "https://developer.android.com/jetpack/compose", NoticeKind.LIBRARY, listOf(
            "androidx.compose:compose-bom", "androidx.compose.ui:ui", "androidx.compose.ui:ui-graphics",
            "androidx.compose.ui:ui-tooling", "androidx.compose.ui:ui-tooling-preview"
        )),
        Notice("Material 3 for Compose", "Buttons, sheets, switches and other components", APACHE, "https://developer.android.com/jetpack/androidx/releases/compose-material3", NoticeKind.LIBRARY, listOf("androidx.compose.material3:material3")),
        Notice("Kotlin and Kotlin standard library", "The programming language and its runtime", APACHE, "https://kotlinlang.org", NoticeKind.LIBRARY),
        Notice("kotlinx.coroutines", "Background work", APACHE, "https://github.com/Kotlin/kotlinx.coroutines", NoticeKind.LIBRARY, listOf(
            "org.jetbrains.kotlinx:kotlinx-coroutines-core", "org.jetbrains.kotlinx:kotlinx-coroutines-android"
        )),
        Notice("Google Play services Auth", "Google sign-in for the optional Drive backup", "Google Play services terms (not open source; see the Android SDK licence)", "https://developer.android.com/studio/terms", NoticeKind.LIBRARY, listOf("com.google.android.gms:play-services-auth")),
        Notice("Google Play services Wearable", "Watch link (only when built with -PwithWear=true)", "Google Play services terms (not open source; see the Android SDK licence)", "https://developer.android.com/studio/terms", NoticeKind.OPTIONAL, listOf("com.google.android.gms:play-services-wearable")),
        Notice("AndroidX Wear Compose, Tiles, ProtoLayout, Watch Face, Concurrent Futures", "The watch app (separate module)", APACHE, "https://developer.android.com/jetpack/androidx", NoticeKind.OPTIONAL, listOf(
            "androidx.wear.compose:compose-material", "androidx.wear.compose:compose-foundation", "androidx.wear.tiles:tiles",
            "androidx.wear.protolayout:protolayout", "androidx.concurrent:concurrent-futures",
            "androidx.wear.watchface:watchface-complications-data-source-ktx"
        )),
        Notice("JUnit 4", "Unit tests", "Eclipse Public License 1.0", "https://junit.org/junit4/", NoticeKind.BUILD, listOf("junit:junit")),
        Notice("AndroidX Test (JUnit extensions, Espresso), Compose UI test", "Instrumented tests", APACHE, "https://developer.android.com/jetpack/androidx/releases/test", NoticeKind.BUILD, listOf(
            "androidx.test.ext:junit", "androidx.test.espresso:espresso-core", "androidx.compose.ui:ui-test-manifest", "androidx.compose.ui:ui-test-junit4"
        )),
        Notice("kotlinx-coroutines-test", "Tests of background work", APACHE, "https://github.com/Kotlin/kotlinx.coroutines", NoticeKind.BUILD, listOf("org.jetbrains.kotlinx:kotlinx-coroutines-test")),
        Notice("Android Gradle Plugin and detekt", "Build and code checks", APACHE, "https://detekt.dev", NoticeKind.BUILD),
        Notice("Plus Jakarta Sans", "The app's typeface, by the Plus Jakarta Sans Project Authors; the licence text is in third_party/fonts", OFL, "https://github.com/tokotype/PlusJakartaSans", NoticeKind.ASSET),
        Notice("Material Icons", "Many of the app's small icons (Google)", APACHE, "https://fonts.google.com/icons", NoticeKind.ASSET),
        Notice("Card network and bank marks", "Visa, Mastercard, American Express, RuPay and Diners Club marks on a card's picture are simple drawings, and bank logos are the banks' own; all are trademarks of their owners, shown only to tell which card is which, with no affiliation or endorsement.", "Owned by each company", "https://www.wipo.int/trademarks/en/", NoticeKind.ASSET),
        Notice("Brand logos", "Bank, airline and other brand logos are fetched from public icon services only to identify a sender. They belong to their owners and are not part of this software.", "Owned by each brand", "https://www.google.com/s2/favicons", NoticeKind.SERVICE),
        Notice("Google Favicon service, DuckDuckGo icons", "Where logos are fetched from", "Their own terms of service", "https://duckduckgo.com/duckduckgo-help-pages/privacy/", NoticeKind.SERVICE),
        Notice("Yahoo Finance", "Market prices and stock-name suggestions (opt-in)", "Yahoo's own terms of service", "https://legal.yahoo.com/", NoticeKind.SERVICE),
        Notice("Flightradar24", "Live flight status (opt-in)", "Flightradar24's own terms of service", "https://www.flightradar24.com/terms-and-conditions", NoticeKind.SERVICE),
        Notice("Live Train Status, Indian Rail API", "Live train running status (opt-in, needs your own key; no agreement between this project and them)", "Its own terms of service and pricing", "https://indianrailapi.com/", NoticeKind.SERVICE),
        Notice("Google Drive API", "Optional backup to your own Drive", "Google API terms of service", "https://developers.google.com/terms", NoticeKind.SERVICE)
    )

    val coveredCoordinates: Set<String> get() = all.flatMap { it.coordinates }.toSet()

    /** The text of the NOTICE file at the repository root, built from the same list so the two cannot disagree. */
    fun noticeFileText(): String = buildString {
        append("Zyke Message\n\n")
        append("This file lists the third-party software, fonts, icons and services the app uses, with each licence's name and\n")
        append("the project's address. The projects' own licence texts apply. It is not legal advice.\n")
        append("The licence of Zyke Message's own code has not been chosen by this file.\n")
        NoticeKind.entries.forEach { kind ->
            val group = all.filter { it.kind == kind }
            if (group.isEmpty()) return@forEach
            append("\n== ").append(kind.label).append(" ==\n")
            group.forEach { append("\n").append(it.name).append("\n  ").append(it.what).append("\n  Licence: ").append(it.licence).append("\n  ").append(it.url).append("\n") }
        }
    }
}
