package com.peerdone.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peerdone.app.R
import com.peerdone.app.core.logging.AppLogger
import com.peerdone.app.core.logging.LogEntry
import com.peerdone.app.core.logging.LogLevel
import com.peerdone.app.ui.theme.PeerDoneBackground
import com.peerdone.app.ui.theme.PeerDoneError
import com.peerdone.app.ui.theme.PeerDoneGray
import com.peerdone.app.ui.theme.PeerDoneInputFieldSolid
import com.peerdone.app.ui.theme.PeerDonePrimary
import com.peerdone.app.ui.theme.PeerDoneTextMuted
import com.peerdone.app.ui.theme.PeerDoneTextPrimary
import com.peerdone.app.ui.theme.PeerDoneTextSecondary
import com.peerdone.app.ui.theme.PeerDoneWhite
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val entries by AppLogger.entries.collectAsState(initial = emptyList())
    var levelFilter by remember { mutableStateOf<LogLevel?>(null) }
    val listState = rememberLazyListState()

    val filtered = remember(entries, levelFilter) {
        if (levelFilter == null) entries
        else entries.filter { it.level == levelFilter }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PeerDoneBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_back),
                    contentDescription = "Назад",
                    modifier = Modifier.size(28.dp),
                    tint = PeerDoneGray
                )
            }
            Text(
                text = "Логи",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = PeerDoneTextPrimary
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { AppLogger.clear() }) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_stop),
                    contentDescription = "Очистить",
                    modifier = Modifier.size(24.dp),
                    tint = PeerDoneError
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = levelFilter == null,
                onClick = { levelFilter = null },
                label = { Text("Все") }
            )
            LogLevel.entries.forEach { level ->
                FilterChip(
                    selected = levelFilter == level,
                    onClick = { levelFilter = level },
                    label = { Text(level.letter.toString()) }
                )
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            color = PeerDoneInputFieldSolid
        ) {
            if (filtered.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Логи пока пусты",
                        fontSize = 16.sp,
                        color = PeerDoneTextMuted
                    )
                    Text(
                        text = "Действия в приложении появятся здесь",
                        fontSize = 14.sp,
                        color = PeerDoneTextMuted,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true
                ) {
                    items(
                        items = filtered.reversed(),
                        key = { "${it.timestamp}-${it.tag}-${it.message.take(50)}" }
                    ) { entry ->
                        LogRow(entry = entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(
    entry: LogEntry,
    modifier: Modifier = Modifier
) {
    val timeStr = remember(entry.timestamp) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.timestamp))
    }
    val levelColor = when (entry.level) {
        LogLevel.VERBOSE -> PeerDoneTextMuted
        LogLevel.DEBUG -> PeerDoneTextSecondary
        LogLevel.INFO -> PeerDonePrimary
        LogLevel.WARN -> Color(0xFFE65100)
        LogLevel.ERROR -> PeerDoneError
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = entry.level.letter.toString(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = levelColor,
            modifier = Modifier.width(14.dp)
        )
        Text(
            text = timeStr,
            fontSize = 10.sp,
            color = PeerDoneTextMuted,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(72.dp)
        )
        if (entry.tag.isNotBlank()) {
            Text(
                text = "${entry.tag} ",
                fontSize = 11.sp,
                color = PeerDoneTextSecondary,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.widthIn(max = 120.dp)
            )
        }
        Text(
            text = entry.message,
            fontSize = 12.sp,
            color = PeerDoneTextPrimary,
            fontFamily = FontFamily.Monospace
        )
    }
}
