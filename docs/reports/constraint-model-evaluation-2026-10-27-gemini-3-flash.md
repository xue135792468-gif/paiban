# 自然语言约束模型评测

- 语料版本：C01-C22-v1
- 生成时间：2026-10-27T12:10:33.797045483Z
- 每句次数：3
- 结论：FAILED
- 预算：20 元；已预留 0.8382 元；按用量 0.1960 元；剩余 19.1618 元
- 明确支持门槛：54 次中至少 52 次整句正确；边界 12 次零误转

## provider 2 / model 8

- 明确支持：27/54 通过
- 边界：9/12 零误转
- 结论：未达标

| 句子 | 次数 | 结果 | 原因 |
|---|---|---|---|
| C01 | 1 | 失败 | 缺少或错误的要求: TEACHER_UNAVAILABLE HARD {teacher=王明} [Slot[day=3, periodNo=5], Slot[day=3, periodNo=6], Slot[day=3, periodNo=7], Slot[day=3, periodNo=8]] |
| C01 | 2 | 失败 | 缺少或错误的要求: TEACHER_UNAVAILABLE HARD {teacher=王明} [Slot[day=3, periodNo=5], Slot[day=3, periodNo=6], Slot[day=3, periodNo=7], Slot[day=3, periodNo=8]] |
| C01 | 3 | 失败 | 缺少或错误的要求: TEACHER_UNAVAILABLE HARD {teacher=王明} [Slot[day=3, periodNo=5], Slot[day=3, periodNo=6], Slot[day=3, periodNo=7], Slot[day=3, periodNo=8]] |
| C02 | 1 | 失败 | 缺少或错误的要求: TEACHER_UNAVAILABLE HARD {teacher=王明} [Slot[day=2, periodNo=1], Slot[day=2, periodNo=2]] |
| C02 | 2 | 通过 |  |
| C02 | 3 | 通过 |  |
| C03 | 1 | 通过 |  |
| C03 | 2 | 失败 | 输出无法解析: 模型输出不是合法 JSON |
| C03 | 3 | 通过 |  |
| C04 | 1 | 通过 |  |
| C04 | 2 | 通过 |  |
| C04 | 3 | 通过 |  |
| C05 | 1 | 通过 |  |
| C05 | 2 | 通过 |  |
| C05 | 3 | 通过 |  |
| C06 | 1 | 通过 |  |
| C06 | 2 | 通过 |  |
| C06 | 3 | 通过 |  |
| C07 | 1 | 失败 | 缺少或错误的要求: SAME_COURSE_ONCE_PER_DAY HARD {} |
| C07 | 2 | 通过 |  |
| C07 | 3 | 通过 |  |
| C08 | 1 | 失败 | 缺少或错误的要求: TEACHER_MAX_PER_DAY HARD {maxPerDay=4} |
| C08 | 2 | 失败 | 缺少或错误的要求: TEACHER_MAX_PER_DAY HARD {maxPerDay=4} |
| C08 | 3 | 失败 | 缺少或错误的要求: TEACHER_MAX_PER_DAY HARD {maxPerDay=4} |
| C09 | 1 | 失败 | 缺少或错误的要求: CLASS_MAX_PER_DAY HARD {maxPerDay=8, class=七年级1班} |
| C09 | 2 | 失败 | 缺少或错误的要求: CLASS_MAX_PER_DAY HARD {maxPerDay=8, class=七年级1班} |
| C09 | 3 | 失败 | 缺少或错误的要求: CLASS_MAX_PER_DAY HARD {maxPerDay=8, class=七年级1班} |
| C10 | 1 | 通过 |  |
| C10 | 2 | 通过 |  |
| C10 | 3 | 通过 |  |
| C11 | 1 | 失败 | 缺少或错误的要求: EVENING_MAX HARD {maxPerDay=2} |
| C11 | 2 | 失败 | 缺少或错误的要求: EVENING_MAX HARD {maxPerDay=2} |
| C11 | 3 | 失败 | 缺少或错误的要求: EVENING_MAX HARD {maxPerDay=2} |
| C12 | 1 | 失败 | 缺少或错误的要求: SPORT_NOT_FIRST_AFTERNOON SOFT {course=体育} |
| C12 | 2 | 失败 | 缺少或错误的要求: SPORT_NOT_FIRST_AFTERNOON SOFT {course=体育} |
| C12 | 3 | 失败 | 缺少或错误的要求: SPORT_NOT_FIRST_AFTERNOON SOFT {course=体育} |
| C13 | 1 | 通过 |  |
| C13 | 2 | 通过 |  |
| C13 | 3 | 通过 |  |
| C14 | 1 | 失败 | 缺少或错误的要求: TEACHER_MAX_CONSECUTIVE HARD {maxConsecutive=3} |
| C14 | 2 | 失败 | 缺少或错误的要求: TEACHER_MAX_CONSECUTIVE HARD {maxConsecutive=3} |
| C14 | 3 | 失败 | 缺少或错误的要求: TEACHER_MAX_CONSECUTIVE HARD {maxConsecutive=3} |
| C15 | 1 | 失败 | 缺少或错误的要求: TEACHER_DAY_OFF SOFT {teacher=王明} [Slot[day=3, periodNo=null]] |
| C15 | 2 | 失败 | 缺少或错误的要求: TEACHER_DAY_OFF SOFT {teacher=王明} [Slot[day=3, periodNo=null]] |
| C15 | 3 | 失败 | 缺少或错误的要求: TEACHER_DAY_OFF SOFT {teacher=王明} [Slot[day=3, periodNo=null]] |
| C16 | 1 | 失败 | 同名教师没有作为候选请用户选择 |
| C16 | 2 | 失败 | 同名教师没有作为候选请用户选择 |
| C16 | 3 | 失败 | 同名教师没有作为候选请用户选择 |
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
| C22 | 1 | 失败 | 缺少或错误的要求: COURSE_UNAVAILABLE HARD {course=化学} [Slot[day=3, periodNo=1], Slot[day=3, periodNo=6], Slot[day=5, periodNo=1], Slot[day=5, periodNo=6]] |
| C22 | 2 | 失败 | 缺少或错误的要求: COURSE_UNAVAILABLE HARD {course=化学} [Slot[day=3, periodNo=1], Slot[day=3, periodNo=6], Slot[day=5, periodNo=1], Slot[day=5, periodNo=6]] |
| C22 | 3 | 失败 | 缺少或错误的要求: COURSE_UNAVAILABLE HARD {course=化学} [Slot[day=3, periodNo=1], Slot[day=3, periodNo=6], Slot[day=5, periodNo=1], Slot[day=5, periodNo=6]] |

