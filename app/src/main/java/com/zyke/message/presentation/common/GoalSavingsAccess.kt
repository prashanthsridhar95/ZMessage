package com.zyke.message.presentation.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.goals.GoalContribution
import com.zyke.message.domain.goals.GoalSavings

private val Context.container get() = (applicationContext as ZykeMessagesApplication).container

/** Records and reads what has been put towards goals, for screens that are not wired through a ViewModel. */
@Composable
fun rememberGoalSavings(): GoalSavings = (LocalContext.current).container.goalSavings

@Composable
fun rememberGoalContributions(): State<List<GoalContribution>> = (LocalContext.current).container.goalContributions.contributions.collectAsState()
