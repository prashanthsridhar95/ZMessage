package com.zyke.message

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.Indication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.BottomNavigation
import androidx.compose.material.BottomNavigationItem
import androidx.compose.material.ripple
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.zyke.message.datalayer.BankMessagesProcessor
import com.zyke.message.datalayer.FastTagMessageProcessor
import com.zyke.message.datalayer.MessagesHandler
import com.zyke.message.datalayer.NetworkCarrier
import com.zyke.message.datalayer.ZCategory
import com.zyke.message.datalayer.ZMessage
import com.zyke.message.model.BankName
import com.zyke.message.model.BankTransactionType
import com.zyke.message.model.BankTransactionType.Companion.isReceived
import com.zyke.message.model.ZBankMessage
import com.zyke.message.model.ZFastTagMessage
import com.zyke.message.ui.theme.ZykeMessageTheme
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {
    private val REQUEST_CODE_READ_SMS = 123
    private val messages = mutableStateOf(listOf<ZMessage>())

    private val showFilters = mutableStateOf(false)
    private val selectedFilter = mutableStateOf<ZCategory?>(null)

    @OptIn(ExperimentalFoundationApi::class)
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkAndRequestSmsPermission()

        val filteredList = arrayListOf<ZMessage>()

        setContent {
            val scrollState = rememberLazyListState()

            ZykeMessageTheme {
                Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
                    TopNavigationBar()
                }, bottomBar = {
                    BottomNavigationBar()
                }) { topPadding ->
                    LazyColumn(
                        Modifier.padding(
                            top = topPadding.calculateTopPadding(),
                            bottom = topPadding.calculateBottomPadding()
                        ), state = scrollState
                    ) {
                        val networkCarrierRegex =
                            """(\d+)% Alert: You have consumed \1% of your daily high Speed Data limit\. Track usage on Airtel Thanks App i\.airtel\.in/Get-Data""".toRegex()

                        if (showFilters.value) {
                            stickyHeader {
                                FiltersView(Modifier.animateItemPlacement())
                            }
                        }

                        groupItemsByDay(messages.value.sortedByDescending { it.date }
                            .distinctBy { "${it.messageContent} ${formatDateWithTime(it.date)}" }).forEach {
                            stickyHeader {
                                Column(
                                    Modifier
                                        .animateItemPlacement()
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface)
                                ) {
                                    Text(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                start = 16.dp,
                                                end = 16.dp,
                                                top = 16.dp,
                                                bottom = 8.dp
                                            ),
                                        text = formatDateWithTimeForHeader(it.value.first().date),
                                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            it.value.forEach {
                                item {
                                    val isExpanded = remember {
                                        mutableStateOf(false)
                                    }

                                    if (it.messageContent.contains(
                                            "One-Time Password", true
                                        ) || it.messageContent.contains(
                                            "one time password", true
                                        ) || it.messageContent.contains(
                                            "Verification Code", true
                                        ) || it.messageContent.contains(
                                            "one time password", true
                                        ) || it.messageContent.contains(
                                            "Delivery Code", true
                                        ) || it.messageContent.contains(
                                            "Happy Code", true
                                        ) || it.messageContent.contains(
                                            "Share Code", true
                                        ) || it.messageContent.contains(
                                            "Verification code", true
                                        ) || it.messageContent.contains("OTP", true)
                                    ) {
                                        if (isExpanded.value) {
                                            MessageDetail(
                                                modifier = Modifier.animateItemPlacement(),
                                                isExpanded,
                                                it,
                                                it.sender,
                                                null
                                            ) {
                                                isExpanded.value = !isExpanded.value
                                            }
                                        } else {
                                            OTPMessages(
                                                Modifier.animateItemPlacement(),
                                                it.messageContent,
                                                it.sender
                                            ) {
                                                isExpanded.value = !isExpanded.value
                                            }
                                        }
                                    }
                                    else if(it.messageContent.contains("Bank Tag", true) || it.messageContent.contains("Fastag", true))
                                    {
                                        if (isExpanded.value) {
                                            MessageDetail(
                                                Modifier.animateItemPlacement(),
                                                isExpanded,
                                                it,
                                                it.sender,
                                                null
                                            ) {
                                                isExpanded.value = !isExpanded.value
                                            }
                                        } else {
                                            FastTagMessages(Modifier.animateItemPlacement(), it) {
                                                isExpanded.value = !isExpanded.value
                                            }
                                        }
                                    }
                                    else if (it.messageContent.contains("INR") || it.messageContent.contains(
                                            "Rs."
                                        ) || it.messageContent.contains(
                                            "Rs "
                                        )
                                    ) {
                                        val bankMessage =
                                            BankMessagesProcessor().processMessage(it.messageContent)

                                        if (isExpanded.value) {
                                            if(bankMessage != null) {
                                                MessageDetail(
                                                    Modifier.animateItemPlacement(),
                                                    isExpanded,
                                                    it,
                                                    bankMessage.payee,
                                                    bankMessage.bankName
                                                ) {
                                                    isExpanded.value = !isExpanded.value
                                                }
                                            }
                                            else
                                            {
                                                MessageDetail(
                                                    Modifier.animateItemPlacement(),
                                                    isExpanded,
                                                    it,
                                                    it.sender,
                                                    null
                                                ) {
                                                    isExpanded.value = !isExpanded.value
                                                }
                                            }
                                        } else {
                                            if(bankMessage != null)
                                            {
                                                when (bankMessage.transactionType) {
                                                    BankTransactionType.CARD_BILL -> {
                                                        CardBillMessages(modifier = Modifier
                                                            .animateItemPlacement()
                                                            .fillMaxWidth(),
                                                            bankMessage = bankMessage,
                                                            unhandledMessage = {
                                                                OtherMessages(
                                                                    Modifier.animateItemPlacement(), it
                                                                ) {
                                                                    isExpanded.value = !isExpanded.value
                                                                }
                                                            }) {
                                                            isExpanded.value = !isExpanded.value
                                                        }
                                                    }

                                                    else -> {
                                                        BankMessages(modifier = Modifier
                                                            .animateItemPlacement()
                                                            .fillMaxWidth(),
                                                            bankMessage = bankMessage,
                                                            isCCMessage = it.messageContent.contains(
                                                                "Card", true
                                                            ),
                                                            unhandledMessage = {
                                                                OtherMessages(
                                                                    modifier = Modifier.animateItemPlacement(),
                                                                    it
                                                                ) {
                                                                    isExpanded.value = !isExpanded.value
                                                                }
                                                            }) {
                                                            isExpanded.value = !isExpanded.value
                                                        }
                                                    }
                                                }
                                            }
                                            else
                                            {
                                                OtherMessages(
                                                    modifier = Modifier.animateItemPlacement(),
                                                    it
                                                ) {
                                                    isExpanded.value = !isExpanded.value
                                                }
                                            }
                                        }
                                    } else if (Pattern.matches(
                                            networkCarrierRegex.pattern, it.messageContent
                                        )
                                    ) {
                                        if (selectedFilter.value == null || selectedFilter.value == ZCategory.NETWORK_CARRIERS) {
                                            if (isExpanded.value) {
                                                MessageDetail(
                                                    Modifier.animateItemPlacement(),
                                                    isExpanded,
                                                    it,
                                                    it.sender,
                                                    null
                                                ) {
                                                    isExpanded.value = !isExpanded.value
                                                }
                                            } else {
                                                NetworkUsageView(
                                                    Modifier.animateItemPlacement(),
                                                    NetworkCarrier.AIRTEL,
                                                    networkCarrierRegex.find(it.messageContent)?.groupValues?.get(
                                                        1
                                                    )?.toInt() ?: 0
                                                ) {
                                                    isExpanded.value = !isExpanded.value
                                                }
                                            }
                                        }
                                    } else if (it.messageContent.contains("Blue Dart")) {
                                        if (selectedFilter.value == null || selectedFilter.value == ZCategory.COURIER) {
                                            if (isExpanded.value) {
                                                MessageDetail(
                                                    Modifier.animateItemPlacement(),
                                                    isExpanded,
                                                    it,
                                                    it.sender,
                                                    null
                                                ) {
                                                    isExpanded.value = !isExpanded.value
                                                }
                                            } else {
                                                BlueDartMessages(
                                                    Modifier.animateItemPlacement(), it
                                                ) {
                                                    isExpanded.value = !isExpanded.value
                                                }
                                            }
                                        }
                                    } else if (it.messageContent.contains("Petpooja")) {
                                        if (selectedFilter.value == null || selectedFilter.value == ZCategory.LOYALTY_PROGRAMMES) {
                                            if (isExpanded.value) {
                                                MessageDetail(
                                                    Modifier.animateItemPlacement(),
                                                    isExpanded,
                                                    it,
                                                    it.sender,
                                                    null
                                                ) {
                                                    isExpanded.value = !isExpanded.value
                                                }
                                            } else {
                                                LoyaltyProgrammeMessages(it) {
                                                    isExpanded.value = !isExpanded.value
                                                }
                                            }
                                        }
                                    }
                                    else {
                                        if (selectedFilter.value == null || selectedFilter.value == ZCategory.OTHERS) {
                                            if (isExpanded.value) {
                                                MessageDetail(
                                                    Modifier.animateItemPlacement(),
                                                    isExpanded,
                                                    it,
                                                    it.sender,
                                                    null
                                                ) {
                                                    isExpanded.value = !isExpanded.value
                                                }
                                            } else {
                                                OtherMessages(Modifier.animateItemPlacement(), it) {
                                                    isExpanded.value = !isExpanded.value
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun FastTagMessages(modifier: Modifier, message: ZMessage, onClick: () -> Unit) {
        val fastTagMessage = FastTagMessageProcessor.processMessage(message.messageContent)

        if(fastTagMessage != null)
        {
            Row(modifier
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                checkAndDisplayMerchantChipView(ZCategory.FASTAG.displayString, ZCategory.FASTAG)

                Spacer(Modifier.padding(start = 16.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(0.6f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier.border(0.5.dp, MaterialTheme.colorScheme.secondary)
                    ) {
                        Text(
                            modifier = Modifier.padding(
                                horizontal = 8.dp
                            ),
                            text = fastTagMessage.vehicleNumber,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    CategoryDisplay(category = ZCategory.FASTAG)
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    when(fastTagMessage)
                    {
                        is ZFastTagMessage.ActiveNote -> {
                            Text(
                                modifier = Modifier.fillMaxWidth(),
                                text = "ACTIVE",
                                textAlign = TextAlign.End,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        is ZFastTagMessage.RechargeNote -> {
                            Text(
                                modifier = Modifier.fillMaxWidth(),
                                text = "RECHARGE NOW",
                                textAlign = TextAlign.End,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        is ZFastTagMessage.ToppedUp -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    modifier = Modifier.fillMaxWidth(),
                                    text = "+ ₹${fastTagMessage.amount}",
                                    textAlign = TextAlign.End,
                                    fontSize = 24.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Image(
                                        modifier = Modifier.size(18.dp),
                                        painter = painterResource(R.drawable.wallet_balance),
                                        contentDescription = "",
                                        colorFilter = ColorFilter.tint(
                                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                                        )
                                    )

                                    Spacer(Modifier.padding(end = 4.dp))

                                    Text(
                                        text = "₹${fastTagMessage.balance}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.End,
                                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }
                        is ZFastTagMessage.TollPaid -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    modifier = Modifier.fillMaxWidth(),
                                    text = "- ₹${fastTagMessage.tollAmount}",
                                    textAlign = TextAlign.End,
                                    fontSize = 24.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.secondary
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Image(
                                        modifier = Modifier.size(18.dp),
                                        painter = painterResource(R.drawable.wallet_balance),
                                        contentDescription = "",
                                        colorFilter = ColorFilter.tint(
                                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                                        )
                                    )

                                    Spacer(Modifier.padding(end = 4.dp))

                                    Text(
                                        text = "₹${fastTagMessage.balance}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.End,
                                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        else
        {
            OtherMessages(modifier, message, onClick)
        }
    }

    @Composable
    fun FiltersView(modifier: Modifier = Modifier) {
        @Composable
        fun Modifier.applyFilter(view: ZCategory?) =
            if (selectedFilter.value == view) this.background(
                color = MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)
            ) else this.border(
                0.5.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                RoundedCornerShape(12.dp)
            )

        Column {
            LazyRow(
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp),
                modifier = modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(top = 8.dp, bottom = 8.dp)
                    .fillMaxWidth()
            ) {
                item {
                    Box(Modifier
                        .padding(horizontal = 4.dp)
                        .clickable { selectedFilter.value = null }
                        .height(36.dp)
                        .applyFilter(null)
                        .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center) {
                        Text(
                            text = "All",
                            fontSize = 14.sp,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                            color = if (selectedFilter.value == null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary.copy(
                                alpha = 0.5f
                            )
                        )
                    }
                }
                items(ZCategory.values()) {
                    Box(modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clickable { selectedFilter.value = it }
                        .height(36.dp)
                        .applyFilter(it)
                        .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = it.displayString,
                            fontSize = 14.sp,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                            color = if (selectedFilter.value == it) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary.copy(
                                alpha = 0.5f
                            )
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(horizontal = 8.dp))
        }
    }

    @Composable
    fun CustomLottieAnimation(modifier: Modifier = Modifier) {
        val composition by rememberLottieComposition(LottieCompositionSpec.Asset("delivery_night.json"))
        val progress by animateLottieCompositionAsState(
            composition, iterations = LottieConstants.IterateForever
        )

        LottieAnimation(
            composition = composition, progress = { progress }, modifier = modifier
        )
    }

    @Composable
    fun LoyaltyProgrammeMessages(message: ZMessage, onClick: () -> Unit) {
        when {
            message.sender.contains("PPOOJA") -> {
                val regex =
                    "Loyalty wallet for (.*?) is loaded with (\\d+\\.\\d{2}) points.*Current wallet balance: (\\d+\\.\\d{2})".toRegex()
                val matchResult = regex.find(message.messageContent)
                val isLoaded = message.messageContent.contains("loaded")

                Row(Modifier
                    .clickable { onClick() }
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val displayString = checkAndDisplayMerchantChipView(
                        matchResult!!.groupValues[1], ZCategory.LOYALTY_PROGRAMMES
                    )

                    if (displayString != null) {
                        Spacer(Modifier.padding(start = 16.dp))

                        Column(
                            modifier = Modifier.fillMaxWidth(0.6f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = displayString.first.lowercase(
                                    Locale.ROOT
                                ).split(" ")
                                    .joinToString(separator = " ") { checkAndCapitalize(it) },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.primary
                            )

                            CategoryDisplay(category = displayString.second)
                        }

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                modifier = Modifier.fillMaxWidth(),
                                text = "${if (isLoaded) "+" else "-"} ₹${matchResult!!.groupValues[2]}",
                                textAlign = TextAlign.End,
                                fontSize = 24.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (isLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(
                                    modifier = Modifier.size(18.dp),
                                    painter = painterResource(R.drawable.wallet_balance),
                                    contentDescription = "",
                                    colorFilter = ColorFilter.tint(
                                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                                    )
                                )

                                Spacer(Modifier.padding(end = 4.dp))

                                Text(
                                    text = "₹${matchResult.groupValues[3]}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.End,
                                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                OtherMessages(modifier = Modifier, message = message, onClick = onClick)
            }
        }
    }

    @Composable
    fun TopNavigationBar(modifier: Modifier = Modifier) {
        val ripple = remember {
            ripple(bounded = false)
        }
        val interactionSource = remember { MutableInteractionSource() }

        Column(
            Modifier.padding(top = getStatusBarHeight())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    modifier = Modifier
                        .height(36.dp)
                        .padding(horizontal = 16.dp),
                    painter = painterResource(id = R.drawable.zyke_main),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                    contentDescription = ""
                )

                Image(
                    modifier = Modifier
                        .clickable(
                            indication = ripple,
                            interactionSource = interactionSource,
                            onClick = {
                                showFilters.value = !showFilters.value
                                if (showFilters.value.not()) {
                                    selectedFilter.value = null
                                }
                            })
                        .height(36.dp)
                        .padding(horizontal = 16.dp),
                    painter = painterResource(id = R.drawable.baseline_filter_alt_24),
                    colorFilter = if (showFilters.value) ColorFilter.tint(MaterialTheme.colorScheme.primary) else ColorFilter.tint(
                        MaterialTheme.colorScheme.secondary
                    ),
                    contentDescription = "Filter"
                )
            }

            HorizontalDivider(
                Modifier
                    .padding(top = 8.dp)
                    .padding(horizontal = 8.dp)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }

    @Composable
    fun getStatusBarHeight(): Dp {
        val context = LocalContext.current
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            with(LocalDensity.current) {
                context.resources.getDimensionPixelSize(resourceId).toDp()
            }
        } else {
            // If the resource is not found, return 0
            0.dp
        }
    }

    @Composable
    fun BottomNavigationBar(modifier: Modifier = Modifier) {
        Box(contentAlignment = Alignment.TopCenter) {
            BottomNavigation(
                backgroundColor = MaterialTheme.colorScheme.surface
            ) {
                BottomNavigationItem(modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                    icon = {
                        Image(
                            painter = painterResource(R.drawable.baseline_message_24),
                            contentDescription = "",
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                        )
                    },
                    label = { Text("Messages", color = MaterialTheme.colorScheme.primary) },
                    selected = true,
                    onClick = { })
            }

            HorizontalDivider(
                Modifier
                    .padding(horizontal = 8.dp)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }

    @Composable
    fun MessageDetail(
        modifier: Modifier,
        isExpanded: MutableState<Boolean>,
        message: ZMessage,
        payee: String,
        bankName: BankName?,
        onClick: () -> Unit
    ) {
        AnimatedVisibility(
            visible = isExpanded.value, enter = expandVertically(
                expandFrom = Alignment.Top, animationSpec = tween(300)
            ) + fadeIn(
                animationSpec = tween(300)
            ), exit = shrinkVertically(
                shrinkTowards = Alignment.Top, animationSpec = tween(300)
            ) + fadeOut(
                animationSpec = tween(300)
            )
        ) {
            Column(
                modifier = modifier.clickable { onClick() },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )

                Spacer(Modifier.padding(top = 16.dp))

                val displayString =
                    checkAndDisplayMerchantChipView("${payee} ${message.messageContent}".ifEmpty { bankName?.displayString ?: "" })

                if (displayString != null) {
                    Spacer(Modifier.padding(start = 16.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(0.6f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (payee.isNotEmpty() && payee != message.sender) {
                            Text(
                                text = if (displayString.second == ZCategory.UPI_PAYMENT) displayString.first else displayString.first.lowercase(
                                    Locale.ROOT
                                ).split(" ")
                                    .joinToString(separator = " ") { checkAndCapitalize(it) },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Text(
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .border(
                                        0.5.dp,
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                        RoundedCornerShape(16.dp)
                                    )
                                    .padding(horizontal = 8.dp),
                                text = message.sender,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                            )
                        } else {
                            Text(
                                text = message.sender.uppercase(Locale.ROOT),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(Modifier.padding(top = 8.dp))

                    Text(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth(),
                        text = message.messageContent
                    )

                    Text(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(top = 16.dp)
                            .fillMaxWidth(),
                        text = formatDateWithTimeForDetail(message.date),
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
                        textAlign = TextAlign.End
                    )

                    Spacer(Modifier.padding(top = 8.dp, bottom = 16.dp))

                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun groupItemsByDay(items: List<ZMessage>): Map<LocalDate, List<ZMessage>> {
        val groupedItems = mutableMapOf<LocalDate, MutableList<ZMessage>>()

        for (item in items) {
            val date = convertLongToLocalDate(item.date)

            if (groupedItems.containsKey(date)) {
                groupedItems[date]?.add(item)
            } else {
                groupedItems[date] = mutableListOf(item)
            }
        }

        return groupedItems
    }


    @RequiresApi(Build.VERSION_CODES.O)
    private fun convertLongToLocalDate(dateLong: Long): LocalDate {
        return Instant.ofEpochMilli(dateLong)
            .atZone(ZoneId.systemDefault()) // Or specify a timezone if needed
            .toLocalDate()
    }

    @Composable
    fun OtherMessages(modifier: Modifier, message: ZMessage, onClick: () -> Unit) {
        Row(modifier
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .fillMaxWidth(), verticalAlignment = Alignment.Top) {
            val displayText =
                checkAndDisplayMerchantChipView("${message.sender} ${message.messageContent}")

            if (displayText != null) {
                Spacer(Modifier.padding(start = 16.dp))

                Column {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = message.sender,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (displayText.second != ZCategory.OTHERS) {
                        CategoryDisplay(category = displayText.second)
                    }

                    Text(
                        modifier = Modifier
                            .padding(top = if (displayText.second != ZCategory.OTHERS) 8.dp else 4.dp)
                            .fillMaxWidth(),
                        text = message.messageContent,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }

    @Composable
    fun BlueDartMessages(modifier: Modifier = Modifier, message: ZMessage, onClick: () -> Unit) {
        Row(modifier
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val displayText =
                checkAndDisplayMerchantChipView("${message.sender} ${message.messageContent}")

            if (displayText != null) {
                Spacer(Modifier.padding(start = 16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.fillMaxWidth(0.7f)) {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = message.sender,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.primary
                        )

                        CategoryDisplay(category = ZCategory.COURIER)
                    }

                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (displayText.second != ZCategory.OTHERS) 8.dp else 4.dp),
                        text = if (message.messageContent.contains(
                                "ARRIVING", true
                            ) && message.messageContent.contains("TODAY", true)
                        ) "Arriving\nToday" else if (message.messageContent.contains(
                                "Delivered", true
                            )
                        ) "Delivered" else "Update",
                        maxLines = 2,
                        textAlign = TextAlign.End,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }

    @Composable
    fun OTPMessages(
        modifier: Modifier = Modifier,
        messageContent: String,
        messageSender: String,
        onClick: () -> Unit
    ) {
        if (selectedFilter.value == null || selectedFilter.value == ZCategory.OTP) {
            val otpRegex = if (messageContent.contains(
                    "Simpl", true
                )
            ) "\\b([A-Z0-9]{10})\\b".toRegex() else "(?:[^\\d]|^)(\\d{4,7})(?:[^\\d]|$)".toRegex()
            val senderRegex =
                "(?:[A-Za-z]+(?: Bank)?|\\b[A-Z]{3,}\\b|\\d{10})".toRegex(RegexOption.IGNORE_CASE) // Bank name, short codes, or even phone numbers
            val amountRegex =
                Regex("INR ([0-9,]+)\\.00") // Matches "INR" followed by digits and commas, then ".00"
            val cardNumberRegex = Regex("ending (\\d+)") // Matches "ending " followed by digits

            val otp = otpRegex.find(messageContent)?.value?.trim()
                ?.replace("[,./<>?;':\"\\[\\]{}|`~!@#$%^&*()\\-_=+]".toRegex(), "") ?: ""
            val sender =
                if (messageContent.contains("Bank")) senderRegex.find(messageContent)?.value?.trim() else messageSender
            val cardNumber =
                cardNumberRegex.find(messageContent)?.value?.split("ending")?.get(1)?.trim()
            val amount =
                amountRegex.find(messageContent)?.groupValues?.getOrNull(1)?.replace(",", "")
                    ?.trim()

            val context = LocalContext.current

            val clipboardManager =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            val copyText: () -> Unit = {
                val clip = ClipData.newPlainText("One-time Password", otp)
                clipboardManager.setPrimaryClip(clip)
                Toast.makeText(context, "OTP copied to clipboard", Toast.LENGTH_SHORT).show()
            }

            val ripple = remember {
                ripple(bounded = false)
            }
            val interactionSource = remember { MutableInteractionSource() }

            Column(Modifier
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.Top) {
                    val displayText = checkAndDisplayMerchantChipView("${sender} ${messageContent}")

                    if (displayText != null) {
                        val merchantName = if (displayText.first == "${sender} ${messageContent}") {
                            sender ?: ""
                        } else displayText.first.ifEmpty { sender ?: "" }

                        Spacer(Modifier.padding(start = 16.dp))

                        Column(verticalArrangement = Arrangement.Center) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (otp.length > 8) {
                                    Box(
                                        Modifier.border(0.5.dp, MaterialTheme.colorScheme.secondary)
                                    ) {
                                        Text(
                                            modifier = Modifier.padding(
                                                horizontal = 8.dp
                                            ),
                                            text = otp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 28.sp,
                                            textAlign = TextAlign.Center,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                } else {
                                    Row {
                                        otp.toCharArray().forEachIndexed { index, itt ->
                                            Box(
                                                modifier = Modifier
                                                    .padding(
                                                        start = if (index == 0) 0.dp else 4.dp,
                                                        end = if (index == otp.length - 1) 0.dp else 4.dp
                                                    )
                                                    .width(32.dp)
                                                    .height(40.dp)
                                                    .border(
                                                        0.5.dp, MaterialTheme.colorScheme.secondary
                                                    ), contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = itt.toString(),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontSize = 24.sp,
                                                    textAlign = TextAlign.Center,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.padding(start = 16.dp))

                                Column(
                                    modifier = Modifier.clickable(interactionSource,
                                        ripple,
                                        onClick = { copyText() }),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Image(
                                        modifier = Modifier.size(16.dp),
                                        painter = painterResource(R.drawable.baseline_content_copy_24),
                                        contentDescription = "",
                                        colorFilter = ColorFilter.tint(
                                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                                        )
                                    )

                                    Text(
                                        text = "Copy",
                                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
                                        fontSize = 10.sp
                                    )
                                }
                            }

                            Row(
                                Modifier
                                    .padding(top = 4.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                                    text = merchantName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                CategoryDisplay(category = ZCategory.OTP)
                            }

                            if (otp.isEmpty()) {
                                Text(
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .fillMaxWidth(),
                                    text = messageContent,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }

                            if (cardNumber != null && amount != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "For payment of ₹${
                                            if (amount.endsWith(".00") || amount.endsWith(
                                                    ".0"
                                                )
                                            ) amount.split(".")[0] else amount
                                        }",
                                        fontSize = 14.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Image(
                                            modifier = Modifier.size(18.dp),
                                            painter = painterResource(R.drawable.credit_card),
                                            contentDescription = "",
                                            colorFilter = ColorFilter.tint(
                                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                                            )
                                        )

                                        Spacer(Modifier.padding(end = 4.dp))

                                        Text(
                                            text = "•• $cardNumber",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.End,
                                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                                        )
                                    }
                                }
                            } else {
                                Spacer(Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun BankMessages(
        modifier: Modifier = Modifier,
        bankMessage: ZBankMessage,
        isCCMessage: Boolean,
        unhandledMessage: @Composable () -> Unit,
        onClick: () -> Unit
    ) {
        if (bankMessage.amount.isNotEmpty()) {
            Row(modifier
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val displayString = checkAndDisplayMerchantChipView(bankMessage.payee)

                if (displayString != null) {
                    Spacer(Modifier.padding(start = 16.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(0.6f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (displayString.second == ZCategory.UPI_PAYMENT) displayString.first else displayString.first.lowercase(
                                Locale.ROOT
                            ).split(" ")
                                .joinToString(separator = " ") { checkAndCapitalize(it) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.primary
                        )

                        CategoryDisplay(category = displayString.second)
                    }

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = "${if (bankMessage.transactionType.isReceived()) "+" else "-"} ₹${
                                if (bankMessage.amount.endsWith(".00") || bankMessage.amount.endsWith(
                                        ".0"
                                    )
                                ) bankMessage.amount.split(".")[0] else bankMessage.amount
                            }".trimStart().replace(",", ""),
                            textAlign = TextAlign.End,
                            fontSize = 24.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (bankMessage.transactionType.isReceived()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                        )

                        val accountNumber =
                            if (bankMessage.accountNumber.startsWith("x")) bankMessage.accountNumber.replace(
                                "x", ""
                            ) else bankMessage.accountNumber
                        if (accountNumber.isEmpty().not()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isCCMessage) {
                                    Image(
                                        modifier = Modifier.size(18.dp),
                                        painter = painterResource(R.drawable.credit_card),
                                        contentDescription = "",
                                        colorFilter = ColorFilter.tint(
                                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                                        )
                                    )
                                } else {
                                    Image(
                                        modifier = Modifier.size(18.dp),
                                        painter = painterResource(R.drawable.bank),
                                        contentDescription = "",
                                        colorFilter = ColorFilter.tint(
                                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                                        )
                                    )
                                }

                                Spacer(Modifier.padding(end = 4.dp))

                                Text(
                                    text = "•• ${accountNumber}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.End,
                                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(
                                    modifier = Modifier.size(18.dp),
                                    painter = painterResource(R.drawable.bank),
                                    contentDescription = "",
                                    colorFilter = ColorFilter.tint(
                                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                                    )
                                )

                                Text(
                                    modifier = Modifier.padding(start = 4.dp),
                                    text = bankMessage.bankName.displayString,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.End,
                                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            unhandledMessage()
        }
    }

    private fun checkAndCapitalize(displayString: String): String
    {
        val pattern = "([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})"
        val isUPI = Pattern.matches(pattern, displayString)

        return if(isUPI)
        {
            displayString
        }
        else
        {
            displayString.capitalize(Locale.ROOT)
        }
    }

    @Composable
    fun CategoryDisplay(modifier: Modifier = Modifier, category: ZCategory?) {
        Text(
            modifier = modifier
                .padding(top = 4.dp)
                .border(
                    0.5.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 8.dp),
            text = category?.displayString ?: ZCategory.OTHERS.displayString,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
        )
    }

    @Composable
    fun CardBillMessages(
        modifier: Modifier = Modifier,
        bankMessage: ZBankMessage,
        unhandledMessage: @Composable () -> Unit,
        onClick: () -> Unit
    ) {
        if (bankMessage.amount.isNotEmpty()) {
            val accountNumber =
                if (bankMessage.accountNumber.startsWith("x")) bankMessage.accountNumber.replace(
                    "x", ""
                ) else bankMessage.accountNumber

            Row(modifier
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                checkAndDisplayMerchantChipView(bankMessage.bankName.displayString)

                Spacer(Modifier.padding(start = 16.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(0.6f), verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "${bankMessage.bankName.displayString} ${if (bankMessage.bankName.isCard) "Card •• " else ""}${accountNumber}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = bankMessage.transactionType.displayString,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = "₹${
                            if (bankMessage.amount.endsWith(".00") || bankMessage.amount.endsWith(
                                    ".0"
                                )
                            ) bankMessage.amount.split(".")[0] else bankMessage.amount
                        }".trimStart().replace(",", ""),
                        textAlign = TextAlign.End,
                        fontSize = 24.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        } else {
            unhandledMessage()
        }
    }

    @Composable
    fun NetworkUsageView(
        modifier: Modifier, networkCarrier: NetworkCarrier, percentUsed: Int, onClick: () -> Unit
    ) {
        Column(modifier = modifier
            .padding(vertical = 8.dp)
            .clickable { onClick() }) {
            Row(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.tertiaryContainer,
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    modifier = Modifier.padding(vertical = 4.dp),
                    text = checkAndCapitalize(networkCarrier.name.lowercase()),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            Row(
                Modifier
                    .padding(horizontal = 16.dp)
                    .height(72.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomEnd = 16.dp, bottomStart = 16.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.secondaryContainer,
                        RoundedCornerShape(bottomEnd = 16.dp, bottomStart = 16.dp)
                    ), verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    Modifier
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .fillMaxHeight()
                        .fillMaxWidth((percentUsed.toFloat() / 100))
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "${percentUsed}%",
                        fontSize = 32.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "Data usage alert",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }


    @Composable
    fun checkAndDisplayMerchantChipView(
        merchantName: String, category: ZCategory? = null
    ): Pair<String, ZCategory>? {
        val upiPattern = "[a-zA-Z0-9.\\-_]{2,49}@[a-zA-Z._]{2,49}"
        val isUPI = Pattern.matches(upiPattern, merchantName)

        return when {
            merchantName.contains("HDFC", true) ||
                    merchantName.contains("HDFCBK", true) -> {
                if (isUPI) {
                    chipView(
                        imageRes = R.drawable.upi,
                        merchantName = merchantName,
                        tint = MaterialTheme.colorScheme.onTertiary,
                        category = category ?: ZCategory.UPI_PAYMENT
                    )
                } else {
                    chipView(
                        imageRes = R.drawable.hdfc,
                        merchantName = "HDFC",
                        category = category ?: ZCategory.CREDIT_CARDS
                    )
                        }

            }

            merchantName.contains("Federal Bank", true) || merchantName.contains(
                "FEDBNK",
                true
            ) -> {
                if (isUPI) {
                    chipView(
                        imageRes = R.drawable.upi,
                        merchantName = merchantName,
                        tint = MaterialTheme.colorScheme.onTertiary,
                        category = category ?: ZCategory.UPI_PAYMENT
                    )
                } else {
                    chipView(
                        imageRes = R.drawable.federal_bank,
                        merchantName = "Federal Bank",
                        category = category ?: ZCategory.UPI_PAYMENT
                    )
                }
            }

            merchantName.contains("Axis Bank", true) || merchantName.contains("AXIS", true) -> {
                if (isUPI) {
                    chipView(
                        imageRes = R.drawable.upi,
                        merchantName = merchantName,
                        tint = MaterialTheme.colorScheme.onTertiary,
                        category = category ?: ZCategory.UPI_PAYMENT
                    )
                } else {
                    chipView(
                        imageRes = R.drawable.axis_bank,
                        merchantName = "Axis Bank",
                        category = category ?: ZCategory.CREDIT_CARDS
                    )
                }
            }

            merchantName.contains("Blue dart", true) || merchantName.contains("BLUDRT", true) -> {
                chipView(
                    imageRes = R.drawable.bluedart,
                    merchantName = "Blue dart",
                    category = category ?: ZCategory.COURIER
                )
            }

            merchantName.contains("Fastag", true) -> {
                chipView(
                    imageRes = R.drawable.fastag,
                    merchantName = "Fastag",
                    category = category ?: ZCategory.FASTAG
                )
            }

            merchantName.contains("BROADWAY", true) -> {
                chipView(
                    imageRes = R.drawable.broadway_cinemas,
                    merchantName = "Broadway Cinemas",
                    category = category ?: ZCategory.ENTERTAINMENT
                )
            }

            merchantName.contains("SWIGGY", true) -> {
                chipView(
                    imageRes = R.drawable.swiggy,
                    merchantName = "Swiggy",
                    category = category ?: ZCategory.FOOD
                )
            }

            merchantName.contains("TATA PLAY", true) || merchantName.contains(
                "TPLYSV",
                true
            ) || merchantName.contains("TPPLAY", true) -> {
                chipView(
                    imageRes = R.drawable.tataplay,
                    merchantName = "Tata Play",
                    category = category ?: ZCategory.UTILITIES
                )
            }

            merchantName.contains("Boomerang", true) -> {
                chipView(
                    imageRes = R.drawable.boomerang,
                    merchantName = "Boomerang",
                    category = category ?: ZCategory.FOOD
                )
            }

            merchantName.contains("Dineout", true) -> {
                chipView(
                    imageRes = R.drawable.dineout,
                    merchantName = "Swiggy Dineout",
                    category = category ?: ZCategory.FOOD
                )
            }

            merchantName.contains("Skyltd", true) -> {
                chipView(
                    imageRes = R.drawable.skylink,
                    merchantName = "Skylink",
                    category = category ?: ZCategory.NETWORK_CARRIERS
                )
            }

            merchantName.contains("FUEL", true) -> {
                chipView(
                    imageRes = R.drawable.fuel,
                    merchantName = merchantName,
                    category = category ?: ZCategory.FUEL
                )
            }

            merchantName.contains("amazon", true) -> {
                chipView(
                    imageRes = R.drawable.amazon,
                    merchantName = "Amazon",
                    category = category ?: ZCategory.SHOPPING
                )
            }

            merchantName.contains("Bakery", true) -> {
                chipView(
                    imageRes = R.drawable.bakery,
                    merchantName = merchantName,
                    category = category ?: ZCategory.FOOD
                )
            }

            merchantName.contains("Karuppatti", true) -> {
                chipView(
                    imageRes = R.drawable.tea,
                    merchantName = merchantName,
                    category = category ?: ZCategory.FOOD
                )
            }

            merchantName.contains("Simpl", true) || merchantName.contains("SMPL", true) -> {
                chipView(
                    imageRes = R.drawable.simpl,
                    merchantName = "Simpl",
                    category = category ?: ZCategory.PAY_LATER
                )
            }

            merchantName.contains("Lazypay", true) -> {
                chipView(
                    imageRes = R.drawable.lazypay,
                    merchantName = "Lazypay",
                    category = category ?: ZCategory.PAY_LATER
                )
            }

            merchantName.contains("Swirlyo", true) -> {
                chipView(
                    imageRes = R.drawable.swirlyo,
                    merchantName = "Swirlyo",
                    category = category ?: ZCategory.FOOD
                )
            }

            merchantName.contains("Burgerman", true) -> {
                chipView(
                    imageRes = R.drawable.burgerman,
                    merchantName = "Burgerman",
                    category = category ?: ZCategory.FOOD
                )
            }

            merchantName.contains("American Express", true) || merchantName.contains(
                "AMEX",
                true
            ) -> {
                chipView(
                    imageRes = R.drawable.amex,
                    merchantName = "American Express",
                    category = category ?: ZCategory.CREDIT_CARDS
                )
            }

            merchantName.contains("Lenskart", true) || merchantName.contains(
                "LNSKRT",
                true
            ) || merchantName.contains("LENSKT", true) -> {
                chipView(
                    imageRes = R.drawable.lenskart,
                    merchantName = "Lenskart",
                    category = category ?: ZCategory.SHOPPING
                )
            }

            merchantName.contains("Airtel", true) -> {
                chipView(
                    imageRes = R.drawable.airtel_logo_icon,
                    merchantName = "Airtel",
                    category = category ?: ZCategory.NETWORK_CARRIERS
                )
            }

            merchantName.contains("BSNL", true) -> {
                chipView(
                    imageRes = R.drawable.bsnl_logo_icon,
                    merchantName = "BSNL",
                    category = category ?: ZCategory.NETWORK_CARRIERS
                )
            }

            merchantName.contains("Jio", true) -> {
                chipView(
                    imageRes = R.drawable.jio_logo_icon,
                    merchantName = "Jio",
                    category = category ?: ZCategory.NETWORK_CARRIERS
                )
            }

            merchantName.contains("Vi Mobile", true) -> {
                chipView(
                    imageRes = R.drawable.vi_mobile_icon,
                    merchantName = "Vi",
                    category = category ?: ZCategory.NETWORK_CARRIERS
                )
            }

            else -> {
                if (isUPI) {
                    chipView(
                        imageRes = R.drawable.upi,
                        merchantName = merchantName,
                        tint = MaterialTheme.colorScheme.onTertiary,
                        category = category ?: ZCategory.UPI_PAYMENT
                    )
                } else {
                    chipView(
                        imageRes = R.drawable.baseline_person_24,
                        merchantName = merchantName,
                        tint = MaterialTheme.colorScheme.onTertiary,
                        category = category ?: null
                    )
                }
            }
        }
    }

    @Composable
    fun chipView(
        imageRes: Int,
        tint: Color? = null,
        merchantName: String? = null,
        category: ZCategory? = null
    ): Pair<String, ZCategory>? {
        return if (selectedFilter.value == null || selectedFilter.value == category) {
            Box(
                Modifier
                    .size(60.dp)
                    .border(0.5.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(imageRes),
                    colorFilter = if (tint != null) ColorFilter.tint(tint) else null,
                    contentScale = ContentScale.Fit,
                    contentDescription = ""
                )
            }

            Pair(merchantName ?: "", category ?: ZCategory.OTHERS)
        } else {
            null
        }
    }

    private fun formatDateWithTime(timestamp: Long): String {
        val date = Date(timestamp)
        val now = Date()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val dayFormat = SimpleDateFormat("EEE", Locale.getDefault()) // Day of the week

        val dateString = dateFormat.format(date)
        val nowString = dateFormat.format(now)

        val calendar = Calendar.getInstance()
        calendar.time = now
        val today = calendar.time

        calendar.time = date
        val diff = today.time - calendar.time.time // Difference in milliseconds
        val diffDays = diff / (24 * 60 * 60 * 1000) // Difference in days

        return when {
            dateString == nowString -> timeFormat.format(date) // Today: show time
            diffDays < 7 -> dayFormat.format(date) // Less than 7 days: show day
            else -> SimpleDateFormat(
                "dd MMM", Locale.getDefault()
            ).format(date) // More than 7 days: show date
        }
    }

    private fun formatDateWithTimeForHeader(timestamp: Long): String {
        val date = Date(timestamp)
        val now = Date()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val dayFormat = SimpleDateFormat("EEEE", Locale.getDefault()) // Day of the week

        val dateString = dateFormat.format(date)
        val nowString = dateFormat.format(now)

        val calendar = Calendar.getInstance()
        calendar.time = now
        val today = calendar.time

        calendar.time = date
        val diff = today.time - calendar.time.time // Difference in milliseconds
        val diffDays = diff / (24 * 60 * 60 * 1000) // Difference in days

        return when {
            dateString == nowString -> "Today, ${
                SimpleDateFormat(
                    "dd MMM", Locale.getDefault()
                ).format(date)
            }" // Today: show time
            diffDays == 0L -> "Yesterday, ${
                SimpleDateFormat(
                    "dd MMM", Locale.getDefault()
                ).format(date)
            }"

            diffDays < 7 -> "${dayFormat.format(date)}, ${
                SimpleDateFormat(
                    "dd MMM", Locale.getDefault()
                ).format(date)
            }" // Less than 7 days: show day
            else -> SimpleDateFormat(
                "dd MMM", Locale.getDefault()
            ).format(date) // More than 7 days: show date
        }
    }

    private fun formatDateWithTimeForDetail(timestamp: Long): String {
        val date = Date(timestamp)
        val now = Date()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd, hh:mm a", Locale.getDefault())
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val dayFormat = SimpleDateFormat("EEEE, hh:mm a", Locale.getDefault()) // Day of the week

        val dateString = dateFormat.format(date)
        val nowString = dateFormat.format(now)

        val calendar = Calendar.getInstance()
        calendar.time = now
        val today = calendar.time

        calendar.time = date
        val diff = today.time - calendar.time.time // Difference in milliseconds
        val diffDays = diff / (24 * 60 * 60 * 1000) // Difference in days

        return when {
            dateString == nowString -> "Today, ${
                SimpleDateFormat(
                    "dd MMM, hh:mm a", Locale.getDefault()
                ).format(date)
            }" // Today: show time
            diffDays == 0L -> "Yesterday, ${
                SimpleDateFormat(
                    "dd MMM, hh:mm a", Locale.getDefault()
                ).format(date)
            }"

            diffDays < 7 -> "${dayFormat.format(date)}, ${
                SimpleDateFormat(
                    "dd MMM, hh:mm a", Locale.getDefault()
                ).format(date)
            }" // Less than 7 days: show day
            else -> "${
                SimpleDateFormat(
                    "dd MMM, hh:mm a", Locale.getDefault()
                ).format(date)
            }" // More than 7 days: show date
        }
    }


    private fun checkAndRequestSmsPermission() {
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.READ_SMS), REQUEST_CODE_READ_SMS
            )
        } else {
            // Permission already granted, proceed with reading SMS
            MessagesHandler().getSmsMessages(this) {
                messages.value = it
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_READ_SMS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, proceed with reading SMS
                MessagesHandler().getSmsMessages(this) {
                    messages.value = it
                }
            } else {
                // Permission denied, handle accordingly (e.g., show a message)
                Log.e("SMS Permission", "Permission Denied")
                // You might want to show a message to the user explaining why
                // the permission is needed and what features are unavailable
                // without it.
            }
        }
    }
}