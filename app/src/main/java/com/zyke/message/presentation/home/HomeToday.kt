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
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.motion.MotionMath
import com.zyke.message.presentation.common.fullWidthClickable
import com.zyke.message.presentation.common.scaledLines
import com.zyke.message.presentation.messages.components.PaymentInstrumentCaption
import com.zyke.message.presentation.messages.components.rememberInstrument
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.ui.unit.Dp
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
import com.zyke.message.presentation.format.displayName
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
        val stacked = homeStacked()
        val ink = MaterialTheme.colorScheme.onSurface
        HomeCard(onClick = { onOpenMessage(message.id) }, onClickLabel = "Open the bill's message") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HomeLabel("Today", Modifier.weight(1f))
                Spacer(Modifier.width(Spacing.sm))
                HomePill(BillsMath.dueCaption(view.date, today), toneColor, dot = true)
            }
            Row(Modifier.fillMaxWidth().padding(top = Spacing.lg), verticalAlignment = if (stacked) Alignment.Top else Alignment.CenterVertically) {
                MerchantChip(
                    message.merchant.logo, imageKey = message.merchant.imageKey, name = message.merchant.name,
                    originalName = message.merchant.originalName, description = view.title, size = RowTile
                )
                Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                    Text(
                        view.title, style = TypeRole.body, fontWeight = FontWeight.Medium, color = ink,
                        maxLines = if (stacked) 3 else 1, overflow = TextOverflow.Ellipsis
                    )
                    Caption("${view.kind} \u00B7 ${homeDate(view.date)}", maxLines = 2)
                    if (stacked) RowAmount(DisplayFormatters.rupees(view.amount), ink, Modifier.padding(top = Spacing.xs))
                }
                if (!stacked) {
                    Spacer(Modifier.width(Spacing.md))
                    RowAmount(DisplayFormatters.rupees(view.amount), ink)
                }
            }
            val link = view.payLink
            // One primary action. "Mark as paid" steps down to a text button when there is a Pay link, and is the primary when there is not.
            ResponsiveRow(Modifier.fillMaxWidth().padding(top = Spacing.lg), spacing = Spacing.sm) {
                if (link != null) {
                    AppButton("Pay bill", { onPay(link) }, Modifier.cell())
                    AppButton("Mark as paid", view.onMarkPaid, Modifier.cell(), style = ButtonStyle.TEXT)
                } else {
                    AppButton("Mark as paid", view.onMarkPaid, Modifier.cell())
                }
            }
        }
    }
}

@Composable
private fun ClearFocus(nudge: Nudge?, onOpenMessage: (Long) -> Unit) {
    val messageId = nudge?.messageId
    val ink = MaterialTheme.colorScheme.onSurface
    HomeCard(onClick = if (messageId != null) { { onOpenMessage(messageId) } } else null, onClickLabel = if (messageId != null) "Open the message" else null) {
        if (nudge != null) {
            // A pacing or balance heads-up is the one thing to read when nothing else is waiting.
            val accent = if (nudge.kind != NudgeKind.PACE) AppColors.danger else mutedColor()
            HomeLabel("First thing", color = accent)
            Text(nudge.text, Modifier.padding(top = Spacing.sm), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, color = ink)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(Spacing.xxxl + Spacing.sm).background(AppColors.success.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(R.drawable.baseline_check_24), contentDescription = null, modifier = Modifier.size(20.dp),
                        colorFilter = ColorFilter.tint(AppColors.success)
                    )
                }
                Column(Modifier.padding(start = Spacing.md)) {
                    Text("You're all clear", style = TypeRole.body, fontWeight = FontWeight.Medium, color = ink, maxLines = 2)
                    Caption("Nothing needs you right now.", maxLines = 2)
                }
            }
        }
    }
}

/**
 * Today's budget as a progress element in a slim card: a small label, what is left (large, in the text colour; red when over), "of" the
 * day's allowance, a rounded 8 dp bar for how much is spent, and Adjust. Tapping the card opens the streak calendar. Without a budget it
 * says so and offers Adjust. Amounts count up once on Home's first showing and stay hidden when hidden.
 */
