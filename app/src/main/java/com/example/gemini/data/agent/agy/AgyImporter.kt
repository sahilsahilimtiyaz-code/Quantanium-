package com.example.gemini.data.agent.agy

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.gemini.data.agent.AgentArchiveImporter
import com.example.gemini.data.local.LocalEnvironmentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Universal importer for the Antigravity CLI (`agy`).
 *
 * Supports `.tar.gz`, `.tgz`, `.gz`, `.tar.xz`, `.txz`, `.xz`, `.zip`, `.tar`, raw ELF binaries,
 * or launcher scripts.
 *
 * Installs `agy` directly at `~/.local/bin/agy` (which `agy_ide_bridge` checks first and invokes
 * automatically via glibc loader if dynamically linked), syncs `$PREFIX/bin/agy` via
 * [LocalEnvironmentManager.ensureAgyWrapper], and removes any stale legacy shell wrappers.
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
                        onProgress("Locating agy binary inside ${extracted.fileCount} extracted entries…")
                        AgentArchiveImporter.findBinaryCandidate(
                            extractDir,
                            listOf("agy", "antigravity", "agy-linux-arm64")
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

                val isGlibc = AgentArchiveImporter.isGlibcDynamicElf(candidateBinary)
                onProgress(
                    if (isGlibc) "Detected glibc-linked ELF (bridge handles glibc loader automatically)."
                    else if (AgentArchiveImporter.isElfFile(candidateBinary)) "Detected static/Bionic ELF binary."
                    else "Detected executable script."
                )

                val homeDir = AgentArchiveImporter.resolveAgentHomeDir(context)
                val localBin = File(homeDir, ".local/bin").apply { mkdirs() }
                val targetFile = File(localBin, "agy")
                onProgress("Installing agy to ~/.local/bin/agy…")
                AgentArchiveImporter.atomicInstallBinary(candidateBinary, targetFile)

                // Clean up any legacy ~/.local/bin/agy.real artifact left by older installs
                File(localBin, "agy.real").takeIf { it.exists() }?.delete()

                if (LocalEnvironmentManager.isTermuxPackage(context)) {
                    LocalEnvironmentManager.ensureAgyWrapper(context)
                } else {
                    val altGeminiBin = File(homeDir, ".gemini/bin").apply { mkdirs() }
                    AgentArchiveImporter.ensureSymlink(targetFile, File(altGeminiBin, "agy"))
                }

                val finalVersion = detectedVersion ?: "installed"
                onProgress("Import complete! agy installed at ${targetFile.absolutePath} (${(targetFile.length() / (1024 * 1024)).coerceAtLeast(1)} MB).")
                Log.i(TAG, "Successfully imported agy to ${targetFile.absolutePath}")

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
