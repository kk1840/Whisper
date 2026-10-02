package com.stepos.whisper.glasses

/**
 * EvenHub：G2 上“原生容器 / 图片”这一层的协议（sid 0xE0，flag 0x20），只能发给右镜腿。
 *
 * 消息是一个手写的 proto3 包裹：field 1 = Cmd（varint），field 2 = magic（关联 id，1..120 滚动，
 * 固件只比较低字节），再加一个按 Cmd 决定字段号的嵌套消息。proto3 规则：零值标量不发，嵌套消息始终发。
 * 容器名不能超过 14 字节，否则固件静默拒绝。
 *
 * 字段号来自对官方 App 的反编译与抓包（g2-kit-unofficial / G2CC），这里逐条照搬。
 */
object EvenHub {
    const val SID = 0xE0
    const val FLAG_REQUEST = 0x20
    const val FLAG_EVENT = 0x01
    const val FLAG_EVENT_ALT = 0x06

    object Cmd {
        const val CREATE = 0
        const val EVENT = 2          // RX
        const val UPDATE_IMAGE = 3
        const val UPDATE_TEXT = 5
        const val REBUILD = 7
        const val SHUTDOWN = 9
        const val PRIVATE_EVENT = 11 // RX
        const val HEARTBEAT = 12
    }

    /** 结果码：偶数成功、奇数失败。createOK=0 在线上会被省略（proto3 零值），缺 result 的 create ack 视为成功。 */
    object Result {
        const val CREATE_OK = 0
        const val IMAGE_OK = 4
        const val REBUILD_OK = 6
        const val TEXT_OK = 8
        const val HEARTBEAT_OK = 12

        fun isOk(r: Int?): Boolean = (r ?: CREATE_OK) % 2 == 0
    }

    /** 系统事件类型（Sys_ItemEvent.EventType）。5/6/7 表示页面已经没了，下次要重新 CREATE。 */
    object SysEvent {
        const val CLICK = 0
        const val DOUBLE_CLICK = 3
        const val FOREGROUND_ENTER = 4
        const val FOREGROUND_EXIT = 5
        const val ABNORMAL_EXIT = 6
        const val SYSTEM_EXIT = 7

        fun isPageGone(type: Int): Boolean = type == FOREGROUND_EXIT || type == ABNORMAL_EXIT || type == SYSTEM_EXIT
    }

    /** 镜片分辨率。 */
    const val LENS_W = 576
    const val LENS_H = 288

    /** 容器名上限（字节）。 */
    const val MAX_NAME_BYTES = 14

    /** 每个 Cmd=3 分片的数据上限：固件对 >4 KB 的分片静默丢弃，留点余量。 */
    const val IMAGE_FRAGMENT_BYTES = 3800

    /** 首个 CREATE 之前要原样发给右镜腿的“应用启动”前奏（sid 0x01），每次 BLE 会话一次。 */
    val PRELUDE: ByteArray = intArrayOf(
        0xAA, 0x21, 0x92, 0x13, 0x01, 0x01, 0x01, 0x20, 0x08, 0x02, 0x10, 0x9C,
        0x01, 0x22, 0x0A, 0x1A, 0x08, 0x12, 0x06, 0x12, 0x04, 0x08, 0x00, 0x10,
        0x00, 0xA1, 0x42
    ).let { a -> ByteArray(a.size) { a[it].toByte() } }

    fun validName(name: String): String {
        require(name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) {
            "EvenHub container name '$name' exceeds $MAX_NAME_BYTES bytes"
        }
        return name
    }

    // ---------------------------------------------------------------- proto3 编码

    private fun tag(field: Int, wire: Int): ByteArray = G2Protocol.varint((field shl 3) or wire)
    private fun uintF(field: Int, v: Int): ByteArray = if (v == 0) ByteArray(0) else tag(field, 0) + G2Protocol.varint(v)
    private fun strF(field: Int, s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return if (b.isEmpty()) ByteArray(0) else tag(field, 2) + G2Protocol.varint(b.size) + b
    }
    private fun bytesF(field: Int, b: ByteArray): ByteArray =
        if (b.isEmpty()) ByteArray(0) else tag(field, 2) + G2Protocol.varint(b.size) + b
    /** 嵌套消息始终发（即使内容为空）。 */
    private fun msgF(field: Int, content: ByteArray): ByteArray =
        tag(field, 2) + G2Protocol.varint(content.size) + content

