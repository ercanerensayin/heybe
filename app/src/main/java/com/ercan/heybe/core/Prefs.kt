package com.ercan.heybe.core

import android.content.Context

/** Favoriler ve son kullanılanlar (2. adımda Room'a taşınacak). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("heybe", Context.MODE_PRIVATE)

    fun favorites(): Set<String> = sp.getStringSet("fav", emptySet())!!.toSet()

    fun toggleFavorite(id: String): Set<String> {
        val s = favorites().toMutableSet()
        if (!s.add(id)) s.remove(id)
        sp.edit().putStringSet("fav", s).apply()
        return s
    }

    fun recents(): List<String> = RecentList.parse(sp.getString("recent", "") ?: "")

    fun addRecent(id: String): List<String> {
        val list = RecentList.push(recents(), id)
        sp.edit().putString("recent", list.joinToString(",")).apply()
        return list
    }
}
