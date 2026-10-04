package com.spacexjr.part2dump.root

import androidx.annotation.StringRes

enum class RootStatus {
    AVAILABLE,
    DENIED,
    UNAVAILABLE,
    ERROR
}

data class RootInfo(
    val status: RootStatus,
    val binaryPath: String?,
    val uid: Int?,
    @param:StringRes val messageRes: Int,
    val detail: String? = null
) {
    val isAvailable: Boolean get() = status == RootStatus.AVAILABLE

    companion object {
        fun unavailable(@StringRes messageRes: Int, detail: String? = null) =
            RootInfo(RootStatus.UNAVAILABLE, null, null, messageRes, detail)

        fun denied(@StringRes messageRes: Int, binaryPath: String? = null, detail: String? = null) =
            RootInfo(RootStatus.DENIED, binaryPath, null, messageRes, detail)

        fun error(@StringRes messageRes: Int, binaryPath: String? = null, detail: String? = null) =
            RootInfo(RootStatus.ERROR, binaryPath, null, messageRes, detail)
    }
}