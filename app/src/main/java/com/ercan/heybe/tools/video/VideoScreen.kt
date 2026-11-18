package com.ercan.heybe.tools.video

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ercan.heybe.data.Recorder
import com.ercan.heybe.ui.rememberWithPermissions
import com.ercan.heybe.ui.storagePermissions
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoScreen(initialText: String? = null, onStarted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf(initialText.orEmpty()) }
    var menu by remember { mutableStateOf(false) }
    val perms = storagePermissions() +
        (if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
    val withPerms = rememberWithPermissions(perms, "İzin verilmedi; video yine de indirilebilir ama ilerleme bildirimi görünmeyebilir.")

    fun start(q: Quality) {
        val url = VideoOptions.extractUrl(text)
        if (url == null) { Toast.makeText(ctx, "Geçerli bir link bulunamadı.", Toast.LENGTH_LONG).show(); return }
        val go = {
            scope.launch {
                val mime = if (q == Quality.MP3) "audio/mpeg" else "video/mp4"
                val id = Recorder.start(ctx, "video_indir", url, mime)
                DownloadWorker.enqueue(ctx, id, url, q)
                Toast.makeText(ctx, "İndirme başladı (${q.label}).", Toast.LENGTH_SHORT).show()
                text = ""
                onStarted()
            }
            Unit
        }
        withPerms(go)
    }

    Column(Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Video linki") },
            placeholder = { Text("https://…") },
            minLines = 2,
        )
        TextButton(onClick = {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
            if (clip.isNullOrBlank()) Toast.makeText(ctx, "Pano boş.", Toast.LENGTH_SHORT).show() else text = clip
        }) { Text("Panodan yapıştır") }
        Spacer(Modifier.height(8.dp))

        Box {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp)
                    .combinedClickable(onClick = { start(Quality.BEST) }, onLongClick = { menu = true }),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("İndir — en yüksek kalite", fontWeight = FontWeight.Bold)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Quality.entries.forEach { q ->
                    DropdownMenuItem(text = { Text(q.label) }, onClick = { menu = false; start(q) })
                }
            }
        }
        Text("Kalite seçmek için düğmeye uzun bas.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(16.dp))
        Text("• Video Download/Heybe/Video klasörüne kaydedilir; ilerlemeyi İndirilenler sekmesinden izleyebilirsin.\n" +
            "• İlk indirmede bileşenler hazırlanır ve yt-dlp güncellenir; bu yaklaşık bir dakika sürebilir.\n" +
            "• 4K gibi bazı videolar MP4 olarak sunulmadığında kaliteyi korumak için MKV olarak kaydedilir.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