    private fun wrap(cmd: Int, magic: Int, field: Int, body: ByteArray): ByteArray =
        uintF(1, cmd) + uintF(2, magic) + msgF(field, body)

    // ---------------------------------------------------------------- 容器定义

    /** ImageObject：X=1 Y=2 W=3 H=4 ContainerID=5 ContainerName=6。 */
    data class ImageContainer(val id: Int, val name: String, val x: Int, val y: Int, val w: Int, val h: Int) {
        init { validName(name) }

        fun encode(): ByteArray =
            uintF(1, x) + uintF(2, y) + uintF(3, w) + uintF(4, h) + uintF(5, id) + strF(6, name)
    }

    /**
     * TextContainerProperty：X=1 Y=2 W=3 H=4 borderW=5 borderColor=6 borderRadius=7 padding=8
     * ContainerID=9 ContainerName=10 IsEventCapture=11 Content=12。内容不能为空（proto3 会丢掉），用 "·" 代替。
     */
    data class TextContainer(
        val id: Int, val name: String, val x: Int, val y: Int, val w: Int, val h: Int,
        val content: String, val captureEvents: Boolean = false
    ) {
        init { validName(name) }

        fun encode(): ByteArray =
            uintF(1, x) + uintF(2, y) + uintF(3, w) + uintF(4, h) +
                uintF(9, id) + strF(10, name) + uintF(11, if (captureEvents) 1 else 0) +
                strF(12, content.ifEmpty { "·" })
    }

    // ---------------------------------------------------------------- 消息构造

    /** Cmd=12 心跳，保住 EvenHub 插件任务（约 10 s 没流量就会被回收）。HeartBeatPacket{Cnt:0} 为空消息。 */
    fun heartbeat(magic: Int): ByteArray = wrap(Cmd.HEARTBEAT, magic, 14, ByteArray(0))

    /**
     * Cmd=0 创建启动页。CreateStartUpPageContainer：ContainerTotalNum=1 ListObject=2 TextObject=3 ImageObject=4 widgetId=5。
     * 页面里至少要有一个文本容器，纯图片页会 ack 但不绘制。
     */
    fun createStartupPage(
        texts: List<TextContainer>, images: List<ImageContainer>, widgetId: Int = 10000, magic: Int = 201
    ): ByteArray {
        var create = uintF(1, texts.size + images.size)
        for (t in texts) create += msgF(3, t.encode())
        for (c in images) create += msgF(4, c.encode())
        create += uintF(5, widgetId)
        return wrap(Cmd.CREATE, magic, 3, create)
    }

    /**
     * Cmd=3 一个图片数据分片。ImageRawData：ContainerID=1 Name=2 SessionId=3 TotalSize=4
     * (CompressMode=5 省略) FragmentIndex=6 DataLen=7 Data=8。
     */
    fun imageRawData(
        containerId: Int, name: String, sessionId: Int, totalSize: Int,
        fragmentIndex: Int, data: ByteArray, magic: Int
    ): ByteArray {
        val img = uintF(1, containerId) + strF(2, name) + uintF(3, sessionId) + uintF(4, totalSize) +
            uintF(6, fragmentIndex) + uintF(7, data.size) + bytesF(8, data)
        return wrap(Cmd.UPDATE_IMAGE, magic, 5, img)
    }

    /** Cmd=5 原地更新文本容器内容。TextContainerUpgrade：ContainerID=1 ContainerName=2 ContentOffset=3 ContentLength=4 Content=5。 */
    fun textUpgrade(containerId: Int, name: String, content: String, magic: Int): ByteArray {
        val bytes = content.ifEmpty { "·" }.toByteArray(Charsets.UTF_8)
        val up = uintF(1, containerId) + strF(2, validName(name)) + uintF(3, 0) + uintF(4, bytes.size) + bytesF(5, bytes)
        return wrap(Cmd.UPDATE_TEXT, magic, 9, up)
    }

