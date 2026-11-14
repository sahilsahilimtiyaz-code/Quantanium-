package com.example.gemini.data.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import com.example.gemini.data.local.LocalEnvironmentManager
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Shared universal package archive & ELF binary importer engine used by Claude Code, OpenCode,
 * Codex, and Antigravity (`agy`).
 *
 * Pure filesystem / archive / ELF inspection helpers have zero Android runtime dependencies so
 * they can be thoroughly unit-tested on JVM alongside Android UI tests.
 */
object AgentArchiveImporter {

    enum class ArchiveFormat {
        RAW_ELF,
        GZIP,
        XZ,
        ZIP,
        TAR,
        SCRIPT,
        UNKNOWN
    }

    data class ExtractedPackage(
        val rootDir: File,
        val versionHint: String?,
        val fileCount: Int
    )

    private val SEMVER_REGEX = Regex("(?:^|[^0-9])v?([0-9]+\\.[0-9]+\\.[0-9]+)(?:[-+][A-Za-z0-9._-]+)?")
    private val STRICT_SEMVER_REGEX = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
    private val PACKAGE_JSON_VERSION_REGEX = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")

    /** Reads up to [count] leading magic bytes from [file]. */
    fun readMagicBytes(file: File, count: Int = 8): ByteArray {
        val len = minOf(count.toLong(), file.length()).toInt().coerceAtLeast(0)
        if (len == 0) return ByteArray(0)
        val bytes = ByteArray(len)
        FileInputStream(file).use { input ->
            var off = 0
            while (off < len) {
                val r = input.read(bytes, off, len - off)
                if (r <= 0) break
                off += r
            }
            return if (off == len) bytes else bytes.copyOf(off)
        }
    }

    fun isElfMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x7F.toByte() &&
            bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()

    fun isGzipMagic(bytes: ByteArray): Boolean =
        bytes.size >= 2 &&
            bytes[0] == 0x1F.toByte() &&
            bytes[1] == 0x8B.toByte()

    fun isXzMagic(bytes: ByteArray): Boolean =
        bytes.size >= 6 &&
            bytes[0] == 0xFD.toByte() &&
            bytes[1] == '7'.code.toByte() &&
            bytes[2] == 'z'.code.toByte() &&
            bytes[3] == 'X'.code.toByte() &&
            bytes[4] == 'Z'.code.toByte() &&
            bytes[5] == 0x00.toByte()

    fun isZipMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            ((bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()) ||
                (bytes[2] == 0x05.toByte() && bytes[3] == 0x06.toByte()))

