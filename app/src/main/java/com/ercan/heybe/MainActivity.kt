package com.ercan.heybe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import com.ercan.heybe.ui.OpenRequest
import com.ercan.heybe.ui.theme.HeybeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TOOL = "heybe_tool"
        const val EXTRA_UPDATE = "heybe_update"
    }

    private var openRequest by mutableStateOf<OpenRequest?>(null)

    private fun readIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_TOOL)?.let { openRequest = OpenRequest(it, System.nanoTime()) }
        intent?.removeExtra(EXTRA_TOOL)
        if (intent?.getBooleanExtra(EXTRA_UPDATE, false) == true) { intent.removeExtra(EXTRA_UPDATE); checkUpdate(force = true) }
    }

    /** Açılışta en fazla 12 saatte bir sessizce denetler; yeni sürüm varsa pencere açılır. */
    private fun checkUpdate(force: Boolean) {
        val prefs = getSharedPreferences("guncelleme", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("son_denetim", 0L) < 12 * 60 * 60 * 1000L) return
        prefs.edit().putLong("son_denetim", now).apply()
        kotlinx.coroutines.MainScope().launch {
            val r = com.ercan.heybe.update.Updater.check(applicationContext)
            if (r is com.ercan.heybe.update.Updater.Check.NewVersion) com.ercan.heybe.update.Updater.pending = r.available
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) { readIntent(intent); checkUpdate(force = false) }
        val crashFile = (application as HeybeApp).crashFile()
        val lastCrash = if (crashFile.exists()) crashFile.readText().also { crashFile.delete() } else null
        setContent {
            HeybeTheme {
                HeybeRoot(openRequest) { openRequest = null }
                com.ercan.heybe.update.UpdateDialog()
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
