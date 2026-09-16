package com.icecode.workbench.vision;

import java.util.ArrayList;
import java.util.List;

/**
 * 一张图的解析结果。
 *
 * <p>{@code rawText} 是图里读出的**全部文字**，会落进 {@code inbox_item.raw_content}
 * 作为用户可编辑的原文；{@code items} 是结构化后的若干条待确认条目。两者都要：
 * 只有 items 时用户无法纠正模型漏读的内容，只有 rawText 时等于没解析。</p>
 */
public class VisionResult {

    private String rawText;
    private final List<VisionItem> items = new ArrayList<VisionItem>();

    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }
    public List<VisionItem> getItems() { return items; }
}
