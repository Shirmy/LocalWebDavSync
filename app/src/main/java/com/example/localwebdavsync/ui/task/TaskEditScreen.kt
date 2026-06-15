package com.example.localwebdavsync.ui.task

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.localwebdavsync.R
import com.example.localwebdavsync.data.entity.DeleteModes
import com.example.localwebdavsync.webdav.WebDavFolder
import java.io.File

@Composable
fun TaskEditScreen(
    viewModel: TaskEditViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    val isExistingTask = (state.taskId ?: 0L) > 0L

    LaunchedEffect(state.saved) {
        if (state.saved) {
            onBack()
        }
    }

    // 打开或关闭文件夹选择对话框时清除焦点，避免键盘焦点把页面滚回任务名称输入框。
    LaunchedEffect(state.localBrowserOpen, state.remoteBrowserOpen) {
        focusManager.clearFocus(force = true)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.White, contentColor = Color.Black) {
        CompositionLocalProvider(LocalContentColor provides Color.Black) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = if (isExistingTask) stringResource(R.string.task_edit_title) else stringResource(R.string.task_new_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Divider(color = Color.Black)
            FramedSection {
                InkTextInput(
                    value = state.name,
                    onValueChange = viewModel::updateName,
                    label = stringResource(R.string.field_task_name),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (!isExistingTask) {
                LocalFolderPickerSection(
                    selectedPath = state.localRootPath,
                    onBrowse = {
                        focusManager.clearFocus(force = true)
                        viewModel.openLocalBrowser(state.localRootPath)
                    }
                )
                RemoteFolderPickerSection(
                    selectedPath = state.remoteRootPath,
                    onBrowse = {
                        focusManager.clearFocus(force = true)
                        viewModel.openRemoteBrowser()
                    }
                )
            } else {
                ReadOnlyPathSection(
                    title = stringResource(R.string.field_local_folder),
                    path = state.localRootPath
                        .ifBlank { state.localDisplayName }
                        .ifBlank { state.localRootUri }
                        .ifBlank { stringResource(R.string.task_edit_no_local_folder) }
                )
                ReadOnlyPathSection(
                    title = stringResource(R.string.field_remote_folder),
                    path = state.remoteRootPath.ifBlank { stringResource(R.string.task_edit_no_webdav_folder) }
                )
            }
            ToggleRow(
                title = stringResource(R.string.task_wifi_only_title),
                description = stringResource(R.string.task_wifi_only_desc),
                checked = state.wifiOnly,
                onCheckedChange = viewModel::updateWifiOnly
            )
            DeleteModePicker(
                value = state.deleteMode,
                onValueChange = viewModel::updateDeleteMode
            )
            if (state.error != null) {
                Text(
                    text = taskEditErrorMessage(state.error),
                    color = Color.Black,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            ResponsiveButtonRow {
                InkButton(
                    text = if (state.saving) stringResource(R.string.action_saving) else stringResource(R.string.action_save),
                    onClick = viewModel::save,
                    enabled = !state.saving
                )
                InkButton(text = stringResource(R.string.action_cancel), onClick = onBack)
            }
        }
        }
    }

    if (!isExistingTask && state.remoteBrowserOpen) {
        RemoteFolderBrowserDialog(
            currentPath = state.remoteBrowserPath,
            folders = state.remoteFolders,
            loading = state.remoteBrowserLoading,
            creatingFolder = state.creatingRemoteFolder,
            canUseCurrent = state.remoteBrowserPathLoaded,
            message = state.remoteBrowserMessage,
            newFolderName = state.newRemoteFolderName,
            createFolderOpen = state.remoteCreateFolderOpen,
            createFolderMessage = state.remoteCreateFolderMessage,
            onNewFolderNameChange = viewModel::updateNewRemoteFolderName,
            onOpenCreateFolder = viewModel::openCreateRemoteFolderDialog,
            onCreateFolder = viewModel::createRemoteFolder,
            onDismissCreateFolder = viewModel::closeCreateRemoteFolderDialog,
            onEnterFolder = viewModel::enterRemoteFolder,
            onParent = viewModel::goRemoteParent,
            onUseCurrent = viewModel::chooseCurrentRemoteFolder,
            onDismiss = viewModel::closeRemoteBrowser
        )
    }
    if (!isExistingTask && state.localBrowserOpen) {
        LocalFolderBrowserDialog(
            currentPath = state.localBrowserPath,
            folders = state.localFolders,
            message = state.localBrowserMessage,
            onEnterFolder = viewModel::enterLocalFolder,
            onParent = viewModel::goLocalParent,
            onUseCurrent = viewModel::chooseCurrentLocalFolder,
            onDismiss = viewModel::closeLocalBrowser
        )
    }
}

@Composable
private fun ReadOnlyPathSection(
    title: String,
    path: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = Color.White,
            contentColor = Color.Black,
            border = BorderStroke(2.dp, Color.Black)
        ) {
            Text(
                text = path,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Black
            )
        }
    }
}

@Composable
private fun LocalFolderPickerSection(
    selectedPath: String,
    onBrowse: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.field_local_folder), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = Color.White,
            contentColor = Color.Black,
            border = BorderStroke(2.dp, Color.Black)
        ) {
            Text(
                text = selectedPath.ifBlank { stringResource(R.string.task_edit_no_local_folder) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Black
            )
        }
        InkButton(text = stringResource(R.string.task_edit_select_local_folder), onClick = onBrowse)
    }
}

