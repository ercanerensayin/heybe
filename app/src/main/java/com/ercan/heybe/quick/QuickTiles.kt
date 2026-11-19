package com.ercan.heybe.quick

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.ercan.heybe.MainActivity

/** Heybe'yi belirli bir araçta açan kısayol/kutucukların ortak bayrağı. */
object QuickFlags {
    /** Video İndir açılınca panodaki link otomatik yapıştırılsın mı? */
    @Volatile var pasteOnOpen = false
}

abstract class HeybeTile(private val toolId: String, private val paste: Boolean = false) : TileService() {
    override fun onClick() {
        super.onClick()
        val i = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TOOL, toolId)
            .putExtra(MainActivity.EXTRA_PASTE, paste)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, toolId.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}

class VideoTile : HeybeTile("video_indir", paste = true)
class ScanTile : HeybeTile("tarayici")
