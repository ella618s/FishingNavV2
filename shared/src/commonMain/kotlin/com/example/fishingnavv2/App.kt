package com.example.fishingnavv2

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme

@Composable
fun App() {
    MaterialTheme {
        // 使用 Any 類型來建立清單，避開 commonMain 不認識 osmdroid 的問題
        val fishingPoints = remember { mutableStateListOf<Any>() }

        // 呼叫地圖組件，傳入清單與填滿螢幕的 Modifier
        FishingMap(
            modifier = Modifier.fillMaxSize(),
            fishingPoints = fishingPoints
        )
    }
}