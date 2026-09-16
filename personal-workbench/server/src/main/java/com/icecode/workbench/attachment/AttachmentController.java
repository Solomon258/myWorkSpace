package com.icecode.workbench.attachment;

import java.nio.file.Path;
import java.util.List;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.icecode.workbench.common.ApiResponse;

@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    /**
     * 上传单个文件。前端多选时**循环调用本接口**，而不是一次传多个：
     * 每张图各自有进度、可单独重试，一张失败也不拖垮其余几张。
     *
     * <p>{@code required = false} 是刻意的：漏传 file 时若让 Spring 直接抛，
     * 会落到 500，用户看到「系统暂时不可用」而不是「没有收到文件内容」。</p>
     */
    @PostMapping
    public ApiResponse<AttachmentVO> upload(@RequestParam(value = "file", required = false) MultipartFile file,
                                            @RequestParam(value = "ownerType", required = false) String ownerType,
                                            @RequestParam(value = "ownerId", required = false) Long ownerId) {
        return ApiResponse.success(attachmentService.store(file, ownerType, ownerId));
    }

    @GetMapping
    public ApiResponse<List<AttachmentVO>> list(@RequestParam("ownerType") String ownerType,
                                                @RequestParam("ownerId") long ownerId) {
        return ApiResponse.success(attachmentService.listByOwner(ownerType, ownerId));
    }

    /**
     * 读取原图。必须走流式 —— 100MB 的文件读成 byte[] 再返回会直接进堆。
     *
     * <p>落盘名含 sha256，内容不变则 URL 不变，所以 immutable 长缓存是安全的。</p>
     */
    @GetMapping("/{id}/raw")
    public ResponseEntity<Resource> raw(@PathVariable long id) {
        AttachmentRecord record = attachmentService.requireForRead(id);
        Path path = attachmentService.pathOf(record);
        String tag = record.sha256 == null ? String.valueOf(record.id)
                : record.sha256.substring(0, Math.min(16, record.sha256.length()));
        return ResponseEntity.ok()
                .contentType(mediaType(record.mimeType))
                .contentLength(record.byteSize)
                // 落盘名含 sha256，内容不变则 URL 不变，所以可以 immutable 长缓存。
                // 不用 CacheControl.immutable()：Spring 5.3 的 CacheControl 没有这个方法。
                .header("Cache-Control", "private, max-age=31536000, immutable")
                .eTag("\"" + tag + "\"")
                .body(new FileSystemResource(path));
    }

    /** 软删：写 deleted_at，磁盘文件保留（软删可恢复，且可能有其它记录在共用同一份文件）。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        attachmentService.delete(id);
        return ApiResponse.success(null);
    }

    private MediaType mediaType(String mimeType) {
        if (mimeType == null || mimeType.trim().isEmpty()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (Exception exception) {
            // 库里存了非法 MIME 时不能让整张图取不出来，退成二进制流让浏览器自己嗅探
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
