package com.icecode.workbench.collect;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 收藏文章的请求体。
 *
 * <p>字段叫 {@code content} 而不是 {@code url}，是因为从微信分享过来的往往是<b>一整段文案</b>
 * （「我分享给你一个知识红包 https://… 复制此链接打开得到」），链接只是其中一段。
 * 服务端负责把链接抽出来，不要为难用户去找。
 */
public class CollectRequest {

    @NotBlank(message = "请先粘贴文章链接")
    @Size(max = 2000, message = "内容太长了，请只粘贴分享链接或分享文案")
    private String content;

    public String getContent() { return content; }

    public void setContent(String content) { this.content = content; }
}
