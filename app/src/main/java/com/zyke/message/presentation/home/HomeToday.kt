package com.zyke.message.presentation.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.bills.BillsMath
import com.zyke.message.domain.bills.DueTone
import com.zyke.message.domain.budget.DailyBudgetStatus
import com.zyke.message.domain.home.Focus
import com.zyke.message.domain.home.FocusPick
import com.zyke.message.domain.insights.Nudge
import com.zyke.message.domain.insights.NudgeKind
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.motion.MotionMath
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.CardHeader
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.LinkButton
import com.zyke.message.presentation.common.LocalResponsive
import com.zyke.message.presentation.common.ResponsiveRow
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.tappable
import com.zyke.message.presentation.dashboard.components.ShareBar
import com.zyke.message.presentation.flights.FlightCard
import com.zyke.message.presentation.flights.FlightsToday
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.MerchantChip
import com.zyke.message.presentation.messages.components.RowAmount
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.messages.components.RowTile
import com.zyke.message.presentation.messages.components.mutedColor
import com.zyke.message.presentation.motion.AnimatedAmount
import com.zyke.message.presentation.theme.AppColors
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.TypeRole
import com.zyke.message.presentation.trains.TrainCard
import com.zyke.message.presentation.trains.TrainsToday
import java.math.BigDecimal
import java.time.LocalDate

/** A bill the Today card can show, with what it needs to draw and act: either an upcoming bill or an overdue one. */
internal class FocusBillView(
    val title: String,
    val kind: String,
    val date: LocalDate,
    val amount: BigDecimal,
    val message: ClassifiedMessage,
    val payLink: String?,
    val onMarkPaid: () -> Unit
)

/**
 * TODAY: the one thing that matters now, chosen by [com.zyke.message.domain.home.HomeFocus]: a trip today (the flight or train card as it
 * is), a fresh code with Copy, a bill due or overdue with Pay and Mark as paid, the next bill due soon, or a calm line. When more
 * competes, a quiet "and N more" link under it opens the deck. Never more than one card.
 */
@Composable
internal fun TodayCard(
    pick: FocusPick,
    today: LocalDate,
    codes: List<ClassifiedMessage>,
    nowMillis: Long,
    flights: FlightsToday,
    trains: TrainsToday,
    bills: Map<String, FocusBillView>,
    nudge: Nudge?,
    onCopy: (ClassifiedMessage, String) -> Unit,
    onAllCodes: () -> Unit,
    onOpenFlight: (String) -> Unit,
    onOpenTrain: (String) -> Unit,
    onDismissTrip: (String) -> Unit,
    onAddTrip: () -> Unit,
    onPay: (String) -> Unit,
    onOpenMessage: (Long) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        when (val focus = pick.focus) {
            is Focus.Trip -> {
                if (focus.trip.isTrain) TrainCard(trains, onOpenTrain, onDismissTrip, onAddTrip)
                else FlightCard(flights, onOpenFlight, onDismissTrip, onAddTrip)
            }
            is Focus.Code -> FintechCard {
                CodePagerContent(codes, nowMillis, onCopy = onCopy, onAll = onAllCodes)
            }
            is Focus.BillDue -> BillFocus(bills[focus.bill.key], today, onPay, onOpenMessage)
            is Focus.BillSoon -> BillFocus(bills[focus.bill.key], today, onPay, onOpenMessage)
            Focus.Clear -> ClearFocus(nudge, onOpenMessage)
        }
        if (pick.hasMore) LinkButton("and ${pick.more} more", onMore)
    }
}

@Composable
private fun BillFocus(view: FocusBillView?, today: LocalDate, onPay: (String) -> Unit, onOpenMessage: (Long) -> Unit) {
    if (view != null) {
        val tone = BillsMath.tone(view.date, today)
        val toneColor = when (tone) {
            DueTone.OVERDUE -> AppColors.danger
            DueTone.TODAY, DueTone.SOON -> AppColors.warning
            DueTone.LATER -> mutedColor()
        }
        val message = view.message
        FintechCard(onClick = { onOpenMessage(message.id) }) {
            Text(
                BillsMath.dueCaption(view.date, today).uppercase(), Modifier.heading(), style = TypeRole.section, color = toneColor,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                MerchantChip(
                    message.merchant.logo, imageKey = message.merchant.imageKey, name = message.merchant.name,
                    originalName = message.merchant.originalName, description = view.title, size = RowTile
                )
                Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                    RowTitle(view.title)
                    Caption(view.kind)
                }
                RowAmount(DisplayFormatters.rupees(view.amount), MaterialTheme.colorScheme.secondary)
            }
            val link = view.payLink
            ResponsiveRow(Modifier.fillMaxWidth().padding(top = Spacing.md), spacing = Spacing.sm) {
                if (link != null) AppButton("Pay bill", { onPay(link) }, Modifier.cell(), size = ButtonSize.COMPACT)
                AppButton(
                    "Mark as paid", view.onMarkPaid, Modifier.cell(),
                    style = if (link != null) ButtonStyle.OUTLINE else ButtonStyle.PRIMARY, size = ButtonSize.COMPACT
                )
            }
        }
    }
}

