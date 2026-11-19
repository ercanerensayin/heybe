package com.ercan.heybe.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ercan.heybe.tools.pdf.PdfEngine
import kotlinx.coroutines.launch
import java.io.File

/** "Yeni sürüm var" penceresi: indir → (gerekirse izin) → kurulum ekranı. */
@Composable
fun UpdateDialog() {
    val a = Updater.pending ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Int?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun tryInstall(f: File) {
        if (!Updater.canInstall(ctx)) {
            error = "Heybe'nin uygulama yüklemesine izin ver, sonra geri dönüp \"Kur\"a bas."
            Updater.openInstallPermission(ctx)
        } else runCatching { Updater.install(ctx, f) }.onFailure { error = "Kurulum ekranı açılamadı: ${it.message}" }
    }

    AlertDialog(
        onDismissRequest = { if (progress == null || file != null) Updater.pending = null },
        title = { Text("Yeni sürüm: ${a.info.versionName}") },
        text = {
            Column {
                Text("Şu an kurulu: ${Updater.installedName(ctx)}" + if (a.size > 0) "\nİndirme boyutu: ${PdfEngine.mb(a.size)}" else "")
                progress?.let {
                    LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    Text(if (file != null) "İndirildi." else "%$it", modifier = Modifier.padding(top = 4.dp))
                }
                error?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = progress == null || file != null, onClick = {
                val f = file
                if (f != null) { tryInstall(f); return@TextButton }
                error = null; progress = 0
                scope.launch {
                    runCatching { Updater.download(ctx, a) { p -> progress = p } }
                        .onSuccess { file = it; progress = 100; tryInstall(it) }
                        .onFailure { progress = null; error = "İndirilemedi: ${it.message}" }
                }
            }) { Text(if (file != null) "Kur" else "İndir ve kur") }
        },
        dismissButton = {
            TextButton(onClick = { Updater.pending = null }) { Text("Sonra") }
        },
    )
}
