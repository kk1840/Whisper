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
}
