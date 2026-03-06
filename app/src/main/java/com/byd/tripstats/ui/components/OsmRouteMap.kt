package com.byd.tripstats.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

// ── Speed thresholds ──────────────────────────────────────────────────────────
//   < 40 km/h  →  Red    (heavy traffic / queuing)
//  40–80 km/h  →  Orange (light traffic / suburban)
//   ≥ 80 km/h  →  Blue  (free flow / no traffic)

private const val SLOW_MAX = 40.0   // km/h
private const val AVG_MAX  = 80.0   // km/h

private val COLOR_SLOW    = 0xFFE53935.toInt()
private val COLOR_AVERAGE = 0xFFFF9800.toInt()
private val COLOR_FAST    = 0xFF2196F3.toInt()

private fun speedCategory(speed: Double): Int = when {
    speed < SLOW_MAX -> 0
    speed < AVG_MAX  -> 1
    else             -> 2
}

private fun categoryColor(cat: Int): Int = when (cat) {
    0    -> COLOR_SLOW
    1    -> COLOR_AVERAGE
    else -> COLOR_FAST
}

// ── Main composable ───────────────────────────────────────────────────────────

@Composable
fun OsmRouteMap(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    if (dataPoints.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "No route data available",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "GPS coordinates will appear here when recorded",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val validPoints = dataPoints.filter { it.latitude != 0.0 && it.longitude != 0.0 }

    if (validPoints.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No valid GPS data in this trip",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val context = LocalContext.current

    // Wrap map + legend overlay in a Box
    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                Configuration.getInstance().apply { userAgentValue = ctx.packageName }
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    buildAndAddOverlays(this, ctx, validPoints)

                    val bounds = buildBounds(validPoints)
                    controller.setCenter(bounds.centerWithDateLine)
                    controller.setZoom(14.0)
                    post { zoomToBoundingBox(bounds, true, 80) }
                }
            },
            update = { mapView ->
                mapView.overlays.clear()
                buildAndAddOverlays(mapView, context, validPoints)
                mapView.invalidate()
            }
        )

        // Floating speed legend — bottom-left corner
        SpeedLegend(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
        )
    }
}

// ── Overlay construction ──────────────────────────────────────────────────────

private fun buildAndAddOverlays(
    mapView    : MapView,
    context    : Context,
    validPoints: List<TripDataPointEntity>
) {
    // Speed-segmented polylines.
    // Consecutive points in the same speed category are grouped into one
    // Polyline.  At a category boundary the transition point is included at
    // the end of the outgoing segment AND the start of the incoming one so
    // there are no visible gaps between colour bands.
    if (validPoints.size >= 2) {
        var currentCat = speedCategory(validPoints[0].speed)
        var currentPts = mutableListOf(GeoPoint(validPoints[0].latitude, validPoints[0].longitude))

        for (i in 1 until validPoints.size) {
            val gp  = GeoPoint(validPoints[i].latitude, validPoints[i].longitude)
            val cat = speedCategory(validPoints[i].speed)

            if (cat == currentCat) {
                currentPts.add(gp)
            } else {
                currentPts.add(gp)                      // bridge: no gap at colour change
                mapView.overlays.add(makePolyline(currentPts, categoryColor(currentCat)))
                currentCat = cat
                currentPts = mutableListOf(gp)          // new segment starts at same point
            }
        }
        if (currentPts.size >= 2) {
            mapView.overlays.add(makePolyline(currentPts, categoryColor(currentCat)))
        }
    }

    // Start marker — green circle labelled "S"
    validPoints.first().let { pt ->
        mapView.overlays.add(
            Marker(mapView).apply {
                position = GeoPoint(pt.latitude, pt.longitude)
                title    = "Start"
                icon     = createCircleMarker(context, android.graphics.Color.rgb(56, 142, 60), "S")
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
        )
    }

    // End marker — dark-red circle labelled "E"
    validPoints.last().let { pt ->
        mapView.overlays.add(
            Marker(mapView).apply {
                position = GeoPoint(pt.latitude, pt.longitude)
                title    = "End"
                icon     = createCircleMarker(context, android.graphics.Color.rgb(183, 28, 28), "E")
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
        )
    }
}

private fun makePolyline(points: List<GeoPoint>, color: Int): Polyline =
    Polyline().apply {
        outlinePaint.color       = color
        outlinePaint.strokeWidth = 9f
        setPoints(points)
    }

private fun buildBounds(pts: List<TripDataPointEntity>): BoundingBox =
    BoundingBox(pts.maxOf { it.latitude }, pts.maxOf { it.longitude },
                pts.minOf { it.latitude }, pts.minOf { it.longitude })

// ── Custom circular marker ────────────────────────────────────────────────────

/**
 * Builds a 72 × 72 px bitmap drawable: a filled coloured circle with a
 * white border and a single bold white letter centred inside.
 *
 * Using a programmatically drawn bitmap avoids the need for drawable resources
 * and replaces the default osmdroid pointing-hand icon entirely.
 */
private fun createCircleMarker(
    context  : Context,
    fillColor: Int,
    letter   : String
): BitmapDrawable {
    val size   = 72
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint  = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
    val cx     = size / 2f
    val cy     = size / 2f
    val radius = size / 2f - 3f

    // Filled circle
    paint.style = AndroidPaint.Style.FILL
    paint.color = fillColor
    canvas.drawCircle(cx, cy, radius, paint)

    // White border ring
    paint.style       = AndroidPaint.Style.STROKE
    paint.color       = android.graphics.Color.WHITE
    paint.strokeWidth = 4f
    canvas.drawCircle(cx, cy, radius, paint)

    // Centred bold letter
    paint.style          = AndroidPaint.Style.FILL
    paint.color          = android.graphics.Color.WHITE
    paint.textSize       = 34f
    paint.textAlign      = AndroidPaint.Align.CENTER
    paint.isFakeBoldText = true
    val textY = cy - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(letter, cx, textY, paint)

    return BitmapDrawable(context.resources, bitmap)
}

// ── Speed legend (Compose overlay) ───────────────────────────────────────────

@Composable
private fun SpeedLegend(modifier: Modifier = Modifier) {
    Card(
        modifier  = modifier,
        shape     = RoundedCornerShape(8.dp),
        colors    = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier            = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Traffic",
                style      = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                fontSize   = 11.sp
            )
            LegendDot(Color(0xFF2196F3), "Free flow  (≥ 80 km/h)")
            LegendDot(Color(0xFFFF9800), "Light traffic  (40–80 km/h)")
            LegendDot(Color(0xFFE53935), "Heavy traffic  (< 40 km/h)")
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(11.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
    }
}