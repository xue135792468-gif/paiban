package com.paiban.imports.recipe;

import com.paiban.common.web.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stores each recipe's sample spreadsheet next to the other uploads, one directory
 * per recipe. The sample is what makes “改配方必须先跑样例” real: the server keeps
 * the file and re-runs the mapping on every save, so a client-side “tested” flag is
 * never trusted.
 *
 * <p>Only xlsx/csv up to 5 MB. Files are not served statically — the management API
 * reads them through this component only.
 */
@Component
public class RecipeSampleStorage {

    private static final Logger log = LoggerFactory.getLogger(RecipeSampleStorage.class);

    private static final long MAX_SIZE = 5L * 1024 * 1024;

    private static final Path ROOT = Paths.get("uploads", "import-recipes");

    /** A validated upload that is not on disk yet (the recipe id comes after save). */
    public record PendingSample(byte[] bytes, String fileName, String extension, String sha256) {}

    public PendingSample validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("请选择配方样例文件");
        }
        if (file.getSize() > MAX_SIZE) {
            throw ApiException.badRequest("配方样例文件不能超过 5MB");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String extension = extension(original);
        if (!"xlsx".equals(extension) && !"csv".equals(extension)) {
            throw ApiException.badRequest("配方样例只支持 xlsx / csv 文件");
        }
        try {
            byte[] bytes = file.getBytes();
            String name = StringUtils.hasText(original)
                    ? original
                    : "sample-" + UUID.randomUUID() + "." + extension;
            return new PendingSample(bytes, name, extension, sha256(bytes));
        } catch (IOException ex) {
            // The exception message carries the server's absolute path; log it, never return it.
            log.warn("读取配方样例失败: file={}", original, ex);
            throw ApiException.badRequest("样例文件无法读取，请重新上传");
        }
    }

    /** Writes the sample under {@code uploads/import-recipes/<recipeId>/} and returns metadata. */
    public Map<String, Object> store(Long recipeId, PendingSample sample) {
        try {
            Path directory = ROOT.resolve(String.valueOf(recipeId));
            Files.createDirectories(directory);
            Path target = directory.resolve(UUID.randomUUID() + "." + sample.extension()).toAbsolutePath();
            Files.write(target, sample.bytes());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("path", target.toString());
            metadata.put("fileName", sample.fileName());
            metadata.put("size", sample.bytes().length);
            metadata.put("sha256", sample.sha256());
            return metadata;
        } catch (IOException ex) {
            log.warn("保存配方样例失败: recipeId={}", recipeId, ex);
            throw ApiException.badRequest("样例文件保存失败，请重新上传");
        }
    }

    /**
     * Reads the stored sample. A missing file is a 409, not a 500: the recipe still
     * exists and the user can upload a new sample.
     */
    public byte[] read(Map<String, Object> sample) {
        Object rawPath = sample == null ? null : sample.get("path");
        if (rawPath == null || !StringUtils.hasText(rawPath.toString())) {
            throw ApiException.conflict("样例文件不存在，请重新上传样例");
        }
        Path path = Paths.get(rawPath.toString());
        if (!Files.isRegularFile(path)) {
            throw ApiException.conflict("样例文件不存在，请重新上传样例");
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException ex) {
            throw ApiException.conflict("样例文件不存在，请重新上传样例");
        }
    }

    public String fileNameOf(Map<String, Object> sample) {
        Object name = sample == null ? null : sample.get("fileName");
        if (name != null && StringUtils.hasText(name.toString())) {
            return name.toString();
        }
        Object path = sample == null ? null : sample.get("path");
        if (path == null) {
            return "";
        }
        Path file = Paths.get(path.toString()).getFileName();
        return file == null ? "" : file.toString();
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
