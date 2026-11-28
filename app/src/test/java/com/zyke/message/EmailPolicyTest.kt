package com.zyke.message

import com.zyke.message.domain.email.EmailAlert
import com.zyke.message.domain.email.EmailAlertSenders
import com.zyke.message.domain.email.EmailAlertSettings
import com.zyke.message.domain.email.EmailProvider
import com.zyke.message.domain.legal.LegalInfo
import com.zyke.message.domain.legal.LegalText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading bank alert emails (opt-in) is the one feature that signs in to something of the person's: so its rules are pinned here. Off until
 * switched on; never in Demo mode; one network class, to four fixed hosts, over TLS only; only read-only IMAP commands; only listed senders;
 * nothing about a mailbox in a log; the password sealed, left out of backups and removed by Disconnect and by Delete all my data; and the
 * words (the page, the docs) saying the same. Source checks, not a run: nothing here talks to a server.
 */
class EmailPolicyTest {
    private fun appRoot(): File = listOf(".", "app").map { File(it) }.first { File(it, "src/main/java").isDirectory }
    private fun repoRoot(): File = appRoot().canonicalFile.let { if (it.name == "app") it.parentFile else it }
    private fun main(path: String) = File(appRoot(), "src/main/java/com/zyke/message/$path").readText()
    private fun domain(path: String) = File(repoRoot(), "domain/src/main/kotlin/com/zyke/message/domain/$path").readText()
    private fun doc(path: String) = File(repoRoot(), path).readText()
    private fun appFiles() = File(appRoot(), "src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun emailDomainFiles() = File(repoRoot(), "domain/src/main/kotlin/com/zyke/message/domain/email").listFiles { f -> f.extension == "kt" }!!.toList()

    /** Source without comments, so a sentence about a command in a comment is not mistaken for the command. */
    private fun code(text: String): String = text.replace(Regex("(?s)/\\*.*?\\*/"), "").lines()
        .map { it.replace(Regex("(?<=\\s)//.*$"), "") }.filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }.joinToString("\n")

    @Test
    fun offUntilSwitchedOn_andNothingRunsWithoutAConnection() {
        val settings = EmailAlertSettings()
        assertFalse(settings.enabled)
        assertFalse(settings.connected)
        assertFalse(settings.explained)
        // The switch asks for the one-time explanation first, and a pass needs both the switch and a connection.
        val ui = main("presentation/email/EmailSettings.kt")
        assertTrue("else if (!s.explained) explaining = true" in ui)
        assertTrue("if (!settings.enabled || !settings.connected) return null" in domain("email/EmailSync.kt"))
        assertTrue("if (!s.enabled || !s.connected || s.syncEvery == EmailSyncEvery.MANUAL) return null" in domain("email/EmailSync.kt"))
        // The app only looks when it is opened, once its gap has passed: no timer, no service, no alarm, no receiver.
        val onStart = main("presentation/MainActivity.kt").substringAfter("override fun onStart()")
        assertTrue("container.emailSync.syncIfDue()" in onStart && "DemoMode.active" in onStart.substringBefore("container.emailSync.syncIfDue()").takeLast(400))
        for (file in File(appRoot(), "src/main/java/com/zyke/message/data/email").listFiles()!!) {
            val text = code(file.readText())
            assertFalse(file.name, Regex("AlarmManager|JobScheduler|WorkManager|startService|startForegroundService|BroadcastReceiver|Handler\\(|Timer\\(").containsMatchIn(text))
        }
        assertFalse(File(appRoot(), "src/main/AndroidManifest.xml").readText().contains("email", ignoreCase = true))
    }

    @Test
    fun theSmsReceiverAndTheBackgroundCodeNeverTouchEmail() {
        val mail = Regex("(?i)emailSync|emailAlerts|emailCredentials|data\\.email|domain\\.email|ImapClient")
        for (name in listOf("SmsReceiver.kt", "LiveAlerts.kt", "DigestReceiver.kt", "BootReceiver.kt", "BalanceRefresher.kt", "CodeSharer.kt", "HeadsUpNotifier.kt")) {
            assertFalse("$name must not touch email", mail.containsMatchIn(main("notifications/$name")))
        }
        assertFalse(main("data/sms/RcsNotificationListener.kt").let { mail.containsMatchIn(it) })
    }

