# Chapter 17: dev-flow——多 Agent R&D 流水线实战

> 核心命题：AI 辅助研发的瓶颈不在 AI 能力，而在工程约束——dev-flow 以 7 个专职 SubAgent + 1 个 Master Agent 覆盖 ONES 工单到知识沉淀的完整研发闭环，每个 Agent 只做一件事、做好一件事，差距由工程结构决定。

---

## 17.0 导读：系统全貌

dev-flow 是以 Phase Gate 为骨架、以可回溯中间产物为血肉的 Multi-Agent R&D 流水线，核心设计思想源自 phf-dev V2。

- **全链路覆盖**：ONES工单 → `dev-analyst` → `dev-designer` → `dev-planner` → `dev-coder × N` → `dev-tester` → `dev-deployer` → `dev-qa` → `dev-reviewer` → 学城，每个箭头对应一个 SubAgent 或 Master 的 Phase Gate 检查点
- **项目结构**：`.claude/commands/`（Slash Command 前端）/ `dev-flow/agents/`（每个 SubAgent 的 System Prompt）/ `dev-flow/master.md`（Master Agent 编排逻辑）
- **四项设计原则**：Phase Gate（阶段门控，不达标不推进）/ 可回溯中间产物（`requirements.md`/`design.md`/`tasks.md` 写到文件而非内存）/ 最小权限（每个 Agent 只拥有本阶段所需工具）/ 人机协作点（高置信度自动推进，低置信度暂停等人）
- **核心洞见**：「每个 Agent 只做一件事」不是哲学口号，而是工程约束——单职责使上下文聚焦、使失败定位精确、使并行成为可能

---

## 17.1 dev-analyst：需求分析 Agent

需求分析的本质是把「用户想要什么」提炼为 AI 可驱动的规格文档，置信度阈值是阻止模糊需求流入研发的第一道闸门。

- **前置步骤**：`fsd req detail -i <reqId>`（拉取需求详情）→ `fsd task create`（创建开发任务）→ `fsd task create-branch`（创建 Git 分支）→ `fsd delivery create`（创建服务端交付记录）；这四步在调用 `dev-analyst` 之前由 `/fsd-create` 命令完成
- **ONES MCP Server**：基于 FastMCP 实现，提供四个只读工具——`get_ones_issue`/`get_ones_issue_children`/`get_ones_issue_comments`/`search_related_issues`；全部只读，不允许写 ONES 数据
- **置信度阈值机制**：`≥80%` 自动推进；`<80%` 暂停流水线并在 ONES 工单添加澄清评论；`<70%` 在 `requirements.md` 顶部追加红色警告块
- **System Prompt 约束**：只描述 `What`（做什么），不涉及 `How`（怎么做）；所有不确定点必须显式标记，不得推测填充
- **文件化输出的三重价值**：`requirements.md` 写到文件而非直接传递给下游——可审计（人工查看）/ 可恢复（流水线重跑从文件读取）/ 可介入（人工修改后再推进）

---

## 17.2 dev-designer：技术设计 Agent

技术设计阶段的最大风险是 AI 不了解存量代码库就开始「拍脑袋设计」——Plan Agent 只读探索 + 「LGTM」人工确认是两道护栏。

- **12 模块 `design.md`**：ADR（架构决策）/ 系统架构 / API Contract / Data Model / 核心流程 / 异常处理 / 安全设计 / 性能设计 / 测试策略 / 部署方案 / 监控告警 / 风险清单；结构即飞行检查单，缺一模块则设计不完整
- **Plan Agent 只读探索**：在 `planAgent.ts` 中通过 `disallowedTools` 彻底禁止写操作；输出必须包含「Critical Files for Implementation」列表，供 `dev-coder` 精确定位入口文件
- **LGTM 等待机制**：Master Agent 轮询 ONES 评论，检测到「LGTM」字符串才推进到 `dev-planner`；人适配 AI 的工作流，而非 AI 适配人的界面习惯
- **ASCII Art 优先于 Mermaid**：ONES 工单 / 学城 KM / Gerrit 代码审查均不渲染 Mermaid；ASCII 流程图在所有平台均可阅读，避免信息传递断层
- **`dev-designer` 自身权限**：可使用 `bash_tool` 执行只读命令（`cat`/`grep`/`find`/`git log`），满足读取存量代码的探索需求，但禁止任何写操作

---

## 17.3 dev-planner：任务分解 Agent

任务粒度是研发工程的隐性架构决策——0.5–3 天是认知负荷与可并行性的平衡点，超出这个范围要么不可测试要么不值得独立追踪。

