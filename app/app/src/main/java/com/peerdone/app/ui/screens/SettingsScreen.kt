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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peerdone.app.R
import com.peerdone.app.data.DeviceIdentityStore
import com.peerdone.app.data.PreferencesStore
import com.peerdone.app.ui.theme.PeerDoneBackground
import com.peerdone.app.ui.theme.PeerDoneInputFieldSolid
import com.peerdone.app.ui.theme.PeerDonePrimary
import com.peerdone.app.ui.theme.PeerDoneTextMuted
import com.peerdone.app.ui.theme.PeerDoneTextPrimary
import com.peerdone.app.ui.theme.PeerDoneTextSecondary
import com.peerdone.app.ui.theme.PeerDoneWhite

data class SettingsSection(
    val iconResId: Int,
    val title: String,
    val subtitle: String
)

private val settingsSections = listOf(
    SettingsSection(
        iconResId = R.drawable.ic_account,
        title = "Аккаунт",
        subtitle = "Описание, никнейм, фото профиля"
    ),
    SettingsSection(
        iconResId = R.drawable.ic_lock,
        title = "Сеть",
        subtitle = "Статус, шифрование, сеть"
    ),
    SettingsSection(
        iconResId = R.drawable.ic_storage,
        title = "Локальные данные",
        subtitle = "История, чаты, кэш"
    )
)

@Composable
fun SettingsScreen(
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val identityStore = remember { DeviceIdentityStore(context) }
    val preferencesStore = remember { PreferencesStore(context) }
    val localIdentity = remember { identityStore.getOrCreate() }
    val firstName by preferencesStore.userFirstName.collectAsState(initial = "")
    val lastName by preferencesStore.userLastName.collectAsState(initial = "")
    val nickname by preferencesStore.userNickname.collectAsState(initial = "")

    val displayName = listOf(firstName, lastName, nickname).filter { it.isNotBlank() }.joinToString(" ").ifBlank { localIdentity.userId.take(12) + "…" }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PeerDoneBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(60.dp))

        Text(
            text = "PeerDone",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFFAE76E9),
                            Color(0xFFA5B4E9)
                        )
                    ),
                    shape = RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
            color = PeerDoneWhite
        )

        Spacer(modifier = Modifier.height(30.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onProfileClick),
            shape = RoundedCornerShape(24.dp),
            color = PeerDoneInputFieldSolid
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(PeerDonePrimary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = displayName.firstOrNull()?.uppercase() ?: "?",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PeerDoneWhite
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Профиль",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = PeerDoneTextPrimary
                    )
                    Text(
                        text = displayName,
                        fontSize = 14.sp,
                        color = PeerDoneTextSecondary
                    )
                }
                Icon(
                    painter = painterResource(id = R.drawable.ic_chevron_right),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = PeerDoneTextMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(40.dp),
            color = PeerDoneInputFieldSolid
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                settingsSections.forEachIndexed { index, section ->
                    SettingsSectionRow(
                        section = section,
                        onClick = if (index == 0) onProfileClick else { {} }
                    )
                    if (index < settingsSections.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 60.dp, end = 20.dp),
                            thickness = 0.5.dp,
                            color = PeerDoneTextMuted.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = PeerDoneInputFieldSolid.copy(alpha = 0.8f)
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_lock),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = PeerDonePrimary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Сквозное шифрование",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PeerDoneTextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "PeerDone использует сквозное шифрование для всех сообщений. Это означает, что только вы и получатель можете читать содержимое.",
                    fontSize = 13.sp,
                    color = PeerDoneTextSecondary,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Сервер физически не может сохранять данные чатов, аккаунтов и звонков.",
                    fontSize = 13.sp,
                    color = PeerDoneTextMuted,
                    lineHeight = 20.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "PeerDone v1.0.0",
            fontSize = 12.sp,
            color = PeerDoneTextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(120.dp))
    }
}

@Composable
private fun SettingsSectionRow(
    section: SettingsSection,
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
            painter = painterResource(id = section.iconResId),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = PeerDonePrimary
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = section.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = PeerDoneTextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = section.subtitle,
                fontSize = 13.sp,
                color = PeerDoneTextSecondary
            )
        }

        Icon(
            painter = painterResource(id = R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = PeerDoneTextMuted
        )
    }
}
