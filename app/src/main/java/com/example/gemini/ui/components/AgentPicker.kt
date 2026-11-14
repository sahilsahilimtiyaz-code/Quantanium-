package com.example.gemini.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.AgentKind

val ClaudeAccent = Color(0xFFD97757)

/** Antigravity's color across the app (same blue as its settings group). */
val AgyAccent = Color(0xFF3B82F6)

/** OpenCode's color across the app. */
val OpenCodeAccent = Color(0xFF10B981)

/** Codex's color across the app. */
val CodexAccent = Color(0xFF8B5CF6)

fun AgentKind.label(): String = when (this) {
    AgentKind.AGY -> "Antigravity"
    AgentKind.CLAUDE -> "Claude Code"
    AgentKind.OPENCODE -> "OpenCode"
    AgentKind.CODEX -> "Codex"
}

fun AgentKind.accent(): Color = when (this) {
    AgentKind.AGY -> AgyAccent
    AgentKind.CLAUDE -> ClaudeAccent
    AgentKind.OPENCODE -> OpenCodeAccent
    AgentKind.CODEX -> CodexAccent
}

/**
 * Segmented picker for the agent of a new chat. Shows only [agents] (the enabled ones) and nothing when there is
 * a single one; [statusColors] adds each agent's status dot.
 */
@Composable
fun AgentPicker(
    selected: AgentKind,
    onSelect: (AgentKind) -> Unit,
    modifier: Modifier = Modifier,
    agents: Collection<AgentKind> = AgentKind.entries,
    statusColors: Map<AgentKind, Color> = emptyMap()
) {
    if (agents.size < 2) return
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.24f), shape)
            .horizontalScroll(rememberScrollState())
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AgentKind.entries.filter { it in agents }.forEach { agent ->
            val isSelected = agent == selected
            val accent = agent.accent()
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) accent.copy(alpha = 0.16f) else Color.Transparent,
                animationSpec = tween(QuantaniumMotion.QuickDurationMs),
                label = "agent_pill_bg"
            )
            val borderColor by animateColorAsState(
                targetValue = if (isSelected) accent.copy(alpha = 0.35f) else Color.Transparent,
                animationSpec = tween(QuantaniumMotion.QuickDurationMs),
                label = "agent_pill_border"
            )
            val labelColor by animateColorAsState(
                targetValue = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(QuantaniumMotion.QuickDurationMs),
                label = "agent_pill_text"
            )
            Row(
                modifier = Modifier
                    .tactilePress(pressedScale = 0.955f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(bgColor)
                    .border(1.dp, borderColor, RoundedCornerShape(18.dp))
                    .clickable { onSelect(agent) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                statusColors[agent]?.let { dot ->
                    val animDot by animateColorAsState(dot, tween(220), label = "agent_picker_dot")
                    Box(Modifier.size(7.dp).clip(CircleShape).background(animDot))
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    text = agent.label(),
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = labelColor,
                    maxLines = 1
                )
            }
        }
    }
}

/** Small badge marking a Claude Code conversation in lists. */
@Composable
fun ClaudeBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(ClaudeAccent.copy(alpha = 0.16f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(text = "Claude", fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = ClaudeAccent)
    }
}
