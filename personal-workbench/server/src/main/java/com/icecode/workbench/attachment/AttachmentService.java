package com.icecode.workbench.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.auth.AuthConstants;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.util.TimeUtil;

/**
 * 附件的上传、读取、绑定与删除。
 *
 * <p>落盘位置复用 {@code <data-dir>/attachments} —— 这个目录在
 * {@code WorkbenchDirectoryInitializer} 里早就被创建了，只是一直没人用。</p>
 *
 * <p><b>落盘名一律是 sha256 前 16 位 + 白名单后缀</b>，绝不用用户原文件名拼路径：
 * 原文件名里可能带 {@code /}、{@code ..} 与控制符，直接拼就是目录穿越。
 * 同内容的文件只占一份盘；同名记录可以有多条，删除时靠引用计数决定是否回收文件。</p>
 */
@Service
public class AttachmentService {

    /** 读取文件头用的字节数：够覆盖 jpeg/png/gif/webp 的魔数。 */
    private static final int HEAD_BYTES = 16;

    private static final List<String> VALID_OWNER_TYPES = new ArrayList<String>();

    static {
        VALID_OWNER_TYPES.add("task");
        VALID_OWNER_TYPES.add("schedule_event");
        VALID_OWNER_TYPES.add("favorite");
        VALID_OWNER_TYPES.add("knowledge_note");
        VALID_OWNER_TYPES.add("inbox_item");
    }

    private final AttachmentRepository attachmentRepository;
    private final AppConfigRepository configRepository;
    private final Path dataDir;

