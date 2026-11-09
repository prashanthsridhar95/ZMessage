package com.zyke.message.presentation.trains

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.train.TrainApi
import com.zyke.message.notifications.TrainTrackingControl
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.ResponsiveRow
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle

/**
 * The person's own train data key for live train status: one masked line (with Show and Hide), Save and Clear. The key is kept in a
 * device-only file (never backed up, never logged); what is typed here lives in memory only (`remember`, not `rememberSaveable`, so
 * it is not written to the saved instance state either) and is emptied after Save. Not shown in Demo mode, which needs no key.
 */
@Composable
internal fun TrainKeySection() {
    val context = LocalContext.current
    val service = (context.applicationContext as ZykeMessagesApplication).container.trains
    val hasKey by service.hasKey.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val problem = if (draft.isEmpty()) null else TrainApi.keyProblem(draft)
    val canSave = draft.isNotEmpty() && problem == null
    fun save() {
        if (!canSave) return
        if (service.saveKey(draft)) {
            draft = ""
            shown = false
            focus.clearFocus()
            TrainTrackingControl.rearm(context)
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        RowTitle("Train data key")
        Caption(
            "Get a free key at indianrailapi.com and paste it here. It stays on this phone and is never backed up or logged. Live train status needs it.",
            Modifier.padding(top = 2.dp), maxLines = 5
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(TrainApi.KEY_MAX + 20) },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 56.dp),
            label = { Text("Train data key") },
            placeholder = { Text(if (hasKey) "Paste a new key to replace ${service.maskedKey()}" else "Paste your key") },
            singleLine = true,
            isError = problem != null,
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSave) save() else focus.clearFocus() }),
            trailingIcon = {
                AppButton(
                    if (shown) "Hide" else "Show", { shown = !shown },
                    Modifier.semantics { contentDescription = if (shown) "Hide train data key" else "Show train data key" },
                    style = ButtonStyle.TEXT, size = ButtonSize.COMPACT
                )
            }
        )
        if (problem != null) Text(problem, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else if (hasKey) Caption("A key is saved on this phone (${service.maskedKey()}).", Modifier.padding(top = 4.dp), maxLines = 2)
        ResponsiveRow(Modifier.fillMaxWidth().padding(top = 10.dp), spacing = 8.dp) {
            AppButton("Save", ::save, Modifier.cell(), enabled = canSave)
            AppButton(
                "Clear",
                {
                    service.clearKey()
                    draft = ""
                    shown = false
                    focus.clearFocus()
                    TrainTrackingControl.rearm(context)
                },
                Modifier.cell(), style = ButtonStyle.OUTLINE, enabled = hasKey || draft.isNotEmpty()
            )
        }
    }
}
