package com.ercan.heybe.tools.reader

import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ercan.heybe.core.Rsvp
import com.ercan.heybe.core.doc.Block
import com.ercan.heybe.core.doc.Doc
import com.ercan.heybe.core.doc.plain
import com.ercan.heybe.tools.pdf.keepAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ReaderTheme(val bg: Color, val fg: Color, val name: String)
private val themes = listOf(
    ReaderTheme(Color(0xFFFFFFFF), Color(0xFF1C1C1C), "Açık"),
    ReaderTheme(Color(0xFFF4ECD8), Color(0xFF5B4636), "Sepya"),
    ReaderTheme(Color(0xFF121212), Color(0xFFD8D8D8), "Koyu"),
)

private class OpenBook(val key: String?, val uri: String?, val doc: Doc)

/** Okuyucu: kitap/belge okuma ve hızlı okuma. [initialText] verilirse doğrudan o metni açar. */
@Composable
fun ReaderScreen(initial: List<Uri>, initialText: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var book by remember { mutableStateOf<OpenBook?>(null) }
    var loading by remember { mutableStateOf<String?>(null) }
    var recents by remember { mutableStateOf(ReaderStore.recents(ctx)) }
    var paste by remember { mutableStateOf(false) }

    fun open(uri: Uri) {
        scope.launch {
            loading = "Açılıyor…"
            runCatching {
                withContext(Dispatchers.IO) { ReaderStore.load(ctx, uri) { m -> scope.launch { loading = m } } }
            }.onSuccess { (key, doc) ->
                ReaderStore.touch(ctx, uri.toString(), doc.title, ReaderStore.recents(ctx).firstOrNull { it.uri == uri.toString() }?.percent ?: 0)
                book = OpenBook(key, uri.toString(), doc)
            }.onFailure { Toast.makeText(ctx, (it as? ReaderError)?.message ?: "Açılamadı: ${it.message}", Toast.LENGTH_LONG).show() }
            loading = null
        }
    }

    LaunchedEffect(Unit) {
        when {
            !initialText.isNullOrBlank() -> book = OpenBook(null, null, ReaderStore.textDoc("Metin", initialText))
            initial.isNotEmpty() -> open(initial.first())
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { keepAccess(ctx, listOf(u)); open(u) }
    }

    val b = book
    if (b != null) {
        BookView(b, onClose = { book = null; recents = ReaderStore.recents(ctx) })
        return
    }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("EPUB, PDF, Word, UYAP (UDF), TXT ve MD dosyalarını okur. Kaldığın yeri hatırlar; yazı boyutu ve sayfa rengi ayarlanır. \"Hızlı oku\" metni kelime kelime gösterir.",
            style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pick.launch(arrayOf("application/epub+zip", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/*", "application/octet-stream", "application/zip")) }) { Text("Dosya aç") }
            OutlinedButton(onClick = { paste = true }) { Text("Metin yapıştır") }
        }
        loading?.let {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Text("Taranmış PDF'lerde metin tanıma yapıldığı için ilk açılış uzun sürebilir; sonraki açılışlar anında olur.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (recents.isNotEmpty()) {
            Text("Son okunanlar", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
            recents.forEach { r ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = loading == null) { open(Uri.parse(r.uri)) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("%${r.percent} · " + DateUtils.getRelativeTimeSpanString(r.time), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LinearProgressIndicator(progress = { r.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                        TextButton(onClick = { ReaderStore.forget(ctx, r.uri); recents = ReaderStore.recents(ctx) }) { Text("Kaldır") }
                    }
                }
            }
        }
    }

    if (paste) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { paste = false },
            title = { Text("Metin yapıştır") },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp), placeholder = { Text("Okumak istediğin metin") }) },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { paste = false; book = OpenBook(null, null, ReaderStore.textDoc("Metin", text)) }) { Text("Oku") } },
            dismissButton = { TextButton(onClick = { paste = false }) { Text("Vazgeç") } },
        )
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun BookView(book: OpenBook, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s0 = remember { ReaderStore.loadSettings(ctx) }
    var font by remember { mutableIntStateOf(s0.first) }
    var theme by remember { mutableIntStateOf(s0.second) }
    var serif by remember { mutableStateOf(s0.third) }
    var settings by remember { mutableStateOf(false) }
    var toc by remember { mutableStateOf(false) }
    var rsvpFrom by remember { mutableStateOf<Int?>(null) }
    val blocks = book.doc.blocks
    val start = remember { book.key?.let { ReaderStore.position(ctx, it) } ?: Pair(0, 0) }
    val list = rememberLazyListState(start.first.coerceIn(0, (blocks.size - 1).coerceAtLeast(0)), start.second)
    val th = themes[theme.coerceIn(0, 2)]
    val family = if (serif) FontFamily.Serif else FontFamily.SansSerif
    val percent by remember { derivedStateOf { if (blocks.size <= 1) 100 else (list.firstVisibleItemIndex * 100 / (blocks.size - 1)).coerceIn(0, 100) } }

    // Kelime sırası: her bloğun hızlı okumada başladığı kelime
    val words = remember(book) { blocks.map { it.plain()?.let(Rsvp::words) ?: emptyList() } }
    val offsets = remember(words) { words.runningFold(0) { a, w -> a + w.size } }
    val allWords = remember(words) { words.flatten() }

    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.debounce(600).collectLatest { (i, o) ->
            book.key?.let { ReaderStore.savePosition(ctx, it, i, o) }
            if (book.uri != null) ReaderStore.touch(ctx, book.uri, book.doc.title, percent)
        }
    }
    LaunchedEffect(font, theme, serif) { ReaderStore.saveSettings(ctx, font, theme, serif) }

    Box(Modifier.fillMaxSize().background(th.bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("‹ Kitaplar", color = th.fg) }
                Text(book.doc.title, Modifier.weight(1f), color = th.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                if (blocks.any { it is Block.Heading }) TextButton(onClick = { toc = true }) { Text("İçindekiler", color = th.fg) }
                TextButton(onClick = { settings = true }) { Text("Aa", color = th.fg, fontWeight = FontWeight.Bold) }
            }
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
                    itemsIndexed(blocks) { _, bl -> BlockView(bl, font, th.fg, family) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("%$percent", color = th.fg, style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                Button(enabled = allWords.isNotEmpty(), onClick = { rsvpFrom = offsets.getOrElse(list.firstVisibleItemIndex) { 0 } }) { Text("Hızlı oku") }
            }
        }
        rsvpFrom?.let { from ->
            RsvpView(allWords, from, th, onClose = { at ->
                rsvpFrom = null
                val bi = offsets.indexOfLast { it <= at }.coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
                scope.launch { list.scrollToItem(bi) }
            })
        }
    }

    if (settings) {
        AlertDialog(
            onDismissRequest = { settings = false },
            title = { Text("Görünüm") },
            text = {
                Column {
                    Text("Yazı boyutu: $font")
                    Slider(font.toFloat(), { font = it.toInt() }, valueRange = 12f..34f, steps = 21)
                    Text("Sayfa rengi", modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        themes.forEachIndexed { i, t ->
                            Box(Modifier.size(56.dp, 40.dp).background(t.bg).border(if (i == theme) 3.dp else 1.dp, MaterialTheme.colorScheme.primary).clickable { theme = i },
                                contentAlignment = Alignment.Center) { Text(t.name, color = t.fg, fontSize = 12.sp) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Tırnaklı yazı tipi", Modifier.weight(1f)); Switch(serif, { serif = it })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { settings = false }) { Text("Tamam") } },
        )
    }
    if (toc) {
        val heads = blocks.withIndex().filter { it.value is Block.Heading }
        AlertDialog(
            onDismissRequest = { toc = false },
            title = { Text("İçindekiler") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(heads) { _, h ->
                        val hd = h.value as Block.Heading
                        Text(hd.text, Modifier.fillMaxWidth().clickable { toc = false; scope.launch { list.scrollToItem(h.index) } }
                            .padding(start = ((hd.level - 1) * 12).dp, top = 8.dp, bottom = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { toc = false }) { Text("Kapat") } },
        )
    }
}

@Composable
private fun BlockView(b: Block, font: Int, fg: Color, family: FontFamily) {
    val lh = (font * 1.55f).sp
    when (b) {
        is Block.Heading -> Text(b.text, color = fg, fontFamily = family, fontWeight = FontWeight.Bold,
            fontSize = (font + when (b.level) { 1 -> 8; 2 -> 5; else -> 3 }).sp, lineHeight = ((font + 8) * 1.3f).sp,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        is Block.Para -> Text(b.text, color = fg, fontFamily = family, fontSize = font.sp, lineHeight = lh, modifier = Modifier.padding(bottom = 10.dp))
        is Block.Rich -> Text(richText(b), color = fg, fontFamily = family, fontSize = font.sp, lineHeight = lh,
            textAlign = when (b.align) { 1 -> TextAlign.Center; 2 -> TextAlign.End; 3 -> TextAlign.Justify; else -> TextAlign.Start },
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
        is Block.Table -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).border(1.dp, fg.copy(alpha = 0.3f))) {
            b.rows.forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { c -> Text(c, Modifier.weight(1f).border(0.5.dp, fg.copy(alpha = 0.2f)).padding(6.dp), color = fg, fontSize = (font - 3).coerceAtLeast(10).sp, fontFamily = family) }
                }
            }
        }
        is Block.Image -> {
            val bmp = remember(b) { runCatching { BitmapFactory.decodeByteArray(b.jpeg, 0, b.jpeg.size) }.getOrNull() }
            bmp?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    .aspectRatio(it.width.toFloat() / it.height.coerceAtLeast(1)), contentScale = ContentScale.Fit)
            }
        }
    }
}

private fun richText(b: Block.Rich): AnnotatedString = buildAnnotatedString {
    b.runs.forEach { r ->
        withStyle(SpanStyle(fontWeight = if (r.bold) FontWeight.Bold else null, fontStyle = if (r.italic) FontStyle.Italic else null,
            textDecoration = if (r.underline) TextDecoration.Underline else null)) { append(r.text) }
    }
}

@Composable
private fun RsvpView(words: List<String>, from: Int, th: ReaderTheme, onClose: (Int) -> Unit) {
    val ctx = LocalContext.current
    var i by remember { mutableIntStateOf(from.coerceIn(0, (words.size - 1).coerceAtLeast(0))) }
    var playing by remember { mutableStateOf(false) }
    var wpm by remember { mutableIntStateOf(ReaderStore.loadWpm(ctx)) }
    LaunchedEffect(playing, wpm) {
        while (playing && i < words.size - 1) { delay(Rsvp.delayMs(words[i], wpm)); i++ }
        if (i >= words.size - 1) playing = false
    }
    LaunchedEffect(wpm) { ReaderStore.saveWpm(ctx, wpm) }
    val w = words.getOrElse(i) { "" }
    val f = Rsvp.focusIndex(w)
    Column(Modifier.fillMaxSize().background(th.bg).clickable { playing = !playing }.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Hızlı okuma", color = th.fg, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { playing = false; onClose(i) }) { Text("Kapat", color = th.fg) }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(2.dp).background(th.fg.copy(alpha = 0.15f)))
        Text(buildAnnotatedString {
            if (w.isNotEmpty()) {
                append(w.substring(0, f))
                withStyle(SpanStyle(color = Color(0xFFE53935), fontWeight = FontWeight.Bold)) { append(w[f]) }
                append(w.substring(f + 1))
            }
        }, color = th.fg, fontSize = 40.sp, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center,
            maxLines = 1, softWrap = false, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(th.fg.copy(alpha = 0.15f)))
        Spacer(Modifier.weight(1f))
        Text(if (playing) "Durdurmak için ekrana dokun" else "Başlatmak için ekrana dokun", color = th.fg.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
        Text("${i + 1} / ${words.size} kelime · kalan ~${Rsvp.minutesLeft(words.size - i - 1, wpm)} dk", color = th.fg, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp))
        Slider(i.toFloat(), { i = it.toInt(); playing = false }, valueRange = 0f..(words.size - 1).coerceAtLeast(1).toFloat(), modifier = Modifier.fillMaxWidth())
        Text("Hız: $wpm kelime/dakika", color = th.fg)
        Slider(wpm.toFloat(), { wpm = (it / 25).toInt() * 25 }, valueRange = 100f..1000f, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { i = (i - 10).coerceAtLeast(0) }) { Text("‹‹ 10") }
            Button(onClick = { playing = !playing }) { Text(if (playing) "Durdur" else "Başlat") }
            OutlinedButton(onClick = { i = (i + 10).coerceAtMost(words.size - 1) }) { Text("10 ››") }
        }
    }
}
