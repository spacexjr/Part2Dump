package com.spacexjr.part2dump.checksum

import com.spacexjr.part2dump.device.DeviceInfo
import com.spacexjr.part2dump.device.Slot
import com.spacexjr.part2dump.device.SlotState
import com.spacexjr.part2dump.device.SystemSnapshot
import com.spacexjr.part2dump.root.RootStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestWriterTest {

    private val writer = ManifestWriter()

    @Test
    fun buildWritesToolAndDeviceMetadata() {
        val json = JSONObject(writer.build(listOf(entry()), deviceInfo(), BACKUP, "1.0", TIMESTAMP))
        assertEquals("Part2Dump", json.getString("tool"))
        assertEquals("1.0", json.getString("tool_version"))
        assertEquals(TIMESTAMP, json.getString("timestamp"))
        assertEquals(BACKUP, json.getString("backup_folder"))
        assertEquals("Pixel 7 Pro", json.getString("device"))
        assertEquals("panther", json.getString("device_codename"))
        assertEquals("Google", json.getString("manufacturer"))
        assertEquals("14", json.getString("android"))
        assertEquals(34, json.getInt("sdk"))
        assertEquals("TQ3A.240105.004", json.getString("build_id"))
        assertEquals("arm64-v8a", json.getString("abi"))
        assertEquals("_a", json.getString("slot"))
        assertTrue(json.getBoolean("ab_device"))
        assertTrue(json.getBoolean("dynamic_partitions"))
        assertEquals("super", json.getString("super_partition"))
        assertEquals("/dev/block/by-name", json.getString("block_device_dir"))
        assertEquals("AVAILABLE", json.getString("root_status"))
        assertEquals("su", json.getString("root_binary"))
    }

    @Test
    fun buildUsesJsonNullForAbsentValues() {
        val json = JSONObject(
            writer.build(
                emptyList(),
                deviceInfo().copy(rootBinary = null, superPartition = null, byNamePath = null),
                BACKUP,
                "1.0",
                TIMESTAMP
            )
        )
        assertTrue(json.isNull("root_binary"))
        assertTrue(json.isNull("super_partition"))
        assertTrue(json.isNull("block_device_dir"))
    }

    @Test
    fun buildNormalisesMissingSlotToNone() {
        val json = JSONObject(
            writer.build(
                emptyList(),
                deviceInfo().copy(slotState = SlotState(false, Slot.NONE, null, null)),
                BACKUP,
                "1.0",
                TIMESTAMP
            )
        )
        assertEquals("none", json.getString("slot"))
        assertFalse(json.getBoolean("ab_device"))
    }

    @Test
    fun buildDescribesEachPartition() {
        val json = JSONObject(
            writer.build(
                listOf(
                    entry(name = "boot_a", sizeBytes = 64L * 1024 * 1024, sha256 = "abc123"),
                    entry(name = "vbmeta_b", sizeBytes = -1L, status = ManifestEntry.STATUS_FAILED, error = "dd: denied\n")
                ),
                deviceInfo(),
                BACKUP,
                "1.0",
                TIMESTAMP
            )
        )
        val partitions = json.getJSONArray("partitions")
        assertEquals(2, partitions.length())

        val ok = partitions.getJSONObject(0)
        assertEquals("boot_a", ok.getString("name"))
        assertEquals("/dev/block/by-name/boot_a", ok.getString("path"))
        assertEquals("/dev/block/sde1", ok.getString("device"))
        assertEquals(64L * 1024 * 1024, ok.getLong("size"))
        assertEquals("A", ok.getString("slot"))
        assertEquals("Boot", ok.getString("group"))
        assertEquals(1234L, ok.getLong("duration_ms"))
        assertEquals(ManifestEntry.STATUS_OK, ok.getString("status"))
        assertEquals("abc123", ok.getString("sha256"))
        assertFalse(ok.has("notice"))

        val failed = partitions.getJSONObject(1)
        assertEquals(ManifestEntry.STATUS_FAILED, failed.getString("status"))
        assertEquals("dd: denied\n", failed.getString("notice"))
        assertFalse(failed.has("sha256"))
    }

    @Test
    fun buildAggregatesTotals() {
        val json = JSONObject(
            writer.build(
                listOf(
                    entry(name = "boot_a", sizeBytes = 100L),
                    entry(name = "system", sizeBytes = 900L),
                    entry(name = "vbmeta", sizeBytes = 50L, status = ManifestEntry.STATUS_FAILED, error = "boom")
                ),
                deviceInfo(),
                BACKUP,
                "1.0",
                TIMESTAMP
            )
        )
        val totals = json.getJSONObject("totals")
        assertEquals(3, totals.getInt("requested"))
        assertEquals(2, totals.getInt("completed"))
        assertEquals(1, totals.getInt("failed"))
        assertEquals(1000L, totals.getLong("bytes"))
    }

    @Test
    fun buildIgnoresNegativeSizesInTotals() {
        val json = JSONObject(
            writer.build(
                listOf(
                    entry(name = "boot_a", sizeBytes = 100L),
                    entry(name = "system", sizeBytes = -1L)
                ),
                deviceInfo(),
                BACKUP,
                "1.0",
                TIMESTAMP
            )
        )
        assertEquals(100L, json.getJSONObject("totals").getLong("bytes"))
    }

    @Test
    fun buildSkipsBlankHashAndError() {
        val json = JSONObject(
            writer.build(
                listOf(entry(sha256 = "  ", error = "")),
                deviceInfo(),
                BACKUP,
                "1.0",
                TIMESTAMP
            )
        )
        val item = json.getJSONArray("partitions").getJSONObject(0)
        assertFalse(item.has("sha256"))
        assertFalse(item.has("notice"))
    }

    @Test
    fun buildProducesPrettyJson() {
        val text = writer.build(listOf(entry()), deviceInfo(), BACKUP, "1.0", TIMESTAMP)
        assertTrue(text.contains("\n"))
        assertTrue(text.trim().startsWith("{"))
        assertTrue(text.trim().endsWith("}"))
    }

    private fun entry(
        name: String = "boot_a",
        sizeBytes: Long = 1024L,
        sha256: String? = null,
        status: String = ManifestEntry.STATUS_OK,
        error: String? = null
    ) = ManifestEntry(
        name = name,
        linkPath = "/dev/block/by-name/$name",
        devicePath = "/dev/block/sde1",
        sizeBytes = sizeBytes,
        slot = "A",
        group = "Boot",
        sha256 = sha256,
        durationMs = 1234L,
        status = status,
        error = error
    )

    private fun deviceInfo() = DeviceInfo(
        snapshot = SystemSnapshot(
            manufacturer = "Google",
            brand = "google",
            model = "Pixel 7 Pro",
            device = "panther",
            product = "panther",
            board = "husky",
            hardware = "husky",
            androidRelease = "14",
            securityPatch = "2024-01-01",
            sdkInt = 34,
            buildId = "TQ3A.240105.004",
            fingerprint = "google/panther/panther:14/UQ1A.240105.004:user/release-keys",
            abis = listOf("arm64-v8a", "armeabi-v7a"),
            kernelVersion = "5.15.148-android13",
            bootloader = "husky-1.0",
            props = mapOf("ro.boot.slot_suffix" to "_a")
        ),
        slotState = SlotState(
            isAbDevice = true,
            currentSlot = Slot.A,
            suffix = "_a",
            slotIndex = 0
        ),
        rootStatus = RootStatus.AVAILABLE,
        rootBinary = "su",
        byNamePath = "/dev/block/by-name",
        hasDynamicPartitions = true,
        superPartition = "super",
        dynamicPartitionCount = 4
    )

    companion object {
        private const val BACKUP = "/sdcard/Part2Dump/Pixel_7_Pro_20231114-221320"
        private const val TIMESTAMP = "2023-11-14T22:13:20Z"
    }
}
