package com.nwbort.gview.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nwbort.gview.Graph
import com.nwbort.gview.data.Track
import com.nwbort.gview.ui.theme.G
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/** Web Mercator, as fractions of the world (0..1 across and down). */
object Mercator {
    fun x(lon: Double) = (lon + 180) / 360
    fun y(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2
    }
}

/**
 * Map tiles from OpenStreetMap (drawn softened to suit the app), kept on the phone
 * once fetched so maps you've looked at work offline.
 */
class TileCache(context: Context) {
    private val dir = File(context.cacheDir, "osm-tiles").also {
        // Tiles from the old map source said "API key required"; drop them.
        File(context.cacheDir, "tiles").deleteRecursively()
    }
    private val memory = LruCache<String, ImageBitmap>(96)
    private val failed = HashSet<String>()
    private val gate = Semaphore(4)
    private val _version = MutableStateFlow(0)
    /** Bumps whenever a tile arrives, so maps redraw. */
    val version = _version.asStateFlow()
    /** Off in tests, so screenshots don't depend on the network. */
    var online = true

    fun get(z: Int, x: Int, y: Int): ImageBitmap? = memory.get("$z/$x/$y")

    fun request(z: Int, x: Int, y: Int) {
        val key = "$z/$x/$y"
        if (memory.get(key) != null || key in failed) return
        synchronized(failed) { if (key in failed) return; failed += key } // in flight
        Graph.scope.launch(Dispatchers.IO) {
            gate.withPermit {
                val file = File(dir, "$key.png")
                val bytes = runCatching {
                    if (file.exists()) file.readBytes() else if (!online) null else download(z, x, y)?.also {
                        file.parentFile?.mkdirs(); file.writeBytes(it)
                    }
                }.getOrNull()
                val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
                if (bmp != null) {
                    memory.put(key, bmp)
                    synchronized(failed) { failed -= key }
                    _version.value++
                }
            }
        }
    }

