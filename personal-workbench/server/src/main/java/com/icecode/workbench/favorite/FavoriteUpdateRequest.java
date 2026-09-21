package com.icecode.workbench.favorite;

import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

public class FavoriteUpdateRequest {

    // 标题与正文都不再单独强制非空（原来 min = 1）：两者**至少写一项**就允许保存，
    // 与创建入口 FavoriteCreateRequest 保持同一条规则。min = 1 会把「清空标题、正文还在」
    // 这种合理编辑拦在注解层，报的还是「收藏标题不能为空」——而用户明明填了正文。
    @Size(max = 200, message = "收藏标题不能超过 200 字")
    private String title;

    @Size(max = 4000, message = "收藏内容不能超过 4000 字")
    private String content;

    @Size(max = 500, message = "标签不能超过 500 字")
    private String tags;

    @Size(max = 500, message = "链接不能超过 500 字")
    private String url;

    @Pattern(regexp = "work|life", message = "收藏空间只能填 工作(work) / 生活(life)")
    private String grp;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getGrp() { return grp; }
    public void setGrp(String grp) { this.grp = grp; }
}
