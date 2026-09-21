package com.icecode.workbench.favorite;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icecode.workbench.attachment.AttachmentService;
import com.icecode.workbench.attachment.AttachmentVO;
import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.auth.AuthConstants;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.util.TextUtil;
import com.icecode.workbench.util.TimeUtil;

@Service
public class FavoriteService {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    private static final Pattern WORK_PATTERN = Pattern.compile("评审|会议|方案|MR|代码|服务器|需求|Jira|GitLab|项目|客户|业务方|报销|发票|运维|VPN|值班|Obsidian|技术", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SHOPPING_PATTERN = Pattern.compile("买|购|奶粉|菜|超市|快递");
    private static final Pattern COMMUTE_PATTERN = Pattern.compile("班车|通勤|地铁|停车");

    private final FavoriteRepository favoriteRepository;
    private final AppConfigRepository configRepository;
    private final AttachmentService attachmentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FavoriteService(FavoriteRepository favoriteRepository, AppConfigRepository configRepository,
                       AttachmentService attachmentService) {
        this.favoriteRepository = favoriteRepository;
        this.configRepository = configRepository;
        this.attachmentService = attachmentService;
    }

    public List<FavoriteVO> list(String grp, String keyword, boolean includeArchived) {
        if (grp != null && !grp.trim().isEmpty() && !"work".equals(grp) && !"life".equals(grp)) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "分组筛选只能填 work / life");
        }
        List<FavoriteRecord> records = favoriteRepository.find(emptyToNull(grp), keyword, includeArchived);
        // 附件走**一次批量查询**再按 ownerId 分组，不是每条 favorite 各查一次（N+1，见 AttachmentService）。
        Map<Long, List<AttachmentVO>> attachments = attachmentService.mapByOwner("favorite", idsOf(records));
        List<FavoriteVO> result = new ArrayList<FavoriteVO>();
        for (FavoriteRecord record : records) result.add(toVO(record, attachments.get(Long.valueOf(record.id))));
        return result;
    }

    private List<Long> idsOf(List<FavoriteRecord> records) {
        List<Long> ids = new ArrayList<Long>(records.size());
        for (FavoriteRecord record : records) ids.add(Long.valueOf(record.id));
        return ids;
    }

    @Transactional(rollbackFor = Exception.class)
    public FavoriteVO create(FavoriteCreateRequest request) {
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
        long id = favoriteRepository.insert(title, content, url, writeTags(tags), grp, now);
        attachmentService.bindAll(request.getAttachmentIds(), "favorite", id);
        favoriteRepository.insertActivity("favorite", grp, "保存收藏「" + title + "」（" + ("work".equals(grp) ? "工作" : "生活") + "）", now);
        return get(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public FavoriteVO update(long id, FavoriteUpdateRequest request) {
        FavoriteRecord favorite = requireFavorite(id);
        // 标题被清空时回落到正文前 24 字，与创建路径同一套规则（原来这里直接抛
        // 「收藏标题不能为空」，于是「清空标题只留正文」在编辑面板里做不到）。
        if (request.getTitle() != null) favorite.title = request.getTitle().trim();
        if (request.getContent() != null) favorite.content = normalizeContent(request.getContent());
        if (favorite.title.isEmpty()) favorite.title = shortTitle(favorite.content);
        requireTitleOrContent(favorite.title, favorite.content);
        if (request.getTags() != null) {
            List<String> tags = parseTags(request.getTags());
            favorite.tags = writeTags(tags.isEmpty() ? autoTags(favorite.content, favorite.grp) : tags);
        }
        if (request.getUrl() != null) favorite.url = request.getUrl().trim().isEmpty() ? extractUrl(favorite.content) : request.getUrl().trim();
        if (request.getGrp() != null) favorite.grp = request.getGrp();
        favorite.updatedAt = now();
        favoriteRepository.update(favorite);
        favoriteRepository.insertActivity("favorite", favorite.grp, "修改收藏「" + favorite.title + "」", favorite.updatedAt);
        return get(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public FavoriteVO togglePin(long id) {
        FavoriteRecord favorite = requireFavorite(id);
        favoriteRepository.setPinned(id, !favorite.pinned, now());
        return get(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public FavoriteVO toggleArchive(long id) {
        FavoriteRecord favorite = requireFavorite(id);
        String target = "archived".equals(favorite.status) ? "active" : "archived";
        favoriteRepository.setStatus(id, target, now());
        return get(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(long id) {
        FavoriteRecord favorite = requireFavorite(id);
        String now = now();
        favoriteRepository.softDelete(id, now);
        favoriteRepository.insertActivity("favorite", favorite.grp, "删除收藏「" + favorite.title + "」", now);
    }

    public FavoriteVO get(long id) { return toVO(requireFavorite(id)); }

    public int countActive(String grp) { return favoriteRepository.countByGroup(grp, false); }

    /** 单条路径：附件按这一条查一次就够（列表路径必须走 {@code mapByOwner} 批量，见 list）。 */
    private FavoriteVO toVO(FavoriteRecord record) {
        return toVO(record, attachmentService.listByOwner("favorite", record.id));
    }

    private FavoriteVO toVO(FavoriteRecord record, List<AttachmentVO> attachments) {
        return new FavoriteVO(record.id, record.title, record.content, record.url, readTags(record.tags),
                record.grp, record.pinned, record.status, record.sourceInboxId, record.createdAt,
                record.updatedAt, record.demo,
                attachments == null ? new ArrayList<AttachmentVO>() : attachments);
    }

    private FavoriteRecord requireFavorite(long id) {
        FavoriteRecord favorite = favoriteRepository.findById(id);
        if (favorite == null) throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        return favorite;
    }

    /**
     * 分组 / 标签 / 链接推导与标签序列化，都是「收藏自己的规则」。
     *
     * <p>{@code public} 是为了让 {@code TransferService}（任务或日程移至收藏时）复用同一套规则 ——
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
        if (value.length() > 4000) throw new BizException(ErrorCode.INVALID_PARAMETER, "收藏内容不能超过 4000 字");
        return value;
    }

    /**
     * 标题与正文至少要有一项非空。这是**跨字段**约束（单字段注解表达不了），
     * 创建与编辑两条路径都必须走这一条 —— 只守一个入口的话，另一个入口就能存出
     * 一张既没标题也没内容的空卡片：列表里它占一格、点开什么都没有，用户只会以为收藏坏了。
     * 报错要写清「写哪一项都行」，而不是笼统的「参数不正确」。
     */
    private void requireTitleOrContent(String title, String content) {
        if (title.isEmpty() && content.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "收藏的标题和正文不能同时为空，至少写一项");
        }
    }

    /**
     * 未填标题时用内容前 24 字兜底。不能拼「…」：这个值会落库成 favorite.title 的正式标题，
     * 省略号会永久留在数据里（列表展示的超长截断交给 CSS 处理）。
     */
    private String shortTitle(String content) { return TextUtil.clip(content, 24); }
    private String timezone() { String timezone = configRepository.findValue(AuthConstants.CONFIG_TIMEZONE); return timezone == null ? "Asia/Shanghai" : timezone; }
    private String now() { return TimeUtil.now(timezone()); }
    private String emptyToNull(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
}
