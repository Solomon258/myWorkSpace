package com.icecode.workbench.demo;

import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.icecode.workbench.util.TimeUtil;

@Service
public class DemoDataInitializer {

    private final JdbcTemplate jdbcTemplate;

    public DemoDataInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initializeIfNeeded(String timezone) {
        Integer inboxCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inbox_item WHERE is_demo = 1", Integer.class);
        Integer taskCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task WHERE is_demo = 1", Integer.class);
        Integer eventCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schedule_event WHERE is_demo = 1", Integer.class);
        Integer memoCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM memo WHERE is_demo = 1", Integer.class);
        Integer logCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM activity_log WHERE is_demo = 1", Integer.class);
        if (Integer.valueOf(4).equals(inboxCount) && Integer.valueOf(4).equals(taskCount)
                && Integer.valueOf(3).equals(eventCount) && Integer.valueOf(4).equals(memoCount)
                && Integer.valueOf(3).equals(logCount)) {
            return;
        }

        jdbcTemplate.update("DELETE FROM activity_log WHERE is_demo = 1");
        jdbcTemplate.update("DELETE FROM memo WHERE is_demo = 1");
        jdbcTemplate.update("DELETE FROM schedule_event WHERE is_demo = 1");
        jdbcTemplate.update("DELETE FROM task WHERE is_demo = 1");
        jdbcTemplate.update("DELETE FROM inbox_item WHERE is_demo = 1");

        LocalDate localDate = TimeUtil.localDate(timezone);
        String now = TimeUtil.now(timezone);
        String today = TimeUtil.format(localDate);
        String yesterday = TimeUtil.format(localDate.minusDays(1));
        String tomorrow = TimeUtil.format(localDate.plusDays(1));
        String plusThreeDays = TimeUtil.format(localDate.plusDays(3));

        insertInbox("明天下午3点约业务方对齐Q4需求评审", "wecom", now);
        insertInbox("记得给小李的方案写评审意见，他等了两天了", "wecom", now);
        insertInbox("看到一篇讲团队技术债治理的文章，思路值得记录一下", "web", now);
        insertInbox("充电桩电费发票报销", "web", now);

        jdbcTemplate.update("INSERT INTO task(title, priority, status, due_date, is_deep_work, is_blocking, created_at, updated_at, is_demo) VALUES (?,?,?,?,?,?,?,?,1)",
                "限流方案定稿（明天评审）", "P0", "doing", tomorrow, 1, 0, now, now);
        jdbcTemplate.update("INSERT INTO task(title, priority, status, due_date, is_deep_work, is_blocking, created_at, updated_at, is_demo) VALUES (?,?,?,?,?,?,?,?,1)",
                "回复业务方日配额口径疑问", "P1", "todo", today, 0, 1, now, now);
        jdbcTemplate.update("INSERT INTO task(title, priority, status, due_date, is_deep_work, is_blocking, created_at, updated_at, is_demo) VALUES (?,?,?,?,?,?,?,?,1)",
                "支付网关 MR 代码评审", "P2", "todo", yesterday, 0, 0, now, now);
        jdbcTemplate.update("INSERT INTO task(title, priority, status, due_date, is_deep_work, is_blocking, created_at, updated_at, is_demo) VALUES (?,?,?,?,?,?,?,?,1)",
                "整理本周团队 1on1 纪要", "P3", "todo", plusThreeDays, 0, 0, now, now);

        insertEvent("团队站会", "meeting", today, "09:30", "10:00", now);
        insertEvent("深度块：限流方案设计", "deep_block", today, "10:00", "11:30", now);
        insertEvent("与业务方对齐 Q4 需求", "meeting", today, "14:00", "15:00", now);

        insertMemo("公司班车时间表", "早班 7:50 软件园东门发车；晚班 18:30 / 19:30 两班，B座楼下上车；周五晚班只有 18:30 一班。", null, "[\"通勤\"]", "life", 1, now);
        insertMemo("家里要买的", "奶粉2段一罐、湿巾两包、垃圾袋。周五前买好。", null, "[\"家庭采购\"]", "life", 0, now);
        insertMemo("Obsidian 双链文章", "一篇讲双链笔记如何组织技术方案的文章，值得参考。", "https://example.com/obsidian-links", "[\"链接收藏\"]", "work", 0, now);
        insertMemo("VPN 与运维值班", "公司 VPN 地址 vpn.example.com；运维值班电话按单双周轮换。", null, "[\"运维\"]", "work", 0, now);

        insertLog("inbox", "收录「明天下午3点约业务方对齐Q4需求评审」", now);
        insertLog("pomo", "完成 1 个番茄，专注「限流方案定稿」25 分钟", now);
        insertLog("praise", "完成任务「梳理限流方案初稿」— 漂亮，这事终于落地了", now);
    }

    private void insertInbox(String raw, String source, String now) {
        jdbcTemplate.update("INSERT INTO inbox_item(raw_content, content_type, source, status, created_at, updated_at, is_demo) VALUES (?, 'text', ?, 'pending', ?, ?, 1)",
                raw, source, now, now);
    }

    private void insertEvent(String title, String type, String date, String start, String end, String now) {
        jdbcTemplate.update("INSERT INTO schedule_event(title, event_type, event_date, start_time, end_time, created_at, updated_at, is_demo) VALUES (?,?,?,?,?,?,?,1)",
                title, type, date, start, end, now, now);
    }

    private void insertMemo(String title, String content, String url, String tags, String group, int pinned, String now) {
        jdbcTemplate.update("INSERT INTO memo(title, content, url, tags, grp, pinned, status, created_at, updated_at, is_demo) VALUES (?,?,?,?,?,?,'active',?,?,1)",
                title, content, url, tags, group, pinned, now, now);
    }

    private void insertLog(String type, String content, String now) {
        jdbcTemplate.update("INSERT INTO activity_log(log_type, content, created_at, is_demo) VALUES (?,?,?,1)", type, content, now);
    }
}
