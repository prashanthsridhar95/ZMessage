package com.zyke.message.data.backup

import com.zyke.message.domain.backup.RemoteBackup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

/**
 * The one place that talks to Google Drive (the other two network files are LogoFetcher and QuoteFetcher). It uses only
 * the Drive v3 REST API on www.googleapis.com, only the private "appDataFolder" (scope drive.appdata, which can see
 * nothing else in the person's Drive), and sends only the one PIN-encrypted backup file. Nothing else is sent: no
 * analytics, no identifiers. The access token comes from [DriveAuth] and is never stored or logged.
 */
class DriveBackupClient {
    /** Uploads one backup file into the app folder. */
    @Throws(BackupFailureException::class)
    fun upload(token: String, name: String, bytes: ByteArray): RemoteBackup {
        val boundary = "zyke" + java.util.UUID.randomUUID().toString().replace("-", "")
        val metadata = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(APP_FOLDER)).toString()
        val head = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
            "--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n"
        val tail = "\r\n--$boundary--"
        val text = call(
            token, "POST", "$UPLOAD/files?uploadType=multipart&fields=id,modifiedTime,size",
            contentType = "multipart/related; boundary=$boundary",
            body = { out ->
                out.write(head.toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.write(tail.toByteArray(Charsets.UTF_8))
            }
        ) { readText(it, MAX_JSON) }
        return parseFile(JSONObject(text), bytes.size.toLong())
    }

    /** The backups in the app folder, newest first. */
    @Throws(BackupFailureException::class)
    fun list(token: String): List<RemoteBackup> {
        val fields = URLEncoder.encode("files(id,modifiedTime,size)", "UTF-8")
        val order = URLEncoder.encode("modifiedTime desc", "UTF-8")
        val text = call(token, "GET", "$API/files?spaces=$APP_FOLDER&orderBy=$order&pageSize=100&fields=$fields") { readText(it, MAX_JSON) }
        val files = JSONObject(text).optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).mapNotNull { runCatching { parseFile(files.getJSONObject(it), 0L) }.getOrNull() }
            .sortedByDescending { it.modifiedAt }
    }

    @Throws(BackupFailureException::class)
    fun download(token: String, id: String): String {
        require(id.matches(ID)) { "bad id" }
        return call(token, "GET", "$API/files/$id?alt=media") { readText(it, MAX_DOWNLOAD) }
    }

    @Throws(BackupFailureException::class)
    fun delete(token: String, id: String) {
        require(id.matches(ID)) { "bad id" }
        call(token, "DELETE", "$API/files/$id") { }
    }

    private fun parseFile(json: JSONObject, fallbackSize: Long) = RemoteBackup(
        id = json.getString("id"),
        modifiedAt = runCatching { Instant.parse(json.getString("modifiedTime")) }.getOrDefault(Instant.now()),
        sizeBytes = json.optString("size").toLongOrNull() ?: fallbackSize
    )

    private fun <T> call(
        token: String, method: String, url: String,
        contentType: String? = null, body: ((OutputStream) -> Unit)? = null,
        read: (InputStream) -> T
    ): T {
        var connection: HttpsURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpsURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", contentType ?: "application/octet-stream")
                    setChunkedStreamingMode(0)
                    outputStream.use(body)
                }
            }
            val code = connection.responseCode
            if (code in 200..299) return connection.inputStream.use(read)
            val reason = runCatching { connection.errorStream?.use { readText(it, MAX_JSON) } }.getOrNull().orEmpty()
            throw BackupFailureException(
                when {
                    code == HttpURLConnection.HTTP_UNAUTHORIZED -> BackupFailure.AUTH_EXPIRED
                    code == HttpURLConnection.HTTP_FORBIDDEN && ("storageQuotaExceeded" in reason || "quotaExceeded" in reason) -> BackupFailure.DRIVE_FULL
                    code == HttpURLConnection.HTTP_FORBIDDEN -> BackupFailure.AUTH_EXPIRED
                    code == 429 || code >= 500 -> BackupFailure.DRIVE_ERROR
                    else -> BackupFailure.UNKNOWN
                }
            )
        } catch (e: BackupFailureException) {
            throw e
        } catch (e: IOException) {
            throw BackupFailureException(BackupFailure.NO_NETWORK)
        } finally {
            connection?.disconnect()
        }
    }

    private fun readText(input: InputStream, limit: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw BackupFailureException(BackupFailure.UNKNOWN)
        }
        return out.toString("UTF-8")
    }

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val APP_FOLDER = "appDataFolder"
        const val TIMEOUT_MS = 30_000
        const val MAX_JSON = 1 shl 20
        const val MAX_DOWNLOAD = 48 * 1024 * 1024
        val ID = Regex("[A-Za-z0-9_-]{5,128}")
    }
}
