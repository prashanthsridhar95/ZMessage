package com.zyke.message.domain.email

import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Connect, sync, dedupe, disconnect: against a fake mailbox, so no connection is ever made. */
class EmailSyncTest {
    private val now = 1_795_000_000_000L
    private var clock = now

    private class FakeSource : MailSource {
        var checkResult: MailResult<Unit> = MailResult.Ok(Unit)
        var fetchResult: MailResult<MailBatch> = MailResult.Ok(MailBatch(1, 0, emptyList(), 0, 0))
        val requests = ArrayList<MailRequest>()
        var checks = 0
        override fun check(provider: EmailProvider, address: String, password: String): MailResult<Unit> { checks++; return checkResult }
        override fun fetch(request: MailRequest): MailResult<MailBatch> { requests += request; return fetchResult }
    }

    private val repo = InMemoryEmailAlerts()
    private val creds = InMemoryEmailCredentials()
    private val source = FakeSource()
    private val sync = EmailSync(repo, creds, source, clock = { clock }, zone = ZoneId.of("UTC"), io = Dispatchers.Unconfined)

    private fun mail(uid: Long, from: String = "alerts@hdfcbank.net", text: String = "Rs.250.00 has been debited from account **1234 to VPA a@b (Shop) on 28-11-26. Your UPI transaction reference number is 61234567890$uid.", id: String = "id$uid") =
        RawMail(uid, from, "Alert", now - 3600_000, id, text)

    private fun connect(password: String = "abcd efgh ijkl mnop") =
        runBlocking { sync.connect(EmailProvider.GMAIL, " me@gmail.com ", password) }

    @Test
    fun offUntilConnected_andNothingIsAsked() = runBlocking {
        assertNull(sync.syncNow())
        assertNull(sync.syncIfDue())
        assertTrue(source.requests.isEmpty())
        assertEquals(0, source.checks)
    }

    @Test
    fun connectChecksFirst_andKeepsNothingOnFailure() {
        source.checkResult = MailResult.Failed(MailProblem.WRONG_PASSWORD)
        val r = connect() as ConnectResult.Failed
        assertEquals(MailProblem.WRONG_PASSWORD, r.problem)
        assertFalse(creds.hasPassword())
        assertFalse(repo.state.value.settings.connected)
        assertFalse(repo.state.value.settings.enabled)
    }

    @Test
    fun connectSavesTheCleanedPassword_andTheAddress() {
        assertEquals(ConnectResult.Connected, connect())
        assertEquals("abcdefghijklmnop", creds.password())
        val s = repo.state.value.settings
        assertTrue(s.enabled && s.connected)
        assertEquals("me@gmail.com", s.address)
        assertEquals("gmail", s.providerId)
    }

    @Test
    fun badInputIsRefusedBeforeTheMailboxIsAsked() {
        val r = runBlocking { sync.connect(EmailProvider.GMAIL, "not an address", "short") } as ConnectResult.Failed
        assertEquals(MailProblem.BAD_INPUT, r.problem)
        assertEquals(0, source.checks)
        val pw = runBlocking { sync.connect(EmailProvider.GMAIL, "me@gmail.com", "abc") } as ConnectResult.Failed
        assertEquals(0, source.checks)
        assertFalse(pw.sentence.contains("abc"))
    }

    @Test
    fun aPassKeepsPaymentsAndMasksTheRest_andOnlyListedSendersCount() {
        connect()
        source.fetchResult = MailResult.Ok(
            MailBatch(
                5, 12,
                listOf(
                    mail(10),
                    mail(11, from = "someone@example.com"),
                    mail(12, text = "Your UPI transaction of Rs 99 to X was completed. Ref 612345678901")
                ),
                3, 0
            )
        )
        assertEquals(SyncOutcome.OK, runBlocking { sync.syncNow() })
        val state = repo.state.value
        assertEquals(2, state.alerts.size)
        assertEquals(1, state.understood)
        assertEquals(1, state.notUnderstood)
        assertEquals(5L, state.settings.uidValidity)
        assertEquals(12L, state.settings.highestUid)
        assertEquals(2, state.settings.lastAdded)
        assertEquals(now, state.settings.lastSyncAt)
        // The next pass starts after what was seen, for the same mailbox.
        runBlocking { sync.syncNow() }
        assertEquals(12L, source.requests.last().afterUid)
        assertEquals(5L, source.requests.last().knownUidValidity)
    }

    @Test
    fun theSameMailTwiceIsOneAlert() {
        connect()
        source.fetchResult = MailResult.Ok(MailBatch(5, 10, listOf(mail(10)), 1, 0))
        runBlocking { sync.syncNow(); sync.syncNow() }
        assertEquals(1, repo.state.value.alerts.size)
    }

