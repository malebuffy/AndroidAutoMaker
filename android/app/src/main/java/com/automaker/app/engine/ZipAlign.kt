package com.automaker.app.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class ZipAlignException(message: String) : IOException(message)

object ZipAlign {
    fun align(input: File, output: File, pageSize: Int, alignment: Int) {
        if (pageSize < 1 || alignment < 1 || pageSize and (pageSize - 1) != 0 || alignment and (alignment - 1) != 0) {
            throw ZipAlignException("Alignment must be a power of two.")
        }
        val entries = readEntries(input)
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        RandomAccessFile(output, "rw").use { out ->
            val locals = ArrayList<Long>(entries.size)
            for (entry in entries) {
                locals.add(out.filePointer)
                writeLocal(out, input, entry, pageSize, alignment)
            }
            val cdOffset = out.filePointer
            for ((index, entry) in entries.withIndex()) {
                writeCentral(out, entry, locals[index])
            }
            val cdSize = out.filePointer - cdOffset
            if (entries.size > 0xFFFF || cdOffset > 0xFFFFFFFFL || cdSize > 0xFFFFFFFFL) {
                throw ZipAlignException("The aligned package is larger than this aligner can write.")
            }
            writeU32(out, 0x06054b50)
            writeU16(out, 0)
            writeU16(out, 0)
            writeU16(out, entries.size)
            writeU16(out, entries.size)
            writeU32(out, cdSize)
            writeU32(out, cdOffset)
            val comment = entries.comment
            writeU16(out, comment.size)
            if (comment.isNotEmpty()) out.write(comment)
        }
    }

    private fun writeLocal(
        out: RandomAccessFile,
        input: File,
        entry: Entry,
        pageSize: Int,
        alignment: Int,
    ) {
        val extra = stripZip64(entry.localExtra)
        val alignTo = when {
            entry.method != 0 -> 1
            isNativeLibrary(entry.nameBytes) -> pageSize
            else -> alignment
        }
        val padded = pad(out.filePointer, entry.nameBytes.size, extra, alignTo)
        writeU32(out, 0x04034b50)
        writeU16(out, entry.versionNeeded)
        writeU16(out, entry.flag and 0x8.inv())
        writeU16(out, entry.method)
        writeU16(out, entry.time)
        writeU16(out, entry.date)
        writeU32(out, entry.crc)
        writeU32(out, entry.compressedSize)
        writeU32(out, entry.uncompressedSize)
        writeU16(out, entry.nameBytes.size)
        writeU16(out, padded.size)
        out.write(entry.nameBytes)
        if (padded.isNotEmpty()) out.write(padded)
        RandomAccessFile(input, "r").use { source ->
            source.seek(entry.dataOffset)
            copy(source, out, entry.compressedSize)
        }
    }

    private fun writeCentral(out: RandomAccessFile, entry: Entry, localOffset: Long) {
        val extra = stripZip64(entry.cdExtra)
        writeU32(out, 0x02014b50)
        writeU16(out, entry.versionMade)
        writeU16(out, entry.versionNeeded)
        writeU16(out, entry.flag and 0x8.inv())
        writeU16(out, entry.method)
        writeU16(out, entry.time)
        writeU16(out, entry.date)
        writeU32(out, entry.crc)
        writeU32(out, entry.compressedSize)
        writeU32(out, entry.uncompressedSize)
        writeU16(out, entry.nameBytes.size)
        writeU16(out, extra.size)
        writeU16(out, entry.comment.size)
        writeU16(out, 0)
        writeU16(out, entry.internalAttr)
        writeU32(out, entry.externalAttr)
        writeU32(out, localOffset)
        out.write(entry.nameBytes)
        if (extra.isNotEmpty()) out.write(extra)
        if (entry.comment.isNotEmpty()) out.write(entry.comment)
    }

    private fun pad(offset: Long, nameLength: Int, extra: ByteArray, alignTo: Int): ByteArray {
        if (alignTo <= 1) return extra
        val start = offset + 30 + nameLength + extra.size
        val remainder = (start % alignTo).toInt()
        if (remainder == 0) return extra
        val padding = alignTo - remainder
        if (extra.size + padding > 0xFFFF) {
            throw ZipAlignException("Alignment padding does not fit in the zip extra field.")
        }
        return extra + ByteArray(padding)
    }

