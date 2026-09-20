package com.icecode.workbench.knowledge;

import java.util.List;

public class AskResponseVO {

    public static class Citation {
        /** 1 起算的编号，与答案正文里的 [1][2] 标记一一对应，也是前端「参考来源」列表的序号。 */
        private final int index;
        private final String file;
        private final String heading;
        private final String snippet;
        private final String obsidianUrl;

        public Citation(int index, String file, String heading, String snippet, String obsidianUrl) {
            this.index = index;
            this.file = file;
            this.heading = heading;
            this.snippet = snippet;
            this.obsidianUrl = obsidianUrl;
        }

        public int getIndex() { return index; }
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
