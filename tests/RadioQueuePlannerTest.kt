package com.evcockpit.mediahub

fun main() {
    val sources = listOf(
        RadioSource("radio1", "Anadolu Radyo", "https://test.example/r1.m3u8?token=secret"),
        RadioSource("radio2", "Klasik Müzik", "https://test.example/r2.mp3"),
        RadioSource("radio3", "Haber Radyo", "https://test.example/r3.mp3")
    )
    check(RadioQueuePlanner.plan(sources, listOf(RadioRequest(id="radio2")), -1) ==
        RadioQueue(listOf("radio1", "radio2", "radio3"), 1))
    check(RadioQueuePlanner.plan(sources, listOf(RadioRequest(search="klasik")), 0)?.index == 1)
    check(RadioQueuePlanner.plan(sources, listOf(RadioRequest(uri=sources[2].url)), 0)?.index == 2)
    check(RadioQueuePlanner.plan(sources, listOf(RadioRequest(id="radio3"), RadioRequest(id="radio1")), 1) ==
        RadioQueue(listOf("radio3", "radio1"), 1))
    check(RadioQueuePlanner.plan(sources, listOf(RadioRequest(id="bad")), 0) == null)
    check(RadioQueuePlanner.plan(sources, listOf(RadioRequest(uri="https://evil.example/video.m3u8")), 0) == null)
    check(RadioQueuePlanner.plan(emptyList(), listOf(RadioRequest(id="radio1")), 0) == null)
    check(RadioQueuePlanner.plan(sources, emptyList(), 0) == null)
    check(RadioQueuePlanner.plan(sources, listOf(RadioRequest(id="radio2")), 999)?.index == 1)
    // Bounded queues avoid excessively large Android Binder transactions.
    val big = (0 until 2500).map { RadioSource("id$it", "Radyo $it", "https://radio.example/$it.mp3") }
    for (selected in listOf(0, 1, 35, 1250, 2469, 2499)) {
        val result = RadioQueuePlanner.plan(big, listOf(RadioRequest(id="id$selected")), 0)!!
        check(result.ids.size <= RadioQueuePlanner.MAX_QUEUE_ITEMS)
        check(result.ids[result.index] == "id$selected")
        check(result.ids.toSet().size == result.ids.size)
    }
    val multiple = big.map { RadioRequest(id=it.id) }
    val result = RadioQueuePlanner.plan(big, multiple, 1499)!!
    check(result.ids.size == RadioQueuePlanner.MAX_QUEUE_ITEMS)
    check(result.ids[result.index] == "id1499")
    val around = RadioQueuePlanner.plan(big, listOf(RadioRequest(id="id1200")), 0)!!
    check(around.ids.first() == "id1180" && around.ids.last() == "id1220")
    println("PASS: radio matching and bounded Android Auto queues (17 cases)")
}
