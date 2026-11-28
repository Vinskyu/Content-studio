package com.contentstudio.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.contentstudio.app.ui.theme.TextMuted
import com.contentstudio.app.ui.theme.TextPrimary

private val MatteSurface = Color(0xFF15171C)
private val MatteElevated = Color(0xFF1E2128)
private val MatteDivider = Color(0xFF262A33)

@Composable
fun StudioMarkdownText(
    markdown: String,
    onCopyCodeBlock: (String) -> Unit = {}
) {
    val lines = remember(markdown) { markdown.lines() }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        var idx = 0
        while (idx < lines.size) {
            val rawLine = lines[idx]
            val trimmed = rawLine.trim()

            if (trimmed.startsWith("```")) {
                val codeLines = mutableListOf<String>()
                idx++
                while (idx < lines.size && !lines[idx].trim().startsWith("```")) {
                    codeLines.add(lines[idx])
                    idx++
                }
                StudioCodeBlock(codeLines.joinToString("\n"), onCopyCodeBlock)
                idx++
                continue
            }

            if (isMarkdownTableStart(lines, idx)) {
                val header = splitMarkdownTableRow(lines[idx].trim())
                val body = mutableListOf<List<String>>()
                var cursor = idx + 2
                while (cursor < lines.size && lines[cursor].trim().startsWith("|")) {
                    val cells = splitMarkdownTableRow(lines[cursor].trim())
                    if (cells.any { it.isNotEmpty() } && !isMarkdownTableSeparator(cells)) body.add(cells)
                    cursor++
                }
                StudioMarkdownTable(header, body)
                idx = cursor
                continue
            }

            val indent = ((rawLine.length - rawLine.trimStart().length) / 2).coerceIn(0, 3)

            when {
                trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 7.dp),
                        thickness = 0.5.dp,
                        color = Color(0xFF232730)
                    )
                }
                trimmed.startsWith("# ") -> StudioMarkdownHeading(trimmed.removePrefix("# "), 17.sp, indent)
                trimmed.startsWith("## ") -> StudioMarkdownHeading(trimmed.removePrefix("## "), 15.sp, indent)
                trimmed.startsWith("### ") -> StudioMarkdownHeading(trimmed.removePrefix("### "), 14.sp, indent)
                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                    StudioMarkdownListItem(trimmed.substring(2), indent, null)
                }
                trimmed.matches(orderedItemRegex) -> {
                    val match = orderedItemRegex.find(trimmed)
                    StudioMarkdownListItem(
                        text = match?.groupValues?.getOrNull(2) ?: trimmed,
                        indent = indent,
                        marker = match?.groupValues?.getOrNull(1)
                    )
                }
                trimmed.isEmpty() -> Spacer(modifier = Modifier.height(5.dp))
                else -> {
                    Text(
                        text = parseInlineMarkdown(trimmed),
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = TextPrimary,
                        modifier = Modifier.padding(start = (indent * 12).dp)
                    )
                }
            }
            idx++
        }
    }
}

private val orderedItemRegex = Regex("^(\\d{1,2})[.)]\\s+(.+)$")

@Composable
private fun StudioMarkdownHeading(
    raw: String,
    size: TextUnit,
    indent: Int
) {
    Text(
        text = parseInlineMarkdown(raw),
        fontSize = size,
        lineHeight = size * 1.35f,
        fontWeight = FontWeight.Bold,
        color = TextPrimary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp, start = (indent * 12).dp)
    )
}

@Composable
private fun StudioMarkdownListItem(
    text: String,
    indent: Int,
    marker: String?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (indent * 12).dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            text = marker?.let { "$it." } ?: "\u2022",
            fontSize = 13.sp,
            lineHeight = 21.sp,
            fontFamily = if (marker != null) FontFamily.Monospace else FontFamily.Default,
            color = TextMuted
        )
        Text(
            text = parseInlineMarkdown(text),
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = TextPrimary,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

@Composable
private fun StudioCodeBlock(
    code: String,
    onCopyCodeBlock: (String) -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MatteSurface)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = "Copy code",
                tint = TextMuted,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onCopyCodeBlock(code) }
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = code,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = TextPrimary,
            softWrap = false,
            modifier = Modifier.horizontalScroll(scrollState)
        )
    }
}

