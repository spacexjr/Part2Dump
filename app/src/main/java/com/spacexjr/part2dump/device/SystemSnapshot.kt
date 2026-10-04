package com.spacexjr.part2dump.device

import android.os.Build
import com.spacexjr.part2dump.core.P2DLog
import com.spacexjr.part2dump.root.Shell

data class SystemSnapshot(
    val manufacturer: String?,
    val brand: String?,
    val model: String?,
    val device: String?,
    val product: String?,
    val board: String?,
    val hardware: String?,
    val androidRelease: String?,
    val securityPatch: String?,
    val sdkInt: Int,
    val buildId: String?,
    val fingerprint: String?,
    val abis: List<String>,
    val kernelVersion: String?,
    val bootloader: String?,
    val props: Map<String, String>
) {
    val abi: String get() = abis.firstOrNull() ?: "?"

    val deviceModel: String get() = model?.takeIf { it.isNotBlank() }
        ?: device?.takeIf { it.isNotBlank() }
        ?: "Android"

    val deviceCodename: String get() = device?.takeIf { it.isNotBlank() } ?: "-"
}

object SystemSnapshotProvider {

    private const val COLLECTION_COMMAND = "getprop 2>/dev/null; echo '--KERNEL--'; uname -r 2>/dev/null"
    private const val KERNEL_MARKER = "--KERNEL--"
    private val PROP_LINE = Regex("^\\[(.+?)]:\\s*\\[(.*)]\\s*$")

    fun load(shell: Shell): SystemSnapshot {
        val raw = try {
            shell.exec(COLLECTION_COMMAND, Shell.QUICK_TIMEOUT_MS).stdout
        } catch (error: Exception) {
            P2DLog.w("Falha ao coletar propriedades do sistema", error)
            ""
        }
        val separatorIndex = raw.indexOf(KERNEL_MARKER)
        val propsText = if (separatorIndex >= 0) raw.substring(0, separatorIndex) else raw
        val kernelText = if (separatorIndex >= 0) raw.substring(separatorIndex + KERNEL_MARKER.length) else ""
        val kernel = kernelText.trim().lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val props = parseProps(propsText)
        return SystemSnapshot(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            device = Build.DEVICE,
            product = Build.PRODUCT,
            board = Build.BOARD,
            hardware = props["ro.boot.hardware"] ?: Build.HARDWARE,
            androidRelease = Build.VERSION.RELEASE,
            securityPatch = props["ro.build.version.security_patch"],
            sdkInt = Build.VERSION.SDK_INT,
            buildId = Build.ID,
            fingerprint = Build.FINGERPRINT,
            abis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            kernelVersion = kernel,
            bootloader = props["ro.boot.bootloader"],
            props = props
        )
    }

    fun parseProps(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        text.lineSequence().forEach { line ->
            val match = PROP_LINE.find(line.trim()) ?: return@forEach
            result[match.groupValues[1]] = match.groupValues[2]
        }
        return result
    }
}