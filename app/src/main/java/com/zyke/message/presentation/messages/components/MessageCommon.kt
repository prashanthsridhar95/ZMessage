package com.zyke.message.presentation.messages.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyke.message.R
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory

@DrawableRes
internal fun MerchantLogo.drawableRes(): Int = when (this) {
    MerchantLogo.UPI -> R.drawable.upi
    MerchantLogo.PERSON -> R.drawable.baseline_person_24
    MerchantLogo.HDFC -> R.drawable.hdfc
    MerchantLogo.FEDERAL_BANK -> R.drawable.federal_bank
    MerchantLogo.AXIS_BANK -> R.drawable.axis_bank
    MerchantLogo.BLUE_DART -> R.drawable.bluedart
    MerchantLogo.FASTAG -> R.drawable.fastag
    MerchantLogo.BROADWAY_CINEMAS -> R.drawable.broadway_cinemas
    MerchantLogo.SWIGGY -> R.drawable.swiggy
    MerchantLogo.TATA_PLAY -> R.drawable.tataplay
    MerchantLogo.BOOMERANG -> R.drawable.boomerang
    MerchantLogo.DINEOUT -> R.drawable.dineout
    MerchantLogo.SKYLINK -> R.drawable.skylink
    MerchantLogo.FUEL -> R.drawable.fuel
    MerchantLogo.AMAZON -> R.drawable.amazon
    MerchantLogo.BAKERY -> R.drawable.bakery
    MerchantLogo.TEA -> R.drawable.tea
    MerchantLogo.SIMPL -> R.drawable.simpl
    MerchantLogo.LAZYPAY -> R.drawable.lazypay
    MerchantLogo.SWIRLYO -> R.drawable.swirlyo
    MerchantLogo.BURGERMAN -> R.drawable.burgerman
    MerchantLogo.AMEX -> R.drawable.amex
    MerchantLogo.LENSKART -> R.drawable.lenskart
    MerchantLogo.AIRTEL -> R.drawable.airtel_logo_icon
    MerchantLogo.BSNL -> R.drawable.bsnl_logo_icon
    MerchantLogo.JIO -> R.drawable.jio_logo_icon
    MerchantLogo.VI -> R.drawable.vi_mobile_icon
}

@Composable
internal fun mutedColor(alpha: Float = 0.5f): Color =
    MaterialTheme.colorScheme.secondary.copy(alpha = alpha)

/** Standard tappable list row: 16dp padding, content laid out horizontally. */
@Composable
internal fun MessageRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = verticalAlignment,
        content = content
    )
}

@Composable
internal fun MerchantChip(logo: MerchantLogo, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .size(60.dp)
            .border(0.5.dp, MaterialTheme.colorScheme.tertiary, shape)
            .background(MaterialTheme.colorScheme.tertiary, shape)
            .clip(shape),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(logo.drawableRes()),
            colorFilter = if (logo.isTinted) ColorFilter.tint(MaterialTheme.colorScheme.onTertiary) else null,
            contentScale = ContentScale.Fit,
            contentDescription = null
        )
    }
}

@Composable
internal fun CategoryTag(category: MessageCategory, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier
            .padding(top = 4.dp)
            .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp),
        text = category.displayString,
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = mutedColor()
    )
}

/** Big right-aligned figure such as "- ₹500". */
@Composable
internal fun AmountText(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier.fillMaxWidth(),
        text = text,
        textAlign = TextAlign.End,
        fontSize = 24.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = color
    )
}

/** Small right-aligned "icon + caption" line (balance, card number, account). */
@Composable
internal fun IconCaption(@DrawableRes icon: Int, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            modifier = Modifier.size(18.dp),
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(mutedColor(0.4f))
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            color = mutedColor(0.4f)
        )
    }
}
