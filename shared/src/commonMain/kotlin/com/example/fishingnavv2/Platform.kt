package com.example.fishingnavv2

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform