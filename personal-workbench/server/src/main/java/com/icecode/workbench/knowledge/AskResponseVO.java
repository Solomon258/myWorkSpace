package com.icecode.workbench.knowledge;

import java.util.List;

public class AskResponseVO {

    public static class Citation {
        private final String file;
        private final String heading;
        private final String snippet;
        private final String obsidianUrl;

        public Citation(String file, String heading, String snippet, String obsidianUrl) {
            this.file = file;
            this.heading = heading;
            this.snippet = snippet;
            this.obsidianUrl = obsidianUrl;
        }

        public String getFile() { return file; }
        public String getHeading() { return heading; }
        public String getSnippet() { return snippet; }
        public String getObsidianUrl() { return obsidianUrl; }
    }

    private final boolean answered;
    private final boolean capturedToInbox;
    private final boolean llmUsed;
    private final String answer;
    private final List<Citation> citations;

    public AskResponseVO(boolean answered, boolean capturedToInbox, boolean llmUsed,
                         String answer, List<Citation> citations) {
        this.answered = answered;
        this.capturedToInbox = capturedToInbox;
        this.llmUsed = llmUsed;
        this.answer = answer;
        this.citations = citations;
    }

    public boolean isAnswered() { return answered; }
    public boolean isCapturedToInbox() { return capturedToInbox; }
    public boolean isLlmUsed() { return llmUsed; }
    public String getAnswer() { return answer; }
    public List<Citation> getCitations() { return citations; }
}
