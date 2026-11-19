package com.ercan.heybe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ercan.heybe.core.Category
import com.ercan.heybe.core.Prefs
import com.ercan.heybe.core.Tool
import com.ercan.heybe.core.ToolRegistry
import com.ercan.heybe.ui.theme.color

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("home", "Ana sayfa", Icons.Filled.Home),
    Tab("downloads", "İndirilenler", Icons.AutoMirrored.Filled.List),
    Tab("settings", "Ayarlar", Icons.Filled.Settings),
)

/** Paylaş menüsünden gelen "şu aracı aç" isteği. */
data class OpenRequest(val toolId: String, val nonce: Long)

@Composable
fun HeybeRoot(openRequest: OpenRequest? = null, onOpenHandled: () -> Unit = {}) {
    val nav = rememberNavController()
    androidx.compose.runtime.LaunchedEffect(openRequest) {
        openRequest?.let {
            nav.navigate("tool/${it.toolId}") { popUpTo("home"); launchSingleTop = false }
            onOpenHandled()
        }
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    Scaffold(
        bottomBar = {
            if (route == null || tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        }
    ) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            composable("home") { HomeScreen(nav) }
            composable("downloads") { DownloadsScreen() }
            composable("settings") { SettingsScreen() }
            composable("tool/{id}") { e ->
                val tool = ToolRegistry.byId(e.arguments?.getString("id") ?: "")
                ToolScreen(tool, onBack = { nav.popBackStack() }) {
                    when (tool?.id) {
                        "video_indir" -> com.ercan.heybe.tools.video.VideoScreen(
                            initialText = remember { com.ercan.heybe.share.SharedInbox.takeText() },
                            onStarted = {
                            nav.navigate("downloads") { popUpTo("home"); launchSingleTop = true }
                        })
                        "pdf_birlestir" -> com.ercan.heybe.tools.pdf.PdfMergeSplitScreen(remember { com.ercan.heybe.share.SharedInbox.takeUris() })
                        "pdf_sikistir" -> com.ercan.heybe.tools.pdf.PdfCompressScreen(remember { com.ercan.heybe.share.SharedInbox.takeUris() })
                        "gorsel_pdf" -> com.ercan.heybe.tools.pdf.ImagesToPdfScreen(remember { com.ercan.heybe.share.SharedInbox.takeUris() })
                        "gorsel_sikistir" -> com.ercan.heybe.tools.image.ImageCompressScreen(remember { com.ercan.heybe.share.SharedInbox.takeUris() })
                        "qr" -> com.ercan.heybe.tools.qr.QrScreen()
                        "kredi_kdv" -> com.ercan.heybe.tools.calc.CalcScreen()
                        "siteler" -> com.ercan.heybe.tools.sites.SitesScreen()
                        "perde" -> com.ercan.heybe.tools.perde.PerdeScreen()
                        "tarayici" -> com.ercan.heybe.tools.scan.ScanScreen(
                            openTool = { id -> nav.navigate("tool/$id") },
                            onConvertStarted = { nav.navigate("downloads") { popUpTo("home"); launchSingleTop = true } },
                        )
                        "donustur" -> com.ercan.heybe.tools.convert.ConverterScreen(remember { com.ercan.heybe.share.SharedInbox.takeUris() }, onStarted = {
                            nav.navigate("downloads") { popUpTo("home"); launchSingleTop = true }
                        })
                        "medya" -> com.ercan.heybe.tools.media.MediaScreen(remember { com.ercan.heybe.share.SharedInbox.takeUris() })
                        "ocr" -> com.ercan.heybe.tools.ocr.OcrScreen(remember { com.ercan.heybe.share.SharedInbox.takeUris() })
                        else -> ComingSoon(tool)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(nav: NavHostController) {
    val prefs = Prefs(LocalContext.current)
    var query by rememberSaveable { mutableStateOf("") }
    var favs by remember { mutableStateOf(prefs.favorites()) }
    var recents by remember { mutableStateOf(prefs.recents()) }
    val open: (Tool) -> Unit = { t -> recents = prefs.addRecent(t.id); nav.navigate("tool/${t.id}") }
    val toggle: (Tool) -> Unit = { t -> favs = prefs.toggleFavorite(t.id) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Heybe", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(4.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Araç ara (ör. pdf, video, kdv)") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Temizle") }
                },
            )
        }
        if (query.isNotBlank()) {
            val found = ToolRegistry.search(query)
            if (found.isEmpty()) item { Text("Eşleşen araç yok.", modifier = Modifier.padding(8.dp)) }
            items(found, key = { "s_" + it.id }) { ToolCard(it, it.id in favs, open, toggle) }
        } else {
            val recentTools = recents.mapNotNull { ToolRegistry.byId(it) }
            if (recentTools.isNotEmpty()) {
                item { SectionTitle("Son kullanılanlar") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recentTools, key = { "r_" + it.id }) { t ->
                            AssistChip(onClick = { open(t) }, label = { Text(t.name) },
                                leadingIcon = { ToolBadge(t, 20) })
                        }
                    }
                }
            }
            val favTools = ToolRegistry.tools.filter { it.id in favs }
            if (favTools.isNotEmpty()) {
                item { SectionTitle("Favoriler") }
                items(favTools, key = { "f_" + it.id }) { ToolCard(it, true, open, toggle) }
            }
            Category.entries.forEach { cat ->
                val list = ToolRegistry.tools.filter { it.category == cat }
                if (list.isNotEmpty()) {
                    item(key = "c_" + cat.name) { SectionTitle(cat.title, cat.color()) }
                    items(list, key = { "t_" + it.id }) { ToolCard(it, it.id in favs, open, toggle) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(text, color = color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

@Composable
private fun ToolCard(tool: Tool, fav: Boolean, onOpen: (Tool) -> Unit, onFav: (Tool) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(tool) }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolBadge(tool, 40)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tool.name, style = MaterialTheme.typography.titleMedium)
                Text(tool.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onFav(tool) }) {
                Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (fav) "Favorilerden çıkar" else "Favorilere ekle",
                    tint = if (fav) tool.category.color() else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolScreen(tool: Tool?, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(tool?.name ?: "Araç") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
        )
        content()
    }
}

@Composable
private fun ComingSoon(tool: Tool?) {
    Column(Modifier.padding(16.dp)) {
        if (tool != null) {
            ToolBadge(tool, 56)
            Spacer(Modifier.padding(8.dp))
            Text(tool.description, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.padding(8.dp))
        }
        Text("Bu araç yol haritasının sonraki adımlarında eklenecek.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
