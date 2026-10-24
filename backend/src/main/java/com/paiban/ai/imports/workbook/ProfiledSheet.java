package com.paiban.ai.imports.workbook;

import java.util.List;

/**
 * One sheet as the importer sees it: the raw grid for the model to page through, the labels
 * aligned to its columns (composite columns already split), and the data rows below the
 * detected header. {@code skippedReason} is set for sheets a conservative rule leaves out.
 */
public record ProfiledSheet(
        String name,
        List<List<String>> grid,
        List<String> labels,
        List<CompositeColumns.Derived> derived,
        int headerRowIndex,
        List<List<String>> dataRows,
        int nonEmptyCells,
        int columnCount,
        String skippedReason) {

    public boolean skipped() {
        return skippedReason != null;
    }

    /** @return 1-based column position of the label, 0 when the label is unknown. */
    public int columnOf(String label) {
        if (label == null || label.isBlank()) {
            return 0;
        }
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i).equals(label.trim())) {
                return i + 1;
            }
        }
        return 0;
    }

    /** The first column carrying {@code field}, or 0. Derived columns win over originals. */
    public int columnOf(ImportVocabulary.Field field) {
        for (int i = 0; i < derived.size(); i++) {
            if (derived.get(i).field() == field) {
                return labels.size() - derived.size() + i + 1;
            }
        }
        for (int i = 0; i < labels.size() - derived.size(); i++) {
            if (ImportVocabulary.match(labels.get(i)) == field) {
                return i + 1;
            }
        }
        return 0;
    }

    /** The sheet's data rows with the derived columns already appended. */
    public List<List<String>> wideDataRows() {
        return dataRows;
    }
}
