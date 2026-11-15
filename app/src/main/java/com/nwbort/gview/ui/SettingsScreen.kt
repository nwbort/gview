package com.nwbort.gview.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nwbort.gview.data.Account
import com.nwbort.gview.data.Cache
import com.nwbort.gview.data.GarminApi
import com.nwbort.gview.ui.theme.G
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(
    account: Account,
    cache: Cache,
    syncing: Boolean,
    onSync: () -> Unit,
    onConnect: (String, LocalDate) -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
) {
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val today = LocalDate.now()
    Column(Modifier.fillMaxSize().background(G.paper).statusBarsPadding().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = G.ink) }
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = G.ink)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(padding = 0.dp) {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !syncing, onClick = onSync).padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Sync now", style = MaterialTheme.typography.titleMedium, color = G.ink)
                        Text(
                            when {
                                syncing -> "Syncing with Garmin…"
                                cache.lastSync != null -> "Last synced ${syncTime(cache.lastSync)}. Syncs by itself every few hours."
                                else -> "Not synced yet"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = G.ink2,
                        )
                    }
                    if (syncing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = G.ink2)
                }
                if (cache.errors.isNotEmpty()) {
                    HorizontalDivider(color = G.line)
                    Column(Modifier.padding(18.dp)) {
                        Text("Last sync missed", style = MaterialTheme.typography.titleSmall, color = G.ink)
                        cache.errors.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall, color = G.ink2) }
                    }
                }
                HorizontalDivider(color = G.line)
                Column(Modifier.fillMaxWidth().clickable { dialog = "key" }.padding(18.dp)) {
                    Text("Garmin API key", style = MaterialTheme.typography.titleMedium, color = G.ink)
                    val left = account.daysLeft(today)
                    Text(
                        when {
                            account.keyRejected -> "Turned down by Garmin. Tap to paste a new one."
                            left == null -> "Tap to replace it"
                            left < 0 -> "Expired ${LocalDate.parse(account.keyExpires).format(DateTimeFormatter.ofPattern("d MMMM yyyy"))}. Tap to paste a new one."
                            else -> "Expires ${LocalDate.parse(account.keyExpires).format(DateTimeFormatter.ofPattern("d MMMM yyyy"))} (in $left days). Tap to replace it."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (account.keyRejected || (left != null && left < 7)) MaterialTheme.colorScheme.error else G.ink2,
                    )
                }
                HorizontalDivider(color = G.line)
                Column(Modifier.fillMaxWidth().clickable { dialog = "off" }.padding(18.dp)) {
                    Text("Disconnect", style = MaterialTheme.typography.titleMedium, color = G.ink)
                    Text("Forget the key and everything synced from Garmin.", style = MaterialTheme.typography.bodyMedium, color = G.ink2)
                }
            }
            Panel {
                Text("About", style = MaterialTheme.typography.titleMedium, color = G.ink)
                Spacer(Modifier.height(4.dp))
                val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
                Text(
                    "gview $version. Reads your data from Garmin Connect's Personal API; it can't change anything in your account. " +
                        "Everything synced stays on this phone. Maps © OpenStreetMap contributors.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = G.ink2,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${cache.activities.size} activities · ${cache.summaries.size} days · ${cache.sleep.size} nights",
                    style = MaterialTheme.typography.bodySmall,
                    color = G.ink3,
                )
            }
        }
    }
    when (dialog) {
        "key" -> KeyDialog(onDismiss = { dialog = null }, onConnect = { k, e -> dialog = null; onConnect(k, e) })
        "off" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Disconnect Garmin?") },
            text = { Text("gview forgets the key and everything it synced. Your data in Garmin Connect isn't touched.") },
            confirmButton = { TextButton(onClick = { dialog = null; onDisconnect() }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

private fun syncTime(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val time = t.format(DateTimeFormatter.ofPattern("h:mm a")).lowercase()
    return if (t.toLocalDate() == LocalDate.now()) "today at $time" else "${t.format(DateTimeFormatter.ofPattern("d MMM"))} at $time"
}

@Composable
fun KeyDialog(onDismiss: () -> Unit, onConnect: (String, LocalDate) -> Unit) {
    val context = LocalContext.current
    var key by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableStateOf(365) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = G.card,
        title = { Text("Connect Garmin") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "In Garmin Connect, open Settings → Personal API token, choose Create Key, and copy the key: " +
                        "Garmin shows it only once. The key you made for Workouts works here too. " +
                        "gview only reads; it can't change anything in your account.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { openLink(context, GarminApi.KEY_PAGE) }) { Text("Open Garmin Connect") }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim() },
                    label = { Text("Personal API key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Expiry you chose for it", style = MaterialTheme.typography.labelLarge)
                GarminApi.KEY_LIFETIMES.forEach { d ->
                    Row(Modifier.fillMaxWidth().clickable { days = d }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = days == d, onClick = { days = d })
                        Text(if (d == 365) "1 year" else "$d days")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = key.isNotBlank(), onClick = { onConnect(key, LocalDate.now().plusDays(days.toLong())) }) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** First run: what the app is, and the one thing to do. */
@Composable
fun WelcomeScreen(onConnect: (String, LocalDate) -> Unit, modifier: Modifier = Modifier) {
    var asking by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().background(G.paper).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            // Concentric rings in the metric colours: sleep, HRV, Body Battery, steps.
            Canvas(Modifier.fillMaxSize()) {
                listOf(G.sleep to 0.72f, G.hrv to 0.58f, G.battery to 0.86f, G.steps to 0.44f).forEachIndexed { i, (c, f) ->
                    val stroke = 12.dp.toPx()
                    val inset = stroke / 2 + i * (stroke + 6.dp.toPx())
                    val d = size.minDimension - 2 * inset
                    val tl = Offset(inset, inset)
                    drawArc(c.copy(alpha = 0.12f), 0f, 360f, false, tl, androidx.compose.ui.geometry.Size(d, d), style = Stroke(stroke))
                    drawArc(c, -90f, 360f * f, false, tl, androidx.compose.ui.geometry.Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        Text("gview", style = MaterialTheme.typography.displayMedium, color = G.ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your sleep, recovery and activities from Garmin, simply and beautifully.",
            style = MaterialTheme.typography.bodyLarge,
            color = G.ink2,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { asking = true },
            colors = ButtonDefaults.buttonColors(containerColor = G.ink, contentColor = Color.White),
            modifier = Modifier.height(52.dp).fillMaxWidth(0.8f),
        ) { Text("Connect Garmin") }
    }
    if (asking) KeyDialog(onDismiss = { asking = false }, onConnect = { k, e -> asking = false; onConnect(k, e) })
}
