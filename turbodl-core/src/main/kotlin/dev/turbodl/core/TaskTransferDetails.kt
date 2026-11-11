package dev.turbodl.core

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

enum class TransferPhase { QUEUED, PROBING, TRANSFERRING, VERIFYING, FINALIZING, COMPLETED, PAUSED, CANCELED, FAILED }

/** A point-in-time view. Active requests are HTTP calls, not physical TCP sockets. */
class TaskTransferDetails internal constructor(
    val taskId: Long,
    val phase: TransferPhase,
    val configuredConnections: Int,
    val activeRequests: Int,
    val peakActiveRequests: Int,
    val requestCount: Long,
    val receivedBytes: Long,
    val timeToFirstByteMs: Long?,
    val elapsedMs: Long,
    val probeMs: Long,
    val transferMs: Long,
    val finalizationMs: Long,
    val lastHttpStatus: Int?,
    val failureStage: TransferPhase?,
    val requestMetricsAvailable: Boolean,
)

internal class TaskTelemetry(val taskId: Long, val connections: Int) {
    @Volatile var requestMetricsAvailable: Boolean = false
    var onHttpResponse: ((String, Int, String?) -> Unit)? = null
    private val createdAt = System.nanoTime()
    private var startedAt = createdAt
    private var current = TransferPhase.QUEUED
    private var failedAt: TransferPhase? = null
    private var endedAt: Long? = null
    private val durations = AtomicLongArray(TransferPhase.entries.size)
    private val active = AtomicInteger()
    private val peak = AtomicInteger()
    private val requests = AtomicLong()
    private val bytes = AtomicLong()
    private val firstByte = AtomicLong()
    @Volatile private var httpStatus: Int? = null

    @Synchronized fun phase(next: TransferPhase) {
        if (current == next) return
        val now = System.nanoTime()
        durations.addAndGet(current.ordinal, now - startedAt)
        if (next == TransferPhase.FAILED) failedAt = current
        current = next
        startedAt = now
        if (next == TransferPhase.COMPLETED || next == TransferPhase.FAILED ||
            next == TransferPhase.PAUSED || next == TransferPhase.CANCELED) endedAt = now
        else endedAt = null
    }

    fun response(code: Int) { if (code >= 400) httpStatus = code }

    fun received(count: Long, probe: Boolean = false) {
        bytes.addAndGet(count)
        if (probe) return
        if (firstByte.compareAndSet(0, System.nanoTime())) {
            synchronized(this) {
                if (current == TransferPhase.PROBING || current == TransferPhase.QUEUED) {
                    phase(TransferPhase.TRANSFERRING)
                }
            }
        }
    }

    fun listener(): EventListener = object : EventListener() {
        private val finished = java.util.concurrent.atomic.AtomicBoolean()
        override fun callStart(call: Call) {
            requests.incrementAndGet()
            val live = active.incrementAndGet()
            peak.accumulateAndGet(live, ::maxOf)
        }
        private fun finish() { if (finished.compareAndSet(false, true)) active.decrementAndGet() }
        override fun callEnd(call: Call) = finish()
        override fun callFailed(call: Call, ioe: IOException) = finish()
    }

    @Synchronized fun snapshot(): TaskTransferDetails {
        val now = endedAt ?: System.nanoTime()
        fun millis(p: TransferPhase): Long = (durations.get(p.ordinal) +
            if (p == current && endedAt == null) now - startedAt else 0) / 1_000_000
        return TaskTransferDetails(taskId, current, connections, active.get().coerceAtLeast(0), peak.get(),
            requests.get(), bytes.get(), firstByte.get().takeIf { it != 0L }?.let { (it - createdAt) / 1_000_000 },
            (now - createdAt) / 1_000_000, millis(TransferPhase.PROBING), millis(TransferPhase.TRANSFERRING),
            millis(TransferPhase.VERIFYING) + millis(TransferPhase.FINALIZING), httpStatus, failedAt,
            requestMetricsAvailable || requests.get() > 0)
    }
}

internal object TaskTransferInstrumentation : Interceptor {
    val listeners = EventListener.Factory { call ->
        call.request().tag(TaskTelemetry::class.java)?.listener() ?: EventListener.NONE
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val telemetry = chain.request().tag(TaskTelemetry::class.java) ?: return response
        telemetry.response(response.code)
        telemetry.onHttpResponse?.invoke(
            chain.request().tag(RequestOrigin::class.java)?.url ?: chain.request().url.toString(),
            response.code, response.header("Retry-After"))
        val probe = chain.request().tag(ProbeCall::class.java) != null
        val body = response.body ?: return response
        val counted = object : ResponseBody() {
            private val stream: BufferedSource by lazy {
                object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        val count = super.read(sink, byteCount)
                        if (count > 0) telemetry.received(count, probe)
                        return count
                    }
                }.buffer()
            }
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = stream
        }
        return response.newBuilder().body(counted).build()
    }
}

internal object ProbeCall
