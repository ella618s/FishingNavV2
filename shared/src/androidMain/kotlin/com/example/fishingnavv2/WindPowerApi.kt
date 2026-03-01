package com.example.fishingnavv2

// 確保這一行 Import 正確對準你的 Data Class
import retrofit2.http.GET

interface WindPowerApi {
    @GET("data/Economy/wpzone_approved?f=geojson")
    suspend fun getWindFarms(): WindFarmResponse
}