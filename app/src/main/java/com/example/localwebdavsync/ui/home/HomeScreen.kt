package com.example.localwebdavsync.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlin.math.abs
import kotlin.math.roundToInt
import com.example.localwebdavsync.R
import com.example.localwebdavsync.data.entity.DeveloperLogEventGroups
import com.example.localwebdavsync.data.entity.FileScanStatuses
import com.example.localwebdavsync.data.entity.LogRecord
import com.example.localwebdavsync.data.entity.SyncFileRecord
import com.example.localwebdavsync.data.entity.SyncTask
import com.example.localwebdavsync.ui.components.LogRecordItem
import com.example.localwebdavsync.ui.components.LogRecordItemStyle
import com.example.localwebdavsync.util.formatFileSize

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.flowOf

private val InkBlack = Color.Black
private val InkMid = Color(0xFF666666)
private val InkLine = Color(0xFF111111)
private val InkPaper = Color.White
private val InkPanel = Color(0xFFF4F4F4)
private val InkDisabled = Color.Black
private val InkDisabledContainer = Color.White
private const val DisabledButtonAlpha = 0.42f
private val HomeTitleText = androidx.compose.ui.text.TextStyle(
    fontSize = 24.sp,
    lineHeight = 30.sp,
    fontWeight = FontWeight.Black
)
private val HomeSectionTitleText = androidx.compose.ui.text.TextStyle(
    fontSize = 19.sp,
    lineHeight = 24.sp,
    fontWeight = FontWeight.Bold
)
private val HomeBodyText = androidx.compose.ui.text.TextStyle(
    fontSize = 15.sp,
    lineHeight = 21.sp
)
private val HomeButtonText = androidx.compose.ui.text.TextStyle(
    fontSize = 16.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.Bold
)
private val HomeLogRecordItemStyle = LogRecordItemStyle(
    containerColor = InkPaper,
    borderColor = InkLine,
    borderWidth = 1.dp,
    textColor = InkBlack,
    textStyle = HomeBodyText,
    separatorBackground = InkBlack,
    separatorTextColor = InkPaper
)

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    isWebDavConfigured: Boolean,
    onEditTask: (SyncTask) -> Unit,
    onCreateTask: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit
) {
    val tasks by viewModel.tasks.collectAsState()
    val autoSyncState by viewModel.autoSyncState.collectAsState()
    val logs by viewModel.logs.collectAsState()
    var selectedTaskId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(tasks) {
        if (selectedTaskId == null || tasks.none { it.id == selectedTaskId }) {
            selectedTaskId = tasks.firstOrNull()?.id
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = InkPaper) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWide = maxWidth >= 700.dp
            val selectedTask = tasks.firstOrNull { it.id == selectedTaskId }
            val currentTaskStatus = autoSyncState.currentTaskId?.let { currentTaskId ->
                autoSyncState.taskStatuses.firstOrNull { it.taskId == currentTaskId }
            }
            val pendingConfirmTaskId = autoSyncState.taskStatuses
                .firstOrNull { it.needsLargeDeleteConfirmation }
                ?.taskId
            val consoleTaskId = pendingConfirmTaskId ?: currentTaskStatus
                ?.takeIf { it.stage in ConsoleFocusStages }
                ?.taskId
                ?: autoSyncState.taskStatuses.firstOrNull { it.stage in ConsoleFocusStages }?.taskId
            val consoleTask = tasks.firstOrNull { it.id == consoleTaskId } ?: selectedTask
            val consoleFiles by remember(consoleTask?.id) {
                consoleTask?.let { viewModel.observeFiles(it.id) } ?: flowOf(emptyList())
            }.collectAsState(initial = emptyList())
            val canScanSyncAll = tasks.any { it.enabled } &&
                isWebDavConfigured &&
                !autoSyncState.running &&
                !autoSyncState.hasResumeableTasks()
            val hasRunningTasks = autoSyncState.running
            val hasUnfinishedTasks = autoSyncState.hasResumeableTasks()
            val canSelectTask = !hasRunningTasks && !hasUnfinishedTasks
            val canModifyTasks = !hasRunningTasks && !hasUnfinishedTasks
            if (isWide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    TaskListPane(
                        tasks = tasks,
                        selectedTaskId = selectedTaskId,
                        onTaskClick = { if (canSelectTask) selectedTaskId = it.id },
                        onEditTask = onEditTask,
                        onDeleteTask = viewModel::deleteTask,
                        onMoveTask = viewModel::moveTask,
                        onCreateTask = onCreateTask,
                        onOpenSettings = onOpenSettings,
                        onScanSyncAll = viewModel::scanAndSyncAllNow,
                        onFullScanAll = viewModel::fullScanAndSyncAllNow,
                        canScanSyncAll = canScanSyncAll,
                        canSelectTask = canSelectTask,
                        canModifyTasks = canModifyTasks,
                        onTaskEnabledChange = viewModel::setTaskEnabled,
                        modifier = Modifier
                            .width(320.dp)
                            .fillMaxHeight()
                    )
                    Divider(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(2.dp),
                        color = InkLine
                    )
                    WorkspacePane(
                        task = consoleTask,
                        files = consoleFiles,
                        autoSyncState = autoSyncState,
                        logs = logs,
                        onConfirmLargeDelete = viewModel::confirmLargeDeleteAndSync,
                        onPauseAll = viewModel::pauseAllTasks,
                        onCancelAll = viewModel::cancelCurrentRoundTasks,
                        onResumeAll = viewModel::resumeAllPausedTasks,
                        onClearLogs = viewModel::clearLogs,
                        onOpenLogs = onOpenLogs,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    HeaderActions(
                        onCreateTask = onCreateTask,
                        onOpenSettings = onOpenSettings,
                        onScanSyncAll = viewModel::scanAndSyncAllNow,
                        onFullScanAll = viewModel::fullScanAndSyncAllNow,
                        canScanSyncAll = canScanSyncAll
                    )
                    TaskListCompact(
                        tasks = tasks,
                        selectedTaskId = selectedTaskId,
                        onTaskClick = { if (canSelectTask) selectedTaskId = it.id },
                        onEditTask = onEditTask,
                        onDeleteTask = viewModel::deleteTask,
                        onMoveTask = viewModel::moveTask,
                        onCreateTask = onCreateTask,
                        canSelectTask = canSelectTask,
                        canModifyTasks = canModifyTasks,
                        onTaskEnabledChange = viewModel::setTaskEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                    )
                    LogConsole(
                        task = consoleTask,
                        status = autoSyncState.taskStatuses.firstOrNull { it.taskId == consoleTask?.id },
                        autoSyncState = autoSyncState,
                        files = consoleFiles,
                        logs = logs,
                        compact = true,
                        onConfirmLargeDelete = viewModel::confirmLargeDeleteAndSync,
                        onPauseAll = viewModel::pauseAllTasks,
                        onCancelAll = viewModel::cancelCurrentRoundTasks,
                        onResumeAll = viewModel::resumeAllPausedTasks,
                        onClearLogs = viewModel::clearLogs,
                        onOpenLogs = onOpenLogs,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskListPane(
    tasks: List<SyncTask>,
    selectedTaskId: Long?,
    onTaskClick: (SyncTask) -> Unit,
    onEditTask: (SyncTask) -> Unit,
    onDeleteTask: (SyncTask) -> Unit,
    onMoveTask: (Long, Int) -> Unit,
    onCreateTask: () -> Unit,
    onOpenSettings: () -> Unit,
    onScanSyncAll: () -> Unit,
    onFullScanAll: () -> Unit,
    canScanSyncAll: Boolean,
    canSelectTask: Boolean,
    canModifyTasks: Boolean,
    onTaskEnabledChange: (SyncTask, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(InkPanel)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        HeaderActions(
            onCreateTask = onCreateTask,
            onOpenSettings = onOpenSettings,
            onScanSyncAll = onScanSyncAll,
            onFullScanAll = onFullScanAll,
            canScanSyncAll = canScanSyncAll
        )
        TaskListCompact(
            tasks = tasks,
            selectedTaskId = selectedTaskId,
            onTaskClick = onTaskClick,
            onEditTask = onEditTask,
            onDeleteTask = onDeleteTask,
            onMoveTask = onMoveTask,
            onCreateTask = onCreateTask,
            canSelectTask = canSelectTask,
            canModifyTasks = canModifyTasks,
            onTaskEnabledChange = onTaskEnabledChange,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HeaderActions(
    onCreateTask: () -> Unit,
    onOpenSettings: () -> Unit,
    onScanSyncAll: () -> Unit,
    onFullScanAll: () -> Unit,
    canScanSyncAll: Boolean
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmFullScan by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.app_name),
            style = HomeTitleText,
            fontWeight = FontWeight.Black,
            color = InkBlack
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InkButton(
                text = stringResource(R.string.home_create_task),
                onClick = onCreateTask,
                modifier = Modifier.weight(1f)
            )
            InkButton(
                text = stringResource(R.string.home_settings),
                onClick = onOpenSettings,
                outlined = true,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InkButton(
                text = stringResource(R.string.home_scan_sync_all),
                onClick = onScanSyncAll,
                enabled = canScanSyncAll,
                modifier = Modifier.weight(1f)
            )
            Box {
                MoreMenuButton(
                    onClick = { menuExpanded = true },
                    enabled = canScanSyncAll,
                    modifier = Modifier.width(54.dp)
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.border(1.dp, InkLine, RoundedCornerShape(8.dp)),
                    shape = RoundedCornerShape(8.dp),
                    containerColor = InkPaper
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.home_full_scan_all),
                                color = InkBlack,
                                style = HomeBodyText,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            confirmFullScan = true
                        },
                        enabled = canScanSyncAll,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }

    if (confirmFullScan) {
        AlertDialog(
            onDismissRequest = { confirmFullScan = false },
            containerColor = InkPaper,
            titleContentColor = InkBlack,
            textContentColor = InkBlack,
            title = {
                Text(
                    text = stringResource(R.string.home_full_scan_confirm_title),
                    color = InkBlack,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.home_full_scan_confirm_body),
                    color = InkBlack,
                    style = HomeBodyText
                )
            },
            confirmButton = {
                TextButton(
                    enabled = canScanSyncAll,
                    onClick = {
                        confirmFullScan = false
                        onFullScanAll()
                    }
                ) {
                    Text(stringResource(R.string.home_start_full_scan), color = InkBlack)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmFullScan = false }) {
                    Text(stringResource(R.string.home_cancel), color = InkBlack)
                }
            }
        )
    }
}

@Composable
private fun TaskListCompact(
    tasks: List<SyncTask>,
    selectedTaskId: Long?,
    onTaskClick: (SyncTask) -> Unit,
    onEditTask: (SyncTask) -> Unit,
    onDeleteTask: (SyncTask) -> Unit,
    onMoveTask: (Long, Int) -> Unit,
    onCreateTask: () -> Unit,
    canSelectTask: Boolean,
    canModifyTasks: Boolean,
    onTaskEnabledChange: (SyncTask, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingDeleteTask by remember { mutableStateOf<SyncTask?>(null) }
    LaunchedEffect(canModifyTasks) {
        if (!canModifyTasks) {
            pendingDeleteTask = null
        }
    }
    if (tasks.isEmpty()) {
        EmptyTaskMessage(onCreateTask = onCreateTask, modifier = modifier)
        return
    }
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(tasks, key = { _, task -> task.id }) { index, task ->
            SwipeTaskCard(
                task = task,
                displayIndex = index + 1,
                taskCount = tasks.size,
                selected = canSelectTask && task.id == selectedTaskId,
                selectionEnabled = canSelectTask,
                mutationEnabled = canModifyTasks,
                onClick = { onTaskClick(task) },
                onEnabledChange = { enabled -> onTaskEnabledChange(task, enabled) },
                onMove = { direction -> onMoveTask(task.id, direction) },
                onEdit = { onEditTask(task) },
                onDelete = { pendingDeleteTask = task }
            )
        }
    }
    pendingDeleteTask?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDeleteTask = null },
            containerColor = InkPaper,
            titleContentColor = InkBlack,
            textContentColor = InkBlack,
            title = { Text(stringResource(R.string.home_confirm_delete_title)) },
            text = { Text(stringResource(R.string.home_confirm_delete_body, task.name)) },
            confirmButton = {
                TextButton(
                    enabled = canModifyTasks,
                    onClick = {
                        onDeleteTask(task)
                        pendingDeleteTask = null
                    }
                ) { Text(stringResource(R.string.home_delete), color = InkBlack) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteTask = null }) { Text(stringResource(R.string.home_cancel), color = InkBlack) }
            }
        )
    }
}

@Composable
private fun WorkspacePane(
    task: SyncTask?,
    files: List<SyncFileRecord>,
    autoSyncState: AutoSyncUiState,
    logs: List<LogRecord>,
    onConfirmLargeDelete: (SyncTask) -> Unit,
    onPauseAll: () -> Unit,
    onCancelAll: () -> Unit,
    onResumeAll: () -> Unit,
    onClearLogs: () -> Unit,
    onOpenLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        LogConsole(
            task = task,
            status = autoSyncState.taskStatuses.firstOrNull { it.taskId == task?.id },
            autoSyncState = autoSyncState,
            files = files,
            logs = logs,
            compact = false,
            onConfirmLargeDelete = onConfirmLargeDelete,
            onPauseAll = onPauseAll,
            onCancelAll = onCancelAll,
            onResumeAll = onResumeAll,
            onClearLogs = onClearLogs,
            onOpenLogs = onOpenLogs,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun EmptyTaskMessage(onCreateTask: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = InkPaper),
        border = BorderStroke(2.dp, InkLine)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.home_empty_title), style = HomeSectionTitleText, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.home_empty_body), style = HomeBodyText)
        }
    }
}

