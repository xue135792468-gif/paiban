package com.paiban.ai.imports.workbook;

import com.paiban.common.excel.ExcelTableReader;
import com.paiban.common.excel.ExcelTableReader.Grid;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns raw sheets into what the import works on: skips the ones a conservative rule proves
 * carry no tabular data, finds the header (or records that there is none), and appends the
 * columns split out of packed cells. Nothing here asks the model anything - the result is
 * the same for the same file.
 */
public final class SheetProfiler {

    /** Below this many non-empty cells a sheet is documentation, not data. */
    private static final int MIN_CELLS = 5;
    /** A one-column sheet cannot describe a row of fields. */
    private static final int MIN_COLUMNS = 2;

    private SheetProfiler() {}

    public static List<ProfiledSheet> profile(List<Grid> grids) {
        List<ProfiledSheet> sheets = new ArrayList<>();
        for (Grid grid : grids) {
            sheets.add(profile(grid));
        }
        return List.copyOf(sheets);
    }

    public static ProfiledSheet profile(Grid grid) {
        List<List<String>> rows = grid.rows();
        int columnCount = HeaderDetection.widest(rows);
        int nonEmpty = 0;
        for (List<String> row : rows) {
            nonEmpty += HeaderDetection.nonEmpty(row);
        }
        if (rows.isEmpty()) {
            return skipped(grid.sheetName(), rows, columnCount, nonEmpty, "空工作表，已跳过");
        }
        if (nonEmpty < MIN_CELLS) {
            return skipped(grid.sheetName(), rows, columnCount, nonEmpty,
                    "非空单元格少于 " + MIN_CELLS + " 个，判断为说明，已跳过");
        }
        if (columnCount < MIN_COLUMNS) {
            return skipped(grid.sheetName(), rows, columnCount, nonEmpty,
                    "不足 " + MIN_COLUMNS + " 列，判断为说明，已跳过");
        }

        HeaderDetection.Header header = HeaderDetection.detect(rows);
        List<List<String>> dataRows = new ArrayList<>();
        for (int r = header.dataStart(); r < rows.size(); r++) {
            dataRows.add(rows.get(r));
        }
        CompositeColumns.Split split = CompositeColumns.split(header.labels(), dataRows);

        List<String> labels = new ArrayList<>(header.labels());
        for (CompositeColumns.Derived item : split.derived()) {
            labels.add(item.label());
        }
        List<List<String>> wide = new ArrayList<>();
        for (int r = 0; r < dataRows.size(); r++) {
            List<String> row = new ArrayList<>(dataRows.get(r));
            row.addAll(split.extraCells().get(r));
            while (row.size() < labels.size()) {
                row.add("");
            }
            wide.add(List.copyOf(row));
        }
        List<List<String>> normalizedGrid = new ArrayList<>();
        normalizedGrid.add(List.copyOf(labels));
        normalizedGrid.addAll(wide);
        return new ProfiledSheet(
                grid.sheetName(), List.copyOf(normalizedGrid), List.copyOf(labels),
                split.derived(), header.rowIndex(), List.copyOf(wide), nonEmpty,
                columnCount, null);
    }

    private static ProfiledSheet skipped(
            String name, List<List<String>> rows, int columnCount, int nonEmpty, String reason) {
        return new ProfiledSheet(
                name, rows, List.of(), List.of(), -1, List.of(), nonEmpty, columnCount, reason);
    }

    /** Kept so callers can read one sheet without knowing about {@link ExcelTableReader}. */
    public static ProfiledSheet profile(String sheetName, List<List<String>> rows) {
        return profile(new Grid(sheetName, rows));
    }
}
