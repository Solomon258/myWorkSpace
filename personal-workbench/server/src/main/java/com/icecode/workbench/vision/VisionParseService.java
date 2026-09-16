package com.icecode.workbench.vision;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icecode.workbench.attachment.AttachmentService;
import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.auth.AuthConstants;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.inbox.ClassifyPayload;
import com.icecode.workbench.inbox.InboxParseWriter;
import com.icecode.workbench.settings.SettingsService;
import com.icecode.workbench.util.TextUtil;
import com.icecode.workbench.util.TimeUtil;

/**
 * 视觉解析的编排：一张图 → 若干条「待确认」收录。
 *
 * <p><b>这个类里最要紧的一件事是事务边界。</b>调视觉模型要 3–10 秒，而 SQLite
 * 的写锁是库级独占的、Hikari 池只有 4 个连接。如果把「调模型」包在
 * {@code @Transactional} 里，那 10 秒内整个库写不进去，5 秒后所有并发写请求
 * 全部撞上 SQLITE_BUSY。所以：</p>
 * <ul>
 *   <li>{@link #parseAsync}（入口）**不带** {@code @Transactional}；</li>
 *   <li>写库只在三处，都委托给 {@link InboxParseWriter} 上各自的 {@code REQUIRES_NEW}
 *       短事务方法：建占位 / 写成功结果 / 写失败状态；</li>
 *   <li>调模型的 {@link VisionProvider#parse} 夹在两处写库之间，**完全裸奔**。</li>
 * </ul>
 *
 * <p>另一个约束是**串行**：执行器只有 1 个线程（见 {@code AsyncConfig}）。
 * 用户体验是「传三张图会一张一张解析」，换来的是界面永远不卡。</p>
 */
