package com.zyke.message.presentation.common

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zyke.message.data.images.ImageStore
import com.zyke.message.presentation.messages.components.Caption

/** Reports the address of a picture the person tapped on the page. Takes a string and nothing else, so a page can do no more with it. */
private class PickBridge(private val onPicked: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun picked(src: String) {
        if (src.length < MAX_ADDRESS && (src.startsWith("https://") || src.startsWith("data:image"))) main.post { onPicked(src) }
    }

    private companion object {
        const val MAX_ADDRESS = 2_000_000
    }
}

/** Finds the picture under a tap (the image itself, or the only one inside what was tapped) and hands its address over. */
private const val TAP_SCRIPT = """
(function() {
  if (window.__zykePick) return;
  window.__zykePick = true;
  function find(el) {
    if (!el || !el.closest) return null;
    var img = el.closest('img');
    if (img) return img;
    var box = el.closest('a,div');
    if (box) { var all = box.querySelectorAll('img'); if (all.length === 1) return all[0]; }
    return null;
  }
  document.addEventListener('click', function(e) {
    var img = find(e.target);
    if (!img) return;
    var src = img.currentSrc || img.src;
    if (src) ZykePick.picked(src);
  }, true);
})();
"""

/**
 * Google Images for [query], inside the app. Tap a picture and it is shown in the bar below; "Use this picture" keeps
 * it for the sender. A long press on a picture does the same, for pages that handle taps themselves.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ImageSearchSheet(query: String, store: ImageStore, onUse: (Bitmap) -> Unit, onDismiss: () -> Unit) {
    var tapped by remember { mutableStateOf<String?>(null) }
    var candidate by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(tapped) {
        candidate = null
        failed = false
        tapped?.let { address ->
            candidate = store.fetchPicked(address)
            failed = candidate == null
        }
    }

    AppBottomSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().height(SHEET_HEIGHT)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Find a picture", Modifier.heading(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Caption("Searching for \"$query\"", maxLines = 1)
                }
                AppButton("Cancel", onDismiss, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
            if (loading) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())

            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        addJavascriptInterface(PickBridge { tapped = it }, "ZykePick")
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading = true }
                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                view.evaluateJavascript(TAP_SCRIPT, null)
                            }
                            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                                !request.url.toString().startsWith("https://", ignoreCase = true)
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress / 100f }
                        }
                        setOnLongClickListener {
                            val hit = hitTestResult
                            val src = hit.extra
                            if ((hit.type == WebView.HitTestResult.IMAGE_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) && src != null) {
                                tapped = src
                                true
                            } else {
                                false
                            }
                        }
                        loadUrl("https://www.google.com/search?tbm=isch&q=" + android.net.Uri.encode(query))
                    }
                },
                onRelease = { it.stopLoading(); it.destroy() }
            )

            // What was tapped, and the one button to keep it.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    candidate?.let {
                        Image(it.asImageBitmap(), contentDescription = "The picture you tapped", Modifier.size(56.dp), contentScale = ContentScale.Crop)
                    }
                }
                Text(
                    when {
                        failed -> "That one could not be loaded. Try another."
                        tapped != null && candidate == null -> "Loading…"
                        candidate != null -> "Use this one?"
                        else -> "Tap a picture, then choose Use this picture."
                    },
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                AppButton("Use this picture", { candidate?.let(onUse) }, enabled = candidate != null)
            }
        }
    }
}

private val SHEET_HEIGHT = 640.dp
