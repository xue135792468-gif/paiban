package com.paiban.imports.manual;

import com.paiban.ai.imports.CsvTableReader;
import com.paiban.common.excel.ExcelTableReader;
import com.paiban.common.web.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploaded file to a list of raw grids. The manual path is deliberately dumb here: no
 * AI decides which sheet matters, so every sheet is read and the caller (inspect or the
 * chosen mapping) picks one. CSV has a single synthetic sheet named {@code CSV} so a
 * recipe's fingerprint stays stable when the file name changes.
 */
public final class SourceSheets {

    public static final String CSV_SHEET = "CSV";

    /** One sheet exactly as laid out, header row included. */
    public record Sheet(String name, List<List<String>> rows) {

        public boolean empty() {
            return rows == null || rows.isEmpty();
        }

        public int columnCount() {
            int widest = 0;
            if (rows != null) {
                for (List<String> row : rows) {
                    widest = Math.max(widest, row == null ? 0 : row.size());
                }
            }
            return widest;
        }

        public List<String> row(int index) {
            if (rows == null || index < 0 || index >= rows.size() || rows.get(index) == null) {
                return List.of();
            }
            return rows.get(index);
        }
    }

    private SourceSheets() {}

    public static List<Sheet> read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("请选择要导入的 Excel 或 CSV 文件");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        try {
            return read(file.getBytes(), name, file.getContentType());
        } catch (IOException ex) {
            throw ApiException.badRequest("读取文件失败：" + ex.getMessage());
        }
    }

    /**
     * Same parsing from bytes, so a recipe's stored sample can be re-tested without a
     * multipart upload. CSV detection uses the stored file name; xlsx always opens
     * by content.
     */
    public static List<Sheet> read(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) {
            throw ApiException.badRequest("请选择要导入的 Excel 或 CSV 文件");
        }
        return read(bytes, fileName == null ? "" : fileName, null);
    }

    private static List<Sheet> read(byte[] bytes, String name, String contentType) {
        try {
            if (isCsv(name, contentType)) {
                return List.of(new Sheet(CSV_SHEET, CsvTableReader.readGrid(bytes)));
            }
            return ExcelTableReader.readAllGrids(new ByteArrayInputStream(bytes)).stream()
                    .map(grid -> new Sheet(grid.sheetName(),
                            grid.rows() == null ? List.of() : grid.rows()))
                    .toList();
        } catch (IOException ex) {
            throw ApiException.badRequest("读取文件失败：" + ex.getMessage());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest(ex.getMessage());
        }
    }

    /** The named sheet, or the first non-empty one when nobody named a sheet. */
    public static Sheet select(List<Sheet> sheets, String sheetName) {
        if (sheets == null || sheets.isEmpty()) {
            throw ApiException.badRequest("文件里没有可读取的工作表");
        }
        if (StringUtils.hasText(sheetName)) {
            return sheets.stream()
                    .filter(sheet -> sheet.name().equals(sheetName))
                    .findFirst()
                    .orElseThrow(() -> ApiException.badRequest("找不到工作表：" + sheetName));
        }
        return sheets.stream()
                .filter(sheet -> !sheet.empty())
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("工作表里没有数据"));
    }

    private static boolean isCsv(String fileName, String contentType) {
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            return true;
        }
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("csv");
    }
}
