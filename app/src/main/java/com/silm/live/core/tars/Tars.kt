package com.silm.live.core.tars

import java.io.ByteArrayOutputStream

/**
 * 最小 TARS(JCE) 编解码实现，移植自 tars_dart。
 * 仅覆盖虎牙 wup/danmaku 所需能力。
 */
object TarsType {
    const val BYTE = 0
    const val SHORT = 1
    const val INT = 2
    const val LONG = 3
    const val FLOAT = 4
    const val DOUBLE = 5
    const val STRING1 = 6
    const val STRING4 = 7
    const val MAP = 8
    const val LIST = 9
    const val STRUCT_BEGIN = 10
    const val STRUCT_END = 11
    const val ZERO_TAG = 12
    const val SIMPLE_LIST = 13
}

class TarsOutputStream {
    private val out = ByteArrayOutputStream()

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun writeHead(type: Int, tag: Int) {
        if (tag < 15) {
            out.write((tag shl 4) or type)
        } else {
            out.write((15 shl 4) or type)
            out.write(tag)
        }
    }

    fun writeByte(b: Int, tag: Int) {
        if (b == 0) {
            writeHead(TarsType.ZERO_TAG, tag)
        } else {
            writeHead(TarsType.BYTE, tag)
            out.write(b)
        }
    }

    fun writeInt(n: Long, tag: Int) {
        when {
            n in -128..127 -> writeByte(n.toInt(), tag)
            n in -32768..32767 -> {
                writeHead(TarsType.SHORT, tag)
                out.write((n shr 8).toInt())
                out.write((n and 0xFF).toInt())
            }
            n in Int.MIN_VALUE..Int.MAX_VALUE.toLong() -> {
                writeHead(TarsType.INT, tag)
                for (i in 3 downTo 0) out.write(((n shr (8 * i)) and 0xFF).toInt())
            }
            else -> {
                writeHead(TarsType.LONG, tag)
                for (i in 7 downTo 0) out.write(((n shr (8 * i)) and 0xFF).toInt())
            }
        }
    }

    fun writeBool(b: Boolean, tag: Int) = writeByte(if (b) 1 else 0, tag)

    fun writeString(s: String, tag: Int) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size > 255) {
            writeHead(TarsType.STRING4, tag)
            writeRawInt(bytes.size, 4)
        } else {
            writeHead(TarsType.STRING1, tag)
            if (bytes.isEmpty()) {
                out.write(0)
            } else {
                out.write(bytes.size)
                out.write(bytes)
            }
        }
    }

    private fun writeRawInt(v: Int, len: Int) {
        for (i in len - 1 downTo 0) out.write(((v shr (8 * i)) and 0xFF))
    }

    /** byte[] -> SimpleList */
    fun writeBytes(ls: ByteArray, tag: Int) {
        writeHead(TarsType.SIMPLE_LIST, tag)
        writeHead(TarsType.BYTE, 0)
        writeInt(ls.size.toLong(), 0)
        out.write(ls)
    }

    fun writeStruct(tag: Int, body: TarsOutputStream.() -> Unit) {
        writeHead(TarsType.STRUCT_BEGIN, tag)
        body()
        writeHead(TarsType.STRUCT_END, 0)
    }

    fun writeMapStringBytes(map: Map<String, ByteArray>, tag: Int) {
        writeHead(TarsType.MAP, tag)
        writeInt(map.size.toLong(), 0)
        for ((k, v) in map) {
            writeString(k, 0)
            writeBytes(v, 1)
        }
    }
}

class TarsInputStream(private val data: ByteArray, private var pos: Int = 0) {

    class HeadData(var type: Int = 0, var tag: Int = 0)

    private class TarsDecodeException(msg: String) : Exception(msg)

    private fun read(): Int {
        if (pos >= data.size) throw TarsDecodeException("read to end")
        return data[pos++].toInt() and 0xFF
    }

    fun readHead(hd: HeadData): Int {
        val b = read()
        hd.type = b and 0x0F
        hd.tag = (b and 0xF0) shr 4
        return if (hd.tag == 15) {
            hd.tag = read()
            2
        } else 1
    }

    private fun peakHead(hd: HeadData): Int {
        val cur = pos
        val len = readHead(hd)
        pos = cur
        return len
    }

    private fun skip(len: Int) {
        pos += len
    }

    private fun skipToTag(tag: Int): Boolean {
        return try {
            val hd = HeadData()
            var found = false
            while (true) {
                val len = peakHead(hd)
                if (tag <= hd.tag || hd.type == TarsType.STRUCT_END) {
                    found = tag == hd.tag
                    break
                }
                skip(len)
                skipFieldWithType(hd.type)
            }
            found
        } catch (e: Exception) {
            false
        }
    }

    private fun skipToStructEnd() {
        val hd = HeadData()
        do {
            readHead(hd)
            skipFieldWithType(hd.type)
        } while (hd.type != TarsType.STRUCT_END)
    }

    private fun skipField() {
        val hd = HeadData()
        readHead(hd)
        skipFieldWithType(hd.type)
    }

