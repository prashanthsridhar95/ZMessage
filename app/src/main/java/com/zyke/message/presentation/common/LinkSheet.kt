package com.zyke.message.presentation.common

import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
import android.view.ViewGroup
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

/**
 * The one web page the person has open inside the app. The page itself lives here, not in the sheet, so minimising
 * the sheet leaves it loaded and running, and opening it again picks up where it was.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebSession(private val context: android.content.Context) {
    /** The address being shown; null when nothing is open. */
    var url by mutableStateOf<String?>(null)
        private set
    var minimised by mutableStateOf(false)
    var host by mutableStateOf("")
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var loading by mutableStateOf(false)
        private set

    private var view: WebView? = null

    val isOpen: Boolean get() = url != null

    /** Shows [address]: in the page already open when it is the same one, else loads it fresh. */
    fun open(address: String) {
        minimised = false
        if (url == address && view != null) return
        url = address
        host = Uri.parse(address).host.orEmpty()
        view?.loadUrl(address) ?: webView().loadUrl(address)
    }

    fun close() {
        view?.let { it.stopLoading(); (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        view = null
        url = null
        minimised = false
        loading = false
    }

    /** The page's view, made once and handed back to whichever sheet is showing it. */
    fun webView(): WebView = view ?: WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) { this@WebSession.loading = true }
            override fun onPageFinished(view: WebView, u: String?) {
                this@WebSession.loading = false
                Uri.parse(u).host?.let { this@WebSession.host = it }
            }

            // Pages that hand off to an app (UPI, a bank app) open it; plain web pages stay here.
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                val next = request.url.toString()
                if (isWebLink(next)) return false
                openExternally(context, next)
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { this@WebSession.progress = newProgress / 100f }
        }
    }.also { view = it }
}

/** A web page inside the app, so a bill link or a tracking page does not take the person away from their messages. */
@Composable
fun LinkSheet(session: WebSession) {
    val context = LocalContext.current

    // Swiping the sheet away closes the page; "Minimise" keeps it, as a button that can be moved about.
    AppBottomSheet({ session.close() }) {
        Column(Modifier.fillMaxWidth().height(640.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    session.host,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                AppButton("Minimise", { session.minimised = true }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                AppButton(
                    "Browser",
                    { session.url?.let { openExternally(context, it) }; session.close() },
                    style = ButtonStyle.TEXT, size = ButtonSize.COMPACT
                )
            }
            if (session.loading) LinearProgressIndicator(progress = { session.progress }, Modifier.fillMaxWidth())

            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = {
                    // The same page may have been on screen before; it can only have one parent.
                    session.webView().also { (it.parent as? ViewGroup)?.removeView(it) }
                },
                // Not destroyed here: minimising takes it off the screen but keeps it alive.
                onRelease = { (it.parent as? ViewGroup)?.removeView(it) }
            )
        }
    }
}

/**
 * The minimised page: a round button that can be dragged anywhere. A tap opens the page again; dragging it onto the
 * circle that appears at the bottom centre closes it.
 */
@Composable
fun MinimisedLink(session: WebSession, modifier: Modifier = Modifier) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizePx = with(density) { FAB_SIZE.toPx() }
    val targetPx = with(density) { TARGET_SIZE.toPx() }
    val reach = with(density) { CLOSE_REACH.toPx() }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        var position by remember(width, height) { mutableStateOf(Offset(width - sizePx - 24f, height - sizePx * 2.5f)) }
        var dragging by remember { mutableStateOf(false) }

        // The close target, bottom centre.
        val targetCentre = Offset(width / 2f, height - targetPx / 2f - 24f)
        fun overTarget() = (position + Offset(sizePx / 2f, sizePx / 2f) - targetCentre).getDistance() < reach

        if (dragging) {
            val hot = overTarget()
            Box(
                Modifier
                    .offset { IntOffset((targetCentre.x - targetPx / 2f).roundToInt(), (targetCentre.y - targetPx / 2f).roundToInt()) }
                    .size(TARGET_SIZE)
                    .clip(CircleShape)
                    .background(if (hot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "\u2715",
                    color = if (hot) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }

        Box(
            Modifier
                .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                .size(FAB_SIZE)
                .shadow(if (dragging) 12.dp else 6.dp, CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .pointerInput(Unit) { detectTapGestures(onTap = { session.minimised = false }) }
                .pointerInput(width, height) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDrag = { change, amount ->
                            change.consume()
                            position = Offset(
                                (position.x + amount.x).coerceIn(0f, width - sizePx),
                                (position.y + amount.y).coerceIn(0f, height - sizePx)
                            )
                        },
                        onDragEnd = {
                            if (overTarget()) session.close()
                            dragging = false
                        },
                        onDragCancel = { dragging = false }
                    )
                }
                .semantics { contentDescription = "Open ${session.host} again. Drag to the bottom to close it" },
            contentAlignment = Alignment.Center
        ) {
            Text("\uD83C\uDF10", style = MaterialTheme.typography.titleLarge)
        }
    }
}

private val FAB_SIZE = 56.dp
private val TARGET_SIZE = 64.dp
private val CLOSE_REACH = 64.dp
