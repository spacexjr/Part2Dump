package com.spacexjr.part2dump.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemSnapshotProviderTest {

    @Test
    fun parsePropsReadsGetpropOutput() {
        val text = """
            [ro.boot.slot_suffix]: [_a]
            [ro.build.version.release]: [14]
            [ro.product.model]: [Pixel 7 Pro]
        """.trimIndent()
        val props = SystemSnapshotProvider.parseProps(text)
        assertEquals(3, props.size)
        assertEquals("_a", props["ro.boot.slot_suffix"])
        assertEquals("14", props["ro.build.version.release"])
        assertEquals("Pixel 7 Pro", props["ro.product.model"])
    }

    @Test
    fun parsePropsKeepsEmptyValues() {
        val props = SystemSnapshotProvider.parseProps("[ro.boot.slot_suffix]: []")
        assertEquals("", props["ro.boot.slot_suffix"])
    }

    @Test
    fun parsePropsKeepsValuesContainingBrackets() {
        val props = SystemSnapshotProvider.parseProps("[ro.build.tags]: [release-keys, test-keys]")
        assertEquals("release-keys, test-keys", props["ro.build.tags"])
    }

    @Test
    fun parsePropsSkipsNoiseLines() {
        val text = """
            getprop: not found
            [ro.product.model]: [Pixel 7]

            [malformed line
            [ro.build.id]: [TQ3A.230901.001]
        """.trimIndent()
        val props = SystemSnapshotProvider.parseProps(text)
        assertEquals(2, props.size)
        assertEquals("Pixel 7", props["ro.product.model"])
        assertEquals("TQ3A.230901.001", props["ro.build.id"])
    }

    @Test
    fun parsePropsOnEmptyInputReturnsEmptyMap() {
        assertTrue(SystemSnapshotProvider.parseProps("").isEmpty())
    }

    @Test
    fun snapshotFallsBackWhenModelAndDeviceAreMissing() {
        val snapshot = snapshot(model = null, device = null)
        assertEquals("Android", snapshot.deviceModel)
        assertEquals("-", snapshot.deviceCodename)
    }

    @Test
    fun snapshotPrefersModelOverDevice() {
        val snapshot = snapshot(model = "Pixel 7 Pro", device = "panther")
        assertEquals("Pixel 7 Pro", snapshot.deviceModel)
        assertEquals("panther", snapshot.deviceCodename)
    }

    @Test
    fun snapshotIgnoresBlankStrings() {
        val snapshot = snapshot(model = "  ", device = "panther")
        assertEquals("panther", snapshot.deviceModel)
    }

    @Test
    fun snapshotAbiFallsBackToQuestionMark() {
        assertEquals("?", snapshot(abis = emptyList()).abi)
        assertEquals("arm64-v8a", snapshot(abis = listOf("arm64-v8a", "armeabi-v7a")).abi)
    }

    private fun snapshot(
        model: String? = "Pixel 7",
        device: String? = "panther",
        abis: List<String> = listOf("arm64-v8a")
    ) = SystemSnapshot(
        manufacturer = "Google",
        brand = "google",
        model = model,
        device = device,
        product = "panther",
        board = "husky",
        hardware = "husky",
        androidRelease = "14",
        securityPatch = "2024-01-01",
        sdkInt = 34,
        buildId = "UQ1A.240105.004",
        fingerprint = "google/panther/panther:14/UQ1A.240105.004:user/release-keys",
        abis = abis,
        kernelVersion = "5.15.148-android13",
        bootloader = "husky-1.0-12345678",
        props = mapOf("ro.boot.slot_suffix" to "_a")
    )
}

class DeviceInfoTest {

    @Test
    fun deviceInfoExposesSlotAndSuperLabels() {
        val info = deviceInfo()
        assertEquals("A", info.slotLabel)
        assertEquals("super", info.superName)
    }

    @Test
    fun deviceInfoHandlesMissingSuperPartition() {
        assertEquals("-", deviceInfo().copy(superPartition = null).superName)
    }

    @Test
    fun deviceInfoHandlesBlankSuperPartition() {
        assertEquals("-", deviceInfo().copy(superPartition = "  ").superName)
    }

    @Test
    fun deviceInfoFallsBackForAndroidRelease() {
        assertEquals("-", deviceInfo().copy(snapshot = snapshotWithRelease(null)).androidRelease)
        assertEquals("-", deviceInfo().copy(snapshot = snapshotWithRelease("")).androidRelease)
    }

    private fun snapshotWithRelease(release: String?) = SystemSnapshot(
        manufacturer = "Google",
        brand = "google",
        model = "Pixel 7",
        device = "panther",
        product = "panther",
        board = "husky",
        hardware = "husky",
        androidRelease = release,
        securityPatch = null,
        sdkInt = 34,
        buildId = null,
        fingerprint = null,
        abis = listOf("arm64-v8a"),
        kernelVersion = null,
        bootloader = null,
        props = emptyMap()
    )

    private fun deviceInfo() = DeviceInfo(
        snapshot = snapshotWithRelease("14"),
        slotState = SlotState(
            isAbDevice = true,
            currentSlot = Slot.A,
            suffix = "_a",
            slotIndex = 0
        ),
        rootStatus = com.spacexjr.part2dump.root.RootStatus.AVAILABLE,
        rootBinary = "su",
        byNamePath = "/dev/block/by-name",
        hasDynamicPartitions = true,
        superPartition = "super",
        dynamicPartitionCount = 4
    )
}
