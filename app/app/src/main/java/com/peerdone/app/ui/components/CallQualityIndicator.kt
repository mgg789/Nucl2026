package com.peerdone.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peerdone.app.core.audio.JitterStats
import com.peerdone.app.ui.theme.PeerDoneDarkGray
import com.peerdone.app.ui.theme.PeerDoneGray
import com.peerdone.app.ui.theme.PeerDonePrimary
import com.peerdone.app.ui.theme.PeerDoneWhite

enum class CallQuality {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR
}

@Composable
fun CallQualityIndicator(
    stats: JitterStats,
    modifier: Modifier = Modifier
) {
    val quality = calculateQuality(stats)
    val (qualityText, qualityColor) = when (quality) {
        CallQuality.EXCELLENT -> "Отлично" to PeerDonePrimary
        CallQuality.GOOD -> "Хорошо" to Color(0xFF4CAF50)
        CallQuality.FAIR -> "Средне" to Color(0xFFFFC107)
        CallQuality.POOR -> "Плохо" to Color(0xFFFF5722)
    }
    
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(PeerDoneDarkGray.copy(alpha = 0.8f))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(4) { index ->
                val barHeight = when (index) {
                    0 -> 8.dp
                    1 -> 12.dp
                    2 -> 16.dp
                    else -> 20.dp
                }
                val isActive = when (quality) {
                    CallQuality.EXCELLENT -> true
                    CallQuality.GOOD -> index < 3
                    CallQuality.FAIR -> index < 2
                    CallQuality.POOR -> index < 1
                }
                
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(barHeight)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isActive) qualityColor else PeerDoneGray.copy(alpha = 0.3f))
                )
            }
        }
        
        Spacer(modifier = Modifier.height(6.dp))
        
        Text(
            text = qualityText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = qualityColor
        )
    }
}

@Composable
fun CallStatsPanel(
    stats: JitterStats,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(PeerDoneDarkGray.copy(alpha = 0.8f))
            .padding(16.dp)
    ) {
        Text(
            text = "Статистика звонка",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = PeerDoneWhite
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        StatRow(
            label = "Пакетов получено",
            value = stats.packetsReceived.toString()
        )
        
        StatRow(
            label = "Потеряно",
            value = "${stats.packetsLost} (${(stats.lossRate * 100).toInt()}%)",
            valueColor = if (stats.lossRate > 0.05f) Color(0xFFFF5722) else PeerDoneGray
        )
        
        StatRow(
            label = "Опоздавших",
            value = stats.packetsLate.toString()
        )
        
        StatRow(
            label = "Буфер",
            value = "${stats.currentBufferSize} пакетов"
        )
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    valueColor: Color = PeerDoneGray,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = PeerDoneGray
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

private fun calculateQuality(stats: JitterStats): CallQuality {
    return when {
        stats.packetsReceived < 10 -> CallQuality.GOOD
        stats.lossRate > 0.15f -> CallQuality.POOR
        stats.lossRate > 0.08f -> CallQuality.FAIR
        stats.lossRate > 0.03f -> CallQuality.GOOD
        else -> CallQuality.EXCELLENT
    }
}
