package com.spacexjr.part2dump.ui

import androidx.annotation.StringRes
import com.spacexjr.part2dump.R
import com.spacexjr.part2dump.dump.DumpError
import com.spacexjr.part2dump.root.RootStatus

object UserMessages {

    @StringRes
    fun forRoot(status: RootStatus): Int = when (status) {
        RootStatus.AVAILABLE -> R.string.root_status_available
        RootStatus.DENIED -> R.string.error_root_denied
        RootStatus.UNAVAILABLE -> R.string.root_status_unavailable
        RootStatus.ERROR -> R.string.root_status_error
    }

    @StringRes
    fun forError(error: DumpError): Int = when (error) {
        DumpError.NONE -> R.string.status_idle
        DumpError.NO_SELECTION -> R.string.error_no_selection
        DumpError.ROOT_DENIED -> R.string.error_root_denied
        DumpError.ROOT_UNAVAILABLE -> R.string.error_root_unavailable
        DumpError.INVALID_TARGET -> R.string.error_invalid_target
        DumpError.PARTITION_MISSING -> R.string.error_partition_not_found
        DumpError.UNABLE_TO_READ -> R.string.error_unable_to_read
        DumpError.OUTPUT_CREATE_FAILED -> R.string.error_unable_to_create_output
        DumpError.DD_FAILED -> R.string.error_dump_failed
        DumpError.SIZE_MISMATCH -> R.string.error_size_mismatch
        DumpError.TIMEOUT -> R.string.error_timeout
        DumpError.CANCELLED -> R.string.error_cancelled
        DumpError.NOT_ENOUGH_STORAGE -> R.string.error_not_enough_storage
        DumpError.UNKNOWN_LAYOUT -> R.string.error_unknown_layout
        DumpError.INTERNAL -> R.string.error_internal
    }
}