package com.paiban.ai.imports;

import com.paiban.common.web.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stores uploaded import sources outside the repository under {@code uploads/ai-import}.
 * Images are validated by file signature; spreadsheets by extension (POI/CSV parsing
 * then decides whether the content is usable).
 */
@Component
public class UploadStorage {

    private static final Path ROOT = Paths.get("uploads", "ai-import");
    private static final List<String> EXCEL_EXTENSIONS = List.of("xlsx", "xls", "csv");

    public record StoredFile(String path, String fileName, long size, String contentType) {}

    public StoredFile storeSpreadsheet(MultipartFile file) {
        requireFile(file);
        String extension = extension(file.getOriginalFilename());
        if (!EXCEL_EXTENSIONS.contains(extension)) {
            throw ApiException.badRequest("只支持 xlsx / xls / csv 文件");
        }
        return write(file, extension, contentType(extension));
    }

    public StoredFile storeImage(MultipartFile file) {
        requireFile(file);
        try {
            byte[] bytes = file.getBytes();
            ImageType type = imageType(bytes);
            if (type == null) {
                throw ApiException.badRequest("只支持 PNG / JPG / WebP 图片");
            }
            return write(bytes, file.getOriginalFilename(), type.extension(), type.contentType());
        } catch (IOException ex) {
            throw ApiException.badRequest("读取图片失败: " + ex.getMessage());
        }
    }

    public byte[] read(String path) {
        if (path == null || path.isBlank()) {
            throw ApiException.notFound("该导入没有原始文件");
        }
        try {
            return Files.readAllBytes(Paths.get(path));
        } catch (IOException ex) {
            throw ApiException.notFound("原始文件已不存在");
        }
    }

    private void requireFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("请选择要上传的文件");
        }
        if (file.getSize() > 20L * 1024 * 1024) {
            throw ApiException.badRequest("文件不能超过 20MB");
        }
    }

    private StoredFile write(MultipartFile file, String extension, String contentType) {
        try {
            return write(file.getBytes(), file.getOriginalFilename(), extension, contentType);
        } catch (IOException ex) {
            throw ApiException.badRequest("读取上传文件失败: " + ex.getMessage());
        }
    }

    private StoredFile write(byte[] bytes, String originalName, String extension, String contentType) {
        try {
            Files.createDirectories(ROOT);
            String storedName = UUID.randomUUID() + "." + extension;
            Path target = ROOT.resolve(storedName).toAbsolutePath();
            Files.write(target, bytes);
            return new StoredFile(
                    target.toString(),
                    originalName == null || originalName.isBlank() ? storedName : originalName,
                    bytes.length,
                    contentType);
        } catch (IOException ex) {
            throw ApiException.badRequest("保存上传文件失败: " + ex.getMessage());
        }
    }

    private String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String contentType(String extension) {
        return switch (extension) {
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls" -> "application/vnd.ms-excel";
            case "csv" -> "text/csv";
            default -> "application/octet-stream";
        };
    }

    private ImageType imageType(byte[] bytes) {
        if (bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return new ImageType("png", "image/png");
        }
        if (bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return new ImageType("jpg", "image/jpeg");
        }
        if (bytes.length > 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return new ImageType("webp", "image/webp");
        }
        return null;
    }

    private record ImageType(String extension, String contentType) {}
}
