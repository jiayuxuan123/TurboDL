package dev.turbodl.plugin.runtime

import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.TurboEvent
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Type-safe event bus with per-listener exception isolation.
 *
 * Two responsibilities:
 *  1. Observation — listeners subscribe to [TurboEvent]s (task created / progress / failed /
 *     completed, bridged from turbodl-core). A throwing listener is caught and logged; it can
 *     never take down the bus or the download engine.
 *  2. Interception — at submit time, plugins may inspect and MODIFY the [DownloadRequest]
 *     before it reaches the engine (e.g. rewrite URL, inject headers). Interceptors run in
 *     registration order; each receives the previous one's output.
 *
 * NOTE: reserved — event objects are designed to be serialization-friendly at the boundary so a
 * future JS bridge can forward them to scripts; serialization itself is not implemented now.
 */
class EventBus(private val logger: (String, Throwable?) -> Unit = { _, _ -> }) {

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val interceptors = CopyOnWriteArrayList<Interceptor>()

    private class Listener(val ownerPluginId: String, val fn: (TurboEvent) -> Unit)
    private class Interceptor(val ownerPluginId: String, val fn: (DownloadRequest) -> DownloadRequest)

    /** Subscribe to all events. Returns a handle to unsubscribe (wire it into a Disposer). */
    fun subscribe(ownerPluginId: String, listener: (TurboEvent) -> Unit): Subscription {
        val l = Listener(ownerPluginId, listener)
        listeners.add(l)
        return Subscription { listeners.remove(l) }
    }

    /**
     * Register a submit-time request interceptor. Returns a handle to remove it.
     * Interceptors may return a modified request; returning the input unchanged is fine.
     */
    fun intercept(ownerPluginId: String, interceptor: (DownloadRequest) -> DownloadRequest): Subscription {
        val i = Interceptor(ownerPluginId, interceptor)
        interceptors.add(i)
        return Subscription { interceptors.remove(i) }
    }

    /** Publish an event to all listeners; isolates and logs individual listener failures. */
    fun publish(event: TurboEvent) {
        for (l in listeners) {
            try {
                l.fn(event)
            } catch (t: Throwable) {
                logger("event listener of plugin '${l.ownerPluginId}' threw on $event", t)
            }
        }
    }

    /**
     * Run the interceptor chain over [request]. A failing interceptor is isolated (logged and
     * skipped), so a broken plugin cannot corrupt submission.
     */
    fun applyInterceptors(request: DownloadRequest): DownloadRequest {
        var current = request
        for (i in interceptors) {
            current = try {
                i.fn(current)
            } catch (t: Throwable) {
                logger("request interceptor of plugin '${i.ownerPluginId}' threw; skipping", t)
                current
            }
        }
        return current
    }

    /** Counts for diagnostics. */
    fun listenerCount(): Int = listeners.size
    fun interceptorCount(): Int = interceptors.size

    fun interface Subscription {
        fun cancel()
    }
}
