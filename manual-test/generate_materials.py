"""Generate synthetic teacher-list test fixtures (stdlib only, no openpyxl)."""

from pathlib import Path
from xml.sax.saxutils import escape
import csv
import zipfile

ROOT = Path(__file__).resolve().parent


def column(index: int) -> str:
    letters = ""
    while index > 0:
        index, remainder = divmod(index - 1, 26)
        letters = chr(65 + remainder) + letters
    return letters


def write_xlsx(path: Path, sheet_name: str, rows: list[list[str]]) -> None:
    sheet_rows = []
    for row_index, row in enumerate(rows, start=1):
        cells = []
        for column_index, value in enumerate(row, start=1):
            ref = f"{column(column_index)}{row_index}"
            text = escape(str(value))
            cells.append(
                f'<c r="{ref}" t="inlineStr"><is><t xml:space="preserve">{text}</t></is></c>'
            )
        sheet_rows.append(f'<row r="{row_index}">{"".join(cells)}</row>')

    sheet_xml = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        f'<sheetData>{"".join(sheet_rows)}</sheetData></worksheet>'
    )
    workbook_xml = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        f'<sheets><sheet name="{escape(sheet_name)}" sheetId="1" r:id="rId1"/></sheets></workbook>'
    )
    workbook_rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" '
        'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" '
        'Target="worksheets/sheet1.xml"/></Relationships>'
    )
    root_rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" '
        'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
        'Target="xl/workbook.xml"/></Relationships>'
    )
    content_types = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/xl/workbook.xml" '
        'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
        '<Override PartName="/xl/worksheets/sheet1.xml" '
        'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        '</Types>'
    )

    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", content_types)
        archive.writestr("_rels/.rels", root_rels)
        archive.writestr("xl/workbook.xml", workbook_xml)
        archive.writestr("xl/_rels/workbook.xml.rels", workbook_rels)
        archive.writestr("xl/worksheets/sheet1.xml", sheet_xml)


standard_rows = [
    ["姓名", "工号", "手机号", "邮箱", "学科"],
    ["张三", "T001", "13800000001", "zhangsan@example.com", "语文"],
    ["李四", "T002", "13800000002", "lisi@example.com", "数学"],
    ["王五", "T003", "13800000003", "wangwu@example.com", "英语"],
    ["赵六", "T004", "13800000004", "zhaoliu@example.com", "物理"],
    ["孙七", "T005", "13800000005", "sunqi@example.com", "化学"],
]

messy_rows = [
    ["老师姓名", "任教学科", "联系电话", "备注"],
    ["张三", "语文", "13800000001", "班主任"],
    ["李四", "数学", "13800000002", ""],
    ["王五", "英语", "13800000003", "教研组长"],
    ["赵六", "物理", "", "新入职"],
    ["孙七", "化学", "13800000005", ""],
    ["周八", "生物", "13800000006", ""],
    ["吴九", "历史", "13800000007", ""],
    ["郑十", "地理", "13800000008", ""],
    ["陈一", "政治", "13800000009", ""],
    ["刘二", "体育", "13800000010", ""],
    ["黄三", "音乐", "", "兼职"],
    ["何四", "美术", "13800000012", ""],
]

write_xlsx(ROOT / "teachers-standard.xlsx", "教师标准表", standard_rows)
write_xlsx(ROOT / "teachers-messy.xlsx", "教师名单", messy_rows)

with (ROOT / "teachers-standard.csv").open("w", encoding="utf-8-sig", newline="") as handle:
    writer = csv.writer(handle)
    writer.writerows(standard_rows)

with (ROOT / "teachers-messy.csv").open("w", encoding="utf-8-sig", newline="") as handle:
    writer = csv.writer(handle)
    writer.writerows(messy_rows)

(ROOT / "teachers-text.txt").write_text(
    "\n".join(
        [
            "张老师教语文，电话 13800000001，工号 T101；",
            "李老师教数学，电话 13800000002；",
            "王老师教英语，邮箱 wang@example.com；",
            "赵老师教物理，工号 T104；",
            "孙老师教化学，电话 13800000005。",
        ]
    )
    + "\n",
    encoding="utf-8",
)

photo_rows = [
    ("张三", "语文", "13800000001"),
    ("李四", "数学", "13800000002"),
    ("王五", "英语", "13800000003"),
    ("赵六", "物理", "13800000004"),
    ("孙七", "化学", "13800000005"),
    ("周八", "生物", "13800000006"),
    ("吴九", "历史", "13800000007"),
    ("郑十", "地理", "13800000008"),
]
table_rows = "\n".join(
    f"<tr><td>{name}</td><td>{subject}</td><td>{phone}</td></tr>"
    for name, subject, phone in photo_rows
)
(ROOT / "teachers-photo.html").write_text(
    f"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<style>
  body {{ margin: 0; padding: 40px; background: #e8e3d8; font-family: "Microsoft YaHei", "SimSun", serif; }}
  .paper {{ width: 780px; margin: 0 auto; padding: 34px 42px; background: #fffdf7; box-shadow: 0 8px 28px rgba(0,0,0,.18); }}
  h1 {{ text-align: center; font-size: 24px; margin: 0 0 6px; letter-spacing: 2px; }}
  .sub {{ text-align: center; color: #666; font-size: 13px; margin-bottom: 22px; }}
  table {{ width: 100%; border-collapse: collapse; font-size: 15px; }}
  th, td {{ border: 1px solid #555; padding: 9px 10px; text-align: center; }}
  th {{ background: #f0ece1; }}
</style>
</head>
<body>
  <div class="paper">
    <h1>育才中学教师名单</h1>
    <div class="sub">2026—2027 学年第一学期 · 教务处</div>
    <table>
      <thead><tr><th>姓名</th><th>学科</th><th>联系电话</th></tr></thead>
      <tbody>
        {table_rows}
      </tbody>
    </table>
  </div>
</body>
</html>
""",
    encoding="utf-8",
)

print("generated:", ", ".join(sorted(path.name for path in ROOT.iterdir() if path.is_file())))
