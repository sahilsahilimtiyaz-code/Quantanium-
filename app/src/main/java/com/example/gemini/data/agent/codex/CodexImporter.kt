package com.example.gemini.data.agent.codex

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.gemini.data.agent.AgentArchiveImporter
import com.example.gemini.data.local.LocalEnvironmentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Universal importer for OpenAI Codex CLI (`codex`).
 *
 * Supports `.tar.gz`, `.tgz`, `.gz`, `.tar.xz`, `.txz`, `.xz`, `.zip`, `.tar`, raw ELFs,
 * and wrapper scripts (including official GitHub releases such as `codex-aarch64-unknown-linux-musl.tar.gz`).
 *
 * Placement & runtime compatibility:
 * - Static musl / native Android / script binaries: installed at `~/.local/bin/codex` and linked
 *   to `$PREFIX/bin/codex` on Termux builds (matching `codex/manager.go` + `AuthPreferences.DEFAULT_CODEX_BINARY_PATH`).
 * - Dynamic glibc Linux ELFs (`PT_INTERP` ld-linux): installed to `~/.local/libexec/codex-bin` with
 *   a Termux glibc loader wrapper written to `~/.local/bin/codex` and linked to `$PREFIX/bin/codex`,
 *   because `codex/manager.go` spawns the resolved binary directly without a built-in glibc wrapper.
 */
object CodexImporter {
    private const val TAG = "CodexImporter"

    data class ImportResult(
        val version: String,
        val targetPath: String,
        val binarySize: Long,
        val usedGlibcWrapper: Boolean
    )

    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        onProgress: (String) -> Unit = {}
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val (displayName, tempFile) = AgentArchiveImporter.copyUriToTempFile(context, uri, "codex", onProgress)
            val extractDir = File(context.cacheDir, "codex_extract_${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                val mb = (tempFile.length() / (1024 * 1024)).coerceAtLeast(1)
                onProgress("Inspecting $displayName (~$mb MB)…")

                val format = AgentArchiveImporter.detectArchiveFormat(tempFile)
                var detectedVersion = AgentArchiveImporter.extractSemverFromText(displayName)
                val candidateBinary: File = when (format) {
                    AgentArchiveImporter.ArchiveFormat.RAW_ELF,
                    AgentArchiveImporter.ArchiveFormat.SCRIPT -> {
                        onProgress("Detected direct Codex executable.")
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
                        onProgress("Locating Codex binary inside ${extracted.fileCount} extracted entries…")
                        AgentArchiveImporter.findBinaryCandidate(
                            extractDir,
                            listOf("codex", "codex-aarch64-unknown-linux-musl", "codex-aarch64-unknown-linux-gnu", "codex-linux-arm64")
                        ) ?: throw IllegalStateException("Could not find a Codex executable inside '$displayName'.")
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

                val isTermux = LocalEnvironmentManager.isTermuxPackage(context)
                val isGlibc = AgentArchiveImporter.isGlibcDynamicElf(candidateBinary)
                val prefixDir = LocalEnvironmentManager.getPrefixDir(context)
                val glibcLoader = File(prefixDir, "glibc/lib/ld-linux-aarch64.so.1")
                val needsWrapper = isTermux && isGlibc && glibcLoader.exists()

                val homeDir = AgentArchiveImporter.resolveAgentHomeDir(context)
                val localBin = File(homeDir, ".local/bin").apply { mkdirs() }
                val localCodex = File(localBin, "codex")
                val binarySize: Long
                val finalVersion = detectedVersion ?: "installed"

                if (needsWrapper) {
                    onProgress("Detected glibc-linked Codex ELF: wrapping with bundled glibc loader…")
                    val libexecDir = File(homeDir, ".local/libexec").apply { mkdirs() }
                    val realElf = File(libexecDir, "codex-bin")
                    AgentArchiveImporter.atomicInstallBinary(candidateBinary, realElf)
                    binarySize = realElf.length()

                    val wrapper = AgentArchiveImporter.buildTermuxGlibcWrapper(prefixDir.absolutePath, realElf.absolutePath)
                    val tmpWrap = File(localBin, ".codex.wrap_tmp")
                    tmpWrap.writeText(wrapper)
                    AgentArchiveImporter.chmod0755(tmpWrap)
                    if (localCodex.exists() || AgentArchiveImporter.isSymlink(localCodex)) localCodex.delete()
                    tmpWrap.renameTo(localCodex)
                    AgentArchiveImporter.chmod0755(localCodex)
                } else {
                    onProgress(
                        if (AgentArchiveImporter.isElfFile(candidateBinary)) "Detected static/native ELF binary: installing directly…"
                        else "Installing Codex executable…"
                    )
                    AgentArchiveImporter.atomicInstallBinary(candidateBinary, localCodex)
                    binarySize = localCodex.length()
                    File(homeDir, ".local/libexec/codex-bin").takeIf { it.exists() }?.delete()
                }

                if (isTermux) {
                    val termuxBin = LocalEnvironmentManager.getBinDir(context)
                    if (termuxBin.exists()) {
                        AgentArchiveImporter.ensureSymlink(localCodex, File(termuxBin, "codex"))
                    }
                }

                onProgress("Import complete! Codex installed at ${localCodex.absolutePath} (${(binarySize / (1024 * 1024)).coerceAtLeast(1)} MB).")
                Log.i(TAG, "Successfully imported Codex to ${localCodex.absolutePath} (wrapper=$needsWrapper)")

                ImportResult(
                    version = finalVersion,
                    targetPath = localCodex.absolutePath,
                    binarySize = binarySize,
                    usedGlibcWrapper = needsWrapper
                )
            } finally {
                extractDir.deleteRecursively()
                tempFile.delete()
            }
        }
    }
}