    @Test
    fun demoModeNeverConnects() {
        val container = main("di/AppContainer.kt")
        assertTrue("if (demo) com.zyke.message.domain.email.NoEmailAlerts" in container)
        assertTrue("if (demo) com.zyke.message.domain.email.NoEmailCredentials" in container)
        assertTrue("if (demo) com.zyke.message.domain.email.NoMailSource else com.zyke.message.data.email.ImapClient()" in container)
        assertTrue("val demo = com.zyke.message.data.demo.DemoMode.active" in main("presentation/email/EmailSettings.kt") && "if (demo) {" in main("presentation/email/EmailSettings.kt"))
        assertTrue("if (message.isEmail || com.zyke.message.data.demo.DemoMode.active) return" in main("presentation/email/EmailNoticeCard.kt"))
        val none = domain("email/MailTypes.kt").substringAfter("object NoMailSource")
        assertTrue("MailResult.Failed" in none && "MailResult.Ok" !in none)
        assertTrue(com.zyke.message.domain.email.NoEmailAlerts.state.value.settings == EmailAlertSettings())
    }

    @Test
    fun oneNetworkClass_oneConstructionSite_noOtherSslSocketForMail() {
        val tls = Regex("SSLSocket|SSLSocketFactory|SSLContext|HttpsURLConnection|javax\\.net")
        val users = appFiles().filter { tls.containsMatchIn(code(it.readText())) }.map { it.name }
        // Mail's own: ImapClient. The others are the existing HTTPS fetchers' own exception handling (SSLException) and similar, never a socket.
        assertTrue(users.toString(), "ImapClient.kt" in users)
        assertEquals(listOf("ImapClient.kt"), appFiles().filter { Regex("SSLSocket|SSLSocketFactory|SSLContext").containsMatchIn(code(it.readText())) }.map { it.name })
        assertEquals(1, appFiles().count { "ImapClient()" in code(it.readText()) })
        assertTrue("ImapClient()" in main("di/AppContainer.kt"))
        // Nothing in :domain opens a connection either: the protocol code only reads and writes the streams it is given.
        for (file in emailDomainFiles()) {
            val text = code(file.readText())
            assertFalse(file.name, Regex("java\\.net\\.|javax\\.net|Socket|URL\\(|openConnection|HttpURLConnection").containsMatchIn(text))
        }
        assertTrue("internal class ImapClient : MailSource" in main("data/email/ImapClient.kt"))
    }

    @Test
    fun theOnlyHostsAreTheFourPresets_overTlsOnly() {
        val hosts = setOf("imap.gmail.com", "outlook.office365.com", "imap.mail.yahoo.com", "imap.mail.me.com")
        assertEquals(hosts, EmailProvider.hosts)
        assertEquals(hosts, LegalText.mailHosts)
        assertEquals(993, EmailProvider.PORT)
        // No host name is written anywhere else in the code (a literal in a second file would be a fifth host), and none can be typed.
        val written = Regex("(?i)\\b(?:imap|smtp|pop3?|mail)\\.[a-z0-9.-]+\\.[a-z]{2,}\\b|outlook\\.office365\\.com")
        for (file in appFiles()) assertFalse("${file.name} writes a mail host", written.containsMatchIn(code(file.readText())))
        File(repoRoot(), "domain/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "EmailProvider.kt" }.forEach {
            assertFalse("${it.name} writes a mail host", written.containsMatchIn(code(it.readText())))
        }
        val client = code(main("data/email/ImapClient.kt"))
        assertTrue("InetSocketAddress(provider.host, EmailProvider.PORT)" in client)
        assertTrue("createSocket(base, provider.host, EmailProvider.PORT, true)" in client)
        assertTrue("endpointIdentificationAlgorithm = \"HTTPS\"" in client && "getDefaultHostnameVerifier().verify(provider.host, socket.session)" in client)
        assertTrue("TLSv1.3" in client && "TLSv1.2" in client && "TIMEOUT_MS = 15_000" in client && "soTimeout = TIMEOUT_MS" in client)
        assertFalse(Regex("(?i)starttls|http://|TrustManager|HostnameVerifier\\s*\\{|ALLOW_ALL|setHostnameVerifier|X509").containsMatchIn(client.replace("getDefaultHostnameVerifier", "")))
        assertTrue("provider: EmailProvider" in client && "host: String" !in client)
        // The host list is on the page and in the notes.
        for (host in hosts) {
            assertTrue("$host missing from the page", host in LegalText.fullText())
            assertTrue("$host missing from PRIVACY.md", host in doc("docs/PRIVACY.md"))
            assertTrue("$host missing from SECURITY.md", host in doc("SECURITY.md"))
        }
    }