    fun isScriptMagic(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == '#'.code.toByte() && bytes[1] == '!'.code.toByte()

    fun isTarHeader(header: ByteArray): Boolean {
        if (header.size < 263) return false
        val magic = String(header, 257, 5, Charsets.US_ASCII)
        return magic == "ustar"
    }

    fun isTarArchive(file: File): Boolean {
        if (file.length() < 512) return false
        val buffer = ByteArray(512)
        FileInputStream(file).use { input ->
            var off = 0
            while (off < 512) {
                val r = input.read(buffer, off, 512 - off)
                if (r <= 0) break
                off += r
            }
            if (off < 263) return false
        }
        return isTarHeader(buffer)
    }

    /** Detects archive / executable format primarily by magic header, with tar header fallback. */
    fun detectArchiveFormat(file: File): ArchiveFormat {
        val magic = readMagicBytes(file, 8)
        return when {
            isElfMagic(magic) -> ArchiveFormat.RAW_ELF
            isGzipMagic(magic) -> ArchiveFormat.GZIP
            isXzMagic(magic) -> ArchiveFormat.XZ
            isZipMagic(magic) -> ArchiveFormat.ZIP
            isTarArchive(file) -> ArchiveFormat.TAR
            isScriptMagic(magic) -> ArchiveFormat.SCRIPT
            else -> ArchiveFormat.UNKNOWN
        }
    }

    /** Returns true if [file] begins with the ELF magic header (`\x7fELF`). */
    fun isElfFile(file: File): Boolean = try {
        file.isFile && file.length() >= 4 && isElfMagic(readMagicBytes(file, 4))
    } catch (_: Throwable) {
        false
    }

    const val EM_386 = 3
    const val EM_ARM = 40
    const val EM_X86_64 = 62
    const val EM_AARCH64 = 183

    /**
     * Reads the ELF header `e_machine` architecture identifier (`EM_AARCH64 = 183`, `EM_X86_64 = 62`, etc.),
     * or returns `null` if [file] is not a valid ELF binary.
     */
    fun readElfMachine(file: File): Int? {
        if (!isElfFile(file) || file.length() < 64) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val ehdr = ByteArray(64)
                raf.readFully(ehdr)
                val eiData = ehdr[5].toInt() and 0xFF
                val order = if (eiData == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
                val bb = ByteBuffer.wrap(ehdr).order(order)
                bb.getShort(18).toInt() and 0xFFFF
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun describeElfArchitecture(machine: Int?): String = when (machine) {
        EM_AARCH64 -> "ARM64 (aarch64)"
        EM_X86_64 -> "x86_64 (amd64)"
        EM_ARM -> "ARM32 (armeabi)"
        EM_386 -> "x86 (32-bit)"
        null -> "unknown"
        else -> "ELF machine $machine"
    }

    /**
     * Returns an actionable incompatibility error message if [file] is an ELF compiled for a CPU ISA
     * that cannot execute natively under [deviceArch] (e.g. `x86_64` imported onto an `aarch64` device),
     * or `null` when compatible / non-ELF.
     */
    fun checkElfArchCompatibility(file: File, deviceArch: String): String? {
        val machine = readElfMachine(file) ?: return null
        val normalized = deviceArch.lowercase()
        val compatible = when {
            normalized == "aarch64" || normalized.startsWith("arm64") -> machine == EM_AARCH64 || machine == EM_ARM
            normalized == "x86_64" || normalized == "amd64" -> machine == EM_X86_64 || machine == EM_386
            normalized.startsWith("arm") -> machine == EM_ARM
            normalized == "i686" || normalized == "x86" -> machine == EM_386
            else -> true
        }
        return if (compatible) null
        else "Incompatible CPU architecture: binary is ${describeElfArchitecture(machine)}, but this device/environment runs $deviceArch."
    }

    /**
     * Parses ELF64/ELF32 program headers (`PT_INTERP`, `p_type == 3`) and returns the dynamic linker path,
     * or `null` for statically linked / non-ELF files.
     */
    fun readElfInterpreter(file: File): String? {
        if (!isElfFile(file) || file.length() < 64) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val ehdr = ByteArray(64)
                raf.readFully(ehdr)
                val eiClass = ehdr[4].toInt() and 0xFF
                val eiData = ehdr[5].toInt() and 0xFF
                val order = if (eiData == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
                val bb = ByteBuffer.wrap(ehdr).order(order)

                val (ePhOff, ePhEntSize, ePhNum) = when (eiClass) {
                    2 -> Triple(bb.getLong(32), bb.getShort(54).toInt() and 0xFFFF, bb.getShort(56).toInt() and 0xFFFF)
                    1 -> Triple((bb.getInt(28).toLong() and 0xFFFFFFFFL), bb.getShort(42).toInt() and 0xFFFF, bb.getShort(44).toInt() and 0xFFFF)
                    else -> return null
                }
                if (ePhOff <= 0L || ePhEntSize < 32 || ePhNum <= 0 || ePhNum > 256) return null
                val fileLen = raf.length()
                for (i in 0 until ePhNum) {
                    val phPos = ePhOff + i.toLong() * ePhEntSize
                    if (phPos + ePhEntSize > fileLen) break
                    raf.seek(phPos)
                    val phdr = ByteArray(ePhEntSize)
                    raf.readFully(phdr)
                    val pbb = ByteBuffer.wrap(phdr).order(order)
                    val pType = pbb.getInt(0)
                    if (pType == 3) { // PT_INTERP
                        val (pOffset, pFileSz) = if (eiClass == 2) {
                            pbb.getLong(8) to pbb.getLong(32)
                        } else {
                            (pbb.getInt(4).toLong() and 0xFFFFFFFFL) to (pbb.getInt(16).toLong() and 0xFFFFFFFFL)
                        }
                        if (pOffset > 0L && pFileSz in 1..512 && pOffset + pFileSz <= fileLen) {
                            raf.seek(pOffset)
                            val interpBytes = ByteArray(pFileSz.toInt())
                            raf.readFully(interpBytes)
                            return String(interpBytes, Charsets.US_ASCII).trim('\u0000', ' ', '\n')
                        }
                    }
                }
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Returns `true` if [file] is dynamically linked against glibc (`ld-linux-*` / `/lib/ld-*`).
     * Static musl / Go binaries and Android Bionic (`/system/bin/linker64`) binaries return `false`.
     */
    fun isGlibcDynamicElf(file: File): Boolean {
        val interp = readElfInterpreter(file) ?: return false
        return (interp.contains("ld-linux") || interp.contains("/lib/ld-")) &&
            !interp.contains("/system/bin/linker") &&
            !interp.contains("musl")
    }

    /** Returns `true` if [file] is a native Android Bionic ELF linked against `/system/bin/linker*`. */
    fun isAndroidBionicElf(file: File): Boolean {
        val interp = readElfInterpreter(file) ?: return false
        return interp.contains("/system/bin/linker")
    }

    /**
     * Detects unpacked Electron / Chromium Desktop IDE bundles (such as the Linux desktop `Antigravity.tar.gz`)
     * so importers can fail clearly instead of copying the GUI Electron executable without its shared libraries.
     */
    fun isDesktopElectronBundle(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val markers = setOf(
            "libffmpeg.so",
            "resources.pak",
            "chrome_100_percent.pak",
            "chrome-sandbox",
            "icudtl.dat",
            "v8_context_snapshot.bin",
            "snapshot_blob.bin"
        )
        var hits = 0
        dir.walkTopDown().maxDepth(4).forEach { file ->
            if (file.isFile && file.name.lowercase() in markers) {
                hits++
                if (hits >= 2) return true
            }
        }
        return false
    }

    /**
     * Guards against Zip-Slip & sibling-directory prefix traversal (e.g. `destDir=/tmp/out` vs `/tmp/out_evil`).
     */
    fun validatePathWithinDest(destDir: File, targetFile: File): File {
        val destCanonical = destDir.canonicalPath
        val destPrefix = if (destCanonical.endsWith(File.separator)) destCanonical else destCanonical + File.separator
        val fileCanonical = targetFile.canonicalPath
        if (fileCanonical != destCanonical && !fileCanonical.startsWith(destPrefix)) {
            throw SecurityException("Invalid archive path outside target directory: ${targetFile.path}")
        }
        return targetFile
    }

    /** Extracts a `.tar.gz`/`.tgz`, `.gz` (single compressed binary), `.tar.xz`/`.txz`, `.xz` (single compressed binary), `.zip`, or `.tar` into [destDir]. */
    fun extractArchive(
        archiveFile: File,
        destDir: File,
        displayName: String = archiveFile.name,
        onProgress: (String) -> Unit = {}
    ): ExtractedPackage {
        destDir.mkdirs()
        return when (detectArchiveFormat(archiveFile)) {
            ArchiveFormat.GZIP -> {
                val stripped = stripCompressionSuffix(displayName, ".gz", ".tgz")
                FileInputStream(archiveFile).use { fis ->
                    BufferedInputStream(fis, 64 * 1024).use { bis ->
                        GzipCompressorInputStream(bis).use { gzis ->
                            extractCompressedStream(gzis, destDir, stripped, onProgress)
                        }
                    }
                }
            }
            ArchiveFormat.XZ -> {
                val stripped = stripCompressionSuffix(displayName, ".xz", ".txz")
                FileInputStream(archiveFile).use { fis ->
                    BufferedInputStream(fis, 64 * 1024).use { bis ->
                        XZCompressorInputStream(bis).use { xzis ->
                            extractCompressedStream(xzis, destDir, stripped, onProgress)
                        }
                    }
                }
            }
            ArchiveFormat.ZIP -> extractZip(archiveFile, destDir, onProgress)
            ArchiveFormat.TAR -> {
                FileInputStream(archiveFile).use { fis ->
                    BufferedInputStream(fis, 64 * 1024).use { bis ->
                        extractTarStream(bis, destDir, onProgress)
                    }
                }
            }
            ArchiveFormat.RAW_ELF, ArchiveFormat.SCRIPT -> {
                val name = sanitizeSingleFilename(displayName.ifBlank { "binary" })
                val target = validatePathWithinDest(destDir, File(destDir, name))
                archiveFile.inputStream().use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
                target.setExecutable(true, false)
                ExtractedPackage(destDir, extractSemverFromText(displayName), 1)
            }
            ArchiveFormat.UNKNOWN -> throw IllegalArgumentException(
                "Unsupported package format for '$displayName'. Supported: .tar.gz, .tgz, .gz, .tar.xz, .txz, .xz, .zip, .tar, ELF binary, or executable script."
            )
        }
    }

    private fun extractCompressedStream(
        decompressed: InputStream,
        destDir: File,
        singleFileFallbackName: String,
        onProgress: (String) -> Unit
    ): ExtractedPackage {
        val buffered = BufferedInputStream(decompressed, 64 * 1024)
        buffered.mark(1024)
        val peek = ByteArray(512)
        var peekRead = 0
        while (peekRead < peek.size) {
            val r = buffered.read(peek, peekRead, peek.size - peekRead)
            if (r <= 0) break
            peekRead += r
        }
        buffered.reset()

        val headerSlice = if (peekRead == peek.size) peek else peek.copyOf(peekRead)
        return if (isTarHeader(headerSlice)) {
            extractTarStream(buffered, destDir, onProgress)
        } else {
            val fileName = sanitizeSingleFilename(singleFileFallbackName.ifBlank { "binary" })
            val outFile = validatePathWithinDest(destDir, File(destDir, fileName))
            onProgress("Decompressing single binary '$fileName'…")
            FileOutputStream(outFile).use { out ->
                buffered.copyTo(out)
            }
            outFile.setExecutable(true, false)
            ExtractedPackage(destDir, extractSemverFromText(singleFileFallbackName), 1)
        }
    }

    private fun extractTarStream(
        tarStream: InputStream,
        destDir: File,
        onProgress: (String) -> Unit
    ): ExtractedPackage {
        var versionFound: String? = null
        var bestVersionDepth = Int.MAX_VALUE
        var count = 0
        TarArchiveInputStream(tarStream).use { tarIn ->
            var entry: TarArchiveEntry?
            while (tarIn.nextTarEntry.also { entry = it } != null) {
                val cur = entry ?: break
                val cleanName = cur.name.trimStart('/')
                if (cleanName.isBlank()) continue
                val outputFile = validatePathWithinDest(destDir, File(destDir, cleanName))
                when {
                    cur.isDirectory -> outputFile.mkdirs()
                    cur.isSymbolicLink || cur.isLink -> {
                        // Skip symlinks during extraction for safety (tarballs typically contain real binaries too)
                    }
                    else -> {
                        outputFile.parentFile?.mkdirs()
                        FileOutputStream(outputFile).use { out ->
                            tarIn.copyTo(out)
                        }
                        if (cur.mode and 0b001_000_000 != 0) {
                            outputFile.setExecutable(true, false)
                        }
                        val candidateVer = readMetadataVersionIfCandidate(outputFile)
                        if (candidateVer != null) {
                            val depth = cleanName.count { it == '/' }
                            if (versionFound == null || depth < bestVersionDepth) {
                                versionFound = candidateVer
                                bestVersionDepth = depth
                            }
                        }
                    }
                }
                count++
                if (count % 100 == 0) onProgress("Unpacked $count entries…")
            }
        }
        return ExtractedPackage(destDir, versionFound, count)
    }

    private fun extractZip(
        zipFile: File,
        destDir: File,
        onProgress: (String) -> Unit
    ): ExtractedPackage {
        var versionFound: String? = null
        var bestVersionDepth = Int.MAX_VALUE
        var count = 0
        FileInputStream(zipFile).use { fileIn ->
            BufferedInputStream(fileIn, 64 * 1024).use { bufIn ->
                ZipInputStream(bufIn).use { zipIn ->
                    var entry: ZipEntry?
                    while (zipIn.nextEntry.also { entry = it } != null) {
                        val cur = entry ?: break
                        val cleanName = cur.name.trimStart('/')
                        if (cleanName.isBlank()) {
                            zipIn.closeEntry()
                            continue
                        }
                        val outputFile = validatePathWithinDest(destDir, File(destDir, cleanName))
                        if (cur.isDirectory) {
                            outputFile.mkdirs()
                        } else {
                            outputFile.parentFile?.mkdirs()
                            FileOutputStream(outputFile).use { out ->
                                zipIn.copyTo(out)
                            }
                            val candidateVer = readMetadataVersionIfCandidate(outputFile)
                            if (candidateVer != null) {
                                val depth = cleanName.count { it == '/' }
                                if (versionFound == null || depth < bestVersionDepth) {
                                    versionFound = candidateVer
                                    bestVersionDepth = depth
                                }
                            }
                        }
                        zipIn.closeEntry()
                        count++
                        if (count % 100 == 0) onProgress("Unpacked $count entries…")
                    }
                }
            }
        }
        return ExtractedPackage(destDir, versionFound, count)
    }

    private fun readMetadataVersionIfCandidate(file: File): String? = when {
        file.name == "package.json" -> parseVersionFromPackageJson(file)
        file.name.equals("version", ignoreCase = true) && file.length() in 1..256 -> try {
            extractSemverFromText(file.readText().trim())
        } catch (_: Throwable) {
            null
        }
        else -> null
    }

    /**
     * Finds the best binary/executable candidate in [searchDir] for an agent whose canonical binary names
     * are listed in [preferredNames] (ordered by preference, e.g. `listOf("codex", "codex-aarch64-unknown-linux-musl")`).
     */
    fun findBinaryCandidate(
        searchDir: File,
        preferredNames: List<String>,
        allowLargeFallback: Boolean = true
    ): File? {
        val allFiles = searchDir.walkTopDown()
            .filter { it.isFile && it.length() > 0L }
            .toList()
        if (allFiles.isEmpty()) return null

        val lowerPreferred = preferredNames.map { it.lowercase() }
        fun matchesPreferred(name: String): Boolean {
            val l = name.lowercase()
            return lowerPreferred.any { pref ->
                l == pref ||
                    l == "$pref.exe" ||
                    l == "$pref.js" ||
                    l == "$pref.mjs" ||
                    l.startsWith("$pref-") ||
                    l.startsWith("${pref}_")
            }
        }

        // 1. Exact/prefixed ELF binary (> 128 KB)
        allFiles.filter { matchesPreferred(it.name) && it.length() > 128 * 1024 && isElfFile(it) }
            .maxByOrNull { file ->
                val exactBonus = if (file.name.lowercase() in lowerPreferred) 1_000_000_000L else 0L
                exactBonus + file.length()
            }?.let { return it }

        // 2. Any large standalone ELF (> 2 MB, ignoring shared .so libraries unless nothing else exists)
        allFiles.filter { !it.name.endsWith(".so") && !it.name.contains(".so.") && it.length() > 2 * 1024 * 1024 && isElfFile(it) }
            .maxByOrNull { it.length() }
            ?.let { return it }

        // 3. Exact preferred file (e.g. Node CLI script / launcher / smaller ELF)
        for (pref in lowerPreferred) {
            allFiles.firstOrNull { it.name.lowercase() == pref || it.name.lowercase() == "$pref.js" }
                ?.let { return it }
        }

        // 4. Prefixed non-ELF match (excluding common docs/metadata)
        allFiles.filter {
            matchesPreferred(it.name) &&
                !it.name.endsWith(".md", ignoreCase = true) &&
                !it.name.endsWith(".txt", ignoreCase = true) &&
                !it.name.endsWith(".json", ignoreCase = true) &&
                !it.name.endsWith(".sha256", ignoreCase = true)
        }.maxByOrNull { it.length() }?.let { return it }

        if (!allowLargeFallback) return null

        // 5. Fallback: largest file > 100 KB excluding archives/docs
        return allFiles.filter {
            it.length() > 100 * 1024 &&
                !it.name.endsWith(".json", ignoreCase = true) &&
                !it.name.endsWith(".md", ignoreCase = true)
        }.maxByOrNull { it.length() }
    }

    fun parseVersionFromPackageJson(packageJson: File): String? = try {
        val content = packageJson.readText()
        val raw = PACKAGE_JSON_VERSION_REGEX.find(content)?.groupValues?.getOrNull(1)
        extractSemverFromText(raw)
    } catch (_: Throwable) {
        null
    }

    fun extractSemverFromText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return SEMVER_REGEX.find(text)?.groupValues?.getOrNull(1)
    }

    /**
     * Parses `cli --version` stdout safely, skipping Google startup log noise (`ERROR: logging before google.Init...`).
     */
    fun parseCliVersionOutput(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleanedLines = raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("ERROR: logging before google.Init") }
        cleanedLines.firstOrNull { Regex("""^v?\d+(\.\d+)+\S*$""").matches(it) }?.let {
            return it.removePrefix("v")
        }
        for (line in cleanedLines) {
            extractSemverFromText(line)?.let { return it }
        }
        return null
    }

    fun isStrictSemver(version: String?): Boolean =
        version != null && STRICT_SEMVER_REGEX.matches(version)

    fun compareSemver(a: String, b: String): Int {
        val pa = a.split(".").mapNotNull { it.toIntOrNull() }
        val pb = b.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until minOf(pa.size, pb.size)) {
            if (pa[i] != pb[i]) return pa[i].compareTo(pb[i])
        }
        return pa.size.compareTo(pb.size)
    }

    /** Atomically installs [source] to [target] with `0755` permissions. */
    fun atomicInstallBinary(source: File, target: File) {
        val parent = target.parentFile ?: throw IllegalArgumentException("Target has no parent: $target")
        parent.mkdirs()
        val tmpTarget = File(parent, ".${target.name}.import_tmp_${System.nanoTime()}")
        if (tmpTarget.exists()) tmpTarget.delete()

        try {
            FileInputStream(source).use { input ->
                FileOutputStream(tmpTarget).use { output ->
                    input.copyTo(output, 64 * 1024)
                    output.fd.sync()
                }
            }
            chmod0755(tmpTarget)
            if (target.exists() || isSymlink(target)) target.delete()
            if (!tmpTarget.renameTo(target)) {
                target.delete()
                if (!tmpTarget.renameTo(target)) {
                    FileInputStream(tmpTarget).use { input ->
                        FileOutputStream(target).use { output ->
                            input.copyTo(output, 64 * 1024)
                        }
                    }
                    tmpTarget.delete()
                }
            }
            chmod0755(target)
        } catch (t: Throwable) {
            tmpTarget.delete()
            throw t
        }
    }

    fun chmod0755(file: File) {
        file.setReadable(true, false)
        file.setExecutable(true, false)
        try {
            Os.chmod(file.absolutePath, 493 /* 0755 */)
        } catch (_: Throwable) {
            // Pure JVM tests run without Android Os.chmod
        }
    }

    fun ensureSymlink(target: File, link: File) {
        try {
            link.parentFile?.mkdirs()
            val existing = try {
                Os.readlink(link.absolutePath)
            } catch (_: Throwable) {
                null
            }
            if (existing == target.absolutePath) return
            if (link.exists() || isSymlink(link)) link.delete()
            Os.symlink(target.absolutePath, link.absolutePath)
        } catch (_: Throwable) {
            // Fallback for filesystems where symlink creation is restricted
            if (!link.exists()) {
                try {
                    atomicInstallBinary(target, link)
                } catch (_: Throwable) {}
            }
        }
    }

    fun isSymlink(file: File): Boolean = try {
        Os.lstat(file.path).st_mode and 61440 == 40960 // S_ISLNK
    } catch (_: Throwable) {
        false
    }

    /**
     * Resolves the Linux home directory for CLI agents (`files/home` on Termux builds,
     * `files/ubuntu/root` if initialized on PRoot Ubuntu builds, otherwise `files/home`).
     */
    fun resolveAgentHomeDir(context: Context): File {
        if (LocalEnvironmentManager.isTermuxPackage(context)) {
            return LocalEnvironmentManager.getHomeDir(context)
        }
        val ubuntuRoot = File(LocalEnvironmentManager.getUbuntuRootDir(context), "root")
        return if (ubuntuRoot.exists()) ubuntuRoot else LocalEnvironmentManager.getHomeDir(context)
    }

    /** Copies Android content [uri] to a temporary file inside cacheDir while streaming progress. */
    fun copyUriToTempFile(
        context: Context,
        uri: Uri,
        prefix: String,
        onProgress: (String) -> Unit
    ): Pair<String, File> {
        val displayName = queryDisplayName(context, uri) ?: "${prefix}_package"
        onProgress("Reading selected file: $displayName…")
        val tempFile = File.createTempFile("${prefix}_import_", "_pkg", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L
                    var nextReport = 1024L * 1024L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalRead >= nextReport) {
                            onProgress("Cached ${totalRead / (1024 * 1024)} MB…")
                            nextReport += 2L * 1024L * 1024L
                        }
                    }
                }
            } ?: throw IllegalArgumentException("Could not open input stream from selected file.")

