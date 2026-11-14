package com.example.gemini.data.agent.opencode

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.gemini.data.agent.AgentArchiveImporter
import com.example.gemini.data.local.LocalEnvironmentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Robust importer for OpenCode agents.
 *
 * Supports importing OpenCode from `.tar.gz`, `.tgz`, `.gz`, `.tar.xz`, `.txz`, `.xz`,
 * `.zip`, `.tar`, raw ELF binaries, or launcher scripts.
 *
 * Installs the executable to `~/.opencode/bin/opencode` (where the bridge's `opencode.Manager`
 * resolves it directly and automatically launches dynamic glibc ELFs via the bundled glibc loader).
 * Also provisions a terminal command wrapper/link at `$PREFIX/bin/opencode` when Termux is active.
 */
object OpenCodeImporter {
    private const val TAG = "OpenCodeImporter"

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
            val (displayName, tempFile) = AgentArchiveImporter.copyUriToTempFile(context, uri, "opencode", onProgress)
            val extractDir = File(context.cacheDir, "opencode_extract_${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                val mb = (tempFile.length() / (1024 * 1024)).coerceAtLeast(1)
                onProgress("Inspecting $displayName (~$mb MB)…")

                val format = AgentArchiveImporter.detectArchiveFormat(tempFile)
                var detectedVersion = AgentArchiveImporter.extractSemverFromText(displayName)
                val candidateBinary: File = when (format) {
                    AgentArchiveImporter.ArchiveFormat.RAW_ELF,
                    AgentArchiveImporter.ArchiveFormat.SCRIPT -> {
                        onProgress("Detected direct OpenCode executable.")
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
                        onProgress("Locating OpenCode binary inside ${extracted.fileCount} extracted entries…")
                        AgentArchiveImporter.findBinaryCandidate(
                            extractDir,
                            listOf("opencode", "opencode-linux-arm64", "opencode-cli")
                        ) ?: throw IllegalStateException("Could not find an OpenCode executable inside '$displayName'.")
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

                val finalVersion = detectedVersion ?: "installed"
                val isGlibc = AgentArchiveImporter.isGlibcDynamicElf(candidateBinary)
                onProgress(
                    if (isGlibc) "Detected dynamic glibc ELF (bridge runs via bundled glibc loader)."
                    else "Detected static binary / script."
                )

                val homeDir = AgentArchiveImporter.resolveAgentHomeDir(context)
                val binDir = File(homeDir, ".opencode/bin").apply { mkdirs() }
                val targetFile = File(binDir, "opencode")
                onProgress("Installing OpenCode to ~/.opencode/bin/opencode…")
                AgentArchiveImporter.atomicInstallBinary(candidateBinary, targetFile)

                // Mirror to ~/.local/bin/opencode and expose in Termux $PREFIX/bin so terminal usage works seamlessly
                val localBinDir = File(homeDir, ".local/bin").apply { mkdirs() }
                val localBinLink = File(localBinDir, "opencode")
                if (LocalEnvironmentManager.isTermuxPackage(context)) {
                    val prefixDir = LocalEnvironmentManager.getPrefixDir(context)
                    val termuxBin = LocalEnvironmentManager.getBinDir(context)
                    if (isGlibc && termuxBin.exists()) {
                        val termuxTarget = File(termuxBin, "opencode")
                        val wrapper = AgentArchiveImporter.buildTermuxGlibcWrapper(prefixDir.absolutePath, targetFile.absolutePath)
                        val tmpWrap = File(termuxBin, ".opencode.wrap_tmp")
                        tmpWrap.writeText(wrapper)
                        AgentArchiveImporter.chmod0755(tmpWrap)
                        if (termuxTarget.exists() || AgentArchiveImporter.isSymlink(termuxTarget)) termuxTarget.delete()
                        tmpWrap.renameTo(termuxTarget)
                        AgentArchiveImporter.chmod0755(termuxTarget)
                        AgentArchiveImporter.ensureSymlink(termuxTarget, localBinLink)
                    } else if (termuxBin.exists()) {
                        AgentArchiveImporter.ensureSymlink(targetFile, File(termuxBin, "opencode"))
                        AgentArchiveImporter.ensureSymlink(targetFile, localBinLink)
                    }
                } else {
                    AgentArchiveImporter.ensureSymlink(targetFile, localBinLink)
                }

                onProgress("Import complete! OpenCode installed at ${targetFile.absolutePath} (${targetFile.length() / (1024 * 1024)} MB).")
                Log.i(TAG, "Successfully imported OpenCode to ${targetFile.absolutePath}")

                ImportResult(
                    version = finalVersion,
                    targetPath = targetFile.absolutePath,
                    binarySize = targetFile.length()
                )
            } finally {
                extractDir.deleteRecursively()
                tempFile.delete()
            }
        }
    }
}
