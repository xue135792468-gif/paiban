package com.paiban.ai.imports;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Small RFC-4180-ish CSV reader for the import pipeline. Handles quoted fields,
 * embedded newlines, UTF-8 with BOM and falls back to GBK for files produced by
 * older Chinese versions of Excel.
 */
public final class CsvTableReader {

    private CsvTableReader() {}

    /**
     * The CSV as a raw grid: every record, header row included, no column clipping.
     * The AI is the one deciding which row is a title, a header or data, so the first
     * row must not be consumed as a fixed header.
     */
    public static List<List<String>> readGrid(byte[] bytes) {
        return readGrid(bytes, Integer.MAX_VALUE);
    }

    /**
     * Same grid, but the parser stops as soon as it has read more than {@code maxRows}
     * records and reports how many it saw: a caller that has its own upload budget (the
     * manual import) must fail fast instead of materialising a huge grid first.
     */
    public static List<List<String>> readGrid(byte[] bytes, int maxRows) {
        String text = decode(bytes);
        List<List<String>> records = parse(text, detectDelimiter(text), maxRows);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("CSV 中没有可解析的数据");
        }
        return records.stream()
                .map(record -> record.stream().map(String::trim).toList())
                .toList();
    }

    static String decode(byte[] bytes) {
        String text = decodeStrict(bytes, StandardCharsets.UTF_8);
        if (text == null) {
            text = decodeStrict(bytes, Charset.forName("GBK"));
        }
        if (text == null) {
            text = new String(bytes, StandardCharsets.UTF_8);
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }

    private static String decodeStrict(byte[] bytes, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            return null;
        }
    }

    static char detectDelimiter(String text) {
        String firstLine = text.lines().findFirst().orElse("");
        int comma = count(firstLine, ',');
        int semicolon = count(firstLine, ';');
        int tab = count(firstLine, '\t');
        if (semicolon > comma && semicolon >= tab) {
            return ';';
        }
        if (tab > comma && tab > semicolon) {
            return '\t';
        }
        return ',';
    }

    private static int count(String text, char needle) {
        int total = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == needle && !quoted) {
                total++;
            }
        }
        return total;
    }

    static List<List<String>> parse(String text, char delimiter) {
        return parse(text, delimiter, Integer.MAX_VALUE);
    }

    /**
     * Record-by-record parse. The row budget is checked as records are appended, so an
     * oversized file is refused while reading instead of after the whole grid exists.
     */
    static List<List<String>> parse(String text, char delimiter, int maxRows) {
        List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
            } else if (c == delimiter) {
                current.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                current.add(field.toString());
                records.add(current);
                requireRowBudget(records.size(), maxRows);
                current = new ArrayList<>();
                field.setLength(0);
            } else if (c == '\r') {
                // \r\n is handled by the \n branch.
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            records.add(current);
            requireRowBudget(records.size(), maxRows);
        }
        return records;
    }

    private static void requireRowBudget(int rows, int maxRows) {
        if (rows > maxRows) {
            throw new IllegalArgumentException(
                    "CSV 有超过 " + maxRows + " 行，超过单表行数上限，请拆分后再导入");
        }
    }
}