            if (tempFile.length() == 0L) {
                throw IllegalStateException("The selected file is empty (0 bytes).")
            }
            return displayName to tempFile
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        }
    }

    const val QUANTANIUM_WRAPPER_MARKER = "Generated by Quantanium agent importer"

    /** Returns `true` if [file] is a Quantanium-generated glibc wrapper script. */
    fun isQuantaniumGlibcWrapper(file: File): Boolean {
        if (!file.isFile || isSymlink(file) || file.length() > 16_384L) return false
        return try {
            file.readText().contains(QUANTANIUM_WRAPPER_MARKER)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Rewrites Unix desktop shebangs (`/bin/bash`, `/bin/sh`, `/usr/bin/env bash`, etc.) inside [scriptFile]
     * to Termux `$PREFIX/bin/...` so imported wrapper scripts do not fail with exit status 127 on Android.
     */
    fun normalizeTermuxScriptShebang(scriptFile: File, prefixPath: String): Boolean {
        if (!scriptFile.isFile || scriptFile.length() == 0L || scriptFile.length() > 1024L * 1024L) return false
        if (!isScriptMagic(readMagicBytes(scriptFile, 2))) return false
        return try {
            val content = scriptFile.readText()
            val newlineIdx = content.indexOf('\n')
            val firstLine = (if (newlineIdx == -1) content else content.substring(0, newlineIdx)).trimEnd('\r')
            val body = if (newlineIdx == -1) "" else content.substring(newlineIdx)
            val normalizedFirst = when {
                firstLine == "#!/bin/bash" || firstLine == "#!/usr/bin/bash" -> "#!$prefixPath/bin/bash"
                firstLine == "#!/bin/sh" || firstLine == "#!/usr/bin/sh" -> "#!$prefixPath/bin/sh"
                firstLine.startsWith("#!/usr/bin/env ") -> {
                    val tool = firstLine.removePrefix("#!/usr/bin/env ").trim().substringBefore(' ')
                    val resolvedTool = if (tool.isNotBlank()) tool else "sh"
                    "#!$prefixPath/bin/$resolvedTool"
                }
                else -> return false
            }
            scriptFile.writeText(normalizedFirst + body)
            chmod0755(scriptFile)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Builds a glibc loader wrapper script for Termux when a dynamic glibc ELF binary needs it. */
    fun buildTermuxGlibcWrapper(
        prefixPath: String,
        targetBinaryPath: String,
        shellPath: String = "$prefixPath/bin/bash"
    ): String = """
        |#!$shellPath
        |# $QUANTANIUM_WRAPPER_MARKER
        |GLIBC_LIB="$prefixPath/glibc/lib"
        |LOADER="${'$'}GLIBC_LIB/ld-linux-aarch64.so.1"
        |TARGET="$targetBinaryPath"
        |if [ ! -x "${'$'}LOADER" ]; then
        |    echo "glibc runtime missing (${'$'}LOADER). Reopen Quantanium to restore it." >&2
        |    exit 127
        |fi
        |if [ ! -x "${'$'}TARGET" ]; then
        |    echo "Agent binary missing or not executable (${'$'}TARGET)." >&2
        |    exit 127
        |fi
        |unset LD_PRELOAD
        |unset LD_LIBRARY_PATH
        |export TMPDIR="${'$'}{TMPDIR:-$prefixPath/tmp}"
        |exec "${'$'}LOADER" --library-path "${'$'}GLIBC_LIB" "${'$'}TARGET" "${'$'}@"
        |""".trimMargin()

    fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Throwable) {
            uri.lastPathSegment
        }
    }

    private fun stripCompressionSuffix(name: String, suffix: String, altTarSuffix: String): String {
        val clean = sanitizeSingleFilename(name)
        return when {
            clean.endsWith(".tar$suffix", ignoreCase = true) -> clean.dropLast(4 + suffix.length)
            clean.endsWith(altTarSuffix, ignoreCase = true) -> clean.dropLast(altTarSuffix.length)
            clean.endsWith(suffix, ignoreCase = true) -> clean.dropLast(suffix.length)
            else -> clean
        }
    }

    private fun sanitizeSingleFilename(raw: String): String =
        raw.substringAfterLast('/').substringAfterLast('\\').ifBlank { "binary" }
}
