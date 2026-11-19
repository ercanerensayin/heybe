package com.ercan.heybe.tools.uyap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ercan.heybe.core.doc.Doc
import com.ercan.heybe.core.doc.Udf
import com.ercan.heybe.tools.convert.ConvertEngine
import com.ercan.heybe.tools.convert.ConvertWorker
import com.ercan.heybe.tools.convert.Target
import com.ercan.heybe.tools.pdf.PdfEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun UdfScreen(initial: List<Uri>, onConvertStarted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf(initial.firstOrNull()) }
    var name by remember { mutableStateOf("") }
    var doc by remember { mutableStateOf<Doc?>(null) }
    var info by remember { mutableStateOf<Udf.Info?>(null) }
    var html by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; uri = u }
    }

    LaunchedEffect(uri) {
        val u = uri ?: return@LaunchedEffect
        busy = true; error = null; doc = null; html = null
        name = PdfEngine.displayName(ctx, u)
        if (!name.endsWith(".udf", true)) { error = "Bu bir UDF dosyası değil: $name"; busy = false; return@LaunchedEffect }
        runCatching {
            withContext(Dispatchers.IO) {
                val tmp = File(ctx.cacheDir, "udf_goster.udf")
                ctx.contentResolver.openInputStream(u)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                Udf.read(tmp, name.substringBeforeLast('.'), ConvertEngine.codec()).also { tmp.delete() }
            }
        }.onSuccess { (d, i) -> doc = d; info = i; html = Udf.toHtml(d) }
            .onFailure { error = "UDF açılamadı: ${it.message}" }
        busy = false
    }

    fun convert(t: Target) {
        val u = uri ?: return
        scope.launch {
            busy = true
            runCatching {
                val f = withContext(Dispatchers.IO) {
                    val dir = File(ctx.cacheDir, "donustur/${System.currentTimeMillis()}").apply { mkdirs() }
                    File(dir, name).also { out -> ctx.contentResolver.openInputStream(u)!!.use { i -> out.outputStream().use { i.copyTo(it) } } }
                }
                ConvertWorker.enqueue(ctx, listOf(Triple(f, name, null)), t)
            }.onSuccess { Toast.makeText(ctx, "${t.label} hazırlanıyor; İndirilenler'de.", Toast.LENGTH_SHORT).show(); onConvertStarted() }
                .onFailure { Toast.makeText(ctx, "Başlatılamadı: ${it.message}", Toast.LENGTH_LONG).show() }
            busy = false
        }
    }

    fun printPdf() {
        val w = web ?: return
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val job = name.substringBeforeLast('.')
        pm.print(job, w.createPrintDocumentAdapter(job), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text(if (uri == null) "UDF dosyası seç" else "Başka UDF seç") }
            }
            if (uri == null) Text("UYAP'tan indirdiğin .udf belgelerini (dilekçe, karar, tutanak) telefonda okunaklı açar; PDF ya da Word'e çevirir. Word ya da PDF'ten UDF yapmak için Dönüştürücü'yü kullan.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            if (name.isNotEmpty()) Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            info?.warnings?.forEach { w -> AssistChip(onClick = {}, label = { Text(w, style = MaterialTheme.typography.bodySmall) }) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            if (doc != null) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { printPdf() }) { Text("PDF (tam görünüm)") }
                OutlinedButton(onClick = { convert(Target.DOCX) }, enabled = !busy) { Text("Word") }
                OutlinedButton(onClick = { convert(Target.PDF) }, enabled = !busy) { Text("PDF (sade)") }
                OutlinedButton(onClick = {
                    val text = ConvertEngine.toText(doc!!)
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(name, text))
                    Toast.makeText(ctx, "Metin kopyalandı.", Toast.LENGTH_SHORT).show()
                }) { Text("Metni kopyala") }
                OutlinedButton(onClick = {
                    uri?.let { u -> ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                        .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Paylaş")) }
                }) { Text("Paylaş") }
            }
            if (doc != null) Text("\"PDF (tam görünüm)\" Android'in yazdırma ekranını açar; yazıcı olarak \"PDF olarak kaydet\"i seç.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        html?.let { h ->
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = false
                    settings.builtInZoomControls = true; settings.displayZoomControls = false
                    settings.allowFileAccess = false
                    web = this
                }
            }, update = { it.loadDataWithBaseURL(null, h, "text/html", "utf-8", null) })
        }
    }
}
