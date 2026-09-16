package com.icecode.workbench.vision;

/**
 * 视觉模型从一张图里读出的一条待办/日程/备忘。
 *
 * <p>与 {@code ClassifyPayload} 的区别：那个是「分类器的输出」（一条文本 → 一个分类），
 * 这个是「视觉解析的输出」（一张图 → **多条**条目）。图片里天然会有好几件事
 * （一张会议通知截图可能同时是「确认参会」的任务和「周三 14:00」的日程），
 * 强行合并成一条会让用户丢信息。</p>
 */
public class VisionItem {

    /** task / schedule / memo / knowledge。 */
    private String category;
    /** 0–1。视觉模型对清晰截图的置信度普遍偏高，所以它**不参与**自动批量确认。 */
    private double confidence;
    private String title;
    /** yyyy-MM-dd 或 null。 */
    private String due;
    /** P0–P3。 */
    private String priority;
    /** HH:mm 或 null。 */
    private String start;
    private String end;
    /** meeting / deep_block / other。 */
    private String eventType;
    /**
     * 这一条对应的**图中原文片段**。
     * 用户核对时最需要的就是「模型是照着哪几个字读出这条的」——只给一个提炼后的
     * 短标题，用户没法判断模型有没有读错。
     */
    private String evidence;

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDue() { return due; }
    public void setDue(String due) { this.due = due; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getStart() { return start; }
    public void setStart(String start) { this.start = start; }
    public String getEnd() { return end; }
    public void setEnd(String end) { this.end = end; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }
}
