package com.damen.asistan

/** web/app.js renderTextWithChips + marked mini-uyarlaması (optimize, bağımlılıksız). */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
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

// Pre-compiled regexes (performans için döngü dışı tek derleme)
private val ATTACH_RE = Regex("""^@\S*/\S*$""")
private val HEAD_RE = Regex("""^(#{1,3})\s+(.*)""")
private val LIST_ITEM_RE = Regex("""^([-*+]|\d+[.)])\s+(.*)""")
private val ORDERED_NUM_RE = Regex("""^\d+[.)]""")
private val IMG_STRIP_RE = Regex("""!\[([^\]]*)\]\([^)]*\)""")
private val LINK_FIND_RE = Regex("""\[([^\]]+)\]\(([^)]+)\)""")

private fun isAttachRef(tok: String): Boolean =
    ATTACH_RE.matches(tok.trimEnd('.', ',', ';', ':', '!', '?', ')'))

private fun baseName(p: String): String =
    p.split('/', '\\').lastOrNull() ?: p

fun parseMd(text: String): List<MdBlock> {
    if (text.isEmpty()) return emptyList()
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
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                code += lines[i]
                i++
            }
            out += MdBlock.Pre(code.joinToString("\n"))
            i++
            continue
        }
        if (t.isEmpty()) {
            flushPara()
            i++
            continue
        }
        // @yol ekleri kontrolü
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
        val h = HEAD_RE.matchEntire(t)
        if (h != null) {
            flushPara()
            out += MdBlock.Head(h.groupValues[1].length, h.groupValues[2])
            i++
            continue
        }
        if (t == "---" || t == "***" || t == "___") {
            flushPara()
            out += MdBlock.Hr
            i++
            continue
        }
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
        val li = LIST_ITEM_RE.matchEntire(t)
        if (li != null) {
            flushPara()
            val items = mutableListOf<String>()
            val ordered = ORDERED_NUM_RE.matches(li.groupValues[1])
            while (i < lines.size) {
                val m = LIST_ITEM_RE.matchEntire(lines[i].trim()) ?: break
                items += m.groupValues[2]
                i++
            }
            out += MdBlock.ListBlock(items, ordered)
            continue
        }
        if (t.startsWith("|") && t.endsWith("|")) {
            flushPara()
            val tbl = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith("|")) {
                tbl += lines[i].trim()
                i++
            }
            out += MdBlock.Table(tbl.joinToString("\n"))
            continue
        }
        paraBuf += line
        i++
    }
    flushPara()
    return out
}

/** Satır içi: `kod`, **kalın**, [ad](url). Hızlı tek-geçiş parse. */
fun inline(text: String, base: Color = Damen.Fg): AnnotatedString {
    val noImg = IMG_STRIP_RE.replace(text, "[görsel: $1]")
    return buildAnnotatedString {
        var i = 0
        val len = noImg.length
        while (i < len) {
            val nextLink = LINK_FIND_RE.find(noImg, i)
            val linkIdx = nextLink?.range?.first ?: -1
            val codeIdx = noImg.indexOf('`', i)
            val boldIdx = noImg.indexOf("**", i)

            var target = len
            var type = 0 // 1: link, 2: code, 3: bold

            if (linkIdx in i until target) { target = linkIdx; type = 1 }
            if (codeIdx in i until target) { target = codeIdx; type = 2 }
            if (boldIdx in i until target) { target = boldIdx; type = 3 }

            if (type == 0) {
                append(noImg.substring(i))
                break
            }

            if (target > i) {
                append(noImg.substring(i, target))
            }

            when (type) {
                1 -> {
                    val m = nextLink!!
                    pushStringAnnotation("URL", m.groupValues[2])
                    pushStyle(SpanStyle(color = base, textDecoration = TextDecoration.Underline))
                    append(m.groupValues[1])
                    pop()
                    pop()
                    i = m.range.last + 1
                }
                2 -> {
                    val endCode = noImg.indexOf('`', target + 1)
                    if (endCode < 0) {
                        append(noImg.substring(target))
                        break
                    }
                    pushStyle(SpanStyle(color = base, fontFamily = FontFamily.Monospace, background = Damen.Surface2))
                    append(noImg.substring(target + 1, endCode))
                    pop()
                    i = endCode + 1
                }
                3 -> {
                    val endBold = noImg.indexOf("**", target + 2)
                    if (endBold < 0) {
                        append(noImg.substring(target))
                        break
                    }
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = base))
                    append(noImg.substring(target + 2, endBold))
                    pop()
                    i = endBold + 2
                }
            }
        }
    }
}

@Composable
fun ChipRow(names: List<String>, onRemove: ((Int) -> Unit)? = null) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        names.forEachIndexed { idx, n ->
            val dot = n.lastIndexOf(".")
            val base = if (dot > 0) n.slice(0 until dot) else n
            val ext = if (dot > 0) n.slice(dot until n.length) else ""
            Box(
                modifier = Modifier.background(Damen.Surface).border(1.dp, Damen.Line).padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("▪", fontSize = 11.sp, color = Damen.Dim, fontFamily = Damen.Mono)
                    Text(base, fontSize = 11.sp, color = Damen.Fg, fontFamily = Damen.Mono)
                    if (ext.isNotEmpty()) Text(ext.uppercase(), fontSize = 11.sp, color = Damen.Faint, fontFamily = Damen.Mono)
                    if (onRemove != null) {
                        Box(
                            modifier = Modifier.padding(start = 4.dp),
                        ) {
                            Text(
                                "✕", fontSize = 11.sp, color = Damen.Faint, fontFamily = Damen.Mono,
                                modifier = Modifier.padding(2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MdBody(text: String) {
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
                        style = TextStyle(fontFamily = Damen.Mono, fontSize = 14.sp, lineHeight = 21.sp, color = Damen.Fg),
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
                is MdBlock.Quote -> Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                ) {
                    Spacer(modifier = Modifier.width(2.dp).fillMaxHeight().background(Damen.Line))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(b.text, fontFamily = Damen.Mono, fontSize = 13.sp, color = Damen.Dim)
                }
                is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    b.items.forEachIndexed { k, item ->
                        val ann = remember(item) { inline(item) }
                        Row {
                            Text(
                                if (b.ordered) "${k + 1}. " else "• ",
                                fontFamily = Damen.Mono, fontSize = 14.sp, color = Damen.Dim,
                            )
                            Text(
                                ann,
                                style = TextStyle(fontFamily = Damen.Mono, fontSize = 14.sp, color = Damen.Fg),
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
