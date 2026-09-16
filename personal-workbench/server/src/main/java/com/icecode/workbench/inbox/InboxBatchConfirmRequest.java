package com.icecode.workbench.inbox;

import java.util.List;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * 一次确认**多条**收录条目。
 *
 * <p>为什么需要它：既有的 {@code POST /inbox/{id}/confirm} 一次只处理一条收录，
 * 而图片解析偏偏是「一张图 → 多条待办」。用户在一张会议通知截图上看到 3 条，
 * 却只能逐条点 3 次「确认生成」，每次都要重新拉一遍列表 —— 更糟的是，
 * 每确认一条，剩下两条的卡片位置都会往上跳一格，操作到第三条时很容易点错。</p>
 *
 * <p>这个接口**不做「一条收录落多条实体」**的事：收录条目与实体仍然一一对应
 * （{@link InboxConfirmRequest} 里那一堆标量字段就是为这个设计的）。
 * 批量指的是「多个收录条目各自确认一次」，在一个事务里完成 ——
 * 于是要么全成功、要么全回滚，不会出现「确认了一半」的中间态。</p>
 */
public class InboxBatchConfirmRequest {

    /**
     * 要确认的条目。上限 50：一次图片解析最多产出 12 条 × 3 张图 = 36 条，留一点余量。
     */
    @NotEmpty(message = "请至少选择一条要确认的条目")
    @Size(max = 50, message = "一次最多确认 50 条")
    @Valid
    private List<Item> items;

    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }

    /**
     * 单条：收录条目 id + 用户核对后的字段。
     *
     * <p>内嵌而不是让前端传「一个 id 列表 + 一套共用字段」—— 图片解析出来的几条
     * 分类/日期/标题各不相同，共用一套字段在语义上就不成立。</p>
     */
    public static class Item {

        /**
         * 收录条目 id。抽成对象而不是 `Map<Long, InboxConfirmRequest>`：
         * JSON 的键只能是字符串，Long→String→Long 的往返会让错误信息里的 id 变得难以辨认。
         */
        @NotNull(message = "条目 id 不能为空")
        private Long inboxId;

        /**
         * 用户核对后的确认内容。复用 {@link InboxConfirmRequest} 而不是另写一份 ——
         * 分类白名单、日期格式、时间格式、标题长度这些约束只应存在一处，
         * 两份 DTO 一漂就会出现「单条能确认、批量确认报参数不正确」。
         */
        @Valid
        @NotNull(message = "缺少该条目的确认内容")
        private InboxConfirmRequest confirm;

        public Long getInboxId() { return inboxId; }
        public void setInboxId(Long inboxId) { this.inboxId = inboxId; }
        public InboxConfirmRequest getConfirm() { return confirm; }
        public void setConfirm(InboxConfirmRequest confirm) { this.confirm = confirm; }
    }
}
