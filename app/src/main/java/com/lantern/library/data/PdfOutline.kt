package com.lantern.library.data

import java.io.File
import java.util.zip.Inflater

/**
 * Reads a PDF document outline (bookmarks) into [TocEntry] rows.
 * [TocEntry.chapterIndex] is the 0-based page index when the destination resolves.
 * Returns an empty list when the file has no outline or the outline cannot be parsed.
 */
object PdfOutline {
    private const val MAX_FILE = 48L * 1024 * 1024
    private const val MAX_ITEMS = 400

    fun read(file: File): List<TocEntry> {
        if (!file.exists() || file.length() < 16L || file.length() > MAX_FILE) return emptyList()
        return runCatching { Parser(file.readBytes()).outlines() }.getOrDefault(emptyList())
    }

    private class Parser(private val data: ByteArray) {
        private val size = data.size
        private val xref = HashMap<Int, Long>()
        private val cache = HashMap<Int, PdfVal>()
        private val objStm = HashMap<Int, Int>() // objNum -> stream obj that contains it
        private var rootRef = -1

        fun outlines(): List<TocEntry> {
            if (!parseXref()) return emptyList()
            val catalog = asDict(resolve(rootRef)) ?: return emptyList()
            val outlinesRef = asRef(catalog["Outlines"]) ?: return emptyList()
            val outlines = asDict(resolve(outlinesRef.n)) ?: return emptyList()
            val first = asRef(outlines["First"]) ?: return emptyList()
            val pages = pageIndex()
            val dests = namedDests(catalog)
            val out = ArrayList<TocEntry>()
            walk(first.n, 0, pages, dests, out, HashSet())
            return out
        }

        private fun walk(
            obj: Int,
            level: Int,
            pages: Map<Int, Int>,
            dests: Map<String, Int>,
            out: MutableList<TocEntry>,
            seen: HashSet<Int>
        ) {
            var cur = obj
            var hops = 0
            while (cur > 0 && hops++ < MAX_ITEMS && out.size < MAX_ITEMS) {
                if (!seen.add(cur)) break
                val dict = asDict(resolve(cur)) ?: break
                val title = pdfString(dict["Title"]).trim()
                val page = destPage(dict["Dest"] ?: actionDest(dict["A"]), pages, dests)
                if (title.isNotEmpty()) {
                    out += TocEntry(title, href = "", level = level.coerceAtLeast(0), chapterIndex = page)
                }
                val child = asRef(dict["First"])
                if (child != null && level < 12) {
                    walk(child.n, level + 1, pages, dests, out, seen)
                }
                cur = asRef(dict["Next"])?.n ?: break
            }
        }

        private fun actionDest(action: PdfVal?): PdfVal? {
            val dict = asDict(action) ?: return null
            val s = asName(dict["S"])
            if (s != null && s != "GoTo") return null
            return dict["D"]
        }

        private fun destPage(dest: PdfVal?, pages: Map<Int, Int>, dests: Map<String, Int>): Int {
            if (dest == null || dest is PdfVal.Null) return -1
            when (dest) {
                is PdfVal.Name -> return dests[dest.v] ?: -1
                is PdfVal.Str -> return dests[dest.v] ?: -1
                is PdfVal.Ref -> {
                    val inner = resolve(dest.n)
                    return destPage(inner, pages, dests)
                }
                is PdfVal.Arr -> {
                    if (dest.v.isEmpty()) return -1
                    val first = dest.v[0]
                    if (first is PdfVal.Ref) return pages[first.n] ?: -1
                    if (first is PdfVal.Num) {
                        val n = first.v.toInt()
                        return when {
                            n in pages.values -> n
                            n - 1 in pages.values -> n - 1
                            else -> -1
                        }
                    }
                    if (first is PdfVal.Name || first is PdfVal.Str) return destPage(first, pages, dests)
                    return -1
                }
                is PdfVal.Dict -> {
                    val d = dest.v["D"] ?: dest.v["Dest"]
                    return destPage(d, pages, dests)
                }
                else -> return -1
            }
        }

