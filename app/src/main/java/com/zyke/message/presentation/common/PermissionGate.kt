package com.zyke.message.presentation.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.presentation.messages.components.Caption

/**
 * Explains what the app does with SMS access before asking: what you get, and that it stays on the phone.
 * Shared by every tab so the first thing anyone sees is the same clear promise.
 */
@Composable
fun PermissionGate(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Your money, from your messages",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Promise(R.drawable.baseline_message_24, "Reads the SMS already on this phone, to find payments, bills and OTPs")
            Promise(R.drawable.baseline_lock_24, "Everything stays on your device. Nothing is uploaded, nothing is shared")
            Promise(R.drawable.baseline_dashboard_24, "Sorts them into spending, upcoming bills and a clean inbox")
        }
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) { Text("Allow SMS access") }
        Caption("You can change this any time in Settings", textAlign = TextAlign.Center)
    }
}

@Composable
private fun Promise(icon: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Image(
            modifier = Modifier.size(22.dp),
            painter = painterResource(icon),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            contentDescription = null
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
    }
}