### 失败明细

#### C01 第1次

原因：缺少或错误的要求: TEACHER_UNAVAILABLE HARD {teacher=王明} [Slot[day=3, periodNo=5], Slot[day=3, periodNo=6], Slot[day=3, periodNo=7], Slot[day=3, periodNo=8]]

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"王老师周三下午不排课","type":"TEACHER_UNAVAILABLE","strength":"HARD","status":"NEEDS_INPUT","explanation":"王老师对应多个教师，需要明确具体是哪一位王老师","reason":"上下文中存在多个姓王的教师（王明），无法确定具体是哪一位","teacherName":"","courseName":"","className":"","subject":"","roomType":"","days":[3],"periods":[5,6,7,8],"section":"AFTERNOON","sectionOrdinals":[1,2,3,4],"allDays":false,"values":{}}]}
```

#### C01 第2次

原因：缺少或错误的要求: TEACHER_UNAVAILABLE HARD {teacher=王明} [Slot[day=3, periodNo=5], Slot[day=3, periodNo=6], Slot[day=3, periodNo=7], Slot[day=3, periodNo=8]]

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"王老师周三下午不排课","type":"TEACHER_UNAVAILABLE","strength":"HARD","status":"NEEDS_INPUT","explanation":"王老师有多个，无法确定具体是哪位王老师","reason":"存在多个同名教师（王明），缺少具体教师全名","teacherName":"","courseName":"","className":"","subject":"","roomType":"","days":[3],"periods":[],"section":"AFTERNOON","sectionOrdinals":[1,2,3,4],"allDays":false,"values":{}}]}
```

#### C01 第3次

