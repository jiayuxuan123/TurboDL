package dev.turbodl.demo

import dev.turbodl.core.ApiVersion
import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.TurboEvent
import dev.turbodl.plugin.runtime.Plugin
import dev.turbodl.plugin.runtime.PluginContext
import dev.turbodl.plugin.runtime.PluginHost
import dev.turbodl.plugin.runtime.ext.ExtensionPoints
import dev.turbodl.plugin.runtime.ext.TaskPreHook

/**
 * Example 1 — a minimal Kotlin-native plugin.
 *
 * Demonstrates the full lifecycle a real plugin uses:
 *  - declaring an id, a human name and the minimum API version it targets;
 *  - registering a SERVICE other plugins can depend on;
 *  - subscribing to engine EVENTS (auto-unsubscribed on unload);
 *  - registering an EXTENSION-POINT implementation (a TaskPreHook that injects a header);
 *  - registering an extra DISPOSER for custom teardown.
 *
 * Everything registered through [PluginContext] is auto-cleaned when the plugin is unloaded, so
 * this plugin needs no manual teardown code. This file is a copy-paste starting point.
 */
class GreetingPlugin : Plugin {
    override val id = "demo.greeting"
    override val name = "Greeting Demo Plugin"

    // Target the 1.x API line. On a future 2.x host this plugin would be rejected up front
    // (state = INCOMPATIBLE) rather than run against an incompatible engine.
    override val requiredApiVersion = ApiVersion(1, 0, 0)

    private var eventsSeen = 0

    override fun onLoad(context: PluginContext) {
        context.log("loading against host API ${context.apiVersion}")

        // (1) Publish a service. Any plugin that declares dependencies = setOf("demo.greeting")
        // will only load after this line runs.
        context.registerService(id, this)

        // (2) Observe engine events. This lambda is removed automatically on unload.
        context.onEvent { event ->
            eventsSeen++
            when (event) {
                is TurboEvent.Created -> context.log("task ${event.taskId} created")
                is TurboEvent.Completed -> context.log("task ${event.taskId} done -> ${event.file.name}")
                is TurboEvent.Failed -> context.log("task ${event.taskId} failed: ${event.reason}")
                else -> {}
            }
        }

        // (3) Register an extension-point implementation: a pre-hook that injects a header.
        val hook = TaskPreHook { request ->
            request.copy(headers = request.headers + ("X-Demo-Greeting" to "hello"))
        }
        context.registerExtension(ExtensionPoints.TASK_PRE_HOOK, hook, priority = 0)

        // (4) Register custom teardown; runs (LIFO) alongside the auto-registered cleanups.
        context.disposer.register { context.log("goodbye, saw $eventsSeen event(s)") }
    }

    override fun onUnload() {
        // Optional: extra work before the disposer chain drains. Usually left empty.
    }
}

/** Runs example 1 end-to-end against a bare [PluginHost] (no bootstrap). */
fun runKotlinPluginExample() {
    println("=== Example 1: Kotlin-native plugin ===")
    val host = PluginHost()
    host.install(GreetingPlugin())

    // Show the plugin is loaded and its hook is registered.
    println(host.diagnostics().render())

    // Demonstrate the pre-hook transforming a request (this is what an integration layer would
    // call at submit time; here we invoke the extension directly for illustration).
    val hooks = host.extensions.all(ExtensionPoints.TASK_PRE_HOOK)
    var request = DownloadRequest("https://example.com/a.bin", java.io.File("a.bin"))
    hooks.forEach { request = it.beforeSubmit(request) }
    println("request headers after pre-hooks: ${request.headers}")

    // Unload: disposer chain removes the service, the event listener and the extension.
    host.uninstall("demo.greeting")
    println("after unload, pre-hooks registered: ${host.extensions.all(ExtensionPoints.TASK_PRE_HOOK).size}")
    host.shutdown()
}
