package com.icecode.workbench.collect;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;

/**
 * 解析「得到」的分享链接。
 *
 * <p>得到的分享页是一个 JS 渲染的空壳（{@code <title>} 为空、没有 og 标签），
 * 直接读 HTML 拿不到任何可读信息。但页面把服务端数据塞进了
 * {@code window.__INITIAL_STATE__} 这个内联 JSON，里面有我们需要的一切：
 *
 * <pre>
 *   packetInfo.article_title          → 文章标题      「06｜问答：孩子沉迷电子产品，怎么办？」
 *   packetInfo.title                  → 课程名        「吴军·教育的方法50讲」
 *   packetInfo.lecturer_list[0].name  → 作者          「吴军」
 * </pre>
 *
 * <p>注意 <b>{@code packetInfo.title} 取出来的值正好等于用户 Vault 里的一级目录名</b>
 * （这不是巧合，它就是课程在得到的官方命名），所以「按作者分文件夹」可以做到精确匹配
 * 而不是猜。这些字段是 2026-09-14 用一个真实分享链接实测确认的。
 *
 * <h3>正文为什么可能只有一小半（2026-09-20 查清）</h3>
 *
 * <p>分享页内联的 {@code articleInfo.content} <b>在匿名请求下只包含试读部分</b>：
 * 实测 16 段 / 1023 字，末段本身是完整句子（不是被截断的半个字）。
 * 判据是服务端的两个权限位见 {@link #hasAuthority}；匿名时 {@code userStatus = 0}。
 *
 * <p>已验证拿不到全文的三条路（都实测过）：{@code /share/packet}、{@code /share/trialReading}、
 * 老版 {@code m.igetget.com/share/course/pay/detail}（后者是 Vue 空壳，正文字符数为 0）。
 * 得到的 {@code acceptClass（领取红包）} 在已登录时只做 {@code location.reload()} ——
 * 权限由服务端凭会话判定，<b>前端与后端都绕不过去</b>，唯一的路是带上用户自己的登录态。
 *
 * <p>直连即可，不需要代理；移动端 UA 返回的内容与桌面端一致，但用移动端 UA 更贴近真实分享场景。
 */