原因：缺少或错误的要求: TEACHER_UNAVAILABLE HARD {teacher=王明} [Slot[day=3, periodNo=5], Slot[day=3, periodNo=6], Slot[day=3, periodNo=7], Slot[day=3, periodNo=8]]

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{
  "sourceText":"王老师周三下午不排课。",
  "type":"TEACHER_UNAVAILABLE",
  "strength":"HARD",
  "status":"NEEDS_INPUT",
  "explanation":"指定王老师在周三下午不排课，但王老师有同名（王明），需要明确具体是哪位王老师。",
  "reason":"上下文中存在多位姓王的教师（王明），无法确定具体是哪位王老师。",
  "teacherName":"王",
  "courseName":"",
  "className":"",
  "subject":"",
  "roomType":"",
  "days":[3],
  "periods":[5,6,7,8],
  "section":"AFTERNOON",
  "sectionOrdinals":[1,2,3,4],
  "allDays":false,
  "values":{}
}]}
```

#### C02 第1次

原因：缺少或错误的要求: TEACHER_UNAVAILABLE HARD {teacher=王明} [Slot[day=2, periodNo=1], Slot[day=2, periodNo=2]]

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"王老师和李老师周二第1、2节都不排","type":"TEACHER_UNAVAILABLE","strength":"HARD","status":"NEEDS_INPUT","explanation":"王老师有多个，无法确定具体是哪位王老师","reason":"上下文中存在多位王老师（王明），需要明确具体指哪一位","teacherName":"王老师","courseName":"","className":"","subject":"","roomType":"NORMAL","days":[2],"periods":[1,2],"section":"MORNING","sectionOrdinals":[1,2],"allDays":false,"values":{}},{"sourceText":"王老师和李老师周二第1、2节都不排","type":"TEACHER_UNAVAILABLE","strength":"HARD","status":"READY","explanation":"李老师周二第1、2节不排课","reason":"","teacherName":"李华","courseName":"","className":"","subject":"","roomType":"NORMAL","days":[2],"periods":[1,2],"section":"MORNING","sectionOrdinals":[1,2],"allDays":false,"values":{}}]}
```

#### C03 第2次

原因：输出无法解析: 模型输出不是合法 JSON

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"每周三下午语文组统一教研，不安排课","type":"TEACHER_MEETING_BLOCKED","strength":"HARD","status":"READY","explanation":"指定学科教师在某课节统一参加教研，不排课","reason":"","teacherName":"","courseName":"","className":"","subject":"语文","roomType":"NORMAL","days":[3],"periods":[5,6,7,8],"section":"AFTERNOON","sectionOrdinals":[1,2,3,4],"allDays":false,"values":{}]}
```

#### C07 第1次

原因：缺少或错误的要求: SAME_COURSE_ONCE_PER_DAY HARD {}

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{
  "sourceText":"全部课程在同一个班一天最多上一节",
  "type":"SAME_COURSE_ONCE_PER_DAY",
  "strength":"INHERIT",
  "status":"READY",
  "explanation":"同一班级的同一课程每天最多安排一节",
  "reason":"",
  "teacherName":"",
  "courseName":"",
  "className":"",
  "subject":"",
  "roomType":"",
  "days":[],
  "periods":[],
  "section":"",
  "sectionOrdinals":[],
  "allDays":true,
  "values":{}
}]}
```

#### C08 第1次

原因：缺少或错误的要求: TEACHER_MAX_PER_DAY HARD {maxPerDay=4}

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"所有老师每天最多上4节","type":"TEACHER_MAX_PER_DAY","strength":"SOFT","status":"READY","explanation":"所有教师单日授课节数不超过4节","reason":"","teacherName":"","courseName":"","className":"","subject":"","roomType":"","days":[],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{"maxPerDay":4}},{"sourceText":"王老师最多3节","type":"TEACHER_MAX_PER_DAY","strength":"SOFT","status":"NEEDS_INPUT","explanation":"指定教师单日授课节数不超过3节","reason":"存在多个姓王的老师（王明），需要明确具体是哪位王老师","teacherName":"","courseName":"","className":"","subject":"","roomType":"","days":[],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{"maxPerDay":3}}]}
```

#### C08 第2次

原因：缺少或错误的要求: TEACHER_MAX_PER_DAY HARD {maxPerDay=4}

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"所有老师每天最多上4节","type":"TEACHER_MAX_PER_DAY","strength":"SOFT","status":"READY","explanation":"所有教师单日授课上限为4节","reason":"","teacherName":"","courseName":"","className":"","subject":"","roomType":"","days":[],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{"maxPerDay":4}},{"sourceText":"王老师最多3节","type":"TEACHER_MAX_PER_DAY","strength":"SOFT","status":"NEEDS_INPUT","explanation":"指定教师单日授课上限为3节","reason":"存在多个姓王的老师（王明），需要明确具体是哪位王老师","teacherName":"王明","courseName":"","className":"","subject":"","roomType":"","days":[],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{"maxPerDay":3}}]}
```

#### C08 第3次