    @Test
    fun onlyReadOnlyImapCommandsExist() {
        val wire = emailDomainFiles().filter { it.name.startsWith("Imap") }.joinToString("\n") { code(it.readText()) }
        val banned = Regex("\\b(STORE|EXPUNGE|DELETE|COPY|APPEND|SELECT|CREATE|RENAME|MOVE|IDLE|SUBSCRIBE|UNSUBSCRIBE|CLOSE|UNSELECT|SETACL|AUTHENTICATE|STARTTLS)\\b|\\\\\\\\(Seen|Deleted|Flagged|Answered)")
        assertFalse(banned.find(wire)?.value ?: "", banned.containsMatchIn(wire))
        // The verbs that are there.
        for (verb in listOf("LOGIN ", "EXAMINE INBOX", "UID SEARCH SINCE", "UID FETCH", "LOGOUT")) assertTrue(verb, verb in wire)
        // Every read of a body or header is a peek; a plain BODY[ (which would set the seen flag) is nowhere.
        assertTrue("BODY.PEEK[" in wire)
        assertFalse(Regex("(?<![.]PEEK)\\bBODY\\[").containsMatchIn(wire.replace("BODY.PEEK[", "PEEKED[")))
        assertFalse("RFC822" in wire)
        // The session takes a command object and nothing else: no way to send a raw line.
        assertTrue("fun run(command: ImapCommand): ImapReply" in wire)
        assertFalse(Regex("fun (send|write|raw|runRaw)\\(").containsMatchIn(wire))
        assertTrue("sealed class ImapCommand" in wire)
    }

    @Test
    fun onlyListedSendersAreSearched_fetched_andKept() {
        assertTrue("EmailAlertSenders::isAllowed" in main("data/email/ImapClient.kt"))
        val mailbox = domain("email/ImapMailbox.kt")
        assertTrue("allowed(it.from)" in mailbox && "UidSearch(since, senders)" in mailbox)
        assertTrue("senders = EmailAlertSenders.addresses" in domain("email/EmailSync.kt"))
        assertTrue("EmailAlertSenders.find(mail.from) ?: continue" in domain("email/EmailSync.kt"))
        // A body is fetched only from the mails that passed the header check.
        assertTrue(mailbox.indexOf("allowed(it.from)") < mailbox.indexOf("UidFetchBody("))
        // The list is exact addresses, in one file, flagged as unverified.
        val list = domain("email/EmailAlertSenders.kt")
        assertTrue("NOT VERIFIED" in list && "it.address == a" in list.replace("{ it.address == a }", "it.address == a"))
        assertTrue(EmailAlertSenders.all.all { it.address == it.address.lowercase() && Regex("[a-z0-9._+-]+@[a-z0-9.-]+\\.[a-z]+").matches(it.address) })
        assertTrue(EmailAlertSenders.isAllowed("alerts@hdfcbank.bank.in") && EmailAlertSenders.isAllowed("alerts@hdfcbank.net"))
        assertFalse(EmailAlertSenders.isAllowed("alerts@hdfcbank.net.evil.com"))
        // What is kept of an email: parsed fields and a masked snippet; no subject, sender address or body field.
        val fields = EmailAlert::class.java.declaredFields.map { it.name.lowercase() }
        for (banned in listOf("subject", "from", "body", "text", "recipient", "to", "address", "email", "messageid")) assertFalse("$banned is kept", banned in fields)
        assertTrue("snippet" in fields)
    }

