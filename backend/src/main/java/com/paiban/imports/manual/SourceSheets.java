package com.paiban.imports.manual;

import com.paiban.ai.imports.CsvTableReader;
import com.paiban.common.excel.ExcelTableReader;
import com.paiban.common.web.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploaded file to a list of raw grids. The manual path is deliberately dumb here: no
 * AI decides which sheet matters, so every sheet is read and the caller (inspect or the
 * chosen mapping) picks one. CSV has a single synthetic sheet named {@code CSV} so a
 * recipe's fingerprint stays stable when the file name changes.
 *
 * <p>The upload is checked here rather than by {@code UploadStorage}: the manual path
 * never persists the file, but it must still refuse the same shapes — only
 * xlsx/xls/csv, at most 20MB, at most {@value #MAX_SHEETS} sheets and
 * {@value #MAX_ROWS_PER_SHEET} rows per sheet. Without those caps a stray 500k-row CSV
 * would be parsed into memory before anyone could tell the user what went wrong.
 */
public final class SourceSheets {

    private static final Logger UPLOAD_LOG = LoggerFactory.getLogger(SourceSheets.class);

    public static final String CSV_SHEET = "CSV";

    /** Same ceiling as {@code UploadStorage}: the manual import is a spreadsheet upload. */
    public static final long MAX_BYTES = 20L * 1024 * 1024;

    /** A school workbook has a handful of sheets; more than this is a wrong file. */
    public static final int MAX_SHEETS = 20;

    /** Matches {@code ExcelTableReader}'s row cap, so CSV and xlsx refuse at the same size. */
    public static final int MAX_ROWS_PER_SHEET = 5000;

    private static final List<String> EXTENSIONS = List.of("xlsx", "xls", "csv");

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
        requireSpreadsheetUpload(file.getSize(), name);
        try {
            return read(file.getBytes(), name, file.getContentType());
        } catch (IOException ex) {
            // The reader's own message may carry paths or parser internals; log it instead.
            UPLOAD_LOG.warn("读取手动导入文件失败: {}", name, ex);
            throw ApiException.badRequest("读取文件失败，请确认文件没有损坏");
        }
    }

    /** The upload guard: extension, size, sheet count and rows per sheet. */
    private static void requireSpreadsheetUpload(long size, String fileName) {
        if (size > MAX_BYTES) {
            throw ApiException.badRequest(
                    "文件不能超过 20MB，请精简后再导入（当前 " + (size / 1024 / 1024) + "MB）");
        }
        String extension = extension(fileName);
        if (!EXTENSIONS.contains(extension)) {
            throw ApiException.badRequest("只支持 xlsx / xls / csv 文件");
        }
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Same parsing from bytes, so a recipe's stored sample can be re-tested without a
     * multipart upload. CSV detection uses the stored file name; xlsx always opens
     * by content. The sheet/row caps still apply: a stored sample is parsed by the same
     * code, so a broken sample must fail with the same message, not a different one.
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
                return List.of(new Sheet(CSV_SHEET, CsvTableReader.readGrid(bytes, MAX_ROWS_PER_SHEET)));
            }
            List<Sheet> sheets = ExcelTableReader.readAllGrids(new ByteArrayInputStream(bytes)).stream()
                    .map(grid -> new Sheet(grid.sheetName(),
                            grid.rows() == null ? List.of() : grid.rows()))
                    .toList();
            requireWorkbookShape(sheets);
            return sheets;
        } catch (IOException ex) {
            UPLOAD_LOG.warn("解析手动导入文件失败: {}", name, ex);
            throw ApiException.badRequest("无法解析该文件，请确认它是有效的 Excel 或 CSV");
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest(ex.getMessage());
        }
    }

    private static void requireWorkbookShape(List<Sheet> sheets) {
        if (sheets.size() > MAX_SHEETS) {
            throw new IllegalArgumentException(
                    "工作簿有 " + sheets.size() + " 个工作表，超过 " + MAX_SHEETS
                            + " 个上限，请拆分后再导入");
        }
        for (Sheet sheet : sheets) {
            if (sheet.rows() != null && sheet.rows().size() > MAX_ROWS_PER_SHEET) {
                throw new IllegalArgumentException(
                        "工作表「" + sheet.name() + "」有 " + sheet.rows().size() + " 行，超过 "
                                + MAX_ROWS_PER_SHEET + " 行上限，请拆分后再导入");
            }
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