原因：缺少或错误的要求: TEACHER_MAX_PER_DAY HARD {maxPerDay=4}

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"所有老师每天最多上4节","type":"TEACHER_MAX_PER_DAY","strength":"SOFT","status":"READY","explanation":"所有教师单日授课节数不超过4节","reason":"","teacherName":"","courseName":"","className":"","subject":"","roomType":"NORMAL","days":[],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{"maxPerDay":4}},{"sourceText":"王老师最多3节","type":"TEACHER_MAX_PER_DAY","strength":"SOFT","status":"NEEDS_INPUT","explanation":"指定教师单日授课节数不超过3节","reason":"存在多位姓王的教师（王明），需要确认具体是哪位王老师","teacherName":"王明","courseName":"","className":"","subject":"","roomType":"NORMAL","days":[],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{"maxPerDay":3}}]}
```

#### C09 第1次

原因：缺少或错误的要求: CLASS_MAX_PER_DAY HARD {maxPerDay=8, class=七年级1班}

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"七年级1班每天最多8节","type":"CLASS_MAX_PER_DAY","strength":"INHERIT","status":"READY","explanation":"限制七年级1班单日课程总数每天最多8节","reason":"","teacherName":"","courseName":"","className":"七年级1班","subject":"","roomType":"","days":[],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{"maxPerDay":8}},{"sourceText":"七年级2班最多7节","type":"CLASS_MAX_PER_DAY","strength":"INHERIT","status":"READY","explanation":"限制七年级2班单日课程总数每天最多7节","reason":"","teacherName":"","courseName":"","className":"七年级2班","subject":"","roomType":"","days":[],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{"maxPerDay":7}}]}
```

#### C09 第2次

原因：缺少或错误的要求: CLASS_MAX_PER_DAY HARD {maxPerDay=8, class=七年级1班}

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"七年级1班每天最多8节","type":"CLASS_MAX_PER_DAY","strength":"INHERIT","status":"READY","explanation":"限制七年级1班每天最多8节课","reason":"","teacherName":"","courseName":"","className":"七年级1班","subject":"","roomType":"NORMAL","days":[],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{"maxPerDay":8}},{"sourceText":"七年级2班最多7节","type":"CLASS_MAX_PER_DAY","strength":"INHERIT","status":"READY","explanation":"限制七年级2班每天最多7节课","reason":"","teacherName":"","courseName":"","className":"七年级2班","subject":"","roomType":"NORMAL","days":[],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{"maxPerDay":7}}]}
```

#### C09 第3次

原因：缺少或错误的要求: CLASS_MAX_PER_DAY HARD {maxPerDay=8, class=七年级1班}

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"七年级1班每天最多8节","type":"CLASS_MAX_PER_DAY","strength":"INHERIT","status":"READY","explanation":"限制七年级1班每天最多授课8节","reason":"","teacherName":"","courseName":"","className":"七年级1班","subject":"","roomType":"","days":[],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{"maxPerDay":8}},{"sourceText":"七年级2班最多7节","type":"CLASS_MAX_PER_DAY","strength":"INHERIT","status":"READY","explanation":"限制七年级2班每天最多授课7节","reason":"","teacherName":"","courseName":"","className":"七年级2班","subject":"","roomType":"","days":[],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{"maxPerDay":7}}]}
```

#### C11 第1次

原因：缺少或错误的要求: EVENING_MAX HARD {maxPerDay=2}

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"每个班每天晚自习最多2节","type":"EVENING_MAX","strength":"SOFT","status":"READY","explanation":"限制每天晚自习的课程节数","reason":"","teacherName":"","courseName":"","className":"","subject":"","roomType":"NORMAL","days":[],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{"maxPerDay":2}},{"sourceText":"九年级1班最多1节","type":"EVENING_MAX","strength":"SOFT","status":"READY","explanation":"限制指定班级每天晚自习的课程节数","reason":"","teacherName":"","courseName":"","className":"九年级1班","subject":"","roomType":"NORMAL","days":[],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{"maxPerDay":1}}]}
```

#### C11 第2次

原因：缺少或错误的要求: EVENING_MAX HARD {maxPerDay=2}

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[
  {
    "sourceText":"每个班每天晚自习最多2节",
    "type":"EVENING_MAX",
    "strength":"SOFT",
    "status":"READY",
    "explanation":"限制每天晚自习的课程节数全局默认最多2节",
    "reason":"",
    "teacherName":"",
    "courseName":"",
    "className":"",
    "subject":"",
    "roomType":"NORMAL",
    "days":[],
    "periods":[],
    "section":"",
    "sectionOrdinals":[],
    "allDays":false,
    "values":{"maxPerDay":2}
  },
  {
    "sourceText":"九年级1班最多1节",
    "type":"EVENING_MAX",
    "strength":"SOFT",
    "status":"READY",
    "explanation":"限制九年级1班每天晚自习最多1节",
    "reason":"",
    "teacherName":"",
    "courseName":"",
    "className":"九年级1班",
    "subject":"",
    "roomType":"NORMAL",
    "days":[],
    "periods":[],
    "section":"",
    "sectionOrdinals":[],
    "allDays":false,
    "values":{"maxPerDay":1}
  }
]}
```

