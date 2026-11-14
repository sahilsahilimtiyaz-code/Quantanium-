package com.example.gemini.data.agent.agy

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.gemini.data.agent.AgentArchiveImporter
import com.example.gemini.data.local.LocalEnvironmentManager
import com.example.gemini.data.preferences.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Universal importer for the Antigravity CLI (`agy`).
 *
 * Supports `.tar.gz`, `.tgz`, `.gz`, `.tar.xz`, `.txz`, `.xz`, `.zip`, `.tar`, raw ELF binaries,
 * or launcher scripts.
 *
 * Runtime placement & compatibility:
 * - Native Android Bionic (`/system/bin/linker64`), static `musl`, or PRoot Linux binaries:
 *   installed directly to `~/.local/bin/agy`.
 * - Linux glibc ELF binaries (`ld-linux-aarch64.so.1`) on Termux builds: bundled glibc is ensured
 *   via [LocalEnvironmentManager.ensureGlibcEnvironment], the raw ELF is installed at
 *   `~/.local/bin/agy.real` (preserved for `agy_ide_bridge` CSRF header patching), and a
 *   Quantanium glibc loader wrapper is written to `~/.local/bin/agy` and linked to `$PREFIX/bin/agy`.
 * - Competing stale binaries (`~/.gemini/bin/agy`, `~/.antigravity/bin/agy`, `~/usr/bin/agy`) are
 *   cleaned up so an older version can never shadow the newly imported binary.
 */
object AgyImporter {
    private const val TAG = "AgyImporter"

