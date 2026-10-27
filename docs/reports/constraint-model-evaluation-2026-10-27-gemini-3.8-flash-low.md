# 自然语言约束模型评测

- 语料版本：C01-C22-v1
- 生成时间：2026-10-27T13:19:32.227479233Z
- 每句次数：3
- 结论：FAILED
- 预算：20 元；已预留 0.8382 元；按用量 0.3218 元；剩余 19.1618 元
- 明确支持门槛：54 次中至少 52 次整句正确；边界 12 次零误转

## provider 2 / model 4

- 明确支持：51/54 通过
- 边界：10/12 零误转
- 结论：未达标

| 句子 | 次数 | 结果 | 原因 |
|---|---|---|---|
| C01 | 1 | 通过 |  |
| C01 | 2 | 通过 |  |
| C01 | 3 | 通过 |  |
| C02 | 1 | 通过 |  |
| C02 | 2 | 通过 |  |
| C02 | 3 | 通过 |  |
| C03 | 1 | 通过 |  |
| C03 | 2 | 通过 |  |
| C03 | 3 | 通过 |  |
| C04 | 1 | 通过 |  |
| C04 | 2 | 通过 |  |
| C04 | 3 | 通过 |  |
| C05 | 1 | 失败 | 缺少或错误的要求: COURSE_REQUIRED_ROOM_TYPE INHERIT {course=化学, roomType=LAB} |
| C05 | 2 | 失败 | 缺少或错误的要求: COURSE_REQUIRED_ROOM_TYPE INHERIT {course=化学, roomType=LAB} |
| C05 | 3 | 通过 |  |
| C06 | 1 | 通过 |  |
| C06 | 2 | 通过 |  |
| C06 | 3 | 通过 |  |
| C07 | 1 | 通过 |  |
| C07 | 2 | 通过 |  |
| C07 | 3 | 通过 |  |
| C08 | 1 | 通过 |  |
| C08 | 2 | 通过 |  |
| C08 | 3 | 通过 |  |
| C09 | 1 | 通过 |  |
| C09 | 2 | 通过 |  |
| C09 | 3 | 通过 |  |
| C10 | 1 | 通过 |  |
| C10 | 2 | 通过 |  |
| C10 | 3 | 通过 |  |
| C11 | 1 | 通过 |  |
| C11 | 2 | 通过 |  |
| C11 | 3 | 通过 |  |
| C12 | 1 | 通过 |  |
| C12 | 2 | 通过 |  |
| C12 | 3 | 通过 |  |
| C13 | 1 | 通过 |  |
| C13 | 2 | 通过 |  |
| C13 | 3 | 通过 |  |
| C14 | 1 | 通过 |  |
| C14 | 2 | 通过 |  |
| C14 | 3 | 通过 |  |
| C15 | 1 | 通过 |  |
| C15 | 2 | 通过 |  |
| C15 | 3 | 通过 |  |
| C16 | 1 | 失败 | 同名教师没有作为候选请用户选择 |
| C16 | 2 | 失败 | 同名教师没有作为候选请用户选择 |
| C16 | 3 | 通过 |  |
| C17 | 1 | 通过 |  |
| C17 | 2 | 通过 |  |
| C17 | 3 | 通过 |  |
| C18 | 1 | 通过 |  |
| C18 | 2 | 通过 |  |
| C18 | 3 | 通过 |  |
| C19 | 1 | 通过 |  |
| C19 | 2 | 通过 |  |
| C19 | 3 | 通过 |  |
| C20 | 1 | 通过 |  |
| C20 | 2 | 通过 |  |
| C20 | 3 | 通过 |  |
| C21 | 1 | 通过 |  |
| C21 | 2 | 通过 |  |
| C21 | 3 | 通过 |  |
| C22 | 1 | 通过 |  |
| C22 | 2 | 失败 | 调用失败: TRUNCATED AI 输出被截断（length），请增大模型输出上限或减少单次行数 |
| C22 | 3 | 通过 |  |

### 失败明细

#### C05 第1次

原因：缺少或错误的要求: COURSE_REQUIRED_ROOM_TYPE INHERIT {course=化学, roomType=LAB}

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"化学课安排在实验室","type":"COURSE_REQUIRED_ROOM_TYPE","strength":"HARD","status":"READY","explanation":"化学课必须安排在实验室上课","reason":"","teacherName":"","courseName":"化学","className":"","subject":"","roomType":"LAB","days":[],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{}}]}
```

#### C05 第2次

原因：缺少或错误的要求: COURSE_REQUIRED_ROOM_TYPE INHERIT {course=化学, roomType=LAB}

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"化学课安排在实验室。","type":"COURSE_REQUIRED_ROOM_TYPE","strength":"HARD","status":"READY","explanation":"化学课必须安排在实验室上课","reason":"","teacherName":"","courseName":"化学","className":"","subject":"","roomType":"LAB","days":[],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{}}]}
```

#### C16 第1次

原因：同名教师没有作为候选请用户选择

原始输出（截断展示，完整内容见 JSON）：
```
{
  "proposals": [
    {
      "sourceText": "张老师周三下午不排",
      "type": "TEACHER_UNAVAILABLE",
      "strength": "HARD",
      "status": "NEEDS_INPUT",
      "explanation": "指定教师周三下午（第5至8节）不排课",
      "reason": "存在多位张姓教师（张伟甲、张伟乙），无法确定具体教师",
      "teacherName": "",
      "courseName": "",
      "className": "",
      "subject": "",
      "roomType": "",
      "days": [3],
      "periods": [5, 6, 7, 8],
      "section": "AFTERNOON",
      "sectionOrdinals": [1, 2, 3, 4],
      "allDays": false,
      "values": {}
    },
    {
      "sourceText": "体育不排晚自习",
      "type": "COURSE_NOT_IN_EVENING",
      "strength": "HARD",
      "status": "READY",
      "explanation": "体育课不安排在晚自习时段",
      "reason": "",
      "teacherName": "",
      "courseName": "体育",
      "className": "",
      "subject": "",
      "roomType": "",
      "days": [],
      "periods": [9, 10],
      "section": "EVENING",
      "sectionOrdinals": [1, 2],
      "allDays": true,
      "values": {}
    }
  ]
}
```

#### C16 第2次

原因：同名教师没有作为候选请用户选择

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"张老师周三下午不排","type":"TEACHER_UNAVAILABLE","strength":"HARD","status":"NEEDS_INPUT","explanation":"指定张老师周三下午（第5-8节）不排课","reason":"上下文存在多位张姓教师（张伟甲、张伟乙），无法确定具体指代哪一位","teacherName":"","courseName":"","className":"","subject":"","roomType":"","days":[3],"periods":[5,6,7,8],"section":"AFTERNOON","sectionOrdinals":[1,2,3,4],"allDays":false,"values":{}},{"sourceText":"体育不排晚自习","type":"COURSE_NOT_IN_EVENING","strength":"HARD","status":"READY","explanation":"体育课不安排在晚自习时段","reason":"","teacherName":"","courseName":"体育","className":"","subject":"","roomType":"","days":[],"periods":[],"section":"EVENING","sectionOrdinals":[],"allDays":true,"values":{}}]}
```

#### C22 第2次

原因：调用失败: TRUNCATED AI 输出被截断（length），请增大模型输出上限或减少单次行数

原始输出（截断展示，完整内容见 JSON）：
```

```

