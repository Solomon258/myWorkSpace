package com.icecode.workbench.inbox;

public interface ClassifyProvider {

    String name();

    ClassifySuggestionVO classify(String raw, String timezone) throws Exception;
}
