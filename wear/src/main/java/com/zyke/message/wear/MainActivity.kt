package com.zyke.message.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Colors
import com.zyke.message.domain.wear.WatchSnapshot
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SnapshotStore.pullExisting(this)
        setContent {
            ZykeWearTheme {
                val snapshot by SnapshotStore.flow(this).collectAsState()
                HomeScreen(snapshot)
            }
        }
    }
}

private val Ink = Color(0xFF000000) // true black: off pixels on an AMOLED screen
private val Panel = Color(0xFF14161A)
private val Good = Color(0xFF7BD88F)
private val Bad = Color(0xFFFF8A80)
private val Muted = Color(0xFFB0B6BF)

/** Dark only, true black background, large type. */
@Composable
fun ZykeWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = Colors(background = Ink, surface = Panel, primary = Color(0xFFB8C7FF), onBackground = Color.White, onSurface = Color.White),
        content = content
    )
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().heightIn(min = 48.dp).background(Panel, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) { content() }
}

@Composable
fun HomeScreen(snapshot: WatchSnapshot?) {
    Box(Modifier.fillMaxSize().background(Ink)) {
        if (snapshot == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Open Zyke Message on your phone", textAlign = TextAlign.Center, fontSize = 18.sp, color = Color.White)
            }
        } else {
            val m = snapshot.masked
            val stale = Format.isStale(snapshot)
            val left = snapshot.todayLeft
            ScalingLazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (stale) "Yesterday's left" else "Today left", fontSize = 14.sp, color = Muted)
                        Text(
                            Format.rupees(left, m), fontSize = 36.sp,
                            color = when { m || stale || left == null -> Muted; left < 0 -> Bad; else -> Good }
                        )
                    }
                }
                item {
                    Card {
                        Text("This month", fontSize = 13.sp, color = Muted)
                        Text(Format.rupees(snapshot.monthSpent, m), fontSize = 20.sp)
                        val vs = Format.delta(snapshot.monthDeltaPercent)
                        Text(
                            if (m) "Last month ${Format.rupees(null, true)}" else if (vs != null) "$vs vs last month (${Format.rupees(snapshot.lastMonthSpent, false)})" else "Nothing to compare yet",
                            fontSize = 13.sp, color = Muted
                        )
                    }
                }
                item { Text("Coming up", fontSize = 14.sp, color = Muted, modifier = Modifier.padding(top = 4.dp)) }
                if (snapshot.bills.isEmpty()) {
                    item { Card { Text("Nothing due this week", fontSize = 16.sp) } }
                } else {
                    snapshot.bills.forEach { bill ->
                        item {
                            Card {
                                Text(bill.name, fontSize = 16.sp, maxLines = 1)
                                Text("${Format.rupees(bill.rupees, m)} · ${Format.dueIn(bill.inDays)}", fontSize = 13.sp, color = Muted)
                            }
                        }
                    }
                }
                item {
                    Card {
                        Text("Needs review", fontSize = 13.sp, color = Muted)
                        Text(if (snapshot.reviewCount == 0) "All clear" else "${snapshot.reviewCount} to look at", fontSize = 18.sp)
                    }
                }
                snapshot.balance?.let { balance ->
                    item { Card { Text("Latest balance", fontSize = 13.sp, color = Muted); Text(Format.rupees(balance, m), fontSize = 18.sp) } }
                }
                item {
                    Text(
                        "Updated " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snapshot.updatedAt)),
                        fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                    )
                }
            }
            TimeText()
        }
    }
}
