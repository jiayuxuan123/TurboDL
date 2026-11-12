package dev.turbodl.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import okhttp3.Call
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** Cancel blocking I/O when cancellation starts, without canceling a completed reusable call. */
internal class HttpCallCancellation(call: Call, context: CoroutineContext) {
    private val disposed = AtomicBoolean()
    private val watcher = CoroutineScope(context).launch(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!disposed.get()) call.cancel()
        }
    }

    fun dispose() {
        disposed.set(true)
        watcher.cancel()
    }
}
