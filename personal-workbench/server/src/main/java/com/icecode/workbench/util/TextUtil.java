package com.icecode.workbench.util;

/**
 * 文本裁剪工具。
 *
 * <p>只做长度裁剪，<b>不追加「…」等省略号</b>。因为这些结果会直接落库成为
 * task / schedule_event / favorite / knowledge_note 的正式标题，省略号一旦写入就永久污染数据
 * （历史上 demo 原型的 {@code slice(0,24)+'…'} 被逐处复制到 Java 侧，导致标题里带省略号）。
 * 纯展示场景（列表摘要、引用片段）需要省略号时请自行拼接，不要用这里。</p>
 */
public final class TextUtil {

    private TextUtil() {
    }

    /** 去掉首尾空白后裁剪到 maxLength；null 视为空串，maxLength ≤ 0 返回空串。 */
    public static String clip(String text, int maxLength) {
        if (maxLength <= 0) return "";
        String value = text == null ? "" : text.trim();
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
