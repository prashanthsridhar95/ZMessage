package com.zyke.message.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.heading
import com.zyke.message.domain.onboarding.DotState
import com.zyke.message.domain.onboarding.StepDots
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.motion.Motion
import com.zyke.message.presentation.motion.enterOnce
import com.zyke.message.presentation.motion.rememberEnterGate
import com.zyke.message.presentation.motion.rememberMotion
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.TypeRole

/**
 * The one layout of a welcome, permission or first-run page: an icon tile, a headline, one line of promise, a full-width
 * primary button and, under it, a quiet secondary one. [extra] (a "Learn more", a list of promises) sits between the promise
 * and the buttons; [footnote] is the small line at the bottom ("You can change this any time in Settings"). [steps] draws the
 * progress dots of a flow of several pages. The four parts rise in once, a little after one another, the first time the page
 * is shown (never again, never when motion is reduced).
 */
@Composable
fun OnboardingHero(
    icon: Int,
    headline: String,
    promise: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    steps: StepDots? = null,
    footnote: String? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null
) {
    val gate = rememberEnterGate()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.xxxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (steps != null && steps.visible) StepDotsRow(steps)
        StateIcon(icon, Modifier.enterOnce(gate, 0))
        Column(
            Modifier.enterOnce(gate, 1),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                headline, Modifier.semantics { heading() }, style = TypeRole.headline,
                color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center
            )
            Caption(promise, maxLines = 4, textAlign = TextAlign.Center)
        }
        if (extra != null) Column(Modifier.fillMaxWidth().enterOnce(gate, 2)) { extra() }
        Column(
            Modifier.fillMaxWidth().enterOnce(gate, 3),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AppButton(primaryLabel, onPrimary, Modifier.fillMaxWidth())
            if (secondaryLabel != null && onSecondary != null) {
                AppButton(secondaryLabel, onSecondary, Modifier.fillMaxWidth(), style = ButtonStyle.TEXT)
            }
            if (footnote != null) Caption(footnote, Modifier.padding(top = Spacing.xs), maxLines = 2, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Progress dots: done and current in the accent, upcoming muted, the current one wider. The width eases in
 * [Motion.QUICK_MS] when the step changes (instant when reduced). A screen reader hears "Step 2 of 3" instead of the dots.
 */
@Composable
fun StepDotsRow(steps: StepDots, modifier: Modifier = Modifier) {
    Row(
        modifier.clearAndSetSemantics { contentDescription = steps.spoken },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.states.forEach { state -> Dot(state) }
    }
}

@Composable
private fun Dot(state: DotState) {
    val motion = rememberMotion()
    val width: Dp by animateDpAsState(if (state == DotState.CURRENT) Spacing.xxl else Spacing.sm, motion.tween(Motion.QUICK_MS), label = "dot")
    val color = if (state == DotState.UPCOMING) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary
    Spacer(Modifier.width(width).height(Spacing.sm).background(color, CircleShape))
}
