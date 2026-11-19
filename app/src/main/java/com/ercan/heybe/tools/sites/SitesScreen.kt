package com.ercan.heybe.tools.sites

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ercan.heybe.core.SiteUrl
import com.ercan.heybe.core.ToolRegistry
import com.ercan.heybe.ui.ToolBadge

@Composable
fun SitesScreen() {
    val ctx = LocalContext.current
    var sites by remember { mutableStateOf(SiteStore.all(ctx)) }
    var editing by remember { mutableStateOf<Site?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Kaydettiğin siteler Chrome'un uygulama içi sekmesinde açılır; Chrome'daki şifrelerin ve oturumların geçerlidir. Uzun basınca düzenleme, sıralama ve ana ekrana ekleme seçenekleri çıkar.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { editing = Site(System.currentTimeMillis(), "", "") }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("Site ekle")
        }
        if (sites.isEmpty()) Text("Henüz site yok. Örneğin turkiye.gov.tr ya da okulunun öğrenci sistemi.",
            modifier = Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn {
            items(sites, key = { it.id }) { s ->
                SiteRow(s, onEdit = { editing = s }, onChange = { sites = it })
            }
        }
    }

    editing?.let { e ->
        var name by remember(e.id) { mutableStateOf(e.name) }
        var url by remember(e.id) { mutableStateOf(e.url) }
        val normalized = SiteUrl.normalize(url)
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (e.name.isEmpty() && e.url.isEmpty()) "Site ekle" else "Siteyi düzenle") },
            text = {
                Column {
                    OutlinedTextField(url, { url = it }, label = { Text("Adres") }, placeholder = { Text("ör. turkiye.gov.tr") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = url.isNotBlank() && normalized == null)
                    OutlinedTextField(name, { name = it }, label = { Text("Ad (isteğe bağlı)") }, singleLine = true, modifier = Modifier.padding(top = 4.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = normalized != null, onClick = {
                    val n = normalized ?: return@TextButton
                    sites = SiteStore.upsert(ctx, Site(e.id, name.trim().ifEmpty { SiteUrl.defaultName(n) }, n))
                    editing = null
                }) { Text("Kaydet") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Vazgeç") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SiteRow(s: Site, onEdit: () -> Unit, onChange: (List<Site>) -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.padding(vertical = 4.dp)) {
        Card(Modifier.fillMaxWidth().combinedClickable(onClick = { SiteOpener.open(ctx, s.url) }, onLongClick = { menu = true })) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolBadge(ToolRegistry.byId("siteler")?.copy(name = s.name), 36)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Aç") }, onClick = { menu = false; SiteOpener.open(ctx, s.url) })
            DropdownMenuItem(text = { Text("Düzenle") }, onClick = { menu = false; onEdit() })
            DropdownMenuItem(text = { Text("Ana ekrana ekle") }, onClick = {
                menu = false
                if (!SiteOpener.pinToHome(ctx, s)) Toast.makeText(ctx, "Ana ekranın kısayol eklemeyi desteklemiyor.", Toast.LENGTH_LONG).show()
            })
            DropdownMenuItem(text = { Text("Yukarı taşı") }, onClick = { menu = false; onChange(SiteStore.move(ctx, s.id, -1)) })
            DropdownMenuItem(text = { Text("Aşağı taşı") }, onClick = { menu = false; onChange(SiteStore.move(ctx, s.id, 1)) })
            DropdownMenuItem(text = { Text("Sil") }, onClick = { menu = false; onChange(SiteStore.delete(ctx, s.id)) })
        }
    }
}
