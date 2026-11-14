package com.example.gemini.data.agent

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AgentArchiveImporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Builds a minimal valid ELF64 little-endian binary; optionally sets `e_machine` and attaches a PT_INTERP program header. */
    private fun makeFakeElf64(
        interpPath: String? = null,
        extraPadding: Int = 0,
        eMachine: Int = AgentArchiveImporter.EM_AARCH64
    ): ByteArray {
        val ehdrSize = 64
        val phdrSize = 56
        val phNum = if (interpPath != null) 1 else 0
        val phOff = if (phNum > 0) ehdrSize.toLong() else 0L
        val interpOff = ehdrSize + phNum * phdrSize
        val interpBytes = interpPath?.let { (it + "\u0000").toByteArray(Charsets.US_ASCII) } ?: ByteArray(0)
        val total = interpOff + interpBytes.size + extraPadding

        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        // e_ident: \x7FELF, ELFCLASS64(2), ELFDATA2LSB(1), EV_CURRENT(1)
        buf.put(0, 0x7F.toByte())
        buf.put(1, 'E'.code.toByte())
        buf.put(2, 'L'.code.toByte())
        buf.put(3, 'F'.code.toByte())
        buf.put(4, 2.toByte())
        buf.put(5, 1.toByte())
        buf.put(6, 1.toByte())
        buf.putShort(18, eMachine.toShort())
        buf.putLong(32, phOff)
        buf.putShort(54, phdrSize.toShort())
        buf.putShort(56, phNum.toShort())

        if (interpPath != null) {
            buf.putInt(ehdrSize + 0, 3) // PT_INTERP
            buf.putLong(ehdrSize + 8, interpOff.toLong()) // p_offset
            buf.putLong(ehdrSize + 32, interpBytes.size.toLong()) // p_filesz
            buf.position(interpOff)
            buf.put(interpBytes)
        }
        return buf.array()
    }

    @Test
    fun detectFormatRecognizesAllSupportedContainersAndBinaries() {
        val elf = tempFolder.newFile("claude").apply { writeBytes(makeFakeElf64()) }
        assertEquals(AgentArchiveImporter.ArchiveFormat.RAW_ELF, AgentArchiveImporter.detectArchiveFormat(elf))

        val script = tempFolder.newFile("launcher.sh").apply { writeText("#!/bin/bash\necho hi\n") }
        assertEquals(AgentArchiveImporter.ArchiveFormat.SCRIPT, AgentArchiveImporter.detectArchiveFormat(script))

        val zipFile = tempFolder.newFile("pkg.zip").apply {
            ZipOutputStream(FileOutputStream(this)).use { zos ->
                zos.putNextEntry(ZipEntry("bin/codex"))
                zos.write(makeFakeElf64())
                zos.closeEntry()
            }
        }
        assertEquals(AgentArchiveImporter.ArchiveFormat.ZIP, AgentArchiveImporter.detectArchiveFormat(zipFile))

        val tgzFile = tempFolder.newFile("pkg.tgz").apply {
            GzipCompressorOutputStream(FileOutputStream(this)).use { gzos ->
                TarArchiveOutputStream(gzos).use { tos ->
                    val data = makeFakeElf64()
                    val entry = TarArchiveEntry("package/bin/opencode").apply { size = data.size.toLong() }
                    tos.putArchiveEntry(entry)
                    tos.write(data)
                    tos.closeArchiveEntry()
                }
            }
        }
        assertEquals(AgentArchiveImporter.ArchiveFormat.GZIP, AgentArchiveImporter.detectArchiveFormat(tgzFile))

        val txzFile = tempFolder.newFile("pkg.tar.xz").apply {
            XZCompressorOutputStream(FileOutputStream(this)).use { xzos ->
                TarArchiveOutputStream(xzos).use { tos ->
                    val data = makeFakeElf64()
                    val entry = TarArchiveEntry("bin/agy").apply { size = data.size.toLong() }
                    tos.putArchiveEntry(entry)
                    tos.write(data)
                    tos.closeArchiveEntry()
                }
            }
        }
        assertEquals(AgentArchiveImporter.ArchiveFormat.XZ, AgentArchiveImporter.detectArchiveFormat(txzFile))
    }

    @Test(expected = SecurityException::class)
    fun validatePathRejectsSiblingPrefixTraversal() {
        val root = tempFolder.newFolder("dest")
        val siblingEvil = File(root.parentFile, "dest_evil/pwned")
        AgentArchiveImporter.validatePathWithinDest(root, siblingEvil)
    }

    @Test(expected = SecurityException::class)
    fun extractZipRejectsZipSlipTraversal() {
        val badZip = tempFolder.newFile("evil.zip")
        ZipOutputStream(FileOutputStream(badZip)).use { zos ->
            zos.putNextEntry(ZipEntry("../../outside.sh"))
            zos.write("echo pwn".toByteArray())
            zos.closeEntry()
        }
        val dest = tempFolder.newFolder("safe_dest")
        AgentArchiveImporter.extractArchive(badZip, dest, "evil.zip")
    }

    @Test
    fun isGlibcDynamicElfDistinguishesGlibcStaticMuslAndBionic() {
        val glibcElf = tempFolder.newFile("claude-glibc").apply {
            writeBytes(makeFakeElf64("/lib/ld-linux-aarch64.so.1"))
        }
        val muslElf = tempFolder.newFile("codex-musl").apply {
            writeBytes(makeFakeElf64("/lib/ld-musl-aarch64.so.1"))
        }
        val staticElf = tempFolder.newFile("codex-static").apply {
            writeBytes(makeFakeElf64(null))
        }
        val androidBionic = tempFolder.newFile("agy-bionic").apply {
            writeBytes(makeFakeElf64("/system/bin/linker64"))
        }

        assertTrue(AgentArchiveImporter.isGlibcDynamicElf(glibcElf))
        assertFalse(AgentArchiveImporter.isGlibcDynamicElf(muslElf))
        assertFalse(AgentArchiveImporter.isGlibcDynamicElf(staticElf))
        assertFalse(AgentArchiveImporter.isGlibcDynamicElf(androidBionic))

        assertTrue(AgentArchiveImporter.isAndroidBionicElf(androidBionic))
        assertFalse(AgentArchiveImporter.isAndroidBionicElf(glibcElf))
        assertFalse(AgentArchiveImporter.isAndroidBionicElf(staticElf))
    }

    @Test
    fun checkElfArchCompatibilityDetectsArm64VsX8664() {
        val arm64Elf = tempFolder.newFile("agy-arm64").apply {
            writeBytes(makeFakeElf64("/lib/ld-linux-aarch64.so.1", eMachine = AgentArchiveImporter.EM_AARCH64))
        }
        val x64Elf = tempFolder.newFile("agy-x64").apply {
            writeBytes(makeFakeElf64("/lib64/ld-linux-x86-64.so.2", eMachine = AgentArchiveImporter.EM_X86_64))
        }

        assertEquals(AgentArchiveImporter.EM_AARCH64, AgentArchiveImporter.readElfMachine(arm64Elf))
        assertEquals(AgentArchiveImporter.EM_X86_64, AgentArchiveImporter.readElfMachine(x64Elf))
        assertEquals(null, AgentArchiveImporter.checkElfArchCompatibility(arm64Elf, "aarch64"))
        val x64Err = AgentArchiveImporter.checkElfArchCompatibility(x64Elf, "aarch64")
        assertNotNull(x64Err)
        assertTrue(x64Err!!.contains("x86_64"))
    }

    @Test
    fun isDesktopElectronBundleDetectsElectronAppsOnly() {
        val desktopDir = tempFolder.newFolder("Antigravity-arm64").apply {
            File(this, "libffmpeg.so").writeText("so")
            File(this, "resources.pak").writeText("pak")
            File(this, "antigravity").writeBytes(makeFakeElf64())
        }
        assertTrue(AgentArchiveImporter.isDesktopElectronBundle(desktopDir))

        val cliDir = tempFolder.newFolder("cli_arm64").apply {
            File(this, "antigravity").writeBytes(makeFakeElf64("/system/bin/linker64"))
        }
        assertFalse(AgentArchiveImporter.isDesktopElectronBundle(cliDir))
    }

    @Test
    fun extractSingleXzCompressedElfAndFindPreferredCandidate() {
        val xzBinary = tempFolder.newFile("codex-aarch64-unknown-linux-musl.xz")
        val fakeBinary = makeFakeElf64(null, extraPadding = 256 * 1024)
        XZCompressorOutputStream(FileOutputStream(xzBinary)).use { xzos ->
            xzos.write(fakeBinary)
        }

        val dest = tempFolder.newFolder("xz_out")
        val extracted = AgentArchiveImporter.extractArchive(xzBinary, dest, "codex-aarch64-unknown-linux-musl.xz")
        assertEquals(1, extracted.fileCount)

        val candidate = AgentArchiveImporter.findBinaryCandidate(dest, listOf("codex", "codex-aarch64-unknown-linux-musl"))
        assertNotNull(candidate)
        assertEquals("codex-aarch64-unknown-linux-musl", candidate!!.name)
        assertEquals(fakeBinary.size.toLong(), candidate.length())
    }

    @Test
    fun extractTarGzPrefersShallowestPackageJsonOverNestedDependencies() {
        val tgz = tempFolder.newFile("agy-cli.tgz")
        val fakeElf = makeFakeElf64("/lib/ld-linux-aarch64.so.1", extraPadding = 200 * 1024)
        val nestedDepJson = """{"name":"old-dep","version":"1.9.0"}""".toByteArray()
        val rootPkgJson = """{"name":"antigravity-cli","version":"1.5.9"}""".toByteArray()

        GzipCompressorOutputStream(FileOutputStream(tgz)).use { gzos ->
            TarArchiveOutputStream(gzos).use { tos ->
                // Put deep nested 1.9.0 dependency BEFORE root 1.5.9 package.json to verify shallower priority wins
                tos.putArchiveEntry(TarArchiveEntry("package/node_modules/old-dep/package.json").apply { size = nestedDepJson.size.toLong() })
                tos.write(nestedDepJson)
                tos.closeArchiveEntry()

                tos.putArchiveEntry(TarArchiveEntry("package/package.json").apply { size = rootPkgJson.size.toLong() })
                tos.write(rootPkgJson)
                tos.closeArchiveEntry()

                tos.putArchiveEntry(TarArchiveEntry("package/bin/agy").apply {
                    size = fakeElf.size.toLong()
                    mode = 493
                })
                tos.write(fakeElf)
                tos.closeArchiveEntry()
            }
        }

        val dest = tempFolder.newFolder("tgz_out")
        val extracted = AgentArchiveImporter.extractArchive(tgz, dest, "agy-cli.tgz")
        assertEquals("1.5.9", extracted.versionHint)

        val bin = AgentArchiveImporter.findBinaryCandidate(dest, listOf("agy"))
        assertNotNull(bin)
        assertEquals("agy", bin!!.name)
        assertTrue(AgentArchiveImporter.isGlibcDynamicElf(bin))
    }

    @Test
    fun termuxWrapperAndShebangNormalizationEliminateStatus127() {
        val prefix = "/data/data/com.termux/files/usr"
        val wrapperContent = AgentArchiveImporter.buildTermuxGlibcWrapper(
            prefixPath = prefix,
            targetBinaryPath = "/data/data/com.termux/files/home/.local/bin/agy.real",
            shellPath = "$prefix/bin/sh"
        )
        assertTrue(wrapperContent.startsWith("#!$prefix/bin/sh\n"))
        assertTrue(wrapperContent.contains("unset LD_PRELOAD"))
        assertTrue(wrapperContent.contains("unset LD_LIBRARY_PATH"))
        assertTrue(wrapperContent.contains("--library-path"))

        val wrapFile = tempFolder.newFile("agy").apply { writeText(wrapperContent) }
        assertTrue(AgentArchiveImporter.isQuantaniumGlibcWrapper(wrapFile))

        val scriptFile = tempFolder.newFile("agy_script.sh").apply {
            writeText("#!/usr/bin/env bash\necho ok\n")
        }
        assertTrue(AgentArchiveImporter.normalizeTermuxScriptShebang(scriptFile, prefix))
        assertTrue(scriptFile.readText().startsWith("#!$prefix/bin/bash\n"))
    }

    @Test
    fun parseCliVersionOutputSkipsStartupNoiseAndReadsSemver() {
        val raw = """
            ERROR: logging before google.Init: I1114 05:39:50.123456 9999 tz.cc:12] Initialized Android local timezone
            Antigravity CLI v1.5.9
        """.trimIndent()
        assertEquals("1.5.9", AgentArchiveImporter.parseCliVersionOutput(raw))
        assertEquals("1.5.9", AgentArchiveImporter.parseCliVersionOutput("1.5.9\n"))
    }
}