        private fun namedDests(catalog: Map<String, PdfVal>): Map<String, Int> {
            val out = HashMap<String, Int>()
            val pages = pageIndex()
            fun add(name: String, dest: PdfVal?) {
                val key = name.trim()
                if (key.isEmpty()) return
                val page = destPage(dest, pages, out)
                if (page >= 0) out[key] = page
            }
            asDict(catalog["Dests"])?.forEach { (k, v) -> add(k, v) }
            val names = asDict(catalog["Names"])
            val destTree = names?.get("Dests")?.let { if (it is PdfVal.Ref) resolve(it.n) else it }
            collectNameTree(destTree, pages, out)
            return out
        }

        private fun collectNameTree(node: PdfVal?, pages: Map<Int, Int>, out: MutableMap<String, Int>) {
            val dict = asDict(node) ?: return
            val names = asArr(dict["Names"])
            if (names != null) {
                var i = 0
                while (i + 1 < names.size) {
                    val key = pdfString(names[i]).ifEmpty { asName(names[i]).orEmpty() }
                    val dest = names[i + 1]
                    val page = destPage(dest, pages, out)
                    if (key.isNotEmpty() && page >= 0) out[key] = page
                    i += 2
                }
            }
            asArr(dict["Kids"])?.forEach { kid ->
                val n = asRef(kid)?.n ?: return@forEach
                collectNameTree(resolve(n), pages, out)
            }
        }

        private fun pageIndex(): Map<Int, Int> {
            val catalog = asDict(resolve(rootRef)) ?: return emptyMap()
            val pagesRef = asRef(catalog["Pages"]) ?: return emptyMap()
            val out = LinkedHashMap<Int, Int>()
            val stack = ArrayList<Int>()
            stack += pagesRef.n
            val seen = HashSet<Int>()
            while (stack.isNotEmpty() && out.size < 20000) {
                val id = stack.removeAt(stack.lastIndex)
                if (!seen.add(id)) continue
                val dict = asDict(resolve(id)) ?: continue
                when (asName(dict["Type"])) {
                    "Page" -> out[id] = out.size
                    else -> {
                        val kids = asArr(dict["Kids"]) ?: continue
                        for (i in kids.size - 1 downTo 0) {
                            asRef(kids[i])?.n?.let { stack += it }
                        }
                    }
                }
            }
            return out
        }

        private fun parseXref(): Boolean {
            val start = startXref() ?: return false
            var offset = start
            val visited = HashSet<Long>()
            while (offset >= 0 && visited.add(offset)) {
                val i = offset.toInt().coerceIn(0, size - 1)
                if (matchAt(i, "xref")) {
                    offset = parseXrefTable(i)
                } else {
                    val obj = readObjectAt(i) ?: break
                    val stream = obj as? PdfVal.Stream ?: break
                    parseXrefStream(stream)
                    val prev = asNum(stream.dict["Prev"])?.toLong()
                    offset = prev ?: -1L
                    asRef(stream.dict["Root"])?.let { rootRef = it.n }
                }
            }
            if (rootRef < 0) return false
            return true
        }

        private fun parseXrefTable(at: Int): Long {
            var i = at + 4
            i = skipWs(i)
            while (i < size && !matchAt(i, "trailer")) {
                val startTok = parseNumber(i) ?: break
                i = startTok.second
                i = skipWs(i)
                val countTok = parseNumber(i) ?: break
                i = countTok.second
                i = skipWs(i)
                val startObj = startTok.first.toInt()
                val count = countTok.first.toInt().coerceAtLeast(0)
                repeat(count) { n ->
                    if (i + 18 >= size) return -1L
                    val line = latin(i, 20)
                    val off = line.substring(0, 10).trim().toLongOrNull()
                    val flag = if (line.length > 17) line[17] else 'n'
                    if (off != null && flag == 'n') xref[startObj + n] = off
                    i += 20
                    if (i < size && (data[i] == '\n'.code.toByte() || data[i] == '\r'.code.toByte())) {
                        i++
                        if (i < size && data[i - 1] == '\r'.code.toByte() && data[i] == '\n'.code.toByte()) i++
                    }
                }
                i = skipWs(i)
            }
            if (!matchAt(i, "trailer")) return -1L
            i = skipWs(i + 7)
            val trailer = parseValue(i)?.first as? PdfVal.Dict ?: return -1L
            asRef(trailer.v["Root"])?.let { rootRef = it.n }
            return asNum(trailer.v["Prev"])?.toLong() ?: -1L
        }

