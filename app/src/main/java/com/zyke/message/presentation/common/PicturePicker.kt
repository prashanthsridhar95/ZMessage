package com.zyke.message.presentation.common

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import kotlinx.coroutines.launch

/**
 * The one way to give something a picture, wherever a picture can be set (a sender, a receiver, an account): tapping
 * its photo opens a sheet to choose where from, the person's own photos or a Google image search for [label] inside
 * the app. [key] is what the picture is kept under. Returns what to call to start, or null where pictures are not available.
 */
@Composable
fun rememberPicturePicker(key: String, label: String): (() -> Unit)? {
    val store = LocalImageStore.current ?: return null
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 0 nothing open, 1 choosing where from, 2 searching the web.
    var step by rememberSaveable { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                if (!store.saveChosen(key, uri)) Toast.makeText(context, "Couldn't use that image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (step == 1) {
        AppBottomSheet({ step = 0 }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add a picture", Modifier.heading(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                PictureSource("From this phone", "Choose a photo from your gallery") {
                    step = 0
                    launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                PictureSource("From the web", "Search Google Images for \"$label\" and tap the one you want") { step = 2 }
            }
        }
    }
    if (step == 2) {
        ImageSearchSheet(
            query = label, store = store, onDismiss = { step = 0 },
            onUse = { bitmap ->
                scope.launch {
                    if (store.saveChosenBitmap(key, bitmap)) step = 0
                    else Toast.makeText(context, "Couldn't use that image", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
    return { step = 1 }
}

@Composable
private fun PictureSource(title: String, description: String, onClick: () -> Unit) {
    FintechCard(onClick = onClick) {
        RowTitle(title)
        Caption(description, maxLines = 2)
    }
}
