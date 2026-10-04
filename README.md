# Part2Dump

<p align="center">
  <img src="https://img.shields.io/badge/Android-Partition%20Tool-9C27B0?style=for-the-badge&logo=android&logoColor=white" alt="Android">
  <img src="https://img.shields.io/badge/Kotlin-Android-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Root-Required-red?style=for-the-badge" alt="Root Required">
</p>

<p align="center">
  <b>A simple Android partition dumping utility for rooted devices.</b>
</p>

---

## About

**Part2Dump** is an Android application written in **Kotlin** designed to make backing up raw device partitions easier on rooted Android devices.

Instead of manually finding block devices and running `dd` commands from a terminal, Part2Dump provides a simple interface to:

* Discover partitions from `/dev/block/by-name`
* Select one or multiple partitions
* Dump selected partitions using `dd`
* Execute privileged operations through `su`
* Monitor dump progress
* Store generated images in `/sdcard/Backup/`

The project is intended primarily for **Android development, device research, debugging, recovery development and partition analysis**.

## Features

### Partition Discovery

Part2Dump scans:

```text
/dev/block/by-name
```

and displays the available partitions exposed by the device.

### Multiple Selection

Select multiple partitions before starting the dump operation.

### Root Dumping

Partition images are created using:

```bash
dd
```

through a root shell obtained with:

```bash
su
```

### Progress

When supported by the device's `dd` implementation, Part2Dump uses:

```bash
status=progress
```

to provide live progress information.

### Backup Location

Dumped images are stored in:

```text
/sdcard/Backup/
```

## Requirements

* Android device
* Root access
* Working `su` binary
* `dd` available on the device
* Sufficient storage space
* Android Studio for development/building

> Root access is mandatory because raw block devices normally cannot be accessed by regular Android applications.

## Usage

### 1. Refresh partitions

Open Part2Dump and use the **Refresh** action from the toolbar.

The application will scan:

```text
/dev/block/by-name
```

and populate the partition list.

### 2. Select partitions

Select one or more partitions that you want to dump.

Examples may include:

```text
boot
vendor_boot
dtbo
vbmeta
recovery
super
userdata
```

The available partitions depend entirely on the device.

### 3. Start the dump

Tap **Dump Selected**.

Part2Dump will request root privileges and execute the required `dd` commands.

### 4. Find the images

The resulting partition images are saved under:

```text
/sdcard/Backup/
```

Example:

```text
/sdcard/Backup/
├── boot.img
├── vendor_boot.img
├── dtbo.img
└── vbmeta.img
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

Connect a rooted Android device with USB debugging enabled and run the application from Android Studio.

## Architecture

The basic workflow is:

```text
Android UI
    │
    ▼
Partition Scanner
    │
    ▼
/dev/block/by-name
    │
    ▼
Partition Selection
    │
    ▼
Root Shell (su)
    │
    ▼
dd
    │
    ▼
/sdcard/Backup/
```

## Why Part2Dump?

Working directly with Android partitions can require commands such as:

```bash
su
ls -l /dev/block/by-name
dd if=/dev/block/by-name/boot of=/sdcard/Backup/boot.img
```

Part2Dump wraps this workflow in an Android interface, making repetitive partition backups easier without manually typing every command.

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

Reading partitions is generally non-destructive, but raw block-device operations can be dangerous if commands are modified or used incorrectly.

Part2Dump should be used for **dumping/backing up** partitions, not writing arbitrary data back to block devices.

Always verify the target partition and make sure you have enough storage available.

Partition dumps may also contain sensitive information.

## Project Status

Part2Dump is an experimental/open-source Android utility focused on rooted devices and low-level Android partition tooling.

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