        private fun parseXrefStream(stream: PdfVal.Stream) {
            val w = asArr(stream.dict["W"])?.mapNotNull { asNum(it)?.toInt() } ?: return
            if (w.size < 3) return
            val sizeCount = asNum(stream.dict["Size"])?.toInt() ?: return
            val index = asArr(stream.dict["Index"])?.mapNotNull { asNum(it)?.toInt() }
                ?: listOf(0, sizeCount)
            val decoded = decodeStream(stream) ?: return
            var p = 0
            fun readW(width: Int): Long {
                if (width <= 0) return 0L
                var v = 0L
                repeat(width) {
                    if (p >= decoded.size) return 0L
                    v = (v shl 8) or (decoded[p].toInt() and 0xFF).toLong()
                    p++
                }
                return v
            }
            var k = 0
            while (k + 1 < index.size) {
                val start = index[k]
                val count = index[k + 1]
                repeat(count) { n ->
                    val type = if (w[0] == 0) 1L else readW(w[0])
                    val f2 = readW(w[1])
                    readW(w[2])
                    val obj = start + n
                    when (type.toInt()) {
                        1 -> xref[obj] = f2
                        2 -> objStm[obj] = f2.toInt()
                    }
                }
                k += 2
            }
        }

        private fun startXref(): Long? {
            val from = (size - 1024).coerceAtLeast(0)
            val tail = latin(from, size - from)
            val idx = tail.lastIndexOf("startxref")
            if (idx < 0) return null
            val rest = tail.substring(idx + 9)
            val num = Regex("\\d+").find(rest)?.value?.toLongOrNull() ?: return null
            return num.takeIf { it in 0 until size.toLong() }
        }

        private fun resolve(obj: Int): PdfVal {
            cache[obj]?.let { return it }
            val loaded = readObject(obj) ?: PdfVal.Null
            cache[obj] = loaded
            return loaded
        }

        private fun readObject(obj: Int): PdfVal? {
            val off = xref[obj]
            if (off != null) return readObjectAt(off.toInt())
            val stmId = objStm[obj] ?: return null
            return readFromObjStm(stmId, obj)
        }

        private fun readObjectAt(offset: Int): PdfVal? {
            var i = skipWs(offset)
            val n = parseNumber(i) ?: return null
            i = skipWs(n.second)
            val g = parseNumber(i) ?: return null
            i = skipWs(g.second)
            if (!matchAt(i, "obj")) return null
            i = skipWs(i + 3)
            val parsed = parseValue(i) ?: return null
            i = skipWs(parsed.second)
            if (parsed.first is PdfVal.Dict && matchAt(i, "stream")) {
                val dict = (parsed.first as PdfVal.Dict).v
                i = afterStreamKeyword(i)
                val length = streamLength(dict)
                val end = if (length != null) (i + length).coerceAtMost(size) else findEndstream(i)
                val bytes = data.copyOfRange(i, end)
                return PdfVal.Stream(dict, bytes)
            }
            return parsed.first
        }