@Composable
private fun SwipeTaskCard(
    task: SyncTask,
    displayIndex: Int,
    taskCount: Int,
    selected: Boolean,
    selectionEnabled: Boolean,
    mutationEnabled: Boolean,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val revealWidth = 196.dp
    val revealPx = with(LocalDensity.current) { revealWidth.toPx() }
    val reorderThresholdPx = with(LocalDensity.current) { 40.dp.toPx() }
    var offsetX by remember(task.id) { mutableStateOf(0f) }
    var dragAxis by remember(task.id) { mutableStateOf<DragAxis?>(null) }
    var dragY by remember(task.id) { mutableStateOf(0f) }
    val revealProgress = (-offsetX / revealPx).coerceIn(0f, 1f)
    LaunchedEffect(mutationEnabled) {
        if (!mutationEnabled) {
            offsetX = 0f
            dragAxis = null
            dragY = 0f
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .width(revealWidth)
                .align(Alignment.CenterEnd)
                .padding(start = 10.dp)
                .graphicsLayer {
                    alpha = revealProgress
                },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallInkButton(
                text = stringResource(R.string.home_edit),
                onClick = onEdit,
                enabled = mutationEnabled,
                outlined = true,
                modifier = Modifier.weight(1f)
            )
            SmallInkButton(
                text = stringResource(R.string.home_delete),
                onClick = onDelete,
                enabled = mutationEnabled,
                modifier = Modifier.weight(1f)
            )
        }
        TaskCard(
            task = task,
            displayIndex = displayIndex,
            taskCount = taskCount,
            selected = selected,
            selectionEnabled = selectionEnabled,
            mutationEnabled = mutationEnabled,
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(task.id, mutationEnabled) {
                    if (mutationEnabled) {
                        detectDragGestures(
                            onDragStart = {
                                dragAxis = null
                                dragY = 0f
                            },
                            onDrag = { change, dragAmount ->
                                if (dragAxis == null) {
                                    dragAxis = if (abs(dragAmount.x) >= abs(dragAmount.y)) DragAxis.HORIZONTAL else DragAxis.VERTICAL
                                }
                                when (dragAxis) {
                                    DragAxis.HORIZONTAL -> {
                                        offsetX = (offsetX + dragAmount.x).coerceIn(-revealPx, 0f)
                                    }
                                    DragAxis.VERTICAL -> {
                                        dragY += dragAmount.y
                                    }
                                    null -> Unit
                                }
                                change.consume()
                            },
                            onDragEnd = {
                                if (dragAxis == DragAxis.VERTICAL && abs(dragY) >= reorderThresholdPx) {
                                    onMove(if (dragY < 0f) -1 else 1)
                                } else {
                                    offsetX = if (offsetX < -revealPx * 0.45f) -revealPx else 0f
                                }
                                dragAxis = null
                                dragY = 0f
                            },
                            onDragCancel = {
                                offsetX = if (offsetX < -revealPx * 0.45f) -revealPx else 0f
                                dragAxis = null
                                dragY = 0f
                            }
                        )
                    }
                },
            onClick = {
                if (offsetX < 0f) {
                    offsetX = 0f
                } else if (selectionEnabled) {
                    onClick()
                }
            },
            onEnabledChange = onEnabledChange,
            onMove = onMove
        )
    }
}

