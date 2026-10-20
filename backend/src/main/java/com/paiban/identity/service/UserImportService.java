package com.paiban.identity.service;

import com.paiban.common.excel.ExcelTableReader;
import com.paiban.common.excel.ExcelTableReader.TableRow;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.web.ImportDtos.ImportError;
import com.paiban.foundation.web.ImportDtos.ImportReport;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.identity.domain.RoleRepository;
import com.paiban.identity.web.IdentityDtos.CreateUserRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Batch creation of local accounts from Excel (user story 52). */
@Service
public class UserImportService {

    private static final List<String> HEADERS =
            List.of("用户名", "姓名", "初始密码", "角色", "教师工号");
    private static final List<String> REQUIRED = List.of("用户名", "姓名", "初始密码", "角色");

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final TeacherRepository teachers;
    private final UserService userService;

    public UserImportService(
            AppUserRepository users,
            RoleRepository roles,
            TeacherRepository teachers,
            UserService userService) {
        this.users = users;
        this.roles = roles;
        this.teachers = teachers;
        this.userService = userService;
    }

    public ImportReport preview(MultipartFile file) {
        return handle(file, false);
    }

    @Transactional
    public ImportReport commit(MultipartFile file) {
        return handle(file, true);
    }

    public byte[] template() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("账号导入模板");
            CellStyle style = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.size(); i++) {
                var cell = header.createCell(i);
                cell.setCellValue(HEADERS.get(i));
                cell.setCellStyle(style);
                sheet.setColumnWidth(i, 20 * 256);
            }
            Row sample = sheet.createRow(1);
            sample.createCell(0).setCellValue("T001");
            sample.createCell(1).setCellValue("张三");
            sample.createCell(2).setCellValue("teacher123");
            sample.createCell(3).setCellValue("teacher");
            sample.createCell(4).setCellValue("EMP001");
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("生成模板失败", ex);
        }
    }

    private ImportReport handle(MultipartFile file, boolean commit) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("请上传 Excel 文件");
        }
        ExcelTableReader.Table table;
        try {
            table = ExcelTableReader.read(file.getInputStream());
        } catch (IOException | RuntimeException ex) {
            throw ApiException.badRequest("无法解析 Excel 文件: " + ex.getMessage());
        }
        List<String> missing = REQUIRED.stream().filter(header -> !table.headers().contains(header)).toList();
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("缺少必需的列: " + String.join("、", missing));
        }

        List<ImportError> errors = new ArrayList<>();
        List<Map<String, String>> validRows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TableRow row : table.rows()) {
            List<ImportError> rowErrors = new ArrayList<>();
            String username = value(row, "用户名");
            String displayName = value(row, "姓名");
            String password = value(row, "初始密码");
            String role = normalizeRole(value(row, "角色"));
            String employeeNo = value(row, "教师工号");

            if (username.isEmpty()) {
                rowErrors.add(new ImportError(row.rowNumber(), "用户名", "不能为空"));
            } else if (!seen.add(username)) {
                rowErrors.add(new ImportError(row.rowNumber(), "用户名", "文件内用户名重复"));
            } else if (users.existsByUsername(username)) {
                rowErrors.add(new ImportError(row.rowNumber(), "用户名", "用户名已存在"));
            }
            if (displayName.isEmpty()) {
                rowErrors.add(new ImportError(row.rowNumber(), "姓名", "不能为空"));
            }
            if (password.length() < 6) {
                rowErrors.add(new ImportError(row.rowNumber(), "初始密码", "密码至少 6 位"));
            }
            if (role == null) {
                rowErrors.add(new ImportError(row.rowNumber(), "角色", "只能是 admin / teacher / head_teacher"));
            }
            Long teacherId = null;
            if (!employeeNo.isEmpty()) {
                Teacher teacher = teachers.findByEmployeeNo(employeeNo).orElse(null);
                if (teacher == null) {
                    rowErrors.add(new ImportError(row.rowNumber(), "教师工号", "教师不存在: " + employeeNo));
                } else {
                    teacherId = teacher.getId();
                }
            }
            if (rowErrors.isEmpty()) {
                Map<String, String> normalized = new LinkedHashMap<>();
                normalized.put("用户名", username);
                normalized.put("姓名", displayName);
                normalized.put("角色", role);
                normalized.put("教师工号", employeeNo);
                normalized.put("teacherId", teacherId == null ? "" : String.valueOf(teacherId));
                normalized.put("password", password);
                validRows.add(normalized);
            } else {
                errors.addAll(rowErrors);
            }
        }

        int imported = 0;
        if (commit) {
            for (Map<String, String> row : validRows) {
                Long teacherId = row.get("teacherId").isEmpty() ? null : Long.parseLong(row.get("teacherId"));
                userService.create(new CreateUserRequest(
                        row.get("用户名"),
                        row.get("password"),
                        row.get("姓名"),
                        List.of(row.get("角色")),
                        teacherId));
                imported++;
            }
        }

        List<Map<String, String>> preview = commit ? List.of() : validRows.stream()
                .map(row -> {
                    Map<String, String> copy = new LinkedHashMap<>(row);
                    copy.remove("password");
                    copy.remove("teacherId");
                    return copy;
                })
                .toList();

        return new ImportReport(
                "users",
                "用户账号",
                table.rows().size(),
                validRows.size(),
                errors.size(),
                imported,
                errors,
                List.of(),
                preview);
    }

    private String value(TableRow row, String header) {
        return row.values().getOrDefault(header, "").trim();
    }

    private String normalizeRole(String value) {
        String normalized = switch (value) {
            case "教务管理员", "管理员", "admin" -> "admin";
            case "教师", "teacher" -> "teacher";
            case "班主任", "head_teacher" -> "head_teacher";
            default -> null;
        };
        return normalized != null && roles.findByCode(normalized).isPresent() ? normalized : null;
    }
}
