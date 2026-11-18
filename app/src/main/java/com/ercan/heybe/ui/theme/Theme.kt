package com.ercan.heybe.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.ercan.heybe.core.Category

fun Category.color(): Color = when (this) {
    Category.BELGE -> Color(0xFF1E88E5)
    Category.MEDYA -> Color(0xFFE53935)
    Category.HESAP -> Color(0xFF43A047)
}

@Composable
fun HeybeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFD7A86E))
        else -> lightColorScheme(primary = Color(0xFF8D5A2B))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
