package com.example.gemini.ui.claude

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.claude.ClaudeCliJob
import com.example.gemini.data.agent.claude.ClaudeStatus
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.ui.agents.AgentActionPill
import com.example.gemini.ui.agents.AgentSetupPrompt
import com.example.gemini.ui.agents.AgentStatusDot
import com.example.gemini.ui.components.ClaudeAccent

/** Empty Claude chat when Claude Code is not ready: what is wrong and the actions that fix it. */
@Composable
fun ClaudeStatusPrompt(
    state: ClaudeStatus,
    error: String?,
    onSignIn: () -> Unit,
    onSetup: () -> Unit,
    onRestartServer: () -> Unit,
    onRetry: () -> Unit,
    onServerLogs: () -> Unit,
    onImport: (() -> Unit)? = null,
    cliJob: ClaudeCliJob? = null,
    modifier: Modifier = Modifier,
    /** The local server is (re)starting: wait instead of reporting it offline. */
    serverStarting: Boolean = false
) {
    val dot = state.dotColor
    val jobRunning = cliJob?.running == true
    val jobKind = cliJob?.kind.orEmpty().ifBlank { "import" }
    val jobLog = cliJob?.log
    val jobExitCode = cliJob?.exit_code

    if (serverStarting) {
        AgentSetupPrompt(
            agent = AgentKind.CLAUDE,
            title = "Starting the local server…",
            message = "Claude Code will be ready in a moment.",
            modifier = modifier,
            statusColor = ClaudeStatus.CHECKING.dotColor,
            jobKind = jobKind,
            jobRunning = jobRunning,
            jobExitCode = jobExitCode,
            jobLog = jobLog
        )
        return
    }
    when (state) {
        ClaudeStatus.CHECKING -> AgentSetupPrompt(
            agent = AgentKind.CLAUDE,
            title = if (jobRunning) "Importing Claude Code…" else "Checking Claude Code…",
            message = "Looking for the Claude Code CLI and your sign-in.",
            modifier = modifier,
            statusColor = dot,
            jobKind = jobKind,
            jobRunning = jobRunning,
            jobExitCode = jobExitCode,
            jobLog = jobLog
        )
        ClaudeStatus.SIGNED_OUT -> AgentSetupPrompt(
            agent = AgentKind.CLAUDE,
            title = "Sign in to Claude Code",
            message = "Use your Claude Pro or Max subscription, or an Anthropic Console account, to chat with Claude Code.",
            modifier = modifier,
            statusColor = dot,
            actionLabel = "Sign in",
            onAction = onSignIn
        )
        ClaudeStatus.SIGNING_IN -> AgentSetupPrompt(
            agent = AgentKind.CLAUDE,
            title = "Finishing sign-in…",
            message = "Complete the sign-in in your browser, then come back here.",
            modifier = modifier,
            statusColor = dot,
            actionLabel = "Open sign-in",
            onAction = onSignIn
        )
        ClaudeStatus.NOT_INSTALLED -> AgentSetupPrompt(
            agent = AgentKind.CLAUDE,
            title = if (jobRunning) "Setting up Claude Code…" else "Claude Code not found",
            message = "The claude CLI is not installed on this device yet. Install online or import a downloaded archive (.tar.gz, .tar.xz, .zip, or ELF binary).",
            modifier = modifier,
            statusColor = dot,
            actionLabel = "Set up Claude Code",
            onAction = onSetup,
            importLabel = if (onImport != null) "Import Agent" else null,
            onImport = onImport,
            secondaryLabel = "Check again",
            onSecondary = onRetry,
            jobKind = jobKind,
            jobRunning = jobRunning,
            jobExitCode = jobExitCode,
            jobLog = jobLog
        )
        ClaudeStatus.BRIDGE_OUTDATED -> AgentSetupPrompt(
            agent = AgentKind.CLAUDE,
            title = "Restart the local server",
            message = error ?: "The running local server cannot run Claude Code yet. Restart it to load Claude.",
            modifier = modifier,
            statusColor = dot,
            actionLabel = "Restart server",
            onAction = onRestartServer,
            secondaryLabel = "Check again",
            onSecondary = onRetry
        )
        ClaudeStatus.BRIDGE_OFFLINE -> AgentSetupPrompt(
            agent = AgentKind.CLAUDE,
            title = "Local server offline",
            message = "Claude Code runs through the local server. Start it to chat with Claude.",
            modifier = modifier,
            statusColor = dot,
            actionLabel = "Retry",
            onAction = onRetry,
            importLabel = if (onImport != null) "Import Agent" else null,
            onImport = onImport,
            secondaryLabel = "Server logs",
            onSecondary = onServerLogs,
            jobKind = jobKind,
            jobRunning = jobRunning,
            jobExitCode = jobExitCode,
            jobLog = jobLog
        )
        ClaudeStatus.ERROR -> AgentSetupPrompt(
            agent = AgentKind.CLAUDE,
            title = "Couldn't check Claude Code",
            message = (error ?: "The status check failed.") + " You can still send a message or import a binary.",
            modifier = modifier,
            statusColor = dot,
            actionLabel = "Check again",
            onAction = onRetry,
            importLabel = if (onImport != null) "Import Agent" else null,
            onImport = onImport,
            jobKind = jobKind,
            jobRunning = jobRunning,
            jobExitCode = jobExitCode,
            jobLog = jobLog
        )
        ClaudeStatus.READY -> Unit
    }
}

/** One-line notice above the input of a Claude chat that has messages but Claude Code is not ready. */
@Composable
fun ClaudeStatusBanner(
    state: ClaudeStatus,
    onSignIn: () -> Unit,
    onSetup: () -> Unit,
    onRestartServer: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (text, action) = when (state) {
        ClaudeStatus.SIGNED_OUT -> "Claude Code is signed out" to ("Sign in" to onSignIn)
        ClaudeStatus.SIGNING_IN -> "Finishing Claude sign-in…" to ("Open" to onSignIn)
        ClaudeStatus.NOT_INSTALLED -> "Claude Code not found on this device" to ("Set up" to onSetup)
        ClaudeStatus.BRIDGE_OUTDATED -> "Restart the local server to use Claude Code" to ("Restart" to onRestartServer)
        ClaudeStatus.BRIDGE_OFFLINE -> "Local server offline" to ("Retry" to onRetry)
        else -> return
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, state.dotColor.copy(alpha = 0.45f)),
        shadowElevation = 2.dp
    ) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            AgentStatusDot(state.dotColor, null, size = 9.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            AgentActionPill(action.first, ClaudeAccent, action.second)
        }
    }
}

/** Above the input while a sent message waits for the claude process to boot. */
@Composable
fun ClaudeStartupNotice(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = ClaudeAccent.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, ClaudeAccent.copy(alpha = 0.3f))
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 10.dp).size(14.dp),
                strokeWidth = 2.dp,
                color = ClaudeAccent
            )
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "The first message of a chat starts Claude Code, which can take a little while on a phone.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}
