package dev.turbodl.core

/**
 * Optional, immutable overrides for one download task.
 *
 * A null value means "inherit from [TurboConfig]". A non-null value is a
 * task-local override and does not change the global configuration.
 *
 * The policy intentionally has no adaptive-concurrency toggle. The base
 * [TurboConfig.adaptiveConcurrency] setting is always preserved.
 *
 * [backpressureGroup] has no equivalent in [TurboConfig], so a null value
 * remains null when a policy is snapshotted.
 */
public class TaskTransferPolicy private constructor(
    public val minSegmentSize: Long?,
    public val blockSize: Long?,
    public val segmentsPerConnection: Int?,
    public val slowStart: Boolean?,
    public val slowStartInitial: Int?,
    public val adaptiveConcurrencyMode: AdaptiveConcurrencyMode?,
    public val maxConnectionsPerHost: Int?,
    public val backpressureGroup: String?,
) {
    init {
        validateMinSegmentSize(minSegmentSize)
        validateSegmentsPerConnection(segmentsPerConnection)
        validateSlowStartInitial(slowStartInitial)
        validateMaxConnectionsPerHost(maxConnectionsPerHost)
        validateBackpressureGroup(backpressureGroup)
    }

    /** Builder for [TaskTransferPolicy]. Null values keep the base configuration. */
    public class Builder {
        private var minSegmentSizeValue: Long? = null
        private var blockSizeValue: Long? = null
        private var segmentsPerConnectionValue: Int? = null
        private var slowStartValue: Boolean? = null
        private var slowStartInitialValue: Int? = null
        private var adaptiveConcurrencyModeValue: AdaptiveConcurrencyMode? = null
        private var maxConnectionsPerHostValue: Int? = null
        private var backpressureGroupValue: String? = null

        public fun minSegmentSize(value: Long?): Builder = apply {
            validateMinSegmentSize(value)
            minSegmentSizeValue = value
        }

        public fun blockSize(value: Long?): Builder = apply {
            // TurboConfig currently has no additional init constraint for blockSize.
            blockSizeValue = value
        }

        public fun segmentsPerConnection(value: Int?): Builder = apply {
            validateSegmentsPerConnection(value)
            segmentsPerConnectionValue = value
        }

        public fun slowStart(value: Boolean?): Builder = apply {
            slowStartValue = value
        }

        public fun slowStartInitial(value: Int?): Builder = apply {
            validateSlowStartInitial(value)
            slowStartInitialValue = value
        }

        public fun adaptiveConcurrencyMode(value: AdaptiveConcurrencyMode?): Builder = apply {
            adaptiveConcurrencyModeValue = value
        }

        public fun maxConnectionsPerHost(value: Int?): Builder = apply {
            validateMaxConnectionsPerHost(value)
            maxConnectionsPerHostValue = value
        }

        public fun backpressureGroup(value: String?): Builder = apply {
            validateBackpressureGroup(value)
            backpressureGroupValue = value
        }

        public fun build(): TaskTransferPolicy = TaskTransferPolicy(
            minSegmentSize = minSegmentSizeValue,
            blockSize = blockSizeValue,
            segmentsPerConnection = segmentsPerConnectionValue,
            slowStart = slowStartValue,
            slowStartInitial = slowStartInitialValue,
            adaptiveConcurrencyMode = adaptiveConcurrencyModeValue,
            maxConnectionsPerHost = maxConnectionsPerHostValue,
            backpressureGroup = backpressureGroupValue,
        )
    }

    public companion object {
        @JvmStatic
        public fun builder(): Builder = Builder()
    }

    /**
     * Materialize inherited values so later changes to [base] cannot affect
     * this task. There is no group value in [TurboConfig]; an unset group
     * therefore remains null.
     */
    internal fun snapshot(base: TurboConfig): TaskTransferPolicy = TaskTransferPolicy(
        minSegmentSize = minSegmentSize ?: base.minSegmentSize,
        blockSize = blockSize ?: base.blockSize,
        segmentsPerConnection = segmentsPerConnection ?: base.segmentsPerConnection,
        slowStart = slowStart ?: base.slowStart,
        slowStartInitial = slowStartInitial ?: base.slowStartInitial,
        adaptiveConcurrencyMode = adaptiveConcurrencyMode ?: base.adaptiveConcurrencyMode,
        maxConnectionsPerHost = maxConnectionsPerHost ?: base.maxConnectionsPerHost,
        backpressureGroup = backpressureGroup,
    )

    /**
     * Apply only this policy's task-level fields to [base]. Global connection,
     * speed-limit, I/O budget, adaptive-concurrency, and unrelated settings
     * stay untouched.
     */
    internal fun effectiveConfig(base: TurboConfig): TurboConfig = base.copy(
        minSegmentSize = minSegmentSize ?: base.minSegmentSize,
        blockSize = blockSize ?: base.blockSize,
        segmentsPerConnection = segmentsPerConnection ?: base.segmentsPerConnection,
        slowStart = slowStart ?: base.slowStart,
        slowStartInitial = slowStartInitial ?: base.slowStartInitial,
        adaptiveConcurrencyMode = adaptiveConcurrencyMode ?: base.adaptiveConcurrencyMode,
        maxConnectionsPerHost = maxConnectionsPerHost ?: base.maxConnectionsPerHost,
    )
}

private fun validateMinSegmentSize(value: Long?) {
    value?.let { require(it >= 4L * 1024) { "minSegmentSize must be at least 4 KiB" } }
}

private fun validateSegmentsPerConnection(value: Int?) {
    value?.let { require(it in 1..64) { "segmentsPerConnection must be in 1..64" } }
}

private fun validateSlowStartInitial(value: Int?) {
    value?.let { require(it >= 0) { "slowStartInitial must not be negative" } }
}

private fun validateMaxConnectionsPerHost(value: Int?) {
    value?.let { require(it >= 0) { "maxConnectionsPerHost must not be negative" } }
}

private fun validateBackpressureGroup(value: String?) {
    if (value == null) return
    require(value.isNotBlank()) { "backpressureGroup must not be blank" }
    require(value.length <= 128) { "backpressureGroup must be at most 128 characters" }
    require(
        value.none { it == '\n' || it == '\r' || it == '\u0085' || it == '\u2028' || it == '\u2029' },
    ) {
        "backpressureGroup must not contain line breaks"
    }
}
