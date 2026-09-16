package com.icecode.workbench.memo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icecode.workbench.attachment.AttachmentService;
import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.auth.AuthConstants;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.util.TextUtil;
import com.icecode.workbench.util.TimeUtil;

@Service
public class MemoService {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    private static final Pattern WORK_PATTERN = Pattern.compile("评审|会议|方案|MR|代码|服务器|需求|Jira|GitLab|项目|客户|业务方|报销|发票|运维|VPN|值班|Obsidian|技术", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SHOPPING_PATTERN = Pattern.compile("买|购|奶粉|菜|超市|快递");
    private static final Pattern COMMUTE_PATTERN = Pattern.compile("班车|通勤|地铁|停车");

    private final MemoRepository memoRepository;
    private final AppConfigRepository configRepository;
    private final AttachmentService attachmentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MemoService(MemoRepository memoRepository, AppConfigRepository configRepository,
                       AttachmentService attachmentService) {
        this.memoRepository = memoRepository;
        this.configRepository = configRepository;
        this.attachmentService = attachmentService;
    }

    public List<MemoVO> list(String grp, String keyword, boolean includeArchived) {
        if (grp != null && !grp.trim().isEmpty() && !"work".equals(grp) && !"life".equals(grp)) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "分组筛选只能填 work / life");
        }
        List<MemoVO> result = new ArrayList<MemoVO>();
        for (MemoRecord record : memoRepository.find(emptyToNull(grp), keyword, includeArchived)) result.add(toVO(record));
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public MemoVO create(MemoCreateRequest request) {
        // 正文可以为空（原来必填）：只写标题的「速查信息」也要能存。两者至少有一项非空，
        // 否则列表里会出现一张既没标题也没内容的空卡片 —— 那种卡片点开也什么都看不到。
        String content = normalizeContent(request.getContent());
        String requestedTitle = request.getTitle() == null ? "" : request.getTitle().trim();
        String title = requestedTitle.isEmpty() ? shortTitle(content) : requestedTitle;
        requireTitleOrContent(title, content);
        String grp = request.getGrp() == null || "auto".equals(request.getGrp()) ? autoGroup(content) : request.getGrp();
        List<String> tags = parseTags(request.getTags());
        if (tags.isEmpty()) tags = autoTags(content, grp);
        String url = extractUrl(content);
        String now = now();
        long id = memoRepository.insert(title, content, url, writeTags(tags), grp, now);
        attachmentService.bindAll(request.getAttachmentIds(), "memo", id);
        memoRepository.insertActivity("memo", grp, "保存备忘「" + title + "」（" + ("work".equals(grp) ? "工作" : "生活") + "）", now);
        return get(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public MemoVO update(long id, MemoUpdateRequest request) {
        MemoRecord memo = requireMemo(id);
        // 标题被清空时回落到正文前 24 字，与创建路径同一套规则（原来这里直接抛
        // 「备忘标题不能为空」，于是「清空标题只留正文」在编辑面板里做不到）。
        if (request.getTitle() != null) memo.title = request.getTitle().trim();
        if (request.getContent() != null) memo.content = normalizeContent(request.getContent());
        if (memo.title.isEmpty()) memo.title = shortTitle(memo.content);
        requireTitleOrContent(memo.title, memo.content);
        if (request.getTags() != null) {
            List<String> tags = parseTags(request.getTags());
            memo.tags = writeTags(tags.isEmpty() ? autoTags(memo.content, memo.grp) : tags);
        }
        if (request.getUrl() != null) memo.url = request.getUrl().trim().isEmpty() ? extractUrl(memo.content) : request.getUrl().trim();
        if (request.getGrp() != null) memo.grp = request.getGrp();
        memo.updatedAt = now();
        memoRepository.update(memo);
        memoRepository.insertActivity("memo", memo.grp, "修改备忘「" + memo.title + "」", memo.updatedAt);
        return get(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public MemoVO togglePin(long id) {
        MemoRecord memo = requireMemo(id);
        memoRepository.setPinned(id, !memo.pinned, now());
        return get(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public MemoVO toggleArchive(long id) {
        MemoRecord memo = requireMemo(id);
        String target = "archived".equals(memo.status) ? "active" : "archived";
        memoRepository.setStatus(id, target, now());
        return get(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(long id) {
        MemoRecord memo = requireMemo(id);
        String now = now();
        memoRepository.softDelete(id, now);
        memoRepository.insertActivity("memo", memo.grp, "删除备忘「" + memo.title + "」", now);
    }

    public MemoVO get(long id) { return toVO(requireMemo(id)); }

    public int countActive(String grp) { return memoRepository.countByGroup(grp, false); }

    private MemoVO toVO(MemoRecord record) {
        return new MemoVO(record.id, record.title, record.content, record.url, readTags(record.tags),
                record.grp, record.pinned, record.status, record.sourceInboxId, record.createdAt,
                record.updatedAt, record.demo);
    }

    private MemoRecord requireMemo(long id) {
        MemoRecord memo = memoRepository.findById(id);
        if (memo == null) throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        return memo;
    }

    /**
     * 分组 / 标签 / 链接推导与标签序列化，都是「备忘自己的规则」。
     *
     * <p>{@code public} 是为了让 {@code TransferService}（任务或日程移至备忘时）复用同一套规则 ——
     * 那里如果另写一份「什么时候算工作、什么时候打家庭采购标签」，两处早晚会漂，
     * 而这种漂移不报错，只是同一条内容从不同入口进来会得到不同的分组。</p>
     */
    public String autoGroup(String text) { return WORK_PATTERN.matcher(text).find() ? "work" : "life"; }

    public List<String> autoTags(String text, String grp) {
        List<String> tags = new ArrayList<String>();
        if (SHOPPING_PATTERN.matcher(text).find()) tags.add("家庭采购");
        if (COMMUTE_PATTERN.matcher(text).find()) tags.add("通勤");
        if (extractUrl(text) != null) tags.add("链接收藏");
        if (tags.isEmpty()) tags.add("work".equals(grp) ? "工作杂项" : "生活");
        return tags;
    }

    public String extractUrl(String text) {
        Matcher matcher = URL_PATTERN.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    List<String> parseTags(String raw) {
        List<String> tags = new ArrayList<String>();
        if (raw == null) return tags;
        for (String part : raw.split("[,，]")) {
            String tag = part.trim();
            if (!tag.isEmpty() && !tags.contains(tag)) tags.add(tag);
        }
        return tags;
    }

    private List<String> readTags(String json) {
        if (json == null || json.trim().isEmpty()) return new ArrayList<String>();
        try { return objectMapper.readValue(json, new TypeReference<List<String>>() { }); }
        catch (Exception exception) { return new ArrayList<String>(); }
    }

    public String writeTags(List<String> tags) {
        try { return objectMapper.writeValueAsString(tags); }
        catch (Exception exception) { return "[]"; }
    }

    /** 只做 trim 与长度校验，**不要求非空**：正文是可空字段，必填规则由 requireTitleOrContent 统一管。 */
    private String normalizeContent(String content) {
        String value = content == null ? "" : content.trim();
        if (value.length() > 4000) throw new BizException(ErrorCode.INVALID_PARAMETER, "备忘内容不能超过 4000 字");
        return value;
    }

    /**
     * 标题与正文至少要有一项非空。这是**跨字段**约束（单字段注解表达不了），
     * 创建与编辑两条路径都必须走这一条 —— 只守一个入口的话，另一个入口就能存出
     * 一张既没标题也没内容的空卡片：列表里它占一格、点开什么都没有，用户只会以为备忘坏了。
     * 报错要写清「写哪一项都行」，而不是笼统的「参数不正确」。
     */
    private void requireTitleOrContent(String title, String content) {
        if (title.isEmpty() && content.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "备忘的标题和正文不能同时为空，至少写一项");
        }
    }

    /**
     * 未填标题时用内容前 24 字兜底。不能拼「…」：这个值会落库成 memo.title 的正式标题，
     * 省略号会永久留在数据里（列表展示的超长截断交给 CSS 处理）。
     */
    private String shortTitle(String content) { return TextUtil.clip(content, 24); }
    private String timezone() { String timezone = configRepository.findValue(AuthConstants.CONFIG_TIMEZONE); return timezone == null ? "Asia/Shanghai" : timezone; }
    private String now() { return TimeUtil.now(timezone()); }
    private String emptyToNull(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
}