        private fun readFromObjStm(stmId: Int, want: Int): PdfVal? {
            val stm = resolve(stmId) as? PdfVal.Stream ?: return null
            val n = asNum(stm.dict["N"])?.toInt() ?: return null
            val first = asNum(stm.dict["First"])?.toInt() ?: return null
            val decoded = decodeStream(stm) ?: return null
            val headEnd = first.coerceIn(0, decoded.size)
            val head = String(decoded, 0, headEnd, Charsets.ISO_8859_1)
            val nums = Regex("\\d+").findAll(head).map { it.value.toInt() }.toList()
            var idx = -1
            var k = 0
            while (k + 1 < nums.size && k / 2 < n) {
                if (nums[k] == want) {
                    idx = nums[k + 1]
                    break
                }
                k += 2
            }
            if (idx < 0) return null
            val inner = Parser(decoded).parseValue(first + idx)?.first
            if (inner != null) cache[want] = inner
            return inner
        }

        private fun streamLength(dict: Map<String, PdfVal>): Int? {
            val v = dict["Length"] ?: return null
            val n = asNum(v)
            if (n != null) return n.toInt().coerceAtLeast(0)
            val ref = asRef(v) ?: return null
            return asNum(resolve(ref.n))?.toInt()?.coerceAtLeast(0)
        }

        private fun afterStreamKeyword(i: Int): Int {
            var p = i + 6
            if (p < size && data[p] == '\r'.code.toByte()) p++
            if (p < size && data[p] == '\n'.code.toByte()) p++
            return p
        }

        private fun findEndstream(from: Int): Int {
            var i = from
            while (i + 9 <= size) {
                if (matchAt(i, "endstream")) return i
                i++
            }
            return size
        }

        private fun decodeStream(stream: PdfVal.Stream): ByteArray? {
            val filter = stream.dict["Filter"]
            val names = when (filter) {
                is PdfVal.Name -> listOf(filter.v)
                is PdfVal.Arr -> filter.v.mapNotNull { asName(it) }
                else -> emptyList()
            }
            var bytes = stream.data
            if (names.isEmpty()) return bytes
            names.forEach { name ->
                bytes = when (name) {
                    "FlateDecode", "Fl" -> inflate(bytes) ?: return null
                    else -> return null
                }
            }
            return bytes
        }

