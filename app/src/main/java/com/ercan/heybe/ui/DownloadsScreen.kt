package com.ercan.heybe.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ercan.heybe.core.Category
import com.ercan.heybe.core.ToolRegistry
import com.ercan.heybe.data.FileStore
import com.ercan.heybe.data.HeybeDb
import com.ercan.heybe.data.HistoryItem
import com.ercan.heybe.data.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DownloadsScreen() {
    val ctx = LocalContext.current
    val dao = remember { HeybeDb.get(ctx).history() }
    val all by dao.all().collectAsState(initial = emptyList())
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = all.filter { item -> filter == null || ToolRegistry.byId(item.toolId)?.category?.name == filter }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("İndirilenler", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Kayıt yeri: " + FileStore.locationLabel(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Tümü") }) }
                items(Category.entries.filter { it != Category.WEB }) { c ->
                    FilterChip(selected = filter == c.name, onClick = { filter = c.name }, label = { Text(c.title) })
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                Text("Henüz bir işlem yok. Araçlarla oluşturduğun dosyalar ve video indirmeleri burada listelenecek.",
                    modifier = Modifier.padding(top = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(shown, key = { it.id }) { HistoryRow(it) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(item: HistoryItem) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val tool = ToolRegistry.byId(item.toolId)

    fun open() {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { FileStore.exists(ctx, item) }
            val intent = FileStore.openIntent(item)
            if (!ok || intent == null) Toast.makeText(ctx, "Dosya bulunamadı (silinmiş ya da taşınmış olabilir).", Toast.LENGTH_LONG).show()
            else runCatching { ctx.startActivity(intent) }
                .onFailure { Toast.makeText(ctx, "Bu dosyayı açacak bir uygulama yok.", Toast.LENGTH_LONG).show() }
        }
    }

    Box {
        Card(Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (item.status == Status.DONE) open() },
            onLongClick = { menu = true },
        )) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolBadge(tool, 36)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val whenText = DateUtils.getRelativeTimeSpanString(item.time).toString()
                    val parts = listOfNotNull(tool?.name, whenText,
                        if (item.size > 0) Formatter.formatShortFileSize(ctx, item.size) else null)
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when (item.status) {
                        Status.RUNNING -> {
                            Spacer(Modifier.padding(2.dp))
                            LinearProgressIndicator(progress = { item.progress / 100f }, modifier = Modifier.fillMaxWidth())
                            Text("%${item.progress}", style = MaterialTheme.typography.labelSmall)
                        }
                        Status.FAILED -> Text("Başarısız: " + (item.error ?: "bilinmeyen hata"),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (item.status == Status.DONE) {
                DropdownMenuItem(text = { Text("Aç") }, onClick = { menu = false; open() })
                DropdownMenuItem(text = { Text("Paylaş") }, onClick = {
                    menu = false
                    FileStore.shareIntent(item)?.let { runCatching { ctx.startActivity(it) } }
                })
                DropdownMenuItem(text = { Text("Dosyayı sil") }, onClick = {
                    menu = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            FileStore.delete(ctx, item)
                            HeybeDb.get(ctx).history().delete(item.id)
                        }
                        Toast.makeText(ctx, "Dosya silindi.", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            if (item.status == Status.RUNNING) {
                DropdownMenuItem(text = { Text("İptal et") }, onClick = {
                    menu = false
                    com.ercan.heybe.tools.video.DownloadWorker.cancel(ctx, item.id)
                })
            }
            DropdownMenuItem(text = { Text("Geçmişten kaldır") }, onClick = {
                menu = false
                scope.launch { withContext(Dispatchers.IO) { HeybeDb.get(ctx).history().delete(item.id) } }
            })
        }
    }
}
