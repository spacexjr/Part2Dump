package com.spacexjr.part2dump.dump

import com.spacexjr.part2dump.checksum.ChecksumCalculator
import com.spacexjr.part2dump.checksum.ManifestEntry
import com.spacexjr.part2dump.checksum.ManifestWriter
import com.spacexjr.part2dump.core.Formats
import com.spacexjr.part2dump.core.P2DLog
import com.spacexjr.part2dump.device.DeviceInfo
import com.spacexjr.part2dump.partition.Partition
import com.spacexjr.part2dump.root.Shell
import com.spacexjr.part2dump.storage.BackupLayout
import com.spacexjr.part2dump.storage.SpaceReport
import com.spacexjr.part2dump.storage.StorageManager
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory

data class BackupResult(
    val success: Boolean,
    val cancelled: Boolean,
    val backupDir: File?,
    val entries: List<ManifestEntry>,
    val error: DumpError?,
    val detail: String?,
    val space: SpaceReport?
)

interface BackupListener {
    fun onState(state: BackupUiState)
    fun onFinished(result: BackupResult)
}

class DumpCoordinator(
    private val storage: StorageManager,
    private val shell: Shell,
    private val engine: DumpEngine,
    private val checksums: ChecksumCalculator,
    private val manifestWriter: ManifestWriter,
    private val toolVersion: String
) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor(
        ThreadFactory { runnable ->
            Thread(runnable, "p2d-backup").apply { isDaemon = true }
        }
    )

    @Volatile
    private var running = false

    @Volatile
    private var pending: Future<*>? = null

    val isRunning: Boolean get() = running

    fun start(
        partitions: List<Partition>,
        device: DeviceInfo,
        listener: BackupListener
    ): Boolean {
        if (running) {
            P2DLog.w("Backup já está em execução")
            return false
        }
        if (partitions.isEmpty()) {
            listener.onFinished(
                BackupResult(false, false, null, emptyList(), DumpError.NO_SELECTION, null, null)
            )
            return false
        }
        running = true
        engine.resetCancel()
        pending = executor.submit { run(partitions, device, listener) }
        return true
    }

    fun cancel() {
        if (running) engine.requestCancel()
    }

    fun shutdown() {
        executor.shutdownNow()
        pending?.cancel(true)
    }

    private fun run(
        partitions: List<Partition>,
        device: DeviceInfo,
        listener: BackupListener
    ) {
        val entries = mutableListOf<ManifestEntry>()
        try {
            listener.onState(BackupUiState(phase = BackupPhase.PREPARING, total = partitions.size, indeterminate = true))

            val space = evaluateSpace(partitions)
            if (!space.isEnough) {
                P2DLog.w("Espaço insuficiente: precisa ${space.requiredBytes}, disponível ${space.availableBytes}")
                listener.onFinished(
                    BackupResult(false, false, null, entries, DumpError.NOT_ENOUGH_STORAGE, null, space)
                )
                return
            }

            val timestamp = Formats.timestampForFolder()
            val directory = BackupLayout.createBackupDirectory(storage, device.model, timestamp)
            if (directory == null) {
                listener.onFinished(
                    BackupResult(
                        false,
                        false,
                        null,
                        entries,
                        DumpError.OUTPUT_CREATE_FAILED,
                        storage.backupRoot().absolutePath,
                        space
                    )
                )
                return
            }
            P2DLog.i("Backup em ${directory.absolutePath}")

            val totalBytes = space.requiredBytes
            var completedBytes = 0L

            for ((index, partition) in partitions.withIndex()) {
                if (engine.isCancelRequested()) {
                    finishCancelled(listener, directory, entries, space)
                    return
                }

                val outputFile = File(directory, BackupLayout.imageFileName(partition))
                listener.onState(
                    BackupUiState(
                        phase = BackupPhase.DUMPING,
                        partition = partition,
                        index = index,
                        total = partitions.size,
                        bytesDone = completedBytes,
                        bytesTotal = totalBytes,
                        percent = runningPercent(completedBytes, totalBytes, index, partitions.size),
                        indeterminate = totalBytes <= 0L
                    )
                )

                val outcome = engine.dump(
                    partition = partition,
                    outputFile = outputFile,
                    onProgress = { done ->
                        val bytes = completedBytes + done
                        listener.onState(
                            BackupUiState(
                                phase = BackupPhase.DUMPING,
                                partition = partition,
                                index = index,
                                total = partitions.size,
                                bytesDone = bytes,
                                bytesTotal = totalBytes,
                                percent = runningPercent(bytes, totalBytes, index, partitions.size),
                                indeterminate = totalBytes <= 0L
                            )
                        )
                    },
                    onLog = { line -> P2DLog.d("dd[$index}/${partitions.size}] $line") }
                )

                if (!outcome.success) {
                    entries.add(
                        ManifestEntry(
                            name = partition.name,
                            linkPath = partition.linkPath,
                            devicePath = partition.devicePath,
                            sizeBytes = 0L,
                            slot = partition.slot.suffix.ifBlank { "none" },
                            group = partition.group.label,
                            sha256 = null,
                            durationMs = outcome.durationMs,
                            status = ManifestEntry.STATUS_FAILED,
                            error = outcome.detail
                        )
                    )
                    P2DLog.e("Falha no dump de ${partition.name}: ${outcome.error} ${outcome.detail}")
                    listener.onFinished(
                        BackupResult(
                            false,
                            outcome.error == DumpError.CANCELLED,
                            directory,
                            entries,
                            outcome.error,
                            outcome.detail,
                            space
                        )
                    )
                    return
                }

                listener.onState(
                    BackupUiState(
                        phase = BackupPhase.HASHING,
                        partition = partition,
                        index = index,
                        total = partitions.size,
                        bytesDone = completedBytes + outcome.sizeBytes,
                        bytesTotal = totalBytes,
                        percent = runningPercent(completedBytes + outcome.sizeBytes, totalBytes, index, partitions.size),
                        indeterminate = true
                    )
                )

                val checksum = checksums.calculate(outputFile) { engine.isCancelRequested() }
                if (checksum.cancelled) {
                    finishCancelled(listener, directory, entries, space)
                    return
                }
                if (checksum.sha256.isNullOrBlank()) {
                    P2DLog.w("SHA-256 indisponível para ${partition.name}: ${checksum.error}")
                }

                entries.add(
                    ManifestEntry(
                        name = partition.name,
                        linkPath = partition.linkPath,
                        devicePath = partition.devicePath,
                        sizeBytes = outcome.sizeBytes,
                        slot = partition.slot.suffix.ifBlank { "none" },
                        group = partition.group.label,
                        sha256 = checksum.sha256,
                        durationMs = outcome.durationMs,
                        status = ManifestEntry.STATUS_OK,
                        error = checksum.error
                    )
                )
                completedBytes += outcome.sizeBytes
            }

            listener.onState(
                BackupUiState(
                    phase = BackupPhase.MANIFEST,
                    index = partitions.size,
                    total = partitions.size,
                    bytesDone = completedBytes,
                    bytesTotal = totalBytes,
                    percent = 99,
                    indeterminate = true
                )
            )

            val json = manifestWriter.build(entries, device, directory.absolutePath, toolVersion)
            val manifestWritten = manifestWriter.write(directory, json, shell)
            P2DLog.i("Manifest gravado: $manifestWritten")

            listener.onState(
                BackupUiState(
                    phase = BackupPhase.DONE,
                    index = partitions.size,
                    total = partitions.size,
                    bytesDone = completedBytes,
                    bytesTotal = totalBytes,
                    percent = 100,
                    indeterminate = false
                )
            )
            listener.onFinished(BackupResult(true, false, directory, entries, null, null, space))
        } catch (error: Throwable) {
            P2DLog.e("Falha inesperada durante o backup", error)
            listener.onFinished(
                BackupResult(false, false, null, entries, DumpError.INTERNAL, error.message, null)
            )
        } finally {
            running = false
        }
    }

    private fun finishCancelled(
        listener: BackupListener,
        directory: File?,
        entries: List<ManifestEntry>,
        space: SpaceReport?
    ) {
        P2DLog.i("Backup cancelado pelo usuário")
        listener.onState(BackupUiState(phase = BackupPhase.CANCELLED, total = entries.size))
        listener.onFinished(BackupResult(false, true, directory, entries, DumpError.CANCELLED, null, space))
    }

    private fun evaluateSpace(partitions: List<Partition>): SpaceReport {
        val known = partitions.filter { it.hasKnownSize }
        val required = known.sumOf { it.sizeBytes }
        val unknown = partitions.size - known.size
        return StorageManager.evaluate(required + RESERVE_BYTES, storage.freeBytes(), unknown)
    }

    private fun runningPercent(done: Long, total: Long, index: Int, count: Int): Int {
        if (total > 0L) {
            return Formats.percentOf(done, total).coerceAtMost(MAX_RUNNING_PERCENT)
        }
        if (count <= 0) return 0
        val base = (index.toDouble() / count.toDouble() * 100.0).toInt()
        return base.coerceIn(0, MAX_RUNNING_PERCENT)
    }

    companion object {
        private const val MAX_RUNNING_PERCENT = 99
        private const val RESERVE_BYTES = 8L * 1024L * 1024L
    }
}