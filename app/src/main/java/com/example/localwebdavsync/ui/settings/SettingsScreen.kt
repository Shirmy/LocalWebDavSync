package com.example.localwebdavsync.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.localwebdavsync.R
import com.example.localwebdavsync.util.FileManagePermissionState

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val hasFileManagePermission by FileManagePermissionState.granted.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = Color.White, contentColor = Color.Black) {
        CompositionLocalProvider(LocalContentColor provides Color.Black) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Divider(color = Color.Black)

                SettingsSection(title = stringResource(R.string.settings_webdav_section)) {
                    SettingsTextInput(
                        value = state.baseUrl,
                        onValueChange = viewModel::updateBaseUrl,
                        label = stringResource(R.string.field_webdav_address),
                        keyboardType = KeyboardType.Uri
                    )
                    SettingsTextInput(
                        value = state.username,
                        onValueChange = viewModel::updateUsername,
                        label = stringResource(R.string.field_username)
                    )
                    SettingsTextInput(
                        value = state.appPassword,
                        onValueChange = viewModel::updateAppPassword,
                        label = stringResource(R.string.field_app_password)
                    )
                    Text(
                        text = stringResource(R.string.settings_password_tip),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    ResponsiveButtonRow {
                        InkButton(
                            text = if (state.testing) stringResource(R.string.action_testing) else stringResource(R.string.action_test_connection),
                            onClick = viewModel::testConnection,
                            enabled = !state.testing
                        )
                    }
                    if (state.testMessage != null) {
                        SettingsMessageBox(settingsMessage(state.testMessage))
                    }
                    if (state.saveMessage != null) {
                        SettingsMessageBox(settingsMessage(state.saveMessage))
                    }
                }

                SettingsSection(
                    title = stringResource(R.string.settings_debug_logs),
                    trailing = {
                        Switch(
                            checked = state.detailedDebugLogsEnabled,
                            onCheckedChange = viewModel::updateDetailedDebugLogsEnabled,
                            colors = inkSwitchColors()
                        )
                    }
                )

                SettingsSection(
                    title = stringResource(R.string.settings_auto_scan_on_start),
                    trailing = {
                        Switch(
                            checked = state.autoScanAndSyncOnAppStart,
                            onCheckedChange = viewModel::updateAutoScanAndSyncOnAppStart,
                            colors = inkSwitchColors()
                        )
                    }
                )

                SettingsSection(title = stringResource(R.string.settings_more_actions)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        InkButton(
                            text = stringResource(R.string.settings_grant_file_permission),
                            onClick = { context.openManageFilesPermissionSettings() },
                            enabled = !hasFileManagePermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        )
                        Text(
                            text = if (hasFileManagePermission) stringResource(R.string.settings_permission_granted) else stringResource(R.string.settings_permission_not_granted),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                ResponsiveButtonRow {
                    InkButton(text = stringResource(R.string.action_back), onClick = onBack)
                }
            }
        }
    }
}

@Composable
private fun SettingsTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = Color.Black) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.Black),
        shape = RoundedCornerShape(8.dp),
        colors = inkTextFieldColors()
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResponsiveButtonRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = { content() }
    )
}

@Composable
private fun SettingsSection(
    title: String,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable (ColumnScope.() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(2.dp, Color.Black),
        colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                trailing?.invoke()
            }
            if (content != null) {
                content()
            }
        }
    }
}

@Composable
private fun InkButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(2.dp, Color.Black),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color.Black,
            disabledContainerColor = Color.White,
            disabledContentColor = Color.Black
        )
    ) {
        Text(
            text = text,
            color = Color.Black,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsMessageBox(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.Black),
        colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Black,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun inkTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.Black,
    unfocusedTextColor = Color.Black,
    disabledTextColor = Color.Black,
    focusedLabelColor = Color.Black,
    unfocusedLabelColor = Color.Black,
    disabledLabelColor = Color.Black,
    focusedBorderColor = Color.Black,
    unfocusedBorderColor = Color.Black,
    disabledBorderColor = Color.Black,
    cursorColor = Color.Black
)

@Composable
private fun inkSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.Black,
    checkedTrackColor = Color.White,
    checkedBorderColor = Color.Black,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = Color.White,
    uncheckedBorderColor = Color.Black
)

@Composable
private fun settingsMessage(message: SettingsMessage?): String {
    return when (message?.type) {
        SettingsMessageType.SETTINGS_SAVED -> stringResource(R.string.settings_saved)
        SettingsMessageType.TEST_SUCCESS -> stringResource(R.string.webdav_test_success)
        SettingsMessageType.ADDRESS_ERROR -> stringResource(R.string.webdav_address_error)
        SettingsMessageType.AUTH_ERROR -> stringResource(R.string.webdav_auth_error)
        SettingsMessageType.NETWORK_ERROR -> stringResource(R.string.webdav_network_error)
        SettingsMessageType.SERVER_ERROR -> stringResource(R.string.webdav_server_error, message.httpStatusCode ?: 0)
        null -> ""
    }
}

private fun Context.openManageFilesPermissionSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    FileManagePermissionState.refresh()
    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
        data = Uri.parse("package:$packageName")
    }
    try {
        startActivity(intent)
    } catch (_: Exception) {
        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}