    /** Cmd=9 关闭当前前台页面（释放容器，眼镜回到自己的仪表盘）。ShutDownCmd{exitMode=1}。 */
    fun shutDown(magic: Int, exitMode: Int = 0): ByteArray = wrap(Cmd.SHUTDOWN, magic, 11, uintF(1, exitMode))

    // ---------------------------------------------------------------- 响应解析

    private fun dVarint(b: ByteArray, off: Int): Pair<Int, Int> {
        var v = 0
        var s = 0
        var i = off
        while (i < b.size) {
            val c = b[i].toInt() and 0xFF
            i++
            v = v or ((c and 0x7F) shl s)
            if ((c and 0x80) == 0) break
            s += 7
        }
        return Pair(v, i)
    }

    data class Response(val cmd: Int, val magic: Int, val result: Int?)

    /** 解析 ack：包裹里 field 1 = cmd（请求 cmd + 1），field 2 = magic，嵌套消息的 field 1 或 8 是结果码。 */
    fun parseResponse(pb: ByteArray): Response {
        var cmd = -1
        var magic = -1
        var result: Int? = null
        var i = 0
        while (i < pb.size) {
            val key = pb[i].toInt() and 0xFF
            i++
            val field = key shr 3
            val wire = key and 7
            if (wire == 0) {
                val (v, n) = dVarint(pb, i); i = n
                if (field == 1) cmd = v else if (field == 2) magic = v
            } else if (wire == 2) {
                val (len, n) = dVarint(pb, i); i = n
                val end = minOf(i + len, pb.size)
                val sub = pb.copyOfRange(i, end); i = end
                var j = 0
                while (j < sub.size) {
                    val k = sub[j].toInt() and 0xFF; j++
                    val f = k shr 3; val w = k and 7
                    if (w == 0) {
                        val (vv, nn) = dVarint(sub, j); j = nn
                        if (f == 1 || f == 8) result = vv
                    } else if (w == 2) {
                        val (l, nn) = dVarint(sub, j); j = nn + l
                    } else break
                }
            } else break
        }
        return Response(cmd, magic, result)
    }

    fun describeResponse(r: Response): String {
        val cmdNames = mapOf(
            1 to "createResp", 2 to "event", 4 to "imageResp", 6 to "textResp",
            8 to "rebuildResp", 10 to "shutdownResp", 13 to "heartbeatResp"
        )
        val errNames = mapOf(
            0 to "createOK", 1 to "createInvalid", 2 to "createOversize", 3 to "createOOM",
            4 to "imageOK", 5 to "imageFAILED", 6 to "rebuildOK", 7 to "rebuildFAILED",
            8 to "textOK", 9 to "textFailed", 12 to "heartbeatOK"
        )
        val cn = cmdNames[r.cmd] ?: "cmd${r.cmd}"
        val rn = r.result?.let { "result=${errNames[it] ?: "err$it"}" } ?: "result=(none)"
        return "$cn magic=${r.magic} $rn"
    }

    // ---------------------------------------------------------------- 事件解析

    sealed class InEvent {
        data class ListClick(val name: String, val index: Int, val itemName: String, val type: Int) : InEvent()
        data class TextClick(val name: String, val type: Int) : InEvent()
        data class Sys(val type: Int, val exitReason: Int) : InEvent()
        data class Private(val name: String, val eventId: Int, val eventData: Int) : InEvent()
        object Other : InEvent()
    }

    private fun readStr(b: ByteArray, off: Int, len: Int): String =
        String(b.copyOfRange(off, minOf(off + len, b.size)), Charsets.UTF_8)

    private fun parseListEvent(b: ByteArray): InEvent {
        var name = ""; var item = ""; var index = 0; var type = 0
        var i = 0
        while (i < b.size) {
            val key = b[i].toInt() and 0xFF; i++
            val f = key shr 3; val w = key and 7
            if (w == 0) {
                val (v, n) = dVarint(b, i); i = n
                if (f == 4) index = v else if (f == 5) type = v
            } else if (w == 2) {
                val (l, n) = dVarint(b, i); i = n
                if (f == 2) name = readStr(b, i, l) else if (f == 3) item = readStr(b, i, l)
                i += l
            } else break
        }
        return InEvent.ListClick(name, index, item, type)
    }

