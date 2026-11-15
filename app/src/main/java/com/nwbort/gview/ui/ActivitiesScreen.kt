package com.nwbort.gview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nwbort.gview.Graph
import com.nwbort.gview.data.Activity
import com.nwbort.gview.data.Cache
import com.nwbort.gview.data.Sport
import com.nwbort.gview.data.Track
import com.nwbort.gview.ui.theme.G
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitiesScreen(cache: Cache, syncing: Boolean, onRefresh: () -> Unit, onSettings: () -> Unit, onActivity: (Long) -> Unit) {
    var filter by rememberSaveable { mutableStateOf<Sport?>(null) }
    val all = cache.sortedActivities
    val sports = all.map { it.sport }.distinct().sortedBy { it.ordinal }
    val shown = all.filter { filter == null || it.sport == filter }
    val thisWeek = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weeks = shown.groupBy { it.date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }

    Column(Modifier.fillMaxSize()) {
        Header("Activities", "${all.size} synced from Garmin", onSettings)
        if (sports.size > 1) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { SportChip("All", filter == null) { filter = null } }
                items(sports) { s -> SportChip(s.label, filter == s) { filter = if (filter == s) null else s } }
            }
        }
        PullToRefreshBox(isRefreshing = syncing, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (shown.isEmpty()) item {
                    Panel { Text(if (syncing) "Fetching from Garmin…" else "No activities yet.", color = G.ink2, modifier = Modifier.padding(vertical = 24.dp)) }
                }
                weeks.forEach { (monday, list) ->
                    item(key = "w$monday") {
                        val dist = list.sumOf { it.distanceM ?: 0.0 }
                        val time = list.sumOf { it.timeS ?: 0.0 }
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp), verticalAlignment = Alignment.Bottom) {
                            Text(
                                when (monday) {
                                    thisWeek -> "This week"
                                    thisWeek.minusWeeks(1) -> "Last week"
                                    else -> "${Fmt.shortDate(monday)} – ${Fmt.shortDate(monday.plusDays(6))}"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = G.ink,
                                modifier = Modifier.weight(1f),
                            )
                            Text("${Fmt.km(dist, 1)} km  ·  ${Fmt.hm(time)}", style = MaterialTheme.typography.bodyMedium, color = G.ink2)
                        }
                    }
                    items(list, key = { it.id }) { a -> ActivityRow(a, onClick = { onActivity(a.id) }) }
                }
            }
        }
    }
}

@Composable
private fun SportChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = G.ink, selectedLabelColor = Color.White, containerColor = G.card, labelColor = G.ink2),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = G.line),
    )
}

/** An activity in a list: its route's shape (or sport icon), name, when, and the three numbers. */
@Composable
fun ActivityRow(a: Activity, onClick: () -> Unit) {
    // Only what's already on the phone: lists never trigger downloads.
    val track by produceState<Track?>(null, a.id) { value = if (a.hasRoute) Graph.store.track(a.id) else null }
    Panel(onClick = onClick, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(G.sport(a.sport).copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                val t = track
                if (t != null && t.hasRoute) RouteShape(t, G.sport(a.sport), Modifier.fillMaxSize().padding(4.dp))
                else SportBadge(a.sport, 40.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name.ifBlank { a.sport.label }, style = MaterialTheme.typography.titleMedium, color = G.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Fmt.when_(a), style = MaterialTheme.typography.bodySmall, color = G.ink3, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (a.km != null) Mini(Fmt.km(a.distanceM), "km")
                    Mini(Fmt.clock(a.timeS), null)
                    if (a.km != null && a.sport != Sport.STRENGTH) Fmt.speed(a).let { (v, u) -> Mini(v, u) }
                    else a.avgHr?.let { Mini(it.toInt().toString(), "bpm") }
                }
            }
        }
    }
}

@Composable
private fun Mini(value: String, unit: String?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = G.ink)
        if (unit != null) Text(" $unit", style = MaterialTheme.typography.bodySmall, color = G.ink3, modifier = Modifier.padding(bottom = 1.dp))
    }
}
