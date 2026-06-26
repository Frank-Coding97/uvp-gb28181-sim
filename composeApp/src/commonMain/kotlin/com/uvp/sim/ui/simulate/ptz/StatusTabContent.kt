package com.uvp.sim.ui.simulate.ptz

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.DeviceControlDto

/**
 * 状态 Tab — 录像 / 布防 / 报警 / 重启 4 个大状态灯.
 */
@Composable
internal fun StatusTabContent(state: DeviceControlDto) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigStatusLamp(
                label = "录像",
                active = state.isRecording,
                activeColor = UvpColor.Danger,
                modifier = Modifier.weight(1f),
            )
            BigStatusLamp(
                label = "布防",
                active = state.isGuarded,
                activeColor = UvpColor.Success,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigStatusLamp(
                label = "报警",
                active = state.isAlarming,
                activeColor = UvpColor.Warning,
                modifier = Modifier.weight(1f),
            )
            BigStatusLamp(
                label = "重启",
                active = state.isRebooting,
                activeColor = UvpColor.Info,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BigStatusLamp(
    label: String,
    active: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier,
) {
    val pulse by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "lamp-pulse-$label",
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) activeColor.copy(alpha = 0.10f) else UvpColor.Bg)
            .border(
                1.dp,
                if (active) activeColor.copy(alpha = 0.35f) else UvpColor.BorderLight,
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(if (active) activeColor else UvpColor.Border)
                .graphicsLayer { scaleX = 1f + pulse * 0.1f; scaleY = 1f + pulse * 0.1f }
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                label,
                fontSize = 11.sp,
                color = if (active) activeColor else UvpColor.TextSecondary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (active) "已开启" else "未开启",
                fontSize = 9.sp,
                color = if (active) activeColor.copy(alpha = 0.85f) else UvpColor.TextHint,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
