package com.stepos.whisper.llm;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/** 大模型从聊天截图的 OCR 文字里整理出的参与者和对话。 */
public class ChatParse {
    public static final String ROLE_OWNER = "owner";
    public static final String ROLE_OTHER = "other";

    /** 对话里代表机主的发送者名。 */
    public static final String OWNER_SPEAKER = "机主";

    @SerializedName("participants")
    public List<Participant> participants = new ArrayList<>();

    @SerializedName("dialogue")
    public List<Message> dialogue = new ArrayList<>();

    public static class Participant {
        /** 截图里显示的昵称；机主本人在单聊里没有昵称，可为 null。 */
        @SerializedName("name")
        public String name;

        /** {@link #ROLE_OWNER} 或 {@link #ROLE_OTHER}。 */
        @SerializedName("role")
        public String role;
    }

    public static class Message {
        @SerializedName("speaker")
        public String speaker;

        @SerializedName("text")
        public String text;
    }

    /**
     * 机主以外、有昵称的参与者，归一化（见 {@link NameMatcher#normalize}）并去重后按出现顺序。
     * 模型偶尔会把“机主”也写成 other，或者把发送者只写在 dialogue 里而漏掉 participants，
     * 这里都补上：dialogue 里出现过、不是机主的发送者也算参与者。
     */
    public List<String> otherNames() {
        List<String> names = new ArrayList<>();
        if (participants != null) {
            for (Participant p : participants) {
                if (p == null || p.name == null || ROLE_OWNER.equals(p.role)) {
                    continue;
                }
                addName(names, p.name);
            }
        }
        if (dialogue != null) {
            for (Message m : dialogue) {
                if (m != null && m.speaker != null) {
                    addName(names, m.speaker);
                }
            }
        }
        return names;
    }

    /** 模型在认不出昵称时可能写的泛称，不是人名，不能当昵称记下来（否则会把下一张截图里的别人也对应过去）。 */
    private static final String[] GENERIC_SPEAKERS = {
            "机主", "我", "对方", "未知", "其他人", "其他", "群成员", "系统", "系统消息", "unknown", "other", "user",
    };

    private static void addName(List<String> names, String raw) {
        String name = NameMatcher.normalize(raw);
        if (name.isEmpty() || names.contains(name)) {
            return;
        }
        for (String generic : GENERIC_SPEAKERS) {
            if (generic.equalsIgnoreCase(name)) {
                return;
            }
        }
        names.add(name);
    }

    /** 对话写成“发送者：内容”的多行文本；没有任何消息时返回 null。 */
    public String dialogueText() {
        if (dialogue == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Message m : dialogue) {
            if (m == null || m.text == null || m.text.trim().isEmpty()) {
                continue;
            }
            String speaker = m.speaker == null || m.speaker.trim().isEmpty() ? "未知" : m.speaker.trim();
            sb.append(speaker).append('：').append(m.text.trim()).append('\n');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 某个发送者在对话里说的话，用于指定昵称对应谁时给机主看。 */
    public List<String> messagesOf(String speaker, int limit) {
        List<String> out = new ArrayList<>();
        if (dialogue == null) {
            return out;
        }
        String wanted = NameMatcher.normalize(speaker);
        for (Message m : dialogue) {
            if (m != null && m.text != null && !m.text.trim().isEmpty()
                    && wanted.equals(NameMatcher.normalize(m.speaker))) {
                out.add(m.text.trim());
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }
}