@Composable
private fun TaskCard(
    task: SyncTask,
    displayIndex: Int,
    taskCount: Int,
    selected: Boolean,
    selectionEnabled: Boolean,
    mutationEnabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onMove: (Int) -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = selectionEnabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (selected) InkPaper else Color(0xFFF8F8F8)),
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) InkBlack else InkMid)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayIndex.toString().padStart(2, '0'),
                modifier = Modifier.width(28.dp),
                style = HomeBodyText,
                color = InkMid,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
            Text(
                text = task.name,
                modifier = Modifier.weight(1f),
                style = HomeSectionTitleText,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            ReorderInkButton(
                text = "↑",
                enabled = mutationEnabled && displayIndex > 1,
                onClick = { onMove(-1) }
            )
            ReorderInkButton(
                text = "↓",
                enabled = mutationEnabled && displayIndex < taskCount,
                onClick = { onMove(1) }
            )
            Switch(
                checked = task.enabled,
                enabled = mutationEnabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = InkBlack,
                    checkedTrackColor = InkPaper,
                    checkedBorderColor = InkBlack,
                    uncheckedThumbColor = InkPaper,
                    uncheckedTrackColor = InkPaper,
                    uncheckedBorderColor = InkBlack
                )
            )
        }
    }
}

@Composable
private fun ReorderInkButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(32.dp)
            .height(32.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = InkPaper,
        contentColor = if (enabled) InkBlack else InkMid,
        border = BorderStroke(1.dp, if (enabled) InkLine else InkMid)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = HomeBodyText,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SelectedTaskConsoleHeader(
    task: SyncTask?,
    status: AutoSyncTaskStatus?,
    autoSyncState: AutoSyncUiState,
    files: List<SyncFileRecord>,
    onConfirmLargeDelete: (SyncTask) -> Unit,
    onPauseAll: () -> Unit,
    onCancelAll: () -> Unit,
    onResumeAll: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val isSelectedTaskRunning = autoSyncState.running &&
            (
                status?.stage == AutoSyncStage.QUEUED ||
                    status?.stage == AutoSyncStage.SCANNING ||
                    status?.stage == AutoSyncStage.SYNCING
                )
        val taskTitle = if (task == null) {
            stringResource(R.string.home_current_task)
        } else {
            val taskIndex = autoSyncState.taskStatuses.indexOfFirst { it.taskId == task.id }
            val taskTotal = autoSyncState.taskStatuses.size
            when {
                status?.needsLargeDeleteConfirmation == true -> "待确认删除：${task.name}"
                isSelectedTaskRunning && taskIndex >= 0 && taskTotal > 1 -> {
                    "正在执行：${task.name}（第${taskIndex + 1}/${taskTotal}个任务）"
                }
                isSelectedTaskRunning -> "正在执行：${task.name}"
                else -> "${stringResource(R.string.home_current_task)}：${task.name}"
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = taskTitle,
                modifier = Modifier.weight(1f),
                color = InkBlack,
                style = HomeSectionTitleText,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (task == null) {
            Text(stringResource(R.string.home_select_task_hint), style = HomeBodyText, color = InkBlack)
            return@Column
        }
        val hasRunningTasks = autoSyncState.running
        val hasManualResumeTasks = autoSyncState.hasManualResumeTasks()
        ResponsiveButtonRow {
            if (status?.needsLargeDeleteConfirmation == true) {
                SmallInkButton(
                    text = stringResource(R.string.home_confirm_remote_delete),
                    onClick = { onConfirmLargeDelete(task) },
                    enabled = !autoSyncState.running && !autoSyncState.hasManuallyPausedTasks()
                )
            }
            if (hasRunningTasks) {
                SmallInkButton(text = stringResource(R.string.home_pause_all), onClick = onPauseAll, outlined = true)
                SmallInkButton(text = stringResource(R.string.home_cancel_task), onClick = onCancelAll, outlined = true)
            }
            if (!hasRunningTasks && hasManualResumeTasks) {
                SmallInkButton(text = stringResource(R.string.home_resume_all), onClick = onResumeAll)
            }
        }
        LocalFileRecordStrip(
            task = task,
            status = status,
            files = files
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResponsiveButtonRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = { content() }
    )
}

@Composable
private fun LocalFileRecordStrip(
    task: SyncTask,
    status: AutoSyncTaskStatus?,
    files: List<SyncFileRecord>
) {
    var showDetails by remember { mutableStateOf(false) }
    val displayScanId = remember(files, task.currentScanId) {
        maxOf(task.currentScanId, files.maxOfOrNull { it.lastScanId } ?: 0L)
    }
    val currentScanFiles = remember(files, displayScanId) {
        files.filter {
            displayScanId > 0L &&
                it.lastScanId == displayScanId &&
                it.status in FileScanStatuses.CURRENT_SCAN_VISIBLE_STATUSES
        }
    }
    val liveSummary = when (status?.stage) {
        AutoSyncStage.SCANNING -> status.message ?: "正在扫描"
        AutoSyncStage.SYNCING -> status.message?.takeIf { it.startsWith("正在同步") }
        AutoSyncStage.WAITING_CONFIRMATION -> status.message ?: "待确认删除"
        AutoSyncStage.NOT_STARTED -> status.message?.takeIf { it == NOT_SYNCED_MESSAGE }
        else -> null
    }
    val pausedDuringScan = status?.stage == AutoSyncStage.PAUSED &&
        status.pausedFromStage == AutoSyncStage.SCANNING
    val cancelledBeforeCompletedScan = status?.stage == AutoSyncStage.CANCELLED &&
        status.pausedFromStage in setOf(AutoSyncStage.QUEUED, AutoSyncStage.SCANNING)
    val detailFiles = if (pausedDuringScan || cancelledBeforeCompletedScan) emptyList() else currentScanFiles
    val summaryMode = status.toRoundSummaryMode()
    val showPendingFilter = status.shouldShowPendingFilterInDetails()
    val detailTitle = if (status == null) {
        stringResource(R.string.home_previous_scan_details)
    } else {
        stringResource(R.string.home_scan_details)
    }
    val resultSummary = when {
        pausedDuringScan -> status.toIncompleteScanSummary(task)
        status?.stage == AutoSyncStage.CANCELLED -> status.toCancelledSummary(
            task = task,
            files = detailFiles,
            currentScanId = displayScanId,
            useNextScanId = cancelledBeforeCompletedScan
        )
        else -> toScanSummaryText(
            files = detailFiles,
            currentScanId = displayScanId,
            mode = summaryMode
        )
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        colors = CardDefaults.cardColors(containerColor = InkPaper),
        border = BorderStroke(1.dp, InkLine)
    ) {
        if (files.isEmpty() && liveSummary == null && status == null) {
            Text(
                text = stringResource(R.string.home_file_record_empty),
                modifier = Modifier.padding(10.dp),
                style = HomeBodyText,
                color = InkMid
            )
            return@Card
        }
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = liveSummary ?: resultSummary,
                modifier = Modifier.weight(1f),
                style = HomeBodyText,
                color = InkBlack,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            CompactInkButton(
                text = detailTitle,
                onClick = { showDetails = true },
                enabled = detailFiles.isNotEmpty()
            )
        }
    }
    if (showDetails) {
        FileRecordDetailsDialog(
            title = detailTitle,
            files = detailFiles,
            mode = summaryMode,
            showPendingFilter = showPendingFilter,
            onDismiss = { showDetails = false }
        )
    }
}

@Composable
private fun FileRecordDetailsDialog(
    title: String,
    files: List<SyncFileRecord>,
    mode: RoundSummaryMode,
    showPendingFilter: Boolean,
    onDismiss: () -> Unit
) {
    var selectedFilter by rememberSaveable { mutableStateOf(ScanFileFilter.PENDING) }
    val visibleFilters = files.visibleScanFilters(mode, showPendingFilter)
    LaunchedEffect(visibleFilters, selectedFilter) {
        if (visibleFilters.isNotEmpty() && selectedFilter !in visibleFilters) {
            selectedFilter = visibleFilters.first()
        }
    }
    val visibleFiles = if (selectedFilter in visibleFilters) {
        files.filter { selectedFilter.matches(it, mode, showPendingFilter) }.let { filtered ->
            if (selectedFilter == ScanFileFilter.SYNCED) {
                filtered.sortedByDescending { it.lastSyncedAt ?: 0L }
            } else {
                filtered
            }
        }
    } else emptyList()
    val listState = rememberLazyListState()
    LaunchedEffect(selectedFilter, visibleFiles.firstOrNull()?.id) {
        if (selectedFilter == ScanFileFilter.SYNCED && visibleFiles.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = InkPaper,
        titleContentColor = InkBlack,
        textContentColor = InkBlack,
        title = { Text(title, color = InkBlack, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (visibleFilters.isNotEmpty()) {
                    ScanFileFilterBar(
                        files = files,
                        mode = mode,
                        showPendingFilter = showPendingFilter,
                        filters = visibleFilters,
                        selectedFilter = selectedFilter,
                        onFilterSelected = { selectedFilter = it }
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (visibleFiles.isEmpty()) {
                            item {
                                Text(stringResource(R.string.home_nothing_to_process), color = InkBlack, style = HomeBodyText)
                            }
                        }
                        items(visibleFiles.take(500), key = { it.id }) { record ->
                            FileRecordChip(record)
                        }
                    }
                    InkScrollBar(state = listState)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_close), color = InkBlack)
            }
        }
    )
}

private enum class ScanFileFilter(@androidx.annotation.StringRes val labelRes: Int) {
    PENDING(R.string.scan_filter_pending),
    SYNCED(R.string.scan_filter_synced),
    FAILED(R.string.scan_filter_failed),
    IGNORED(R.string.scan_filter_ignored);

    fun matches(record: SyncFileRecord, mode: RoundSummaryMode, showPendingFilter: Boolean): Boolean {
        return when (this) {
            PENDING -> showPendingFilter &&
                record.status in FileScanStatuses.DETAIL_PENDING_STATUSES
            SYNCED -> record.status in FileScanStatuses.DETAIL_SYNCED_STATUSES
            FAILED -> if (mode == RoundSummaryMode.SYNC_RESULT && !showPendingFilter) {
                record.status in FileScanStatuses.UPLOAD_FAILURE_STATUSES ||
                    record.status in FileScanStatuses.DETAIL_FAILED_STATUSES
            } else {
                record.status in FileScanStatuses.DETAIL_FAILED_STATUSES
            }
            IGNORED -> record.status in FileScanStatuses.DETAIL_IGNORED_STATUSES
        }
    }
}

private enum class DragAxis {
    HORIZONTAL,
    VERTICAL
}

@Composable
private fun ScanFileFilterBar(
    files: List<SyncFileRecord>,
    mode: RoundSummaryMode,
    showPendingFilter: Boolean,
    filters: List<ScanFileFilter>,
    selectedFilter: ScanFileFilter,
    onFilterSelected: (ScanFileFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        filters.forEach { filter ->
            ScanFilterButton(
                text = "${stringResource(filter.labelRes)} ${files.count { filter.matches(it, mode, showPendingFilter) }}",
                onClick = { onFilterSelected(filter) },
                outlined = filter != selectedFilter
            )
        }
    }
}

@Composable
private fun ScanFilterButton(
    text: String,
    onClick: () -> Unit,
    outlined: Boolean
) {
    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, InkLine),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = InkPaper,
                contentColor = InkBlack
            )
        ) {
            Text(text, color = InkBlack, style = HomeBodyText.copy(fontSize = 13.sp, lineHeight = 16.sp), maxLines = 1)
        }
    } else {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, InkLine),
            colors = ButtonDefaults.buttonColors(
                containerColor = InkBlack,
                contentColor = InkPaper
            )
        ) {
            Text(text, color = InkPaper, style = HomeBodyText.copy(fontSize = 13.sp, lineHeight = 16.sp), maxLines = 1)
        }
    }
}

