package com.peerdone.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.peerdone.app.R
import com.peerdone.app.data.DeviceIdentityStore
import com.peerdone.app.data.PreferencesStore
import com.peerdone.app.ui.theme.PeerDoneBackground
import com.peerdone.app.ui.theme.PeerDoneGradientEnd
import com.peerdone.app.ui.theme.PeerDoneGradientStart
import com.peerdone.app.ui.theme.PeerDoneGray
import com.peerdone.app.ui.theme.PeerDoneInputFieldSolid
import com.peerdone.app.ui.theme.PeerDoneOnline
import com.peerdone.app.ui.theme.PeerDonePrimary
import com.peerdone.app.ui.theme.PeerDoneTextDark
import com.peerdone.app.ui.theme.PeerDoneWhite

@Composable
fun ProfileSetupScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    isEditing: Boolean = false
) {
    val context = LocalContext.current
    val identityStore = remember { DeviceIdentityStore(context) }
    val preferencesStore = remember { PreferencesStore(context) }
    val identity = remember { identityStore.getOrCreate() }
    val coroutineScope = rememberCoroutineScope()

    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    LaunchedEffect(isEditing, preferencesStore) {
        if (isEditing) {
            firstName = preferencesStore.userFirstName.first()
            lastName = preferencesStore.userLastName.first()
            nickname = preferencesStore.userNickname.first()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PeerDoneBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(60.dp))

            Text(
                text = "PeerDone",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(PeerDoneGradientStart, PeerDoneGradientEnd)
                    ),
                    shape = RoundedCornerShape(4.dp)
                ).padding(horizontal = 8.dp),
                color = PeerDoneWhite
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = if (isEditing) "Редактировать профиль" else "Ваш профиль",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = PeerDoneWhite
            )

            Spacer(modifier = Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(PeerDoneInputFieldSolid),
                contentAlignment = Alignment.Center
            ) {
                if (firstName.isNotEmpty()) {
                    Text(
                        text = firstName.first().uppercase(),
                        fontSize = 40.sp,
                        color = PeerDoneTextDark
                    )
                } else {
                    androidx.compose.material3.Icon(
                        painter = painterResource(id = R.drawable.ic_account),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = PeerDoneGray
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            ProfileTextField(
                value = firstName,
                onValueChange = { firstName = it },
                placeholder = "Имя"
            )

            Spacer(modifier = Modifier.height(16.dp))

            ProfileTextField(
                value = lastName,
                onValueChange = { lastName = it },
                placeholder = "Фамилия"
            )

            Spacer(modifier = Modifier.height(16.dp))

            ProfileTextField(
                value = nickname,
                onValueChange = { nickname = it },
                placeholder = "Никнейм"
            )

            Spacer(modifier = Modifier.height(16.dp))

            ProfileTextField(
                value = description,
                onValueChange = { description = it },
                placeholder = "Описание",
                singleLine = false,
                maxLines = 3
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Ваш персональный ID был успешно сгенерирован",
                fontSize = 14.sp,
                color = PeerDoneOnline,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = identity.userId.take(16) + "...",
                fontSize = 12.sp,
                color = PeerDoneGray,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    coroutineScope.launch {
                        preferencesStore.saveUserProfile(firstName, lastName, nickname)
                    }
                    onComplete()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PeerDonePrimary
                ),
                enabled = firstName.isNotBlank() || isEditing
            ) {
                Text(
                    text = if (isEditing) "Сохранить" else "Найдите друзей",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PeerDoneWhite
                )
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
private fun ProfileTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    maxLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = {
            Text(
                text = placeholder,
                color = PeerDoneGray
            )
        },
        shape = RoundedCornerShape(40.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = PeerDoneInputFieldSolid,
            focusedContainerColor = PeerDoneInputFieldSolid,
            unfocusedBorderColor = Color.Transparent,
            focusedBorderColor = PeerDonePrimary,
            unfocusedTextColor = PeerDoneTextDark,
            focusedTextColor = PeerDoneTextDark,
            cursorColor = PeerDonePrimary
        ),
        singleLine = singleLine,
        maxLines = maxLines,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
    )
}