    @Test
    fun nothingAboutAMailboxIsLogged() {
        val files = File(appRoot(), "src/main/java/com/zyke/message/data/email").listFiles()!!.toList() +
            File(appRoot(), "src/main/java/com/zyke/message/presentation/email").listFiles()!!.toList() +
            File(appRoot(), "src/main/java/com/zyke/message/data/repository/EmailAlertRepositoryImpl.kt")
        for (file in files) {
            val text = code(file.readText())
            assertFalse("${file.name} logs", Regex("\\bLog\\.|println\\(|printStackTrace|Timber|DevLog\\.e\\(|BlackBox").containsMatchIn(text))
            for (call in Regex("DevLog\\.d\\(\"email\"\\) \\{ (\".*?\") \\}").findAll(text)) {
                val body = call.groupValues[1].trim()
                assertTrue("${file.name}: a log line may hold only a fixed word, a problem name or a class name: $body", body in listOf("\"ImapClient: \${e.problem}\"", "\"ImapClient: \${e.javaClass.simpleName}\"", "\"ImapClient: timeout\""))
            }
        }
        for (file in emailDomainFiles()) assertFalse(file.name, Regex("DiagLog|DevLog|println\\(|\\bLog\\.").containsMatchIn(code(file.readText())))
        // The log writers also strip an address or a login that someone passes by mistake.
        assertTrue("LogScrub.scrub(message)" in main("data/diag/DevLog.kt") && "LogScrub.scrub(" in main("data/diag/BlackBox.kt"))
        // Nothing about a command, a login or a reply gets into an error: no message, no cause.
        assertTrue("class MailException(val problem: MailProblem) : Exception(problem.name)" in domain("email/MailTypes.kt"))
        assertTrue("override fun toString(): String = \"Login(<hidden>)\"" in domain("email/ImapWire.kt"))
        assertTrue("override fun toString(): String = \"ImapReply(\$status)\"" in domain("email/ImapWire.kt"))
        // The page never writes the address or password into a message, a toast or a state that survives rotation.
        val ui = code(main("presentation/email/EmailSettings.kt"))
        assertFalse(Regex("Toast|Snackbar|Intent|share|Share").containsMatchIn(ui))
        assertTrue("var password by remember { mutableStateOf(\"\") }" in ui && "var address by remember {" in ui)
        assertFalse("rememberSaveable { mutableStateOf(\"\") }" in ui && Regex("rememberSaveable\\s*\\{\\s*mutableStateOf\\((?:settings\\.address|\"\")\\)").containsMatchIn(ui))
    }

    @Test
    fun thePasswordIsSealedLeftOutOfBackupsAndRemovedOnDisconnectAndOnDeleteAll() {
        val vault = main("data/email/EmailPasswordVault.kt")
        assertTrue("AES/GCM/NoPadding" in vault && "KeyProperties.KEY_ALGORITHM_AES" in vault && "AndroidKeyStore" in vault && "KEY_BITS = 256" in vault)
        assertTrue("const val FILE = \"email_secret\"" in vault && "const val ALIAS = \"zyke_email_password\"" in vault)
        assertTrue("deleteEntry(ALIAS)" in vault && "prefs.edit().clear().commit()" in vault)
        assertFalse("setUserAuthenticationRequired" in vault)
        // It is not plain text: the file only ever gets the sealed bytes.
        assertTrue("putString(BLOB, Base64.encodeToString(cipher.iv + sealed" in vault)
        val backup = main("data/backup/PrefsBackup.kt")
        val excluded = backup.substringAfter("private val EXCLUDED = setOf(").substringBefore(")")
        for (file in listOf("email_secret", "email_alerts", "email_alerts_settings")) assertTrue("$file must never travel in a backup", "\"$file\"" in excluded)
        assertTrue("const val ALERTS_FILE = \"email_alerts\"" in main("data/repository/EmailAlertRepositoryImpl.kt") && "const val SETTINGS_FILE = \"email_alerts_settings\"" in main("data/repository/EmailAlertRepositoryImpl.kt"))
        // Auto Backup takes the one encrypted snapshot file only (ManifestPolicyTest pins the rules); neither xml names an email file.
        for (rules in listOf("backup_rules.xml", "data_extraction_rules.xml")) assertFalse(File(appRoot(), "src/main/res/xml/$rules").readText().contains("email"))
        assertTrue("EmailPasswordVault.ALIAS" in main("data/DataWipe.kt"))
        val sync = domain("email/EmailSync.kt")
        val disconnect = sync.substringAfter("fun disconnect()").substringBefore("}")
        assertTrue("credentials.clear()" in disconnect && "repository.clearAll()" in disconnect)
        assertTrue("fun deleteAlerts() = repository.clearAlerts()" in sync)
        // The password is held only for the call: it is a parameter, never a field of the sync, and not part of the stored state.
        assertFalse(EmailAlertSettings::class.java.declaredFields.any { "password" in it.name.lowercase() })
        assertEquals(1, appFiles().count { "EmailPasswordVault(" in code(it.readText()) && it.name != "EmailPasswordVault.kt" })
    }