    private fun isNativeLibrary(name: ByteArray): Boolean {
        if (name.size < 3 || name.last() == '/'.code.toByte()) return false
        val end = name.decodeToString().lowercase()
        return end.endsWith(".so")
    }

    private class Entry(
        val nameBytes: ByteArray,
        val method: Int,
        val flag: Int,
        val time: Int,
        val date: Int,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localOffset: Long,
        val dataOffset: Long,
        val localExtra: ByteArray,
        val cdExtra: ByteArray,
        val comment: ByteArray,
        val versionMade: Int,
        val versionNeeded: Int,
        val internalAttr: Int,
        val externalAttr: Long,
    )

    private class Listing(val entries: List<Entry>, val comment: ByteArray) : List<Entry> by entries

    private fun readEntries(input: File): Listing {
        RandomAccessFile(input, "r").use { raf ->
            val size = raf.length()
            if (size < 22) throw ZipAlignException("The package is not a valid zip.")
            val tailLen = minOf(size, 22L + 0xFFFF).toInt()
            val tail = ByteArray(tailLen)
            raf.seek(size - tailLen)
            raf.readFully(tail)
            var eocd = -1
            for (index in tail.size - 22 downTo 0) {
                if (readU32(tail, index) == 0x06054b50L) {
                    eocd = index
                    break
                }
            }
            if (eocd < 0) throw ZipAlignException("The package is not a valid zip.")
            val commentLen = readU16(tail, eocd + 20)
            val comment = if (eocd + 22 + commentLen <= tail.size) {
                tail.copyOfRange(eocd + 22, eocd + 22 + commentLen)
            } else {
                ByteArray(0)
            }
            var cdSize = readU32(tail, eocd + 12)
            var cdOffset = readU32(tail, eocd + 16)
            if (cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                val located = readZip64(raf, size - tailLen + eocd)
                cdSize = located.first
                cdOffset = located.second
            }
            val directory = ByteArray(cdSize.toInt())
            raf.seek(cdOffset)
            raf.readFully(directory)
            val entries = ArrayList<Entry>()
            var offset = 0
            while (offset + 46 <= directory.size) {
                if (readU32(directory, offset) != 0x02014b50L) break
                val versionMade = readU16(directory, offset + 4)
                val versionNeeded = readU16(directory, offset + 6)
                val flag = readU16(directory, offset + 8)
                val method = readU16(directory, offset + 10)
                val time = readU16(directory, offset + 12)
                val date = readU16(directory, offset + 14)
                var crc = readU32(directory, offset + 16)
                var compressed = readU32(directory, offset + 20)
                var uncompressed = readU32(directory, offset + 24)
                val nameLen = readU16(directory, offset + 28)
                val extraLen = readU16(directory, offset + 30)
                val entryCommentLen = readU16(directory, offset + 32)
                val internalAttr = readU16(directory, offset + 36)
                val externalAttr = readU32(directory, offset + 38)
                var localOffset = readU32(directory, offset + 42)
                val nameStart = offset + 46
                val extraStart = nameStart + nameLen
                val commentStart = extraStart + extraLen
                val next = commentStart + entryCommentLen
                if (next > directory.size) throw ZipAlignException("The package central directory is truncated.")
                val name = directory.copyOfRange(nameStart, extraStart)
                val cdExtra = directory.copyOfRange(extraStart, commentStart)
                val entryComment = directory.copyOfRange(commentStart, next)
                val wide = zip64(cdExtra, uncompressed, compressed, localOffset)
                uncompressed = wide[0]
                compressed = wide[1]
                localOffset = wide[2]
                if (flag and 0x1 != 0) throw ZipAlignException("Encrypted zip entries are not supported.")
                if (compressed > Int.MAX_VALUE.toLong() || uncompressed > 0xFFFFFFFFL) {
                    throw ZipAlignException("This package is larger than this aligner can rewrite.")
                }
                val local = ByteArray(30)
                raf.seek(localOffset)
                raf.readFully(local)
                if (readU32(local, 0) != 0x04034b50L) throw ZipAlignException("A zip entry header is missing.")
                val localNameLen = readU16(local, 26)
                val localExtraLen = readU16(local, 28)
                val localExtra = ByteArray(localExtraLen)
                raf.seek(localOffset + 30 + localNameLen)
                raf.readFully(localExtra)
                entries.add(
                    Entry(
                        nameBytes = name,
                        method = method,
                        flag = flag,
                        time = time,
                        date = date,
                        crc = crc,
                        compressedSize = compressed,
                        uncompressedSize = uncompressed,
                        localOffset = localOffset,
                        dataOffset = localOffset + 30 + localNameLen + localExtraLen,
                        localExtra = localExtra,
                        cdExtra = cdExtra,
                        comment = entryComment,
                        versionMade = versionMade,
                        versionNeeded = versionNeeded,
                        internalAttr = internalAttr,
                        externalAttr = externalAttr,
                    ),
                )
                offset = next
            }
            return Listing(entries, comment)
        }
    }

