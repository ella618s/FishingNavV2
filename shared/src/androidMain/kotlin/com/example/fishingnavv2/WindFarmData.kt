import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

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
    val Name: String? = null,        // 獲准風場名稱
    val County: String? = null,      // 所屬縣市
    val Area: Double? = null         // 面積
)

@Serializable
data class WindGeometry(
    val type: String,
    val coordinates: JsonArray       // GeoJSON 座標層級很多，先用 JsonArray 接收最安全
)