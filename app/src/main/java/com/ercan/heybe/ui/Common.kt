package com.ercan.heybe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ercan.heybe.core.Tool
import com.ercan.heybe.ui.theme.color

/** Aracın kategori renginde, baş harfli yuvarlak rozeti. Araç bilinmiyorsa gri. */
@Composable
fun ToolBadge(tool: Tool?, sizeDp: Int) {
    Box(
        Modifier.size(sizeDp.dp).background(tool?.category?.color() ?: Color.Gray, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(tool?.name?.take(1) ?: "?", color = Color.White, fontWeight = FontWeight.Bold,
            style = if (sizeDp < 30) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleMedium)
    }
}
