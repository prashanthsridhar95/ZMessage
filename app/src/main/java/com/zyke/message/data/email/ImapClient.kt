package com.zyke.message.data.email

import com.zyke.message.data.diag.DevLog
import com.zyke.message.domain.email.EmailAlertSenders
import com.zyke.message.domain.email.EmailProvider
import com.zyke.message.domain.email.ImapMailbox
import com.zyke.message.domain.email.ImapSession
import com.zyke.message.domain.email.MailBatch
import com.zyke.message.domain.email.MailException
import com.zyke.message.domain.email.MailProblem
import com.zyke.message.domain.email.MailRequest
import com.zyke.message.domain.email.MailResult
import com.zyke.message.domain.email.MailSource
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The only code that opens a connection to a mailbox, and the only class that uses an SSL socket for mail. One of four fixed hosts
 * ([EmailProvider]: there is no way to give it another one), TLS from the first byte on port 993 (no STARTTLS, no plain fallback),
 * the certificate checked by the system and the host name checked twice (the TLS handshake's own check and an explicit one), 15 s
 * to connect and 15 s of silence at most. What is said on the connection is decided by `ImapMailbox` in `:domain`, which can only write
 * read-only commands. Constructed in one place, `AppContainer`, and used only by `EmailSync`.
 *
 * Every failure becomes a [MailProblem]; the log gets that name and the exception's class, never an address, a password or a reply.
 *
 * NOT EXERCISED AGAINST A REAL MAIL SERVER: the build environment has no network. The protocol logic is tested against a scripted
 * server (`ImapSessionTest`); this class only opens the socket and hands its streams over.
 */
internal class ImapClient : MailSource {

    override fun check(provider: EmailProvider, address: String, password: String): MailResult<Unit> =
        run(provider) { session -> ImapMailbox.check(session, address, password) }

    override fun fetch(request: MailRequest): MailResult<MailBatch> =
        run(request.provider) { session -> ImapMailbox.fetch(session, request, EmailAlertSenders::isAllowed) }

    private fun <T> run(provider: EmailProvider, work: (ImapSession) -> T): MailResult<T> {
        var plain: Socket? = null
        var secure: SSLSocket? = null
        return try {
            val base = Socket()
            plain = base
            base.connect(InetSocketAddress(provider.host, EmailProvider.PORT), TIMEOUT_MS)
            base.soTimeout = TIMEOUT_MS
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val socket = factory.createSocket(base, provider.host, EmailProvider.PORT, true) as SSLSocket
            secure = socket
            socket.soTimeout = TIMEOUT_MS
            socket.sslParameters = socket.sslParameters.apply {
                // The handshake checks the name in the certificate against the host; nothing is skipped.
                endpointIdentificationAlgorithm = "HTTPS"
                protocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            }
            socket.startHandshake()
            // And once more by hand: a platform that ignored the setting above must not make this connection unchecked.
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(provider.host, socket.session)) throw SSLPeerUnverifiedException("host")
            val session = ImapSession(socket.inputStream.buffered(), socket.outputStream.buffered())
            MailResult.Ok(work(session))
        } catch (e: MailException) {
            DevLog.d("email") { "ImapClient: ${e.problem}" }
            MailResult.Failed(e.problem)
        } catch (e: SocketTimeoutException) {
            DevLog.d("email") { "ImapClient: timeout" }
            MailResult.Failed(MailProblem.TIMEOUT)
        } catch (e: SSLException) {
            DevLog.d("email") { "ImapClient: ${e.javaClass.simpleName}" }
            MailResult.Failed(MailProblem.TLS)
        } catch (e: IOException) {
            DevLog.d("email") { "ImapClient: ${e.javaClass.simpleName}" }
            MailResult.Failed(MailProblem.NETWORK)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            DevLog.d("email") { "ImapClient: ${e.javaClass.simpleName}" }
            MailResult.Failed(MailProblem.PROTOCOL)
        } finally {
            runCatching { secure?.close() }
            runCatching { plain?.close() }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}
