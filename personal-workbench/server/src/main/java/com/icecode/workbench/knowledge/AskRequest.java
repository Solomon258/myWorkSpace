package com.icecode.workbench.knowledge;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class AskRequest {

    @NotBlank(message = "提问不能为空")
    @Size(max = 500, message = "提问不能超过 500 字")
    private String question;

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
}
