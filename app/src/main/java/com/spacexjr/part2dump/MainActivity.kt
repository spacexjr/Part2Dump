package com.spacexjr.part2dump

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.spacexjr.part2dump.checksum.ChecksumCalculator
import com.spacexjr.part2dump.checksum.ManifestWriter
import com.spacexjr.part2dump.core.Formats
import com.spacexjr.part2dump.core.P2DLog
import com.spacexjr.part2dump.device.DeviceInfo
import com.spacexjr.part2dump.device.SlotFilter
import com.spacexjr.part2dump.device.SystemSnapshot
import com.spacexjr.part2dump.device.SystemSnapshotProvider
import com.spacexjr.part2dump.dump.BackupListener
import com.spacexjr.part2dump.dump.BackupPhase
import com.spacexjr.part2dump.dump.BackupResult
import com.spacexjr.part2dump.dump.BackupUiState
import com.spacexjr.part2dump.dump.DumpCoordinator
import com.spacexjr.part2dump.dump.DumpEngine
import com.spacexjr.part2dump.dump.DumpError
import com.spacexjr.part2dump.partition.Partition
import com.spacexjr.part2dump.partition.PartitionGroup
import com.spacexjr.part2dump.partition.PartitionRepository
import com.spacexjr.part2dump.partition.PartitionScan
import com.spacexjr.part2dump.root.RootInfo
import com.spacexjr.part2dump.root.RootManager
import com.spacexjr.part2dump.root.RootStatus
import com.spacexjr.part2dump.storage.SpaceReport
import com.spacexjr.part2dump.storage.StorageManager
import com.spacexjr.part2dump.storage.StoragePermissionHelper
import com.spacexjr.part2dump.ui.BackupResultDialog
import com.spacexjr.part2dump.ui.DeviceInfoDialog
import com.spacexjr.part2dump.ui.PartitionAdapter
import com.spacexjr.part2dump.ui.PartitionInspector
import com.spacexjr.part2dump.ui.UserMessages
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var dumpButton: Button
    private lateinit var listView: ListView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var topAppBar: MaterialToolbar
    private lateinit var rootStatusText: TextView
    private lateinit var pathsText: TextView
    private lateinit var slotToggle: MaterialButtonToggleGroup

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isWorking = AtomicBoolean(false)

    private val rootManager = RootManager()
    private val storage by lazy { StorageManager(this) }
    private val selection = linkedSetOf<String>()

    private var adapter: PartitionAdapter? = null
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var engine: DumpEngine? = null
    private var coordinator: DumpCoordinator? = null
    private var inspectorDialog: AlertDialog? = null

    private var snapshot: SystemSnapshot? = null
    private var rootInfo: RootInfo? = null
    private var scan: PartitionScan? = null
    private var allPartitions: List<Partition> = emptyList()
    private var visiblePartitions: List<Partition> = emptyList()
    private var currentFilter: SlotFilter = SlotFilter.ALL
    private var lastBackupDirectory: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dumpButton = findViewById(R.id.dumpButton)
        listView = findViewById(R.id.partitionsList)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        topAppBar = findViewById(R.id.topAppBar)
        rootStatusText = findViewById(R.id.rootStatusText)
        pathsText = findViewById(R.id.pathsText)
        slotToggle = findViewById(R.id.slotToggle)

        setSupportActionBar(topAppBar)

        val listAdapter = PartitionAdapter(
            context = this,
            onToggle = { partition, checked -> onPartitionToggled(partition, checked) },
            onLongPress = { partition ->
                if (!isWorking.get()) openInspector(partition)
                true
            }
        )
        adapter = listAdapter
        listView.choiceMode = ListView.CHOICE_MODE_NONE
        listView.adapter = listAdapter

        dumpButton.setOnClickListener { onDumpButtonClicked() }
        listView.setOnItemClickListener { _, _, position, _ ->
            val partition = listAdapter.itemAt(position) ?: return@setOnItemClickListener
            onPartitionToggled(partition, !selection.contains(partition.name))
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val partition = listAdapter.itemAt(position) ?: return@setOnItemLongClickListener false
            if (!isWorking.get()) openInspector(partition)
            true
        }

        slotToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentFilter = when (checkedId) {
                R.id.slotCurrent -> SlotFilter.CURRENT
                R.id.slotA -> SlotFilter.A
                R.id.slotB -> SlotFilter.B
                else -> SlotFilter.ALL
            }
            refreshVisible()
        }

        setupChips()

        if (StoragePermissionHelper.needsLegacyRuntimePermission()) {
            requestLegacyStoragePermission()
        } else if (!StoragePermissionHelper.hasFullAccess(this)) {
            StoragePermissionHelper.settingsIntent(this)?.let { startActivity(it) }
        }

        bootstrap()
    }

    override fun onDestroy() {
        if (isFinishing && isWorking.get()) {
            P2DLog.i("Activity finalizado durante operação: cancelando")
            coordinator?.cancel()
        }
        worker.shutdownNow()
        coordinator?.shutdown()
        inspectorDialog?.dismiss()
        inspectorDialog = null
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                bootstrap()
                true
            }
            R.id.action_device_info -> {
                showDeviceInfo()
                true
            }
            R.id.action_storage_access -> {
                promptStorageAccess()
                true
            }
            R.id.action_about -> {
                showAbout()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_STORAGE) return
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
        P2DLog.i("Permissão de armazenamento concedida: $granted")
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setView(R.layout.dialog_about)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun setupChips() {
        findViewById<View>(R.id.chipSelectAll).setOnClickListener { applyGroupFilter(null) }
        findViewById<View>(R.id.chipClear).setOnClickListener { clearSelection() }
        findViewById<View>(R.id.chipBoot).setOnClickListener {
            applyGroupFilter(PartitionGroup.BOOT)
        }
        findViewById<View>(R.id.chipAvb).setOnClickListener {
            applyGroupFilter(PartitionGroup.AVB)
        }
        findViewById<View>(R.id.chipDynamic).setOnClickListener {
            applyGroupFilter(PartitionGroup.DYNAMIC)
        }
        findViewById<View>(R.id.chipRecovery).setOnClickListener {
            applyGroupFilter(PartitionGroup.RECOVERY)
        }
    }

    private fun requestLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (!StoragePermissionHelper.needsLegacyRuntimePermission()) return
        try {
            requestPermissions(StoragePermissionHelper.legacyPermissions(), REQUEST_STORAGE)
        } catch (error: Exception) {
            P2DLog.w("Falha ao solicitar permissão de armazenamento", error)
        }
    }

    private fun bootstrap() {
        if (isWorking.get()) return
        setWorking(true)
        setStatus(getString(R.string.status_loading))
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        rootStatusText.setText(R.string.root_checking)
        pathsText.setText(R.string.paths_unknown)

        worker.execute {
            val root = rootManager.probe(R.string.error_root_unavailable)
            val reader = rootManager.reader()
            val loadedSnapshot = SystemSnapshotProvider.load(reader)
            val loadedScan = PartitionRepository(reader).scan(loadedSnapshot.props)

            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                rootInfo = root
                snapshot = loadedSnapshot
                scan = loadedScan
                allPartitions = loadedScan.all
                selection.clear()
                renderRootStatus(root)
                renderPaths(loadedScan)
                refreshVisible()
                setWorking(false)
                reportScanOutcome(loadedScan, root)
                prepareEngine(reader, root)
            }
        }
    }

    private fun prepareEngine(reader: com.spacexjr.part2dump.root.Shell, root: RootInfo) {
        if (!root.isAvailable) {
            engine = null
            coordinator = null
            return
        }
        val newEngine = DumpEngine(reader)
        engine = newEngine
        coordinator = DumpCoordinator(
            storage = storage,
            shell = reader,
            engine = newEngine,
            checksums = ChecksumCalculator(reader),
            manifestWriter = ManifestWriter(),
            toolVersion = appVersion()
        )
    }

    private fun reportScanOutcome(loadedScan: PartitionScan, root: RootInfo) {
        when {
            loadedScan.all.isEmpty() -> setStatus(
                loadedScan.byName.errorRes?.let { getString(it) } ?: getString(R.string.status_empty)
            )
            !root.isAvailable -> setStatus(getString(R.string.status_root_missing))
            else -> setStatus(
                resources.getQuantityString(R.plurals.status_loaded, loadedScan.all.size, loadedScan.all.size)
            )
        }
    }

    private fun renderRootStatus(root: RootInfo) {
        rootStatusText.text = buildString {
            append(getString(UserMessages.forRoot(root.status)))
            if (!root.binaryPath.isNullOrBlank()) {
                append("  ")
                append(getString(R.string.root_binary_line, root.binaryPath))
            }
        }
        rootStatusText.setTextColor(
            when (root.status) {
                RootStatus.AVAILABLE -> COLOR_OK
                RootStatus.DENIED -> COLOR_ERROR
                else -> COLOR_WARN
            }
        )
    }

    private fun renderPaths(loadedScan: PartitionScan) {
        val state = loadedScan.slotState
        pathsText.text = when {
            !loadedScan.byName.isFound -> getString(R.string.paths_unknown)
            state.isAbDevice && loadedScan.hasDynamicPartitions -> getString(
                R.string.paths_line_dynamic,
                loadedScan.byName.summary,
                state.currentLabel,
                loadedScan.dynamicPartitions.size.toString()
            )
            state.isAbDevice -> getString(
                R.string.paths_line_slot,
                loadedScan.byName.summary,
                state.currentLabel
            )
            loadedScan.hasDynamicPartitions -> getString(
                R.string.paths_line_dynamic,
                loadedScan.byName.summary,
                "-",
                loadedScan.dynamicPartitions.size.toString()
            )
            else -> getString(R.string.paths_line, loadedScan.byName.summary)
        }
        slotToggle.visibility = if (state.isAbDevice) View.VISIBLE else View.GONE
        if (state.isAbDevice) {
            if (slotToggle.checkedButtonId == View.NO_ID) {
                slotToggle.check(R.id.slotCurrent)
                currentFilter = SlotFilter.CURRENT
            }
        } else {
            currentFilter = SlotFilter.ALL
        }
    }

    private fun refreshVisible() {
        val state = scan?.slotState ?: return
        visiblePartitions = allPartitions.filter {
            SlotFilter.matches(currentFilter, it.slot, state.currentSlot, state.isAbDevice)
        }
        adapter?.submit(visiblePartitions, selection.toSet(), !isWorking.get())
        updateDumpButtonLabel()
        updateIdleStatus()
    }

    private fun updateDumpButtonLabel() {
        val active = coordinator
        dumpButton.text = if (active != null && active.isRunning) {
            getString(R.string.dump_cancel)
        } else {
            getString(R.string.dump) + " (" + selection.size + ")"
        }
    }

    private fun updateIdleStatus() {
        if (isWorking.get()) return
        if (selection.isEmpty()) {
            setStatus(getString(R.string.status_idle))
        } else {
            setStatus(resources.getQuantityString(R.plurals.status_selected, selection.size, selection.size))
        }
    }

    private fun onPartitionToggled(partition: Partition, checked: Boolean) {
        if (isWorking.get()) return
        if (checked) selection.add(partition.name) else selection.remove(partition.name)
        adapter?.updateSelection(selection.toSet())
        updateDumpButtonLabel()
        updateIdleStatus()
    }

    private fun clearSelection() {
        if (isWorking.get()) return
        selection.clear()
        adapter?.updateSelection(selection.toSet())
        updateDumpButtonLabel()
        updateIdleStatus()
    }

    private fun applyGroupFilter(group: PartitionGroup?) {
        if (isWorking.get()) return
        selection.clear()
        for (partition in visiblePartitions) {
            if (group == null || partition.group == group) selection.add(partition.name)
        }
        adapter?.updateSelection(selection.toSet())
        updateDumpButtonLabel()
        updateIdleStatus()
    }

    private fun onDumpButtonClicked() {
        val active = coordinator
        if (active != null && active.isRunning) {
            active.cancel()
            setStatus(getString(R.string.error_cancelled))
            return
        }
        if (isWorking.get()) return
        val selected = selectedPartitions()
        if (selected.isEmpty()) {
            setStatus(getString(R.string.error_no_selection))
            return
        }
        val root = rootInfo
        if (root == null || !root.isAvailable) {
            AlertDialog.Builder(this)
                .setTitle(UserMessages.forRoot(root?.status ?: RootStatus.UNAVAILABLE))
                .setMessage(root?.messageRes?.let { getString(it) } ?: getString(R.string.error_root_unavailable))
                .setPositiveButton(R.string.action_ok, null)
                .show()
            return
        }
        confirmAndStart(selected)
    }

    private fun selectedPartitions(): List<Partition> =
        visiblePartitions.filter { selection.contains(it.name) }

    private fun confirmAndStart(selected: List<Partition>) {
        val space = evaluateSpace(selected)
        val message = buildString {
            append(resources.getQuantityString(R.plurals.confirm_selection, selected.size, selected.size))
            append("\n")
            append(getString(R.string.confirm_required, Formats.humanBytes(space.requiredBytes)))
            append("\n")
            append(getString(R.string.confirm_available, Formats.humanBytes(space.availableBytes)))
            if (space.unknownSizes > 0) {
                append("\n")
                append(
                    resources.getQuantityString(
                        R.plurals.confirm_unknown_sizes,
                        space.unknownSizes,
                        space.unknownSizes
                    )
                )
            }
            if (!space.isEnough) {
                append("\n\n")
                append(getString(R.string.confirm_not_enough))
            }
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.confirm_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
        if (space.isEnough) {
            builder.setPositiveButton(R.string.dump) { _, _ -> startBackup(selected) }
        } else {
            builder.setPositiveButton(R.string.action_ok, null)
        }
        builder.show()
    }

    private fun evaluateSpace(partitions: List<Partition>): SpaceReport {
        val known = partitions.filter { it.hasKnownSize }
        val required = known.sumOf { it.sizeBytes } + SPACE_RESERVE_BYTES
        return StorageManager.evaluate(required, storage.freeBytes(), partitions.size - known.size)
    }

    private fun startBackup(partitions: List<Partition>) {
        val active = coordinator
        val deviceInfo = currentDeviceInfo()
        if (active == null || deviceInfo == null) {
            setStatus(getString(R.string.error_root_unavailable))
            return
        }
        lastBackupDirectory = null
        setWorking(true)
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        setStatus(getString(R.string.progress_preparing))

        val started = active.start(partitions, deviceInfo, backupListener(partitions.size))
        if (!started) {
            setWorking(false)
            setStatus(getString(R.string.error_internal))
        }
    }

    private fun backupListener(total: Int): BackupListener = object : BackupListener {
        override fun onState(state: BackupUiState) {
            mainHandler.post { renderProgress(state, total) }
        }

        override fun onFinished(result: BackupResult) {
            mainHandler.post { renderResult(result) }
        }
    }

    private fun renderProgress(state: BackupUiState, total: Int) {
        val partition = state.partition
        when (state.phase) {
            BackupPhase.PREPARING -> {
                progressBar.isIndeterminate = true
                setStatus(getString(R.string.progress_preparing))
            }
            BackupPhase.DUMPING -> {
                progressBar.isIndeterminate = state.indeterminate
                if (!state.indeterminate) progressBar.progress = state.percent
                val title = if (total > 1) {
                    getString(R.string.progress_dumping_of, state.index + 1, total, partition?.name.orEmpty())
                } else {
                    getString(R.string.progress_dumping, partition?.name.orEmpty())
                }
                setStatus(buildString {
                    append(title)
                    if (state.bytesTotal > 0) {
                        append("\n")
                        append(
                            getString(
                                R.string.progress_bytes,
                                Formats.humanBytes(state.bytesDone),
                                Formats.humanBytes(state.bytesTotal),
                                state.percent
                            )
                        )
                    }
                })
                PartitionInspector.setProgress(inspectorDialog, state.percent, state.indeterminate)
                PartitionInspector.setResult(
                    inspectorDialog,
                    partition?.let { getString(R.string.inspector_running, it.name, state.percent) }
                )
            }
            BackupPhase.HASHING -> {
                progressBar.isIndeterminate = true
                setStatus(getString(R.string.progress_hashing, partition?.name.orEmpty()))
                PartitionInspector.setProgress(inspectorDialog, null, true)
                PartitionInspector.setResult(
                    inspectorDialog,
                    partition?.let { getString(R.string.progress_hashing, it.name) }
                )
            }
            BackupPhase.MANIFEST -> {
                progressBar.isIndeterminate = true
                setStatus(getString(R.string.progress_manifest))
            }
            BackupPhase.DONE -> {
                progressBar.isIndeterminate = false
                progressBar.progress = 100
                setStatus(getString(R.string.progress_done))
            }
            BackupPhase.CANCELLED -> setStatus(getString(R.string.progress_cancelled))
            BackupPhase.FAILED -> setStatus(
                getString(R.string.progress_failed, getString(R.string.error_internal))
            )
        }
    }

    private fun renderResult(result: BackupResult) {
        setWorking(false)
        lastBackupDirectory = result.backupDir
        progressBar.isIndeterminate = false
        when {
            result.error == DumpError.NOT_ENOUGH_STORAGE -> {
                val space = result.space
                setStatus(buildString {
                    append(getString(R.string.error_not_enough_storage))
                    if (space != null) {
                        append("\n")
                        append(getString(R.string.confirm_required, Formats.humanBytes(space.requiredBytes)))
                        append("\n")
                        append(getString(R.string.confirm_available, Formats.humanBytes(space.availableBytes)))
                    }
                })
            }
            result.success -> {
                progressBar.progress = 100
                setStatus(getString(R.string.progress_done))
            }
            else -> {
                val message = getString(UserMessages.forError(result.error ?: DumpError.INTERNAL))
                setStatus(buildString {
                    append(getString(R.string.progress_failed, message))
                    val detail = result.detail?.takeIf { it.isNotBlank() }
                    if (detail != null) {
                        append("\n")
                        append(getString(R.string.error_technical_detail, detail.take(200)))
                    }
                })
                progressBar.progress = 0
            }
        }
        PartitionInspector.setProgress(inspectorDialog, null, false)
        if (inspectorDialog != null) {
            PartitionInspector.setResult(
                inspectorDialog,
                if (result.success) {
                    getString(R.string.inspector_done, result.backupDir?.name.orEmpty())
                } else {
                    getString(
                        R.string.inspector_failed,
                        getString(UserMessages.forError(result.error ?: DumpError.INTERNAL))
                    )
                }
            )
        }
        BackupResultDialog.show(this, result)
        refreshVisible()
    }

    private fun openInspector(partition: Partition) {
        inspectorDialog?.dismiss()
        val dialog = PartitionInspector.show(
            activity = this,
            partition = partition,
            onDump = { target ->
                lastBackupDirectory = null
                startBackup(listOf(target))
            },
            onSha256 = { target -> verifyHash(target) }
        )
        dialog.setOnDismissListener {
            if (inspectorDialog === dialog) inspectorDialog = null
        }
        inspectorDialog = dialog
        dialog.show()
    }

    private fun verifyHash(partition: Partition) {
        val directory = lastBackupDirectory
        val file = directory?.let { File(it, partition.imageFileName) }
        if (file == null || !file.exists()) {
            PartitionInspector.setResult(inspectorDialog, getString(R.string.inspector_hash_missing))
            return
        }
        val calculator = ChecksumCalculator(rootManager.rootShell())
        setWorking(true)
        progressBar.isIndeterminate = true
        setStatus(getString(R.string.progress_hashing, partition.name))
        worker.execute {
            val outcome = calculator.calculate(file) { false }
            mainHandler.post {
                setWorking(false)
                progressBar.isIndeterminate = false
                val text = if (!outcome.sha256.isNullOrBlank()) {
                    getString(R.string.label_sha256, Formats.shortHash(outcome.sha256.orEmpty()))
                } else {
                    getString(R.string.result_hash_unavailable)
                }
                PartitionInspector.setResult(inspectorDialog, text)
                PartitionInspector.setProgress(inspectorDialog, null, false)
                setStatus(text)
            }
        }
    }

    private fun promptStorageAccess() {
        if (StoragePermissionHelper.hasFullAccess(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.permission_title)
                .setMessage(R.string.permission_message)
                .setPositiveButton(R.string.action_ok, null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_title)
            .setMessage(R.string.permission_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_continue, null)
            .setPositiveButton(R.string.action_open_settings) { _, _ ->
                val intent = StoragePermissionHelper.settingsIntent(this)
                if (intent == null) {
                    setStatus(getString(R.string.permission_message))
                    return@setPositiveButton
                }
                try {
                    startActivity(intent)
                } catch (error: Exception) {
                    P2DLog.w("Não foi possível abrir as configurações de armazenamento", error)
                }
            }
            .show()
    }

    private fun showDeviceInfo() {
        val info = currentDeviceInfo()
        if (info == null) {
            bootstrap()
            return
        }
        DeviceInfoDialog.show(this, info)
    }

    private fun currentDeviceInfo(): DeviceInfo? {
        val loadedSnapshot = snapshot ?: return null
        val loadedScan = scan ?: return null
        val root = rootInfo ?: return null
        return DeviceInfo(
            snapshot = loadedSnapshot,
            slotState = loadedScan.slotState,
            rootStatus = root.status,
            rootBinary = root.binaryPath,
            byNamePath = loadedScan.byName.directory,
            hasDynamicPartitions = loadedScan.hasDynamicPartitions,
            superPartition = loadedScan.superPartition,
            dynamicPartitionCount = loadedScan.dynamicPartitions.size
        )
    }

    private fun setWorking(working: Boolean) {
        isWorking.set(working)
        setUiEnabled(!working)
        updateDumpButtonLabel()
    }

    private fun setUiEnabled(enabled: Boolean) {
        dumpButton.isEnabled = true
        listView.isEnabled = enabled
        slotToggle.isEnabled = enabled
        for (id in CHIP_IDS) findViewById<View>(id).isEnabled = enabled
        adapter?.submit(visiblePartitions, selection.toSet(), enabled)
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    private fun appVersion(): String {
        return try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (error: Exception) {
            "1.0"
        }
    }

    private companion object {
        const val REQUEST_STORAGE = 4711
        const val SPACE_RESERVE_BYTES = 8L * 1024L * 1024L
        val COLOR_OK = 0xFF2E7D32.toInt()
        val COLOR_WARN = 0xFFEF6C00.toInt()
        val COLOR_ERROR = 0xFFC62828.toInt()
        val CHIP_IDS = intArrayOf(
            R.id.chipSelectAll,
            R.id.chipClear,
            R.id.chipBoot,
            R.id.chipAvb,
            R.id.chipDynamic,
            R.id.chipRecovery
        )
    }
}