package com.example.fishingnavv2

import retrofit2.http.POST


interface WindPowerApi {
    @POST("data/Economy/wpzone_approved?f=geojson")
    suspend fun getWindFarms(): String
}