    @Test
    fun theSettingsPageSaysAppPassword_andWarns() {
        val ui = main("presentation/email/EmailSettings.kt")
        assertTrue("label = { Text(\"App password\") }" in ui)
        assertTrue("PasswordVisualTransformation()" in ui && "KeyboardType.Password" in ui)
        assertTrue("never your normal password" in ui)
        assertTrue("The app only reads alert emails from banks" in ui)
        assertTrue("Hide app password" in ui && "Show app password" in ui)
        for (provider in EmailProvider.entries) {
            assertTrue(provider.name, provider.appPasswordSteps.size in 2..3)
            assertTrue(provider.name, provider.appPasswordSteps.all { "http" !in it })
        }
        assertTrue("provider.appPasswordSteps.forEach" in ui)
        // Equal-width buttons, side by side or stacked on a narrow phone.
        assertTrue(Regex("AppButton\\(if \\(busy\\) \"Reading...\" else \"Sync now\".*Modifier\\.cell\\(\\)").containsMatchIn(ui))
        assertTrue(Regex("AppButton\\(\"Disconnect\", onDisconnect, Modifier\\.cell\\(\\)").containsMatchIn(ui))
        val card = main("presentation/email/EmailNoticeCard.kt")
        assertEquals(2, Regex("Modifier\\.cell\\(\\)").findAll(card).count())
        assertTrue("\"Not now\"" in card && "\"Set up\"" in card && "hdfcNoticeAnswered = true" in card)
        // Page, search entries, summary.
        assertTrue("SettingsGroup.EMAIL -> com.zyke.message.presentation.email.EmailSettingsContent()" in main("presentation/settings/SettingsScreen.kt"))
        val index = com.zyke.message.domain.settings.SettingsIndex.all.filter { it.page == com.zyke.message.domain.settings.SettingsPageId.EMAIL }
        assertTrue(index.size >= 5 && index.all { it.hiddenInDemo })
        // The "Email" badge.
        assertTrue("message.isEmail -> \"Email\"" in main("presentation/messages/components/ChatSourceUi.kt"))
        assertTrue("SourceTag(item.message)" in main("presentation/messages/components/TransactionCards.kt"))
        assertTrue("EmailNoticeCard(message)" in main("presentation/messages/components/MessageDetailSheet.kt"))
    }

