package com.example.fishingnavv2

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun FishingMap(
    modifier: Modifier = Modifier,
    // 改成 Any，繞過 commonMain 不認識 GeoPoint 的問題
    fishingPoints: Any
)