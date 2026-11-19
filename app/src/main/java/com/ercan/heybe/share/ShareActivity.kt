package com.ercan.heybe.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.ercan.heybe.MainActivity
import com.ercan.heybe.core.ShareRouter
import com.ercan.heybe.core.ShareRouter.Route
import com.ercan.heybe.core.ToolRegistry
import com.ercan.heybe.data.Recorder
import com.ercan.heybe.tools.video.DownloadWorker
import com.ercan.heybe.tools.video.Quality
import com.ercan.heybe.ui.ToolBadge
import com.ercan.heybe.ui.theme.HeybeTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** "Paylaş → Heybe" girişi ve sabit kısayolların ortak karşılayıcısı. */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shortcut = ShareRouter.Shortcut.fromClassName(intent.component?.className)
        val uris = sharedUris()
        // UYAP belgeleri: paylaşılan ya da "Birlikte aç" ile gelen .udf dosyaları
        val names = uris.map { com.ercan.heybe.tools.pdf.PdfEngine.displayName(this, it) }
        if (names.isNotEmpty() && names.all { it.endsWith(".udf", true) }) { open("uyap", null, uris); finish(); return }
        if (intent.action == Intent.ACTION_VIEW) { toast("Heybe bu dosyayı açamıyor: ${names.firstOrNull() ?: ""}"); finish(); return }
        val mimes = if (uris.isEmpty()) listOf(intent.type ?: "text/plain")
            else uris.map { contentResolver.getType(it) ?: intent.type ?: "" }
        val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT))
            .joinToString(" ").ifBlank { null }

        when (val r = ShareRouter.route(mimes, text, shortcut)) {
            is Route.StartVideo -> MainScope().launch {
                runCatching {
                    val id = Recorder.start(applicationContext, "video_indir", r.url, "video/mp4")
                    DownloadWorker.enqueue(applicationContext, id, r.url, Quality.BEST)
                }.onSuccess { toast("İndirme başladı (en yüksek kalite). İlerleme bildirimde ve İndirilenler'de.") }
                    .onFailure { toast("İndirme başlatılamadı: ${it.message}") }
                finish()
            }
            is Route.OpenTool -> { open(r.toolId, r.text ?: text, uris); finish() }
            is Route.Unsupported -> { toast(r.message); finish() }
            is Route.Choose -> setContent {
                HeybeTheme {
                    AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text("Hangi araçla açılsın?") },
                        text = {
                            Column {
                                r.toolIds.mapNotNull { ToolRegistry.byId(it) }.forEach { tool ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable { open(tool.id, text, uris); finish() }.padding(vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        ToolBadge(tool, 32)
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text(tool.name, style = MaterialTheme.typography.titleMedium)
                                            Text(tool.description, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = { TextButton(onClick = { finish() }) { Text("Vazgeç") } },
                    )
                }
            }
        }
    }

    private fun sharedUris(): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        else -> emptyList()
    }

    private fun open(toolId: String, text: String?, uris: List<Uri>) {
        SharedInbox.put(text, uris)
        val i = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TOOL, toolId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.isNotEmpty()) {
            val clip = ClipData.newRawUri("heybe", uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            i.clipData = clip
        }
        startActivity(i)
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
}
