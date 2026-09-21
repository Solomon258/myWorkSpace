package com.icecode.workbench.settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import javax.servlet.http.HttpSession;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.auth.AuthConstants;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.util.TimeUtil;

@Service
public class SettingsService {

    public static final String KEY_AI_ENABLED = "ai.enabled";
    public static final String KEY_AI_BASE_URL = "ai.base_url";
    public static final String KEY_AI_MODEL = "ai.model";
    public static final String KEY_AI_API_KEY = "ai.api_key";
    /**
     * 视觉模型名，与 {@link #KEY_AI_MODEL} 分开配置。
     *
     * <p>同一个服务地址下文本模型与视觉模型通常不同名（如 deepseek-chat 与 gpt-4o-mini），
     * 共用一个键会让「配好文本整理」和「配好图片解析」互相覆盖。</p>
     */
    public static final String KEY_AI_VISION_MODEL = "ai.vision_model";
    /**
     * 「仅本地解析」开关。
     *
     * <p>开启后图片**不会**发给外部模型，解析入口直接报「已关闭云端解析」。
     * 有些截图（工资条、合同、客户名单）用户就是不希望出本机，
     * 而这个决定必须由用户自己下，不能由我们默认替他决定。</p>
     */
    public static final String KEY_AI_VISION_LOCAL_ONLY = "ai.vision_local_only";
    public static final String KEY_OBSIDIAN_VAULT = "obsidian.vault_path";
    /**
     * 得到的登录 Cookie（浏览器里复制的那一整串）。
     *
     * <p>为什么需要它：得到的分享页对<b>匿名</b>请求只下发试读正文（2026-09-20 实测 1023 字，
     * 约为全文的 20%），带登录态请求才会对已购 / 已领取的文章下发全文。
     * 详见 {@code DedaoShareParser} 的类注释与 {@code ArticleCollectService.trialOnlyMessage}。
     *
     * <p>与 API Key 同级对待：只落在本机 SQLite，接口不回传明文，只回「配没配」。
     */
    public static final String KEY_DEDAO_COOKIE = "dedao.cookie";

