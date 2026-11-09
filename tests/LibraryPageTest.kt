package com.evcockpit.mediahub

fun main() {
    val items = (0 until 2500).toList()
    require(LibraryPage.slice(items, 0, 20) == (0 until 20).toList())
    require(LibraryPage.slice(items, 1, 20) == (20 until 40).toList())
    require(LibraryPage.slice(items, 124, 20) == (2480 until 2500).toList())
    require(LibraryPage.slice(items, 125, 20).isEmpty())
    require(LibraryPage.slice(items, Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
    require(LibraryPage.slice(items, 0, Int.MAX_VALUE) == items)
    require(LibraryPage.slice(items, -1, 20).isEmpty())
    require(LibraryPage.slice(items, 0, 0).isEmpty())
    require(LibraryPage.slice(emptyList<String>(), 0, 10).isEmpty())
    require(LibraryPage.slice(listOf(1, 2, 3), 1, 2) == listOf(3))
    println("PASS: LibraryPage safe paging (10 cases)")
}
