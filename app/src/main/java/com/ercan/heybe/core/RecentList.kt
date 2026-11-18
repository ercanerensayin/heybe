package com.ercan.heybe.core

object RecentList {
    const val MAX = 4
    fun parse(s: String): List<String> = s.split(",").filter { it.isNotBlank() }
    fun push(list: List<String>, id: String): List<String> = (listOf(id) + list.filter { it != id }).take(MAX)
}
