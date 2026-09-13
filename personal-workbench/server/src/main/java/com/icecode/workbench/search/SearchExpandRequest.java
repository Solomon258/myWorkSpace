package com.icecode.workbench.search;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 语义联想的入参。
 *
 * <p>校验注解必须自带中文 {@code message}：不写就会透出 Hibernate 的英文默认文案
 * （{@code must not be blank} / {@code size must be between 0 and 100}），
 * {@code ValidationMessageTest} 会直接把这条钉红。</p>
 */
public class SearchExpandRequest {

    @NotBlank(message = "请输入要联想的词")
    @Size(max = SearchService.MAX_QUERY, message = "关键词不能超过 " + SearchService.MAX_QUERY + " 字")
    private String q;

    public String getQ() { return q; }

    public void setQ(String q) { this.q = q; }
}