private fun List<SyncFileRecord>.visibleScanFilters(
    mode: RoundSummaryMode,
    showPendingFilter: Boolean
): List<ScanFileFilter> {
    return ScanFileFilter.entries.filter { filter ->
        count { filter.matches(it, mode, showPendingFilter) } > 0
    }
}

@Composable
private fun FileRecordChip(record: SyncFileRecord) {
    Card(
        colors = CardDefaults.cardColors(containerColor = InkPaper),
        border = BorderStroke(1.dp, InkLine)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = record.localRelativePath,
                modifier = Modifier.fillMaxWidth(),
                style = HomeBodyText.copy(fontSize = 14.sp, lineHeight = 18.sp),
                maxLines = Int.MAX_VALUE
            )
            Text(
                text = "${toReadableFileStatus(record.status)}  ${formatFileSize(record.fileSize)}",
                style = HomeBodyText.copy(fontSize = 13.sp, lineHeight = 17.sp),
                fontWeight = FontWeight.Bold,
                maxLines = Int.MAX_VALUE
            )
        }
    }
}

@Composable
private fun LogConsole(
    task: SyncTask?,
    status: AutoSyncTaskStatus?,
    autoSyncState: AutoSyncUiState,
    files: List<SyncFileRecord>,
    logs: List<LogRecord>,
    compact: Boolean,
    onConfirmLargeDelete: (SyncTask) -> Unit,
    onPauseAll: () -> Unit,
    onCancelAll: () -> Unit,
    onResumeAll: () -> Unit,
    onClearLogs: () -> Unit,
    onOpenLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val filtered = logs.filter { it.isPrimaryLogEvent() || it.eventType in DeveloperLogEventGroups.failed }

    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) listState.scrollToItem(0)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 280.dp else 0.dp),
        colors = CardDefaults.cardColors(containerColor = InkPanel),
        border = BorderStroke(2.dp, InkLine)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SelectedTaskConsoleHeader(
                task = task,
                status = status,
                autoSyncState = autoSyncState,
                files = files,
                onConfirmLargeDelete = onConfirmLargeDelete,
                onPauseAll = onPauseAll,
                onCancelAll = onCancelAll,
                onResumeAll = onResumeAll
            )
            Divider(color = InkLine)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.home_logs), modifier = Modifier.weight(1f), color = InkBlack, style = HomeSectionTitleText, fontWeight = FontWeight.Black)
                CompactInkButton(text = stringResource(R.string.home_details), onClick = onOpenLogs, enabled = filtered.isNotEmpty())
                CompactInkButton(text = stringResource(R.string.home_clear), onClick = onClearLogs, outlined = true)
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered, key = { it.id }) { log ->
                        LogRecordItem(log = log, style = HomeLogRecordItemStyle)
                    }
                }
                InkScrollBar(state = listState)
            }
        }
    }
}

