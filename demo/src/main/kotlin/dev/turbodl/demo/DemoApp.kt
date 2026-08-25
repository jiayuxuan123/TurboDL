package dev.turbodl.demo

import kotlin.system.exitProcess

/**
 * TurboDL demo launcher.
 *
 * Runs one of three self-contained examples:
 *   1  Kotlin-native plugin (services / events / extension points / disposer lifecycle)
 *   2  bootstrap usage (TurboBootstrap wiring + a real local download)
 *   3  Shim adapter template (bridging an external system via LinkParser + DownloadBackend)
 *
 * Usage:
 *   ./gradlew :demo:run --args="1"     # or 2, 3, or "all" (default)
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()?.trim()?.lowercase() ?: "all") {
        "1" -> runKotlinPluginExample()
        "2" -> runBootstrapExample()
        "3" -> runShimAdapterExample()
        "all" -> {
            runKotlinPluginExample()
            println()
            runBootstrapExample()
            println()
            runShimAdapterExample()
        }
        else -> {
            println("Unknown example. Use: 1 | 2 | 3 | all")
        }
    }
    // Some backends/servers keep daemon-ish worker threads around; exit explicitly so the
    // example process terminates promptly instead of lingering.
    exitProcess(0)
}