- **五步分解法**：① 按技术分层切割（8 层层次结构：DB Migration → 数据层 → 服务层 → API 层 → 前端 → 联调 → 测试 → 文档）→ ② 校验粒度（min 0.5 天，max 3 天）→ ③ 识别依赖关系 → ④ 计算关键路径 → ⑤ 补充非功能任务清单
- **YAML 格式直连 ONES API**：`tasks.md` 中 `workdays` 字段直接转换为 ONES `estimate`（1 天 = 28800 秒），任务标题/描述字段映射到 ONES 对应字段，避免人工录入
- **非功能任务清单**：DB Migration 脚本 / 配置变更说明 / API 文档更新 / 监控告警配置 / Feature Flag 开关 / Code Review 任务；这些任务在功能需求评估阶段容易遗漏，由 System Prompt 强制检查
- **拓扑排序建 ONES 任务**：使用 NetworkX DAG + `topological_generations` 进行并行分组，`identify_parallel_groups()` 返回可并行执行的任务批次，Master Agent 据此控制 `dev-coder` 的并发数
- **质量自检指标**：`critical_path_days ÷ total_workdays < 60%`——若比率过高说明任务串行化过度，并行化机会被浪费

---

## 17.4 dev-coder：编码执行 Agent

`max_turns=8` 是驱动任务粒度设计的工程约束，而非随意参数——单个编码任务不超过 3 个文件变更，才能在 8 轮内高质量完成。

- **五步 Spec Coding 流程**：`READ_SPEC`（读 `design.md` 和 `tasks.md`）→ `PLAN`（列出将要修改的文件和变更要点）→ `IMPLEMENT`（逐文件实现）→ `VERIFY`（`mvn compile` 编译验证）→ `MARK_DONE`（写 `.dev-progress/{TASK_ID}.done`）
- **FileEditTool 三铁律**：必须先 `FileReadTool` 再编辑 / `old_string` 必须在文件中唯一 / `normalizeQuotes()` 自动纠正中文弯引号为直引号，防止编码错误导致编译失败
- **进度文件格式**：`.dev-progress/{TASK_ID}.done` 包含状态 `DONE`/`FAILED`/`NEEDS_CLARIFICATION`、时间戳、`files_created` 列表、`files_modified` 列表；Master Agent 通过轮询此文件实现异步并行调度
- **三铁禁令**：不越界（只修改本任务 `task.scope` 内的文件）/ 不猜测（Spec 不清晰时写 `NEEDS_CLARIFICATION` 而非推测实现）/ 不写测试（测试是 `dev-tester` 的职责）
- **BashTool 白名单**：允许 `mvn compile`/`./gradlew compileJava`/`java` 冒烟测试；禁止 `git push`/`curl`/`kubectl` 等产生外部副作用的命令；`max_parallel=3` 避免 API 速率超限

---

## 17.5 dev-tester：测试设计 Agent

测试的独立性来自阅读规格而非实现——读 `design.md`（设计文档）而不是 `src/main/java/`（实现代码），是防止「测试镜像实现」反模式的根本机制。

- **测试设计矩阵**：每个测试类顶部的注释块记录三类测试场景——正常流（Happy Path）/ 异常流（Error Path）/ 边界值（Boundary）；覆盖度要求：每类至少 3 个用例
- **`BOUNDARY_AMBIGUOUS` 处理**：规格中边界定义模糊时，同时生成 `_inclusive` 和 `_exclusive` 两个变体，均标注 `// TODO: confirm boundary direction` 注释，并在 `.done` 文件的 `ambiguities` 字段记录，由人工确认后删除其中一个
- **发现 Bug 的处理规范**：在生产代码中发现 Bug 时，记录到 `.dev-progress/test-{taskId}.done` 的 `notes` 字段，**绝对不修改** `src/main/java/`；Bug 修复是 `dev-coder` 的职责
- **覆盖率追踪**：`.done` 文件中 `coverage: X%/Y%`（行覆盖率/分支覆盖率）+ `test_count`；AssertJ 优先于 JUnit 5 原生断言；`assertThatThrownBy` 链式风格统一异常测试
- **突变测试进阶**：`mvn org.pitest:pitest-maven:mutationCoverage` 目标突变覆盖率 `≥60%`；突变测试发现的「存活突变体」说明测试用例覆盖了代码行但未验证行为，是比行覆盖率更严格的质量指标

---

## 17.6 dev-deployer + dev-qa：部署与验收 Agent

部署失败的最大成本是时间浪费——15 分钟构建失败是可预防的，前置一个单元测试门控就能把浪费拦在入口。

