package com.evcockpit.mediahub

/** Bounds-safe paging with no Android dependencies; catalogues can contain 2500 channels. */
object LibraryPage {
    fun <T> slice(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0 || items.isEmpty()) return emptyList()
        val start = page.toLong() * pageSize.toLong()
        if (start >= items.size) return emptyList()
        val end = (start + pageSize.toLong()).coerceAtMost(items.size.toLong())
        return items.subList(start.toInt(), end.toInt())
    }
}
