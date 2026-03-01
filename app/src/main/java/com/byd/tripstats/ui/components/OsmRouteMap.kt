package com.byd.tripstats.ui.components

import android.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * OpenStreetMap component for displaying trip routes
 * No API keys required, completely free
 */
@Composable
fun OsmRouteMap(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    val ROUTE_COLOR = 0xFF2196F3.toInt() // BatteryBlue
    if (dataPoints.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "No route data available",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "GPS coordinates will appear here when recorded",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    
    // Filter out invalid coordinates (0,0)
    val validPoints = dataPoints.filter { 
        it.latitude != 0.0 && it.longitude != 0.0 
    }
    
    if (validPoints.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No valid GPS data in this trip",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // Configure osmdroid
            Configuration.getInstance().apply {
                userAgentValue = ctx.packageName
            }
            
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK) // OpenStreetMap tiles
                setMultiTouchControls(true) // Enable zoom gestures
                
                // Create route polyline
                val routeLine = Polyline().apply {
                    outlinePaint.color = ROUTE_COLOR
                    outlinePaint.strokeWidth = 8f
                    
                    // Add all valid GPS points
                    val geoPoints = validPoints.map { point ->
                        GeoPoint(point.latitude, point.longitude)
                    }
                    setPoints(geoPoints)
                }
                
                // Add start marker (green)
                val startPoint = validPoints.first()
                val startMarker = Marker(this).apply {
                    position = GeoPoint(startPoint.latitude, startPoint.longitude)
                    title = "🟢 START"
                    snippet = "Trip began here"
                }
                
                // Add end marker (red)
                val endPoint = validPoints.last()
                val endMarker = Marker(this).apply {
                    position = GeoPoint(endPoint.latitude, endPoint.longitude)
                    title = "🔴 END"
                    snippet = "Trip ended here"
                }
                
                // Add overlays to map
                overlays.add(routeLine)
                overlays.add(startMarker)
                overlays.add(endMarker)
                
                // Auto-zoom to fit entire route
                post {
                    zoomToBoundingBox(routeLine.bounds, true, 50)
                }
            }
        },
        update = { mapView ->
            // Update map if data changes
            mapView.overlays.clear()
            
            val routeLine = Polyline().apply {
                outlinePaint.color = ROUTE_COLOR
                outlinePaint.strokeWidth = 8f
                
                val geoPoints = validPoints.map { point ->
                    GeoPoint(point.latitude, point.longitude)
                }
                setPoints(geoPoints)
            }
            
            val startPoint = validPoints.first()
            val startMarker = Marker(mapView).apply {
                position = GeoPoint(startPoint.latitude, startPoint.longitude)
                title = "🟢 START"
                snippet = "Trip began here"
            }
            
            val endPoint = validPoints.last()
            val endMarker = Marker(mapView).apply {
                position = GeoPoint(endPoint.latitude, endPoint.longitude)
                title = "🔴 END"
                snippet = "Trip ended here"
            }
            
            mapView.overlays.add(routeLine)
            mapView.overlays.add(startMarker)
            mapView.overlays.add(endMarker)
            
            mapView.invalidate()
        }
    )
}
