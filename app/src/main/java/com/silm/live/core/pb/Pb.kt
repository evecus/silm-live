package com.silm.live.core.pb

/**
 * 极简 protobuf 解码器（仅需支持抖音弹幕消息读取）。
 * 只处理 varint 与 length-delimited 两类 wire type。
 */
class PbField(val field: Int, val wire: Int, val value: Long, val data: ByteArray?)

class Pb private constructor(private val fields: List<PbField>) {

    companion object {
        fun parse(data: ByteArray): Pb {
            val list = mutableListOf<PbField>()
            var pos = 0
            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (shift < 64) {
                    val b = data[pos].toInt() and 0xFF
                    pos++
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                return result
            }
            while (pos < data.size) {
                val tag = readVarint()
                val field = (tag ushr 3).toInt()
                val wire = (tag and 0x7).toInt()
                when (wire) {
                    0 -> list.add(PbField(field, wire, readVarint(), null))
                    1 -> { val b = data.copyOfRange(pos, pos + 8); pos += 8; list.add(PbField(field, wire, 0, b)) }
                    2 -> {
                        val len = readVarint().toInt()
                        val b = data.copyOfRange(pos, pos + len)
                        pos += len
                        list.add(PbField(field, wire, 0, b))
                    }
                    5 -> { val b = data.copyOfRange(pos, pos + 4); pos += 4; list.add(PbField(field, wire, 0, b)) }
                    else -> break // 未知 wire type，停止解析
                }
            }
            return Pb(list)
        }
    }

    private fun byField(f: Int) = fields.filter { it.field == f }

    fun int(f: Int): Long = byField(f).firstOrNull { it.wire == 0 }?.value ?: 0L

    fun str(f: Int): String =
        byField(f).firstOrNull { it.wire == 2 }?.data?.let { String(it, Charsets.UTF_8) } ?: ""

    fun bytes(f: Int): ByteArray = byField(f).firstOrNull { it.wire == 2 }?.data ?: ByteArray(0)

    fun has(f: Int): Boolean = byField(f).isNotEmpty()

    fun msgs(f: Int): List<Pb> = byField(f).filter { it.wire == 2 }.map { parse(it.data!!) }
}
