package com.paiban.timetable.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.paiban.common.settings.SettingsService;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.Room;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.timetable.web.TimetableDtos.TimetableEntryDto;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Excel and PDF export of the merged timetable view. */
@Service
public class TimetableExportService {

    public record Cell(Long courseId, String courseName, String teacherName, String roomName, String color) {}

    public record Grid(String title, List<Integer> days, List<Row> rows, Map<String, Cell> cells) {}

    public record Row(int periodNo, String label, String time) {}

    private static final String[] DAY_NAMES = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final String[] PALETTE = {
        "AEC6CF", "FFD1DC", "B5EAD7", "FFDAC1", "C7CEEA", "E2F0CB", "F1C0E8", "FDFD96", "B0E0E6", "FFB7B2"
    };

    private final TimetableViewService views;
    private final SemesterService semesters;
    private final SettingsService settings;
    private final AdminClassRepository adminClasses;
    private final CourseRepository courses;
    private final TeacherRepository teachers;
    private final RoomRepository rooms;
    private final PeriodRepository periods;
    private final String pdfFontPath;

    public TimetableExportService(
            TimetableViewService views,
            SemesterService semesters,
            SettingsService settings,
            AdminClassRepository adminClasses,
            CourseRepository courses,
            TeacherRepository teachers,
            RoomRepository rooms,
            PeriodRepository periods,
            @Value("${paiban.export.pdf-font-path:}") String pdfFontPath) {
        this.views = views;
        this.semesters = semesters;
        this.settings = settings;
        this.adminClasses = adminClasses;
        this.courses = courses;
        this.teachers = teachers;
        this.rooms = rooms;
        this.periods = periods;
        this.pdfFontPath = pdfFontPath;
    }

