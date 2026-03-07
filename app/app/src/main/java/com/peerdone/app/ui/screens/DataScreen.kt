package com.peerdone.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peerdone.app.PeerDoneApplication
import com.peerdone.app.R
import com.peerdone.app.data.CallHistoryStore
import com.peerdone.app.data.ChatHistoryStore
import com.peerdone.app.data.IncomingFileStore
import com.peerdone.app.data.MessageQueueStore
import com.peerdone.app.ui.theme.PeerDoneBackground
import com.peerdone.app.ui.theme.PeerDoneDarkGray
import com.peerdone.app.ui.theme.PeerDoneGray
import com.peerdone.app.ui.theme.PeerDoneInputFieldSolid
import com.peerdone.app.ui.theme.PeerDoneWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DataScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val callHistoryStore = remember { CallHistoryStore(context) }
    val chatHistoryStore = (context.applicationContext as PeerDoneApplication).chatHistoryStore
    val messageQueueStore = remember { MessageQueueStore(context) }
    val incomingFileStore = remember { IncomingFileStore(context) }
    val callHistory by callHistoryStore.history.collectAsState(initial = emptyList())
    val chatByPeer by chatHistoryStore.byPeer.collectAsState(initial = emptyMap())
    var cacheSizeBytes by remember { mutableStateOf<Long?>(null) }
    var incomingFileStats by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var clearedMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        cacheSizeBytes = withContext(Dispatchers.IO) {
            context.cacheDir.walkTopDown().sumOf { it.length() }
        }
    }
    LaunchedEffect(Unit) {
        incomingFileStats = withContext(Dispatchers.IO) {
            val list = incomingFileStore.loadAll()
            val count = list.size
            val totalBytes = list.sumOf { f ->
                f.meta?.totalBytes ?: f.chunks.values.sumOf { it.size.toLong() }
            }
            count to totalBytes
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PeerDoneBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                text = "Локальные данные",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = PeerDoneWhite
            )
        }
        Spacer(modifier = Modifier.height(24.dp))

        clearedMessage?.let { msg ->
            Text(
                text = msg,
                fontSize = 14.sp,
                color = PeerDoneGray,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = PeerDoneInputFieldSolid
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                DataActionRow(
                    iconResId = R.drawable.ic_storage,
                    title = "История чатов",
                    subtitle = run {
                        val chats = chatByPeer.size
                        val messages = chatByPeer.values.sumOf { it.size }
                        if (messages == 0) "Нет сохранённых сообщений" else "Чатов: $chats, сообщений: $messages"
                    },
                    actionLabel = "Очистить",
                    onClick = {
                        scope.launch {
                            chatHistoryStore.clearAll()
                            clearedMessage = "История чатов очищена. Сообщения снова придут через сеть при обмене с узлами."
                        }
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 60.dp, end = 20.dp),
                    thickness = 0.5.dp,
                    color = PeerDoneGray.copy(alpha = 0.3f)
                )
                DataActionRow(
                    iconResId = R.drawable.ic_storage,
                    title = "История звонков",
                    subtitle = "Записей: ${callHistory.size}",
                    actionLabel = "Очистить",
                    onClick = {
                        scope.launch {
                            callHistoryStore.clearHistory()
                            clearedMessage = "История звонков очищена."
                        }
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 60.dp, end = 20.dp),
                    thickness = 0.5.dp,
                    color = PeerDoneGray.copy(alpha = 0.3f)
                )
                DataActionRow(
                    iconResId = R.drawable.ic_storage,
                    title = "Кэш приложения",
                    subtitle = cacheSizeBytes?.let { "≈ ${it / 1024} КБ" } ?: "…",
                    actionLabel = "Очистить",
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                            }
                            cacheSizeBytes = 0L
                            clearedMessage = "Кэш очищен."
                        }
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 60.dp, end = 20.dp),
                    thickness = 0.5.dp,
                    color = PeerDoneGray.copy(alpha = 0.3f)
                )
                DataActionRow(
                    iconResId = R.drawable.ic_storage,
                    title = "Очередь сообщений",
                    subtitle = "Локальная очередь отправки",
                    actionLabel = "Очистить",
                    onClick = {
                        messageQueueStore.clearQueue()
                        clearedMessage = "Очередь сообщений очищена."
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 60.dp, end = 20.dp),
                    thickness = 0.5.dp,
                    color = PeerDoneGray.copy(alpha = 0.3f)
                )
                DataActionRow(
                    iconResId = R.drawable.ic_storage,
                    title = "Временные файлы приёма",
                    subtitle = incomingFileStats?.let { (count, bytes) ->
                        if (count == 0) "Нет несохранённых файлов"
                        else {
                            val sizeStr = when {
                                bytes < 1024 -> "$bytes Б"
                                bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
                                else -> String.format("%.1f МБ", bytes / (1024.0 * 1024.0))
                            }
                            "Файлов: $count, ≈ $sizeStr. Буфер приёма по сети."
                        }
                    } ?: "…",
                    actionLabel = "Очистить",
                    onClick = {
                        scope.launch {
                            incomingFileStore.clearAll()
                            incomingFileStats = 0 to 0L
                            clearedMessage = "Временные данные приёма файлов очищены."
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Сообщения чатов хранятся локально для отображения; при очистке истории они снова подгрузятся с узлов сети при обмене. Очистка затрагивает только локальные кэши, историю звонков и временные файлы приёма.",
            fontSize = 13.sp,
            color = PeerDoneGray,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
private fun DataActionRow(
    iconResId: Int,
    title: String,
    subtitle: String,
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = iconResId),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = PeerDoneDarkGray
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = PeerDoneDarkGray
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = PeerDoneGray
            )
        }
        Text(
            text = actionLabel,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFAE76E9)
        )
    }
}
