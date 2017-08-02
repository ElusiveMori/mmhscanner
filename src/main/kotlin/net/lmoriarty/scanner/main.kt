package net.lmoriarty.scanner

fun main(args: Array<String>) {
    val extractor = MakeMeHostExtractor()
    println(extractor.extractRows())
}