    @Transactional(readOnly = true)
    public byte[] excel(String scope, Long id, String view, LocalDate date) {
        List<Grid> grids = grids(scope, id, view, date);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int index = 0; index < grids.size(); index++) {
                writeSheet(workbook, grids.get(index), index);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("导出 Excel 失败", ex);
        }
    }

    @Transactional(readOnly = true)
    public byte[] pdf(String scope, Long id, String view, LocalDate date) {
        List<Grid> grids = grids(scope, id, view, date);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate(), 24, 24, 24, 24);
            PdfWriter.getInstance(document, out);
            document.open();
            BaseFont baseFont = resolveFont();
            Font titleFont = new Font(baseFont, 18, Font.BOLD);
            Font subFont = new Font(baseFont, 11, Font.NORMAL, new Color(90, 90, 90));
            Font headFont = new Font(baseFont, 10, Font.BOLD);
            Font bodyFont = new Font(baseFont, 9, Font.NORMAL);
            Font smallFont = new Font(baseFont, 8, Font.NORMAL, new Color(80, 80, 80));

            for (int index = 0; index < grids.size(); index++) {
                Grid grid = grids.get(index);
                if (index > 0) {
                    document.newPage();
                }
                addLogo(document);
                Paragraph title = new Paragraph(settings.schoolName() + " · " + grid.title(), titleFont);
                title.setAlignment(Element.ALIGN_CENTER);
                document.add(title);
                Paragraph subtitle = new Paragraph(
                        semesters.requireCurrent().getName() + "  ·  " + LocalDate.now(), subFont);
                subtitle.setAlignment(Element.ALIGN_CENTER);
                document.add(subtitle);
                document.add(new Paragraph(" "));

                PdfPTable table = new PdfPTable(grid.days().size() + 1);
                table.setWidthPercentage(100);
                table.setHeaderRows(1);
                table.addCell(headerCell("节次", headFont));
                for (Integer day : grid.days()) {
                    table.addCell(headerCell(DAY_NAMES[day], headFont));
                }
                for (Row row : grid.rows()) {
                    PdfPCell labelCell = new PdfPCell(new Phrase(
                            "第" + row.periodNo() + "节\n" + row.time(), smallFont));
                    labelCell.setPadding(4);
                    labelCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                    labelCell.setBackgroundColor(new Color(245, 247, 251));
                    table.addCell(labelCell);
                    for (Integer day : grid.days()) {
                        Cell cell = grid.cells().get(day + "-" + row.periodNo());
                        PdfPCell pdfCell = new PdfPCell();
                        pdfCell.setPadding(4);
                        pdfCell.setMinimumHeight(34);
                        if (cell == null) {
                            pdfCell.setPhrase(new Phrase("", bodyFont));
                        } else {
                            pdfCell.setPhrase(new Phrase(
                                    cell.courseName() + "\n" + nullSafe(cell.teacherName()) + "\n"
                                            + nullSafe(cell.roomName()), bodyFont));
                            pdfCell.setBackgroundColor(hexColor(cell.color(), cell.courseId()));
                        }
                        table.addCell(pdfCell);
                    }
                }
                document.add(table);
            }
            document.close();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("导出 PDF 失败", ex);
        }
    }

    private void addLogo(Document document) {
        String logo = settings.schoolLogoPath();
        if (logo == null || logo.isBlank()) {
            return;
        }
        File file = new File(logo);
        if (!file.exists()) {
            return;
        }
        try {
            Image image = Image.getInstance(file.getAbsolutePath());
            image.scaleToFit(48, 48);
            image.setAlignment(Element.ALIGN_CENTER);
            document.add(image);
        } catch (Exception ignored) {
            // logo is decorative; never fail the export because of it
        }
    }

    private PdfPCell headerCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(5);
        cell.setBackgroundColor(new Color(230, 236, 250));
        return cell;
    }

    private void writeSheet(XSSFWorkbook workbook, Grid grid, int index) {
        var sheet = workbook.createSheet(safeSheetName(grid.title(), index));
        int columns = grid.days().size() + 1;
        CellStyle titleStyle = workbook.createCellStyle();
        var titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 16);
        titleStyle.setFont(titleFont);
        titleStyle.setAlignment(HorizontalAlignment.CENTER);

        var titleRow = sheet.createRow(0);
        var titleCell = titleRow.createCell(0);
        titleCell.setCellValue(settings.schoolName() + " · " + grid.title());
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, columns - 1));

        var subRow = sheet.createRow(1);
        subRow.createCell(0).setCellValue(semesters.requireCurrent().getName() + "  ·  " + LocalDate.now());
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, columns - 1));

        CellStyle headStyle = workbook.createCellStyle();
        headStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte) 230, (byte) 236, (byte) 250}, null));
        headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headStyle.setAlignment(HorizontalAlignment.CENTER);
        headStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        headStyle.setBorderBottom(BorderStyle.THIN);
        headStyle.setBorderTop(BorderStyle.THIN);
        headStyle.setBorderLeft(BorderStyle.THIN);
        headStyle.setBorderRight(BorderStyle.THIN);

        var header = sheet.createRow(2);
        var labelHeader = header.createCell(0);
        labelHeader.setCellValue("节次");
        labelHeader.setCellStyle(headStyle);
        for (int i = 0; i < grid.days().size(); i++) {
            var cell = header.createCell(i + 1);
            cell.setCellValue(DAY_NAMES[grid.days().get(i)]);
            cell.setCellStyle(headStyle);
        }

        CellStyle bodyStyle = workbook.createCellStyle();
        bodyStyle.setWrapText(true);
        bodyStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        bodyStyle.setBorderBottom(BorderStyle.THIN);
        bodyStyle.setBorderTop(BorderStyle.THIN);
        bodyStyle.setBorderLeft(BorderStyle.THIN);
        bodyStyle.setBorderRight(BorderStyle.THIN);

        Map<String, CellStyle> courseStyles = new HashMap<>();
        int rowIndex = 3;
        for (Row row : grid.rows()) {
            var excelRow = sheet.createRow(rowIndex);
            var label = excelRow.createCell(0);
            label.setCellValue("第" + row.periodNo() + "节\n" + row.time());
            label.setCellStyle(bodyStyle);
            for (int i = 0; i < grid.days().size(); i++) {
                int day = grid.days().get(i);
                Cell cell = grid.cells().get(day + "-" + row.periodNo());
                var excelCell = excelRow.createCell(i + 1);
                if (cell == null) {
                    excelCell.setCellValue("");
                    excelCell.setCellStyle(bodyStyle);
                } else {
                    excelCell.setCellValue(cell.courseName() + "\n" + nullSafe(cell.teacherName())
                            + (cell.roomName() == null ? "" : "\n" + cell.roomName()));
                    CellStyle style = courseStyles.computeIfAbsent(
                            String.valueOf(cell.courseId()),
                            key -> courseStyle(workbook, cell.color(), cell.courseId()));
                    excelCell.setCellStyle(style);
                }
            }
            rowIndex++;
        }

        // Merge consecutive lessons of the same course on the same day (连堂).
        for (int i = 0; i < grid.days().size(); i++) {
            int day = grid.days().get(i);
            int runStart = -1;
            Long runCourse = null;
            for (int r = 0; r < grid.rows().size(); r++) {
                Cell cell = grid.cells().get(day + "-" + grid.rows().get(r).periodNo());
                Long courseId = cell == null ? null : cell.courseId();
                if (courseId != null && courseId.equals(runCourse)) {
                    continue;
                }
                if (runCourse != null && runStart >= 0 && r - runStart > 1) {
                    sheet.addMergedRegion(new CellRangeAddress(
                            3 + runStart, 3 + r - 1, i + 1, i + 1));
                }
                runStart = r;
                runCourse = courseId;
            }
            if (runCourse != null && runStart >= 0 && grid.rows().size() - runStart > 1) {
                sheet.addMergedRegion(new CellRangeAddress(
                        3 + runStart, 3 + grid.rows().size() - 1, i + 1, i + 1));
            }
        }

        sheet.setColumnWidth(0, 16 * 256);
        for (int i = 1; i < columns; i++) {
            sheet.setColumnWidth(i, 22 * 256);
        }
        sheet.createFreezePane(1, 3);
    }

    private CellStyle courseStyle(XSSFWorkbook workbook, String color, Long courseId) {
        XSSFCellStyle style = workbook.createCellStyle();
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setFillForegroundColor(new XSSFColor(hexBytes(color, courseId), null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private List<Grid> grids(String scope, Long id, String view, LocalDate date) {
        Long semesterId = semesters.requireCurrent().getId();
        Map<Long, Period> periodMap = new HashMap<>();
        periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId)
                .forEach(period -> periodMap.put(period.getId(), period));
        List<Row> rows = buildRows(periodMap.values().stream().toList());
        List<Integer> days = periodMap.values().stream()
                .map(Period::getDayOfWeek)
                .distinct()
                .sorted()
                .toList();

        return switch (scope) {
            case "class" -> {
                AdminClass adminClass = adminClasses.findById(id)
                        .orElseThrow(() -> ApiException.notFound("班级不存在: " + id));
                yield List.of(buildGrid(adminClass.getName() + " 课表", days, rows,
                        views.classView(id, view, date)));
            }
            case "teacher" -> {
                Teacher teacher = teachers.findById(id)
                        .orElseThrow(() -> ApiException.notFound("教师不存在: " + id));
                yield List.of(buildGrid(teacher.getName() + " 课表", days, rows,
                        views.teacherView(id, view, date)));
            }
            case "room" -> {
                Room room = rooms.findById(id)
                        .orElseThrow(() -> ApiException.notFound("教室不存在: " + id));
                yield List.of(buildGrid(room.getName() + " 占用表", days, rows,
                        views.roomView(id, view, date)));
            }
            case "school" -> {
                List<Grid> result = new ArrayList<>();
                List<TimetableEntryDto> all = views.schoolView(view, date).entries();
                Map<Long, List<TimetableEntryDto>> byClass = new LinkedHashMap<>();
                all.forEach(entry -> byClass
                        .computeIfAbsent(entry.classId(), key -> new ArrayList<>())
                        .add(entry));
                List<AdminClass> classes = adminClasses.findAll().stream()
                        .filter(adminClass -> adminClass.getSemesterId().equals(semesterId))
                        .sorted(Comparator.comparing(AdminClass::getName))
                        .toList();
                for (AdminClass adminClass : classes) {
                    result.add(buildGrid(adminClass.getName() + " 课表", days, rows,
                            byClass.getOrDefault(adminClass.getId(), List.of())));
                }
                yield result;
            }
            default -> throw ApiException.badRequest("不支持的导出范围: " + scope);
        };
    }

    private List<Row> buildRows(List<Period> periodList) {
        Map<Integer, Row> map = new LinkedHashMap<>();
        periodList.stream()
                .sorted(Comparator.comparingInt(Period::getPeriodNo))
                .forEach(period -> map.putIfAbsent(
                        period.getPeriodNo(),
                        new Row(
                                period.getPeriodNo(),
                                period.getLabel(),
                                period.getStartTime() + "-" + period.getEndTime())));
        return new ArrayList<>(map.values());
    }

    private Grid buildGrid(String title, List<Integer> days, List<Row> rows, List<TimetableEntryDto> entries) {
        Map<String, Cell> cells = new HashMap<>();
        Map<Long, Course> courseMap = new HashMap<>();
        courses.findAll().forEach(course -> courseMap.put(course.getId(), course));
        for (TimetableEntryDto entry : entries) {
            Course course = courseMap.get(entry.courseId());
            cells.put(
                    entry.dayOfWeek() + "-" + entry.periodNo(),
                    new Cell(
                            entry.courseId(),
                            entry.courseName(),
                            entry.teacherName(),
                            entry.roomName(),
                            course == null ? null : course.getColor()));
        }
        return new Grid(title, days, rows, cells);
    }

    private byte[] hexBytes(String hex, Long courseId) {
        String value = hex;
        if (value == null || value.isBlank()) {
            value = PALETTE[(int) (Math.abs(courseId == null ? 0 : courseId) % PALETTE.length)];
        }
        value = value.replace("#", "");
        if (value.length() != 6) {
            value = PALETTE[0];
        }
        try {
            return new byte[]{
                (byte) Integer.parseInt(value.substring(0, 2), 16),
                (byte) Integer.parseInt(value.substring(2, 4), 16),
                (byte) Integer.parseInt(value.substring(4, 6), 16)
            };
        } catch (NumberFormatException ex) {
            return new byte[]{(byte) 0xAE, (byte) 0xC6, (byte) 0xCF};
        }
    }

    private Color hexColor(String hex, Long courseId) {
        byte[] bytes = hexBytes(hex, courseId);
        return new Color(bytes[0] & 0xFF, bytes[1] & 0xFF, bytes[2] & 0xFF);
    }

    private BaseFont resolveFont() {
        List<String> candidates = new ArrayList<>();
        if (pdfFontPath != null && !pdfFontPath.isBlank()) {
            candidates.add(pdfFontPath);
        }
        candidates.add("C:/Windows/Fonts/msyh.ttc,0");
        candidates.add("C:/Windows/Fonts/simsun.ttc,0");
        candidates.add("C:/Windows/Fonts/simhei.ttf");
        candidates.add("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc,0");
        candidates.add("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc,0");
        for (String candidate : candidates) {
            String path = candidate.contains(",") ? candidate.substring(0, candidate.indexOf(',')) : candidate;
            if (!new File(path).exists()) {
                continue;
            }
            try {
                return BaseFont.createFont(candidate, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            } catch (Exception ignored) {
                // try the next candidate
            }
        }
        try {
            return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (Exception ex) {
            throw new IllegalStateException("无法加载 PDF 字体", ex);
        }
    }

    private String safeSheetName(String title, int index) {
        String value = title.replaceAll("[\\\\/*?\\[\\]:]", " ").trim();
        if (value.length() > 28) {
            value = value.substring(0, 28);
        }
        return (index + 1) + "-" + value;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
