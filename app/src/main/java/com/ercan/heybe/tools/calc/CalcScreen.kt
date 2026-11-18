package com.ercan.heybe.tools.calc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ercan.heybe.core.Calc

@Composable
fun CalcScreen() {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Kredi") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("KDV") })
        }
        if (tab == 0) LoanTab() else VatTab()
    }
}

@Composable
private fun Num(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(value, onChange, modifier.padding(top = 4.dp), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
private fun Line(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f))
        Text(value, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun LoanTab() {
    var type by rememberSaveable { mutableStateOf(Calc.LoanType.IHTIYAC) }
    var amount by rememberSaveable { mutableStateOf("") }
    var rate by rememberSaveable { mutableStateOf("") }
    var months by rememberSaveable { mutableStateOf("") }
    var kkdf by rememberSaveable { mutableStateOf("15") }
    var bsmv by rememberSaveable { mutableStateOf("15") }
    var showPlan by rememberSaveable { mutableStateOf(false) }

    val a = Calc.parse(amount); val r = Calc.parse(rate); val n = months.trim().toIntOrNull()
    val k = Calc.parse(kkdf) ?: 0.0; val b = Calc.parse(bsmv) ?: 0.0
    val loan = if (a != null && r != null && n != null) runCatching { Calc.loan(a, r, n, k, b) }.getOrNull() else null

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Calc.LoanType.entries.forEach { t ->
                FilterChip(selected = type == t, onClick = {
                    type = t; kkdf = t.kkdf.toInt().toString(); bsmv = t.bsmv.toInt().toString()
                }, label = { Text(t.label) })
            }
        }
        Num(amount, { amount = it }, "Kredi tutarı (TL)")
        Num(rate, { rate = it }, "Aylık faiz (%) — ör. 3,4")
        Num(months, { months = it }, "Vade (ay)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Num(kkdf, { kkdf = it }, "KKDF (%)", Modifier.weight(1f))
            Num(bsmv, { bsmv = it }, "BSMV (%)", Modifier.weight(1f))
        }
        Text("KKDF ve BSMV faiz tutarı üzerinden hesaplanır. Oranlar kredi türüne göre otomatik dolar; değişirse elle düzeltebilirsin.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

        loan?.let { l ->
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Line("Aylık taksit", Calc.money(l.installment), bold = true)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Line("Toplam geri ödeme", Calc.money(l.total))
                    Line("Toplam faiz", Calc.money(l.totalInterest))
                    Line("Toplam KKDF", Calc.money(l.totalKkdf))
                    Line("Toplam BSMV", Calc.money(l.totalBsmv))
                    Line("Toplam maliyet", Calc.money(l.total - a!!), bold = true)
                }
            }
            TextButton(onClick = { showPlan = !showPlan }) { Text(if (showPlan) "Ödeme planını gizle" else "Ödeme planını göster") }
            if (showPlan) {
                val st = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Row { listOf("No" to 0.5f, "Anapara" to 1.3f, "Faiz" to 1.2f, "Vergi" to 1.1f, "Kalan" to 1.4f).forEach { (h, w) ->
                    Text(h, Modifier.weight(w), style = st, fontWeight = FontWeight.Bold, textAlign = TextAlign.End) } }
                l.rows.forEach { row ->
                    Row {
                        listOf("${row.no}" to 0.5f, short(row.principal) to 1.3f, short(row.interest) to 1.2f,
                            short(row.kkdf + row.bsmv) to 1.1f, short(row.balance) to 1.4f).forEach { (v, w) ->
                            Text(v, Modifier.weight(w), style = st, textAlign = TextAlign.End)
                        }
                    }
                }
            }
        }
    }
}

private fun short(v: Double) = Calc.money(v).removeSuffix(" TL")

@Composable
private fun VatTab() {
    var amount by rememberSaveable { mutableStateOf("") }
    var rate by rememberSaveable { mutableStateOf("20") }
    var includes by rememberSaveable { mutableStateOf(false) }
    val a = Calc.parse(amount); val r = Calc.parse(rate)
    val v = if (a != null && r != null) Calc.vat(a, r, includes) else null

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !includes, onClick = { includes = false }, label = { Text("KDV hariç tutar") })
            FilterChip(selected = includes, onClick = { includes = true }, label = { Text("KDV dahil tutar") })
        }
        Num(amount, { amount = it }, if (includes) "KDV dahil tutar (TL)" else "KDV hariç tutar (TL)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            listOf("1", "10", "20").forEach { o -> FilterChip(selected = rate == o, onClick = { rate = o }, label = { Text("%$o") }) }
        }
        Num(rate, { rate = it }, "KDV oranı (%)")
        v?.let {
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Line("KDV hariç", Calc.money(it.net))
                    Line("KDV", Calc.money(it.vat), bold = true)
                    Line("KDV dahil", Calc.money(it.gross), bold = true)
                }
            }
        }
    }
}