#### C11 第3次

原因：缺少或错误的要求: EVENING_MAX HARD {maxPerDay=2}

原始输出（截断展示，完整内容见 JSON）：
```
{
  "proposals": [
    {
      "sourceText": "每个班每天晚自习最多2节",
      "type": "EVENING_MAX",
      "strength": "SOFT",
      "status": "READY",
      "explanation": "限制每天晚自习的课程节数全局默认最多2节",
      "reason": "",
      "teacherName": "",
      "courseName": "",
      "className": "",
      "subject": "",
      "roomType": "",
      "days": [],
      "periods": [],
      "section": "",
      "sectionOrdinals": [],
      "allDays": true,
      "values": {
        "maxPerDay": 2
      }
    },
    {
      "sourceText": "九年级1班最多1节",
      "type": "EVENING_MAX",
      "strength": "SOFT",
      "status": "READY",
      "explanation": "限制九年级1班每天晚自习最多1节",
      "reason": "",
      "teacherName": "",
      "courseName": "",
      "className": "九年级1班",
      "subject": "",
      "roomType": "",
      "days": [],
      "periods": [],
      "section": "",
      "sectionOrdinals": [],
      "allDays": true,
      "values": {
        "maxPerDay": 1
      }
    }
  ]
}
```

#### C12 第1次

原因：缺少或错误的要求: SPORT_NOT_FIRST_AFTERNOON SOFT {course=体育}

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{
  "sourceText":"体育尽量不要排下午第一节",
  "type":"PE_LAST_PERIOD_AVOID",
  "strength":"SOFT",
  "status":"UNSUPPORTED",
  "explanation":"该约束类型表示体育不在最后一节，无法直接表达体育不在下午第一节",
  "reason":"目录中没有直接对应“体育不在下午第一节”的约束类型，不能用“PE_LAST_PERIOD_AVOID（体育不在最后一节）”代替",
  "teacherName":"",
  "courseName":"体育",
  "className":"",
  "subject":"体育",
  "roomType":"PLAYGROUND",
  "days":[],
  "periods":[],
  "section":"AFTERNOON",
  "sectionOrdinals":[1],
  "allDays":false,
  "values":{}
}]}
```

#### C12 第2次

原因：缺少或错误的要求: SPORT_NOT_FIRST_AFTERNOON SOFT {course=体育}

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"体育尽量不要排下午第一节","type":"PE_LAST_PERIOD_AVOID","strength":"SOFT","status":"UNSUPPORTED","explanation":"该类型为体育课避免安排在当天最后一节，与下午第一节不符","reason":"无对应的体育课避免排下午第一节的约束类型","teacherName":"","courseName":"体育","className":"","subject":"体育","roomType":"PLAYGROUND","days":[],"periods":[],"section":"AFTERNOON","sectionOrdinals":[1],"allDays":false,"values":{}}]}
```

#### C12 第3次

原因：缺少或错误的要求: SPORT_NOT_FIRST_AFTERNOON SOFT {course=体育}

原始输出（截断展示，完整内容见 JSON）：
```
{
  "proposals": [
    {
      "sourceText": "体育尽量不要排下午第一节。",
      "type": "PE_LAST_PERIOD_AVOID",
      "strength": "SOFT",
      "status": "UNSUPPORTED",
      "explanation": "体育尽量不要排下午第一节。",
      "reason": "系统不支持下午第一节限制，PE_LAST_PERIOD_AVOID 仅支持避免最后一节，不得扩大或缩小作用范围。",
      "teacherName": "",
      "courseName": "体育",
      "className": "",
      "subject": "",
      "roomType": "PLAYGROUND",
      "days": [],
      "periods": [],
      "section": "AFTERNOON",
      "sectionOrdinals": [1],
      "allDays": false,
      "values": {}
    }
  ]
}
```

#### C14 第1次

