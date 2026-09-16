package com.icecode.workbench.inbox;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 图片解析写入收录条目的**唯一入口**，供 vision 包跨包调用。
 *
 * <p>为什么不把这些方法直接加在 {@code InboxService} 上：{@code InboxService} 已经
 * 依赖了 TaskService / EventRepository / KnowledgeService，而 {@code VisionParseService}
 * 又需要被 InboxController 调用 —— 直接把写库方法挂过去会绕成一个环。
 * 单独一个薄薄的门面既打断了环，也把「解析写库只允许做这几件事」这件事
 * 从类型上钉住了（这个类里没有 confirm / classify / delete）。</p>
 *
 * <p>所有方法都是 {@code REQUIRES_NEW} 的短事务：解析线程调模型的那几秒
 * **绝不能**持有事务 —— SQLite 的写锁是库级独占的，而 Hikari 池只有 4 个连接。</p>
 */
@Service
public class InboxParseWriter {

    private final InboxRepository inboxRepository;

    public InboxParseWriter(InboxRepository inboxRepository) {
        this.inboxRepository = inboxRepository;
    }

    /** 建「正在解析」的占位条目，返回它的 id。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public long begin(String placeholderRaw, String source, Long attachmentId, String now) {
        long id = inboxRepository.insertWithOrigin(placeholderRaw, source, attachmentId, now);
        inboxRepository.markParseStatus(id, "running", null, now);
        return id;
    }

    /** 解析失败：条目降级成「待整理」，用户仍能在收集箱里看到它并手动补文字。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void fail(long inboxId, String message, String now) {
        inboxRepository.markParseFailed(inboxId, message, now);
    }

    /**
     * 解析成功：把占位条目改写成本条结果，其余条目另建。
     *
     * @param raws     每条对应的原文（长度已由调用方裁到 4000 以内）
     * @param trimmed  原文是否被截断过 —— 截了必须留痕，否则用户以为模型漏读了
     * @return 全部生成/改写的条目 id（第一条是占位条目本身，顺序与入参一致）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public List<Long> finish(long inboxId, String source, Long attachmentId, String now,
                             boolean trimmed, List<ParsedItem> items) {
        List<Long> ids = new ArrayList<Long>();
        for (int index = 0; index < items.size(); index++) {
            ParsedItem item = items.get(index);
            if (index == 0) {
                inboxRepository.updateParsedFromImage(inboxId, item.raw, item.category,
                        item.confidence, item.payload, trimmed, now);
                ids.add(Long.valueOf(inboxId));
            } else {
                long id = inboxRepository.insertFromImage(item.raw, source, item.category,
                        item.confidence, item.payload, now, trimmed, attachmentId);
                ids.add(Long.valueOf(id));
            }
        }
        return ids;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void log(String type, String content, String now) {
        inboxRepository.insertActivity(type, content, now);
    }

    /** 一条待写入的解析结果。字段都是标量，避免把 vision 包的类型泄漏进来。 */
    public static final class ParsedItem {
        final String raw;
        final String category;
        final double confidence;
        final String payload;

        public ParsedItem(String raw, String category, double confidence, String payload) {
            this.raw = raw;
            this.category = category;
            this.confidence = confidence;
            this.payload = payload;
        }
    }
}
