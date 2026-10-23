package com.paiban.ai.imports;

import java.util.List;

/**
 * Reads the grade out of how a school writes a class name: {@code 八（1）}, {@code 高一1班},
 * {@code 7年级(2)}. Only the prefix is inspected; whether the grade exists is the caller's
 * call, so nothing is renamed here.
 */
public final class GradeInference {

    /** Stage prefixes that already name a grade and are kept verbatim. */
    private static final List<String> STAGES = List.of("高一", "高二", "高三", "初一", "初二", "初三");

    private GradeInference() {}

    /** @return 八年级 / 高一 / 初一 / 7年级 / 一年级, or null when the prefix is not a grade. */
    public static String infer(String className) {
        String name = ImportMappingService.normalizeName(className);
        if (name.isEmpty()) {
            return null;
        }
        for (String stage : STAGES) {
            if (name.startsWith(stage)) {
                return stage;
            }
        }
        if (isGradeNumeral(name.charAt(0))) {
            if (name.startsWith("年级", 1)) {
                return name.substring(0, 3);
            }
            if (name.length() == 1 || "（(班".indexOf(name.charAt(1)) >= 0) {
                return name.charAt(0) + "年级";
            }
        }
        return null;
    }

    /**
     * Matches via {@link ImportMappingService#normalizeName}; returns the existing grade's exact
     * name so the caller can map to it instead of creating a duplicate.
     */
    public static String matchExisting(String inferred, List<String> gradeNames) {
        if (inferred == null || gradeNames == null) {
            return inferred;
        }
        String needle = ImportMappingService.normalizeName(inferred);
        for (String name : gradeNames) {
            if (name != null && ImportMappingService.normalizeName(name).equals(needle)) {
                return name;
            }
        }
        return inferred;
    }

    private static boolean isGradeNumeral(char value) {
        return (value >= '1' && value <= '9') || "一二三四五六七八九".indexOf(value) >= 0;
    }
}