@Composable
internal fun TodayBudgetRow(daily: DailyBudgetStatus?, onOpen: () -> Unit, onAdjust: () -> Unit, intro: Boolean, modifier: Modifier = Modifier) {
    val stacked = homeStacked()
    HomeCard(
        modifier, onClick = if (daily != null) onOpen else onAdjust, onClickLabel = if (daily != null) "Open budget streak" else "Set a daily budget",
        vertical = Spacing.lg
    ) {
        val head: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                HomeLabel("Today's budget")
                if (daily != null) {
                    val over = daily.isOver
                    val figure: @Composable () -> Unit = {
                        AnimatedAmount(
                            if (over) daily.leftToday.negate() else daily.leftToday,
                            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.Bold,
                            color = if (over) AppColors.danger else MaterialTheme.colorScheme.onSurface, fromZero = intro
                        )
                    }
                    val note = if (over) "over today" else "left of ${DisplayFormatters.rupees(daily.allowance)}"
                    if (stacked) {
                        Column(Modifier.padding(top = Spacing.xs)) {
                            figure()
                            Text(note, style = TypeRole.body, color = mutedColor(), maxLines = 2)
                        }
                    } else {
                        Row(Modifier.padding(top = Spacing.xs), verticalAlignment = Alignment.Bottom) {
                            figure()
                            Text(note, Modifier.padding(start = Spacing.sm, bottom = Spacing.xs), style = TypeRole.body, color = mutedColor(), maxLines = 1)
                        }
                    }
                } else {
                    Text("No daily budget yet", Modifier.padding(top = Spacing.xs), style = TypeRole.body, color = mutedColor(), maxLines = 2)
                }
            }
        }
        val adjust: @Composable () -> Unit = { AppButton("Adjust", onAdjust, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
        if (stacked) {
            head(Modifier.fillMaxWidth())
            adjust()
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                head(Modifier.weight(1f))
                adjust()
            }
        }
        if (daily != null) {
            HomeProgress(
                MotionMath.share(daily.spentToday, daily.allowance), if (daily.isOver) AppColors.danger else AppColors.success,
                Modifier.padding(top = Spacing.sm)
            )
        }
    }
}

/** The last payments as one grouped card of rows with hairlines between them, under a small label with "See all" that opens Messages. */
@Composable
internal fun RecentActivity(
    recent: List<ClassifiedMessage>, onOpen: (Long) -> Unit, onSeeAll: () -> Unit, modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HomeLabel("Recent activity", Modifier.weight(1f))
            LinkButton("See all", onSeeAll)
        }
        HomeCard(vertical = Spacing.xs) {
            recent.forEachIndexed { index, message ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = ActivityTile + Spacing.md), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                HomeActivityRow(message, onOpen = { onOpen(message.id) })
            }
        }
    }
}

private val ActivityTile: Dp = 40.dp

/** One payment: a 40 dp tile, who and how (the Messages row anatomy), and the amount; received money in the success colour, money out in the text colour. */
@Composable
private fun HomeActivityRow(message: ClassifiedMessage, onOpen: () -> Unit) {
    val tx = message.content as? MessageContent.Transaction
    if (tx != null) {
        val stacked = homeStacked()
        val received = tx.isReceived
        val amount = (if (received) "+ " else "\u2212 ") + DisplayFormatters.rupees(tx.amount)
        val color = if (received) AppColors.success else MaterialTheme.colorScheme.onSurface
        Row(
            Modifier.fillMaxWidth().fullWidthClickable(onClickLabel = "Open details", onClick = onOpen).padding(vertical = Spacing.md),
            verticalAlignment = if (stacked) Alignment.Top else Alignment.CenterVertically
        ) {
            MerchantChip(
                message.merchant.logo, imageKey = message.merchant.imageKey, name = message.merchant.name,
                originalName = message.merchant.originalName, description = message.merchant.name, size = ActivityTile
            )
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    message.merchant.displayName, style = TypeRole.body, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = scaledLines(1), overflow = TextOverflow.Ellipsis
                )
                PaymentInstrumentCaption(
                    instrument = rememberInstrument(message), last4 = tx.accountNumber, rest = DisplayFormatters.listStamp(message.message.date)
                )
                if (stacked) RowAmount(amount, color, Modifier.padding(top = Spacing.xs))
            }
            if (!stacked) {
                Spacer(Modifier.width(Spacing.md))
                RowAmount(amount, color)
            }
        }
    }
}

/** The single footer of Home: a quiet text button that opens Customize Home. */
@Composable
internal fun HomeFooter(onCustomize: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.Center) {
        AppButton("Customize Home", onCustomize, style = ButtonStyle.TEXT)
    }
}
