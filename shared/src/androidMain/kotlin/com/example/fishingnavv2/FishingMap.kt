package com.example.fishingnavv2

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double

@Composable
actual fun FishingMap(
    modifier: Modifier,
    fishingPoints: Any
) {
    val points = fishingPoints as androidx.compose.runtime.snapshots.SnapshotStateList<org.osmdroid.util.GeoPoint>
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // --- 新增：Retrofit 初始化 ---
    val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true // 忽略沒寫到的欄位 (如 crs)，防止噴錯
        isLenient = true
    }

    var mapReference by remember { mutableStateOf<org.osmdroid.views.MapView?>(null) }
    var isSatelliteMode by remember { mutableStateOf(false) }
    val locationOverlay = remember { mutableStateOf<org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                org.osmdroid.config.Configuration.getInstance().load(ctx, android.preference.PreferenceManager.getDefaultSharedPreferences(ctx))
                org.osmdroid.config.Configuration.getInstance().userAgentValue = ctx.packageName

                org.osmdroid.views.MapView(ctx).apply {
                    mapReference = this
                    setMultiTouchControls(true)
                    controller.setZoom(17.0)

                    // 1. MBTiles 離線地圖支援
                    val mbtilesFile = java.io.File(context.getExternalFilesDir(null), "fishing_map.mbtiles")
                    if (mbtilesFile.exists()) {
                        val mbtilesProvider = org.osmdroid.tileprovider.modules.OfflineTileProvider(
                            org.osmdroid.tileprovider.util.SimpleRegisterReceiver(context),
                            arrayOf(mbtilesFile)
                        )
                        setTileProvider(mbtilesProvider)
                        setTileSource(org.osmdroid.tileprovider.tilesource.FileBasedTileSource.getSource(mbtilesFile.name))
                    }

                    // 2. 定位圖標修正 (解決偏移問題)
                    val overlay = org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay(
                        org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider(ctx), this
                    )

                    // 重新設計 Bitmap：讓 (30,30) 成為三角形的幾何中心
                    val bitSize = 60
                    val bit = android.graphics.Bitmap.createBitmap(bitSize, bitSize, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bit)
                    val p = android.graphics.Paint().apply {
                        color = android.graphics.Color.GREEN
                        style = android.graphics.Paint.Style.FILL
                        isAntiAlias = true
                        setShadowLayer(8f, 0f, 0f, android.graphics.Color.BLACK) // 加強陰影
                    }

                    // 重新計算 Path，讓三角形的中間剛好落在畫布正中央 (30, 30)
                    val path = android.graphics.Path().apply {
                        moveTo(30f, 5f)   // 頂點 (中心上方)
                        lineTo(50f, 55f)  // 右下
                        lineTo(30f, 45f)  // 內凹中心點
                        lineTo(10f, 55f)  // 左下
                        close()
                    }
                    canvas.drawPath(path, p)

                    // 設定自定義圖標
                    overlay.setDirectionArrow(bit, bit)
                    // 關鍵修正：將錨點設為 0.5f, 0.5f，這會強迫三角形的 (30,30) 位置對齊 GPS 圓圈中心
                    overlay.setDirectionAnchor(0.5f, 0.5f)
                    // 同時也要設定位置圖標(圓圈)的錨點
                    overlay.setPersonAnchor(0.5f, 0.5f)

                    overlay.enableMyLocation()
                    overlay.enableFollowLocation()
                    locationOverlay.value = overlay
                    overlays.add(overlay)

                    // 3. 指南針與插旗
                    val compass = org.osmdroid.views.overlay.compass.CompassOverlay(ctx, this)
                    compass.enableCompass()
                    overlays.add(compass)

                    val mapEventsReceiver = object : org.osmdroid.events.MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: org.osmdroid.util.GeoPoint): Boolean {
                            points.add(p); return true
                        }
                        override fun longPressHelper(p: org.osmdroid.util.GeoPoint): Boolean = false
                    }
                    overlays.add(org.osmdroid.views.overlay.MapEventsOverlay(mapEventsReceiver))
                }
            },
            update = { mapView ->
                // 4. 衛星與普通模式切換
                if (isSatelliteMode) {
                    val googleSatellite = object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
                        "Google-Satellite", 0, 20, 256, ".jpg",
                        arrayOf("https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}")
                    ) {
                        override fun getTileURLString(pTileIndex: Long): String = baseUrl
                            .replace("{x}", org.osmdroid.util.MapTileIndex.getX(pTileIndex).toString())
                            .replace("{y}", org.osmdroid.util.MapTileIndex.getY(pTileIndex).toString())
                            .replace("{z}", org.osmdroid.util.MapTileIndex.getZoom(pTileIndex).toString())
                    }
                    mapView.setTileSource(googleSatellite)
                } else {
                    val mbtilesFile = java.io.File(context.getExternalFilesDir(null), "fishing_map.mbtiles")
                    if (!mbtilesFile.exists()) {
                        mapView.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
                    }
                }

                // 5. 導航線
                val myLoc = locationOverlay.value?.myLocation
                val target = points.lastOrNull()
                mapView.overlays.filterIsInstance<org.osmdroid.views.overlay.Polyline>().forEach { mapView.overlays.remove(it) }

                if (myLoc != null && target != null) {
                    val line = org.osmdroid.views.overlay.Polyline(mapView).apply {
                        setPoints(listOf(myLoc, target as org.osmdroid.util.GeoPoint))
                        outlinePaint.color = android.graphics.Color.CYAN
                        outlinePaint.strokeWidth = 12f
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND // 線條末端圓潤化
                    }
                    mapView.overlays.add(line)
                }

                // 6. 標記標籤
                mapView.overlays.filterIsInstance<org.osmdroid.views.overlay.Marker>().forEach { mapView.overlays.remove(it) }
                points.forEach { pt ->
                    val marker = org.osmdroid.views.overlay.Marker(mapView).apply {
                        position = pt as org.osmdroid.util.GeoPoint
                        setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
                    }
                    mapView.overlays.add(marker)
                }
                mapView.invalidate()
            }
        )

        // 7. 左上角距離顯示儀
        val distance = remember(points.size, locationOverlay.value?.myLocation) {
            val myLoc = locationOverlay.value?.myLocation
            val target = points.lastOrNull()
            if (myLoc != null && target != null) myLoc.distanceToAsDouble(target) else null
        }

        if (distance != null) {
            Surface(
                modifier = Modifier.padding(top = 64.dp, start = 16.dp).align(Alignment.TopStart),
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (distance > 1000) "距離: ${String.format("%.2f", distance / 1000)} km" else "距離: ${distance.toInt()} m",
                    color = Color.Green,
                    modifier = Modifier.padding(10.dp),
                    style = androidx.compose.ui.text.TextStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 20.sp)
                )
            }
        }

        // 8. 右上角按鈕組
        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(onClick = { isSatelliteMode = !isSatelliteMode }) {
                Text(if (isSatelliteMode) "一般模式" else "衛星模式")
            }
            Button(onClick = {
                locationOverlay.value?.enableFollowLocation()
                mapReference?.controller?.animateTo(locationOverlay.value?.myLocation)
            }) {
                Text("回到我的位置")
            }
            Button(onClick = { points.clear() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
                Text("清除導航", color = Color.White)
            }
            Button(onClick = {
                mapReference?.let { map ->
                    val cache = org.osmdroid.tileprovider.cachemanager.CacheManager(map)
                    cache.downloadAreaAsync(context, map.boundingBox, 15, 18)
                    android.widget.Toast.makeText(context, "開始預載...", android.widget.Toast.LENGTH_SHORT).show()
                }
            }) {
                Text("預載此區")
            }
        }
    }
}