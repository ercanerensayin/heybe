package com.ercan.heybe.tools.pdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ercan.heybe.data.FileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Araç ekranlarının ortak durumu: çalışıyor mu, ilerleme, sonuç, hata. */
class ToolRun {
    var busy by mutableStateOf(false)
    var progress by mutableStateOf<String?>(null)
    var result by mutableStateOf<PdfResult?>(null)
    var error by mutableStateOf<String?>(null)

    fun start(scope: CoroutineScope, block: suspend (report: (String) -> Unit) -> PdfResult) {
        if (busy) return
        busy = true; result = null; error = null; progress = null
        scope.launch {
            try {
                result = withContext(Dispatchers.IO) { block { msg -> progress = msg } }
            } catch (e: PdfUserError) {
                error = e.message
            } catch (e: OutOfMemoryError) {
                error = "Bellek yetmedi; daha küçük bir dosya ya da daha güçlü sıkıştırma dene."
            } catch (e: Exception) {
                error = "Beklenmeyen hata: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false; progress = null
            }
        }
    }
}

@Composable
fun RunStatus(run: ToolRun) {
    val ctx = LocalContext.current
    if (run.busy) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(run.progress ?: "İşleniyor…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
    run.error?.let {
        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
    run.result?.let { r ->
        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(r.summary, style = MaterialTheme.typography.bodyMedium)
                Text("Kayıt yeri: " + FileStore.locationLabel(FileStore.Folder.PDF), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.files.size == 1) {
                    val f = r.files.first()
                    Text(f.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedButton(onClick = { openFile(ctx, f.uri) }) { Text("Aç") }
                        OutlinedButton(onClick = { shareFile(ctx, f.uri) }) { Text("Paylaş") }
                    }
                } else {
                    Text("Dosyalar İndirilenler sekmesinde de listelendi.", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

fun openFile(ctx: Context, uri: Uri, mime: String = "application/pdf") {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(i) }.onFailure { Toast.makeText(ctx, "Bu dosyayı açacak bir uygulama yok.", Toast.LENGTH_LONG).show() }
}

fun shareFile(ctx: Context, uri: Uri, mime: String = "application/pdf") {
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(send, "Paylaş")) }
}

/** Sıralanabilir dosya listesi (yukarı/aşağı ve kaldır). */
@Composable
fun FileList(uris: List<Uri>, onChange: (List<Uri>) -> Unit, reorder: Boolean = true) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        uris.forEachIndexed { i, uri ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. " + PdfEngine.displayName(ctx, uri), Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    if (reorder) {
                        IconButton(onClick = { if (i > 0) onChange(uris.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, "Yukarı")
                        }
                        IconButton(onClick = { if (i < uris.lastIndex) onChange(uris.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < uris.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, "Aşağı")
                        }
                    }
                    IconButton(onClick = { onChange(uris.toMutableList().apply { removeAt(i) }) }) { Icon(Icons.Filled.Close, "Kaldır") }
                }
            }
        }
    }
}

/** Seçilen dosyaya kalıcı okuma izni (uygulama yeniden açılsa da liste bozulmasın). */
fun keepAccess(ctx: Context, uris: List<Uri>) {
    uris.forEach { runCatching { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
}
