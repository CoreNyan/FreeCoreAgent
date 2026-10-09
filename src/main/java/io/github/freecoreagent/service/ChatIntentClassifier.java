package io.github.freecoreagent.service;

import java.util.Locale;

/**
 * Lightweight, non-intrusive pre-filter.
 * Only discards obvious non-dialogue noise (pure digits, pure symbols, or explicit whispers to someone else),
 * leaving semantic judgment entirely to LLM intelligence so CoreNyan acts like a real player
 * without needing robotic wake-words ("小可").
 */
public final class ChatIntentClassifier {

    public static boolean shouldPassToBrain(String senderName, String message) {
        if (message == null) return false;
        String raw = message.trim();
        if (raw.isBlank()) return false;

        // 1. If message starts with an @ to someone else (e.g. "@lovnjers ..."), respect their two-person conversation
        String lower = raw.toLowerCase(Locale.ROOT);
        if (raw.startsWith("@") && !lower.startsWith("@小可") && !lower.startsWith("@corenyan")) {
            return false;
        }

        // 2. Pure single punctuation or very short spam (e.g. "?", "...", "6", "草", "好")
        if (raw.length() <= 1 && !raw.equals("?") && !raw.equals("？")) {
            return false;
        }
        if (raw.matches("^[0-9]+$") || raw.matches("^[!?.~，。？！]+$")) {
            return false;
        }

        // Everything else: natural questions, chatter, comments, help queries -> let LLM decide!
        return true;
    }

    /**
     * Determines whether a message is an explicit question or help request asking the server/community.
     * Questions should ALWAYS be answered by OP CoreNyan!
     */
    public static boolean isHelpOrQuestion(String text) {
        if (text == null) return false;
        String raw = text.trim();
        if (raw.contains("?") || raw.contains("？")) return true;
        String[] qKeywords = {
            "怎么", "怎样", "如何", "在哪", "哪里", "为什么", "为啥", "能不能", "可以吗", "行不行",
            "有没", "有没有", "怎么办", "谁有", "什么是", "啥是", "怎么去", "怎么走", "怎么弄",
            "请问", "求助", "帮我", "教程", "指令", "命令", "世界", "传送", "出去",
            "卡", "好卡", "tps", "延时", "ping", "卡顿", "掉帧", "卡死了", "走不动"
        };
        for (String kw : qKeywords) {
            if (raw.contains(kw)) return true;
        }
        return false;
    }
}
