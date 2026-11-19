package com.ercan.heybe.tools.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ercan.heybe.tools.ocr.Ocr
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Telefonda çalışan çeviri (ML Kit). Dil paketleri bir kez indirilir, sonra internet gerekmez. */
object Translator {
    val langs = linkedMapOf(
        TranslateLanguage.TURKISH to "Türkçe", TranslateLanguage.ENGLISH to "İngilizce", TranslateLanguage.GERMAN to "Almanca",
        TranslateLanguage.FRENCH to "Fransızca", TranslateLanguage.ARABIC to "Arapça", TranslateLanguage.RUSSIAN to "Rusça",
        TranslateLanguage.SPANISH to "İspanyolca", TranslateLanguage.ITALIAN to "İtalyanca", TranslateLanguage.PERSIAN to "Farsça",
        TranslateLanguage.DUTCH to "Felemenkçe", TranslateLanguage.JAPANESE to "Japonca", TranslateLanguage.CHINESE to "Çince",
    )

    fun detect(text: String): String? = runCatching {
        Tasks.await(LanguageIdentification.getClient().identifyLanguage(text.take(500)))
    }.getOrNull()?.takeIf { it != "und" }?.let { TranslateLanguage.fromLanguageTag(it) }

    /** Paragraf paragraf çevirir (uzun metinlerde daha isabetli). */
    fun translate(text: String, from: String, to: String, status: (String) -> Unit): String {
        if (from == to) return text
        val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        try {
            status("Dil paketleri hazırlanıyor (ilk kullanımda ~30 MB indirilir)…")
            Tasks.await(client.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            val paras = text.split(Regex("\\n\\s*\\n"))
            return paras.mapIndexed { i, p ->
                status("Çevriliyor ${i + 1} / ${paras.size}")
                if (p.isBlank()) p else p.lines().joinToString("\n") { l -> if (l.isBlank()) l else Tasks.await(client.translate(l)) }
            }.joinToString("\n\n")
        } finally { client.close() }
    }

    fun downloaded(): Set<String> = runCatching {
        Tasks.await(RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)).map { it.language }.toSet()
    }.getOrDefault(emptySet())

    fun delete(lang: String) = runCatching {
        Tasks.await(RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(lang).build()))
    }
}

@Composable
private fun LangPicker(label: String, value: String, auto: Boolean, onPick: (String) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: " + if (value == "auto") "Otomatik" else (Translator.langs[value] ?: value), maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (auto) DropdownMenuItem(text = { Text("Otomatik algıla") }, onClick = { open = false; onPick("auto") })
            Translator.langs.forEach { (k, v) -> DropdownMenuItem(text = { Text(v) }, onClick = { open = false; onPick(k) }) }
        }
    }
}

@Composable
fun TranslateScreen(initialText: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var src by rememberSaveable { mutableStateOf(initialText.orEmpty()) }
    var out by rememberSaveable { mutableStateOf("") }
    var from by rememberSaveable { mutableStateOf("auto") }
    var to by rememberSaveable { mutableStateOf(TranslateLanguage.TURKISH) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<Set<String>>(emptySet()) }
    val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun run() {
        if (src.isBlank()) return
        scope.launch {
            busy = true; status = null
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val f = if (from == "auto") Translator.detect(src) ?: error("Dil algılanamadı; kaynak dili elle seç.") else from
                    if (f !in Translator.langs.keys && from == "auto") main.post { status = "Algılanan dil: $f" }
                    val tgt = if (f == to) (if (to == TranslateLanguage.TURKISH) TranslateLanguage.ENGLISH else TranslateLanguage.TURKISH) else to
                    Translator.translate(src, f, tgt) { s -> main.post { status = s } } to (f to tgt)
                }
            }
            r.onSuccess { (t, pair) -> out = t; status = "${Translator.langs[pair.first] ?: pair.first} → ${Translator.langs[pair.second] ?: pair.second}"; if (pair.second != to) to = pair.second }
                .onFailure { status = "Çevrilemedi: ${it.message}" }
            models = withContext(Dispatchers.IO) { Translator.downloaded() }
            busy = false
        }
    }
    LaunchedEffect(Unit) {
        models = withContext(Dispatchers.IO) { Translator.downloaded() }
        if (!initialText.isNullOrBlank()) run()
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u: Uri? ->
        if (u != null) scope.launch {
            busy = true; status = "Fotoğraftaki yazı okunuyor…"
            runCatching { Ocr.read(ctx, u) }.onSuccess { src = it; busy = false; run() }.onFailure { status = "Okunamadı: ${it.message}"; busy = false }
        }
    }
    fun copy(t: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Çeviri", t))
        Toast.makeText(ctx, "Kopyalandı.", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LangPicker("Kaynak", from, true, { from = it }, Modifier.weight(1f))
            TextButton(onClick = { if (from != "auto") { val t = from; from = to; to = t; if (out.isNotBlank()) { src = out; out = "" } } }) { Text("⇄") }
            LangPicker("Hedef", to, false, { to = it }, Modifier.weight(1f))
        }
        OutlinedTextField(src, { src = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Metin") }, minLines = 4)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Button(onClick = { run() }, enabled = !busy && src.isNotBlank()) { Text("Çevir") }
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let { src = it }
            }) { Text("Yapıştır") }
            OutlinedButton(onClick = { pickImage.launch("image/*") }) { Text("Fotoğraftan") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        if (out.isNotBlank()) {
            Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Column(Modifier.padding(12.dp)) {
                    SelectionContainer { Text(out, style = MaterialTheme.typography.bodyLarge) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = { copy(out) }) { Text("Kopyala") }
                        OutlinedButton(onClick = { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, out), "Paylaş")) }) { Text("Paylaş") }
                    }
                }
            }
        }
        Text("Çeviri telefonda yapılır, metin internete gönderilmez. Makine çevirisidir; resmi belgelerde kontrol etmeden kullanma.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        if (models.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("İndirilmiş dil paketleri", style = MaterialTheme.typography.titleSmall)
            models.filter { it != TranslateLanguage.ENGLISH }.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Translator.langs[m] ?: m, Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { Translator.delete(m) }; models = withContext(Dispatchers.IO) { Translator.downloaded() } } }) { Text("Sil") }
                }
            }
            Text("İngilizce paketi her çeviride ara dil olarak kullanıldığı için silinemez.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
