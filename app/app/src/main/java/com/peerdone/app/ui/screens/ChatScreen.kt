package com.peerdone.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.peerdone.app.R
import android.content.Intent
import android.webkit.MimeTypeMap
import com.peerdone.app.core.audio.PlaybackState
import com.peerdone.app.core.audio.RecordingState
import com.peerdone.app.core.audio.VoicePlayer
import com.peerdone.app.core.audio.VoiceRecorder
import com.peerdone.app.core.message.OutboundContent
import com.peerdone.app.core.transport.DeliveryClass
import com.peerdone.app.core.transport.StubTransportAdapter
import com.peerdone.app.core.transport.TransportAdapter
import com.peerdone.app.core.transport.TransportHealth
import com.peerdone.app.core.transport.TransportRegistry
import com.peerdone.app.core.transport.TransportType
import com.peerdone.app.data.NearbyTransportAdapter
import com.peerdone.app.di.LocalDeviceIdentity
import com.peerdone.app.di.LocalNearbyClient
import com.peerdone.app.domain.AccessPolicy
import com.peerdone.app.service.SendOrchestrator
import com.peerdone.app.ui.theme.PeerDoneBackground
import com.peerdone.app.ui.theme.PeerDoneDarkGray
import com.peerdone.app.ui.theme.PeerDoneGray
import com.peerdone.app.ui.theme.PeerDoneLightGray
import com.peerdone.app.ui.theme.PeerDonePrimary
import com.peerdone.app.ui.theme.PeerDonePrimaryVariant
import com.peerdone.app.ui.theme.PeerDoneReceivedBubble
import com.peerdone.app.ui.theme.PeerDoneSentBubble
import com.peerdone.app.ui.theme.PeerDoneTextDark
import com.peerdone.app.ui.theme.PeerDoneWhite
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

enum class ChatMessageType {
    TEXT,
    VOICE,
    FILE
}

private data class ChatMessage(
    val id: String,
    val text: String,
    val timestampMs: Long,
    val isOutgoing: Boolean,
    val type: ChatMessageType = ChatMessageType.TEXT,
    val voiceFileId: String? = null,
    val voiceDurationMs: Long = 0,
    val voiceFile: File? = null,
    val fileName: String? = null,
    val filePath: String? = null
)

