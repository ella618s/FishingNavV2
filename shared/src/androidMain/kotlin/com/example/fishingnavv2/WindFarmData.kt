import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WindPowerResponse(
    val type: String,
    val features: List<WindFeature> // API 的核心在 features 陣列裡
)

@Serializable
data class WindFeature(
    val type: String,
    val properties: WindProperties, // 存放風場名稱等資訊
    val geometry: WindGeometry      // 存放座標資訊
)

@Serializable
data class WindProperties(
    // 假設 API 傳回的 Key 是 "wpname"，我們對應到變數 Name
    val wpname: String = "未知風場",
    val County: String? = null,
    val Area: Double? = null
)

@Serializable
data class WindGeometry(
    val type: String,
    val coordinates: JsonArray       // GeoJSON 座標層級很多，先用 JsonArray 接收最安全
)

@Serializable
data class CustomMarker(
    // 這裡我們把座標拆成 Double，這樣序列化存檔會更穩定
    val latitude: Double,
    val longitude: Double,
    var name: String
) {
    // 增加一個方便轉換回 GeoPoint 的工具方法
    fun toGeoPoint() = org.osmdroid.util.GeoPoint(latitude, longitude)
}

// --- 手機硬碟存取工具 ---
object MarkerStorage {
    private val json = Json { ignoreUnknownKeys = true }
    fun serialize(list: List<CustomMarker>) = json.encodeToString(list)
    fun deserialize(str: String) = try { json.decodeFromString<List<CustomMarker>>(str) } catch (e: Exception) { emptyList() }
}