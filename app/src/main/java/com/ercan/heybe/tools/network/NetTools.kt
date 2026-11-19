package com.ercan.heybe.tools.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.ercan.heybe.core.NetMath
import com.ercan.heybe.core.WifiQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** Ağ bilgisi, cihaz taraması ve hız testi. */
object NetTools {
    data class Info(
        val wifi: Boolean, val cellular: Boolean, val ssid: String?, val ip: String?, val prefix: Int,
        val gateway: String?, val dns: List<String>, val linkMbps: Int?,
    )

    fun info(ctx: Context): Info {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val lp = net?.let { cm.getLinkProperties(it) }
        val v4 = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
        val gw = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        @Suppress("DEPRECATION")
        val wi = if (wifi) runCatching { (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo }.getOrNull() else null
        return Info(
            wifi = wifi,
            cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            ssid = WifiQr.cleanSsid(wi?.ssid),
            ip = v4?.address?.hostAddress,
            prefix = v4?.prefixLength ?: 24,
            gateway = gw,
            dns = lp?.dnsServers?.mapNotNull { it.hostAddress }?.filter { !it.contains(':') } ?: emptyList(),
            linkMbps = wi?.linkSpeed?.takeIf { it > 0 },
        )
    }

    data class Device(val ip: String, val name: String?, val ports: Set<Int>, val kind: String)

    private val probePorts = listOf(80, 443, 445, 139, 9100, 631, 8008, 8009, 5555, 62078, 554, 22, 3389, 515, 53, 8080)

    /** Bağlantı reddedildi (RST) de "orada bir cihaz var" demektir. */
    private fun probe(ip: String, port: Int, timeout: Int): Boolean? = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeout) }; true
    } catch (e: ConnectException) { false } catch (e: SocketTimeoutException) { null } catch (e: Exception) { null }

    suspend fun scan(info: Info, onProgress: (Int, Int) -> Unit, onFound: (Device) -> Unit): List<Device> = withContext(Dispatchers.IO) {
        val ip = info.ip ?: return@withContext emptyList()
        val hosts = NetMath.hosts(ip, info.prefix)
        val sem = Semaphore(48)
        val done = AtomicInteger()
        coroutineScope {
            hosts.map { h ->
                async {
                    sem.withPermit {
                        coroutineContext.ensureActive()
                        val res = check(h, info)
                        onProgress(done.incrementAndGet(), hosts.size)
                        res?.also(onFound)
                    }
                }
            }.awaitAll().filterNotNull().sortedBy { NetMath.ipToInt(it.ip)?.toLong()?.and(0xffffffffL) ?: 0L }
        }
    }

    private fun check(h: String, info: Info): Device? {
        val self = h == info.ip
        val gw = h == info.gateway
        var alive = self || gw
        val open = mutableSetOf<Int>()
        // İlk dört port hızlı bir varlık testi; cevap gelirse geri kalanlar türü anlamak için.
        for ((i, p) in probePorts.withIndex()) {
            if (i >= 4 && !alive) break
            when (probe(h, p, if (alive) 250 else 350)) { true -> { open += p; alive = true }; false -> alive = true; null -> {} }
        }
        if (!alive) alive = runCatching { InetAddress.getByName(h).isReachable(300) }.getOrDefault(false)
        if (!alive) return null
        val name = runCatching { InetAddress.getByName(h).canonicalHostName }.getOrNull()?.takeIf { it != h }
        return Device(h, name, open, NetMath.guess(open, gw, self))
    }

    // --- hız testi (Cloudflare) ---
    private const val BASE = "https://speed.cloudflare.com"

    private fun conn(u: String) = (URL(u).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000; readTimeout = 15000; useCaches = false; setRequestProperty("User-Agent", "Heybe")
    }

    suspend fun ping(): Long = withContext(Dispatchers.IO) {
        val xs = (1..6).map {
            val t0 = System.nanoTime()
            conn("$BASE/__down?bytes=0").run { inputStream.use { it.readBytes() }; disconnect() }
            (System.nanoTime() - t0) / 1_000_000
        }
        NetMath.median(xs.drop(1))
    }

    /** İndirme hızı (Mbps); en fazla [seconds] saniye ölçer. */
    suspend fun download(seconds: Int, onMbps: (Double) -> Unit): Double = withContext(Dispatchers.IO) {
        val start = System.nanoTime(); var bytes = 0L
        val buf = ByteArray(64 * 1024)
        while ((System.nanoTime() - start) / 1e9 < seconds) {
            coroutineContext.ensureActive()
            val c = conn("$BASE/__down?bytes=25000000")
            try {
                c.inputStream.use { inp ->
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        bytes += n
                        val ms = (System.nanoTime() - start) / 1_000_000
                        if (ms > 0 && bytes % (1 shl 20) < n) onMbps(NetMath.mbps(bytes, ms))
                        if (ms / 1000.0 >= seconds) break
                        coroutineContext.ensureActive()
                    }
                }
            } finally { c.disconnect() }
        }
        NetMath.mbps(bytes, (System.nanoTime() - start) / 1_000_000)
    }

    suspend fun upload(seconds: Int, onMbps: (Double) -> Unit): Double = withContext(Dispatchers.IO) {
        val chunk = ByteArray(2_000_000) { (it % 251).toByte() }
        val start = System.nanoTime(); var bytes = 0L
        while ((System.nanoTime() - start) / 1e9 < seconds) {
            coroutineContext.ensureActive()
            val c = conn("$BASE/__up").apply {
                requestMethod = "POST"; doOutput = true; setFixedLengthStreamingMode(chunk.size)
                setRequestProperty("Content-Type", "application/octet-stream")
            }
            try {
                c.outputStream.use { o -> var off = 0; while (off < chunk.size) { val n = minOf(65536, chunk.size - off); o.write(chunk, off, n); off += n } }
                c.responseCode
                bytes += chunk.size
                onMbps(NetMath.mbps(bytes, (System.nanoTime() - start) / 1_000_000))
            } finally { c.disconnect() }
        }
        NetMath.mbps(bytes, (System.nanoTime() - start) / 1_000_000)
    }
}
