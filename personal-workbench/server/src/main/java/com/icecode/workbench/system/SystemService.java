package com.icecode.workbench.system;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;

@Service
public class SystemService {

    private static final String[] DEMO_TABLES = {"inbox_item", "task", "schedule_event", "favorite", "activity_log"};

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationContext applicationContext;

    public SystemService(JdbcTemplate jdbcTemplate, ApplicationContext applicationContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.applicationContext = applicationContext;
    }

    public void shutdown() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                int code = org.springframework.boot.SpringApplication.exit(applicationContext,
                        new org.springframework.boot.ExitCodeGenerator() {
                            @Override
                            public int getExitCode() { return 0; }
                        });
                System.exit(code);
            }
        });
        thread.setDaemon(false);
        thread.start();
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Integer> clearDemo(boolean confirm) {
        if (!confirm) throw new BizException(ErrorCode.CONFIRM_REQUIRED);
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        int total = 0;

        // SQLite 开启了外键校验（DataSourceConfig#enforceForeignKeys=true）。
        // 用户基于「示例」收件条目整理出的真实数据（任务/收藏/日程/知识/微信消息），
        // 会通过 source_inbox_id 引用示例 inbox_item；直接 DELETE 示例 inbox_item 会触发
        // SQLITE_CONSTRAINT_FOREIGNKEY，使整笔事务回滚、清空失败。先断开这些外键关联
        // （仅解关联、保留真实数据本身），再删除示例行。
        String demoInboxIds = "SELECT id FROM inbox_item WHERE is_demo=1";
        jdbcTemplate.update("UPDATE task SET source_inbox_id=NULL WHERE source_inbox_id IN (" + demoInboxIds + ")");
        jdbcTemplate.update("UPDATE schedule_event SET source_inbox_id=NULL WHERE source_inbox_id IN (" + demoInboxIds + ")");
        jdbcTemplate.update("UPDATE favorite SET source_inbox_id=NULL WHERE source_inbox_id IN (" + demoInboxIds + ")");
        jdbcTemplate.update("UPDATE knowledge_note SET source_inbox_id=NULL WHERE source_inbox_id IN (" + demoInboxIds + ")");
        jdbcTemplate.update("UPDATE wechat_msg_log SET inbox_item_id=NULL WHERE inbox_item_id IN (" + demoInboxIds + ")");

        // 用户把示例任务排入日计划 / 跑了番茄钟，会引用示例 task；同样先解关联再删。
        String demoTaskIds = "SELECT id FROM task WHERE is_demo=1";
        jdbcTemplate.update("UPDATE pomodoro SET task_id=NULL WHERE task_id IN (" + demoTaskIds + ")");
        // daily_plan_item.task_id 为 NOT NULL，无法置空，改为删除指向示例任务的计划项行
        jdbcTemplate.update("DELETE FROM daily_plan_item WHERE task_id IN (" + demoTaskIds + ")");

        for (String table : DEMO_TABLES) {
            int deleted = jdbcTemplate.update("DELETE FROM " + table + " WHERE is_demo=1");
            result.put(table, Integer.valueOf(deleted));
            total += deleted;
        }
        result.put("total", Integer.valueOf(total));
        return result;
    }
}
