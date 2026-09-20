package com.icecode.workbench.trash;

import java.util.Map;

/**
 * 清空回收站的结果。
 *
 * <p>带上 {@code count} 而不是让前端拿 {@code state.trash.length} 说事：两次请求之间
 * 回收站可能已经变了（另一个标签页恢复了一条），前端手里的数字是**上一次取数**的，
 * 拿它当「删掉了多少」会报出一个没发生过的数。这里回的是服务端**真正删掉的行数**。</p>
 *
 * <p>{@code byType} 按回收站的类型名分组（inbox / task / event / memo / knowledge），
 * 只放真正删到过的类型 —— 全 0 的键会让「共 N 条」旁边多出一排 0，反而看不清。</p>
 */
public class TrashPurgeAllVO {

    private final int count;
    private final Map<String, Integer> byType;

    public TrashPurgeAllVO(int count, Map<String, Integer> byType) {
        this.count = count;
        this.byType = byType;
    }

    public int getCount() { return count; }
    public Map<String, Integer> getByType() { return byType; }
}