    data class ImportResult(
        val version: String,
        val targetPath: String,
        val binarySize: Long
    )

    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        onProgress: (String) -> Unit = {}
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val (displayName, tempFile) = AgentArchiveImporter.copyUriToTempFile(context, uri, "agy", onProgress)
            val extractDir = File(context.cacheDir, "agy_extract_${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                val mb = (tempFile.length() / (1024 * 1024)).coerceAtLeast(1)
                onProgress("Inspecting $displayName (~$mb MB)…")

                val format = AgentArchiveImporter.detectArchiveFormat(tempFile)
                var detectedVersion = AgentArchiveImporter.extractSemverFromText(displayName)
                val candidateBinary: File = when (format) {
                    AgentArchiveImporter.ArchiveFormat.RAW_ELF,
                    AgentArchiveImporter.ArchiveFormat.SCRIPT -> {
                        onProgress("Detected direct agy executable.")
                        tempFile
                    }
                    AgentArchiveImporter.ArchiveFormat.GZIP,
                    AgentArchiveImporter.ArchiveFormat.XZ,
                    AgentArchiveImporter.ArchiveFormat.ZIP,
                    AgentArchiveImporter.ArchiveFormat.TAR -> {
                        onProgress("Extracting ${format.name} package…")
                        val extracted = AgentArchiveImporter.extractArchive(tempFile, extractDir, displayName, onProgress)
                        if (detectedVersion == null && extracted.versionHint != null) {
                            detectedVersion = extracted.versionHint
                        }
                        if (AgentArchiveImporter.isDesktopElectronBundle(extractDir)) {
                            throw IllegalStateException(
                                "This archive is the Antigravity Desktop IDE (Electron GUI app), not the headless CLI ('agy'). " +
                                    "Please import the Antigravity CLI package (e.g. cli_android_arm64.tar.gz, agy_cli_linux_arm64.tar.gz, or agy_cli_linux_arm64_musl.tar.gz), " +
                                    "or use Install agy in Settings."
                            )
                        }
                        onProgress("Locating agy binary inside ${extracted.fileCount} extracted entries…")
                        AgentArchiveImporter.findBinaryCandidate(
                            extractDir,
                            listOf("agy", "antigravity", "agy-linux-arm64", "antigravity-cli")
                        ) ?: throw IllegalStateException("Could not find an `agy` executable inside '$displayName'.")
                    }
                    AgentArchiveImporter.ArchiveFormat.UNKNOWN -> {
                        if (tempFile.length() > 5L * 1024L * 1024L) {
                            onProgress("Treating large package as standalone binary…")
                            tempFile
                        } else {
                            throw IllegalArgumentException("Unsupported package format: expected .tar.gz, .tgz, .tar.xz, .zip, .tar, or ELF binary.")
                        }
                    }
                }

                // Validate CPU architecture before installing so wrong-arch archives fail cleanly instead of exit 126/127 at launch.
                AgentArchiveImporter.checkElfArchCompatibility(
                    candidateBinary,
                    LocalEnvironmentManager.getBootstrapArch()
                )?.let { incompatibleMsg ->
                    throw IllegalStateException("$incompatibleMsg Please import an arm64/aarch64 build.")
                }

                val isTermux = LocalEnvironmentManager.isTermuxPackage(context)
                val isGlibc = AgentArchiveImporter.isGlibcDynamicElf(candidateBinary)
                val isBionic = AgentArchiveImporter.isAndroidBionicElf(candidateBinary)

                if (!isTermux && isBionic) {
                    throw IllegalStateException(
                        "This binary is linked against Android Bionic (/system/bin/linker64) and cannot run inside PRoot Ubuntu. " +
                            "Please import the Linux ARM64 build (agy_cli_linux_arm64.tar.gz or agy_cli_linux_arm64_musl.tar.gz)."
                    )
                }

                val homeDir = AgentArchiveImporter.resolveAgentHomeDir(context)
                val localBin = File(homeDir, ".local/bin").apply { mkdirs() }
                val targetFile = File(localBin, "agy")
                val realFile = File(localBin, "agy.real")
                val installedBinarySize: Long

                // Remove competing stale agy binaries/symlinks so older versions (and invalid host symlinks in PRoot) never shadow ~/.local/bin/agy.
                listOf(
                    File(homeDir, ".gemini/bin/agy"),
                    File(homeDir, ".antigravity/bin/agy"),
                    File(homeDir, "usr/bin/agy")
                ).forEach { stale ->
                    if (stale.exists() || AgentArchiveImporter.isSymlink(stale)) {
                        stale.delete()
                    }
                }

                if (isTermux && isGlibc) {
                    onProgress("Detected glibc-linked ELF; preparing bundled glibc runtime…")
                    val glibcReady = LocalEnvironmentManager.ensureGlibcEnvironment(context)
                    if (!glibcReady) {
                        throw IllegalStateException(
                            "Bundled glibc runtime (ld-linux-aarch64.so.1) could not be initialized. Please check bootstrap installation."
                        )
                    }
                    onProgress("Installing glibc binary to ~/.local/bin/agy.real and writing Termux loader wrapper…")
                    AgentArchiveImporter.atomicInstallBinary(candidateBinary, realFile)
                    installedBinarySize = realFile.length()
                    LocalEnvironmentManager.ensureAgyWrapper(context)
                } else {
                    if (realFile.exists() || AgentArchiveImporter.isSymlink(realFile)) {
                        realFile.delete()
                    }
                    val prefixPath = LocalEnvironmentManager.getPrefixDir(context).absolutePath
                    if (isTermux && !AgentArchiveImporter.isElfFile(candidateBinary)) {
                        AgentArchiveImporter.normalizeTermuxScriptShebang(candidateBinary, prefixPath)
                    }
                    onProgress(
                        when {
                            isBionic -> "Detected native Android Bionic ELF; installing directly to ~/.local/bin/agy…"
                            isGlibc -> "Detected Linux glibc ELF (PRoot); installing to ~/.local/bin/agy…"
                            AgentArchiveImporter.isElfFile(candidateBinary) -> "Detected static/musl ELF binary; installing to ~/.local/bin/agy…"
                            else -> "Detected executable script; installing to ~/.local/bin/agy…"
                        }
                    )
                    AgentArchiveImporter.atomicInstallBinary(candidateBinary, targetFile)
                    installedBinarySize = targetFile.length()
                    if (isTermux) {
                        LocalEnvironmentManager.ensureAgyWrapper(context)
                    }
                }

                // Reset custom agy binary path preference if it pointed away from the default location
                val authPrefs = AuthPreferences(context)
                val configuredPath = authPrefs.getAgyBinaryPathSync().trim()
                val defaultPath = authPrefs.getDefaultAgyBinaryPath()
                if (configuredPath.isNotBlank() && configuredPath != defaultPath && configuredPath != targetFile.absolutePath) {
                    authPrefs.saveAgyBinaryPath(defaultPath)
                }

                // On Termux, verify the newly installed binary actually launches cleanly (`agy --version`) and read its exact version.
                var verifiedVersion: String? = null
                if (isTermux) {
                    verifiedVersion = verifyTermuxAgyBinary(context, targetFile, homeDir, onProgress)
                }

                val finalVersion = verifiedVersion ?: detectedVersion ?: "installed"
                onProgress(
                    "Import complete! agy ($finalVersion) installed at ${targetFile.absolutePath} " +
                        "(${(installedBinarySize / (1024 * 1024)).coerceAtLeast(1)} MB)."
                )
                Log.i(TAG, "Successfully imported agy ($finalVersion) to ${targetFile.absolutePath} (size=$installedBinarySize, glibc=$isGlibc)")

                ImportResult(
                    version = finalVersion,
                    targetPath = targetFile.absolutePath,
                    binarySize = installedBinarySize
                )
            } finally {
                extractDir.deleteRecursively()
                tempFile.delete()
            }
        }
    }

    private fun verifyTermuxAgyBinary(
        context: Context,
        agyExecutable: File,
        homeDir: File,
        onProgress: (String) -> Unit
    ): String? {
        if (!agyExecutable.exists()) return null
        return try {
            val prefix = LocalEnvironmentManager.getPrefixDir(context).absolutePath
            val tmpDir = File(prefix, "tmp").apply { mkdirs() }
            val pb = ProcessBuilder(agyExecutable.absolutePath, "--version")
                .directory(homeDir)
            val env = pb.environment()
            env.remove("LD_PRELOAD")
            env.remove("LD_LIBRARY_PATH")
            env["HOME"] = homeDir.absolutePath
            env["USERPROFILE"] = homeDir.absolutePath
            env["TMPDIR"] = tmpDir.absolutePath
            env["CI"] = "1"
            env["AGY_NO_UPDATE_PROMPT"] = "1"
            env["PATH"] = "${homeDir.absolutePath}/.local/bin:$prefix/bin:/system/bin"

            val proc = pb.start()
            val finished = proc.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return null
            }
            val stdout = proc.inputStream.bufferedReader().readText()
            val stderr = proc.errorStream.bufferedReader().readText()
            val code = proc.exitValue()
            if (code == 126 || code == 127) {
                val detail = (stderr.ifBlank { stdout }).trim().lines().takeLast(3).joinToString(" | ")
                throw IllegalStateException(
                    "Imported binary failed execution self-test (exit status $code)${if (detail.isNotBlank()) ": $detail" else ""}."
                )
            }
            val parsed = AgentArchiveImporter.parseCliVersionOutput(stdout)
                ?: AgentArchiveImporter.parseCliVersionOutput(stderr)
            if (!parsed.isNullOrBlank()) {
                onProgress("Verified installed binary: agy $parsed")
            } else if (code != 0) {
                val detail = (stderr.ifBlank { stdout }).trim().lines().lastOrNull().orEmpty()
                if (detail.isNotBlank()) onProgress("agy --version exited $code: $detail")
            }
            parsed
        } catch (ise: IllegalStateException) {
            throw ise
        } catch (t: Throwable) {
            Log.w(TAG, "Self-test skipped/non-fatal: ${t.message}")
            null
        }
    }
}

