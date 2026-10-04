# Part2Dump

<p align="center">
  <img src="https://img.shields.io/badge/Android-Partition%20Tool-9C27B0?style=for-the-badge&logo=android&logoColor=white" alt="Android">
  <img src="https://img.shields.io/badge/Kotlin-Android-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Root-Required-red?style=for-the-badge" alt="Root Required">
</p>

<p align="center">
  <b>A read-only Android partition dumping utility for rooted devices.</b>
</p>

---

## About

**Part2Dump** is an Android application written in **Kotlin** that makes backing up raw
device partitions easier on rooted Android devices.

Instead of manually finding block devices and running `dd` commands from a terminal,
Part2Dump provides an interface to:

* Discover partitions across every common `by-name` location
* Detect A/B (slot) layouts and filter by active slot
* Detect Dynamic Partitions (`system`, `vendor`, `product`, `odm`, …) on `super` devices
* Select one or multiple partitions
* Dump selected partitions using `dd`
* Verify the resulting image size against the block device
* Calculate a SHA-256 checksum for each image
* Write a `manifest.json` describing the whole operation
* Store generated images in `/sdcard/Part2Dump/`

The project is intended primarily for **Android development, device research, debugging,
recovery development and partition analysis**.

## Features

### Partition Discovery

Part2Dump probes the following directories, in order, and uses the first one that exists:

```text
/dev/block/by-name
/dev/block/bootdevice/by-name
/dev/block/platform/*/by-name
```

Each entry is resolved with `readlink -f`, so the app always works with the real block
device (`/dev/block/sde12`) rather than the symlink.

### A/B and Slot Awareness

On devices with an A/B (seamless) update layout the app reads:

```text
ro.boot.slot_suffix
ro.boot.slot
ro.virtual_ab.enabled
```

The active slot is shown in the header, and partitions can be filtered by the active slot,
slot A, slot B, or all slots. Partitions without a slot suffix (`super`, `vbmeta`, …) always
remain visible because they are shared between slots.

### Dynamic Partitions

On devices using `super`, the logical dynamic partitions are resolved by mounting
`lpdump` metadata and matching mount points to their backing devices:

```text
/dev/block/dm-0, /dev/block/dm-1, …
```

These are listed in a separate group, since they are logical volumes carved out of
`super` rather than physical partitions.

### Multiple Selection

Select multiple partitions with the checkboxes, or use the quick group chips
(Boot, AVB, Dynamic, Recovery) to select a whole family at once.

### Root Dumping

Partition images are created using:

```bash
dd
```

through a root shell obtained with:

```bash
su
```

The app probes a list of known `su` locations and reports whether root is
**available**, **denied**, **unavailable** or **failed**.

Reading partitions never requires writing to a block device: Part2Dump only ever runs
`dd if=… of=<file>`.

### Progress and Integrity

When the device's `dd` supports it, Part2Dump uses:

```bash
status=progress
```

to provide live progress information, and falls back to a simpler invocation otherwise.

After each dump the size of the generated image is compared to the size of the source
block device, and a mismatch is reported instead of being silently accepted.

### Checksums

A SHA-256 checksum is calculated for every generated image:

* in the app, when the image is readable by the app;
* through root (`sha256sum`, `toybox sha256sum` or `busybox sha256sum`) otherwise.

### Backup Location

Dumped images are stored in:

```text
/sdcard/Part2Dump/<DEVICE>_<DATE_TIME>/
```

for example:

```text
/sdcard/Part2Dump/Pixel_7_Pro_20231114-221320/
├── boot_a.img
├── vendor_boot_a.img
├── dtbo_a.img
├── vbmeta_a.img
└── manifest.json
```

An existing folder is never overwritten: if the target name already exists, a numeric
suffix is appended.

> Images written by the previous versions of the app remain in `/sdcard/Backup/` and are
> never touched.

### manifest.json

Every backup folder contains a `manifest.json` describing the operation:

