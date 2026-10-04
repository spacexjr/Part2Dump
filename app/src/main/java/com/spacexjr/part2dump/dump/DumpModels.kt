package com.spacexjr.part2dump.dump

import com.spacexjr.part2dump.partition.Partition

enum class DumpError {
    NONE,
    NO_SELECTION,
    ROOT_DENIED,
    ROOT_UNAVAILABLE,
    INVALID_TARGET,
    PARTITION_MISSING,
    UNABLE_TO_READ,
    OUTPUT_CREATE_FAILED,
    DD_FAILED,
    SIZE_MISMATCH,
    TIMEOUT,
    CANCELLED,
    NOT_ENOUGH_STORAGE,
    UNKNOWN_LAYOUT,
    INTERNAL
}

data class DumpOutcome(
    val success: Boolean,
    val sizeBytes: Long,
    val error: DumpError,
    val detail: String? = null,
    val durationMs: Long = 0L,
    val usedFallback: Boolean = false
) {
    companion object {
        fun failure(error: DumpError, detail: String? = null, durationMs: Long = 0L) =
            DumpOutcome(false, 0L, error, detail, durationMs)
    }
}

enum class BackupPhase {
    PREPARING,
    DUMPING,
    HASHING,
    MANIFEST,
    DONE,
    FAILED,
    CANCELLED
}

data class BackupUiState(
    val phase: BackupPhase,
    val partition: Partition? = null,
    val index: Int = 0,
    val total: Int = 0,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val percent: Int = 0,
    val indeterminate: Boolean = false,
    val sha256: String? = null,
    val error: DumpError? = null,
    val detail: String? = null
)