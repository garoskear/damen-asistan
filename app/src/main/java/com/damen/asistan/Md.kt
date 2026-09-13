package com.damen.asistan

/** web/app.js renderTextWithChips + marked mini-uyarlaması (bağımlılıksız). */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface MdBlock {
    data class Para(val text: String) : MdBlock
    data class Head(val level: Int, val text: String) : MdBlock
    data class Pre(val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class ListBlock(val items: List<String>, val ordered: Boolean) : MdBlock
    data class Table(val text: String) : MdBlock
    data class Chips(val names: List<String>) : MdBlock
    data object Hr : MdBlock
}

private val attachRe = Regex("""^@\S*/\S*$""")
private fun isAttachRef(tok: String) = attachRe.matches(tok.trimEnd('.', ',', ';', ':', '!', '?', ')'))
private fun baseName(p: String) = p.split('/', '\\').lastOrNull() ?: p

fun parseMd(text: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = text.split("\n")
    var i = 0
    val paraBuf = mutableListOf<String>()
    fun flushPara() {
        if (paraBuf.isEmpty()) return
        out += MdBlock.Para(paraBuf.joinToString("\n"))
        paraBuf.clear()
    }
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        if (t.startsWith("```")) {
            flushPara()
            val code = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) { code += lines[i]; i++ }
            out += MdBlock.Pre(code.joinToString("\n"))
            i++
            continue
        }
        if (t.isEmpty()) { flushPara(); i++; continue }
        val toks = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.isNotEmpty() && toks.all(::isAttachRef)) {
            flushPara()
            val names = toks.map { baseName(it.drop(1).trimEnd('.', ',', ';', ':', '!', '?', ')')) }
            val last = out.lastOrNull()
            if (last is MdBlock.Chips) out[out.lastIndex] = MdBlock.Chips(last.names + names)
            else out += MdBlock.Chips(names)
            i++
            continue
        }
        val h = Regex("^(#{1,3})\\s+(.*)").matchEntire(t)
        if (h != null) { flushPara(); out += MdBlock.Head(h.groupValues[1].length, h.groupValues[2]); i++; continue }
        if (t == "---" || t == "***" || t == "___") { flushPara(); out += MdBlock.Hr; i++; continue }
        if (t.startsWith(">")) {
            flushPara()
            val q = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                q += lines[i].trim().removePrefix(">").trimStart()
                i++
            }
            out += MdBlock.Quote(q.joinToString("\n"))
            continue
        }
        val li = Regex("^([-*+]|\\d+[.)])\\s+(.*)").matchEntire(t)
        if (li != null) {
            flushPara()
            val items = mutableListOf<String>()
            var ordered = Regex("^\\d+[.)]").matches(li.groupValues[1])
            while (i < lines.size) {
                val m = Regex("^([-*+]|\\d+[.)])\\s+(.*)").matchEntire(lines[i].trim()) ?: break
                items += m.groupValues[2]
                i++
            }
            out += MdBlock.ListBlock(items, ordered)
            continue
        }
        if (t.startsWith("|") && t.endsWith("|")) {
            flushPara()
            val tbl = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith("|")) { tbl += lines[i].trim(); i++ }
            out += MdBlock.Table(tbl.joinToString("\n"))
            continue
        }
        paraBuf += line
        i++
    }
    flushPara()
    return out
}

/** Satır içi: `kod`, **kalın**, [ad](url), ![alt](url). */
fun inline(text: String, base: Color = Damen.Fg): AnnotatedString {
    val noImg = text.replace(Regex("""!\[([^\]]*)\]\([^)]*\)"""), "[görsel: $1]")
    return buildAnnotatedString {
        var i = 0
        fun pushRun(s: String, style: SpanStyle? = null, url: String? = null) {
            if (url != null) pushStringAnnotation("URL", url)
            if (style != null) pushStyle(style)
            append(s)
            if (style != null) pop()
            if (url != null) pop()
        }
        while (i < noImg.length) {
            val link = Regex("""\[([^\]]+)\]\(([^)]+)\)""").find(noImg, i)
            val code = noImg.indexOf("`", i)
            val bold = noImg.indexOf("**", i)
            val next = listOfNotNull(
                link?.range?.first?.let { it to "link" },
                code.takeIf { it >= 0 }?.let { it to "code" },
                bold.takeIf { it >= 0 }?.let { it to "bold" },
            ).minByOrNull { it.first }
            if (next == null) { append(noImg.substring(i)); break }
            if (next.first > i) append(noImg.substring(i, next.first))
            when (next.second) {
                "link" -> {
                    val m = link!!
                    pushRun(m.groupValues[1], SpanStyle(color = base, textDecoration = TextDecoration.Underline), m.groupValues[2])
                    i = m.range.last + 1
                }
                "code" -> {
                    val end = noImg.indexOf("`", code + 1)
                    if (end < 0) { append(noImg.substring(i)); break }
                    pushRun(noImg.substring(code + 1, end), SpanStyle(color = base, fontFamily = FontFamily.Monospace, background = Damen.Surface2))
                    i = end + 1
                }
                else -> {
                    val end = noImg.indexOf("**", bold + 2)
                    if (end < 0) { append(noImg.substring(i)); break }
                    pushRun(noImg.substring(bold + 2, end), SpanStyle(fontWeight = FontWeight.Bold, color = base))
                    i = end + 2
                }
            }
        }
    }
}