@Composable
private fun RemoteFolderPickerSection(
    selectedPath: String,
    onBrowse: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.field_remote_folder), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            text = selectedPath.ifBlank { stringResource(R.string.task_edit_no_webdav_folder) },
            style = MaterialTheme.typography.bodyLarge
        )
        InkButton(text = stringResource(R.string.task_edit_browse_webdav_folder), onClick = onBrowse)
    }
}

@Composable
private fun LocalFolderBrowserDialog(
    currentPath: String,
    folders: List<LocalFolderItem>,
    message: String?,
    onEnterFolder: (String) -> Unit,
    onParent: () -> Unit,
    onUseCurrent: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black)
        ) {
            CompositionLocalProvider(LocalContentColor provides Color.Black) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.task_edit_select_local_folder_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.task_edit_current_position, currentPath), style = MaterialTheme.typography.bodyLarge)
                ResponsiveButtonRow(horizontalGap = 10.dp) {
                    InkButton(
                        text = stringResource(R.string.task_edit_parent),
                        onClick = onParent,
                        enabled = File(currentPath).parentFile != null
                    )
                    InkButton(text = stringResource(R.string.task_edit_use_current_folder), onClick = onUseCurrent)
                }
                if (!message.isNullOrBlank()) {
                    Text(message, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(folders, key = { it.path }) { folder ->
                        InkButton(
                            text = folder.name,
                            onClick = { onEnterFolder(folder.path) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    InkButton(text = stringResource(R.string.task_edit_cancel), onClick = onDismiss)
                }
            }
            }
        }
    }
}

@Composable
private fun RemoteFolderBrowserDialog(
    currentPath: String,
    folders: List<WebDavFolder>,
    loading: Boolean,
    creatingFolder: Boolean,
    canUseCurrent: Boolean,
    message: String?,
    newFolderName: String,
    createFolderOpen: Boolean,
    createFolderMessage: String?,
    onNewFolderNameChange: (String) -> Unit,
    onOpenCreateFolder: () -> Unit,
    onCreateFolder: () -> Unit,
    onDismissCreateFolder: () -> Unit,
    onEnterFolder: (String) -> Unit,
    onParent: () -> Unit,
    onUseCurrent: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black)
        ) {
            CompositionLocalProvider(LocalContentColor provides Color.Black) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.task_edit_select_webdav_folder_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.task_edit_current_position, currentPath), style = MaterialTheme.typography.bodyLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RemoteBrowserActionButton(
                        text = stringResource(R.string.task_edit_use_current_folder),
                        onClick = onUseCurrent,
                        enabled = canUseCurrent && !loading && !creatingFolder,
                        backgroundColor = Color(0xFFECECEC),
                        modifier = Modifier.weight(1f)
                    )
                    RemoteBrowserActionButton(
                        text = stringResource(R.string.task_edit_create_remote_folder),
                        onClick = onOpenCreateFolder,
                        enabled = canUseCurrent && !loading && !creatingFolder,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (loading || creatingFolder) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator()
                        Text(
                            if (creatingFolder) stringResource(R.string.task_edit_creating_remote_folder) else stringResource(R.string.task_edit_reading_remote_folder),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                if (!message.isNullOrBlank()) {
                    Text(message, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(folders, key = { it.path }) { folder ->
                        InkButton(
                            text = folder.name,
                            onClick = { onEnterFolder(folder.path) },
                            enabled = !loading && !creatingFolder,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RemoteBrowserActionButton(
                        text = "←",
                        onClick = onParent,
                        enabled = currentPath != "/" && !loading && !creatingFolder,
                        modifier = Modifier.widthIn(min = 44.dp, max = 44.dp)
                    )
                    RemoteBrowserActionButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = onDismiss,
                        enabled = !creatingFolder,
                        modifier = Modifier.widthIn(min = 76.dp)
                    )
                }
            }
            }
        }
    }
    if (createFolderOpen) {
        CreateRemoteFolderDialog(
            folderName = newFolderName,
            creatingFolder = creatingFolder,
            message = createFolderMessage,
            onFolderNameChange = onNewFolderNameChange,
            onCreate = onCreateFolder,
            onDismiss = onDismissCreateFolder
        )
    }
}

