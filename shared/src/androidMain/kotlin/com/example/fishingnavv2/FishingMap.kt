package com.example.fishingnavv2

import CustomMarker
import WindPowerResponse
import android.content.Context
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit


@Composable
actual fun FishingMap(
    modifier: Modifier,
    fishingPoints: Any
) {
    val points = fishingPoints as androidx.compose.runtime.snapshots.SnapshotStateList<Any>
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("fishing_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    // --- 新增：存放風場資料的狀態 ---
    // 這裡的 WindPowerResponse 要對應在 WindFarmData.kt 定義的 Class 名稱
    var windFarmResponse by remember { mutableStateOf<WindPowerResponse?>(null) }
    val sharedPrefs = remember { context.getSharedPreferences("MapData", android.content.Context.MODE_PRIVATE) }

    // 3. 🚀 把「儲存」與「讀取」函式寫在這裡
    fun saveMarkersToDisk(markers: List<CustomMarker>) {
        val gson = com.google.gson.Gson()
        val json = gson.toJson(markers)
        sharedPrefs.edit().putString("saved_markers", json).apply()
    }

    fun loadMarkersFromDisk(prefs: android.content.SharedPreferences): List<CustomMarker> {
        val gson = com.google.gson.Gson()
        val json = prefs.getString("saved_markers", null)
        return if (json != null) {
            val type = object : com.google.gson.reflect.TypeToken<List<CustomMarker>>() {}.type
            gson.fromJson(json, type)
        } else emptyList()
    }

    val myMarkers = remember {
        androidx.compose.runtime.mutableStateListOf<CustomMarker>().apply {
            // 呼叫下方的讀取函式
            val saved = loadMarkersFromDisk(sharedPrefs)
            addAll(saved)
        }
    }

    // 控制視窗與編輯狀態
    var showMarkerDialog by remember { mutableStateOf(false) }
    var tempLocation by remember { mutableStateOf<org.osmdroid.util.GeoPoint?>(null) }
    var pendingPoint by remember { mutableStateOf<org.osmdroid.util.GeoPoint?>(null) }
    var editingMarker by remember { mutableStateOf<CustomMarker?>(null) }
    var markerNameInput by remember { mutableStateOf("") }

    fun startEditing(pt: CustomMarker) {
        editingMarker = pt
        markerNameInput = pt.name
        showMarkerDialog = true
    }

    // --- 新增：Retrofit 初始化 ---
    val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true // 忽略沒寫到的欄位 (如 crs)，防止噴錯
        isLenient = true
    }

    // 2. 初始化 API 服務
    val api = remember {
        Retrofit.Builder()
            .baseUrl("https://windpower.geologycloud.tw/")
            // 這是 Retrofit 官方的，通常比較不會有版本衝突
            .addConverterFactory(retrofit2.converter.scalars.ScalarsConverterFactory.create())
            .build()
            .create(WindPowerApi::class.java)
    }

    var mapReference by remember { mutableStateOf<org.osmdroid.views.MapView?>(null) }
    var isSatelliteMode by remember { mutableStateOf(false) }
    val locationOverlay =
        remember { mutableStateOf<org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay?>(null) }

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                val jsonString = api.getWindFarms()
                // 手動把字串轉成物件，這樣就算 Converter 紅字也沒關係
                val response = json.decodeFromString<WindPowerResponse>(jsonString)

                withContext(Dispatchers.Main) {
                    // 關鍵：把抓到的資料存進剛才宣告的變數，這樣 update 區塊就看得到了
                    windFarmResponse = response
                }

                if (response.features.isNotEmpty()) {
                    // 1. 轉換為 GeoPoint 列表
                    val geoPoints = response.features.mapNotNull { feature ->
                        try {
                            val coords = feature.geometry.coordinates
                            // 因為 Log 顯示有三層括號 [[[...]]]，我們要一路點進去拿到數字
                            // 假設我們只取該區域的第一個點作為代表
                            val firstRing = coords[0] as List<*>
                            val firstPoint = firstRing[0] as List<*>

                            val lon = firstPoint[0].toString().toDouble()
                            val lat = firstPoint[1].toString().toDouble()

                            org.osmdroid.util.GeoPoint(lat, lon)
                        } catch (e: Exception) {
                            null // 如果這筆資料格式不對，就跳過它
                        }
                    }

                    // 2. 切換回主執行緒更新 UI 狀態
                    withContext(Dispatchers.Main) {
                        points.clear()
                        points.addAll(geoPoints)

                        // 如果妳想讓地圖自動跳到第一個點位
                        // mapView.controller.setCenter(geoPoints[0])
                    }

                    android.util.Log.d("API_DEBUG", "成功解析 14 個點位！")
                }
            } catch (e: Exception) {
                android.util.Log.e("API_DEBUG", "錯誤：${e.message}")
            }
        }
    }

    // --- 這裡是在 FishingMap 函式內部 ---

    if (showMarkerDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showMarkerDialog = false },
            title = { Text(if (editingMarker != null) "修改名稱" else "新增導航點") },
            text = {
                // 這裡放輸入框
                TextField(
                    value = markerNameInput,
                    onValueChange = { markerNameInput = it },
                    label = { Text("請輸入名稱") }
                )
            },
            // 👇 就是這裡！confirmButton 是 AlertDialog 的參數
            confirmButton = {
                Button(onClick = {
                    if (editingMarker != null) {
                        // --- 模式 A：修改名稱 ---
                        editingMarker?.let { markerData ->
                            // 1. 更新數據
                            markerData.name = markerNameInput

                            // 2. 🎯 同步更新地圖上的氣泡內容
                            mapReference?.overlays?.filterIsInstance<org.osmdroid.views.overlay.Marker>()?.forEach { m ->
                                // 🚀 關鍵比對：必須使用 latitude/longitude 全寫來對應妳的類別定義
                                if (m.position.latitude == markerData.latitude && m.position.longitude == markerData.longitude) {
                                    m.title = markerNameInput
                                    // 讓氣泡刷新顯示新名稱
                                    if (m.isInfoWindowShown) {
                                        m.closeInfoWindow()
                                        m.showInfoWindow()
                                    }
                                }
                            }
                        }
                    } else {
                        // --- 模式 B：新增標記 ---
                        val newMarker = CustomMarker(
                            name = markerNameInput,
                            // 🚀 使用妳在 singleTap 存好的 tempLocation
                            latitude = tempLocation?.latitude ?: 0.0,
                            longitude = tempLocation?.longitude ?: 0.0
                        )
                        myMarkers.add(newMarker)
                    }

                    saveMarkersToDisk(myMarkers)
                    // 🎯 刷新與歸零
                    showMarkerDialog = false
                    editingMarker = null
                    tempLocation = null
                    mapReference?.invalidate()
                }) { Text("確定") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showMarkerDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                org.osmdroid.config.Configuration.getInstance().load(
                    ctx,
                    android.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                )
                org.osmdroid.config.Configuration.getInstance().userAgentValue = ctx.packageName

                org.osmdroid.views.MapView(ctx).apply {
                    mapReference = this
                    setMultiTouchControls(true)
                    controller.setZoom(17.0)

                    // 1. MBTiles 離線地圖支援
                    val mbtilesFile =
                        java.io.File(context.getExternalFilesDir(null), "fishing_map.mbtiles")
                    if (mbtilesFile.exists()) {
                        val mbtilesProvider = org.osmdroid.tileprovider.modules.OfflineTileProvider(
                            org.osmdroid.tileprovider.util.SimpleRegisterReceiver(context),
                            arrayOf(mbtilesFile)
                        )
                        setTileProvider(mbtilesProvider)
                        setTileSource(
                            org.osmdroid.tileprovider.tilesource.FileBasedTileSource.getSource(
                                mbtilesFile.name
                            )
                        )
                    }

                    // 2. 定位圖標修正 (解決偏移問題)
                    val overlay = org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay(
                        org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider(ctx), this
                    )

                    // 重新設計 Bitmap：讓 (30,30) 成為三角形的幾何中心
                    val bitSize = 60
                    val bit = android.graphics.Bitmap.createBitmap(
                        bitSize,
                        bitSize,
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
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

                    // 1. 🚀 處理地圖點擊 (暫存點位與新增)
                    val mapEventsReceiver = object : org.osmdroid.events.MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: org.osmdroid.util.GeoPoint?): Boolean {
                            p?.let {
                                tempLocation = it // 🎯 暫存點位座標
                                markerNameInput = "新點位 ${myMarkers.size + 1}"
                                editingMarker = null // 🎯 確保現在是「新增」模式
                                showMarkerDialog = true
                            }
                            return true
                        }
                        override fun longPressHelper(p: org.osmdroid.util.GeoPoint?): Boolean = false
                    }
                    overlays.add(org.osmdroid.views.overlay.MapEventsOverlay(mapEventsReceiver))
                }
            },
            update = { mapView ->
                // 4. 衛星與普通模式切換
                if (isSatelliteMode) {
                    val googleSatellite =
                        object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
                            "Google-Satellite", 0, 20, 256, ".jpg",
                            arrayOf("https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}")
                        ) {
                            override fun getTileURLString(pTileIndex: Long): String = baseUrl
                                .replace(
                                    "{x}",
                                    org.osmdroid.util.MapTileIndex.getX(pTileIndex).toString()
                                )
                                .replace(
                                    "{y}",
                                    org.osmdroid.util.MapTileIndex.getY(pTileIndex).toString()
                                )
                                .replace(
                                    "{z}",
                                    org.osmdroid.util.MapTileIndex.getZoom(pTileIndex).toString()
                                )
                        }
                    mapView.setTileSource(googleSatellite)
                } else {
                    val mbtilesFile =
                        java.io.File(context.getExternalFilesDir(null), "fishing_map.mbtiles")
                    if (!mbtilesFile.exists()) {
                        mapView.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
                    }
                }

                // 5. 導航線
                val myLoc = locationOverlay.value?.myLocation
                // 抓最後一個點做導航
                val target = (myMarkers.lastOrNull() ?: points.lastOrNull())
                mapView.overlays.filterIsInstance<org.osmdroid.views.overlay.Polyline>()
                    .forEach { mapView.overlays.remove(it) }
                if (myLoc != null && target != null) {
                    val line = org.osmdroid.views.overlay.Polyline(mapView).apply {
                        val targetPos =
                            if (target is CustomMarker) target.toGeoPoint() else target as? org.osmdroid.util.GeoPoint
                        targetPos?.let { setPoints(listOf(myLoc, it)) }
                        outlinePaint.color = android.graphics.Color.CYAN
                        outlinePaint.strokeWidth = 12f
                    }
                    mapView.overlays.add(line)
                }

                // 6. 標記標籤
                // --- A. 保留原本點擊產生的 Marker ---
                mapView.overlays.removeAll { it is org.osmdroid.views.overlay.Marker }
                val allMarkers = points + myMarkers

                // 這裡不要用 as GeoPoint，因為 points 裡面現在是 CustomMarker 物件了
