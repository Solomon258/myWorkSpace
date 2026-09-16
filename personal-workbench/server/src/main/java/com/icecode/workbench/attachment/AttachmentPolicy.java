package com.icecode.workbench.attachment;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 附件的类型与大小策略。
 *
 * <p>单独抽出来是为了能对着它写单元测试 —— 白名单、上限、魔数识别这几处
 * 有个共同特点：**写错了不会报错，只会静默放行**，靠人工回归是发现不了的。</p>
 */
final class AttachmentPolicy {

    /** 图片 10MB：还要降采样后送视觉模型，更大的原图对识别没有帮助。 */
    static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    /**
     * 文档 100MB。敢于给这么大的前提是：<b>备份只备份 workbench.db，不包含 attachments 目录</b>
     * （见 BackupService 的 VACUUM INTO），所以大附件不会把备份体积和耗时拖垮。
     */
    static final long MAX_DOC_BYTES = 100L * 1024 * 1024;

    /** 单个实体可挂的解析图片数。一张截图常出 2–3 条，3 张已能产生十来个待确认条目。 */
    static final int MAX_PARSE_IMAGES = 3;

    /** 图片按 MIME 白名单判（这些类型的 MIME 浏览器给得准，且我们要拿它去送模型）。 */
    private static final Set<String> IMAGE_MIME = new HashSet<String>(Arrays.asList(
            "image/jpeg", "image/png", "image/gif", "image/webp"));

    /**
     * 文档按**扩展名**判。md / json / csv 这类浏览器常报 application/octet-stream，
     * 用 MIME 判会把合法文件挡在外面。
     */
    private static final Set<String> DOC_EXT = new HashSet<String>(Arrays.asList(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "md", "json", "txt", "csv"));

    private AttachmentPolicy() {
    }

    static boolean isImageMime(String mimeType) {
        return mimeType != null && IMAGE_MIME.contains(mimeType.trim().toLowerCase(Locale.ROOT));
    }

    static boolean isAllowedDocExt(String fileName) {
        String extension = extensionOf(fileName);
        return extension != null && DOC_EXT.contains(extension);
    }

    static String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 用文件头魔数判图片的**真实**类型。
     *
     * <p>后缀与 MIME 都能伪造：把一个 exe 改名成 .png、或者构造 multipart 时把
     * Content-Type 写成 image/png，只看这两样的实现会照单全收。只有字节骗不了人。</p>
     *
     * @return 识别出的 MIME；认不出来返回 {@code null}
     */
    static String sniffImageMime(byte[] head) {
        if (head == null || head.length < 12) {
            return null;
        }
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if ((head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
            return "image/png";
        }
        if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F') {
            return "image/gif";
        }
        if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /**
     * 落盘后缀一律从**已识别的 MIME** 反推，绝不用用户原文件名 ——
     * 原文件名里可能有 / 、.. 与控制符，直接拼路径就是目录穿越。
     */
    static String extensionForImageMime(String mimeType) {
        if ("image/jpeg".equals(mimeType)) {
            return "jpg";
        }
        if ("image/png".equals(mimeType)) {
            return "png";
        }
        if ("image/gif".equals(mimeType)) {
            return "gif";
        }
        if ("image/webp".equals(mimeType)) {
            return "webp";
        }
        return "bin";
    }

    /** 给用户看的体积描述，如 3.2 MB。报错文案里带着它，用户才知道超了多少。 */
    static String humanSize(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
        if (bytes >= 1024L) {
            return String.format(Locale.ROOT, "%d KB", bytes / 1024L);
        }
        return bytes + " B";
    }
}
