package com.icecode.workbench.inbox;

public class ClassifySuggestionVO {
    private final String category;
    private final double confidence;
    private final ClassifyPayload payload;
    private final boolean needsConfirm;

    public ClassifySuggestionVO(String category, double confidence, ClassifyPayload payload, boolean needsConfirm) {
        this.category = category;
        this.confidence = confidence;
        this.payload = payload;
        this.needsConfirm = needsConfirm;
    }

    public String getCategory() { return category; }
    public double getConfidence() { return confidence; }
    public ClassifyPayload getPayload() { return payload; }
    public boolean isNeedsConfirm() { return needsConfirm; }
}