@Service
public class DedaoShareParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(DedaoShareParser.class);

    private static final String MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final String STATE_ANCHOR = "__INITIAL_STATE__";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 是否是能得到分享链接（含 {@code d.dedao.cn} 短链）。
     *
     * <p>做成实例方法而不是 static，是为了让调用方能注入替身来测试 ——
     * 静态方法没法被 mock，只能真连外网跑测试。
     */
    public boolean supports(String url) {
        if (url == null) return false;
        String lower = url.trim().toLowerCase();
        return lower.contains("dedao.cn") || lower.contains("igetget.com");
    }

    /**
     * 抓取并解析，失败一律抛 {@link ErrorCode#ARTICLE_PARSE_FAILED}（带中文原因），
     * 绝不返回半成品 —— 调用方拿到对象就意味着 title 一定非空。
     */
    public ArticleMeta parse(String url) {
        return parse(url, null);
    }

    /**
     * 带登录态抓取。
     *
     * <p>{@code cookie} 是用户在设置里粘贴的浏览器 Cookie 整串。为空 = 匿名请求，
     * 此时服务端只下发试读正文，返回对象的 {@link ArticleMeta#trialOnly} 为 true。
     *
     * <p><b>为什么不在这里读配置</b>：解析器保持「纯函数」形态（输入 URL + 凭据，
     * 输出元信息），配置从哪来由调用方决定 —— 这样单测可以直接喂样本，
     * 不必为了测试去造一个配置表。
     */
    public ArticleMeta parse(String url, String cookie) {
        String html = fetch(url, cookie);
        if (html == null) {
            throw new BizException(ErrorCode.ARTICLE_PARSE_FAILED,
                    "打不开这个链接，请确认它能在手机浏览器里正常打开");
        }
        return parseHtml(html, url);
    }

    /**
     * 纯解析：HTML → 文章元信息。
     *
     * <p>刻意和网络抓取分开，这样测试可以喂一段固定的 HTML，既不必依赖外网，
     * 也不会因为得到改版或网络抖动把测试变成「时好时坏」。
     */
    ArticleMeta parseHtml(String html, String url) {
        JsonNode state = extractInitialState(html);
        if (state == null) {
            throw new BizException(ErrorCode.ARTICLE_PARSE_FAILED,
                    "这个链接不是得到的文章分享页（页面里没有文章数据）");
        }
        JsonNode packet = state.path("packetInfo");
        if (packet.isMissingNode() || packet.isNull()) {
            throw new BizException(ErrorCode.ARTICLE_PARSE_FAILED,
                    "页面打开了，但里面没有文章信息，可能是分享已过期");
        }

        String title = text(packet, "article_title");
        if (title.isEmpty()) {
            throw new BizException(ErrorCode.ARTICLE_PARSE_FAILED,
                    "没能读出文章标题，可能是分享已过期");
        }
        String collection = text(packet, "title");
        String author = "";
        JsonNode lecturers = packet.path("lecturer_list");
        if (lecturers.isArray() && lecturers.size() > 0) {
            author = text(lecturers.get(0), "name");
        }
        String content = extractContent(state);
        boolean authority = hasAuthority(packet);
        LOGGER.info("解析得到分享成功：{} / {} / {} / 正文 {} 字 / 阅读权限 {}（userStatus={}）",
                collection, author, title, content.length(), authority, state.path("userStatus").asInt(-1));
        return new ArticleMeta("dedao", title, collection, author, url, content, !authority);
    }

    /**
     * 服务端是否给了阅读权限 —— 没有就只拿到了试读正文。
     *
     * <p>两个字段取并集，与得到前端 {@code packet.js} 里的 {@code hasAuthority()} 判断一致：
     * <pre>
     *   packetInfo.has_authority                    → 已购买 / 已领取红包 的通用位
     *   packetInfo.red_packet_data.red_packet_authority → 知识红包的领取位
     * </pre>
     *
     * <p>2026-09-20 用真实分享链接实测：匿名请求这三个位置分别是 {@code false / false / userStatus=0}，
     * 正文只有 1023 字（16 段）。页面那句「本篇内容剩余80%，继续学习」是前端写死的字符串
     * （每个分享页都显示 80%），不是真实比例。
     */
    private boolean hasAuthority(JsonNode packet) {
        if (packet.path("has_authority").asBoolean(false)) return true;
        return packet.path("red_packet_data").path("red_packet_authority").asBoolean(false);
    }

    /**
     * 把 {@code articleInfo.content} 还原成 Markdown 正文。
     *
     * <p>这个字段本身是一段 <b>JSON 字符串</b>（要再解析一次），里面按块存放正文：
     * <pre>
     *   {"type":"audio", ...}          音频文件，跳过
     *   {"type":"salutation", "text"}  开场问候
     *   {"type":"paragraph",  "text"}  正文段落
     *   {"type":"label-group","text"}  提问者姓名
     *   {"type":"blockquote", "text"}  学员提问
     * </pre>
     *
     * <p>取每个块的 {@code text} 按顺序拼接即可得到完整正文。已用真实链接逐段比对过
     * 用户 Vault 里既有的笔记，内容一致（而且更干净 —— 既有笔记里混进了转换时产生的
     * 多余 {@code ==} 高亮标记和连字符）。
     *
     * <p>两个细节：{@code $_IGET_USER_NAME_$} 是得到注入用户昵称的占位符，用户既有笔记里
     * 呈现为「读者」，这里保持一致；块之间用空行分隔，段落才是 Markdown 的一个段落。
     */
    private String extractContent(JsonNode state) {
        JsonNode raw = state.path("articleInfo").path("content");
        if (raw.isMissingNode() || raw.isNull()) return "";
        String json = raw.isTextual() ? raw.asText("") : raw.toString();
        if (json.trim().isEmpty()) return "";

        JsonNode blocks;
        try {
            blocks = objectMapper.readTree(json);
        } catch (Exception exception) {
            LOGGER.warn("正文 JSON 解析失败：{}", exception.getMessage());
            return "";
        }
        if (blocks == null || !blocks.isArray()) return "";

        StringBuilder markdown = new StringBuilder();
        for (JsonNode block : blocks) {
            String type = block.path("type").asText("");
            if ("audio".equals(type) || "image".equals(type) || "video".equals(type)) continue;
            String text = block.path("text").asText("").trim();
            if (text.isEmpty()) continue;
            text = text.replace("$_IGET_USER_NAME_$", "读者");
            if (markdown.length() > 0) markdown.append("\n\n");
            markdown.append(text);
        }
        return markdown.toString();
    }

    // ------------------------------------------------------------------ 抓取

    private String fetch(String url, String cookie) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url.trim()).openConnection();
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", MOBILE_UA);
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            // 带上用户自己的登录态：服务端据此决定 has_authority，从而下发全文而不是试读。
            // 整串原样透传 —— 逐项解析再拼回去只会因为同名 / 域不匹配而漏掉关键项。
            if (cookie != null && !cookie.trim().isEmpty()) {
                connection.setRequestProperty("Cookie", cookie.trim());
            }
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                LOGGER.warn("抓取得到分享页返回 {}：{}", status, url);
                return null;
            }
            InputStream stream = connection.getInputStream();
            try {
                return readAll(stream);
            } finally {
                stream.close();
            }
        } catch (Exception exception) {
            LOGGER.warn("抓取得到分享页失败：{}：{}", url, exception.getMessage());
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String readAll(InputStream stream) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
            if (buffer.size() > MAX_BODY_BYTES) break;
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------- 提取 JSON

    /**
     * 从 HTML 里抠出 {@code window.__INITIAL_STATE__} 的 JSON 对象。
     *
     * <p>不能用正则：得到的这个 JSON 有 8 KB、多层嵌套，非贪婪匹配到第一个 {@code }} 就断了。
     * 这里用<b>括号配平</b>逐个字符走，并且要正确处理字符串内部的转义引号
     * （作者简介里有换行和引号，不处理就会提前结束）。
     */
    private JsonNode extractInitialState(String html) {
        int anchor = html.indexOf(STATE_ANCHOR);
        if (anchor < 0) return null;
        int start = html.indexOf('{', anchor);
        if (start < 0) return null;

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int end = -1;
        for (int i = start; i < html.length(); i++) {
            char ch = html.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') inString = false;
                continue;
            }
            if (ch == '"') inString = true;
            else if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) { end = i + 1; break; }
            }
        }
        if (end < 0) return null;
        try {
            return objectMapper.readTree(html.substring(start, end));
        } catch (Exception exception) {
            LOGGER.warn("解析 __INITIAL_STATE__ 失败：{}", exception.getMessage());
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return "";
        return value.asText("").trim();
    }
}