@OptIn(ExperimentalEncodingApi::class)
@Composable
fun ChatScreen(
    peerId: String,
    onBack: () -> Unit,
    onCallClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val identityStore = LocalDeviceIdentity.current
    val nearbyClient = LocalNearbyClient.current
    val localIdentity = remember { identityStore.getOrCreate() }

    val voiceRecorder = remember { VoiceRecorder(context) }
    val voicePlayer = remember { VoicePlayer(context) }
    val recordingState by voiceRecorder.state.collectAsState()
    val playbackState by voicePlayer.state.collectAsState()
    val playingFileId by voicePlayer.playingFileId.collectAsState()
    
    var recordingDurationMs by remember { mutableStateOf(0L) }
    
    LaunchedEffect(recordingState) {
        while (recordingState == RecordingState.RECORDING) {
            recordingDurationMs = voiceRecorder.getCurrentDurationMs()
            delay(100)
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            voiceRecorder.cancelRecording()
            voicePlayer.release()
        }
    }

    val incoming by nearbyClient.incomingMessages.collectAsState()
    val localMessages = remember { mutableStateListOf<ChatMessage>() }
    var messageDraft by remember { mutableStateOf("") }
    var isRecordingVoice by remember { mutableStateOf(false) }

    val adapters = remember(nearbyClient) {
        listOf<TransportAdapter>(
            NearbyTransportAdapter(nearbyClient),
            StubTransportAdapter(
                type = TransportType.BLUETOOTH_LE,
                staticHealth = TransportHealth(TransportType.BLUETOOTH_LE, false, 250, 800, 7, 4),
            ),
        )
    }
    val adaptersByType = remember(adapters) { adapters.associateBy { it.type } }
    val transportRegistry = remember(adapters) {
        TransportRegistry().apply {
            adapters.forEach { adapter -> register { adapter.health() } }
        }
    }
    val sendOrchestrator = remember(adaptersByType, transportRegistry) {
        SendOrchestrator(adaptersByType, transportRegistry)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
            sendOrchestrator.enqueueFileTransfer(
                sender = localIdentity,
                fileName = fileName,
                mimeType = mimeType,
                bytes = bytes,
                policy = AccessPolicy(),
            )
            localMessages += ChatMessage(
                id = "local_file_${System.currentTimeMillis()}",
                text = "Файл: $fileName (отправлен)",
                timestampMs = System.currentTimeMillis(),
                isOutgoing = true,
                type = ChatMessageType.FILE,
                fileName = fileName
            )
        } catch (_: Exception) {}
    }

    val remoteMessages = remember(incoming, peerId) {
        incoming
            .filter { it.envelope.senderUserId == peerId }
            .map { msg ->
                val isFileWithPath = msg.receivedFilePath != null && msg.receivedFileName != null
                ChatMessage(
                    id = msg.envelope.id,
                    text = msg.contentSummary,
                    timestampMs = msg.envelope.timestampMs,
                    isOutgoing = false,
                    type = if (isFileWithPath) ChatMessageType.FILE else ChatMessageType.TEXT,
                    fileName = msg.receivedFileName,
                    filePath = msg.receivedFilePath
                )
            }
    }

    val allMessages = remember(remoteMessages, localMessages.toList()) {
        (remoteMessages + localMessages).sortedBy { it.timestampMs }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(allMessages.size) {
        if (allMessages.isNotEmpty()) {
            listState.animateScrollToItem(allMessages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PeerDoneBackground)
            .imePadding()
    ) {
        ChatHeader(
            peerName = peerId.take(16),
            isOnline = true,
            onBack = onBack,
            onCallClick = onCallClick
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            state = listState,
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "6 марта",
                        color = PeerDoneGray,
                        fontSize = 13.sp
                    )
                }
            }

            items(allMessages) { message ->
                when (message.type) {
                    ChatMessageType.VOICE -> VoiceMessageBubble(
                        durationMs = message.voiceDurationMs,
                        timestamp = message.timestampMs.toTimeString(),
                        isOutgoing = message.isOutgoing,
                        isPlaying = playingFileId == message.voiceFileId && playbackState == PlaybackState.PLAYING,
                        onPlayPause = {
                            message.voiceFile?.let { file ->
                                if (playingFileId == message.voiceFileId && playbackState == PlaybackState.PLAYING) {
                                    voicePlayer.pause()
                                } else {
                                    voicePlayer.play(file, message.voiceFileId ?: "")
                                }
                            }
                        }
                    )
                    ChatMessageType.FILE -> FileMessageBubble(
                        fileName = message.fileName ?: message.text,
                        timestamp = message.timestampMs.toTimeString(),
                        isOutgoing = message.isOutgoing,
                        filePath = message.filePath,
                        onOpenFile = { path ->
                            path?.let { openFileWithExternalApp(context, it, message.fileName) }
                        }
                    )
                    else -> MessageBubble(
                        message = message.text,
                        timestamp = message.timestampMs.toTimeString(),
                        isOutgoing = message.isOutgoing
                    )
                }
            }
        }

        if (isRecordingVoice) {
            VoiceRecordingBar(
                durationMs = recordingDurationMs,
                onCancel = {
                    voiceRecorder.cancelRecording()
                    isRecordingVoice = false
                    recordingDurationMs = 0
                },
                onSend = {
                    val result = voiceRecorder.stopRecording()
                    isRecordingVoice = false
                    recordingDurationMs = 0
                    
                    if (result != null) {
                        val fileBytes = result.file.readBytes()
                        val base64 = Base64.encode(fileBytes)
                        
                        val policy = AccessPolicy()
                        sendOrchestrator.enqueueAndTrySend(
                            sender = localIdentity,
                            content = OutboundContent.VoiceNoteMeta(
                                fileId = result.fileId,
                                durationMs = result.durationMs,
                                codec = result.codec
                            ),
                            policy = policy,
                            deliveryClass = DeliveryClass.INTERACTIVE,
                        )
                        
                        localMessages += ChatMessage(
                            id = "local_voice_${System.currentTimeMillis()}",
                            text = "Голосовое сообщение",
                            timestampMs = System.currentTimeMillis(),
                            isOutgoing = true,
                            type = ChatMessageType.VOICE,
                            voiceFileId = result.fileId,
                            voiceDurationMs = result.durationMs,
                            voiceFile = result.file
                        )
                    }
                }
            )
        } else {
            ChatInputBar(
                value = messageDraft,
                onValueChange = { messageDraft = it },
                onSend = {
                    if (messageDraft.isNotBlank()) {
                        val policy = AccessPolicy()
                        sendOrchestrator.enqueueAndTrySend(
                            sender = localIdentity,
                            content = OutboundContent.Text(messageDraft),
                            policy = policy,
                            deliveryClass = DeliveryClass.INTERACTIVE,
                        )
                        localMessages += ChatMessage(
                            id = "local_${System.currentTimeMillis()}",
                            text = messageDraft,
                            timestampMs = System.currentTimeMillis(),
                            isOutgoing = true
                        )
                        messageDraft = ""
                    }
                },
                onMicClick = {
                    if (voiceRecorder.startRecording()) {
                        isRecordingVoice = true
                    }
                },
                onAttach = { filePickerLauncher.launch("*/*") }
            )
        }
    }
}

