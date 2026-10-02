package com.stepos.whisper.glasses

/**
 * CRC-16/CCITT-FALSE：多项式 0x1021，初值 0xFFFF，不反射、无最终异或。
 * G2 固件对帧内容校验用的就是这一种，其他常见 CRC-16 变体会被静默丢弃。
 * 标准校验向量："123456789" -> 0x29B1。
 */
object Crc16 {
    private val TABLE = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var crc = i shl 8
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
            }
            t[i] = crc and 0xFFFF
        }
    }

    fun ccitt(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            val b = data[i].toInt() and 0xFF
            crc = ((crc shl 8) xor TABLE[((crc shr 8) xor b) and 0xFF]) and 0xFFFF
        }
        return crc
    }

    /** 小端追加的两个字节。 */
    fun ccittLe(data: ByteArray): ByteArray {
        val c = ccitt(data)
        return byteArrayOf((c and 0xFF).toByte(), ((c shr 8) and 0xFF).toByte())
    }
}
