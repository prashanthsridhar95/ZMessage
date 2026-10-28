package com.zyke.message.presentation.common

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zyke.message.R

/** Opens a link from anywhere in the app: web pages in the in-app sheet, anything else (a call, a UPI request) in its own app. */
val LocalOpenLink = compositionLocalOf<(String) -> Unit> { {} }

private val WEB_LINK = Regex("""(?i)\bhttps?://[^\s<>"')\]]+""")

/** The web links in a message body, in order, without trailing punctuation. */
fun linksIn(body: String): List<String> =
    WEB_LINK.findAll(body).map { it.value.trimEnd('.', ',', ';', ':', '!', '?') }.distinct().toList()

fun isWebLink(url: String) = url.startsWith("http://", true) || url.startsWith("https://", true)

/** Hands a non-web link (tel:, upi:, mailto:) to whichever app handles it. */
fun openExternally(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(context, R.string.link_failed, Toast.LENGTH_SHORT).show() }
}

/** A web page inside the app, so a bill link or a tracking page does not take the person away from their messages. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LinkSheet(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var progress by remember { mutableFloatStateOf(0f) }
    var loading by remember { mutableStateOf(true) }
    var host by remember { mutableStateOf(Uri.parse(url).host.orEmpty()) }

    AppBottomSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().height(640.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    host,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                AppButton("Open in browser", { openExternally(context, url); onDismiss() }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
            if (loading) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())

            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) { loading = true }
                            override fun onPageFinished(view: WebView, u: String?) {
                                loading = false
                                Uri.parse(u).host?.let { host = it }
                            }
                            // Pages that hand off to an app (UPI, a bank app) open it; plain web pages stay here.
                            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                                val next = request.url.toString()
                                if (isWebLink(next)) return false
                                openExternally(ctx, next)
                                return true
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress / 100f }
                        }
                        loadUrl(url)
                    }
                },
                onRelease = { it.stopLoading(); it.destroy() }
            )
        }
    }
}