    private fun skipFieldWithType(type: Int) {
        when (type) {
            TarsType.BYTE -> skip(1)
            TarsType.SHORT -> skip(2)
            TarsType.INT -> skip(4)
            TarsType.LONG -> skip(8)
            TarsType.FLOAT -> skip(4)
            TarsType.DOUBLE -> skip(8)
            TarsType.STRING1 -> {
                val len = read()
                skip(len)
            }
            TarsType.STRING4 -> skip(readRawInt(4).toInt())
            TarsType.MAP -> {
                val size = readInt(0, true).toInt()
                repeat(size * 2) { skipField() }
            }
            TarsType.LIST -> {
                val size = readInt(0, true).toInt()
                repeat(size) { skipField() }
            }
            TarsType.SIMPLE_LIST -> {
                val hh = HeadData()
                readHead(hh)
                val size = readInt(0, true).toInt()
                skip(size)
            }
            TarsType.STRUCT_BEGIN -> skipToStructEnd()
            // STRUCT_END / ZERO_TAG: nothing
        }
    }

    private fun readRawInt(len: Int): Long {
        var result = 0L
        var signed = false
        if (len == 1) {
            result = (data[pos].toInt() and 0xFF).toLong()
            if (result >= 0x80) { result -= 0x100; signed = true }
        } else {
            for (i in 0 until len) {
                result = (result shl 8) or (data[pos + i].toInt() and 0xFF).toLong()
            }
            if (data[pos].toInt() and 0x80 != 0) {
                result -= (1L shl (8 * len))
                signed = true
            }
        }
        pos += len
        return result
    }

    fun readInt(tag: Int, required: Boolean = false): Long {
        var n = 0L
        if (skipToTag(tag)) {
            val hd = HeadData()
            readHead(hd)
            when (hd.type) {
                TarsType.ZERO_TAG -> n = 0
                TarsType.BYTE -> n = readRawInt(1)
                TarsType.SHORT -> n = readRawInt(2)
                TarsType.INT -> n = readRawInt(4)
                TarsType.LONG -> n = readRawInt(8)
                else -> throw TarsDecodeException("type mismatch int")
            }
        } else if (required) {
            throw TarsDecodeException("require field not exist int")
        }
        return n
    }

    fun readBool(tag: Int, required: Boolean = false): Boolean = readInt(tag, required) != 0L

    fun readString(tag: Int, required: Boolean = false): String {
        var n = ""
        if (skipToTag(tag)) {
            val hd = HeadData()
            readHead(hd)
            when (hd.type) {
                TarsType.STRING1 -> {
                    val len = read()
                    n = String(data, pos, len, Charsets.UTF_8)
                    pos += len
                }
                TarsType.STRING4 -> {
                    val len = readRawInt(4).toInt()
                    n = String(data, pos, len, Charsets.UTF_8)
                    pos += len
                }
                else -> throw TarsDecodeException("type mismatch string")
            }
        } else if (required) {
            throw TarsDecodeException("require field not exist string")
        }
        return n
    }

    fun readBytes(tag: Int, required: Boolean = false): ByteArray {
        var lr = ByteArray(0)
        if (skipToTag(tag)) {
            val hd = HeadData()
            readHead(hd)
            when (hd.type) {
                TarsType.SIMPLE_LIST -> {
                    val hh = HeadData()
                    readHead(hh)
                    if (hh.type != TarsType.BYTE) throw TarsDecodeException("type mismatch bytes")
                    val size = readInt(0, true).toInt()
                    lr = data.copyOfRange(pos, pos + size)
                    pos += size
                }
                TarsType.LIST -> {
                    val size = readInt(0, true).toInt()
                    lr = ByteArray(size)
                    for (i in 0 until size) lr[i] = readInt(0, true).toByte()
                }
                else -> throw TarsDecodeException("type mismatch bytes")
            }
        } else if (required) {
            throw TarsDecodeException("require field not exist bytes")
        }
        return lr
    }

    /** 读取嵌套结构：进入 STRUCT 后回调消费字段 */
    fun readStruct(tag: Int, required: Boolean = false, body: (TarsInputStream.() -> Unit)): Boolean {
        if (skipToTag(tag)) {
            val hd = HeadData()
            readHead(hd)
            if (hd.type != TarsType.STRUCT_BEGIN) throw TarsDecodeException("type mismatch struct")
            body()
            skipToStructEnd()
            return true
        } else if (required) {
            throw TarsDecodeException("require field not exist struct")
        }
        return false
    }

    fun readMapStringBytes(tag: Int, required: Boolean = false): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        if (skipToTag(tag)) {
            val hd = HeadData()
            readHead(hd)
            if (hd.type != TarsType.MAP) throw TarsDecodeException("type mismatch map")
            val size = readInt(0, true).toInt()
            repeat(size) {
                val k = readString(0, true)
                val v = readBytes(1, true)
                map[k] = v
            }
        } else if (required) {
            throw TarsDecodeException("require field not exist map")
        }
        return map
    }
}
