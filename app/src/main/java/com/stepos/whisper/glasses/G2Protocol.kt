package com.stepos.whisper.glasses

import java.util.UUID

/**
 * Even Realities G2 的 BLE 传输层：帧格式、鉴权包、心跳。全部小端。
 *
 * 字节序列照社区逆向整理的协议（OpenEvenSdk PROTOCOL.md / i-soxi/even-g2-protocol）逐字节搬运：
 *
 * 单包：`AA 21 seq len 01 01 svcHi svcLo payload crcLo crcHi`，len = payload + 2，CRC 只算 payload。
 * 多片（EvenHub 用）：`AA 21 seq len total idx sid flag data…`，整个 payload 一个 CRC 追加在末片，
 * 所有分片共用同一个 seq（固件按 seq 重组）。
 */
object G2Protocol {
    /** 扫描 / 过滤用的服务 UUID（Nordic UART 风格）。 */
    val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    /** 控制 / 文本写特征（writeWithoutResponse），所有真实流量都走这条。 */
    const val WRITE_UUID_SUFFIX = "2e5401"
    /** ack 与事件通知特征。 */
    const val NOTIFY_UUID_SUFFIX = "2e5402"
    /** 麦克风 LC3 通知特征（本应用不用，只是识别后忽略）。 */
    const val MIC_UUID_SUFFIX = "2e6402"

    const val FRAME_HEAD = 0xAA
    const val FRAME_TYPE = 0x21

    // ---------------------------------------------------------------- varint

    /** 标准 protobuf base-128 varint。 */
    fun varint(value: Int): ByteArray {
        var v = value.toLong() and 0xFFFFFFFFL
        val out = ArrayList<Byte>(5)
        do {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.add(b.toByte())
        } while (v != 0L)
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- 帧

    /** 单包控制帧。 */
    fun buildPacket(seq: Int, svcHi: Int, svcLo: Int, payload: ByteArray): ByteArray {
        val header = byteArrayOf(
            FRAME_HEAD.toByte(), FRAME_TYPE.toByte(), seq.toByte(), ((payload.size + 2) and 0xFF).toByte(),
            0x01, 0x01, (svcHi and 0xFF).toByte(), (svcLo and 0xFF).toByte()
        )
        return header + payload + Crc16.ccittLe(payload)
    }

    /**
     * 多片帧。payload 超过一个 BLE 块时切成若干片，一个 CRC 追加到末片；分片大小应为 min(232, MTU-3-8)，
     * 比 MTU 大的帧会被固件静默丢弃导致重组失败。
     */
    fun framePb(seq: Int, sid: Int, flag: Int, pb: ByteArray, chunk: Int = 232): List<ByteArray> {
        require(chunk > 0)
        // 固件按 seq 把各片的 data 原样拼起来再校验，所以把 payload+CRC 当成一个整体等分即可
        // （CRC 可能落在最后一片的开头甚至单独成片，这是允许的）。
        val full = pb + Crc16.ccittLe(pb)
        val totalFrags = (full.size + chunk - 1) / chunk
        val frames = ArrayList<ByteArray>(totalFrags)
        for (i in 0 until totalFrags) {
            val off = i * chunk
            val chunkBytes = full.copyOfRange(off, minOf(off + chunk, full.size))
            val header = byteArrayOf(
                FRAME_HEAD.toByte(), FRAME_TYPE.toByte(), seq.toByte(), (chunkBytes.size and 0xFF).toByte(),
                (totalFrags and 0xFF).toByte(), ((i + 1) and 0xFF).toByte(),
                (sid and 0xFF).toByte(), (flag and 0xFF).toByte()
            )
            frames.add(header + chunkBytes)
        }
        return frames
    }

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    // ---------------------------------------------------------------- 鉴权

    /**
     * 连接后向两条镜腿各发这 7 个包（间隔约 100 ms，发完再等约 500 ms）。
     * 第 3、7 包内嵌 varint 的 Unix 秒时间戳；txid 固定。
     */
    fun authPackets(unixSeconds: Int): List<ByteArray> {
        val ts = varint(unixSeconds)
        val txid = bytes(0xE8, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01)
        return listOf(
            buildPacket(0x01, 0x80, 0x00, bytes(0x08, 0x04, 0x10, 0x0C, 0x1A, 0x04, 0x08, 0x01, 0x10, 0x04)),
            buildPacket(0x02, 0x80, 0x20, bytes(0x08, 0x05, 0x10, 0x0E, 0x22, 0x02, 0x08, 0x02)),
            buildPacket(0x03, 0x80, 0x20, bytes(0x08, 0x80, 0x01, 0x10, 0x0F, 0x82, 0x08, 0x11, 0x08) + ts + bytes(0x10) + txid),
            buildPacket(0x04, 0x80, 0x00, bytes(0x08, 0x04, 0x10, 0x10, 0x1A, 0x04, 0x08, 0x01, 0x10, 0x04)),
            buildPacket(0x05, 0x80, 0x00, bytes(0x08, 0x04, 0x10, 0x11, 0x1A, 0x04, 0x08, 0x01, 0x10, 0x04)),
            buildPacket(0x06, 0x80, 0x20, bytes(0x08, 0x05, 0x10, 0x12, 0x22, 0x02, 0x08, 0x01)),
            buildPacket(0x07, 0x80, 0x20, bytes(0x08, 0x80, 0x01, 0x10, 0x13, 0x82, 0x08, 0x11, 0x08) + ts + bytes(0x10) + txid),
        )
    }

    // ---------------------------------------------------------------- 心跳

    /** 没有 EvenHub 页面时的保活心跳，发到两条镜腿，约 1.5 s 一次。 */
    fun heartbeat(seq: Int): ByteArray = buildPacket(seq, 0x80, 0x00, bytes(0x08, 0x25))

    /**
     * 判断一条通知是不是 EvenHub（sid 0xE0）帧：byte[0]=0xAA 且 byte[6]=0xE0；payload = bytes[8..]，
     * byte[7] 是 flag：0x20 为请求 ack，0x01 / 0x06 为异步事件。
     */
    fun isEvenHubFrame(bytes: ByteArray): Boolean =
        bytes.size >= 9 && (bytes[0].toInt() and 0xFF) == FRAME_HEAD && (bytes[6].toInt() and 0xFF) == EvenHub.SID

    fun hex(bytes: ByteArray, max: Int = 32): String {
        val sb = StringBuilder()
        val n = minOf(bytes.size, max)
        for (i in 0 until n) {
            if (i > 0) sb.append(' ')
            sb.append(String.format("%02X", bytes[i].toInt() and 0xFF))
        }
        if (bytes.size > max) sb.append(" …(").append(bytes.size).append(")")
        return sb.toString()
    }
}
