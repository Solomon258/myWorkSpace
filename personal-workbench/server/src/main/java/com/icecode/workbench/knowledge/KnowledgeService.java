package com.icecode.workbench.knowledge;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.auth.AuthConstants;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.util.TimeUtil;

@Service
public class KnowledgeService {

    private final KnowledgeRepository knowledgeRepository;
    private final ObsidianVaultService vaultService;
    private final AppConfigRepository configRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KnowledgeService(KnowledgeRepository knowledgeRepository, ObsidianVaultService vaultService,
                            AppConfigRepository configRepository) {
        this.knowledgeRepository = knowledgeRepository;
        this.vaultService = vaultService;
        this.configRepository = configRepository;
    }

    public boolean vaultConfigured() { return vaultService.isConfigured(); }

    public List<KnowledgeNoteVO> list() {
        List<KnowledgeNoteVO> result = new ArrayList<KnowledgeNoteVO>();
        for (KnowledgeNoteRecord record : knowledgeRepository.findActive()) result.add(toVO(record));
        return result;
    }

    /** 整理确认生成知识：先落库（pending），再尝试写入 Vault（synced），失败留 failed 可重试。 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeNoteVO create(Long sourceInboxId, String title, String content) {
        if (!vaultService.isConfigured()) throw new BizException(ErrorCode.VAULT_NOT_CONFIGURED);
        String now = now();
        List<String> tags = autoTags(content);
        String relativePath = vaultService.writeInboxNote(title, content, tags, now);
        String status = relativePath == null ? "failed" : "synced";
        long id = knowledgeRepository.insert(title, content, writeTags(tags), relativePath,
                relativePath == null ? null : relativePath.substring(relativePath.lastIndexOf('/') + 1),
                status, sourceInboxId, now);
        knowledgeRepository.insertActivity(relativePath == null
                ? "收藏知识「" + title + "」（Vault 写入失败，待重试）"
                : "收藏知识「" + title + "」→ Vault " + relativePath, now);
        return get(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public KnowledgeNoteVO retrySync(long id) {
        KnowledgeNoteRecord record = requireNote(id);
        if (!vaultService.isConfigured()) throw new BizException(ErrorCode.VAULT_NOT_CONFIGURED);
        String now = now();
        String relativePath = vaultService.writeInboxNote(record.title, record.content, readTags(record.tags), now);
        if (relativePath == null) {
            knowledgeRepository.updateSync(id, "failed", null, null, now);
            throw new BizException(ErrorCode.BACKUP_FAILED);
        }
        knowledgeRepository.updateSync(id, "synced", relativePath,
                relativePath.substring(relativePath.lastIndexOf('/') + 1), now);
        knowledgeRepository.insertActivity("重试同步知识「" + record.title + "」→ Vault " + relativePath, now);
        return get(id);
    }

    /** 删除仅移除工作台内记录；已写入 Vault 的 .md 属于用户知识库，不动。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(long id) {
        KnowledgeNoteRecord record = requireNote(id);
        knowledgeRepository.softDelete(id, now());
        knowledgeRepository.insertActivity("删除知识记录「" + record.title + "」（Vault 文件保留）", now());
    }

    public KnowledgeNoteVO get(long id) { return toVO(requireNote(id)); }

    private KnowledgeNoteRecord requireNote(long id) {
        KnowledgeNoteRecord record = knowledgeRepository.findById(id);
        if (record == null) throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        return record;
    }

    private KnowledgeNoteVO toVO(KnowledgeNoteRecord record) {
        String obsidianUrl = null;
        if ("synced".equals(record.syncStatus) && record.vaultPath != null) {
            obsidianUrl = "obsidian://open?vault=" + urlEncode(vaultService.vaultName())
                    + "&file=" + urlEncode(record.vaultPath);
        }
        String preview = record.content == null ? ""
                : record.content.length() <= 120 ? record.content : record.content.substring(0, 120) + "…";
        return new KnowledgeNoteVO(record.id, record.title, preview, readTags(record.tags), record.vaultPath,
                record.fileName, record.syncStatus, obsidianUrl, record.createdAt);
    }

    private List<String> autoTags(String content) {
        List<String> tags = new ArrayList<String>();
        String text = content == null ? "" : content;
        if (text.matches("(?s).*https?://\\S+.*")) tags.add("链接收藏");
        if (text.matches("(?s).*(模板|结构|方法论|流程).*")) tags.add("方法模板");
        if (tags.isEmpty()) tags.add("知识");
        return tags;
    }

    private List<String> readTags(String json) {
        if (json == null || json.trim().isEmpty()) return new ArrayList<String>();
        try { return objectMapper.readValue(json, new TypeReference<List<String>>() { }); }
        catch (Exception exception) { return new ArrayList<String>(); }
    }

    private String writeTags(List<String> tags) {
        try { return objectMapper.writeValueAsString(tags); }
        catch (Exception exception) { return "[]"; }
    }

    private String urlEncode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (UnsupportedEncodingException exception) { return value; }
    }

    private String timezone() {
        String timezone = configRepository.findValue(AuthConstants.CONFIG_TIMEZONE);
        return timezone == null ? "Asia/Shanghai" : timezone;
    }

    private String now() { return TimeUtil.now(timezone()); }
}
