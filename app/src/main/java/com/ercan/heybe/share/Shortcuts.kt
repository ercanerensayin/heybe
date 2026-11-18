package com.ercan.heybe.share

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.ercan.heybe.core.ShareRouter.Shortcut

/** Paylaş menüsündeki ikinci sabit kısayolu açıp kapatır (Video İndir her zaman açık). */
object Shortcuts {
    private fun component(ctx: Context, s: Shortcut) = ComponentName(ctx.packageName, "com.ercan.heybe.share.${s.alias}")

    fun current(ctx: Context): Shortcut =
        Shortcut.second.firstOrNull { s ->
            when (ctx.packageManager.getComponentEnabledSetting(component(ctx, s))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> s == Shortcut.PDF
                else -> false
            }
        } ?: Shortcut.PDF

    fun setSecond(ctx: Context, chosen: Shortcut) {
        Shortcut.second.forEach { s ->
            ctx.packageManager.setComponentEnabledSetting(
                component(ctx, s),
                if (s == chosen) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
