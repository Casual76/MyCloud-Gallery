package com.mycloudgallery.presentation.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mycloudgallery.domain.model.MediaItem
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

private const val SOURCE_ID = "gps-media-source"
private const val LAYER_ID = "gps-media-layer"
private const val PROP_ITEM_ID = "itemId"

// Stile mappa OpenStreetMap — nessuna API key richiesta
private val OSM_STYLE_JSON = """
{
  "version": 8,
  "sources": {
    "osm": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "layers": [{"id":"osm","type":"raster","source":"osm"}]
}
""".trimIndent()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onMediaClick: (String) -> Unit,
    viewModel: MapViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) { MapLibre.getInstance(context) }

    val mapView = remember { MapView(context) }

    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = mapView.onStart()
            override fun onResume(owner: LifecycleOwner) = mapView.onResume()
            override fun onPause(owner: LifecycleOwner) = mapView.onPause()
            override fun onStop(owner: LifecycleOwner) = mapView.onStop()
            override fun onDestroy(owner: LifecycleOwner) = mapView.onDestroy()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.Hidden,
            skipHiddenState = false,
        )
    )

    LaunchedEffect(uiState.selectedItem) {
        if (uiState.selectedItem != null) scaffoldState.bottomSheetState.expand()
        else scaffoldState.bottomSheetState.hide()
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 0.dp,
        sheetContent = {
            uiState.selectedItem?.let { item ->
                MapItemPreview(
                    item = item,
                    onOpen = {
                        viewModel.onItemSelected(null)
                        onMediaClick(item.id)
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                AndroidView(
                    factory = { mapView },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        view.getMapAsync { map ->
                            // Imposta stile solo la prima volta
                            if (map.style == null) {
                                map.setStyle(Style.Builder().fromJson(OSM_STYLE_JSON)) { style ->
                                    updateMediaMarkers(style, uiState.mediaWithGps)

                                    map.addOnMapClickListener { point ->
                                        val screenPoint = map.projection.toScreenLocation(point)
                                        val features = map.queryRenderedFeatures(screenPoint, LAYER_ID)
                                        if (features.isNotEmpty()) {
                                            val itemId = features[0].getStringProperty(PROP_ITEM_ID)
                                            val clicked = uiState.mediaWithGps.firstOrNull { it.id == itemId }
                                            viewModel.onItemSelected(clicked)
                                            true
                                        } else false
                                    }

                                    // Centra sulla prima foto con GPS
                                    uiState.mediaWithGps.firstOrNull()?.let { first ->
                                        map.cameraPosition = CameraPosition.Builder()
                                            .target(LatLng(first.exifLatitude!!, first.exifLongitude!!))
                                            .zoom(6.0)
                                            .build()
                                    }
                                }
                            } else {
                                // Aggiorna i marker quando cambia il filtro anno
                                map.style?.let { style ->
                                    updateMediaMarkers(style, uiState.mediaWithGps)
                                }
                            }
                        }
                    }
                )

                // Filtro anno sovrapposto in cima
                YearFilterBar(
                    years = uiState.availableYears,
                    selectedYear = uiState.selectedYearFilter,
                    onYearSelected = viewModel::onYearFilterChanged,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                )

                // Contatore foto
                if (uiState.mediaWithGps.isNotEmpty()) {
                    Text(
                        text = "${uiState.mediaWithGps.size} foto con GPS",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(bottom = 16.dp, start = 8.dp)
                            .background(
                                Color.Black.copy(alpha = 0.55f),
                                MaterialTheme.shapes.small,
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** Aggiunge/aggiorna source GeoJSON e CircleLayer per i marker GPS. */
private fun updateMediaMarkers(style: Style, items: List<MediaItem>) {
    val features = items.mapNotNull { item ->
        val lat = item.exifLatitude ?: return@mapNotNull null
        val lng = item.exifLongitude ?: return@mapNotNull null
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lng,$lat]},"properties":{"$PROP_ITEM_ID":"${item.id}"}}"""
    }
    val geoJson = """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    val existingSource = style.getSource(SOURCE_ID) as? GeoJsonSource
    if (existingSource != null) {
        existingSource.setGeoJson(geoJson)
    } else {
        style.addSource(GeoJsonSource(SOURCE_ID, geoJson))
        style.addLayer(
            CircleLayer(LAYER_ID, SOURCE_ID).apply {
                setProperties(
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleColor("#4CAF50"),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                )
            }
        )
    }
}

@Composable
private fun YearFilterBar(
    years: List<Int>,
    selectedYear: Int?,
    onYearSelected: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            FilterChip(
                selected = selectedYear == null,
                onClick = { onYearSelected(null) },
                label = { Text("Tutto") },
            )
        }
        items(years) { year ->
            FilterChip(
                selected = selectedYear == year,
                onClick = { onYearSelected(if (selectedYear == year) null else year) },
                label = { Text(year.toString()) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapItemPreview(item: MediaItem, onOpen: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        androidx.compose.material3.ListItem(
            headlineContent = { Text(item.fileName) },
            supportingContent = {
                val lat = item.exifLatitude
                val lng = item.exifLongitude
                if (lat != null && lng != null) {
                    Text("%.5f, %.5f".format(lat, lng))
                }
            },
            trailingContent = {
                androidx.compose.material3.Button(onClick = onOpen) {
                    Text("Apri")
                }
            },
        )
    }
}