    @Test
    fun theWordsSaySoEverywhere() {
        assertEquals(5, LegalInfo.TEXT_VERSION)
        val page = LegalText.fullText()
        for (needle in listOf("app password", "Bank alert emails", "never marks, moves, flags or deletes", "port", "secure (TLS)")) {
            assertTrue("the page must say \"$needle\"", needle.lowercase().replace("port", "") in page.lowercase())
        }
        val privacy = doc("docs/PRIVACY.md")
        val security = doc("SECURITY.md")
        val notes = doc("docs/LEGAL_NOTES.md")
        for ((name, text) in listOf("PRIVACY.md" to privacy, "SECURITY.md" to security, "LEGAL_NOTES.md" to notes, "ARCHITECTURE.md" to doc("docs/ARCHITECTURE.md"), "DESIGN.md" to doc("docs/DESIGN.md"), "UNIFICATION.md" to doc("docs/UNIFICATION.md"))) {
            assertTrue("$name must describe email alerts", Regex("(?i)email alerts?|alert emails?").containsMatchIn(text))
        }
        assertTrue("email_secret" in privacy && "email_alerts" in privacy && "app password" in privacy.lowercase())
        assertTrue("unverified" in privacy.lowercase() && "provider sees" in privacy.lowercase())
        assertTrue("ImapClient" in privacy && "EmailPolicyTest" in privacy)
        assertTrue("unverified" in notes.lowercase() && "app password" in notes.lowercase())
        assertTrue("Mail provider" in doc("NOTICE") || "mail provider" in doc("NOTICE"))
    }

    @Test
    fun theOnboardingAndUpdatePrompt_enablesOnlyAfterAConnect_andIsNeverShownInDemo() {
        val prompt = main("presentation/email/EmailPromptHost.kt")
        assertTrue("Set up leads to the consent page", prompt.contains("onPrimary = { phase = EmailPromptPhase.CONSENT.ordinal }"))
        assertTrue("Continue only records the answer", prompt.contains("it.copy(emailPrompted = true, explained = true)") && prompt.contains("phase = EmailPromptPhase.CONNECT.ordinal"))
        // The prompt never switches the feature on itself: enabling is in EmailSync.connect, after the sign-in worked.
        assertFalse("enabled = true" in prompt)
        assertTrue("ConnectCard(state.settings, container.emailSync, onConnected = onDone)" in prompt)
        val sync = domain("email/EmailSync.kt")
        assertTrue(sync.indexOf("source.check(provider, a, p)") < sync.indexOf("enabled = true, explained = true"))
        assertTrue("fun notNow() { answered(); onDone() }" in prompt && "onDismissRequest = { notNow() }" in prompt)
        assertEquals(1, appFiles().count { "EmailPromptHost(" in code(it.readText()) && it.name == "MainActivity.kt" })
        val activity = main("presentation/MainActivity.kt")
        // After places, behind the lock screen and the welcome page, one at a time; the tour waits for both.
        assertTrue("4 -> {" in activity && "val kind = emailPromptKind()" in activity && "&& placesKind == com.zyke.message.domain.places.PlacesPromptKind.NONE && !locked && !welcomePending" in activity)
        assertTrue(activity.indexOf("PlacesPromptHost(") < activity.indexOf("EmailPromptHost("))
        assertTrue("private const val LAST_STAGE = 4" in activity)
        val settle = activity.substringAfter("EmailPromptHost(").substringBefore("if (welcomePending)")
        assertTrue("permissionsSettled.value = true" in settle)
        assertFalse("permissionsSettled.value = true" in activity.substringAfter("PlacesPromptHost(").substringBefore("val emailKind"))
        val kind = domain("email/EmailPrompt.kt")
        assertTrue(kind.contains("demo || blocked || answered(settings) -> EmailPromptKind.NONE"))
        assertTrue("the answered flag lives in the never-backed-up settings file", main("data/repository/EmailAlertRepositoryImpl.kt").contains(".putBoolean(\"prompted\", s.emailPrompted)"))
        assertTrue("anyone connected counts as answered", kind.contains("s.emailPrompted || s.connected || s.enabled"))
        // The contextual HDFC card still shows once when email is not connected, whatever the onboarding answer was.
        val card = main("presentation/email/EmailNoticeCard.kt")
        assertTrue("if (state.settings.connected || state.settings.hdfcNoticeAnswered) return" in card && "emailPrompted" !in card)
        assertTrue("equal buttons" in "equal buttons" && main("presentation/common/OnboardingHero.kt").count { it == '\n' } > 0)
    }
}