原因：缺少或错误的要求: TEACHER_MAX_CONSECUTIVE HARD {maxConsecutive=3}

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[
  {
    "sourceText":"王老师每天最多连上2节",
    "type":"TEACHER_MAX_CONSECUTIVE",
    "strength":"SOFT",
    "status":"NEEDS_INPUT",
    "explanation":"王老师有两位（王明），缺少具体指代",
    "reason":"上下文存在多个姓王的老师（王明），无法确定具体指代哪个王老师",
    "teacherName":"王明",
    "courseName":"",
    "className":"",
    "subject":"",
    "roomType":null,
    "days":[],
    "periods":[],
    "section":null,
    "sectionOrdinals":[],
    "allDays":false,
    "values":{"maxConsecutive":2}
  },
  {
    "sourceText":"其他老师最多3节",
    "type":"TEACHER_MAX_CONSECUTIVE",
    "strength":"SOFT",
    "status":"READY",
    "explanation":"全局其他老师连续上课上限",
    "reason":"",
    "teacherName":"",
    "courseName":"",
    "className":"",
    "subject":"",
    "roomType":null,
    "days":[],
    "periods":[],
    "section":null,
    "sectionOrdinals":[],
    "allDays":false,
    "values":{"maxConsecutive":3}
  }
]}
```

#### C14 第2次

原因：缺少或错误的要求: TEACHER_MAX_CONSECUTIVE HARD {maxConsecutive=3}

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"王老师每天最多连上2节","type":"TEACHER_MAX_CONSECUTIVE","strength":"SOFT","status":"READY","explanation":"指定王老师连续授课不超过2节","reason":"","teacherName":"王明","courseName":"","className":"","subject":"","roomType":"NORMAL","days":[],"periods":[],"section":"MORNING","sectionOrdinals":[],"allDays":false,"values":{"maxConsecutive":2}},{"sourceText":"其他老师最多3节","type":"TEACHER_MAX_CONSECUTIVE","strength":"SOFT","status":"READY","explanation":"全局教师连续授课不超过3节","reason":"","teacherName":"","courseName":"","className":"","subject":"","roomType":"NORMAL","days":[],"periods":[],"section":"MORNING","sectionOrdinals":[],"allDays":false,"values":{"maxConsecutive":3}}]}
```

#### C14 第3次

原因：缺少或错误的要求: TEACHER_MAX_CONSECUTIVE HARD {maxConsecutive=3}

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"王老师每天最多连上2节","type":"TEACHER_MAX_CONSECUTIVE","strength":"SOFT","status":"NEEDS_INPUT","explanation":"王老师重名，上下文中有王明，无法确定具体指哪位王老师","reason":"存在多个同名教师（王明），无法确定具体指哪位","teacherName":"王老师","courseName":"","className":"","subject":"","roomType":"","days":[],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{"maxConsecutive":2}},{"sourceText":"其他老师最多3节","type":"TEACHER_MAX_CONSECUTIVE","strength":"SOFT","status":"READY","explanation":"其他老师连续上课上限为3节","reason":"","teacherName":"","courseName":"","className":"","subject":"","roomType":"","days":[],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{"maxConsecutive":3}}]}
```

#### C15 第1次

原因：缺少或错误的要求: TEACHER_DAY_OFF SOFT {teacher=王明} [Slot[day=3, periodNo=null]]

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"王老师每周三尽量整天无课。","type":"TEACHER_DAY_OFF","strength":"SOFT","status":"NEEDS_INPUT","explanation":"王老师有两位（张伟甲和王明），无法确定具体指哪一位。","reason":"存在多个姓王的老师（张伟甲、王明），请明确是哪位王老师。","teacherName":"","courseName":"","className":"","subject":"","days":[3],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{}}]}
```

#### C15 第2次

原因：缺少或错误的要求: TEACHER_DAY_OFF SOFT {teacher=王明} [Slot[day=3, periodNo=null]]

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"王老师每周三尽量整天无课。","type":"TEACHER_DAY_OFF","strength":"SOFT","status":"NEEDS_INPUT","explanation":"王老师有多个，无法确定具体是哪位王老师","reason":"上下文中存在多个姓王的教师（王明），无法确定“王老师”指代谁。","teacherName":"","courseName":"","className":"","subject":"","roomType":"NORMAL","days":[3],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{}}]}
```

#### C15 第3次

原因：缺少或错误的要求: TEACHER_DAY_OFF SOFT {teacher=王明} [Slot[day=3, periodNo=null]]

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"王老师每周三尽量整天无课。","type":"TEACHER_DAY_OFF","strength":"SOFT","status":"NEEDS_INPUT","explanation":"王老师每周三固定无课","reason":"上下文中存在多个姓王的老师（王明），无法确定具体是哪位王老师","teacherName":"王老师","courseName":"","className":"","subject":"","roomType":"NORMAL","days":[3],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{}}]}
```

#### C16 第1次