    @Test
    fun theSearchAsksOnlyForTheListedAddresses() {
        connect()
        runBlocking { sync.syncNow() }
        assertEquals(EmailAlertSenders.addresses, source.requests.single().senders)
        assertEquals("abcdefghijklmnop", source.requests.single().password)
    }

    @Test
    fun aRefusedPasswordAsksForItAgain_andOtherTroubleKeepsTheConnection() {
        connect()
        source.fetchResult = MailResult.Failed(MailProblem.NETWORK)
        assertEquals(SyncOutcome.NETWORK, runBlocking { sync.syncNow() })
        assertTrue(repo.state.value.settings.connected)
        source.fetchResult = MailResult.Failed(MailProblem.WRONG_PASSWORD)
        assertEquals(SyncOutcome.WRONG_PASSWORD, runBlocking { sync.syncNow() })
        assertFalse(repo.state.value.settings.connected)
        assertNull(runBlocking { sync.syncNow() })
    }

    @Test
    fun aLostPasswordIsAskedForAgain_withoutAskingTheNetwork() {
        connect()
        creds.clear()
        assertEquals(SyncOutcome.WRONG_PASSWORD, runBlocking { sync.syncNow() })
        assertFalse(repo.state.value.settings.connected)
        assertTrue(source.requests.isEmpty())
    }

    @Test
    fun syncWhenTheAppOpens_respectsTheChosenGap() {
        connect()
        repo.update { it.copy(syncEvery = EmailSyncEvery.HOURLY) }
        assertEquals(SyncOutcome.OK, runBlocking { sync.syncIfDue() })
        clock += 10 * 60_000
        assertNull(runBlocking { sync.syncIfDue() })
        clock += 61 * 60_000
        assertEquals(SyncOutcome.OK, runBlocking { sync.syncIfDue() })
        repo.update { it.copy(syncEvery = EmailSyncEvery.MANUAL) }
        clock += 48 * 3600_000
        assertNull(runBlocking { sync.syncIfDue() })
        assertEquals(SyncOutcome.OK, runBlocking { sync.syncNow() })
        assertEquals(3, source.requests.size)
    }

    @Test
    fun disconnectForgetsEverything_andDeleteAlertsKeepsTheConnection() {
        connect()
        source.fetchResult = MailResult.Ok(MailBatch(5, 10, listOf(mail(10)), 1, 0))
        runBlocking { sync.syncNow() }
        sync.deleteAlerts()
        assertTrue(repo.state.value.alerts.isEmpty())
        assertTrue(repo.state.value.settings.connected)
        assertEquals(0L, repo.state.value.settings.highestUid)
        runBlocking { sync.syncNow() }
        assertEquals(1, repo.state.value.alerts.size)
        sync.disconnect()
        assertFalse(creds.hasPassword())
        assertEquals(EmailAlertState(), repo.state.value)
    }

    @Test
    fun oldAlertsAreDroppedByTheKeepForSetting_andTheStoreIsCapped() {
        connect()
        val old = EmailAlert("old", "hdfc", com.zyke.message.domain.model.BankName.HDFC, "HDFC Bank", now - 400L * 24 * 3600_000, EmailAlertKind.DEBIT, "1", "x", "", "1234", false, "", "")
        repo.add(listOf(old), now)
        assertTrue(repo.state.value.alerts.isEmpty())
        val many = (1..2100).map { old.copy(key = "k$it", at = now - it * 1000L) }
        repo.add(many, now)
        assertEquals(EmailAlertRepository.MAX_ALERTS, repo.state.value.alerts.size)
        assertEquals("k1", repo.state.value.alerts.first().key)
    }

    @Test
    fun theProvidersAreExactlyTheFourPresets_overTlsPort993() {
        assertEquals(setOf("imap.gmail.com", "outlook.office365.com", "imap.mail.yahoo.com", "imap.mail.me.com"), EmailProvider.hosts)
        assertEquals(993, EmailProvider.PORT)
        assertTrue(EmailProvider.entries.all { it.appPasswordSteps.size in 2..3 })
    }

    @Test
    fun theSenderListIsExactAddressesOnly() {
        assertTrue(EmailAlertSenders.isAllowed("alerts@hdfcbank.bank.in"))
        assertTrue(EmailAlertSenders.isAllowed(" Alerts@HDFCBank.net "))
        for (no in listOf("alerts@hdfcbank.net.example.com", "xalerts@hdfcbank.net", "alerts@mail.hdfcbank.net", "hdfcbank.net", "", "alerts@hdfcbank.com")) assertFalse(no, EmailAlertSenders.isAllowed(no))
        assertTrue(EmailAlertSenders.all.all { it.address == it.address.lowercase() && "@" in it.address })
        assertTrue(EmailAlertSenders.all.any { it.basis == SenderBasis.UNVERIFIED })
    }
}
