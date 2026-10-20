# 技术栈：Java 21 + Spring Boot 3 + Timefold Solver + Vue 3 + PostgreSQL

后端采用 Java 21 + Spring Boot 3，排课引擎内嵌 Timefold Solver（同 JVM 进程调用）；前端 Vue 3 + TypeScript + Vite + Element Plus，课表矩阵基于 CSS Grid 自研；持久层 PostgreSQL；钉钉集成走 H5 微应用形态（复用同一套前端），通过 Stream 模式接收组织变更事件。

## 为什么

基于"稳定性 ≈ 可维护性 > 运行速度 >> 迭代速度"的权重：

- **稳定性**：JVM 是长期运行最稳的运行时；Timefold 同进程内嵌，无跨进程调用故障点。
- **可维护性（3-5 年后视角）**：Java 强类型 + Spring 生态向后兼容，让未来陌生维护者（学校信息科 / 教育信息化公司）接手成本最低；国内中学教务系统 Java 是绝对主流。
- **运行速度**：中学规模（~50 班、~500 授课任务）的求解在 Timefold 下是秒级到十秒级，Java/Python 差异不构成决策权重。
- **不追求迭代速度**：Java 开发慢这一最大缺点被用户明确豁免。

## Considered Options

- **NestJS (TS) 业务后端 + Python 算法微服务**：开发速度最快、AI 编码最友好，但两进程两语言栈的运维与维护成本与项目"一次部署跑多年"的画像冲突，被否。
- **全 Python (FastAPI + OR-Tools)**：单语言栈简单、AI 生态亲和，但 Python 动态类型在长期维护上最弱；OR-Tools 是通用求解器，需自建领域层，而 Timefold 是为排课/排班场景专门设计的。
- **FET 作为算法内核**：欧洲中小学事实标准，但 C++/Qt 桌面应用 + XML 文件通信的形态与现代 Web 服务架构不契合，被否。
- **直接 fork 国内开源项目**（CourseArragement 等）：均为学生毕设级遗传算法 demo，无钉钉/飞书集成、无 AI 接口、算法面对真实中学规则不收敛，不可用。

## Consequences

- Timefold 的 `@PlanningEntity` / Constraint Streams 模型将塑造核心领域模型的形状，建模时需遵循其范式。
- 钉钉 SDK 用官方 Java 版本（更新最勤）。
- 前端课表矩阵不依赖 FullCalendar 等通用日历组件（它们是时间轴视图，不是固定网格），自研 CSS Grid 实现"上午4+下午4+晚自习"的中学固定节次结构。