@Service
public class VisionParseService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VisionParseService.class);

    /**
     * {@code raw_content} 的硬上限 4000 字，与 {@code InboxCreateRequest.raw} 的
     * {@code @Size(max=4000)} 以及 {@code InboxService.requireRaw} 逐字一致。
     *
     * <p>视觉模型会把整张图的字都吐出来，一张密集的会议通知截图轻易超过 4000 字。
     * 直接写库会让这条记录在**后续任何一次编辑保存时**被 DTO 校验拒掉 ——
     * 一个谁都没改过的东西突然「参数不正确」，用户完全无从下手。
     * 所以写入前必须裁，并且**留痕**（{@code raw_truncated}）。</p>
     */
    static final int MAX_RAW_LENGTH = 4000;

    private final AttachmentService attachmentService;
    private final InboxParseWriter parseWriter;
    private final VisionProvider visionProvider;
    private final AppConfigRepository configRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VisionParseService(AttachmentService attachmentService, InboxParseWriter parseWriter,
                              VisionProvider visionProvider, AppConfigRepository configRepository) {
        this.attachmentService = attachmentService;
        this.parseWriter = parseWriter;
        this.visionProvider = visionProvider;
        this.configRepository = configRepository;
    }

    /**
     * 触发前的前置校验，让「配置不全」这类问题在**当前这个请求**里就报出来。
     *
     * <p>为什么不在异步任务里报：异步任务的异常只能落进日志，用户点完「解析」
     * 看到的是一片安静。这类「本来就能提前知道」的失败必须在同步路径上拦下，
     * 并且文案要指向设置页。</p>
     */
    public void assertParseable(List<Long> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "请先选择要解析的图片");
        }
        if (isLocalOnly()) {
            throw new BizException(ErrorCode.VISION_UNSUPPORTED,
                    "已开启「仅本地解析」，图片不会发往外部模型，因此无法解析。要使用图片解析请到「设置 → AI」关掉它");
        }
        if (!visionProvider.isConfigured()) {
            // 不用笼统文案：四个前提里到底缺哪个，直接说清楚。
            // 实测最常见的是「总开关没开」，而旧文案一律让人去填视觉模型名 —— 指错了地方，
            // 用户填完照样报错，体验极差（2026-09-16 真实踩到）。
            String reason = visionProvider.describeMissingConfig();
            throw new BizException(ErrorCode.VISION_UNSUPPORTED,
                    reason != null ? reason : ErrorCode.VISION_UNSUPPORTED.getMessage());
        }
    }

    /**
     * 异步解析一组图片。
     *
     * <p>为什么是「一组」而不是「一张」：用户拖进来三张截图，期望是三个待确认组，
     * 而不是三次独立进度。一次任务里串行处理三张，前端的轮询只需要盯一个状态。</p>
     *
     * <p>方法上**没有** {@code @Transactional}，这是刻意的 —— 见类注释。</p>
     */
    @Async(com.icecode.workbench.config.AsyncConfig.VISION_EXECUTOR)
    public void parseAsync(List<Long> attachmentIds, String source) {
        String timezone = timezone();
        for (Long attachmentId : attachmentIds) {
            if (attachmentId == null) {
                continue;
            }
            try {
                parseOne(attachmentId.longValue(), source, timezone);
            } catch (RuntimeException exception) {
                // 兜到最外层：单张图的任何意外都不该让整批静默停下。
                LOGGER.warn("图片解析未预期失败：附件 {} / {}",
                        attachmentId, exception.getClass().getSimpleName());
            }
        }
    }

    private void parseOne(long attachmentId, String source, String timezone) {
        AttachmentService.ImageSource image;
        String now = TimeUtil.now(timezone);
        try {
            image = attachmentService.openImage(attachmentId);
        } catch (BizException exception) {
            // 附件在触发与执行之间被删了 / 文件丢了。没有可记录的落点（连条目都还没建），
            // 只能记日志 —— 所以 Controller 在触发前已经先校验过一次存在性。
            LOGGER.warn("图片解析跳过附件 {}：{}", Long.valueOf(attachmentId), exception.getMessage());
            return;
        }

        // ① 先建占位条目并置 running：否则解析这十秒里页面上什么都没有，看起来像没反应。
        long inboxId = parseWriter.begin("正在解析图片「" + image.displayName() + "」…",
                source, Long.valueOf(attachmentId), now);

        VisionResult result;
        try {
            result = visionProvider.parse(image.getPath(), timezone);
        } catch (BizException exception) {
            // 单张失败不中断整批：用户拖了三张，第一张模糊导致失败不该让后两张也白传。
            parseWriter.fail(inboxId, exception.getMessage(), TimeUtil.now(timezone));
            LOGGER.info("图片解析失败（已记录到条目上）：{}", exception.getMessage());
            return;
        }

        writeResult(inboxId, image, result, source, timezone);
    }

    /**
     * 把解析结果落库。
     *
     * <p><b>一条图片条目 → 多条待确认记录</b>，这正是「重复调 confirm 第二次会抛异常」
     * 那个既有冲突的正面解法：图片里本来就有好几件事，用「一条收录对应一条实体」
     * 的模型硬装，用户只能在删掉前一条之后才能确认后一条。这里改成：</p>
     * <ul>
     *   <li>第一条**复用占位记录**（用户看到的那条就是他最终确认的那条，没有凭空消失的块）；</li>
     *   <li>其余每条新建一条 {@code origin='image'} 的待确认记录，都带同一个
     *       {@code source_attachment_id}，前端据此把它们显示成一组。</li>
     * </ul>
     */
    private void writeResult(long inboxId, AttachmentService.ImageSource image,
                             VisionResult result, String source, String timezone) {
        String now = TimeUtil.now(timezone);
        String rawText = result.getRawText() == null ? "" : result.getRawText().trim();
        boolean trimmed = rawText.length() > MAX_RAW_LENGTH;
        String clipped = trimmed ? rawText.substring(0, MAX_RAW_LENGTH) : rawText;
        if (clipped.isEmpty()) {
            clipped = "图片「" + image.displayName() + "」（模型没有读出文字）";
        }

        List<VisionItem> items = result.getItems();
        if (items.isEmpty()) {
            // 读出了文字但没有待办内容：留一条 memo 待确认，让用户自己决定要不要留。
            // 直接丢弃会让「解析成功，但页面上什么都没出现」，比留一条更让人困惑。
            VisionItem fallback = new VisionItem();
            fallback.setCategory("memo");
            fallback.setConfidence(0.5);
            fallback.setTitle(TextUtil.clip(clipped, 200));
            items = new ArrayList<VisionItem>();
            items.add(fallback);
        }

        List<InboxParseWriter.ParsedItem> payloads = new ArrayList<InboxParseWriter.ParsedItem>();
        for (int index = 0; index < items.size(); index++) {
            VisionItem item = items.get(index);
            payloads.add(new InboxParseWriter.ParsedItem(describe(item, clipped, index == 0),
                    item.getCategory(), item.getConfidence(), writePayload(item)));
        }
        parseWriter.finish(inboxId, source, Long.valueOf(image.getId()), now, trimmed, payloads);
        parseWriter.log("inbox",
                "图片解析完成：" + items.size() + " 条待确认（来自「" + image.displayName() + "」）", now);
    }

    /**
     * 每条待确认记录的 {@code raw_content}。
     *
     * <p>第一条用整图原文（那是用户核对时的底稿），其余条用本条的原文片段 ——
     * 让每一条都能独立看懂。全部塞整图原文的话，整理页会出现 N 个一模一样的长文本块，
     * 用户根本分不清哪条是哪条。</p>
     */
    private String describe(VisionItem item, String fallbackRaw, boolean first) {
        if (first) {
            return fallbackRaw;
        }
        String text = item.getEvidence() != null ? item.getEvidence()
                : item.getTitle() != null ? item.getTitle() : "（图中另一条）";
        return TextUtil.clip(text, MAX_RAW_LENGTH);
    }

    private String writePayload(VisionItem item) {
        // 复用 ClassifyPayload 的**字段名**（title/due/priority/start/end/eventType）：
        // 整理页的确认卡因此不需要为图片条目走第二套渲染分支 —— 它读的是同一批键。
        ClassifyPayload payload = new ClassifyPayload();
        payload.setTitle(item.getTitle());
        payload.setDue(item.getDue());
        payload.setPriority(item.getPriority());
        payload.setStart(item.getStart());
        payload.setEnd(item.getEnd());
        payload.setEventType(item.getEventType());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "解析结果序列化失败");
        }
    }

    public boolean isLocalOnly() {
        return Boolean.parseBoolean(configRepository.findValue(SettingsService.KEY_AI_VISION_LOCAL_ONLY));
    }

    private String timezone() {
        String timezone = configRepository.findValue(AuthConstants.CONFIG_TIMEZONE);
        return timezone == null ? "Asia/Shanghai" : timezone;
    }
}
