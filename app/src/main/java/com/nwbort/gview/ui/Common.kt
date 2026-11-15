package com.nwbort.gview.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Pool
import androidx.compose.material.icons.outlined.SportsGymnastics
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nwbort.gview.data.Sport
import com.nwbort.gview.ui.theme.G

val CardShape = RoundedCornerShape(22.dp)

/** A white card on the paper background, with a hairline edge. */
@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(G.card)
            .border(1.dp, G.line, CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** A small uppercase label with a coloured dot naming what the card is about. */
@Composable
fun Label(text: String, color: Color? = null, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (color != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
        }
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = G.ink2)
    }
}

/** A number with its unit set smaller beside it: "7h 32m", "52 ms". */
@Composable
fun Value(value: String, unit: String? = null, style: TextStyle = MaterialTheme.typography.displaySmall, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(value, style = style, color = G.ink, maxLines = 1)
        if (!unit.isNullOrEmpty()) {
            Spacer(Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.bodyMedium, color = G.ink3, modifier = Modifier.padding(bottom = (style.fontSize.value / 7).dp), maxLines = 1)
        }
    }
}

/** One figure in a grid of stats. */
@Composable
fun Stat(label: String, value: String, unit: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = G.ink3, maxLines = 1)
        Value(value, unit, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"))
    }
}

/** A row of stats sharing the width. */
@Composable
fun StatRow(vararg stats: Triple<String, String, String?>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        stats.forEach { (l, v, u) -> Stat(l, v, u, Modifier.weight(1f)) }
    }
}

/** A soft pill, e.g. a sleep score's qualifier. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** A key line for a chart or bar: dot, name, value. */
@Composable
fun Legend(color: Color, name: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(name, style = MaterialTheme.typography.bodySmall, color = G.ink2)
        Spacer(Modifier.width(4.dp))
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = G.ink)
    }
}

/** Legend entries two to a row, so long values never collide. */
@Composable
fun LegendGrid(items: List<Triple<Color, String, String>>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (c, n, v) -> Legend(c, n, v, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

fun sportIcon(s: Sport): ImageVector = when (s) {
    Sport.RUN -> Icons.AutoMirrored.Outlined.DirectionsRun
    Sport.RIDE -> Icons.AutoMirrored.Outlined.DirectionsBike
    Sport.SWIM -> Icons.Outlined.Pool
    Sport.WALK -> Icons.AutoMirrored.Outlined.DirectionsWalk
    Sport.STRENGTH -> Icons.Outlined.FitnessCenter
    Sport.OTHER -> Icons.Outlined.SportsGymnastics
}

@Composable
fun SportBadge(s: Sport, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(G.sport(s).copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(sportIcon(s), contentDescription = s.label, tint = G.sport(s), modifier = Modifier.size(size * 0.55f))
    }
}

fun openLink(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT))
    }
}
