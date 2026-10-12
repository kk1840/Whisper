package com.stepos.whisper.llm;

import com.stepos.whisper.data.FactCard;

/**
 * 大模型判断“新消息的待办和某条已有待办是同一件事”后的合并建议：
 * {@link #incoming} 已作为独立待办入库（用户选择保留两条时它就是那条新待办，中途崩溃也不丢数据），
 * 机主确认合并时把 {@link #target} 的内容更新为合并后的描述，再删除 incoming。
 */
public class MergeSuggestion {
    public final long userId;
    /** 已有待办（保留的那条）。 */
    public final FactCard target;
    /** 新插入的候选待办（被吸收的那条）。 */
    public final FactCard incoming;
    /** 合并后的一句话描述与各字段（null 表示沿用 target 里的旧值）。 */
    public final String event;
    public final String time;
    public final String location;
    public final String people;

    public MergeSuggestion(long userId, FactCard target, FactCard incoming,
                           String event, String time, String location, String people) {
        this.userId = userId;
        this.target = target;
        this.incoming = incoming;
        this.event = event;
        this.time = time;
        this.location = location;
        this.people = people;
    }
}
