package com.example.localwebdavsync.ui.log

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.localwebdavsync.R
import com.example.localwebdavsync.data.entity.DeveloperLogEventGroups
import com.example.localwebdavsync.data.entity.LogRecord
import com.example.localwebdavsync.ui.components.LogRecordItem
import com.example.localwebdavsync.ui.components.LogRecordItemStyle

private val LogLogRecordItemStyle = LogRecordItemStyle(
    containerColor = Color.White,
    borderColor = Color.Black,
    borderWidth = 2.dp,
    textColor = Color.Black,
    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    separatorBackground = Color.Black,
    separatorTextColor = Color.White
)

@Composable
fun LogScreen(
    viewModel: LogViewModel,
    onBack: () -> Unit
) {
    val logs by viewModel.logs.collectAsState()
    val detailedDebugLogsEnabled by viewModel.detailedDebugLogsEnabled.collectAsState()
    var selectedFilter by rememberSaveable { mutableStateOf(LogFilter.PRIMARY) }
    var paused by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val filteredLogs = logs.filter(selectedFilter::matches)

    LaunchedEffect(filteredLogs.size, paused) {
        if (!paused && filteredLogs.isNotEmpty()) listState.scrollToItem(0)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.White, contentColor = Color.Black) {
        CompositionLocalProvider(LocalContentColor provides Color.Black) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.logs_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Divider(color = Color.Black)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InkButton(text = if (paused) stringResource(R.string.log_resume_scroll) else stringResource(R.string.log_pause_scroll), onClick = { paused = !paused }, modifier = Modifier.weight(1f))
                InkButton(text = stringResource(R.string.log_clear_logs), onClick = viewModel::clearLogs, modifier = Modifier.weight(1f))
                InkButton(text = stringResource(R.string.action_back), onClick = onBack, modifier = Modifier.weight(1f))
            }
            LogFilterBar(
                logs = logs,
                showDebug = detailedDebugLogsEnabled,
                selectedFilter = selectedFilter,
                onFilterSelected = { selectedFilter = it }
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredLogs, key = { it.id }) { log ->
                        LogRecordItem(log = log, style = LogLogRecordItemStyle)
                    }
                }
                LogScrollBar(state = listState)
            }
        }
        }
    }
}

private enum class LogFilter(@androidx.annotation.StringRes val labelRes: Int) {
    PRIMARY(R.string.log_filter_primary),
    SUCCESS(R.string.log_filter_success),
    FAILED(R.string.log_filter_failed),
    ALL(R.string.log_filter_all),
    DEBUG(R.string.log_filter_debug);

    fun matches(log: LogRecord): Boolean {
        return when (this) {
            PRIMARY -> log.eventType in DeveloperLogEventGroups.primary
            SUCCESS -> log.eventType in DeveloperLogEventGroups.success
            FAILED -> log.eventType in DeveloperLogEventGroups.failed
            ALL -> true
            DEBUG -> log.eventType in DeveloperLogEventGroups.debug
        }
    }
}

@Composable
private fun LogFilterBar(
    logs: List<LogRecord>,
    showDebug: Boolean,
    selectedFilter: LogFilter,
    onFilterSelected: (LogFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        logs.visibleLogFilters(showDebug).forEach { filter ->
            FilterButton(
                text = "${stringResource(filter.labelRes)} ${logs.count(filter::matches)}",
                selected = filter == selectedFilter,
                onClick = { onFilterSelected(filter) }
            )
        }
    }
}

private fun List<LogRecord>.visibleLogFilters(showDebug: Boolean): List<LogFilter> {
    return LogFilter.entries.filter { filter ->
        if (filter == LogFilter.DEBUG && !showDebug) {
            false
        } else {
            filter == LogFilter.ALL || count(filter::matches) > 0
        }
    }
}

@Composable
private fun FilterButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(2.dp, Color.Black),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color.Black else Color.White,
            contentColor = if (selected) Color.White else Color.Black
        )
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else Color.Black,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun InkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
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
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 18.sp),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}


@Composable
private fun LogScrollBar(
    state: LazyListState,
    modifier: Modifier = Modifier
) {
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    val scrollProgress by remember(state) {
        derivedStateOf {
            val layoutInfo = state.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (layoutInfo.totalItemsCount == 0 || visibleItems.isEmpty()) {
                0f
            } else {
                val first = visibleItems.first()
                val last = visibleItems.last()
                val atTop = first.index == 0 && first.offset >= layoutInfo.viewportStartOffset
                val atBottom = last.index == layoutInfo.totalItemsCount - 1 &&
                    last.offset + last.size <= layoutInfo.viewportEndOffset
                if (atTop) {
                    return@derivedStateOf 0f
                }
                if (atBottom) {
                    return@derivedStateOf 1f
                }
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
    val shownProgress = dragProgress ?: scrollProgress

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
        val density = LocalDensity.current
        val maxOffsetPx = with(density) { maxOffset.toPx() }.coerceAtLeast(1f)
        val layoutInfo = state.layoutInfo
        val averageItemSize = layoutInfo.visibleItemsInfo
            .map { it.size }
            .average()
            .toFloat()
            .coerceAtLeast(1f)
        val viewportSize = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset)
            .toFloat()
            .coerceAtLeast(1f)
        val estimatedContentSize = averageItemSize * totalItems
        val estimatedMaxScroll = (estimatedContentSize - viewportSize).coerceAtLeast(1f)

        Box(
            modifier = Modifier
                .offset(y = maxOffset * shownProgress)
                .fillMaxWidth()
                .height(thumbHeight)
                .pointerInput(totalItems, visibleItems, maxOffsetPx, estimatedMaxScroll) {
                    detectVerticalDragGestures(
                        onDragStart = { dragProgress = scrollProgress },
                        onDragEnd = { dragProgress = null },
                        onDragCancel = { dragProgress = null }
                    ) { change, dragAmount ->
                        change.consume()
                        val currentProgress = dragProgress ?: scrollProgress
                        dragProgress = (currentProgress + dragAmount / maxOffsetPx).coerceIn(0f, 1f)
                        val listDragAmount = dragAmount / maxOffsetPx * estimatedMaxScroll
                        state.dispatchRawDelta(listDragAmount)
                    }
                }
                .background(Color.Black, RoundedCornerShape(3.dp))
        )
    }
}