@Composable
private fun InkScrollBar(
    state: LazyListState,
    modifier: Modifier = Modifier
) {
    val scrollProgress by remember(state) {
        derivedStateOf {
            val layoutInfo = state.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (layoutInfo.totalItemsCount == 0 || visibleItems.isEmpty()) {
                0f
            } else {
                val first = visibleItems.first()
                val averageItemSize = visibleItems.map { it.size }.average().toFloat().coerceAtLeast(1f)
                val viewportSize = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset)
                    .toFloat()
                    .coerceAtLeast(1f)
                val estimatedContentSize = averageItemSize * layoutInfo.totalItemsCount
                val maxScroll = (estimatedContentSize - viewportSize).coerceAtLeast(1f)
                val currentScroll = (first.index * averageItemSize - first.offset).coerceAtLeast(0f)
                (currentScroll / maxScroll).coerceIn(0f, 1f)
            }
        }
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(5.dp)
            .background(Color(0xFFE0E0E0), RoundedCornerShape(3.dp))
    ) {
        val totalItems = state.layoutInfo.totalItemsCount
        val visibleItems = state.layoutInfo.visibleItemsInfo.size
        if (totalItems <= visibleItems || visibleItems == 0) return@BoxWithConstraints

        val thumbHeight = (maxHeight * (visibleItems.toFloat() / totalItems.toFloat()))
            .coerceAtLeast(28.dp)
            .coerceAtMost(maxHeight)
        val maxOffset = maxHeight - thumbHeight

        Box(
            modifier = Modifier
                .offset(y = maxOffset * scrollProgress)
                .fillMaxWidth()
                .height(thumbHeight)
                .background(InkBlack, RoundedCornerShape(3.dp))
        )
    }
}