@Composable
fun ChipRow(names: List<String>, onRemove: ((Int) -> Unit)? = null) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        names.forEachIndexed { idx, n ->
            val dot = n.lastIndexOf(".")
            val base = if (dot > 0) n.slice(0 until dot) else n
            val ext = if (dot > 0) n.slice(dot until n.length) else ""
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.border(1.dp, Damen.Line).padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("▪", fontSize = 11.sp, color = Damen.Dim, fontFamily = Damen.Mono)
                    Text(base, fontSize = 11.sp, color = Damen.Fg, fontFamily = Damen.Mono)
                    if (ext.isNotEmpty()) Text(ext.uppercase(), fontSize = 11.sp, color = Damen.Faint, fontFamily = Damen.Mono)
                    if (onRemove != null) {
                        androidx.compose.material3.TextButton(onClick = { onRemove(idx) }, contentPadding = PaddingValues(0.dp)) {
                            Text("✕", fontSize = 11.sp, color = Damen.Faint, fontFamily = Damen.Mono)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MdBody(text: String) {
    // Parse metin başına bir kez (canlı akışta her kare değil)
    val blocks = remember(text) { parseMd(text) }
    MdBlocks(blocks)
}

@Composable
private fun MdBlocks(blocks: List<MdBlock>) {
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in blocks) {
            when (b) {
                is MdBlock.Para -> {
                    val ann = remember(b.text) { inline(b.text) }
                    ClickableText(
                        text = ann,
                        style = androidx.compose.ui.text.TextStyle(fontFamily = Damen.Mono, fontSize = 14.sp, lineHeight = 21.sp, color = Damen.Fg),
                        onClick = { off ->
                            ann.getStringAnnotations("URL", off, off).firstOrNull()?.let {
                                try { uri.openUri(it.item) } catch (_: Exception) { }
                            }
                        },
                    )
                }
                is MdBlock.Head -> Text(
                    b.text.uppercase(), fontFamily = Damen.Mono, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp, color = Damen.Fg,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is MdBlock.Pre -> Text(
                    b.code,
                    fontFamily = Damen.Mono, fontSize = 13.sp, color = Damen.Fg,
                    modifier = Modifier.fillMaxWidth()
                        .background(Damen.Surface)
                        .border(1.dp, Damen.LineDim)
                        .padding(10.dp, 12.dp)
                        .horizontalScroll(rememberScrollState()),
                )
                is MdBlock.Quote -> androidx.compose.foundation.layout.Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                ) {
                    Spacer(modifier = Modifier.width(1.dp).fillMaxHeight().background(Damen.Line))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(b.text, fontFamily = Damen.Mono, fontSize = 13.sp, color = Damen.Dim)
                }
                is MdBlock.ListBlock -> Column {
                    b.items.forEachIndexed { k, item ->
                        val ann = remember(item) { inline(item) }
                        androidx.compose.foundation.layout.Row {
                            Text(
                                if (b.ordered) "${k + 1}. " else "• ",
                                fontFamily = Damen.Mono, fontSize = 14.sp, color = Damen.Dim,
                            )
                            Text(
                                ann,
                                style = androidx.compose.ui.text.TextStyle(
                                    fontFamily = Damen.Mono, fontSize = 14.sp, color = Damen.Fg,
                                ),
                            )
                        }
                    }
                }
                is MdBlock.Table -> Text(
                    b.text,
                    fontFamily = Damen.Mono, fontSize = 12.sp, color = Damen.Fg,
                    modifier = Modifier.fillMaxWidth()
                        .border(1.dp, Damen.LineDim)
                        .padding(6.dp, 10.dp)
                        .horizontalScroll(rememberScrollState()),
                )
                is MdBlock.Chips -> ChipRow(b.names)
                MdBlock.Hr -> Divider(color = Damen.LineDim, thickness = 1.dp)
            }
        }
    }
}
