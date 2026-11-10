package dev.turbodl.core

/** Reject work whose estimated saving cannot repay another request's startup. */
internal object TailAssistPolicy {
    private const val MIN_OBSERVATION_NANOS = 1_000_000L
    private const val MIN_REMAINING_SECONDS = 0.100

    fun usefulEtaSeconds(
        remaining: Long,
        bytesPerSecond: Double,
        observedNanos: Long,
        startupNanos: Long,
    ): Double? {
        if (remaining <= 0 || !bytesPerSecond.isFinite() || bytesPerSecond <= 0 ||
            observedNanos < MIN_OBSERVATION_NANOS) return null
        val eta = remaining / bytesPerSecond
        // Require room for startup and a safety margin before adding a request.
        val breakEven = maxOf(MIN_REMAINING_SECONDS, startupNanos.coerceAtLeast(0) / 1e9 * 4)
        return eta.takeIf { it.isFinite() && it > breakEven }
    }

    /** Balance finish times, including the helper's measured request startup. */
    fun retainedRemaining(
        remaining: Long,
        originalRate: Double,
        helperRate: Double,
        helperStartupNanos: Long,
        minimumBytes: Long,
    ): Long? {
        val minimum = minimumBytes.coerceAtLeast(1)
        if (remaining / 2 < minimum || !originalRate.isFinite() || originalRate <= 0) return null
        if (!helperRate.isFinite() || helperRate <= 0) return remaining / 2
        val ratio = originalRate / helperRate
        val kept = remaining - remaining / (1 + ratio) +
            helperStartupNanos.coerceAtLeast(0) / 1e9 * (originalRate / (1 + ratio))
        if (!kept.isFinite() || kept >= remaining) return null
        val retained = kept.toLong().coerceIn(minimum, remaining - minimum)
        val finishSeconds = maxOf(retained / originalRate,
            helperStartupNanos.coerceAtLeast(0) / 1e9 + (remaining - retained) / helperRate)
        // A minimum-size handoff to a very slow peer can cost more than waiting.
        if (!finishSeconds.isFinite() || finishSeconds >= remaining / originalRate * .95) return null
        return retained
    }
}

/** Owned by one segment and guarded by its write lock. Rates use byte/time
 * windows, so a burst of buffered reads cannot outweigh the preceding wait. */
internal class TailTransferMetrics {
    private val rateWindowNanos = 50_000_000L
    private var attemptStarted = 0L
    private var firstProgress = 0L
    private var startup = 0L
    private var hasData = false
    private var firstReadBytes = 0L
    private var windowStarted = 0L
    private var windowBytes = 0L
    private var recentRate = 0.0

    fun begin(now: Long) {
        attemptStarted = now
        hasData = false
        windowBytes = 0
        recentRate = 0.0
    }

    fun record(bytes: Long, now: Long) {
        if (bytes <= 0) return
        if (!hasData) {
            firstProgress = now
            startup = (now - attemptStarted).coerceAtLeast(0)
            firstReadBytes = bytes
            windowStarted = now
            hasData = true
            return
        }
        windowBytes += bytes
        val elapsed = now - windowStarted
        if (elapsed >= rateWindowNanos) {
            val rate = windowBytes * 1e9 / elapsed
            recentRate = if (recentRate <= 0) rate else recentRate * .75 + rate * .25
            windowStarted = now
            windowBytes = 0
        }
    }

    fun usefulEtaSeconds(remaining: Long, now: Long): Double? {
        if (!hasData) return null
        val elapsed = now - windowStarted
        // A slow first byte followed by buffered reads is not a slow body.
        // Give it a partial observation window before using the first-read rate.
        if (recentRate <= 0 && windowBytes == 0L &&
            now - firstProgress < minOf(rateWindowNanos, startup)) return null
        val rate = if (recentRate <= 0) {
            // A small slow tail can finish before a full sampling window. The
            // first read gives a conservative rate including its startup cost;
            // the policy still requires the saved time to repay that cost.
            if (windowBytes > 0 && elapsed > 0) windowBytes * 1e9 / elapsed
            else firstReadBytes * 1e9 / startup.coerceAtLeast(1)
        } else if (elapsed >= rateWindowNanos) {
            // A partial/stalled window can lower the estimate before another
            // read completes. This also permits assisting a stalled body.
            val partial = maxOf(1.0, windowBytes * 1e9 / elapsed)
            if (recentRate > 0) minOf(recentRate, partial) else partial
        } else recentRate
        return TailAssistPolicy.usefulEtaSeconds(remaining, rate, now - firstProgress, startup)
    }

    fun requestStartupNanos(): Long = if (hasData) startup else 0
}
