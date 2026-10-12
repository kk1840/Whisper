package com.stepos.whisper.data;

import java.util.List;

/** 等机主指定的一个昵称及其上下文，来源可以是聊天截图，也可以是微信通知。 */
public final class PendingAlias {
    public final long jobId;
    public final String name;
    /** 这个昵称说的话（最多几条）。 */
    public final List<String> messages;
    /** 这一批里还有几个昵称等待指定（含这个）。 */
    public final int remaining;
    /** 看起来像是这位用户（名字或以前的昵称相似），-1 表示没有推荐。 */
    public final long suggestedUserId;

    public PendingAlias(long jobId, String name, List<String> messages, int remaining, long suggestedUserId) {
        this.jobId = jobId;
        this.name = name;
        this.messages = messages;
        this.remaining = remaining;
        this.suggestedUserId = suggestedUserId;
    }
}