@Composable
private fun SmallInkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    outlined: Boolean = false
) {
    val shape = RoundedCornerShape(8.dp)
    val colors = if (outlined) {
        ButtonDefaults.outlinedButtonColors(
            containerColor = InkPaper,
            contentColor = InkBlack,
            disabledContainerColor = InkDisabledContainer,
            disabledContentColor = InkDisabled
        )
    } else {
        ButtonDefaults.buttonColors(
            containerColor = InkPaper,
            contentColor = InkBlack,
            disabledContainerColor = InkDisabledContainer,
            disabledContentColor = InkDisabled
        )
    }
    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .height(42.dp)
                .graphicsLayer { alpha = if (enabled) 1f else DisabledButtonAlpha },
            shape = shape,
            border = BorderStroke(2.dp, if (enabled) InkLine else InkDisabled),
            colors = colors
        ) {
            Text(text = text, style = HomeBodyText, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .height(42.dp)
                .graphicsLayer { alpha = if (enabled) 1f else DisabledButtonAlpha },
            shape = shape,
            border = BorderStroke(2.dp, if (enabled) InkLine else InkDisabled),
            colors = colors
        ) {
            Text(text = text, style = HomeBodyText, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun CompactInkButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    outlined: Boolean = false
) {
    val shape = RoundedCornerShape(8.dp)
    val modifier = Modifier
        .width(100.dp)
        .height(36.dp)
        .graphicsLayer { alpha = if (enabled) 1f else DisabledButtonAlpha }
    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            shape = shape,
            border = BorderStroke(1.dp, if (enabled) InkLine else InkDisabled),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = InkPaper,
                contentColor = InkBlack,
                disabledContainerColor = InkDisabledContainer,
                disabledContentColor = InkDisabled
            ),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Text(text = text, style = HomeBodyText, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            shape = shape,
            border = BorderStroke(1.dp, if (enabled) InkLine else InkDisabled),
            colors = ButtonDefaults.buttonColors(
                containerColor = InkPaper,
                contentColor = InkBlack,
                disabledContainerColor = InkDisabledContainer,
                disabledContentColor = InkDisabled
            ),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Text(text = text, style = HomeBodyText, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun MoreMenuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(72.dp)
            .graphicsLayer { alpha = if (enabled) 1f else DisabledButtonAlpha },
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = InkPaper,
            contentColor = InkBlack,
            disabledContainerColor = InkDisabledContainer,
            disabledContentColor = InkDisabled
        ),
        border = BorderStroke(2.dp, if (enabled) InkLine else InkDisabled),
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(
                            color = if (enabled) InkBlack else InkDisabled,
                            shape = RoundedCornerShape(50)
                        )
                )
            }
        }
    }
}

