package com.stepos.whisper.glasses

/** 左右镜腿。 */
enum class Side { LEFT, RIGHT }

/** 整体连接状态。 */
enum class G2Status {
    /** 开关关闭，不连接。 */
    OFF,
    /** 没有连接，也没有在尝试（蓝牙关闭、缺权限、从未配对、用户手动断开）。 */
    DISCONNECTED,
    /** 正在扫描镜腿。 */
    SCANNING,
    /** 至少一条镜腿正在连接 / 等另一条。 */
    CONNECTING,
    /** 两条镜腿都已连上，正在发握手包。 */
    AUTHENTICATING,
    /** 握手完成，可以推送。 */
    READY,
}

/** 单条镜腿的状态。 */
data class ArmState(
    val side: Side,
    val name: String? = null,
    val address: String? = null,
    /** 已经扫描到或已记住，并持有设备对象。 */
    val found: Boolean = false,
    /** GATT 已连接。 */
    val connected: Boolean = false,
    /** 服务已发现、通知已订阅，可以收发。 */
    val ready: Boolean = false,
)

/** 推给界面的快照。 */
data class G2State(
    val status: G2Status,
    val left: ArmState = ArmState(Side.LEFT),
    val right: ArmState = ArmState(Side.RIGHT),
    /** 最近一次错误的说明文字；没有为 null。 */
    val error: String? = null,
    /** 眼镜上正在显示本应用推送的页面。 */
    val pageShown: Boolean = false,
    /** 已记住过一副眼镜（可自动重连）。 */
    val remembered: Boolean = false,
) {
    val isReady: Boolean get() = status == G2Status.READY
}

/** 扫描到的一条镜腿，给配对界面的列表用。 */
data class ScanArm(
    val name: String,
    val address: String,
    val rssi: Int,
    /** 从广播名里识别出的左右；识别不出为 null。 */
    val side: Side?,
    /** 当前被指定为哪一侧；未指定为 null。 */
    val assigned: Side?,
)
