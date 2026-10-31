package com.zyke.message.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.split.FriendBalance
import com.zyke.message.domain.split.FriendBalances
import com.zyke.message.domain.split.SplitInsights
import com.zyke.message.domain.split.SplitPayer
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.EmptyState
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.LinkButton
import com.zyke.message.presentation.common.LocalAppSnackbar
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.home.HomeContract.Intent
import com.zyke.message.presentation.home.HomeContract.State
import com.zyke.message.presentation.messages.components.AmountText
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import java.time.ZoneId
import kotlinx.coroutines.launch

/** A link under "Friends owe you" to the running balance with each friend. Shows nothing until a split exists. */
@Composable
internal fun FriendsLink(state: State, onIntent: (Intent) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    if (state.splits.isNotEmpty()) LinkButton("Balances with friends", { open = true })
    if (open) FriendsPage(state, onIntent) { open = false }
}

@Composable
private fun FriendsPage(state: State, onIntent: (Intent) -> Unit, onBack: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val shares = remember(state.splits, state.messages) { SplitInsights.build(state.splits, state.messages, zone) }
    val friends = remember(shares, state.messages) { FriendBalances.fromShares(shares, state.messages, zone) }
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()

    fun settle(friend: FriendBalance) {
        val changed = FriendBalances.settleAll(state.splits, shares, friend.name)
        if (changed.isEmpty()) return
        val originals = state.splits.filter { s -> changed.any { it.id == s.id } }
        changed.forEach { onIntent(Intent.SaveSplit(it)) }
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("Settled with ${friend.name}", actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) originals.forEach { onIntent(Intent.SaveSplit(it)) }
        }
    }

    AppPage("Friends", onBack) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (friends.isEmpty()) {
                EmptyState("No friends yet", "Split a payment from its details and the people you share it with appear here.")
            }
            friends.forEach { FriendCard(it, onSettle = { settle(it) }) }
        }
    }
}

@Composable
private fun FriendCard(friend: FriendBalance, onSettle: () -> Unit) {
    FintechCard {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    RowTitle(friend.name)
                    Caption(friend.suggestion)
                }
                if (!friend.isSettled) {
                    val color = if (friend.net.signum() >= 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                    AmountText((if (friend.net.signum() < 0) "-" else "") + DisplayFormatters.rupees(friend.net.abs()), color)
                }
            }
            friend.open.forEach { share ->
                val theyPaid = share.split.paidBy == SplitPayer.FRIEND
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Caption(share.split.title + if (theyPaid) " (they paid)" else "", Modifier.weight(1f))
                    Caption((if (theyPaid) "-" else "") + DisplayFormatters.rupees(share.person.share))
                }
            }
            if (!friend.isSettled) AppButton("Settle", onSettle, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT)
            else if (friend.settledCount > 0) Caption("${friend.settledCount} settled payments")
        }
    }
}