    private fun download(z: Int, x: Int, y: Int): ByteArray? {
        val conn = URI("https://tile.openstreetmap.org/$z/$x/$y.png").toURL().openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            // OpenStreetMap's tile policy asks apps to identify themselves.
            conn.setRequestProperty("User-Agent", "gview/1.0 (Android; personal Garmin viewer)")
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * The route on a light map, fitted to the view. With [interactive], pinch and
 * drag to look around.
 */
@Composable
fun RouteMap(
    track: Track,
    color: Color,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    /** Per-sample colours (aligned with the track's samples): draws the route coloured by a metric. */
    colors: List<Color?>? = null,
    /** A sample to mark with a dot, e.g. where a chart is being read. */
    marker: Int? = null,
    /** Space covered at the bottom (e.g. by a chart), kept clear when fitting the route. */
    bottomInset: Dp = 0.dp,
) {
    val tiles = Graph.tiles
    val version by tiles.version.collectAsState()
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val xs = remember(track) { track.lon.map { Mercator.x(it) } }
    val ys = remember(track) { track.lat.map { Mercator.y(it) } }
    // View state: the world point at the centre, and zoom (world width = 256 dp * 2^zoom).
    var center by remember(track) { mutableStateOf(Offset.Zero) }
    var zoom by remember(track) { mutableStateOf(-1f) }
    var fitted by remember(track) { mutableIntStateOf(0) }

    LaunchedEffect(size, track) {
        if (size == IntSize.Zero || xs.isEmpty() || fitted == size.width) return@LaunchedEffect
        val tilePx = with(density) { 256.dp.toPx() }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val pad = 0.82
        val inset = with(density) { bottomInset.toPx() }
        val h = (size.height - inset).coerceAtLeast(size.height * 0.3f)
        val zx = log2(size.width * pad / (max(maxX - minX, 1e-9) * tilePx))
        val zy = log2(h * pad / (max(maxY - minY, 1e-9) * tilePx))
        zoom = min(min(zx, zy), 17.0).toFloat()
        val worldPx = tilePx * 2.0.pow(zoom.toDouble())
        // Centre the route in the uncovered part of the view.
        center = Offset(((minX + maxX) / 2).toFloat(), ((minY + maxY) / 2 + (size.height - h) / 2 / worldPx).toFloat())
        fitted = size.width
    }

    val gestures = if (interactive) Modifier.pointerInput(track) {
        detectTransformGestures { _, pan, gestureZoom, _ ->
            val tilePx = 256.dp.toPx()
            zoom = (zoom + log2(gestureZoom)).coerceIn(2f, 19f)
            val scale = tilePx * 2f.pow(zoom)
            center = Offset((center.x - pan.x / scale).coerceIn(0f, 1f), (center.y - pan.y / scale).coerceIn(0f, 1f))
        }
    } else Modifier

    Box(modifier.clipToBounds().background(Color(0xFFF2F1EC)).onSizeChanged { size = it }.semantics { contentDescription = "Route map" }) {
        Canvas(Modifier.matchParentSize().then(gestures)) {
            if (zoom < 0 || xs.isEmpty()) return@Canvas
            version // read so new tiles redraw
            val tilePx = 256.dp.toPx()
            // One zoom level finer than the view: OSM tiles are 256 px, so this keeps them sharp.
            val z = (floor(zoom).toInt() + 1).coerceIn(0, 19)
            val n = 1 shl z
            val worldPx = tilePx * 2f.pow(zoom)
            val tileSize = worldPx / n
            val ox = size.width / 2 - center.x * worldPx
            val oy = size.height / 2 - center.y * worldPx
            val tx0 = floor(-ox / tileSize).toInt(); val tx1 = floor((size.width - ox) / tileSize).toInt()
            val ty0 = max(0, floor(-oy / tileSize).toInt()); val ty1 = min(n - 1, floor((size.height - oy) / tileSize).toInt())
            for (ty in ty0..ty1) for (tx in tx0..tx1) {
                val wx = ((tx % n) + n) % n
                val img = tiles.get(z, wx, ty)
                if (img == null) { tiles.request(z, wx, ty); continue }
                val left = ox + tx * tileSize; val top = oy + ty * tileSize
                drawImage(
                    img,
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize((left + tileSize).roundToInt() - left.roundToInt(), (top + tileSize).roundToInt() - top.roundToInt()),
                    colorFilter = Soften,
                )
            }
            val path = Path()
            xs.indices.forEach { i ->
                val px = ox + (xs[i] * worldPx).toFloat(); val py = oy + (ys[i] * worldPx).toFloat()
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, Color.White, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            fun sample(i: Int): Offset? {
                val la = track.sLat.getOrNull(i) ?: return null; val lo = track.sLon.getOrNull(i) ?: return null
                return Offset(ox + (Mercator.x(lo) * worldPx).toFloat(), oy + (Mercator.y(la) * worldPx).toFloat())
            }
            if (colors != null && track.sLat.isNotEmpty()) {
                // The route through the samples, each stretch in its value's colour.
                drawPath(path, G.ink3.copy(alpha = 0.5f), style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val w = 4.dp.toPx()
                var prev: Offset? = null
                for (i in track.sLat.indices) {
                    val p = sample(i)
                    val c = colors.getOrNull(i)
                    if (p != null && prev != null && c != null) drawLine(c, prev, p, w, cap = StrokeCap.Round)
                    if (p != null) prev = p
                }
            } else {
                drawPath(path, color, style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            val start = Offset(ox + (xs.first() * worldPx).toFloat(), oy + (ys.first() * worldPx).toFloat())
            val end = Offset(ox + (xs.last() * worldPx).toFloat(), oy + (ys.last() * worldPx).toFloat())
            drawCircle(Color.White, 7.dp.toPx(), end); drawCircle(G.ink, 4.5.dp.toPx(), end)
            drawCircle(Color.White, 7.dp.toPx(), start); drawCircle(G.hrv, 4.5.dp.toPx(), start)
            marker?.let { sample(it) }?.let { p ->
                drawCircle(color.copy(alpha = 0.2f), 16.dp.toPx(), p)
                drawCircle(Color.White, 8.dp.toPx(), p)
                drawCircle(G.ink, 5.5.dp.toPx(), p)
            }
        }
        Text(
            "© OpenStreetMap contributors",
            color = G.ink3,
            fontSize = 9.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = bottomInset).background(Color.White.copy(alpha = 0.7f)).padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/** OpenStreetMap's colours, mostly desaturated and lifted towards white: a quiet backdrop for the route. */
private val Soften = ColorFilter.colorMatrix(ColorMatrix().apply {
    setToSaturation(0.25f)
    val lift = ColorMatrix(floatArrayOf(
        0.8f, 0f, 0f, 0f, 255 * 0.2f,
        0f, 0.8f, 0f, 0f, 255 * 0.2f,
        0f, 0f, 0.8f, 0f, 255 * 0.19f,
        0f, 0f, 0f, 1f, 0f,
    ))
    timesAssign(lift)
})

/** Just the route's shape, no map: for the activity list. */
@Composable
fun RouteShape(track: Track, color: Color, modifier: Modifier = Modifier) {
    val xs = remember(track) { track.lon.map { Mercator.x(it) } }
    val ys = remember(track) { track.lat.map { Mercator.y(it) } }
    Canvas(modifier) {
        if (xs.size < 2) return@Canvas
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val pad = 6.dp.toPx()
        val s = min((size.width - 2 * pad) / max(maxX - minX, 1e-9), (size.height - 2 * pad) / max(maxY - minY, 1e-9))
        val ox = (size.width - (maxX - minX) * s) / 2
        val oy = (size.height - (maxY - minY) * s) / 2
        val path = Path()
        xs.indices.forEach { i ->
            val px = (ox + (xs[i] - minX) * s).toFloat(); val py = (oy + (ys[i] - minY) * s).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, 2.5.dp.toPx(), Offset((ox + (xs.last() - minX) * s).toFloat(), (oy + (ys.last() - minY) * s).toFloat()))
    }
}
