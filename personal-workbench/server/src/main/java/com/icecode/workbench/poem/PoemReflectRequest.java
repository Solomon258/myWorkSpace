package com.icecode.workbench.poem;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 请求「AI 品读」一首诗。
 *
 * <p>只传四个纯文本字段（标题 / 朝代 / 作者 / 诗句），不传 id —— 诗库在前端
 * （{@code static/poems.js}）里，后端不认识任何一首诗。这样加诗、换诗都不用改后端，
 * 也不会因为前后端各存一份诗库而漂。
 */
public class PoemReflectRequest {

    @NotBlank(message = "诗词标题不能为空")
    @Size(max = 100, message = "诗词标题不能超过 100 字")
    private String title;

    @Size(max = 50, message = "朝代不能超过 50 字")
    private String dynasty;

    @Size(max = 50, message = "作者不能超过 50 字")
    private String author;

    /** 整首诗，用换行分隔句组。上限对齐前端单首诗的规模（长调约 300 字），留足余量。 */
    @NotBlank(message = "诗词正文不能为空")
    @Size(max = 1200, message = "诗词正文不能超过 1200 字")
    private String body;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDynasty() { return dynasty; }
    public void setDynasty(String dynasty) { this.dynasty = dynasty; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
}
