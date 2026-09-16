package com.icecode.workbench.vision;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.springframework.stereotype.Component;

/**
 * 送模型前的图片降采样。
 *
 * <p><b>为什么必须降采样</b>：base64 会让体积膨胀约 33%。一张 10MB 的截图编码后接近 13MB，
 * 多数视觉模型的单请求上限在 10–20MB 之间，稍大一点就会被服务端直接拒掉，
 * 而错误信息往往只说「请求过大」，用户完全不知道是哪张图的问题。</p>
 *
 * <p>长边压到 {@link #MAX_EDGE} 之后，一张手机截图的体积通常掉到 200–500KB，
 * 对识别精度几乎没有影响（截图里的字本来就不是靠像素量取胜的）。</p>
 */
@Component
public class ImageDownscaler {

    /**
     * 长边上限 1600px。截图/照片在这个分辨率下文字依然清晰，
     * 而更大的图对视觉模型的识别结果没有可测量的提升，只会拖慢上传。
     */
    static final int MAX_EDGE = 1600;

    /** JPEG 质量。0.82 是「肉眼看不出损失、体积掉一半」的常见档位。 */
    private static final float JPEG_QUALITY = 0.82f;

    /**
     * 压到长边 1600px 以内并输出 JPEG 字节。
     *
     * <p>已经足够小的图直接返回原字节？—— 不。GIF / WEBP 有可能不被模型接受，
     * 统一转 JPEG 是最省心的做法；而且这里同时完成了「统一格式」和「限制尺寸」两件事。
     * 透明通道会被合成到白底（JPEG 不支持 alpha）。</p>
     *
     * @param path 磁盘上的原图
     * @return JPEG 字节；任何一步失败都返回 {@code null}，由调用方决定是否回退到原图
     */
    public byte[] toJpegBytes(Path path) {
        try {
            BufferedImage source = ImageIO.read(path.toFile());
            if (source == null) {
                return null;
            }
            BufferedImage target = scale(source);
            return writeJpeg(target);
        } catch (IOException exception) {
            return null;
        } catch (OutOfMemoryError error) {
            // 超大图解码可能吃满堆内存。捕获它是为了让解析功能优雅降级（报「图片太大」），
            // 而不是把整个应用拖崩。
            return null;
        }
    }

    private BufferedImage scale(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longEdge = Math.max(width, height);
        if (longEdge <= MAX_EDGE) {
            return flatten(source, width, height);
        }
        double ratio = (double) MAX_EDGE / longEdge;
        int targetWidth = Math.max(1, (int) Math.round(width * ratio));
        int targetHeight = Math.max(1, (int) Math.round(height * ratio));

        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /** 把带 alpha 的图合成到白底：JPEG 没有透明通道，直接写会得到一块突兀的黑。 */
    private BufferedImage flatten(BufferedImage source, int width, int height) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private byte[] writeJpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /**
     * 兜底：读原文件字节。降采样失败（比如 ImageIO 没有该格式的解码器）时用它 ——
     * 让模型自己去试总比直接报「图片无法处理」好，用户至少还有一次机会。
     */
    public byte[] readRaw(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException exception) {
            return null;
        }
    }
}
