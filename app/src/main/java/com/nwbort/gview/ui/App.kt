package com.nwbort.gview.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nwbort.gview.ui.theme.G

enum class Tab(val label: String) { TODAY("Today"), ACTIVITIES("Activities"), TRENDS("Trends") }

/** Screens stacked over the tabs; back pops them. */
sealed interface Screen {
    data class ActivityDetail(val id: Long) : Screen
    data class FullMap(val id: Long) : Screen
    data class RawData(val id: Long) : Screen
    data class MetricDetail(val metric: Metric) : Screen
    data object Settings : Screen
}

@Composable
fun GviewRoot(vm: AppViewModel) {
    val account by vm.account.collectAsStateWithLifecycle()
    val cache by vm.cache.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }

    if (!account.connected) {
        Scaffold(containerColor = G.paper, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
            WelcomeScreen(onConnect = vm::connect, modifier = Modifier.padding(pad))
        }
        return
    }

    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    val stack = remember { mutableStateListOf<Screen>() }
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.isEmpty() && tab != Tab.TODAY) { tab = Tab.TODAY }
    val open: (Screen) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    AnimatedContent(
        targetState = stack.lastOrNull(),
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
        label = "screen",
    ) { top ->
        when (top) {
            is Screen.ActivityDetail -> {
                val a = cache.activities.firstOrNull { it.id == top.id }
                if (a == null) LaunchedEffect(Unit) { back() } else ActivityScreen(a, vm, onBack = back, onMap = { open(Screen.FullMap(a.id)) }, onRaw = { open(Screen.RawData(a.id)) })
            }
            is Screen.FullMap -> {
                val a = cache.activities.firstOrNull { it.id == top.id }
                if (a == null) LaunchedEffect(Unit) { back() } else FullMapScreen(a, vm, onBack = back)
            }
            is Screen.RawData -> {
                val a = cache.activities.firstOrNull { it.id == top.id }
                if (a == null) LaunchedEffect(Unit) { back() } else RawDataScreen(a, vm, onBack = back)
            }
            is Screen.MetricDetail -> MetricScreen(top.metric, cache, onBack = back)
            Screen.Settings -> SettingsScreen(account, cache, syncing, onSync = vm::sync, onHistory = vm::setHistory, onConnect = vm::connect, onDisconnect = { vm.disconnect(); stack.clear() }, onBack = back)
            null -> Scaffold(
                containerColor = G.paper,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBar(containerColor = G.card, tonalElevation = 0.dp) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = t == tab,
                                onClick = { tab = t },
                                icon = {
                                    Icon(
                                        when (t) {
                                            Tab.TODAY -> Icons.Outlined.WbSunny
                                            Tab.ACTIVITIES -> Icons.Outlined.Timeline
                                            Tab.TRENDS -> Icons.Outlined.ShowChart
                                        },
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = G.ink,
                                    selectedTextColor = G.ink,
                                    unselectedIconColor = G.ink3,
                                    unselectedTextColor = G.ink3,
                                    indicatorColor = G.wash,
                                ),
                            )
                        }
                    }
                },
            ) { pad ->
                Box(Modifier.fillMaxSize().padding(pad)) {
                    val settings = { open(Screen.Settings) }
                    when (tab) {
                        Tab.TODAY -> TodayScreen(cache, syncing, vm::sync, settings, onActivity = { open(Screen.ActivityDetail(it)) }, onMetric = { open(Screen.MetricDetail(it)) })
                        Tab.ACTIVITIES -> ActivitiesScreen(cache, syncing, vm::sync, settings, onActivity = { open(Screen.ActivityDetail(it)) })
                        Tab.TRENDS -> TrendsScreen(cache, settings, onMetric = { open(Screen.MetricDetail(it)) }, onActivity = { open(Screen.ActivityDetail(it)) })
                    }
                }
            }
        }
    }
}

/** A big page title with an optional subtitle and the settings button. */
@Composable
fun Header(title: String, subtitle: String? = null, onSettings: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(top = 8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = G.ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = G.ink2)
        }
        if (onSettings != null) {
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = G.ink2) }
        }
    }
}
