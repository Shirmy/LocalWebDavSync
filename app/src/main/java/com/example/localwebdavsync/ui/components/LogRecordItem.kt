package com.example.localwebdavsync.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.localwebdavsync.data.entity.DeveloperLogEvents
import com.example.localwebdavsync.data.entity.LogRecord
import com.example.localwebdavsync.util.isVisualLogSeparator
import com.example.localwebdavsync.util.toConsoleAnnotatedText
import kotlin.math.ceil

data class LogRecordItemStyle(
    val containerColor: Color,
    val borderColor: Color,
    val borderWidth: Dp,
    val textColor: Color,
    val textStyle: TextStyle,
    val separatorBackground: Color,
    val separatorTextColor: Color,
    val maxLines: Int = 8
)

@Composable
fun LogRecordItem(
    log: LogRecord,
    style: LogRecordItemStyle,
    modifier: Modifier = Modifier
) {
    if (log.isVisualLogSeparator()) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = style.containerColor,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(
                width = style.borderWidth,
                color = style.borderColor
            )
        ) {
            RepeatedDividerText(symbol = '<', style = style, verticalPadding = 8.dp)
        }
        return
    }

    log.centeredBatchEndText()?.let { endText ->
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = style.containerColor,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(style.borderWidth, style.borderColor)
        ) {
            Text(
                text = buildCenteredEndText(endText),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = style.textColor,
                style = style.textStyle.copy(fontSize = 14.sp, lineHeight = 22.sp),
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
        return
    }

    log.centeredNetworkWarningText()?.let { warningText ->
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = style.containerColor,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(style.borderWidth, style.borderColor)
        ) {
            Text(
                text = warningText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = style.textColor,
                style = style.textStyle.copy(fontSize = 14.sp, lineHeight = 18.sp),
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
        return
    }

    log.centeredBatchHeaderText()?.let {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = style.containerColor,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(style.borderWidth, style.borderColor)
        ) {
            Text(
                text = log.toConsoleAnnotatedText(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = style.textColor,
                style = style.textStyle.copy(fontSize = 14.sp, lineHeight = 18.sp),
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
        return
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = style.containerColor),
        border = BorderStroke(style.borderWidth, style.borderColor)
    ) {
        Text(
            text = log.toConsoleAnnotatedText(),
            modifier = Modifier.padding(10.dp),
            color = style.textColor,
            style = style.textStyle,
            maxLines = style.maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RepeatedDividerText(
    symbol: Char,
    style: LogRecordItemStyle,
    verticalPadding: Dp
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val textStyle = style.textStyle.copy(fontSize = 14.sp, lineHeight = 18.sp)
        val repeatCount = remember(symbol, maxWidth, textStyle.fontSize) {
            with(density) {
                (ceil(maxWidth.toPx() / textStyle.fontSize.toPx()).toInt().coerceAtLeast(24) + 8) * 2
            }
        }
        Text(
            text = symbol.toString().repeat(repeatCount),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = verticalPadding),
            color = style.textColor,
            style = textStyle,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Start,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

private fun buildCenteredEndText(text: String): AnnotatedString {
    val dotIndex = text.indexOf('·')
    if (dotIndex < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, dotIndex))
        withStyle(SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Black)) {
            append("·")
        }
        append(text.substring(dotIndex + 1))
    }
}

private fun LogRecord.centeredBatchEndText(): String? {
    if (summary != null || filePath != null || eventType != DeveloperLogEvents.GENERAL) return null
    val text = message.trim()
    return text.takeIf { it.startsWith("结束 · ") }
}

private fun LogRecord.centeredNetworkWarningText(): String? {
    if (filePath != null) return null
    val text = (summary ?: message).trim()
    if (eventType == DeveloperLogEvents.GENERAL && summary == null) {
        return text.takeIf { it == "📶 WiFi已连接成功" }
    }
    if (eventType != DeveloperLogEvents.SYNC_INTERRUPTED) return null
    return when (text) {
        "WiFi连接中断，请检查网络",
        "未连接 WiFi，请开启 WiFi 连接" -> "⚠️ $text"
        "APP异常退出，上次扫描异常中断",
        "APP异常退出，上次上传异常中断",
        "APP异常退出，上次同步异常中断" -> "⚠️ $text"
        else -> null
    }
}

private fun LogRecord.centeredBatchHeaderText(): String? {
    if (summary != null || filePath != null || eventType != DeveloperLogEvents.GENERAL) return null
    val text = message.trim()
    return when {
            text.matches(Regex("🔍 (重新)?开始扫描")) ||
            text == "🔄 尝试自动同步任务" ||
            text == "🔄 扫描任务已恢复" ||
            text == "🔄 同步任务已恢复" ||
            text == "⏸️ 扫描任务已暂停" ||
            text == "⏸️ 同步任务已暂停" ||
            text == "⛔ 本轮任务已取消" ||
            text == "⬆️ 开始同步" ||
            text == "⬆️ 继续同步" -> text
        else -> null
    }
}
