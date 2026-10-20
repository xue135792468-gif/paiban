# 14: 课表导出（Excel + PDF）

**What to build:** 实现课表的 Excel 与 PDF 导出：班级/教师/教室三个视角都能导出，Excel 用 Apache POI（格式规整、含合并单元格、含学校 Logo 与学期信息），PDF 用 OpenPDF 渲染 HTML 模板（适合打印张贴）。完成后，admin 在班级课表页点"导出 Excel"下载到格式规整的文件；点"导出 PDF"得到可打印张贴的版本。

**Blocked by:** 09

**Status:** ready-for-agent

- [ ] Excel 导出接口：GET `/api/timetable/class/{id}/export/excel`、`/teacher/{id}`、`/room/{id}` 同理
- [ ] Excel 格式：首行学校名 + 学期名，次行班级名，表头周一到周日 + 节次，合并单元格（连堂显示为一格），课程用浅色填充
- [ ] PDF 导出接口：同 Excel 三个视角
- [ ] PDF 模板：Thymeleaf HTML 模板 + OpenPDF 渲染，A4 横向，含学校 Logo（可配置）与学期信息，适合打印张贴
- [ ] 导出内容与课表查询结果一致（合并调代后的最新视图）
- [ ] 支持 `view=baseline` 参数导出原始视图
- [ ] Excel 导出接缝测试：用 POI 解析导出结果，断言结构与内容正确
- [ ] PDF 导出测试：断言生成的 PDF 非空且可被 PDFBox 解析
- [ ] 性能验证：全校总表导出 Excel < 5s