    private fun readZip64(raf: RandomAccessFile, eocdOffset: Long): Pair<Long, Long> {
        if (eocdOffset < 20) throw ZipAlignException("The Zip64 directory could not be found.")
        val locator = ByteArray(20)
        raf.seek(eocdOffset - 20)
        raf.readFully(locator)
        if (readU32(locator, 0) != 0x07064b50L) throw ZipAlignException("The Zip64 directory could not be found.")
        val eocd64 = readU64(locator, 8)
        val head = ByteArray(56)
        raf.seek(eocd64)
        raf.readFully(head)
        if (readU32(head, 0) != 0x06064b50L) throw ZipAlignException("The Zip64 directory could not be found.")
        return readU64(head, 40) to readU64(head, 48)
    }

    private fun zip64(extra: ByteArray, uncompressed: Long, compressed: Long, offset: Long): LongArray {
        var u = uncompressed
        var c = compressed
        var o = offset
        var index = 0
        while (index + 4 <= extra.size) {
            val id = readU16(extra, index)
            val size = readU16(extra, index + 2)
            val start = index + 4
            if (start + size > extra.size) break
            if (id == 0x0001) {
                var cursor = start
                if (u == 0xFFFFFFFFL && cursor + 8 <= start + size) {
                    u = readU64(extra, cursor)
                    cursor += 8
                }
                if (c == 0xFFFFFFFFL && cursor + 8 <= start + size) {
                    c = readU64(extra, cursor)
                    cursor += 8
                }
                if (o == 0xFFFFFFFFL && cursor + 8 <= start + size) {
                    o = readU64(extra, cursor)
                }
                break
            }
            index = start + size
        }
        return longArrayOf(u, c, o)
    }

    private fun stripZip64(extra: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(extra.size)
        var index = 0
        while (index + 4 <= extra.size) {
            val id = readU16(extra, index)
            val size = readU16(extra, index + 2)
            val next = index + 4 + size
            if (next > extra.size) {
                out.write(extra, index, extra.size - index)
                break
            }
            if (id != 0x0001) out.write(extra, index, 4 + size)
            index = next
        }
        if (index < extra.size && index + 4 > extra.size) out.write(extra, index, extra.size - index)
        return out.toByteArray()
    }

    private fun copy(input: RandomAccessFile, output: RandomAccessFile, size: Long) {
        val buffer = ByteArray(1024 * 1024)
        var left = size
        while (left > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (count < 0) throw ZipAlignException("A zip entry ended early.")
            output.write(buffer, 0, count)
            left -= count
        }
    }

    private fun writeU16(out: RandomAccessFile, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun writeU32(out: RandomAccessFile, value: Long) {
        val bits = value.toInt()
        out.write(bits and 0xFF)
        out.write((bits ushr 8) and 0xFF)
        out.write((bits ushr 16) and 0xFF)
        out.write((bits ushr 24) and 0xFF)
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long {
        return (readU16(bytes, offset).toLong() and 0xFFFF) or
            ((readU16(bytes, offset + 2).toLong() and 0xFFFF) shl 16)
    }

    private fun readU64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = value or ((bytes[offset + index].toLong() and 0xFF) shl (8 * index))
        }
        return value
    }
}
