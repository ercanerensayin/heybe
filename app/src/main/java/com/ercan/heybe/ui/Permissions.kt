package com.ercan.heybe.ui

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * İzni yalnızca gerektiği an ister. Dönen fonksiyon çağrıldığında izinler varsa işi hemen yapar,
 * yoksa ister; verilirse işi yapar, verilmezse kısa bir açıklama gösterir.
 */
@Composable
fun rememberWithPermissions(permissions: List<String>, deniedMessage: String): (() -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val action = pending
        pending = null
        if (result.values.all { it }) action?.invoke()
        else Toast.makeText(ctx, deniedMessage, Toast.LENGTH_LONG).show()
    }
    return { action ->
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) action()
        else { pending = action; launcher.launch(missing.toTypedArray()) }
    }
}

/** Dosya kaydetmek için gereken izinler (yalnızca Android 9 ve öncesi). */
fun storagePermissions(): List<String> =
    if (com.ercan.heybe.data.FileStore.needsPermission()) listOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    else emptyList()
