package com.stepos.whisper.llm;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/** 大模型按约定 JSON 结构返回的提取结果：新的待办和事实，以及已完成的待办、不再成立的事实。 */
public class ExtractionResult {
    @SerializedName("new_todos")
    public List<Todo> newTodos = new ArrayList<>();

    @SerializedName("new_facts")
    public List<Fact> newFacts = new ArrayList<>();

    @SerializedName("completed_todo_ids")
    public List<Long> completedTodoIds = new ArrayList<>();

    @SerializedName("outdated_fact_ids")
    public List<Long> outdatedFactIds = new ArrayList<>();

    /** 新消息里的待办和某条已有待办是同一件事的合并建议（只有微信通知来源的提示词会输出）。 */
    @SerializedName("merged_todos")
    public List<MergeTodo> mergedTodos = new ArrayList<>();

    public static class Todo {
        @SerializedName("time")
        public String time;

        @SerializedName("location")
        public String location;

        @SerializedName("people")
        public String people;

        @SerializedName("event")
        public String event;

        @SerializedName("type")
        public String type;
    }

    public static class Fact {
        @SerializedName("content")
        public String content;

        @SerializedName("type")
        public String type;
    }

    public static class MergeTodo {
        /** 要合并进去的那条已有待办的 id。 */
        @SerializedName("target_id")
        public long targetId;

        /** 这条新消息单独作为待办时的一句话描述（合并弹窗的下卡片；拒绝合并时保留的也是它）。 */
        @SerializedName("new_event")
        public String newEvent;

        @SerializedName("time")
        public String time;

        @SerializedName("location")
        public String location;

        @SerializedName("people")
        public String people;

        /** 两边信息合在一起后的一句完整描述（合并后的卡片）。 */
        @SerializedName("event")
        public String event;
    }
}