原因：同名教师没有作为候选请用户选择

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"张老师周三下午不排","type":"TEACHER_UNAVAILABLE","strength":"HARD","status":"NEEDS_INPUT","explanation":"张老师姓名不唯一，无法确定是张伟甲还是张伟乙","reason":"上下文中存在多个张老师（张伟甲、张伟乙），无法确定具体指哪一位","teacherName":"","courseName":"","className":"","subject":"","roomType":"NORMAL","days":[3],"periods":[5,6,7,8],"section":"AFTERNOON","sectionOrdinals":[1,2,3,4],"allDays":false,"values":{}},{"sourceText":"体育不排晚自习","type":"COURSE_NOT_IN_EVENING","strength":"HARD","status":"READY","explanation":"体育课不安排在晚自习时段","reason":"","teacherName":"","courseName":"体育","className":"","subject":"","roomType":"NORMAL","days":[],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{}}]}
```

#### C16 第2次

原因：同名教师没有作为候选请用户选择

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"张老师周三下午不排","type":"TEACHER_UNAVAILABLE","strength":"HARD","status":"NEEDS_INPUT","explanation":"张伟甲和张伟乙两位教师重名，无法确定具体是哪位张老师","reason":"存在多个同名教师（张伟甲、张伟乙），无法确定具体指哪一位","teacherName":"","courseName":"","className":"","subject":"","days":[3],"periods":[5,6,7,8],"section":"AFTERNOON","sectionOrdinals":[1,2,3,4],"allDays":false,"values":{}},{"sourceText":"体育不排晚自习","type":"COURSE_NOT_IN_EVENING","strength":"HARD","status":"READY","explanation":"体育课不能安排在晚自习时段","teacherName":"","courseName":"体育","className":"","subject":"","days":[],"periods":[9,10],"section":"EVENING","sectionOrdinals":[1,2],"allDays":false,"values":{}}]}
```

#### C16 第3次

原因：同名教师没有作为候选请用户选择

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"张老师周三下午不排","type":"TEACHER_UNAVAILABLE","strength":"HARD","status":"NEEDS_INPUT","explanation":"张老师姓名存在歧义（张伟甲、张伟乙），需要明确","reason":"存在多个同名教师（张伟甲、张伟乙），无法确定具体指哪一位","teacherName":"","courseName":"","className":"","subject":"","roomType":"NORMAL","days":[3],"periods":[5,6,7,8],"section":"AFTERNOON","sectionOrdinals":[1,2,3,4],"allDays":false,"values":{}},{"sourceText":"体育不排晚自习","type":"COURSE_NOT_IN_EVENING","strength":"HARD","status":"READY","explanation":"指定课程不安排在晚自习时段","reason":"","teacherName":"","courseName":"体育","className":"","subject":"","roomType":"NORMAL","days":[],"periods":[],"section":"","sectionOrdinals":[],"allDays":false,"values":{}}]}
```

#### C22 第1次

原因：缺少或错误的要求: COURSE_UNAVAILABLE HARD {course=化学} [Slot[day=3, periodNo=1], Slot[day=3, periodNo=6], Slot[day=5, periodNo=1], Slot[day=5, periodNo=6]]

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[{"sourceText":"化学周三上午第1节不排。","type":"COURSE_UNAVAILABLE","strength":"HARD","status":"READY","explanation":"化学周三上午第1节不排","reason":"","teacherName":"","courseName":"化学","className":"","subject":"","roomType":"","days":[3],"periods":[1],"section":"MORNING","sectionOrdinals":[1],"allDays":false,"values":{}},{"sourceText":"化学周三下午第2节不排。","type":"COURSE_UNAVAILABLE","strength":"HARD","status":"READY","explanation":"化学周三下午第2节不排","reason":"","teacherName":"","courseName":"化学","className":"","subject":"","roomType":"","days":[3],"periods":[6],"section":"AFTERNOON","sectionOrdinals":[2],"allDays":false,"values":{}},{"sourceText":"化学周五上午第1节不排。","type":"COURSE_UNAVAILABLE","strength":"HARD","status":"READY","explanation":"化学周五上午第1节不排","reason":"","teacherName":"","courseName":"化学","className":"","subject":"","roomType":"","days":[5],"periods":[1],"section":"MORNING","sectionOrdinals":[1],"allDays":false,"values":{}},{"sourceText":"化学周五下午第2节不排。","type":"COURSE_UNAVAILABLE","strength":"HARD","status":"READY","explanation":"化学周五下午第2节不排","reason":"","teacherName":"","courseName":"化学","className":"","subject":"","roomType":"","days":[5],"periods":[6],"section":"AFTERNOON","sectionOrdinals":[2],"allDays":false,"values":{}}]}
```

#### C22 第2次