@Composable
private fun ChatHeader(
    peerName: String,
    isOnline: Boolean,
    onBack: () -> Unit,
    onCallClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = PeerDoneWhite,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_back),
                    contentDescription = "Назад",
                    modifier = Modifier.size(24.dp),
                    tint = PeerDoneTextDark
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(PeerDoneLightGray),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = peerName.firstOrNull()?.uppercase() ?: "?",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PeerDoneDarkGray
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peerName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = PeerDoneTextDark
                )
                Text(
                    text = if (isOnline) "В сети" else "Не в сети",
                    fontSize = 13.sp,
                    color = PeerDonePrimaryVariant
                )
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(PeerDoneLightGray)
                    .clickable(onClick = onCallClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_call),
                    contentDescription = "Call",
                    modifier = Modifier.size(20.dp),
                    tint = PeerDoneDarkGray
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(PeerDoneLightGray),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_more),
                    contentDescription = "More",
                    modifier = Modifier.size(20.dp),
                    tint = PeerDoneDarkGray
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: String,
    timestamp: String,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier
) {
    val bubbleShape = RoundedCornerShape(
        topStart = 30.dp,
        topEnd = 0.dp,
        bottomEnd = 30.dp,
        bottomStart = 30.dp
    )

    val bubbleColor = if (isOutgoing) PeerDoneSentBubble else PeerDoneReceivedBubble

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(bubbleShape)
                .background(bubbleColor)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Text(
                text = message,
                color = PeerDoneTextDark,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
        }
    }
}

private fun openFileWithExternalApp(context: android.content.Context, filePath: String, fileName: String?) {
    val file = File(filePath)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val mime = fileName?.let { name ->
        val ext = name.substringAfterLast('.', "")
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    } ?: "application/octet-stream"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: Exception) {}
}

@Composable
private fun FileMessageBubble(
    fileName: String,
    timestamp: String,
    isOutgoing: Boolean,
    filePath: String?,
    onOpenFile: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val bubbleShape = RoundedCornerShape(
        topStart = 30.dp,
        topEnd = 0.dp,
        bottomEnd = 30.dp,
        bottomStart = 30.dp
    )
    val bubbleColor = if (isOutgoing) PeerDoneSentBubble else PeerDoneReceivedBubble

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(bubbleShape)
                .background(bubbleColor)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Column {
                Text(
                    text = fileName,
                    color = PeerDoneTextDark,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                if (isOutgoing) {
                    Text(
                        text = "Отправлен",
                        fontSize = 12.sp,
                        color = PeerDoneGray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (filePath != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = PeerDonePrimary.copy(alpha = 0.2f),
                        onClick = { onOpenFile(filePath) }
                    ) {
                        Text(
                            text = "Открыть",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = PeerDonePrimary,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceMessageBubble(
    durationMs: Long,
    timestamp: String,
    isOutgoing: Boolean,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bubbleShape = RoundedCornerShape(
        topStart = 30.dp,
        topEnd = 0.dp,
        bottomEnd = 30.dp,
        bottomStart = 30.dp
    )

    val bubbleColor = if (isOutgoing) PeerDoneSentBubble else PeerDoneReceivedBubble
    val durationText = formatDuration(durationMs)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 160.dp, max = 280.dp)
                .clip(bubbleShape)
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(PeerDonePrimary)
                        .clickable(onClick = onPlayPause),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        ),
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(20.dp),
                        tint = PeerDoneWhite
                    )
                }
                
                Column(modifier = Modifier.weight(1f)) {
                    LinearProgressIndicator(
                        progress = { if (isPlaying) 0.5f else 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = PeerDonePrimary,
                        trackColor = PeerDoneGray.copy(alpha = 0.3f),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = durationText,
                        fontSize = 12.sp,
                        color = PeerDoneGray
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceRecordingBar(
    durationMs: Long,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val durationText = formatDuration(durationMs)
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = PeerDoneWhite,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF4444))
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_stop),
                    contentDescription = "Cancel",
                    modifier = Modifier.size(20.dp),
                    tint = PeerDoneWhite
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF4444))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Запись: $durationText",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = PeerDoneTextDark
                )
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(PeerDonePrimary)
                    .clickable(onClick = onSend),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_send),
                    contentDescription = "Send",
                    modifier = Modifier.size(20.dp),
                    tint = PeerDoneWhite
                )
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicClick: () -> Unit,
    onAttach: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = PeerDoneWhite,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(PeerDonePrimary)
                    .clickable(onClick = onAttach),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_plus),
                    contentDescription = "Attach",
                    modifier = Modifier.size(24.dp),
                    tint = PeerDoneWhite
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(Color(0x4DA6A6A6))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = "Отправьте сообщение",
                        color = PeerDoneGray,
                        fontSize = 15.sp
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        color = PeerDoneTextDark,
                        fontSize = 15.sp
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(PeerDonePrimary)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            if (value.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(PeerDonePrimary)
                        .clickable(onClick = onSend),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_send),
                        contentDescription = "Send",
                        modifier = Modifier.size(20.dp),
                        tint = PeerDoneWhite
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(PeerDonePrimary)
                        .clickable(onClick = onMicClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_mic),
                        contentDescription = "Record voice",
                        modifier = Modifier.size(20.dp),
                        tint = PeerDoneWhite
                    )
                }
            }
        }
    }
}

private fun Long.toTimeString(): String {
    val format = SimpleDateFormat("HH:mm", Locale.getDefault())
    return format.format(Date(this))
}