@Composable
private fun ClearFocus(nudge: Nudge?, onOpenMessage: (Long) -> Unit) {
    val messageId = nudge?.messageId
    FintechCard(onClick = if (messageId != null) { { onOpenMessage(messageId) } } else null) {
        if (nudge != null) {
            // A pacing or balance heads-up is the one thing to read when nothing else is waiting.
            val accent = if (nudge.kind != NudgeKind.PACE) AppColors.danger else MaterialTheme.colorScheme.primary
            Caption("FIRST THING", color = accent)
            Text(
                nudge.text, Modifier.padding(top = Spacing.xs), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary
            )
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.baseline_check_24), contentDescription = null, modifier = Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(AppColors.success)
                )
                Column(Modifier.padding(start = Spacing.md)) {
                    RowTitle("You're all clear")
                    Caption("Nothing needs you right now.", maxLines = 2)
                }
            }
        }
    }
}

/**
 * Today's budget as one slim line: what is left, a thin bar for how much is spent, and Adjust. Tapping the line opens the streak
 * calendar. Without a budget it says so and offers Adjust. Amounts count up once on Home's first showing and stay hidden when hidden.
 */
@Composable
internal fun TodayBudgetRow(daily: DailyBudgetStatus?, onOpen: () -> Unit, onAdjust: () -> Unit, intro: Boolean, modifier: Modifier = Modifier) {
    val stacked = LocalResponsive.current.stackRows || LocalResponsive.current.fontScale >= 1.5f
    Column(
        modifier.fillMaxWidth().tappable(onClick = if (daily != null) onOpen else onAdjust, onClickLabel = if (daily != null) "Open budget streak" else "Set a daily budget")
    ) {
        val label: @Composable (Modifier) -> Unit = { m ->
            if (daily != null) {
                val over = daily.isOver
                val color = if (over) AppColors.danger else AppColors.success
                Row(m, verticalAlignment = Alignment.Bottom) {
                    AnimatedAmount(
                        if (over) daily.leftToday.negate() else daily.leftToday,
                        style = TypeRole.title, fontWeight = FontWeight.SemiBold, color = color, fromZero = intro
                    )
                    Text(if (over) " over today" else " left today", style = TypeRole.body, color = mutedColor(), maxLines = 1)
                }
            } else {
                Text("No daily budget yet", m, style = TypeRole.body, color = mutedColor(), maxLines = 2)
            }
        }
        val adjust: @Composable () -> Unit = { AppButton("Adjust", onAdjust, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
        if (stacked) {
            label(Modifier.fillMaxWidth())
            adjust()
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                label(Modifier.weight(1f))
                adjust()
            }
        }
        if (daily != null) {
            val over = daily.isOver
            ShareBar(MotionMath.share(daily.spentToday, daily.allowance), color = if (over) AppColors.danger else AppColors.success)
        }
    }
}

/** The last [rows] payments, one card of rows, with "See all" in the header that opens Messages. */
@Composable
internal fun RecentActivity(
    recent: List<ClassifiedMessage>, onOpen: (Long) -> Unit, onSeeAll: () -> Unit, modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("RECENT ACTIVITY", Modifier.weight(1f).heading(), style = TypeRole.section, color = mutedColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinkButton("See all", onSeeAll)
        }
        FintechCard {
            recent.forEachIndexed { index, message ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                RecentRow(message, onOpen = { onOpen(message.id) })
            }
        }
    }
}

/** The single footer of Home: a quiet text button that opens Customize Home. */
@Composable
internal fun HomeFooter(onCustomize: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        AppButton("Customize Home", onCustomize, style = ButtonStyle.TEXT)
    }
}