        private fun inflate(src: ByteArray): ByteArray? {
            val inf = Inflater()
            return try {
                inf.setInput(src)
                val out = java.io.ByteArrayOutputStream(src.size.coerceAtLeast(64))
                val buf = ByteArray(4096)
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0) {
                        if (inf.needsInput()) break
                        if (inf.needsDictionary()) return null
                    } else out.write(buf, 0, n)
                    if (out.size() > 16 * 1024 * 1024) return null
                }
                out.toByteArray()
            } catch (_: Exception) {
                try {
                    val raw = Inflater(true)
                    raw.setInput(src)
                    val out = java.io.ByteArrayOutputStream(src.size.coerceAtLeast(64))
                    val buf = ByteArray(4096)
                    while (!raw.finished()) {
                        val n = raw.inflate(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                } catch (_: Exception) {
                    null
                }
            } finally {
                inf.end()
            }
        }

        private fun parseValue(start: Int): Pair<PdfVal, Int>? {
            val i = skipWs(start)
            if (i >= size) return null
            val c = data[i].toInt() and 0xFF
            return when {
                c == '/'.code -> parseName(i)
                c == '('.code -> parseLiteral(i)
                c == '<'.code && i + 1 < size && data[i + 1] == '<'.code.toByte() -> parseDict(i)
                c == '<'.code -> parseHex(i)
                c == '['.code -> parseArray(i)
                c == 't'.code && matchAt(i, "true") -> PdfVal.Bool(true) to i + 4
                c == 'f'.code && matchAt(i, "false") -> PdfVal.Bool(false) to i + 5
                c == 'n'.code && matchAt(i, "null") -> PdfVal.Null to i + 4
                c == '+'.code || c == '-'.code || c == '.'.code || c in '0'.code..'9'.code -> parseNumberOrRef(i)
                else -> null
            }
        }

        private fun parseNumberOrRef(i: Int): Pair<PdfVal, Int>? {
            val n1 = parseNumber(i) ?: return null
            val after = skipWs(n1.second)
            val n2 = parseNumber(after)
            if (n2 != null) {
                val rpos = skipWs(n2.second)
                if (matchAt(rpos, "R") && (rpos + 1 >= size || isDelim(data[rpos + 1].toInt() and 0xFF))) {
                    return PdfVal.Ref(n1.first.toInt(), n2.first.toInt()) to rpos + 1
                }
            }
            return PdfVal.Num(n1.first) to n1.second
        }

        private fun parseName(i: Int): Pair<PdfVal, Int> {
            var p = i + 1
            val sb = StringBuilder()
            while (p < size) {
                val c = data[p].toInt() and 0xFF
                if (isDelim(c) || isWs(c)) break
                if (c == '#'.code && p + 2 < size) {
                    val hex = latin(p + 1, 2)
                    val v = hex.toIntOrNull(16)
                    if (v != null) {
                        sb.append(v.toChar())
                        p += 3
                        continue
                    }
                }
                sb.append(c.toChar())
                p++
            }
            return PdfVal.Name(sb.toString()) to p
        }

        private fun parseLiteral(i: Int): Pair<PdfVal, Int> {
            var p = i + 1
            var depth = 1
            val bytes = ArrayList<Byte>()
            while (p < size && depth > 0) {
                val c = data[p].toInt() and 0xFF
                if (c == '\\'.code) {
                    if (p + 1 >= size) break
                    val n = data[p + 1].toInt() and 0xFF
                    when (n.toChar()) {
                        'n' -> { bytes += '\n'.code.toByte(); p += 2 }
                        'r' -> { bytes += '\r'.code.toByte(); p += 2 }
                        't' -> { bytes += '\t'.code.toByte(); p += 2 }
                        'b' -> { bytes += 8.toByte(); p += 2 }
                        'f' -> { bytes += 12.toByte(); p += 2 }
                        '(', ')', '\\' -> { bytes += n.toByte(); p += 2 }
                        '\n', '\r' -> {
                            p += 2
                            if (n == '\r'.code && p < size && data[p] == '\n'.code.toByte()) p++
                        }
                        else -> {
                            if (n in '0'.code..'7'.code) {
                                var v = n - '0'.code
                                var k = 0
                                p += 2
                                while (k < 2 && p < size) {
                                    val d = data[p].toInt() and 0xFF
                                    if (d !in '0'.code..'7'.code) break
                                    v = v * 8 + (d - '0'.code)
                                    p++
                                    k++
                                }
                                bytes += v.toByte()
                            } else {
                                bytes += n.toByte()
                                p += 2
                            }
                        }
                    }
                } else if (c == '('.code) {
                    depth++; bytes += c.toByte(); p++
                } else if (c == ')'.code) {
                    depth--
                    if (depth > 0) bytes += c.toByte()
                    p++
                } else {
                    bytes += c.toByte(); p++
                }
            }
            return PdfVal.Str(decodePdfBytes(bytes.toByteArray())) to p
        }

        private fun parseHex(i: Int): Pair<PdfVal, Int> {
            var p = i + 1
            val hex = StringBuilder()
            while (p < size && data[p] != '>'.code.toByte()) {
                val c = data[p].toInt() and 0xFF
                if (!isWs(c)) hex.append(c.toChar())
                p++
            }
            if (p < size && data[p] == '>'.code.toByte()) p++
            val s = if (hex.length % 2 == 1) hex.append('0') else hex
            val bytes = ByteArray(s.length / 2)
            var b = 0
            while (b < bytes.size) {
                bytes[b] = s.substring(b * 2, b * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
                b++
            }
            return PdfVal.Str(decodePdfBytes(bytes)) to p
        }

        private fun parseArray(i: Int): Pair<PdfVal, Int>? {
            var p = i + 1
            val items = ArrayList<PdfVal>()
            while (p < size) {
                p = skipWs(p)
                if (p < size && data[p] == ']'.code.toByte()) return PdfVal.Arr(items) to p + 1
                val v = parseValue(p) ?: return PdfVal.Arr(items) to p
                items += v.first
                p = v.second
            }
            return PdfVal.Arr(items) to p
        }

        private fun parseDict(i: Int): Pair<PdfVal, Int>? {
            var p = i + 2
            val map = LinkedHashMap<String, PdfVal>()
            while (p < size) {
                p = skipWs(p)
                if (matchAt(p, ">>")) return PdfVal.Dict(map) to p + 2
                val key = parseValue(p) ?: break
                val name = (key.first as? PdfVal.Name)?.v ?: break
                val value = parseValue(key.second) ?: break
                map[name] = value.first
                p = value.second
            }
            return PdfVal.Dict(map) to p
        }

        private fun parseNumber(i: Int): Pair<Double, Int>? {
            var p = skipWs(i)
            if (p >= size) return null
            val start = p
            if (data[p] == '+'.code.toByte() || data[p] == '-'.code.toByte()) p++
            var seen = false
            while (p < size) {
                val c = data[p].toInt() and 0xFF
                if (c in '0'.code..'9'.code || c == '.'.code) {
                    seen = true
                    p++
                } else break
            }
            if (!seen) return null
            val v = latin(start, p - start).toDoubleOrNull() ?: return null
            return v to p
        }

        private fun skipWs(from: Int): Int {
            var i = from
            while (i < size) {
                val c = data[i].toInt() and 0xFF
                if (c == '%'.code) {
                    i++
                    while (i < size && data[i] != '\n'.code.toByte() && data[i] != '\r'.code.toByte()) i++
                    continue
                }
                if (!isWs(c)) break
                i++
            }
            return i
        }

        private fun matchAt(i: Int, s: String): Boolean {
            if (i < 0 || i + s.length > size) return false
            for (k in s.indices) if (data[i + k].toInt().toChar() != s[k]) return false
            return true
        }

        private fun latin(from: Int, len: Int): String {
            val end = (from + len).coerceAtMost(size).coerceAtLeast(from)
            return String(data, from, end - from, Charsets.ISO_8859_1)
        }
    }

    private sealed class PdfVal {
        data class Num(val v: Double) : PdfVal()
        data class Name(val v: String) : PdfVal()
        data class Str(val v: String) : PdfVal()
        data class Ref(val n: Int, val g: Int) : PdfVal()
        data class Arr(val v: List<PdfVal>) : PdfVal()
        data class Dict(val v: Map<String, PdfVal>) : PdfVal()
        data class Stream(val dict: Map<String, PdfVal>, val data: ByteArray) : PdfVal()
        data class Bool(val v: Boolean) : PdfVal()
        object Null : PdfVal()
    }

    private fun asDict(v: PdfVal?): Map<String, PdfVal>? = when (v) {
        is PdfVal.Dict -> v.v
        is PdfVal.Stream -> v.dict
        else -> null
    }

    private fun asArr(v: PdfVal?): List<PdfVal>? = (v as? PdfVal.Arr)?.v
    private fun asRef(v: PdfVal?): PdfVal.Ref? = v as? PdfVal.Ref
    private fun asName(v: PdfVal?): String? = (v as? PdfVal.Name)?.v
    private fun asNum(v: PdfVal?): Double? = (v as? PdfVal.Num)?.v

    private fun pdfString(v: PdfVal?): String = when (v) {
        is PdfVal.Str -> v.v
        is PdfVal.Name -> v.v
        else -> ""
    }

    private fun decodePdfBytes(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return runCatching { String(bytes, Charsets.UTF_16BE) }.getOrDefault("")
        }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun isWs(c: Int): Boolean =
        c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32

    private fun isDelim(c: Int): Boolean =
        c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
            c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code ||
            c == '/'.code || c == '%'.code
}
