package com.evcockpit.mediahub

fun main() {
    check(StreamProbe.classify(200).status == StreamStatus.SERVER_RESPONDS)
    check(StreamProbe.classify(206).status == StreamStatus.SERVER_RESPONDS)
    check(StreamProbe.classify(302).status == StreamStatus.REDIRECT)
    check(StreamProbe.classify(401).status == StreamStatus.LOGIN_REQUIRED)
    check(StreamProbe.classify(403).status == StreamStatus.LOGIN_REQUIRED)
    check(StreamProbe.classify(404).status == StreamStatus.NOT_FOUND)
    check(StreamProbe.classify(410).status == StreamStatus.NOT_FOUND)
    check(StreamProbe.classify(405).status == StreamStatus.HEAD_UNSUPPORTED)
    check(StreamProbe.classify(501).status == StreamStatus.HEAD_UNSUPPORTED)
    check(StreamProbe.classify(500).status == StreamStatus.SERVER_PROBLEM)
    check(StreamProbe.classify(429).status == StreamStatus.UNKNOWN)
    check(StreamProbe.classify(403).description().contains("403"))
    println("PASS: StreamProbe 12 HTTP status assertions (offline)")
}
