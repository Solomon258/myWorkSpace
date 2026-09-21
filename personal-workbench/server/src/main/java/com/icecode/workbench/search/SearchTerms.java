package com.icecode.workbench.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 检索词的切分与转义。
 *
 * <p>中文没有空格分词，SQLite 的 LIKE 也做不了词干匹配，所以这里沿用
 * {@code AssistantService} 已经验证过的做法：<b>CJK 切成二元组</b>
 * （「速率限制」→ 速率 / 率限 / 限制），拉丁词按 ≥2 个字符整体保留。
 * 二元组的召回率明显高于整串匹配（「限流」能命中「限流策略」），
 * 代价是会产生少量误匹配，交给打分排序把真正相关的顶上去。</p>
 *
 * <p><b>注意</b>{@link #split(String)} 允许返回空列表（用户只输入了标点、纯空格等）。
 * 调用方<b>必须</b>把空列表当成「没有可检索内容」显式处理 —— 少了这一层，
 * SQL 的 WHERE 会退化成「没有任何条件」，一次搜索会把整库数据全量返回。</p>
 */
public final class SearchTerms {

    /** 二元组的最小长度：1 个汉字也要保留（否则搜「书」这类单字词永远无结果）。 */
    private static final int GRAM = 2;

    /** 拉丁词的最短长度。单个字母（如 a、1）作为检索词只带来噪音，不参与匹配。 */
    private static final int MIN_LATIN = 2;

    private SearchTerms() {
    }

    /**
     * 把查询串切成检索词，保持出现顺序并去重。
     *
     * <p>切分规则：汉字连续段切二元组；字母数字连续段整体成词；其余字符（标点、空白）
     * 一律当分隔符。用户从一句话里粘一段关键词过来时常常带标点，所以标点不能算作检索内容。</p>
     */
    public static List<String> split(String query) {
        List<String> terms = new ArrayList<String>();
        if (query == null) return terms;

        StringBuilder cjk = new StringBuilder();
        StringBuilder latin = new StringBuilder();
        for (int i = 0; i < query.length(); i++) {
            char ch = query.charAt(i);
            if (isCjk(ch)) {
                flushLatin(terms, latin);
                cjk.append(ch);
            } else if (Character.isLetterOrDigit(ch)) {
                flushCjk(terms, cjk);
                latin.append(ch);
            } else {
                flushCjk(terms, cjk);
                flushLatin(terms, latin);
            }
        }
        flushCjk(terms, cjk);
        flushLatin(terms, latin);
        return dedupe(terms);
    }

    /** JOIN 查询串：只保留可检索字符，用于和「原始整串」比对（判断整词是否命中标题）。 */
    public static String normalized(String query) {
        if (query == null) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < query.length(); i++) {
            char ch = query.charAt(i);
            if (Character.isLetterOrDigit(ch)) builder.append(Character.toLowerCase(ch));
        }
        return builder.toString();
    }

    /**
     * 转义 LIKE 的通配符。
     *
     * <p>规则与 {@code FavoriteRepository.escapeLike} 一致（那边是私有的，跨包取不到；
     * 两处必须保持同一口径，否则「搜 100% 返回全库」这类问题会只在一处复现）。
     * 反斜杠要先转义，否则会把后面补上的转义符自己再吃一遍。</p>
     */
    public static String escapeLike(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 生成 {@code LIKE ? ESCAPE '\'} 用的模式串。 */
    public static String likePattern(String term) {
        return "%" + escapeLike(term.toLowerCase()) + "%";
    }

    private static boolean isCjk(char ch) {
        return ch >= 0x4E00 && ch <= 0x9FFF;
    }

    private static void flushCjk(List<String> terms, StringBuilder run) {
        String text = run.toString();
        if (text.isEmpty()) return;
        if (text.length() < GRAM) {
            terms.add(text);
        } else {
            for (int i = 0; i + GRAM <= text.length(); i++) terms.add(text.substring(i, i + GRAM));
        }
        run.setLength(0);
    }

    private static void flushLatin(List<String> terms, StringBuilder run) {
        String text = run.toString();
        if (text.length() >= MIN_LATIN) terms.add(text.toLowerCase());
        run.setLength(0);
    }

    private static List<String> dedupe(List<String> terms) {
        return new ArrayList<String>(new LinkedHashSet<String>(terms));
    }
}
