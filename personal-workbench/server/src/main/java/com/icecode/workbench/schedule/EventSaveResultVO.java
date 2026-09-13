package com.icecode.workbench.schedule;

import java.util.List;

public class EventSaveResultVO {
    private final EventVO event;
    private final List<String> warnings;

    public EventSaveResultVO(EventVO event, List<String> warnings) {
        this.event = event;
        this.warnings = warnings;
    }

    public EventVO getEvent() { return event; }
    public List<String> getWarnings() { return warnings; }
}