    private fun parseTextEvent(b: ByteArray): InEvent {
        var name = ""; var type = 0
        var i = 0
        while (i < b.size) {
            val key = b[i].toInt() and 0xFF; i++
            val f = key shr 3; val w = key and 7
            if (w == 0) { val (v, n) = dVarint(b, i); i = n; if (f == 3) type = v }
            else if (w == 2) { val (l, n) = dVarint(b, i); i = n; if (f == 2) name = readStr(b, i, l); i += l }
            else break
        }
        return InEvent.TextClick(name, type)
    }

    private fun parseSysEvent(b: ByteArray): InEvent {
        var type = 0; var exitReason = 0
        var i = 0
        while (i < b.size) {
            val key = b[i].toInt() and 0xFF; i++
            val f = key shr 3; val w = key and 7
            if (w == 0) {
                val (v, n) = dVarint(b, i); i = n
                if (f == 1) type = v else if (f == 4) exitReason = v
            } else if (w == 2) { val (l, n) = dVarint(b, i); i = n + l }
            else break
        }
        return InEvent.Sys(type, exitReason)
    }

    /** SendDeviceEvent：field 1 = List_ItemEvent，2 = Text_ItemEvent，3 = Sys_ItemEvent。 */
    private fun parseDevEvent(b: ByteArray): InEvent {
        var i = 0
        while (i < b.size) {
            val key = b[i].toInt() and 0xFF; i++
            val f = key shr 3; val w = key and 7
            if (w == 2) {
                val (l, n) = dVarint(b, i); i = n
                val sub = b.copyOfRange(i, minOf(i + l, b.size)); i += l
                when (f) {
                    1 -> return parseListEvent(sub)
                    2 -> return parseTextEvent(sub)
                    3 -> return parseSysEvent(sub)
                }
            } else if (w == 0) { val (_, n) = dVarint(b, i); i = n }
            else break
        }
        return InEvent.Other
    }

    private fun parsePrivate(b: ByteArray): InEvent {
        var name = ""; var eventId = 0; var eventData = 0
        var i = 0
        while (i < b.size) {
            val key = b[i].toInt() and 0xFF; i++
            val f = key shr 3; val w = key and 7
            if (w == 0) {
                val (v, n) = dVarint(b, i); i = n
                if (f == 3) eventId = v else if (f == 4) eventData = v
            } else if (w == 2) { val (l, n) = dVarint(b, i); i = n; if (f == 2) name = readStr(b, i, l); i += l }
            else break
        }
        return InEvent.Private(name, eventId, eventData)
    }

    private fun parseEventInner(pb: ByteArray): InEvent? {
        var cmd = -1
        var devSub: ByteArray? = null
        var privSub: ByteArray? = null
        var i = 0
        while (i < pb.size) {
            val key = pb[i].toInt() and 0xFF; i++
            val f = key shr 3; val w = key and 7
            if (w == 0) { val (v, n) = dVarint(pb, i); i = n; if (f == 1) cmd = v }
            else if (w == 2) {
                val (l, n) = dVarint(pb, i); i = n
                val sub = pb.copyOfRange(i, minOf(i + l, pb.size)); i += l
                if (f == 13) devSub = sub else if (f == 16) privSub = sub
            } else break
        }
        if (cmd == Cmd.EVENT && devSub != null) return parseDevEvent(devSub)
        if (cmd == Cmd.PRIVATE_EVENT && privSub != null) return parsePrivate(privSub)
        return null
    }

    /** 解析异步事件；通知末尾可能带 CRC，解析失败时去掉最后两个字节再试一次。 */
    fun parseEvent(pb: ByteArray): InEvent? {
        parseEventInner(pb)?.let { return it }
        if (pb.size > 2) return parseEventInner(pb.copyOfRange(0, pb.size - 2))
        return null
    }
}
