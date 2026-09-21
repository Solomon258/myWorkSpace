package com.icecode.workbench.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.icecode.workbench.common.BizException;

/**
 * 解析器的纯单元测试：喂固定的 HTML 样本，不连外网。
 *
 * <p>样本来自一个真实分享页的<b>脱敏</b>副本 —— 保留了全部结构特征
 * （内联 {@code __INITIAL_STATE__}、转义引号、作者简介里的换行、content 是字符串形式的 JSON），
 * 但把 uid、分享者昵称等替换成了占位值。
 */
class DedaoShareParserTest {

    private final DedaoShareParser parser = new DedaoShareParser();

    @Test
    void recognisesDedaoLinksOnly() {
        assertThat(parser.supports("https://www.dedao.cn/share/packet?packetId=abc")).isTrue();
        assertThat(parser.supports("https://d.dedao.cn/abc")).isTrue();
        assertThat(parser.supports("HTTPS://WWW.DEDAO.CN/share/packet?packetId=abc")).isTrue();
        assertThat(parser.supports("https://mp.weixin.qq.com/s/abc")).isFalse();
        assertThat(parser.supports("https://v.douyin.com/abc")).isFalse();
        assertThat(parser.supports(null)).isFalse();
        assertThat(parser.supports("  ")).isFalse();
    }

    @Test
    void readsTitleCourseAuthorFromInitialState() {
        ArticleMeta meta = parser.parseHtml(fixture(), "https://www.dedao.cn/share/packet?packetId=abc");

        assertThat(meta.platform).isEqualTo("dedao");
        assertThat(meta.title).isEqualTo("06｜问答：孩子沉迷电子产品，怎么办？");
        assertThat(meta.collection).isEqualTo("吴军·教育的方法50讲");
        assertThat(meta.author).isEqualTo("吴军");
    }

    @Test
    void rebuildsBodyAndDropsAudioBlock() {
        ArticleMeta meta = parser.parseHtml(fixture(), "https://www.dedao.cn/share/packet?packetId=abc");

        assertThat(meta.content).contains("我是吴军，欢迎你开启这场教育的理性探索之旅。");
        assertThat(meta.content).contains("今天是一期课程问答。");
        assertThat(meta.content).contains("龙卷风");
        assertThat(meta.content).contains("孩子上高一，喜欢玩电脑，周末回家使用时间过长。");
        assertThat(meta.content).doesNotContain("235219");
    }

    @Test
    void replacesUserNamePlaceholderWithReader() {
        ArticleMeta meta = parser.parseHtml(fixture(), "https://www.dedao.cn/share/packet?packetId=abc");

        assertThat(meta.content).contains("读者，你好。");
        assertThat(meta.content).doesNotContain("$_IGET_USER_NAME_$");
    }

    @Test
    void targetDirMatchesExistingVaultLayout() {
        ArticleMeta meta = parser.parseHtml(fixture(), "https://www.dedao.cn/share/packet?packetId=abc");

        assertThat(meta.targetDir()).isEqualTo("知识体系/得到/吴军·教育的方法50讲");
    }

    @Test
    void targetDirFallsBackWhenCourseMissing() {
        ArticleMeta noCourse = new ArticleMeta("dedao", "标题", "", "吴军", "u", "");
        assertThat(noCourse.targetDir()).isEqualTo("知识体系/得到/吴军");

        ArticleMeta onlyPlatform = new ArticleMeta("dedao", "标题", "", "", "u", "");
        assertThat(onlyPlatform.targetDir()).isEqualTo("知识体系/得到/dedao");
    }

    @Test
    void marksAnonymousShareAsTrialOnly() {
        // 匿名请求（userStatus=0，两个权限位都是 false）只能拿到试读正文：
        // 2026-09-20 用真实分享链接实测为 1023 字 / 16 段，约为全文的 20%。
        // 这个标记决定 ArticleCollectService 会不会拒绝写盘，必须守住。
        ArticleMeta meta = parser.parseHtml(fixture(), "https://www.dedao.cn/share/packet?packetId=abc");

        assertThat(meta.trialOnly).isTrue();
    }

    @Test
    void purchasedArticleIsNotTrialOnly() {
        // 带上登录 Cookie 后服务端会把 has_authority 置为 true 并下发全文。
        String html = fixture().replace("\"has_authority\":false", "\"has_authority\":true");

        assertThat(parser.parseHtml(html, "u").trialOnly).isFalse();
    }

    @Test
    void redPacketAuthorityAlsoCountsAsFullAccess() {
        // 领取知识红包后服务端给的是 red_packet_authority，而不是 has_authority ——
        // 只认一个字段会把「已领红包」的文章误判成试读、直接拒收（比写入半篇更烦人）。
        String html = fixture().replace("\"red_packet_authority\":false", "\"red_packet_authority\":true");

        assertThat(parser.parseHtml(html, "u").trialOnly).isFalse();
    }

    @Test
    void missingAuthorityFieldsMeanNoAccess() {
        // 页面改版 / 精简版里可能根本没有这两个字段。缺字段必须按「没有权限」处理 ——
        // 反过来的默认值会让我们在没权限时照样写半篇文章，那正是本次要修的问题。
        String html = fixture()
                .replace("\"has_authority\":false,", "")
                .replace(",\"red_packet_authority\":false", "");
        assertThat(html).doesNotContain("has_authority").doesNotContain("red_packet_authority");

        assertThat(parser.parseHtml(html, "u").trialOnly).isTrue();
    }

    @Test
    void failsLoudlyWhenPageHasNoArticleData() {
        assertThatThrownBy(() -> parser.parseHtml("<html><body>hello</body></html>", "u"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是得到的文章分享页");

        String emptyState = "<script>window.__INITIAL_STATE__={\"packetInfo\":{}}</script>";
        assertThatThrownBy(() -> parser.parseHtml(emptyState, "u"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没能读出文章标题");
    }

    private String fixture() {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("fixtures/dedao-share-packet.html")) {
            if (in == null) throw new IllegalStateException("缺少测试样本 fixtures/dedao-share-packet.html");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) buffer.write(chunk, 0, read);
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
