package ir.hermes.mobile.core.runtime

import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream

/**
 * استخراجکننده جریانی tar.gz برای rootfs هرمس.
 * بخشهای جداشده gzip بهصورت یک جریان پیوسته خوانده میشوند؛ هدرهای
 * GNU طولانی (L/K) و pax و symlink مطلق پشتیبانی میشوند.
 * مجوز پوشهها در پایان (از عمیق به کمعمق) اعمال میشود.
 */
object TarExtractor {

    private const val BLOCK = 512
    private const val COPY_BUF = 128 * 1024
    private const val PROGRESS_EVERY = 512L * 1024

    private class CountingInput(
        private val src: InputStream,
        private val onRead: (Long) -> Unit,
    ) : InputStream() {
        private var total = 0L
        private var since = 0L
        override fun read(): Int {
            val r = src.read()
            if (r >= 0) bump(1)
            return r
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = src.read(b, off, len)
            if (r > 0) bump(r)
            return r
        }
        private fun bump(n: Int) {
            total += n
            since += n
            if (since >= PROGRESS_EVERY) {
                since = 0
                onRead(total)
            }
        }
        fun finish() = onRead(total)
    }

    fun extract(parts: List<Path>, dest: Path, stripComponents: Int = 1, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val totalBytes = parts.sumOf { Files.size(it) }
        val mkdirs = ArrayList<Path>()
        val fallbackDirs = ArrayList<Path>()
        java.io.BufferedInputStream(
            java.io.SequenceInputStream(java.util.Collections.enumeration(parts.map { Files.newInputStream(it) })),
            COPY_BUF * 2,
        ).use { concatenated ->
            GZIPInputStream(CountingInput(concatenated, { read -> onProgress(read, totalBytes) }), COPY_BUF).use { gz ->
                val input = java.io.BufferedInputStream(gz, COPY_BUF * 2)
                tarLoop(input, dest, stripComponents, mkdirs, fallbackDirs)
            }
        }
        onProgress(totalBytes, totalBytes)
        for (dir in mkdirs.sortedByDescending { it.nameCount }) {
            runCatching { Files.setPosixFilePermissions(dir, defaultDirPerms) }
        }
        for (dir in fallbackDirs) {
            runCatching {
                val u = dir.resolve("u").toFile()
                u.mkdirs()
                u.delete()
            }
        }
    }

    private val defaultDirPerms = setOf(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE,
    )