@Composable
private fun InkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    outlined: Boolean = false
) {
    val shape = RoundedCornerShape(10.dp)
    val buttonModifier = modifier
        .height(72.dp)
        .graphicsLayer { alpha = if (enabled) 1f else DisabledButtonAlpha }
    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            shape = shape,
            border = BorderStroke(2.dp, if (enabled) InkLine else InkDisabled),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = InkPaper,
                contentColor = InkBlack,
                disabledContainerColor = InkDisabledContainer,
                disabledContentColor = InkDisabled
            )
        ) {
            Text(
                text = text,
                style = HomeButtonText,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = InkPaper,
                contentColor = InkBlack,
                disabledContainerColor = InkDisabledContainer,
                disabledContentColor = InkDisabled
            ),
            border = BorderStroke(2.dp, if (enabled) InkLine else InkDisabled)
        ) {
            Text(
                text = text,
                style = HomeButtonText,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatTime(value: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))
}

@Composable
private fun Long?.toShortTime(): String {
    return this?.let { formatTime(it) } ?: stringResource(R.string.scan_summary_not_recorded)
}

private enum class RoundSummaryMode {
    SCAN_RESULT,
    SYNC_RESULT
}

private fun AutoSyncTaskStatus?.toRoundSummaryMode(): RoundSummaryMode {
    return if (
        this?.stage == AutoSyncStage.PAUSED &&
        pausedFromStage == AutoSyncStage.SCANNING
    ) {
        RoundSummaryMode.SCAN_RESULT
    } else {
        RoundSummaryMode.SYNC_RESULT
    }
}

private fun AutoSyncTaskStatus?.shouldShowPendingFilterInDetails(): Boolean {
    return this == null || !isFinalResultStatus()
}

private fun AutoSyncTaskStatus.isFinalResultStatus(): Boolean {
    return stage in FinalResultStages
}

@Composable
private fun AutoSyncTaskStatus?.toIncompleteScanSummary(task: SyncTask): String {
    val nextScanId = task.currentScanId + 1
    val totalFiles = this?.filesTotal ?: this?.filesScanned ?: 0
    val updatedAt = this?.updatedAt ?: System.currentTimeMillis()
    return stringResource(R.string.scan_summary_header, nextScanId, totalFiles) +
        "\n" + stringResource(R.string.scan_summary_incomplete) + "\n" +
        stringResource(R.string.scan_summary_updated_at, updatedAt.toShortTime())
}

@Composable
private fun AutoSyncTaskStatus.toCancelledSummary(
    task: SyncTask,
    files: List<SyncFileRecord>,
    currentScanId: Long,
    useNextScanId: Boolean
): String {
    val summaryScanId = if (useNextScanId) task.currentScanId + 1 else currentScanId
    val totalFiles = when {
        useNextScanId -> filesTotal ?: filesScanned
        files.isNotEmpty() -> files.size
        else -> filesScanned
    }
    val header = if (summaryScanId > 0L) {
        stringResource(R.string.scan_summary_header, summaryScanId, totalFiles)
    } else {
        stringResource(R.string.scan_summary_no_results)
    }
    return header +
        "\n" + ROUND_CANCELLED_MESSAGE + "\n" +
        stringResource(R.string.scan_summary_updated_at, updatedAt.toShortTime())
}

