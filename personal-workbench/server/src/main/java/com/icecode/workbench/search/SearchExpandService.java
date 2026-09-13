package com.icecode.workbench.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 语义联想的缓存与降级。
 *
 * <p>这个服务的核心职责不是「调模型」，而是<b>把模型的不确定性挡住</b>：
 * 未配置、超时、返回垃圾，对上层一律表现为「这次没有联想词」，而不是错误。</p>
 *
 * <p>缓存是必要的而不是优化：同一个词在同一天里会被反复搜到，
 * 每次都花 1 秒等模型，会让「输入即搜」变成「输入等 1 秒」。</p>
 */
@Service
public class SearchExpandService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchExpandService.class);

    /** 成功结果缓存 24 小时：同义说法一天之内不会变。 */
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L;

    /**
     * 失败也缓存，但只缓存 60 秒。
     *
     * <p>不缓存失败的话，模型挂了或网络不通时，用户每敲一个字都要白等一次 1.2 秒超时 ——
     * 降级本来是保体验的，结果反而把每次输入都拖慢。缓存 60 秒既不再反复重试，
     * 又不会把一次网络抖动钉成 24 小时的永久故障。</p>
     */
    private static final long FAIL_TTL_MS = 60 * 1000L;

    private static final int CACHE_MAX = 500;

    private final SearchExpandClient client;

    /** LRU：accessOrder=true + removeEldestEntry，超上限时淘汰最久未用的。 */
    private final Map<String, Entry> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, Entry>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    public SearchExpandService(SearchExpandClient client) {
        this.client = client;
    }

    public SearchExpandVO expand(String q) {
        String query = q == null ? "" : q.trim();
        boolean enabled = client.isEnabled();
        if (query.isEmpty() || !enabled) {
            return new SearchExpandVO(new ArrayList<String>(), false, enabled);
        }

        Entry cached = cache.get(query);
        if (cached != null && !cached.expired()) {
            return new SearchExpandVO(cached.terms, true, true);
        }

        List<String> terms = new ArrayList<String>();
        boolean failed = false;
        try {
            terms = client.expand(query);
        } catch (Exception exception) {
            // 这里是**预期内**的降级路径（模型超时、返回格式不对、网络抖动），
            // 用户侧完全无感，所以用 debug 而不是 warn —— 否则一个字一次 warn 会把日志刷满。
            failed = true;
            LOGGER.debug("语义联想失败，本次不做联想：{}", exception.getMessage());
        }
        cache.put(query, new Entry(terms, failed));
        return new SearchExpandVO(terms, false, true);
    }

    private static final class Entry {
        final List<String> terms;
        final long at = System.currentTimeMillis();
        final boolean failed;

        Entry(List<String> terms, boolean failed) {
            this.terms = terms;
            this.failed = failed;
        }

        boolean expired() {
            long ttl = failed ? FAIL_TTL_MS : CACHE_TTL_MS;
            return System.currentTimeMillis() - at > ttl;
        }
    }
}