    private final AppConfigRepository configRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public SettingsService(AppConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    public SettingsVO get() {
        Map<String, String> config = configRepository.findValues(
                AuthConstants.CONFIG_USERNAME,
                KEY_AI_ENABLED, KEY_AI_BASE_URL, KEY_AI_MODEL, KEY_AI_API_KEY,
                KEY_AI_VISION_MODEL, KEY_AI_VISION_LOCAL_ONLY, KEY_OBSIDIAN_VAULT,
                KEY_DEDAO_COOKIE);
        String apiKey = config.get(KEY_AI_API_KEY);
        String dedaoCookie = config.get(KEY_DEDAO_COOKIE);
        return new SettingsVO(
                config.get(AuthConstants.CONFIG_USERNAME),
                Boolean.parseBoolean(config.get(KEY_AI_ENABLED)),
                emptyToNull(config.get(KEY_AI_BASE_URL)),
                emptyToNull(config.get(KEY_AI_MODEL)),
                mask(apiKey),
                apiKey != null && !apiKey.isEmpty(),
                emptyToNull(config.get(KEY_OBSIDIAN_VAULT)),
                emptyToNull(config.get(KEY_AI_VISION_MODEL)),
                Boolean.parseBoolean(config.get(KEY_AI_VISION_LOCAL_ONLY)),
                dedaoCookie != null && !dedaoCookie.trim().isEmpty());
    }

    /**
     * 保存得到登录 Cookie。传空串表示清除（用户换账号 / 想撤掉凭据时用）。
     *
     * <p>做一道「看起来不像 Cookie」的轻校验：用户很容易把地址栏、User-Agent 甚至
     * 整页 HTML 复制进来，那时如果默默存下，下一次收藏失败的原因会指向「Cookie 过期」，
     * 而真正的问题是复制错了东西 —— 在保存这一刻拦住能省一整轮排查。
     */
    @Transactional(rollbackFor = Exception.class)
    public SettingsVO saveDedaoCookie(DedaoCookieRequest request) {
        String cookie = normalizeCookie(request.getCookie());
        if (cookie != null && cookie.indexOf('=') < 0) {
            throw new BizException(ErrorCode.INVALID_PARAMETER,
                    "这段文本看起来不是浏览器 Cookie：它应当是 name=value; name2=value2 的形态。"
                            + "请在浏览器里登录得到后，按 F12 打开开发者工具 → 网络 → 点任一 dedao.cn 请求 → "
                            + "在「请求标头」里找到 Cookie 那一行，复制它的值。");
        }
        configRepository.save(KEY_DEDAO_COOKIE, cookie == null ? "" : cookie, now());
        return get();
    }

    /**
     * 归一化用户粘贴的内容：去掉首尾空白，并把整行请求头一起复制过来的情况兜住。
     *
     * <p>从开发者工具复制时，很容易连 {@code Cookie: } 这个前缀一起带走；
     * 原样塞进请求头会变成 {@code Cookie: Cookie: a=b}，服务端认不出来，而且失败得很安静。
     */
    private String normalizeCookie(String raw) {
        String value = trimToNull(raw);
        if (value == null) return null;
        if (value.regionMatches(true, 0, "cookie:", 0, 7)) value = value.substring(7).trim();
        // CSV / 表格里粘贴常常带一层引号
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value.isEmpty() ? null : value;
    }

    @Transactional(rollbackFor = Exception.class)
    public SettingsVO updateProfile(ProfileUpdateRequest request, HttpSession session) {
        String now = now();
        boolean changed = false;
        if (request.getUsername() != null && !request.getUsername().trim().isEmpty()) {
            String username = request.getUsername().trim();
            if (username.length() < 2 || username.length() > 32) throw new BizException(ErrorCode.INVALID_PARAMETER, "用户名长度需在 2–32 个字符之间");
            configRepository.save(AuthConstants.CONFIG_USERNAME, username, now);
            if (session != null) session.setAttribute(AuthConstants.SESSION_USER, username);
            changed = true;
        }
        if (request.getNewPassword() != null && !request.getNewPassword().isEmpty()) {
            String currentHash = configRepository.findValue(AuthConstants.CONFIG_PASSWORD_HASH);
            if (request.getCurrentPassword() == null || currentHash == null
                    || !passwordEncoder.matches(request.getCurrentPassword(), currentHash)) {
                throw new BizException(ErrorCode.INVALID_PASSWORD);
            }
            String newPassword = request.getNewPassword();
            if (newPassword.length() < 4
                    || newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new BizException(ErrorCode.INVALID_PARAMETER, "新密码至少 4 位，且 UTF-8 编码后不能超过 72 字节");
            }
            configRepository.save(AuthConstants.CONFIG_PASSWORD_HASH, passwordEncoder.encode(newPassword), now);
            changed = true;
        }
        if (!changed) throw new BizException(ErrorCode.INVALID_PARAMETER, "没有检测到任何修改：用户名或新密码至少填一项");
        return get();
    }

    @Transactional(rollbackFor = Exception.class)
    public SettingsVO saveAi(AiSettingsRequest request) {
        String baseUrl = trimToNull(request.getBaseUrl());
        String model = trimToNull(request.getModel());
        if (request.isEnabled() && baseUrl == null) throw new BizException(ErrorCode.INVALID_PARAMETER, "要启用 AI 整理，必须先填写服务地址");
        // 图片解析与「仅本地」是互斥的：用户开了「仅本地」却又去点解析，
        // 会得到一个「明明是开的却什么都不做」的困惑。所以在保存这一刻就说清楚 ——
        // 这里不强行关掉开关（用户可能只是暂时不解析），只把矛盾指出来。
        String visionModel = trimToNull(request.getVisionModel());
        if (visionModel != null && request.isVisionLocalOnly()) {
            throw new BizException(ErrorCode.INVALID_PARAMETER,
                    "「仅本地解析」与「视觉模型」不能同时启用：只用一个就不能调用云端模型。"
                            + "要保留图片解析请先关掉「仅本地」，要保护隐私请清空视觉模型名");
        }
        String now = now();
        configRepository.save(KEY_AI_ENABLED, String.valueOf(request.isEnabled()), now);
        configRepository.save(KEY_AI_BASE_URL, baseUrl == null ? "" : baseUrl, now);
        configRepository.save(KEY_AI_MODEL, model == null ? "" : model, now);
        configRepository.save(KEY_AI_VISION_MODEL, visionModel == null ? "" : visionModel, now);
        configRepository.save(KEY_AI_VISION_LOCAL_ONLY, String.valueOf(request.isVisionLocalOnly()), now);
        String apiKey = trimToNull(request.getApiKey());
        if (apiKey != null) configRepository.save(KEY_AI_API_KEY, apiKey, now);
        return get();
    }

    @Transactional(rollbackFor = Exception.class)
    public SettingsVO saveObsidian(ObsidianSettingsRequest request) {
        String vaultPath = trimToNull(request.getVaultPath());
        // 保存即校验路径是否为真实存在的目录：避免「明明填了路径却仍提示未配置」的静默失败，
        // 让用户在点保存时立刻得到明确反馈（路径拼错 / 指向文件而非文件夹 / 目录不存在）。
        if (vaultPath != null) {
            Path vault = Paths.get(vaultPath).toAbsolutePath().normalize();
            if (!Files.isDirectory(vault)) {
                String message = "路径无效，应用进程看不到这个目录：" + vaultPath;
                if (vaultPath.matches("(?i)^[a-z]:[\\\\/].*") || vaultPath.contains("\\")) {
                    // Windows 盘符路径：在容器里是「不可见」，在原生 Windows 上多半只是路径写错。
                    // 用条件式表述，保证两种运行环境下提示都成立。
                    message += "。请确认该路径确实存在且是文件夹；若工作台运行在 Docker 容器（Linux）内，"
                            + "容器看不到 Windows 盘符，需要在 docker-compose.dev.yml 中把 Vault 挂载进容器，"
                            + "并填写容器内的挂载路径（如 /workspace/vault）。";
                } else {
                    message += "。请确认它确实存在，并且是文件夹而不是文件。";
                }
                throw new BizException(ErrorCode.INVALID_PARAMETER, message);
            }
        }
        configRepository.save(KEY_OBSIDIAN_VAULT, vaultPath == null ? "" : vaultPath, now());
        return get();
    }

    private String mask(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) return "";
        if (apiKey.length() <= 8) return "****";
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    private String now() {
        String timezone = configRepository.findValue(AuthConstants.CONFIG_TIMEZONE);
        return TimeUtil.now(timezone == null ? "Asia/Shanghai" : timezone);
    }

    private String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }
    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