    public AttachmentService(AttachmentRepository attachmentRepository,
                             AppConfigRepository configRepository,
                             @Value("${workbench.data-dir}") String dataDir) {
        this.attachmentRepository = attachmentRepository;
        this.configRepository = configRepository;
        this.dataDir = Paths.get(dataDir).toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------ 上传

    public AttachmentVO store(MultipartFile file, String ownerType, Long ownerId) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.ATTACHMENT_INVALID, "没有收到文件内容，请重新选择");
        }
        String resolvedOwnerType = resolveOwnerType(ownerType, ownerId);
        String originalName = safeOriginalName(file.getOriginalFilename());

        byte[] head = readHead(file);
        String sniffedMime = AttachmentPolicy.sniffImageMime(head);
        String declaredMime = file.getContentType();

        // 声明是图片、字节却不是 → 伪造，直接拒。只查后缀或 Content-Type 的实现会放过它。
        if (AttachmentPolicy.isImageMime(declaredMime) && sniffedMime == null) {
            throw new BizException(ErrorCode.ATTACHMENT_INVALID,
                    "「" + originalName + "」不是有效的图片文件（内容与扩展名不符）");
        }

        boolean image = sniffedMime != null;
        String mimeType;
        long maxBytes;

        if (image) {
            mimeType = sniffedMime;
            maxBytes = AttachmentPolicy.MAX_IMAGE_BYTES;
        } else {
            if (!AttachmentPolicy.isAllowedDocExt(originalName)) {
                throw new BizException(ErrorCode.ATTACHMENT_INVALID,
                        "「" + originalName + "」不是支持的文件类型，支持 pdf / word / excel / ppt"
                                + " / md / json / txt / csv，以及 jpg / png / gif / webp 图片");
            }
            mimeType = declaredMime == null || declaredMime.trim().isEmpty()
                    ? "application/octet-stream" : declaredMime.trim();
            maxBytes = AttachmentPolicy.MAX_DOC_BYTES;
        }

        // 先用声明的长度快速失败；真实长度在流式复制时再兜一次（声明值可能被伪造）。
        if (file.getSize() > maxBytes) {
            throw new BizException(ErrorCode.ATTACHMENT_TOO_LARGE,
                    "「" + originalName + "」" + AttachmentPolicy.humanSize(file.getSize())
                            + " 超过 " + AttachmentPolicy.humanSize(maxBytes) + " 上限");
        }

        Path root = attachmentsRoot();
        Path temp;
        String sha256;
        long realSize;
        try {
            Files.createDirectories(root);
            temp = Files.createTempFile(root, "upload-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            realSize = copyWithDigest(file, temp, digest, maxBytes, originalName);
            sha256 = toHex(digest.digest());
        } catch (BizException exception) {
            throw exception;
        } catch (NoSuchAlgorithmException exception) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "服务端不支持 SHA-256，无法保存附件");
        } catch (IOException exception) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "附件写入失败，请检查磁盘空间后重试");
        }

        String extension = image
                ? AttachmentPolicy.extensionForImageMime(mimeType)
                : AttachmentPolicy.extensionOf(originalName);
        if (extension == null) {
            extension = "bin";
        }
        String fileName = sha256.substring(0, 16) + "." + extension;
        Path target = resolveInsideAttachments(fileName);

        boolean deduped = Files.isRegularFile(target);
        try {
            if (deduped) {
                Files.deleteIfExists(temp);   // 同内容已在盘上：不重复占空间
            } else {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            deleteQuietly(temp);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "附件写入失败，请检查磁盘空间后重试");
        }

        Integer[] size = image ? readImageSize(target) : new Integer[]{null, null};
        String now = now();
        int sortOrder = (ownerType == null || ownerId == null)
                ? 0 : attachmentRepository.nextSortOrder(resolvedOwnerType, ownerId.longValue());
        long id = attachmentRepository.insert(resolvedOwnerType, ownerId, fileName, originalName,
                mimeType, realSize, size[0], size[1], sha256, sortOrder, now);
        return toVO(attachmentRepository.findById(id), deduped);
    }

    // ------------------------------------------------------------------ 读取

    public AttachmentRecord requireForRead(long id) {
        AttachmentRecord record = attachmentRepository.findById(id);
        if (record == null) {
            throw new BizException(ErrorCode.ATTACHMENT_NOT_FOUND);
        }
        return record;
    }

    /**
     * 给跨包调用方（图片解析）用的一小份快照。
     *
     * <p>{@code AttachmentRecord} 是包级可见的，解析服务在另一个包里拿不到它。
     * 与其把 record 提升成 public（那等于把整张表的字段形态对外暴露，
     * 以后任何一次加列都会变成一次跨包影响的改动），不如给一个只读的三元组 ——
     * 解析真正需要的只有「哪个文件、叫什么名字、在盘的哪里」。</p>
     */
    public ImageSource openImage(long id) {
        AttachmentRecord record = requireForRead(id);
        if (record.mimeType == null || !record.mimeType.startsWith("image/")) {
            throw new BizException(ErrorCode.ATTACHMENT_INVALID, "附件 " + id + " 不是图片，不能做图片解析");
        }
        return new ImageSource(id, record.originalName, record.fileName,
                record.byteSize, pathOf(record));
    }

    /** 磁盘上一个图片附件的只读视图。 */
    public static final class ImageSource {
        private final long id;
        private final String originalName;
        private final String fileName;
        private final long byteSize;
        private final Path path;

        ImageSource(long id, String originalName, String fileName, long byteSize, Path path) {
            this.id = id;
            this.originalName = originalName;
            this.fileName = fileName;
            this.byteSize = byteSize;
            this.path = path;
        }

        public long getId() { return id; }
        public String getOriginalName() { return originalName; }
        public String getFileName() { return fileName; }
        public long getByteSize() { return byteSize; }
        public Path getPath() { return path; }
        /** 报错文案里用哪个名字：优先用户原文件名。 */
        public String displayName() {
            return originalName == null || originalName.trim().isEmpty() ? fileName : originalName;
        }
    }

    public Path pathOf(AttachmentRecord record) {
        Path path = resolveInsideAttachments(record.fileName);
        if (!Files.isRegularFile(path)) {
            // 说清可能的原因：用户才知道该去查磁盘还是去恢复备份。
            // （备份只含 workbench.db，不含附件目录 —— 见 BackupService。）
            throw new BizException(ErrorCode.ATTACHMENT_NOT_FOUND,
                    "附件文件已丢失，可能被手工删除，或恢复备份时没有一并恢复附件目录");
        }
        return path;
    }

    public List<AttachmentVO> listByOwner(String ownerType, long ownerId) {
        requireOwnerType(ownerType);
        List<AttachmentVO> result = new ArrayList<AttachmentVO>();
        for (AttachmentRecord record : attachmentRepository.findByOwner(ownerType, ownerId)) {
            result.add(toVO(record, false));
        }
        return result;
    }

    /**
     * 批量版：一次查出多个归属的附件，按 ownerId 分组（列表接口专用，避免 N+1）。
     *
     * <p>没有附件的归属**不会出现在返回的 Map 里**，调用方用 {@code get()} 拿到 null 时按空列表处理即可 ——
     * 与其给每个 key 塞一个空 List（内存里凭空多出上百个对象），不如让「没有」就是没有。</p>
     */
    public Map<Long, List<AttachmentVO>> mapByOwner(String ownerType, List<Long> ownerIds) {
        Map<Long, List<AttachmentVO>> result = new HashMap<Long, List<AttachmentVO>>();
        if (ownerIds == null || ownerIds.isEmpty()) {
            return result;
        }
        requireOwnerType(ownerType);
        for (AttachmentRecord record : attachmentRepository.findByOwners(ownerType, ownerIds)) {
            Long key = record.ownerId;
            if (key == null) {
                continue;   // 理论不可达（查询按 owner_id IN 过滤），防御性跳过
            }
            List<AttachmentVO> list = result.get(key);
            if (list == null) {
                list = new ArrayList<AttachmentVO>();
                result.put(key, list);
            }
            list.add(toVO(record, false));
        }
        return result;
    }

    /**
     * 把 {@code fromType/fromId} 名下的附件整体转绑到 {@code toType/toId}。
     *
     * <p>唯一调用方是「收录条目确认生成实体」：附件在上传时挂在收录条目上，
     * 确认成收藏 / 任务 / 日程之后要跟着走 —— 否则收录带附件生成的记录**一个附件都没有**，
     * 而用户在界面上完全看不出来（2026-09-19 修的就是这个）。</p>
     *
     * <p><b>为什么不复制一份给目标：</b>附件是「同一份文件只有一条记录、靠引用计数决定何时回收」，
     * 复制会让同一次上传变成两条记录、删一处另一处还指着同一份盘上文件。
     * 转绑之后收录条目自己不再持有附件 —— 这是对的：它已经归档，附件归实体所有。
     * 收录条目的 {@code source_attachment_id}（解析原图）**不跟着走**，所以
     * 「重新解析」在转绑后仍然读得到原图（该字段只用来定位文件，不参与归属判断）。</p>
     *
     * @return 实际转绑的条数（0 = 原本就没有附件）
     */
    @Transactional(rollbackFor = Exception.class)
    public int transferOwner(String fromType, long fromId, String toType, long toId) {
        requireOwnerType(fromType);
        requireOwnerType(toType);
        List<AttachmentRecord> records = attachmentRepository.findByOwner(fromType, fromId);
        if (records.isEmpty()) {
            return 0;
        }
        String now = now();
        // 从目标归属的现有最大排序位往后接：目标已经有附件时，搬过来的排在后面，
        // 而不是从 0 开始把已有的挤乱。
        int sortOrder = attachmentRepository.nextSortOrder(toType, toId);
        int moved = 0;
        for (AttachmentRecord record : records) {
            moved += attachmentRepository.moveOwner(record.id, fromType, fromId, toType, toId, sortOrder, now);
            sortOrder++;
        }
        return moved;
    }

    private void requireOwnerType(String ownerType) {
        if (!VALID_OWNER_TYPES.contains(ownerType)) {
            throw new BizException(ErrorCode.INVALID_PARAMETER,
                    "ownerType 只能填 task / schedule_event / favorite / knowledge_note / inbox_item");
        }
    }

    // ------------------------------------------------------------------ 绑定与删除

    /**
     * 表单提交时把「已上传但还没归属」的附件绑到新建的实体上。
     *
     * <p>已经绑到别处的附件一律拒绝，而不是悄悄改归属 —— 否则同一个附件会在两个
     * 实体下同时出现，用户完全看不出来发生了什么。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void bindAll(List<Long> attachmentIds, String ownerType, long ownerId) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        if (!VALID_OWNER_TYPES.contains(ownerType)) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "附件归属类型不合法");
        }
        String now = now();
        for (Long attachmentId : attachmentIds) {
            if (attachmentId == null) {
                continue;
            }
            AttachmentRecord record = attachmentRepository.findById(attachmentId.longValue());
            if (record == null) {
                throw new BizException(ErrorCode.ATTACHMENT_NOT_FOUND,
                        "附件 " + attachmentId + " 不存在或已被删除");
            }
            if (record.ownerId != null) {
                boolean sameOwner = ownerType.equals(record.ownerType)
                        && record.ownerId.longValue() == ownerId;
                if (sameOwner) {
                    continue;   // 幂等：重复提交不报错
                }
                throw new BizException(ErrorCode.ATTACHMENT_OCCUPIED,
                        "附件 " + attachmentId + " 已经挂在其他记录上");
            }
            int sortOrder = attachmentRepository.nextSortOrder(ownerType, ownerId);
            attachmentRepository.bindOwner(record.id, ownerType, ownerId, sortOrder, now);
        }
    }

    /**
     * 把某个归属名下的附件整批复制到另一个归属下（2026-09-21，供任务复制使用）。
     *
     * <p><b>为什么不能复用 {@code bindAll}</b>：它落到 {@code bindOwner} 时 WHERE 带着
     * {@code owner_id IS NULL}，只接受「已上传、还没归属」的附件 —— 源附件本来就挂在原记录上，
     * 直接把它们的 id 传进来会被 {@code ATTACHMENT_OCCUPIED} 拒掉。所以这里必须新增行，
     * 而不是改归属；改归属会让原记录**当场丢掉附件**。</p>
     *
     * <p><b>磁盘上不复制文件</b>：落盘文件名是 sha256 前 16 位，同内容天然共用一份。
     * 这不是省空间的小聪明，而是删的时候必须成立的前提 —— 见下。</p>
     *
     * <p><b>共用一个文件是安全的</b>：{@link #delete(long)} 只软删数据库行，不碰磁盘；
     * 物理回收交给孤儿清理，它按 {@code countActiveByFileName} 的引用计数判断
     * （引用没归零就不删）。所以删掉原任务不会让新任务的附件变成打不开的死链。
     * ⚠️ 将来若有人把「删记录的同事顺手删文件」加回 {@link #delete(long)}，
     * 这里立刻变成数据丢失 —— 两个归属共用一个文件的前提就会被打破。</p>
     *
     * @return 实际复制过去的条数（源没有附件时为 0，调用方据此决定提示里要不要提附件）
     */
    @Transactional(rollbackFor = Exception.class)
    public int duplicateForOwner(String fromOwnerType, long fromOwnerId, String toOwnerType, long toOwnerId) {
        requireOwnerType(fromOwnerType);
        requireOwnerType(toOwnerType);
        List<AttachmentRecord> sources = attachmentRepository.findByOwner(fromOwnerType, fromOwnerId);
        if (sources.isEmpty()) {
            return 0;
        }
        String now = now();
        // 排序位在新归属里从 0 重排：直接沿用源的 sort_order 会在目标已有附件时撞号，
        // 而 .att-strip 是按 sort_order 渲染的，撞号会让顺序随机漂。
        int sortOrder = 0;
        for (AttachmentRecord source : sources) {
            attachmentRepository.insert(toOwnerType, Long.valueOf(toOwnerId),
                    source.fileName, source.originalName, source.mimeType, source.byteSize,
                    source.width, source.height, source.sha256, sortOrder++, now);
        }
        return sources.size();
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(long id) {
        AttachmentRecord record = requireForRead(id);
        attachmentRepository.softDelete(id, now());
        // 磁盘文件不在这里物理删除：软删可恢复，且同内容可能还有别条记录在用。
        // 真正的回收交给孤儿清理（引用计数 + 超期），否则恢复记录会指向不存在的文件。
        assertUnused(record);
    }

    /** 实体被软删 / 恢复时，附件跟着走 —— 详情面板不该显示已删记录的图。 */
    @Transactional(rollbackFor = Exception.class)
    public void softDeleteByOwner(String ownerType, long ownerId) {
        attachmentRepository.softDeleteByOwner(ownerType, ownerId, now());
    }

    @Transactional(rollbackFor = Exception.class)
    public void restoreByOwner(String ownerType, long ownerId) {
        attachmentRepository.restoreByOwner(ownerType, ownerId, now());
    }

    // ------------------------------------------------------------------ 内部

    private void assertUnused(AttachmentRecord record) {
        // 目前只做口径留痕：同一个 sha256 可能有多条记录共用一份文件，
        // 物理清理必须等引用归零，这条判断放在清理任务里做。
        attachmentRepository.countActiveByFileName(record.fileName);
    }

    private long copyWithDigest(MultipartFile file, Path temp, MessageDigest digest,
                                long maxBytes, String originalName) throws IOException {
        long total = 0L;
        byte[] buffer = new byte[8192];
        try (InputStream in = file.getInputStream();
             DigestInputStream digestIn = new DigestInputStream(in, digest);
             OutputStream out = Files.newOutputStream(temp)) {
            int read;
            while ((read = digestIn.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new BizException(ErrorCode.ATTACHMENT_TOO_LARGE,
                            "「" + originalName + "」超过 " + AttachmentPolicy.humanSize(maxBytes) + " 上限");
                }
                out.write(buffer, 0, read);
            }
        }
        return total;
    }

    private byte[] readHead(MultipartFile file) {
        byte[] head = new byte[HEAD_BYTES];
        try (InputStream in = file.getInputStream()) {
            int filled = 0;
            while (filled < HEAD_BYTES) {
                int read = in.read(head, filled, HEAD_BYTES - filled);
                if (read < 0) {
                    break;
                }
                filled += read;
            }
            if (filled == HEAD_BYTES) {
                return head;
            }
            byte[] shorter = new byte[filled];
            System.arraycopy(head, 0, shorter, 0, filled);
            return shorter;
        } catch (IOException exception) {
            return new byte[0];
        }
    }

    /** 只读图片头拿宽高，不做全量解码 —— 全解码一张 10MB 的图要几百毫秒。 */
    private Integer[] readImageSize(Path path) {
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) {
                return new Integer[]{null, null};
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return new Integer[]{null, null};
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                return new Integer[]{Integer.valueOf(reader.getWidth(0)),
                        Integer.valueOf(reader.getHeight(0))};
            } finally {
                reader.dispose();
            }
        } catch (Exception exception) {
            return new Integer[]{null, null};
        }
    }

    private Path attachmentsRoot() {
        return dataDir.resolve("attachments").normalize();
    }

    /**
     * 逃逸防护，与 {@code ObsidianVaultService.resolveInsideVault} 同一套做法。
     * 即使文件名是服务端生成的，这里也要再校验一次 —— 防御性检查的价值在于「永远不会被绕过」。
     */
    private Path resolveInsideAttachments(String fileName) {
        Path root = attachmentsRoot();
        Path path = root.resolve(fileName).normalize();
        if (!path.startsWith(root)) {
            throw new BizException(ErrorCode.ATTACHMENT_INVALID, "附件路径不合法");
        }
        return path;
    }

    private String resolveOwnerType(String ownerType, Long ownerId) {
        if (ownerType == null || ownerType.trim().isEmpty()) {
            if (ownerId != null) {
                throw new BizException(ErrorCode.INVALID_PARAMETER,
                        "传了 ownerId 就必须同时传 ownerType");
            }
            return null;   // 待绑定：合法的中间状态
        }
        String value = ownerType.trim();
        if (!VALID_OWNER_TYPES.contains(value)) {
            throw new BizException(ErrorCode.INVALID_PARAMETER,
                    "ownerType 只能填 task / schedule_event / favorite / knowledge_note / inbox_item");
        }
        if (ownerId == null) {
            throw new BizException(ErrorCode.INVALID_PARAMETER,
                    "传了 ownerType 就必须同时传 ownerId");
        }
        return value;
    }

    /** 原文件名只用于展示与报错：去掉目录部分与控制符，并限长。 */
    private String safeOriginalName(String originalName) {
        if (originalName == null || originalName.trim().isEmpty()) {
            return "未命名文件";
        }
        String name = originalName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\r\\n\\t\\u0000-\\u001f]", "").trim();
        if (name.isEmpty()) {
            return "未命名文件";
        }
        return name.length() <= 120 ? name : name.substring(0, 120);
    }

    private AttachmentVO toVO(AttachmentRecord record, boolean deduped) {
        return new AttachmentVO(record.id, "/api/v1/attachments/" + record.id + "/raw",
                record.originalName, record.mimeType, record.byteSize,
                record.width, record.height,
                record.mimeType != null && record.mimeType.startsWith("image/"),
                deduped, record.createdAt);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
        }
        return builder.toString();
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件删不掉不影响主流程：孤儿清理会兜底
        }
    }

    private String now() {
        return TimeUtil.now(timezone());
    }

    private String timezone() {
        String timezone = configRepository.findValue(AuthConstants.CONFIG_TIMEZONE);
        return timezone == null ? "Asia/Shanghai" : timezone;
    }
}
