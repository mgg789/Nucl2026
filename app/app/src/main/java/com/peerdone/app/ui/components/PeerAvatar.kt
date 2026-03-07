package com.peerdone.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peerdone.app.ui.theme.PeerDoneLightGray
import com.peerdone.app.ui.theme.PeerDonePrimaryContainer
import com.peerdone.app.ui.theme.PeerDoneTextDark
import com.peerdone.app.ui.theme.PeerDoneWhite

@Composable
fun PeerAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    backgroundColor: Color = PeerDonePrimaryContainer,
    textColor: Color = PeerDoneWhite
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.firstOrNull()?.uppercase() ?: "?",
            fontSize = (size.value / 2.5).sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

@Composable
fun PeerAvatarLight(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    PeerAvatar(
        name = name,
        modifier = modifier,
        size = size,
        backgroundColor = PeerDoneLightGray,
        textColor = PeerDoneTextDark
    )
}