原因：缺少或错误的要求: COURSE_UNAVAILABLE HARD {course=化学} [Slot[day=3, periodNo=1], Slot[day=3, periodNo=6], Slot[day=5, periodNo=1], Slot[day=5, periodNo=6]]

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[
  {
    "sourceText":"化学周三上午第1节不排",
    "type":"COURSE_UNAVAILABLE",
    "strength":"HARD",
    "status":"READY",
    "explanation":"指定化学在周三上午第1节不排课",
    "reason":"",
    "teacherName":"",
    "courseName":"化学",
    "className":"",
    "subject":"",
    "roomType":"NORMAL",
    "days":[3],
    "periods":[1],
    "section":"MORNING",
    "sectionOrdinals":[1],
    "allDays":false,
    "values":{}
  },
  {
    "sourceText":"化学周三下午第2节不排",
    "type":"COURSE_UNAVAILABLE",
    "strength":"HARD",
    "status":"READY",
    "explanation":"指定化学在周三下午第2节不排课",
    "reason":"",
    "teacherName":"",
    "courseName":"化学",
    "className":"",
    "subject":"",
    "roomType":"NORMAL",
    "days":[3],
    "periods":[6],
    "section":"AFTERNOON",
    "sectionOrdinals":[2],
    "allDays":false,
    "values":{}
  },
  {
    "sourceText":"化学周五上午第1节不排",
    "type":"COURSE_UNAVAILABLE",
    "strength":"HARD",
    "status":"READY",
    "explanation":"指定化学在周五上午第1节不排课",
    "reason":"",
    "teacherName":"",
    "courseName":"化学",
    "className":"",
    "subject":"",
    "roomType":"NORMAL",
    "days":[5],
    "periods":[1],
    "section":"MORNING",
    "sectionOrdinals":[1],
    "allDays":false,
    "values":{}
  },
  {
    "sourceText":"化学周五下午第2节不排",
    "type":"COURSE_UNAVAILABLE",
    "strength":"HARD",
    "status":"READY",
    "explanation":"指定化学在周五下午第2节不排课",
    "reason":"",
    "teacherName":"",
    "courseName":"化学",
    "className":"",
    "subject":"",
    "roomType":"NORMAL",
    "days":[5],
    "periods":[6],
    "section":"AFTERNOON",
    "sectionOrdinals":[2],
    "allDays":false,
    "values":{}
  }
]}
```

#### C22 第3次

原因：缺少或错误的要求: COURSE_UNAVAILABLE HARD {course=化学} [Slot[day=3, periodNo=1], Slot[day=3, periodNo=6], Slot[day=5, periodNo=1], Slot[day=5, periodNo=6]]

原始输出（截断展示，完整内容见 JSON）：
```
{"proposals":[
  {
    "sourceText":"化学周三、周五上午第1节都不排。",
    "type":"COURSE_UNAVAILABLE",
    "strength":"HARD",
    "status":"READY",
    "explanation":"化学在周三上午第1节不排课",
    "reason":"",
    "teacherName":"",
    "courseName":"化学",
    "className":"",
    "subject":"",
    "roomType":"NORMAL",
    "days":[3],
    "periods":[1],
    "section":"MORNING",
    "sectionOrdinals":[1],
    "allDays":false,
    "values":{}
  },
  {
    "sourceText":"化学周三、周五上午第1节都不排。",
    "type":"COURSE_UNAVAILABLE",
    "strength":"HARD",
    "status":"READY",
    "explanation":"化学在周五上午第1节不排课",
    "reason":"",
    "teacherName":"",
    "courseName":"化学",
    "className":"",
    "subject":"",
    "roomType":"NORMAL",
    "days":[5],
    "periods":[1],
    "section":"MORNING",
    "sectionOrdinals":[1],
    "allDays":false,
    "values":{}
  },
  {
    "sourceText":"化学周三、周五下午第2节都不排。",
    "type":"COURSE_UNAVAILABLE",
    "strength":"HARD",
    "status":"READY",
    "explanation":"化学在周三下午第2节不排课",
    "reason":"",
    "teacherName":"",
    "courseName":"化学",
    "className":"",
    "subject":"",
    "roomType":"NORMAL",
    "days":[3],
    "periods":[6],
    "section":"AFTERNOON",
    "sectionOrdinals":[2],
    "allDays":false,
    "values":{}
  },
  {
    "sourceText":"化学周三、周五下午第2节都不排。",
    "type":"COURSE_UNAVAILABLE",
    "strength":"HARD",
    "status":"READY",
    "explanation":"化学在周五下午第2节不排课",
    "reason":"",
    "teacherName":"",
    "courseName":"化学",
    "className":"",
    "subject":"",
    "roomType":"NORMAL",
    "days":[5],
    "periods":[6],
    "section":"AFTERNOON",
    "sectionOrdinals":[2],
    "allDays":false,
    "values":{}
  }
]}
```