```json
{
  "tool": "Part2Dump",
  "tool_version": "1.0",
  "timestamp": "2023-11-14T22:13:20Z",
  "backup_folder": "/sdcard/Part2Dump/Pixel_7_Pro_20231114-221320",
  "device": "Pixel 7 Pro",
  "device_codename": "panther",
  "android": "14",
  "sdk": 34,
  "slot": "_a",
  "ab_device": true,
  "dynamic_partitions": true,
  "super_partition": "super",
  "block_device_dir": "/dev/block/by-name",
  "root_status": "AVAILABLE",
  "partitions": [
    {
      "name": "boot_a",
      "path": "/dev/block/by-name/boot_a",
      "device": "/dev/block/sde12",
      "size": 67108864,
      "slot": "A",
      "group": "Boot",
      "duration_ms": 2134,
      "status": "ok",
      "sha256": "…"
    }
  ],
  "totals": {
    "requested": 1,
    "completed": 1,
    "failed": 0,
    "bytes": 67108864
  }
}
```

## Requirements

* Android device (Android 5.0 / API 21 or newer)
* Root access
* Working `su` binary
* `dd` available on the device
* Sufficient storage space
* Android Studio for development/building

> Root access is mandatory because raw block devices normally cannot be accessed by regular
> Android applications.

> On Android 11+ the app asks for "all files access" so it can read the images back to
> compute the checksum. The dump itself is still performed by root, so it works even if
> that permission is denied — only the app-side checksum is skipped.

## Usage

### 1. Refresh partitions

Open Part2Dump and use the **Refresh** action from the toolbar. The application scans the
`by-name` directories, resolves the block devices and populates the partition list.

### 2. Select partitions

Select one or more partitions that you want to dump.

Examples may include:

```text
boot_a
vendor_boot_a
dtbo_a
vbmeta_a
recovery_a
super
system
vendor
userdata
```

The available partitions depend entirely on the device.

### 3. Start the dump

Tap **Dump Selected**. The app shows a confirmation dialog with the required and the
available space, and refuses to start when there is not enough room.

### 4. Find the images

The resulting partition images are saved under:

```text
/sdcard/Part2Dump/<DEVICE>_<DATE_TIME>/
```

## Building

Clone the repository:

```bash
git clone https://github.com/spacexjr/part2dump.git
cd part2dump
```

Then open the project with **Android Studio** and allow Gradle to synchronize.

Alternatively, if your environment is configured for Gradle builds:

```bash
./gradlew build
```

The unit tests cover the hardware independent logic (formatting, slot detection, partition
classification, property parsing, shell quoting and the manifest):

```bash
./gradlew test
```

Connect a rooted Android device with USB debugging enabled and run the application from
Android Studio.

## Architecture

```text
Android UI (MainActivity)
    │
    ▼
RootManager ──► Shell (su / sh)
    │
    ├──► SystemSnapshotProvider   device properties, slot props, kernel
    ├──► PartitionRepository      by-name discovery + DynamicPartitionDetector
    │            │
    │            ▼
    │        DeviceProbe         size, block device, readability
    │
    ├──► DumpCoordinator ──► DumpEngine (dd) ──► image file
    │            │
    │            ├──► ChecksumCalculator (app-side or root-side SHA-256)
    │            └──► ManifestWriter (manifest.json)
    │
    └──► StorageManager           target folder, free space, permissions
```

## Why Part2Dump?

Working directly with Android partitions can require commands such as:

```bash
su
ls -l /dev/block/by-name
dd if=/dev/block/by-name/boot_a of=/sdcard/Part2Dump/boot_a.img
```

Part2Dump wraps this workflow in an Android interface, making repetitive partition backups
easier without manually typing every command.

## Use Cases

Part2Dump can be useful for:

* Android development
* Custom ROM development
* Custom recovery development
* Kernel development
* Boot image research
* Partition analysis
* Device bring-up
* Firmware research
* Creating partition backups
* Reverse engineering Android devices

## Safety

**Use this application carefully.**

Part2Dump is **read-only with respect to block devices**: it only reads partitions and
writes plain files to shared storage. It does not contain, and will not accept, any
command that writes to a partition, erases a device or flashes firmware.

Dumped images can be large: dumping `super`, `userdata` or `system` may require several
gigabytes of free space, and the confirmation dialog only knows the exact size for
partitions it could measure.

Partition dumps may also contain sensitive information.

## Project Status

Part2Dump is an experimental/open-source Android utility focused on rooted devices and
low-level Android partition tooling.

Features and compatibility may vary between Android versions and devices.

## Credits

Developed by **Space / spacexjr**.

Built with:

* Kotlin
* Android
* Gradle
* `su`
* `dd`

## License

See the repository for the current license information.

---

<p align="center">
  <b>Android • Root • Partitions • Open Source</b>
</p>
