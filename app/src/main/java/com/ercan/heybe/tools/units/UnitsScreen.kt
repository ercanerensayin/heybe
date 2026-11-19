package com.ercan.heybe.tools.units

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ercan.heybe.core.Calc
import com.ercan.heybe.core.Tcmb
import com.ercan.heybe.core.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun UnitsScreen() {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Birim") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Döviz") })
        }
        if (tab == 0) UnitTab() else CurrencyTab()
    }
}

@Composable
private fun <T> Picker(label: String, current: String, options: List<T>, text: (T) -> String, onPick: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: $current", maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(text(o)) }, onClick = { open = false; onPick(o) }) }
        }
    }
}

@Composable
private fun UnitTab() {
    var groupId by rememberSaveable { mutableStateOf("uzunluk") }
    val group = Units.groups.first { it.id == groupId }
    var from by rememberSaveable(groupId) { mutableStateOf(group.units.first().id) }
    var to by rememberSaveable(groupId) { mutableStateOf(group.units.getOrElse(2) { group.units.last() }.id) }
    var input by rememberSaveable { mutableStateOf("1") }
    val v = Calc.parse(input)

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(Units.groups) { g -> FilterChip(selected = g.id == groupId, onClick = { groupId = g.id }, label = { Text(g.label) }) }
        }
        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Değer") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (groupId == "sicaklik") KeyboardType.Text else KeyboardType.Decimal))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Picker("Kaynak", group.units.first { it.id == from }.label, group.units, { it.label }, { from = it.id }, Modifier.weight(1f))
            TextButton(onClick = { val t = from; from = to; to = t }) { Text("⇄") }
            Picker("Hedef", group.units.first { it.id == to }.label, group.units, { it.label }, { to = it.id }, Modifier.weight(1f))
        }
        if (v != null) {
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(Units.format(Units.convert(groupId, from, to, v)) + " " + group.units.first { it.id == to }.label,
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${Units.format(v)} ${group.units.first { it.id == from }.label}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Diğer birimlerde", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            group.units.filter { it.id != from }.forEach { u ->
                Row(Modifier.fillMaxWidth().clickable { to = u.id }.padding(vertical = 4.dp)) {
                    Text(u.label, Modifier.weight(1f)); Text(Units.format(Units.convert(groupId, from, u.id, v)))
                }
                HorizontalDivider()
            }
        }
    }
}

/** TCMB kurları: internetten çekilir, son başarılı kurlar telefonda saklanır. */
object RateStore {
    private const val URL_TODAY = "https://www.tcmb.gov.tr/kurlar/today.xml"
    private fun sp(ctx: Context) = ctx.getSharedPreferences("doviz", Context.MODE_PRIVATE)

    fun cached(ctx: Context): Pair<Tcmb.Rates, Long>? {
        val xml = sp(ctx).getString("xml", null) ?: return null
        return runCatching { Tcmb.parse(xml) to sp(ctx).getLong("zaman", 0L) }.getOrNull()?.takeIf { it.first.rates.isNotEmpty() }
    }

    suspend fun refresh(ctx: Context): Tcmb.Rates = withContext(Dispatchers.IO) {
        val c = (URL(URL_TODAY).openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 20000; setRequestProperty("User-Agent", "Heybe") }
        if (c.responseCode != 200) error("TCMB yanıtı: ${c.responseCode}")
        val bytes = c.inputStream.use { it.readBytes() }
        val xml = com.ercan.heybe.core.SubtitleText.decode(bytes)
        val r = Tcmb.parse(xml)
        if (r.rates.isEmpty()) error("Kur listesi okunamadı")
        sp(ctx).edit().putString("xml", xml).putLong("zaman", System.currentTimeMillis()).apply()
        r
    }
}

@Composable
private fun CurrencyTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rates by remember { mutableStateOf(RateStore.cached(ctx)?.first) }
    var status by remember { mutableStateOf<String?>(null) }
    var amount by rememberSaveable { mutableStateOf("100") }
    var from by rememberSaveable { mutableStateOf("USD") }
    var to by rememberSaveable { mutableStateOf("TRY") }

    fun refresh() = scope.launch {
        status = "Kurlar alınıyor…"
        runCatching { RateStore.refresh(ctx) }.onSuccess { rates = it; status = null }
            .onFailure { status = "Güncellenemedi (${it.message}). " + if (rates != null) "Son kayıtlı kurlar gösteriliyor." else "İnternet bağlantısını kontrol et." }
    }
    LaunchedEffect(Unit) {
        val c = RateStore.cached(ctx)
        if (c == null || System.currentTimeMillis() - c.second > 3 * 60 * 60 * 1000L) refresh()
    }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        val r = rates
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (r != null) "TCMB ${r.date} kurları (döviz satış)" else "Kur bilgisi yok", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { refresh() }) { Text("Yenile") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (r == null) return@Column
        val codes = listOf("TRY") + r.rates.map { it.code }
        fun nameOf(c: String) = if (c == "TRY") "TRY — Türk lirası" else r.rates.first { it.code == c }.let { "${it.code} — ${it.name.lowercase().replaceFirstChar { ch -> ch.titlecase() }}" }
        OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Tutar") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Picker("Kaynak", from, codes, ::nameOf, { from = it }, Modifier.weight(1f))
            TextButton(onClick = { val t = from; from = to; to = t }) { Text("⇄") }
            Picker("Hedef", to, codes, ::nameOf, { to = it }, Modifier.weight(1f))
        }
        val a = Calc.parse(amount)
        val res = a?.let { Tcmb.convert(r, from, to, it) }
        if (a != null && res != null) {
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(Units.format(res) + " " + to, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${Units.format(a)} $from · 1 $from = ${Units.format(Tcmb.convert(r, from, to, 1.0) ?: 0.0)} $to", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("Başlıca kurlar (1 birim, TL)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        Row { Text("", Modifier.weight(1f)); Text("Alış", Modifier.weight(0.7f)); Text("Satış", Modifier.weight(0.7f)) }
        r.rates.filter { it.code in listOf("USD", "EUR", "GBP", "CHF", "JPY", "SAR", "RUB", "CNY") }.forEach { x ->
            Row(Modifier.fillMaxWidth().clickable { from = x.code; to = "TRY" }.padding(vertical = 4.dp)) {
                Text(x.code, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(x.buying?.let { Units.format(it / x.unit) } ?: "—", Modifier.weight(0.7f))
                Text(x.selling?.let { Units.format(it / x.unit) } ?: "—", Modifier.weight(0.7f))
            }
            HorizontalDivider()
        }
        Text("Kurlar bilgi amaçlıdır; bankaların işlem kurları farklıdır.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}
