package com.example.gemini.data.agent.claude

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.gemini.data.agent.AgentArchiveImporter
import com.example.gemini.data.local.LocalEnvironmentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Robust importer for Claude Code agents.
 *
 * Supports importing Claude Code from:
 * - Gzipped tarballs / single binaries (.tar.gz, .tgz, .gz)
 * - XZ-compressed tarballs / single binaries (.tar.xz, .txz, .xz)
 * - Zip archives (.zip)
 * - Uncompressed tar archives (.tar)
 * - Raw standalone ELF binaries or launcher scripts (e.g. 'claude', 'claude-linux-arm64')
 *
 * Inspects the package safely (Zip-Slip guarded), installs to `~/.local/share/claude/versions/<version>`,
 * ensures the Termux glibc loader wrapper / standard symlink routes to it, and prunes stale versions.
 */
object ClaudeImporter {
    private const val TAG = "ClaudeImporter"

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
            val (displayName, tempFile) = AgentArchiveImporter.copyUriToTempFile(context, uri, "claude", onProgress)
            val extractDir = File(context.cacheDir, "claude_extract_${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                val mb = (tempFile.length() / (1024 * 1024)).coerceAtLeast(1)
                onProgress("Inspecting $displayName (~$mb MB)…")

                val format = AgentArchiveImporter.detectArchiveFormat(tempFile)
                var detectedVersion = AgentArchiveImporter.extractSemverFromText(displayName)
                val candidateBinary: File = when (format) {
                    AgentArchiveImporter.ArchiveFormat.RAW_ELF,
                    AgentArchiveImporter.ArchiveFormat.SCRIPT -> {
                        onProgress("Detected direct Claude executable.")
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
                        onProgress("Locating Claude binary inside ${extracted.fileCount} extracted entries…")
                        AgentArchiveImporter.findBinaryCandidate(
                            extractDir,
                            listOf("claude", "claude-linux-arm64", "claude-code", "claude.js")
                        ) ?: throw IllegalStateException("Could not find a Claude executable inside '$displayName'.")
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
                    if (isGlibc) "Detected dynamic glibc ELF binary."
                    else if (AgentArchiveImporter.isElfFile(candidateBinary)) "Detected static ELF binary."
                    else "Detected executable script/bundle."
                )

                val homeDir = AgentArchiveImporter.resolveAgentHomeDir(context)
                val versionsDir = File(homeDir, ".local/share/claude/versions").apply { mkdirs() }
                val finalVersion = resolveVersion(versionsDir, detectedVersion)
                onProgress("Installing Claude Code $finalVersion…")

                val targetFile = File(versionsDir, finalVersion)
                AgentArchiveImporter.atomicInstallBinary(candidateBinary, targetFile)

                if (LocalEnvironmentManager.isTermuxPackage(context)) {
                    LocalEnvironmentManager.ensureClaudeWrapper(context)
                } else {
                    val localBin = File(homeDir, ".local/bin").apply { mkdirs() }
                    AgentArchiveImporter.ensureSymlink(targetFile, File(localBin, "claude"))
                }

                pruneVersions(versionsDir, keep = 2)
                onProgress("Import complete! Claude Code $finalVersion installed (${targetFile.length() / (1024 * 1024)} MB).")
                Log.i(TAG, "Successfully imported Claude Code $finalVersion to ${targetFile.absolutePath}")

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

    private fun resolveVersion(versionsDir: File, detected: String?): String {
        if (AgentArchiveImporter.isStrictSemver(detected)) return detected!!
        val existing = versionsDir.listFiles()
            ?.filter { it.isFile && AgentArchiveImporter.isStrictSemver(it.name) }
            ?.map { it.name }
            .orEmpty()
        if (existing.isEmpty()) return "1.0.0"
        val latest = existing.sortedWith(Comparator { a, b -> AgentArchiveImporter.compareSemver(a, b) }).last()
        val parts = latest.split(".").mapNotNull { it.toIntOrNull() }
        return if (parts.size == 3) "${parts[0]}.${parts[1]}.${parts[2] + 1}" else "2.0.0"
    }

    private fun pruneVersions(versionsDir: File, keep: Int) {
        val files = versionsDir.listFiles()
            ?.filter { it.isFile && AgentArchiveImporter.isStrictSemver(it.name) }
            ?: return
        if (files.size <= keep) return
        val sorted = files.sortedWith(Comparator { a, b -> AgentArchiveImporter.compareSemver(a.name, b.name) })
        sorted.take(files.size - keep).forEach { it.delete() }
    }
}