- **Fail Fast 前检查**：`mvn dependency:resolve -DincludeScope=compile` 在正式构建前验证依赖可解析；`SKIP_TEST_GATE=true` 环境变量为热修复场景提供逃生舱，正常流程不得使用
- **Health Check 规范**：必须调用 `/actuator/health` 接口，不得用 TCP 端口探测——Spring Boot 2 阶段启动（端口监听早于应用就绪），TCP 探测会误报「部署成功」
- **泳道路由**：`X-Lane-Id` 请求头全链路透传；泳道版本存在则路由到泳道实例，否则降级到基线；`deploy-config.json` 记录泳道配置，`dev-deployer` 读取并写入 `deploy-status.json`
- **文件握手协议**：`deploy-config.json`（输入）→ `dev-deployer` 执行 → `deploy-status.json`（中间状态）→ `dev-qa` 读取 → `qa-report-*.md`（输出）；任意文件不存在则对应 Agent 拒绝启动
- **三类验证**：功能验证（核心业务流程正确）/ 异常验证（异常路径返回预期错误码）/ 性能基线（P99 不超过基线 110%）；三类全部通过才写 `qa-report-*.md` 标记 QA 完成

---

## 17.7 dev-reviewer：代码审查 Agent

AI Code Review 的差异化价值不在于替代 Lint，而在于跨文件语义分析——N+1 查询、业务逻辑完整性、安全边界这三类问题需要「读懂逻辑」才能发现。

- **8 项检查清单**：4 个 BLOCKER（需求覆盖率 / 接口安全 / DB 变更脚本 / 联调验收记录）+ 4 个 WARNING（单测覆盖率 / 代码规范 / 配置变更说明 / 性能风险预警）；BLOCKER 不清零不允许合码
- **语义级检查示例**：循环内 ORM 调用 → 识别 N+1 查询 / 状态流转未覆盖所有分支 → 业务逻辑不完整 / 外部输入未经校验直接入 SQL → SQL 注入风险；这三类静态分析工具无法检测
- **知识沉淀 CLI**：`oa-skills citadel create --parent-id {team_km_parent_id} --title "..." --content {file}` 返回 `https://km.sankuai.com/collabpage/{page_id}`，把每次 Review 产出的知识自动入库到团队知识库
- **KM 文档六节结构**：🗺️ 功能定位 → 🔑 核心决策（ADR 摘要）→ ⚠️ 踩坑记录（含解决方案）→ 📊 性能基线 → 🔧 复用指南 → 📎 相关资源；结构化模板确保知识可检索
- **AI 写文档的三个优势**：无「知识诅咒」（不会默认读者已知背景）/ 不嫌繁琐（人类嫌写文档麻烦，AI 没有此情绪）/ 强制结构（模板输出比自由发挥的文档一致性高 10 倍）

---

## 17.8 Command 系统：用户接口层

Slash Command 是 SubAgent 的前端——Command 运行在主会话（有聊天历史访问权），SubAgent 运行在隔离会话（有专注上下文），两层分离实现「调用简单」与「执行专注」的统一。

- **Command 加载机制**：`.claude/commands/*.md` 文件名即命令名；`$ARGUMENTS` 占位符在执行时替换为用户输入；`init-dev-commands.sh` 脚本一键创建目录结构和初始命令文件
- **5 条命令的 80/20 设计**：`/dev-start`（全流程，覆盖 80% 场景）+ 4 个高频局部操作（`/dev-analyze`/`/dev-code`/`/dev-test`/`/dev-review`）；`/dev-design` 和 `/dev-deploy` 有意省略（单独频率低且与前后步骤强耦合）
- **粒度梯度原则**：`/dev-start` 是最粗粒度（全流程触发）；4 个子命令是中粒度（阶段级触发）；直接调用 SubAgent System Prompt 是最细粒度（调试用）；用户按需选择粒度
- **Java 类比**：Command ≈ Maven Plugin Goal（用户调用入口）；SubAgent ≈ Mojo（实际执行单元）；`.dev-progress/` ≈ `target/`（构建中间产物目录）
- **隔离边界的价值**：SubAgent 在独立会话运行，看不到主会话的对话历史，上下文完全由 System Prompt 和输入文件决定——避免「污染上下文」导致的行为漂移

---

## 17.9 Master Agent：编排与 Phase Gate

Master Agent 是 ch10 Coordinator 框架的应用层实例——`phase-gate.json` 状态机将 7 个阶段的推进规则外化为可持久化、可回滚的工程制品。

