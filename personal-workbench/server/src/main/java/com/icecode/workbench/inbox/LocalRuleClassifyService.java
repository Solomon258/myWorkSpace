package com.icecode.workbench.inbox;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.icecode.workbench.util.TextUtil;
import com.icecode.workbench.util.TimeUtil;

@Service
public class LocalRuleClassifyService implements ClassifyProvider {

    private static final double AUTO_CONFIRM_THRESHOLD = 0.70;
    /** 与 InboxConfirmRequest.title 的 @Size(max=200) 对齐：预填标题必须能通过校验。 */
    private static final int MAX_TITLE_LENGTH = 200;
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2})\\s*[点时:：]");
    private static final Pattern WEEKDAY = Pattern.compile("周([一二三四五六日天])");
    /**
     * 「每周 X」这类周期安排（「每周三」「每星期三」「每礼拜三」都算）。
     *
     * <p>与 {@link #WEEKDAY} 的区别就在那个「每」字：没有「每」的是**单次**（「周三开会」），
     * 有「每」的是**周期**（「每周三开会」）。所以两条正则必须并存，不能拿一条去顶另一条。</p>
     */
    private static final Pattern WEEKLY = Pattern.compile("每\\s*(?:周|星期|礼拜)\\s*([一二三四五六日天])");
    /** 「每周 X」默认生成连续 4 期（用户 2026-09-13 定的）。 */
    private static final int DEFAULT_REPEAT_WEEKS = 4;

    @Override
    public String name() { return "local"; }

    @Override
    public ClassifySuggestionVO classify(String raw, String timezone) {
        String text = raw == null ? "" : raw.trim();
        String category = "task";
        double confidence = 0.58;
        if (matches(text, "(今天|明天|后天|下周|周[一二三四五六日天]|\\d{1,2}\\s*[点时:：]|会议|约|对齐|评审会|站会)")) {
            category = "schedule";
            confidence = 0.84;
        } else if (matches(text, "(记得|别忘|收藏|报销|发票|带)")) {
            category = "favorite";
            confidence = 0.76;
        } else if (matches(text, "(笔记|总结|学习|心得|资料|文章|思路|值得|文档|教程|模板|方法论|双链)")) {
            // 知识类：确认时写入 Obsidian Vault（未配置 Vault 则确认环节拦截并提示）
            category = "knowledge";
            confidence = 0.72;
        } else if (matches(text, "(完成|处理|回复|确认|提交|评审|定稿|写|整理|跟进|检查)")) {
            confidence = 0.74;
        }
        LocalDate today = TimeUtil.localDate(timezone);
        ClassifyPayload payload = new ClassifyPayload();
        payload.setTitle(shortTitle(text));
        payload.setDue(parseDue(text, today));
        payload.setPriority(parsePriority(text));
        payload.setStart(parseStart(text));
        payload.setEnd(payload.getStart() == null ? null : plusHour(payload.getStart()));
        payload.setEventType(inferEventType(text));
        payload.setRepeatWeeks(parseRepeatWeeks(text));
        return new ClassifySuggestionVO(category, confidence, payload, confidence < AUTO_CONFIRM_THRESHOLD);
    }

    /**
     * 「每周三」「每星期三」「每礼拜三」→ 连续 4 期。
     *
     * <p>只做「每周 X」这一种：规则每多一条，误判面就大一圈，而**误判出来的重复日程比不做更烦人**
     * ——用户得手动删掉多出来的那几期，还得先意识到它们是多余的。所以「双周」「每月 1 号」
     * 「每周一三五」一律不识别，宁可当单次日程让用户自己加。</p>
     */
    private int parseRepeatWeeks(String text) {
        return WEEKLY.matcher(text).find() ? DEFAULT_REPEAT_WEEKS : 1;
    }

    /**
     * 给任意一份整理建议补上「每周 X」这一层判断 —— 无论它来自本地规则还是外部 LLM。
     *
     * <p>为什么要单独暴露这个入口：没配 AI 时走本地规则、配了 AI 时走 {@code LlmClassifyProvider}，
     * 两条路径产出的建议必须一致。只把识别写在 {@link #classify} 里的话，用户一配 AI，
     * 「每周三开周会」的重复识别就**凭空消失**了 —— 而这种差异在界面上完全看不出来，
     * 只是不再重复而已，最容易被当成「功能时好时坏」。</p>
     *
     * <p>只覆盖 {@code repeatWeeks}，其余字段一律不动：LLM 在分类、标题、时间上通常比本地规则准，
     * 没必要把它整份结果丢掉重来。而「每」字是**确定性的文本特征**，不该赌模型这一轮发挥得如何。</p>
     *
     * <p>只对日程生效：给任务 / 收藏填 repeatWeeks 没有意义，还会让确认卡多出一行看不懂的提示。</p>
     */
    public ClassifySuggestionVO applyRepeatHint(String raw, ClassifySuggestionVO suggestion) {
        if (suggestion == null || suggestion.getPayload() == null) return suggestion;
        if (!"schedule".equals(suggestion.getCategory())) return suggestion;
        int weeks = parseRepeatWeeks(raw == null ? "" : raw);
        if (weeks > 1) suggestion.getPayload().setRepeatWeeks(weeks);
        return suggestion;
    }

    private boolean matches(String text, String regex) { return Pattern.compile(regex).matcher(text).find(); }
    /**
     * 预填标题会直接写进 task/schedule_event/favorite/knowledge_note，所以不能带「…」这类省略号，
     * 否则正式数据的标题里会永久留下截断符号。按 API 上限 200 字截断，用户在整理页仍可手改。
     */
    private String shortTitle(String text) { return TextUtil.clip(text, MAX_TITLE_LENGTH); }
    private String parseDue(String text, LocalDate today) {
        if (text.contains("后天")) return TimeUtil.format(today.plusDays(2));
        if (text.contains("明天")) return TimeUtil.format(today.plusDays(1));
        if (text.contains("今天")) return TimeUtil.format(today);
        if (text.contains("下周")) return TimeUtil.format(today.plusDays(7));
        Matcher weekday = WEEKDAY.matcher(text);
        if (weekday.find()) {
            DayOfWeek target = toDayOfWeek(weekday.group(1));
            LocalDate date = today.with(TemporalAdjusters.next(target));
            return TimeUtil.format(date);
        }
        return null;
    }
    private DayOfWeek toDayOfWeek(String value) {
        if ("一".equals(value)) return DayOfWeek.MONDAY;
        if ("二".equals(value)) return DayOfWeek.TUESDAY;
        if ("三".equals(value)) return DayOfWeek.WEDNESDAY;
        if ("四".equals(value)) return DayOfWeek.THURSDAY;
        if ("五".equals(value)) return DayOfWeek.FRIDAY;
        if ("六".equals(value)) return DayOfWeek.SATURDAY;
        return DayOfWeek.SUNDAY;
    }
    private String parsePriority(String text) {
        if (matches(text, "(P0|紧急|马上|评审)")) return "P0";
        if (matches(text, "(重要|阻塞)")) return "P1";
        return "P2";
    }
    private String parseStart(String text) {
        Matcher matcher = CLOCK.matcher(text);
        if (!matcher.find()) return null;
        int hour = Integer.parseInt(matcher.group(1));
        if (hour <= 12 && (text.contains("下午") || text.contains("傍晚") || text.contains("晚上"))) hour += 12;
        if (hour > 23) return null;
        return String.format("%02d:00", hour);
    }
    private String plusHour(String start) {
        int hour = Integer.parseInt(start.substring(0, 2));
        return String.format("%02d:00", Math.min(23, hour + 1));
    }
    private String inferEventType(String text) {
        if (text.contains("深度") || text.contains("专注")) return "deep_block";
        if (matches(text, "(会议|约|对齐|评审会|站会)")) return "meeting";
        return "other";
    }
}
