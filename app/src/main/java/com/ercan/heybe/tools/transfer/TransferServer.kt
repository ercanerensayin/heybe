package com.ercan.heybe.tools.transfer

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.ercan.heybe.core.HttpBits
import com.ercan.heybe.data.FileStore
import com.ercan.heybe.data.HeybeDb
import com.ercan.heybe.data.Recorder
import com.ercan.heybe.data.Status
import com.ercan.heybe.tools.pdf.PdfEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Aynı Wi-Fi'deki bilgisayarın tarayıcısından Heybe dosyalarını indirmesini ve telefona dosya yüklemesini sağlayan
 * küçük HTTP sunucusu. Adres rastgele bir anahtar içerir; anahtarı bilmeyen ağdaki biri erişemez.
 */
class TransferServer(private val ctx: Context, private val onEvent: (String) -> Unit) {
    val token: String = SecureRandom().let { r -> (1..6).map { "abcdefghjkmnpqrstuvwxyz23456789"[r.nextInt(31)] }.joinToString("") }
    var port = 0; private set
    private var server: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile var extras: List<Uri> = emptyList()

    fun address(): String? {
        val list = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { ni ->
                ni.inetAddresses.toList().filter { it is java.net.Inet4Address }.map { ni.name to it.hostAddress!! }
            }
        }.getOrDefault(emptyList())
        return HttpBits.pickAddress(list)
    }

    fun url(): String? = address()?.let { "http://$it:$port/$token/" }

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        var p = 8080
        while (true) { try { s.bind(InetSocketAddress(p)); break } catch (e: Exception) { if (++p > 8099) throw e } }
        port = p; server = s
        scope.launch {
            while (isActive) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                launch { runCatching { handle(c) }; runCatching { c.close() } }
            }
        }
    }

    fun stop() { runCatching { server?.close() }; scope.cancel() }

    // ---------------- HTTP ----------------

    private fun readLine(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c == -1) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(c); if (b.size() > 16384) return null
        }
    }

    private fun head(o: OutputStream, code: Int, type: String, length: Long? = null, extra: Map<String, String> = emptyMap()) {
        val reason = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Too Large"; else -> "Error" }
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nConnection: close\r\nCache-Control: no-store\r\n")
        length?.let { sb.append("Content-Length: $it\r\n") }
        extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        o.write(sb.append("\r\n").toString().toByteArray(Charsets.UTF_8))
    }

    private fun text(o: OutputStream, code: Int, body: String, type: String = "text/html; charset=utf-8") {
        val b = body.toByteArray(Charsets.UTF_8); head(o, code, type, b.size.toLong()); o.write(b); o.flush()
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 120_000
        val input = BufferedInputStream(sock.getInputStream())
        val out = sock.getOutputStream().buffered()
        val req = readLine(input)?.let(HttpBits::parseRequestLine) ?: return
        val headers = HashMap<String, String>()
        while (true) { val l = readLine(input) ?: break; if (l.isEmpty()) break; l.indexOf(':').takeIf { it > 0 }?.let { headers[l.substring(0, it).trim().lowercase()] = l.substring(it + 1).trim() } }

        val prefix = "/$token"
        if (!req.path.startsWith(prefix)) { text(out, 403, page("Adres eksik", "<p>Telefonda Heybe → Wi-Fi Aktarım ekranındaki adresin tamamını yaz.</p>")); return }
        val rest = req.path.removePrefix(prefix)
        when {
            req.method == "GET" && (rest == "" || rest == "/") -> text(out, 200, index())
            req.method == "GET" && rest.startsWith("/dosya/") -> {
                val id = rest.removePrefix("/dosya/").toLongOrNull() ?: return text(out, 404, page("Bulunamadı", ""))
                val item = runBlocking { HeybeDb.get(ctx).history().get(id) } ?: return text(out, 404, page("Bulunamadı", ""))
                serve(out, Uri.parse(item.uri ?: return text(out, 404, page("Bulunamadı", ""))), item.title, item.mime, item.size)
            }
            req.method == "GET" && rest.startsWith("/ek/") -> {
                val u = rest.removePrefix("/ek/").toIntOrNull()?.let { extras.getOrNull(it) } ?: return text(out, 404, page("Bulunamadı", ""))
                serve(out, u, PdfEngine.displayName(ctx, u), ctx.contentResolver.getType(u) ?: "application/octet-stream", -1)
            }
            req.method == "POST" && rest == "/yukle" -> {
                val len = headers["content-length"]?.toLongOrNull() ?: return text(out, 411, "Uzunluk yok", "text/plain")
                val name = HttpBits.safeName(req.query["ad"] ?: "dosya")
                val mime = headers["content-type"]?.substringBefore(';')?.takeIf { it.contains('/') && it != "application/octet-stream" }
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
                val saved = FileStore.save(ctx, FileStore.Folder.GELEN, name, mime) { os ->
                    val buf = ByteArray(64 * 1024); var left = len
                    while (left > 0) { val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; os.write(buf, 0, n); left -= n }
                    if (left > 0) error("Yükleme yarıda kaldı")
                }
                runBlocking { Recorder.done(ctx, "aktarim", saved, mime) }
                onEvent("Bilgisayardan geldi: ${saved.name}")
                text(out, 200, "ok", "text/plain")
            }
            else -> text(out, 404, page("Bulunamadı", ""))
        }
    }

    private fun serve(out: OutputStream, uri: Uri, name: String, mime: String, size: Long) {
        val len = size.takeIf { it > 0 } ?: runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it > 0 }
        val input = runCatching { ctx.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return text(out, 404, page("Dosya bulunamadı", "<p>Dosya silinmiş ya da taşınmış olabilir.</p>"))
        input.use {
            head(out, 200, mime, len, mapOf("Content-Disposition" to HttpBits.contentDisposition(name)))
            it.copyTo(out, 64 * 1024); out.flush()
        }
        onEvent("Bilgisayara gönderildi: $name")
    }

    // ---------------- Sayfa ----------------

    private fun page(title: String, body: String) = """<!DOCTYPE html><html lang="tr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${HttpBits.html(title)} — Heybe</title><style>
body{font-family:system-ui,Segoe UI,Roboto,sans-serif;max-width:900px;margin:24px auto;padding:0 16px;color:#222;background:#faf7f2}
h1{color:#A9441F;margin-bottom:4px}h2{margin-top:28px;border-bottom:2px solid #e8d9c8;padding-bottom:4px}
.box{background:#fff;border:1px solid #e3d6c6;border-radius:12px;padding:16px}
table{width:100%;border-collapse:collapse;background:#fff}td{padding:7px 8px;border-bottom:1px solid #eee;font-size:14px}
td.k{color:#777;white-space:nowrap}a{color:#1E64C8;text-decoration:none}a:hover{text-decoration:underline}
button{background:#A9441F;color:#fff;border:0;border-radius:8px;padding:10px 18px;font-size:15px;cursor:pointer}
progress{width:100%;height:14px}.drop{border:2px dashed #c9a98a;border-radius:12px;padding:22px;text-align:center;color:#8a6a4a}
</style></head><body><h1>${HttpBits.html(title)}</h1>$body</body></html>"""

    private fun index(): String {
        val items = runBlocking { HeybeDb.get(ctx).history().list() }.filter { it.status == Status.DONE && it.uri != null }.sortedByDescending { it.time }.take(400)
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr"))
        val rows = StringBuilder()
        extras.forEachIndexed { i, u ->
            rows.append("<tr><td><a href=\"/$token/ek/$i\">${HttpBits.html(PdfEngine.displayName(ctx, u))}</a></td><td class=k>telefondan seçildi</td><td class=k></td></tr>")
        }
        items.forEach { h ->
            rows.append("<tr><td><a href=\"/$token/dosya/${h.id}\">${HttpBits.html(h.title)}</a></td><td class=k>${fmt.format(Date(h.time))}</td><td class=k>${if (h.size > 0) PdfEngine.mb(h.size) else ""}</td></tr>")
        }
        val list = if (rows.isEmpty()) "<p>Henüz Heybe ile oluşturulmuş dosya yok.</p>" else "<table>$rows</table>"
        return page("Heybe — Wi-Fi Aktarım", """
<p>Telefon ve bilgisayar aynı Wi-Fi ağında olmalı. Telefonda Heybe'nin aktarım ekranı açık kaldığı sürece çalışır.</p>
<h2>Bilgisayardan telefona gönder</h2>
<div class="box"><div class="drop" id="drop">Dosyaları buraya sürükle ya da <input type="file" id="f" multiple></div>
<p><button onclick="gonder()">Gönder</button></p><progress id="p" value="0" max="100" hidden></progress><div id="d"></div>
<p style="color:#777;font-size:13px">Gelen dosyalar telefonda ${HttpBits.html(FileStore.locationLabel(FileStore.Folder.GELEN))} klasörüne kaydedilir.</p></div>
<h2>Telefondan bilgisayara indir</h2>$list
<script>
var secilen=[];var f=document.getElementById('f');f.onchange=function(){secilen=Array.from(f.files)};
var dr=document.getElementById('drop');dr.ondragover=function(e){e.preventDefault()};dr.ondrop=function(e){e.preventDefault();secilen=Array.from(e.dataTransfer.files);document.getElementById('d').textContent=secilen.length+' dosya seçildi'};
function gonder(){if(!secilen.length){alert('Önce dosya seç');return}var p=document.getElementById('p'),d=document.getElementById('d');p.hidden=false;var i=0;
function sonraki(){if(i>=secilen.length){d.textContent='Tamamlandı: '+secilen.length+' dosya';setTimeout(function(){location.reload()},1200);return}
var x=new XMLHttpRequest(),dosya=secilen[i];x.open('POST','/$token/yukle?ad='+encodeURIComponent(dosya.name));
x.upload.onprogress=function(e){if(e.lengthComputable)p.value=e.loaded*100/e.total};
x.onload=function(){d.textContent=(i+1)+'/'+secilen.length+' gönderildi: '+dosya.name;i++;sonraki()};x.onerror=function(){d.textContent='Hata: '+dosya.name};
x.setRequestHeader('Content-Type',dosya.type||'application/octet-stream');x.send(dosya)}sonraki()}
</script>""")
    }
}