@Composable
private fun StudioMarkdownTable(
    header: List<String>,
    rows: List<List<String>>
) {
    val colCount = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
    if (colCount == 0) return

    val naturalWidths = remember(header, rows) {
        (0 until colCount).map { col ->
            val longest = (listOf(header.getOrElse(col) { "" }) + rows.map { it.getOrElse(col) { "" } })
                .maxOfOrNull { it.length } ?: 0
            (longest * 7).coerceIn(72, 210).dp
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MatteSurface)
            .padding(vertical = 2.dp)
    ) {
        val naturalTotal = naturalWidths.fold(0f) { acc, w -> acc + w.value }
        val widths = if (naturalTotal in 0.01f..(maxWidth.value - 0.01f)) {
            naturalWidths.map { (maxWidth.value * (it.value / naturalTotal)).dp }
        } else {
            naturalWidths
        }
        val tableWidth = widths.fold(0f) { acc, w -> acc + w.value }.dp
        val hScrollState = rememberScrollState()

        Column(modifier = Modifier.horizontalScroll(hScrollState)) {
            Row(
                modifier = Modifier
                    .width(tableWidth)
                    .background(MatteElevated)
            ) {
                for (col in 0 until colCount) {
                    Text(
                        text = parseInlineMarkdown(header.getOrElse(col) { "" }),
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        modifier = Modifier
                            .width(widths[col])
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
            rows.forEach { row ->
                Box(
                    modifier = Modifier
                        .width(tableWidth)
                        .height(0.5.dp)
                        .background(MatteDivider)
                )
                Row(modifier = Modifier.width(tableWidth)) {
                    for (col in 0 until colCount) {
                        Text(
                            text = parseInlineMarkdown(row.getOrElse(col) { "" }),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = TextPrimary,
                            modifier = Modifier
                                .width(widths[col])
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun splitMarkdownTableRow(line: String): List<String> {
    var body = line.trim()
    if (body.startsWith("|")) body = body.substring(1)
    if (body.endsWith("|")) body = body.dropLast(1)
    return body.split("|").map { it.trim() }
}

private fun isMarkdownTableSeparator(cells: List<String>): Boolean =
    cells.isNotEmpty() && cells.all { cell ->
        cell.contains('-') && cell.all { ch -> ch == '-' || ch == ':' }
    }

private fun isMarkdownTableStart(lines: List<String>, index: Int): Boolean {
    if (index + 1 >= lines.size) return false
    val head = lines[index].trim()
    if (!head.startsWith("|") || head.count { it == '|' } < 2) return false
    return isMarkdownTableSeparator(splitMarkdownTableRow(lines[index + 1].trim()))
}

private fun parseInlineMarkdown(raw: String): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var i = 0
    while (i < raw.length) {
        val ch = raw[i]
        if (ch == '\\' && i + 1 < raw.length) {
            builder.append(raw[i + 1])
            i += 2
            continue
        }
        if (ch == '`') {
            val end = raw.indexOf('`', i + 1)
            if (end > i + 1) {
                builder.pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = Color(0xFFD8DEE9),
                        background = MatteElevated
                    )
                )
                builder.append(raw.substring(i + 1, end))
                builder.pop()
                i = end + 1
                continue
            }
            builder.append(ch)
            i++
            continue
        }
        if (ch == '*' && i + 1 < raw.length && raw[i + 1] == '*') {
            val end = raw.indexOf("**", i + 2)
            if (end > i + 2) {
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = TextPrimary))
                builder.append(parseInlineMarkdown(raw.substring(i + 2, end)))
                builder.pop()
                i = end + 2
                continue
            }
            builder.append("**")
            i += 2
            continue
        }
        if (ch == '*' || ch == '_') {
            val end = raw.indexOf(ch, i + 1)
            if (end > i + 1) {
                builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                builder.append(raw.substring(i + 1, end))
                builder.pop()
                i = end + 1
                continue
            }
            builder.append(ch)
            i++
            continue
        }
        builder.append(ch)
        i++
    }
    return builder.toAnnotatedString()
}
