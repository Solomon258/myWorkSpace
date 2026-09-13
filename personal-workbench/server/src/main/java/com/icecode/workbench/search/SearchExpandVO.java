package com.icecode.workbench.search;

import java.util.List;

/**
 * 语义联想的结果。
 *
 * <p>这个接口<b>不会失败</b>：未配置 AI、调用超时、模型返回垃圾，一律返回空 {@code terms}
 * 且 {@code code} 仍为 0。语义是增强不是依赖 —— 让一次联想失败把整个搜索结果打成错误页，
 * 是拿主链路的可用性去换一个锦上添花的功能。</p>
 */
public class SearchExpandVO {

    private final List<String> terms;
    private final boolean cached;
    private final boolean enabled;

    public SearchExpandVO(List<String> terms, boolean cached, boolean enabled) {
        this.terms = terms;
        this.cached = cached;
        this.enabled = enabled;
    }

    public List<String> getTerms() { return terms; }
    /** 是否命中缓存（命中时前端不必再等，也不该再显示「正在联想…」）。 */
    public boolean isCached() { return cached; }
    /** AI 是否已配置。未配置时前端不显示任何联想相关的提示，免得承诺一个做不到的事。 */
    public boolean isEnabled() { return enabled; }
}
