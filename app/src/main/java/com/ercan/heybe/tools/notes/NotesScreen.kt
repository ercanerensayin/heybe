package com.ercan.heybe.tools.notes

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ercan.heybe.core.MiniMd
import com.ercan.heybe.data.FileStore
import com.ercan.heybe.data.Recorder
import com.ercan.heybe.tools.pdf.PdfEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Düzenlenen şey: uygulama içi not ya da telefondaki bir dosya. */
private sealed class Target {
    data class Internal(val file: File) : Target()
    data class External(val uri: Uri, val name: String) : Target()
}

@Composable
fun NotesScreen(initialText: String?, onSpeedRead: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf(NoteStore.list(ctx)) }
    var editing by remember { mutableStateOf<Target?>(null) }
    var startText by remember { mutableStateOf("") }

    fun refresh() { notes = NoteStore.list(ctx) }

    LaunchedEffect(Unit) {
        if (!initialText.isNullOrBlank()) {
            val f = NoteStore.create(ctx); NoteStore.save(f, initialText); startText = initialText; editing = Target.Internal(f)
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NoteStore.readUri(ctx, u) } }
                .onSuccess { startText = it; editing = Target.External(u, PdfEngine.displayName(ctx, u)) }
                .onFailure { Toast.makeText(ctx, "Açılamadı: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    val e = editing
    if (e != null) {
        Editor(e, startText, onSpeedRead, onClose = { editing = null; refresh() })
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { val f = NoteStore.create(ctx); startText = ""; editing = Target.Internal(f) }) { Text("Yeni not") }
            OutlinedButton(onClick = { openFile.launch(arrayOf("text/*")) }) { Text("TXT / MD dosyası aç") }
        }
        if (notes.isEmpty()) {
            Text("Henüz not yok. Notlar Markdown destekler: # başlık, **kalın**, *eğik*, - madde, - [ ] yapılacak.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(notes, key = { it.file.name }) { n ->
                Card(Modifier.fillMaxWidth().clickable { startText = runCatching { n.file.readText() }.getOrDefault(""); editing = Target.Internal(n.file) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(n.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (n.preview.isNotBlank()) Text(n.preview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(DateUtils.getRelativeTimeSpanString(n.modified).toString(), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Editor(target: Target, initial: String, onSpeedRead: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initial) }
    var saved by remember { mutableStateOf(initial) }
    var preview by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }

    suspend fun persist() {
        if (text == saved) return
        val t = text
        runCatching {
            withContext(Dispatchers.IO) {
                when (target) {
                    is Target.Internal -> NoteStore.save(target.file, t)
                    is Target.External -> NoteStore.writeUri(ctx, target.uri, t)
                }
            }
        }.onSuccess { saved = t }.onFailure { Toast.makeText(ctx, "Kaydedilemedi: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(text) { delay(800); persist() }
    fun close() {
        scope.launch {
            persist()
            if (target is Target.Internal && text.isBlank()) target.file.delete()
            onClose()
        }
    }
    BackHandler { close() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { close() }) { Text("‹ Notlar") }
            Text(if (target is Target.External) target.name else "", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { preview = !preview }) { Text(if (preview) "Düzenle" else "Önizle") }
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮", fontSize = 20.sp) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Paylaş") }, onClick = {
                        menu = false
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Paylaş"))
                    })
                    DropdownMenuItem(text = { Text("Hızlı oku") }, onClick = { menu = false; scope.launch { persist(); onSpeedRead(text) } })
                    listOf("md" to "Markdown (.md) olarak kaydet", "txt" to "Metin (.txt) olarak kaydet").forEach { (ext, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            menu = false
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        val mime = if (ext == "md") "text/markdown" else "text/plain"
                                        val s = FileStore.save(ctx, FileStore.Folder.METIN, NoteStore.fileName(text, ext), mime) { it.write(text.toByteArray()) }
                                        Recorder.done(ctx, "notlar", s, mime); s
                                    }
                                }.onSuccess { Toast.makeText(ctx, "Kaydedildi: ${FileStore.locationLabel(FileStore.Folder.METIN)}/${it.name}", Toast.LENGTH_LONG).show() }
                                    .onFailure { Toast.makeText(ctx, "Kaydedilemedi: ${it.message}", Toast.LENGTH_LONG).show() }
                            }
                        })
                    }
                    if (target is Target.Internal) DropdownMenuItem(text = { Text("Notu sil") }, onClick = { menu = false; askDelete = true })
                }
            }
        }
        if (preview) {
            SelectionContainer(Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { MdPreview(text) }
            }
        } else {
            TextField(text, { text = it }, Modifier.weight(1f).fillMaxWidth(),
                placeholder = { Text("Yazmaya başla…\n\n# Başlık\n- madde\n- [ ] yapılacak") },
                colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface))
        }
        val (w, c) = remember(text) { MiniMd.stats(text) }
        Text("$w kelime · $c karakter" + if (text == saved) " · kaydedildi" else "", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    }

    if (askDelete && target is Target.Internal) {
        AlertDialog(onDismissRequest = { askDelete = false }, title = { Text("Not silinsin mi?") },
            confirmButton = { TextButton(onClick = { askDelete = false; target.file.delete(); onClose() }) { Text("Sil") } },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Vazgeç") } })
    }
}

@Composable
private fun MdPreview(src: String) {
    val fg = MaterialTheme.colorScheme.onSurface
    for (l in MiniMd.parse(src)) when (l) {
        is MiniMd.Line.H -> Text(styled(l.text), fontWeight = FontWeight.Bold, fontSize = when (l.level) { 1 -> 26.sp; 2 -> 22.sp; else -> 18.sp },
            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        is MiniMd.Line.Bullet -> Row(Modifier.padding(vertical = 2.dp)) {
            Text(when (l.done) { true -> "☑ "; false -> "☐ "; null -> "• " }, fontSize = 16.sp)
            Text(styled(l.text), fontSize = 16.sp, color = if (l.done == true) fg.copy(alpha = 0.5f) else fg)
        }
        is MiniMd.Line.Num -> Row(Modifier.padding(vertical = 2.dp)) { Text("${l.n}. ", fontSize = 16.sp); Text(styled(l.text), fontSize = 16.sp) }
        is MiniMd.Line.Quote -> Row(Modifier.padding(vertical = 4.dp).height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            Text(styled(l.text), fontStyle = FontStyle.Italic, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
        }
        is MiniMd.Line.P -> Text(styled(l.text), fontSize = 16.sp, modifier = Modifier.padding(vertical = 2.dp))
        MiniMd.Line.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
        MiniMd.Line.Blank -> Spacer(Modifier.height(8.dp))
    }
}

private fun styled(s: String) = buildAnnotatedString {
    MiniMd.spans(s).forEach { sp ->
        withStyle(SpanStyle(fontWeight = if (sp.bold) FontWeight.Bold else null, fontStyle = if (sp.italic) FontStyle.Italic else null,
            fontFamily = if (sp.code) FontFamily.Monospace else null)) { append(sp.text) }
    }
}