@Composable
private fun toScanSummaryText(
    files: List<SyncFileRecord>,
    currentScanId: Long,
    mode: RoundSummaryMode
): String {
    if (currentScanId <= 0L) {
        return stringResource(R.string.scan_summary_no_results)
    }
    if (files.isEmpty()) {
        return stringResource(R.string.scan_summary_no_changes, currentScanId)
    }
    val latestUpdatedAt = files.maxOfOrNull { it.updatedAt }
    val counts = files.groupingBy { it.status }.eachCount()
    val uploadFailedCount = files.count {
        it.status in FileScanStatuses.UPLOAD_FAILURE_STATUSES && it.lastSyncedAt == null
    }
    val modifiedFailedCount = files.count {
        it.status in FileScanStatuses.UPLOAD_FAILURE_STATUSES && it.lastSyncedAt != null
    }
    val resultItems = when (mode) {
        RoundSummaryMode.SCAN_RESULT -> listOfNotNull(
            stringResource(R.string.scan_item_new, counts[FileScanStatuses.NEW] ?: 0)
                .takeIf { (counts[FileScanStatuses.NEW] ?: 0) > 0 },
            stringResource(R.string.scan_item_modified, counts[FileScanStatuses.MODIFIED] ?: 0)
                .takeIf { (counts[FileScanStatuses.MODIFIED] ?: 0) > 0 },
            stringResource(R.string.scan_item_retry, counts[FileScanStatuses.LAST_FAILED] ?: 0)
                .takeIf { (counts[FileScanStatuses.LAST_FAILED] ?: 0) > 0 },
            stringResource(R.string.scan_item_local_deleted, counts[FileScanStatuses.LOCAL_DELETED] ?: 0)
                .takeIf { (counts[FileScanStatuses.LOCAL_DELETED] ?: 0) > 0 },
            stringResource(R.string.scan_item_local_deleted_keep, counts[FileScanStatuses.LOCAL_DELETED_KEEP_REMOTE] ?: 0)
                .takeIf { (counts[FileScanStatuses.LOCAL_DELETED_KEEP_REMOTE] ?: 0) > 0 }
        )
        RoundSummaryMode.SYNC_RESULT -> listOfNotNull(
            stringResource(R.string.scan_item_synced, counts[FileScanStatuses.SYNCED] ?: 0)
                .takeIf { (counts[FileScanStatuses.SYNCED] ?: 0) > 0 },
            stringResource(R.string.scan_item_overwrite, counts[FileScanStatuses.OVERWRITE_UPLOADED] ?: 0)
                .takeIf { (counts[FileScanStatuses.OVERWRITE_UPLOADED] ?: 0) > 0 },
            stringResource(R.string.scan_item_remote_deleted, counts[FileScanStatuses.REMOTE_DELETED] ?: 0)
                .takeIf { (counts[FileScanStatuses.REMOTE_DELETED] ?: 0) > 0 },
            stringResource(R.string.scan_item_upload_failed, uploadFailedCount)
                .takeIf { uploadFailedCount > 0 },
            stringResource(R.string.scan_item_modified_failed, modifiedFailedCount)
                .takeIf { modifiedFailedCount > 0 },
            stringResource(R.string.scan_item_delete_failed, counts[FileScanStatuses.DELETE_FAILED] ?: 0)
                .takeIf { (counts[FileScanStatuses.DELETE_FAILED] ?: 0) > 0 }
        )
    }
    if (resultItems.isEmpty()) {
        if (mode == RoundSummaryMode.SYNC_RESULT && files.any { it.status in FileScanStatuses.SUMMARY_PENDING_RESULT_STATUSES }) {
            return stringResource(R.string.scan_summary_header, currentScanId, files.size) +
                "\n" + stringResource(R.string.scan_summary_no_completed_results) + "\n" +
                stringResource(R.string.scan_summary_updated_at, latestUpdatedAt.toShortTime())
        }
        return stringResource(R.string.scan_summary_no_changes, currentScanId)
    }
    val changed = resultItems.joinToString("，")
    val changedText = "\n$changed"
    return stringResource(R.string.scan_summary_header, currentScanId, files.size) +
        changedText + "\n" +
        stringResource(R.string.scan_summary_updated_at, latestUpdatedAt.toShortTime())
}

private val ConsoleFocusStages = setOf(
    AutoSyncStage.QUEUED,
    AutoSyncStage.SCANNING,
    AutoSyncStage.SYNCING,
    AutoSyncStage.WAITING_CONFIRMATION,
    AutoSyncStage.WAITING_RETRY,
    AutoSyncStage.CANCELLED,
    AutoSyncStage.PAUSED,
    AutoSyncStage.NOT_STARTED
)

private val FinalResultStages = setOf(
    AutoSyncStage.NO_CHANGES,
    AutoSyncStage.COMPLETED,
    AutoSyncStage.WAITING_RETRY,
    AutoSyncStage.CANCELLED,
    AutoSyncStage.FAILED
)

private fun LogRecord.isPrimaryLogEvent(): Boolean {
    return eventType in DeveloperLogEventGroups.primary
}

@Composable
private fun toReadableFileStatus(status: String): String {
    return when (status) {
        FileScanStatuses.NEW -> stringResource(R.string.file_status_new)
        FileScanStatuses.MODIFIED -> stringResource(R.string.file_status_pending_overwrite)
        FileScanStatuses.SKIPPED -> stringResource(R.string.file_status_skipped)
        FileScanStatuses.LOCAL_DELETED_KEEP_REMOTE -> stringResource(R.string.file_status_locally_deleted_keep_remote)
        FileScanStatuses.LOCAL_DELETED -> stringResource(R.string.file_status_pending_cloud_delete)
        FileScanStatuses.LAST_FAILED -> stringResource(R.string.file_status_pending_retry)
        FileScanStatuses.SYNCED -> stringResource(R.string.file_status_sync_complete)
        FileScanStatuses.OVERWRITE_UPLOADED -> stringResource(R.string.file_status_overwrite_complete)
        FileScanStatuses.UPLOAD_FAILED -> stringResource(R.string.file_status_upload_failed)
        FileScanStatuses.REMOTE_DELETED -> stringResource(R.string.file_status_cloud_deleted)
        FileScanStatuses.DELETE_FAILED -> stringResource(R.string.file_status_delete_failed)
        else -> status
    }
}