    private fun tarLoop(
        input: java.io.BufferedInputStream,
        dest: Path,
        strip: Int,
        mkdirs: MutableList<Path>,
        fallbackDirs: MutableList<Path>,
    ) {
        val header = ByteArray(BLOCK)
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        while (true) {
            if (!readFully(input, header)) return
            if (header.all { it.toInt() == 0 }) {
                if (!readFully(input, header)) return
                if (header.all { it.toInt() == 0 }) return
                continue
            }
            val type = header[156].toInt().toChar()
            val size = sizeOctal(header, 124, 12)
            if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                val body = readBody(input, size)
                val text = body.toString(Charsets.UTF_8).trimEnd('\u0000')
                when (type) {
                    'L' -> longName = text
                    'K' -> longLink = text
                    else -> paxPath = paxPath(text, "path") ?: paxPath
                }
                continue
            }
            var name = str(header, 0, 100)
            if (name == "./" || name == ".") name = ""
            val prefix = str(header, 345, 155)
            if (prefix.isNotEmpty()) name = "$prefix/$name"
            var link = str(header, 157, 100)
            longName?.let { name = it; longName = null }
            longLink?.let { link = it; longLink = null }
            paxPath?.let { name = it; paxPath = null }
            val safeName = sanitize(name, strip)
            if (safeName != null) {
                val target = safeResolve(dest, safeName)
                when (type) {
                    '5' -> {
                        createDir(target)
                        mkdirs.add(target)
                        if (!Files.isWritable(target)) fallbackDirs.add(target)
                    }
                    '2' -> {
                        createDir(target.parent)
                        runCatching { Files.deleteIfExists(target) }
                        Files.createSymbolicLink(target, Paths.get(if (link.isEmpty()) "." else link))
                    }
                    '0', '\u0000', '7' -> {
                        createDir(target.parent)
                        runCatching { Files.deleteIfExists(target) }
                        Files.newOutputStream(target).use { out -> copyExactly(input, out, size) }
                        applyMode(target, modeOf(header))
                    }
                    else -> skipExactly(input, size)
                }
            } else {
                skipExactly(input, size)
            }
            val rem = size % BLOCK
            if (rem != 0L) skipExactly(input, BLOCK - rem)
        }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) return false
            if (r == 0) continue
            off += r
        }
        return true
    }

    private fun readBody(input: java.io.InputStream, size: Long): ByteArray {
        var s = size
        if (s > 1 shl 20) { skipExactly(input, s); return ByteArray(0) }
        val buf = ByteArray(s.toInt())
        readFully(input, buf)
        val rem = size % BLOCK
        if (rem != 0L) skipExactly(input, BLOCK - rem)
        return buf
    }

    private fun copyExactly(input: java.io.InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(COPY_BUF)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("tar truncated")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skipExactly(input: java.io.InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n > 0) { left -= n; continue }
            if (input.read() < 0) throw IOException("tar truncated skip")
            left--
        }
    }

    private fun sizeOctal(h: ByteArray, off: Int, len: Int): Long {
        var s = 0L
        var i = off
        val end = off + len
        while (i < end) {
            val c = h[i].toInt().toChar()
            if (c == ' ' || c == '\u0000') break
            if (c < '0' || c > '7') break
            s = (s shl 3) + (c - '0')
            i++
        }
        return s
    }

    private fun modeOf(h: ByteArray): Int = sizeOctal(h, 100, 8).toInt()

    private fun str(b: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = off + len
        while (end < max && b[end].toInt() != 0) end++
        if (end == off) return ""
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun sanitize(path: String, strip: Int): String? {
        var p = path.replace('\\', '/')
        while (p.startsWith("./")) p = p.substring(2)
        if (p.startsWith("/")) p = p.substring(1)
        if (p.isEmpty()) return null
        val comps = p.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        if (comps.size <= strip) return null
        return comps.drop(strip).joinToString("/")
    }

    private fun safeResolve(dest: Path, rel: String): Path {
        val target = dest.resolve(rel).normalize()
        if (!target.startsWith(dest.normalize())) throw IOException("unsafe path in tar: $rel")
        return target
    }

    private fun createDir(dir: Path?) {
        if (dir == null) return
        Files.createDirectories(dir)
    }

    private fun applyMode(path: Path, mode: Int) {
        val perms = LinkedHashSet<PosixFilePermission>()
        if (mode and 0b100_000_000 != 0) perms.add(PosixFilePermission.OWNER_READ)
        if (mode and 0b010_000_000 != 0) perms.add(PosixFilePermission.OWNER_WRITE)
        if (mode and 0b001_000_000 != 0) perms.add(PosixFilePermission.OWNER_EXECUTE)
        if (mode and 0b000_100_000 != 0) perms.add(PosixFilePermission.GROUP_READ)
        if (mode and 0b000_010_000 != 0) perms.add(PosixFilePermission.GROUP_WRITE)
        if (mode and 0b000_001_000 != 0) perms.add(PosixFilePermission.GROUP_EXECUTE)
        if (mode and 0b000_000_100 != 0) perms.add(PosixFilePermission.OTHERS_READ)
        if (mode and 0b000_000_010 != 0) perms.add(PosixFilePermission.OTHERS_WRITE)
        if (mode and 0b000_000_001 != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE)
        runCatching { Files.setPosixFilePermissions(path, perms) }
    }

    private fun paxPath(text: String, key: String): String? {
        // فرمت pax: "LEN key=value\n"
        var i = 0
        while (i < text.length) {
            val sp = text.indexOf(' ', i)
            if (sp < 0) break
            val len = text.substring(i, sp).toIntOrNull() ?: break
            val rec = text.substring(sp + 1, minOf(text.length, i + len)).trimEnd('\n')
            if (rec.startsWith("$key=")) return rec.substring(key.length + 1)
            i += len
            if (i <= sp) break
        }
        return null
    }
}
