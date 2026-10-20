package com.paiban.ai.imports;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Target shapes the import pipeline can write into. Phase two ships teachers;
 * adding a target means adding one enum value and its persistence hook in
 * {@link AiImportService}, leaving the AI prompt and preview format unchanged.
 */
public enum ImportTarget {

    TEACHER("teacher", List.of(
            new Field("name", "姓名", true),
            new Field("employeeNo", "工号", false),
            new Field("phone", "电话", false),
            new Field("email", "邮箱", false),
            new Field("subject", "学科", false)));

    public record Field(String field, String label, boolean required) {}

    private final String code;
    private final List<Field> fields;

    ImportTarget(String code, List<Field> fields) {
        this.code = code;
        this.fields = fields;
    }

    public String code() {
        return code;
    }

    public List<Field> fields() {
        return fields;
    }

    public List<String> fieldNames() {
        return fields.stream().map(Field::field).toList();
    }

    public Optional<Field> field(String name) {
        return fields.stream().filter(field -> field.field().equals(name)).findFirst();
    }

    public static ImportTarget fromCode(String code) {
        String normalized = code == null ? "" : code.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(value -> value.code.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的导入目标: " + code));
    }
}
