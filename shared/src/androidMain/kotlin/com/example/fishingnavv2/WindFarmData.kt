package com.example.fishingnavv2

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonArray

@Serializable
data class WindFarmResponse(
    @SerialName("type") val type: String? = null,
    @SerialName("features") val features: List<Feature>? = null
)

@Serializable
data class Feature(
    @SerialName("type") val type: String? = null,
    @SerialName("properties") val properties: WindFarmProperties? = null,
    @SerialName("geometry") val geometry: Geometry? = null
)

@Serializable
data class WindFarmProperties(
    @SerialName("計畫名稱") val planName: String? = null
)

@Serializable
data class Geometry(
    @SerialName("type") val type: String? = null,
    // 使用 JsonArray 避開 Kotlin 四層 List 的編譯紅字
    @SerialName("coordinates") val coordinates: JsonArray? = null
)