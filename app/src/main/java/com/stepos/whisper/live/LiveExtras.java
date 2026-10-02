package com.stepos.whisper.live;

/**
 * SystemUI 实况通知（灵动岛）的 extras 键和取值，与 LiveNotifiSample 的 LiveNotificationConstant 一致。
 * 只保留本应用用到的部分。
 */
final class LiveExtras {
    private LiveExtras() {
    }

    /** 生命周期：0 创建、1 更新、2 结束。demo 统一传 1。 */
    static final String OPERATION = "notification.live.operation";
    static final int OPERATION_UPDATE = 1;

    /** 场景，决定排序优先级，比如 SOUND_RECORDING、SCHEDULE、AI。 */
    static final String EVENT = "notification.live.event";
    static final String TYPE = "notification.live.type";
    static final int TYPE_CARD = 6;
    static final int TYPE_SOUND_RECORD = 7;
    static final int TYPE_DIALOG_MEDIUM_BUTTON = 10;

    /** 是否悬浮弹出，默认 true。 */
    static final String PINNED = "notification.live.pinned";
    static final String TITLE_OVERLAY = "notification.live.titleOverlay";
    static final String CONTENT_OVERLAY = "notification.live.contentOverlay";
    static final String ICON_OVERLAY = "notification.live.iconOverlay";
    static final String BG_COLOR_OVERLAY = "notification.live.bgColorOverlay";
    static final String TASK_STATE_DESC = "notification.live.task.state.desc";
    static final String TASK_STATE_DESC_COLOR = "notification.live.task.state.desc.color";
    static final String RIGHT_TIPS = "notification.live.top.right.tips";
    /** 中卡单按钮的按钮颜色：0 深色、1 白色。 */
    static final String BUTTON_COLOR = "notification.live.permission.button.color";

    static final String PROGRESS_HIDE = "notification.live.feature.progressHide";
    /** 录音模板左下角的文字（不显示计时器时占用计时器的位置）。 */
    static final String BOTTOM_TEXT = "notification.live.bottom.text";
    /** 录音模板扩展区的按钮图标，顺序与 Notification.Action 一致。 */
    static final String EXTEND_IMAGE = "notification.live.feature.extendImage";

    static final String CAPSULE = "notification.live.capsule";
    /** 1 显示、-1 结束。 */
    static final String CAPSULE_STATUS = "notification.live.capsuleStatus";
    /** 1 图标 + 文本、2 计时器、3 进度。 */
    static final String CAPSULE_TYPE = "notification.live.capsuleType";
    static final int CAPSULE_TYPE_TEXT = 1;
    static final String CAPSULE_ICON = "notification.live.capsuleIcon";
    static final String CAPSULE_BG_COLOR = "notification.live.capsuleBgColor";
    static final String CAPSULE_TITLE = "notification.live.capsuleTitle";
    static final String CAPSULE_CONTENT = "notification.live.capsuleContent";
    static final String CAPSULE_CONTENT_COLOR = "notification.live.capsuleContentColor";
    /** 更新胶囊时提醒用户（胶囊存在期间次数有限）。 */
    static final String CAPSULE_REMIND = "notification.live.capsuleRemind";

    /** 点击后收起通知面板（放在 contentIntent 的 Intent 里）。 */
    static final String INTENT_DISMISS_PANEL = "dismissnotificationpannel";
}
