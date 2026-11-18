package com.ercan.heybe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ercan.heybe.ui.HeybeRoot
import com.ercan.heybe.ui.theme.HeybeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val crashFile = (application as HeybeApp).crashFile()
        val lastCrash = if (crashFile.exists()) crashFile.readText().also { crashFile.delete() } else null
        setContent {
            HeybeTheme {
                HeybeRoot()
                var crash by remember { mutableStateOf(lastCrash) }
                crash?.let { text ->
                    AlertDialog(
                        onDismissRequest = { crash = null },
                        title = { Text("Uygulama son açılışta çöktü") },
                        text = {
                            Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Heybe hata", text))
                                crash = null
                            }) { Text("Kopyala") }
                        },
                        dismissButton = { TextButton(onClick = { crash = null }) { Text("Kapat") } },
                    )
                }
            }
        }
    }
}