- **`CLAUDE_CODE_COORDINATOR_MODE=1`**：激活 `coordinatorMode.ts` 的 Coordinator 模式；Master Agent 不直接执行任务，只负责 Phase Gate 判断、SubAgent 调度、状态持久化
- **`phase-gate.json` 结构**：7 个阶段，每阶段记录 `status`/`retry_count`/`agent`/`output`/`completed_at`；原子写法——先写 `.tmp` 文件，再 `rename` 到目标路径，防止崩溃导致状态文件损坏
- **Gate 条件示例**：`testing` 阶段推进条件为 `pass_rate=100%` AND `line_coverage≥80%`；条件不满足则 `retry_count++`，超过重试上限后触发人工干预点
- **SubAgent 完成标记协议**：SubAgent 输出中包含结构化完成标记（如 `DESIGN_COMPLETE`/`TESTS_COMPLETE`），Master Agent 通过解析此标记判断阶段是否完成，而非靠进程退出码
- **ch10 vs ch17 对比**：ch10 Coordinator = 框架层（动态编排，通用）；ch17 Master Agent = 应用层（固定 7 阶段顺序，专用）；ch10 用运行时上下文判断；ch17 用 `phase-gate.json` 持久化状态；ch10 基础重试；ch17 限次重试 + 阶段回滚 + 人工干预点

---

## 17.10 harness-dev-flow 对比：工程约束决定可靠性

同样的 AI 模型，有工程约束的 dev-flow 和无结构的 harness 之间的差距，在 5 个具体节点上可量化——工具只是工具，工程约束决定工具在复杂场景里是否可靠。

- **节点一（需求澄清）**：harness：模糊需求在 QA 阶段才暴露 → 1.5 天返工；dev-flow：`dev-planner` 强制确认，澄清成本 1 小时
- **节点二（上下文稀释）**：harness：第 47 轮对话上下文稀释，调用错误接口；dev-flow：每个编码任务独立 SubAgent，`process.txt` 携带关键上下文，无稀释
- **节点三（自我审查盲区）**：harness：自我 Review 73% 覆盖率，空列表分支遗漏；dev-flow：独立 `dev-reviewer` 从业务文档视角审查，盲区不重叠
- **节点四（安全视角缺失）**：harness：安全问题上线后第 3 天发现；dev-flow：`dev-reviewer` 专项安全检查清单，上线前发现
- **节点五（知识传承）**：harness：第二个 Feature 需要 25 分钟上下文说明；dev-flow：`AGENTS.md` 自动继承，2 分钟开始编码
- **核心公式**：`可靠性 = AI能力 × 工程约束` ——约束为零则可靠性无法保证，无论模型多强

---

## 17.11 生产落地：成本、韧性与团队推广

生产级 Multi-Agent 系统的工程韧性设计不是「防止 AI 犯错」，而是「让错误可感知、可恢复、代价有限」——ROI >100x 的关键是把 9 小时人工降到 ¥6 的 token 成本。

- **Token 预算三层控制**：单次请求（32k context / 8k output）/ 单任务累计（500k total）/ 按天/月（API Console Dashboard 配置告警阈值）；超出单任务预算时终止并写 `BUDGET_EXCEEDED` 状态
- **重试策略**：指数退避 `[10s, 30s, 60s]`；最大重试 3 次；重试时注入上次失败原因到新上下文（不是重复同样请求）；`exemption_patterns` 豁免遗留代码/生成代码/Mock 文件的覆盖率要求
- **超时设置原则**：测量 P90 执行时长，设置为 P90 × 1.5；不得设置任意大值（如 `timeout: 3600s`），避免卡死任务占用资源
- **权限最小化**：每个 SubAgent 使用独立 API Key；`DEPLOYER_K8S_CONTEXT=staging-only` 限制 `dev-deployer` 只能操作 Staging 环境；高风险操作（生产部署）设 `requires_human_approval: true`
- **幂等性设计**：`task_id` 作为文件前缀避免重复创建；`skip_if_output_exists: true` 跳过已完成阶段；用 `kubectl apply` 而非 `kubectl create`，保证重跑幂等
- **人工干预触发条件**：连续 3 次阶段失败 / SubAgent 输出含 `confidence: LOW` / 涉及生产环境高风险操作；干预点不是系统缺陷，是设计意图
- **成本与 ROI**：中等 Feature 约消耗 126,000 tokens ≈ $0.83 ≈ ¥6；节省约 9 小时人工（按 ¥600/小时计约 ¥5400）；ROI >100x；团队推广建议：先跑数据 → 一条命令入口 → 划清 AI/人边界 → 把问题当改进机会