@Composable
private fun CreateRemoteFolderDialog(
    folderName: String,
    creatingFolder: Boolean,
    message: String?,
    onFolderNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black)
        ) {
            CompositionLocalProvider(LocalContentColor provides Color.Black) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        stringResource(R.string.task_edit_create_remote_folder),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    InkTextInput(
                        value = folderName,
                        onValueChange = onFolderNameChange,
                        label = stringResource(R.string.task_edit_new_folder_name),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !creatingFolder
                    )
                    if (!message.isNullOrBlank()) {
                        Text(message, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    }
                    if (creatingFolder) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator()
                            Text(
                                stringResource(R.string.task_edit_creating_remote_folder),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    ResponsiveButtonRow(horizontalGap = 10.dp) {
                        InkButton(
                            text = if (creatingFolder) stringResource(R.string.task_edit_creating) else stringResource(R.string.task_edit_create),
                            onClick = onCreate,
                            enabled = !creatingFolder
                        )
                        InkButton(
                            text = stringResource(R.string.action_cancel),
                            onClick = onDismiss,
                            enabled = !creatingFolder
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResponsiveButtonRow(
    horizontalGap: androidx.compose.ui.unit.Dp = 12.dp,
    content: @Composable () -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(horizontalGap),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = { content() }
    )
}

@Composable
private fun FramedSection(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(2.dp, Color.Black),
        colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun InkTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, color = Color.Black, style = MaterialTheme.typography.labelLarge)
        CompositionLocalProvider(LocalTextSelectionColors provides InkTextSelectionColors) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Black),
                modifier = Modifier.fillMaxWidth(),
                colors = inkTextFieldColors()
            )
        }
    }
}

@Composable
private fun inkTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.Black,
    unfocusedTextColor = Color.Black,
    disabledTextColor = Color.Black,
    focusedBorderColor = Color.Black,
    unfocusedBorderColor = Color.Black,
    disabledBorderColor = Color.Black,
    cursorColor = Color.Black
)

private val InkTextSelectionColors = TextSelectionColors(
    handleColor = Color.Black,
    backgroundColor = Color(0x33000000)
)

@Composable
private fun RemoteBrowserActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = Color.White
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 40.dp),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.Black),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            contentColor = Color.Black,
            disabledContainerColor = backgroundColor,
            disabledContentColor = Color.Black
        )
    ) {
        Text(
            text = text,
            color = Color.Black,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun InkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 56.dp)
            .widthIn(min = 112.dp),
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
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodyLarge)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = inkSwitchColors()
        )
    }
}

@Composable
private fun inkSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.Black,
    checkedTrackColor = Color.White,
    checkedBorderColor = Color.Black,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = Color(0xFFE8E8E8),
    uncheckedBorderColor = Color.Black,
    disabledCheckedThumbColor = Color.Black,
    disabledCheckedTrackColor = Color.White,
    disabledCheckedBorderColor = Color.Black,
    disabledUncheckedThumbColor = Color.White,
    disabledUncheckedTrackColor = Color(0xFFE8E8E8),
    disabledUncheckedBorderColor = Color.Black
)

@Composable
private fun DeleteModePicker(
    value: String,
    onValueChange: (String) -> Unit
) {
    val options = listOf(
        DeleteModes.KEEP_REMOTE_ON_LOCAL_DELETE to stringResource(R.string.delete_mode_keep_remote),
        DeleteModes.DELETE_REMOTE_ON_LOCAL_DELETE to stringResource(R.string.delete_mode_delete_remote)
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.task_delete_mode_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            options.forEach { (modeValue, label) ->
                val selected = value == modeValue
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 96.dp)
                        .clickable { onValueChange(modeValue) },
                    shape = RoundedCornerShape(10.dp),
                    border = if (selected) BorderStroke(3.dp, Color.Black) else null,
                    colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (selected) stringResource(R.string.task_edit_current_option) else stringResource(R.string.task_edit_optional),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Black,
                            fontWeight = if (selected) FontWeight.Black else FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
@Composable
private fun taskEditErrorMessage(error: TaskEditError?): String {
    return when (error) {
        TaskEditError.NAME_REQUIRED -> stringResource(R.string.error_task_name_required)
        TaskEditError.LOCAL_FOLDER_REQUIRED -> stringResource(R.string.error_choose_local_folder)
        TaskEditError.REMOTE_FOLDER_REQUIRED -> stringResource(R.string.error_remote_folder_required)
        null -> ""
    }
}
