package com.icecode.workbench.transfer;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * 「移至」请求：把 {@code id} 这条记录从 {@code fromType} 菜单搬到 {@code toType} 菜单。
 *
 * <p>类型取值与回收站（{@code /api/v1/trash}）保持一致：task / event / favorite，
 * 前端拿到的 type 可以直接喂进来，不用再维护第三套命名。</p>
 */
public class TransferRequest {

    @NotBlank(message = "请说明这条记录现在属于哪个菜单")
    @Pattern(regexp = "task|event|favorite", message = "菜单类型只能填 任务(task) / 日程(event) / 收藏(favorite)")
    private String fromType;

    @NotBlank(message = "请说明要移动到哪个菜单")
    @Pattern(regexp = "task|event|favorite", message = "菜单类型只能填 任务(task) / 日程(event) / 收藏(favorite)")
    private String toType;

    @Min(value = 1, message = "记录 id 不正确，无法定位要移动的记录")
    private long id;

    public String getFromType() { return fromType; }
    public void setFromType(String fromType) { this.fromType = fromType; }
    public String getToType() { return toType; }
    public void setToType(String toType) { this.toType = toType; }
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
}