//                allMarkers.forEach { pt ->
//                    val isCustom = pt is CustomMarker
//                    val marker = org.osmdroid.views.overlay.Marker(mapView).apply {
//                        position = if (isCustom) (pt as CustomMarker).toGeoPoint() else pt as org.osmdroid.util.GeoPoint
//                        title = if (isCustom) (pt as CustomMarker).name else "導航點"
//                        setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
//
//                        // 🚀 1. 點擊標記：執行導航線更新
//                        setOnMarkerClickListener { m, _ ->
//                            m.showInfoWindow()
//
//                            // 🎯 關鍵：更新妳用來畫導航線的目標座標變數
//                            pendingPoint = m.position // 將導航線終點設為目前點擊的標記位置
//                            // 🚀 只有當 pendingPoint 有值時，才畫導航線
//                            pendingPoint?.let { target ->
//                                mapReference?.let { map ->
//                                    // 移除所有的 Polyline (導航線)
//                                    map.overlays.removeAll { it is org.osmdroid.views.overlay.Polyline }
//                                }
//                                val line = org.osmdroid.views.overlay.Polyline().apply {
//                                    // 起點：妳目前的定位
//                                    addPoint(locationOverlay.value?.myLocation)
//                                    // 終點：妳剛才點擊的那個點
//                                    addPoint(target)
//                                    outlinePaint.color = android.graphics.Color.CYAN
//                                    outlinePaint.strokeWidth = 12f
//                                }
//                                mapView.overlays.add(line)
//                            }
//
//                            mapReference?.invalidate() // 🚀 強制重繪地圖
//                            android.widget.Toast.makeText(context, "導航目標：${m.title}", android.widget.Toast.LENGTH_SHORT).show()
//
//                            if (isCustom && m.isInfoWindowShown) {
//                                startEditing(pt as CustomMarker)
//                            }
//
//                            android.widget.Toast.makeText(context, "已導航！再次點擊標記可修改名稱", android.widget.Toast.LENGTH_SHORT).show()
//                            true
//                        }
//                    }
//                    mapView.overlays.add(marker)
//                }

                allMarkers.forEach { pt ->
                    val isCustom = pt is CustomMarker
                    val marker = org.osmdroid.views.overlay.Marker(mapView).apply {
                        position = if (isCustom) (pt as CustomMarker).toGeoPoint() else pt as org.osmdroid.util.GeoPoint
                        title = if (isCustom) (pt as CustomMarker).name else "導航點"
                        setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)

                        // 🚀 核心邏輯：用標記點擊搞定一切，不依賴氣泡點擊
                        setOnMarkerClickListener { m, _ ->
                            // 情況 A：如果氣泡已經是開著的 -> 代表這是「第二次點擊」 -> 跳出編輯視窗
                            if (isCustom && m.isInfoWindowShown) {
                                startEditing(pt as CustomMarker) //
                                m.closeInfoWindow() // 編輯時順手關掉氣泡
                            }
                            // 情況 B：氣泡還沒開 -> 這是「第一次點擊」 -> 執行導航換線 + 開氣泡
                            else {
                                // 1. 更新導航線
                                pendingPoint = m.position
                                mapReference?.let { map ->
                                    map.overlays.removeAll { it is org.osmdroid.views.overlay.Polyline }
                                    val line = org.osmdroid.views.overlay.Polyline().apply {
                                        addPoint(locationOverlay.value?.myLocation)
                                        addPoint(m.position)
                                        outlinePaint.color = android.graphics.Color.CYAN
                                        outlinePaint.strokeWidth = 12f
                                    }
                                    map.overlays.add(line)
                                }
                                // 2. 顯示氣泡並重新整理
                                m.showInfoWindow()
                                mapReference?.invalidate() //
                                android.widget.Toast.makeText(context, "已設為導航目標！再次點擊可修改名稱", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            true
                        }
                    }
                    mapView.overlays.add(marker)
                }

                // --- B. 處理 API 抓回來的風場區域 (Polygon) ---
                // 只移除舊的 Polygon
                mapView.overlays.removeAll { it is org.osmdroid.views.overlay.Polygon }

                windFarmResponse?.features?.forEach { feature ->
                    val polygon = org.osmdroid.views.overlay.Polygon(mapView)

                    // 使用防禦性轉型，避免 NumberFormatException
                    val coordinates = feature.geometry.coordinates as? List<*> ?: return@forEach
                    val firstRing = coordinates.getOrNull(0) as? List<*> ?: return@forEach

                    val geoPoints = firstRing.mapNotNull { pt ->
                        // 確保 pt 是一對座標 [lon, lat]，而不是整串文字
                        val coordPair = pt as? List<*> ?: return@mapNotNull null
                        val lon = coordPair.getOrNull(0)?.toString()?.toDoubleOrNull()
                            ?: return@mapNotNull null
                        val lat = coordPair.getOrNull(1)?.toString()?.toDoubleOrNull()
                            ?: return@mapNotNull null
                        org.osmdroid.util.GeoPoint(lat, lon)
                    }

                    if (geoPoints.isNotEmpty()) {
                        polygon.points = geoPoints
                        polygon.fillPaint.color =
                            android.graphics.Color.parseColor("#331E90FF") // 半透明藍
                        polygon.outlinePaint.color =
                            android.graphics.Color.parseColor("#FF1E90FF") // 深藍邊框
                        polygon.outlinePaint.strokeWidth = 5f

                        // --- C. 點擊顯示名稱 (Toast 功能) ---
                        val name = feature.properties.wpname
                        polygon.setOnClickListener { _, _, _ ->
                            android.widget.Toast.makeText(
                                context,
                                "區域：$name",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            true
                        }
                        mapView.overlays.add(polygon)
                    }
                }
                mapView.invalidate() // 統一刷新
            }
        )

        // 7. 左上角距離顯示儀
        val distance = remember(points.size, myMarkers.size, locationOverlay.value?.myLocation) {
            val myLoc = locationOverlay.value?.myLocation
            // 優先抓自訂點，沒有才抓 API 點
            val target = (myMarkers.lastOrNull() ?: points.lastOrNull())

            val targetPos = when (target) {
                is CustomMarker -> target.toGeoPoint()
                is org.osmdroid.util.GeoPoint -> target
                else -> null
            }

            if (myLoc != null && targetPos != null) {
                myLoc.distanceToAsDouble(targetPos)
            } else null
        }

        if (distance != null) {
            Surface(
                modifier = Modifier
                    .padding(top = 64.dp, start = 16.dp)
                    .align(Alignment.TopStart),
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (distance > 1000) "距離: ${
                        String.format(
                            "%.2f",
                            distance / 1000
                        )
                    } km" else "距離: ${distance.toInt()} m",
                    color = Color.Green,
                    modifier = Modifier.padding(10.dp),
                    style = androidx.compose.ui.text.TextStyle(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        fontSize = 20.sp
                    )
                )
            }
        }

        // 8. 右上角按鈕組
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 64.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(onClick = { isSatelliteMode = !isSatelliteMode }) {
                Text(if (isSatelliteMode) "一般模式" else "衛星模式")
            }
            Button(onClick = {
                val myLoc = locationOverlay.value?.myLocation
                if (myLoc != null) {
                    mapReference?.controller?.animateTo(myLoc)
                    mapReference?.controller?.setZoom(17.0)
                } else {
                    android.widget.Toast.makeText(
                        context,
                        "正在搜尋衛星訊號...",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }) { Text("回到我的位置") }
            Button(onClick = {
                // 1. 🚀 停止導航線繪製（清空座標目標）
                pendingPoint = null // 確保這行有執行，原本的藍線才會消失
                // 2. 🎯 關鍵修正：只關閉氣泡與移除線條，不要 removeAll Marker
                mapReference?.let { map ->
                    // A. 關閉所有正在顯示的氣泡 (InfoWindow)
                    map.overlays.filterIsInstance<org.osmdroid.views.overlay.Marker>().forEach { m ->
                        m.closeInfoWindow()
                    }
                    // B. 只移除導航線 (Polyline)，保留標記點 (Marker)
                    // 如果妳想保留地圖上的點，就不要執行移除 Marker 的動作
                    map.overlays.removeAll { it is org.osmdroid.views.overlay.Polyline }
                    // C. 刷新地圖
                    map.invalidate()
                }

                // 3. 📢 提示使用者
                android.widget.Toast.makeText(context, "導航已清除，標記已保留", android.widget.Toast.LENGTH_SHORT).show()
            }) {
                Text("清除導航")
            }
            // 🚀 大掃除（清除所有標記與存檔）
            Button(onClick = {
                // 1. 🎯 第一步：在清空數據前，先關閉地圖上所有顯示中的氣泡
                mapReference?.let { map ->
                    map.overlays.filterIsInstance<org.osmdroid.views.overlay.Marker>().forEach { m ->
                        m.closeInfoWindow() // 🚀 關鍵：讓所有懸浮的氣泡消失
                    }
                }

                // 2. 清空記憶體與導航狀態
                myMarkers.clear() //
                points.clear()    //
                pendingPoint = null // 同時取消導航線

                // 3. 清除手機內的持久化存檔
                sharedPrefs.edit().remove("saved_markers").apply() //

                // 4. 從視覺上徹底移除所有 Overlay (標記與線)
                mapReference?.let { map ->
                    map.overlays.removeAll { it is org.osmdroid.views.overlay.Marker } //
                    map.overlays.removeAll { it is org.osmdroid.views.overlay.Polyline } //
                    map.invalidate() // 🚀 刷新地圖，畫面立刻變乾淨
                }

                android.widget.Toast.makeText(context, "所有資料已清空", android.widget.Toast.LENGTH_SHORT).show()
            }) {
                Text("清除所有標記")
            }
            Button(onClick = {
                mapReference?.let { map ->
                    val tileSource = map.tileProvider.tileSource

                    if (tileSource.name() == "Mapnik") {
                        // 🚀 一般模式：徹底避開會閃退的 CacheManager
                        // 我們改用「強制重繪」地圖，這會讓地圖自動去抓當前畫面的圖資到暫存區
                        map.invalidate()
                        android.widget.Toast.makeText(
                            context,
                            "一般模式：已完成當前區域快取",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        // 🚀 衛星模式：通常沒限制，可以使用批量預載
                        try {
                            val cache = org.osmdroid.tileprovider.cachemanager.CacheManager(map)
                            val currentZoom = map.zoomLevelDouble.toInt()
                            cache.downloadAreaAsync(
                                context,
                                map.boundingBox,
                                currentZoom,
                                currentZoom
                            )
                            android.widget.Toast.makeText(
                                context,
                                "衛星模式：預載中...",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Exception) {
                            // 萬一衛星模式也限制，這裡能攔截異常不閃退
                            map.invalidate()
                            android.widget.Toast.makeText(
                                context,
                                "已完成當前快取",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }) { Text("預載此區") }
            mapReference?.invalidate()
        }
    }
}