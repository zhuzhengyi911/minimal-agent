# Chapter 16: AI-Native 工程工作流

> 核心命题：AI-native 工程师与普通「会用 AI」工程师的本质差异不在技术能力，而在工作流设计——人负责决策与判断，AI 负责执行与探索；七种工作模式构成一套完整的 token 经济学体系。

---

## 16.0 导读：七种工作模式总览

AI-native 工程师的核心能力是把「什么时候用哪种模式」变成本能，而不是偶尔用 AI 加速某一步骤。

- **小明 vs 小华对比**：小明把 AI 当搜索引擎（问答式）；小华把 AI 当协作者（目标驱动），同样的工作量小华产出质量高 3 倍、耗时减半
- **七种模式**：问答（最低效）→ Plan → Vibe Coding → Spec Coding → Agent → Sub-Agent/Multi-Agent → 工作流编排（Harness）
- **token 经济学**：每种模式的 token 消耗与产出效率成反比；Plan 模式节省 30–50% 总 token；Agent 模式单次消耗高但人工介入趋近于零
- **核心定义**：AI-native 不等于全自动化，而是「AI 执行、人决策」的职责分工——人设目标和边界，AI 自主填充实现细节
- **选型原则**：任务确定性高 → Spec Coding；探索性强 → Vibe Coding；步骤超过 3 步且有验收标准 → Agent 模式

---

## 16.1 Plan 模式

`/plan` 命令把 Claude Code 切换到只读探索模式——不写代码、不修改文件，只做分析和规划，是高复杂度任务的必要前置步骤。

- **触发机制**：输入 `/plan` 后进入 `prePlanMode` 状态；系统通过 `prepareContextForPlanMode` 函数对当前权限做快照，退出 Plan 模式时由 `ExitPlanModeV2Tool` 精确恢复，不影响后续执行权限
- **权限枚举**：`EXTERNAL_PERMISSION_MODES`（定义在 `src/types/permissions.ts`）区分 Plan 模式的只读权限集和普通执行权限集
- **计划持久化**：Plan 结果保存到 `~/.claude/plans/*.md`，跨会话可复用，避免重复探索同一代码库
- **STAR 框架**：高质量 Plan 提示词包含 Situation（现状）、Task（目标）、Approach（期望方向）、Result（验收标准）；缺少任一要素 AI 会漫游式探索导致 token 浪费
- **核心价值**：复杂任务先 Plan 再执行，总 token 节省 30–50%；Plan 阶段发现的设计矛盾，比执行阶段发现便宜 10 倍

---

## 16.2 Vibe Coding

Vibe Coding（Andrej Karpathy 2025 年提出）是「不求完美、快速探索」的 AI 辅助编程范式——用来降低探索成本，而非替代生产代码质量要求。

- **四节奏循环**：快速起步（不设计，直接跑）→ 运行观察（看结果，不看代码）→ 聚焦迭代（只改让结果变好的部分）→ 固化成果（有价值的才提炼成正式代码）
- **探索成本革命**：AI 加持后探索一个技术方向的成本下降 10–20 倍；失败探索的代价从「几天工作量」降至「几十分钟对话」
- **语言选择策略**：探索期用 Python（语法轻、反馈快、修改成本低）；交付期换 Java + Spec（类型安全、团队规范、可维护性）
- **`turn-loop` 模式**：在 claw-code 中，`turn-loop` 让 AI 自动循环执行直到满足条件，是 Vibe Coding 快速迭代的底层机制
- **典型反模式**：在 Vibe 阶段追求代码整洁（浪费时间）；把 Vibe 产物直接合并进主干（埋下质量炸弹）；超过 10 轮对话还在 Vibe 而不是切换到 Spec（上下文失控）

---

## 16.3 Spec Coding

Spec Coding 是 AI 时代的 TDD——用五要素规格文档驱动 AI 生成可验收的生产代码，而非靠「感觉」迭代。

- **五要素规格文档**：功能描述（做什么）/ 技术约束（用什么技术栈、禁止什么）/ 接口规格（入参出参 schema）/ 业务规则（状态机、校验逻辑）/ 验收标准（AC-01 到 AC-N，每条对应一个测试用例）
- **TDD 黄金工作流**：Spec 文档 → AI 生成 failing 测试（红）→ AI 实现最小代码让测试通过（绿）→ 人工审查并重构（refactor）；跳过任一步骤都会导致 AI 输出漂移
- **测试命名约定**：用 `@DisplayName("AC-01: ...")` 把验收标准编号直接嵌入测试方法名，AI 能精确对齐规格与实现
- **粒度陷阱**：Spec 太粗 AI 会自行猜测业务逻辑（幻觉高发区）；Spec 太细等于你自己做了设计工作 AI 只是打字员；甜蜜点是「功能级」粒度，一个 Spec 对应 3–8 个验收标准
- **`TaskCreate` 工具**：在 Spec Coding 流程中用 `TaskCreate` 追踪每个 AC 的完成状态，实现 Spec → 任务 → 代码的全链路可追溯

---

## 16.4 Agent 模式

Agent 模式让 AI 从「单次响应」进化为「多步骤自主执行」——你描述目标和边界，Agentic Loop 驱动工具链完成工作，遇到不确定才回来请示。

- **Agentic Loop**：核心执行引擎定义在 `src/query.ts`；循环逻辑：调用 API → 检查是否有工具调用 → 执行工具 → 结果追加消息历史 → 继续下一轮，直到无工具调用（`end_turn`）或达到 `maxTurns`
- **关键参数**：`QueryParams` 中的 `maxTurns`（防无限循环）和 `taskBudget.total`（防 token 失控），通过 `--max-turns` CLI 参数透出给用户
- **`--dangerously-skip-permissions`**：跳过所有权限确认的「无人值守」开关；源码（`src/setup.ts`）内置三重安全护栏：非 root 用户、必须在容器/沙箱中、无公网访问
- **Agent 友好任务描述四要素**：边界（允许/禁止修改哪些文件）/ 验收（完成条件清单）/ 恢复点（哪些情况暂停回报）/ 粒度（一次 Agent 任务控制在 3–15 分钟量级）
- **三种失控模式**：无限循环（同一测试失败超 3 次设放弃条件，设 `--max-turns 30`）/ 上下文爆炸（明确指定只读哪些文件，内置 `autoCompactTracking`）/ 权限蔓延（明确禁止范围，事后 `git diff --stat` 审查改动）

---

## 16.5 Sub-Agent 与 Multi-Agent

Multi-Agent 并行架构的核心哲学：用并行换速度，用边界换可靠性——当单 Agent 上下文窗口不够用时，把任务拆给多个专注 Agent 协同完成。

- **AgentTool**（`src/tools/AgentTool/AgentTool.tsx`）：主 Agent 召唤子 Agent 的入口；`run_in_background=true` 立即返回 `agentId`（异步）；默认同步阻塞等待子 Agent 完成；`isolation="worktree"` 为子 Agent 创建独立 git 分支，完成后主 Agent 合并
- **SendMessageTool**（`src/tools/SendMessageTool/SendMessageTool.ts`）：三种通信模式——点对点（by name/id）/ 广播（`to:"*"`）/ 子→父（发给 `TEAM_LEAD`）；子 Agent 停止时可通过 `resumeAgentBackground` 自动恢复并注入新消息
- **任务拆分三原则**：独立性（子任务间无依赖，可真正并行）/ 明确边界（每个 Agent 只写自己的文件分区，或用 `worktree` 隔离）/ 结果聚合（汇聚型拓扑：所有子 Agent 输出汇总给主 Agent，避免链式依赖导致级联失败）
- **黑板模式**（Blackboard Pattern）：cron 驱动的 Agent 优先用共享状态文件（而非实时消息）协调进度——持久、可审计、无需运行时连接；任务状态机：`⏳ 待处理 → Running（先标记再执行）→ ✅ 完成`
- **三类资源竞争**：写冲突（文件分区 + worktree）/ 任务状态竞争（`claim_task()` 原子锁：先标记 running 再执行）/ API 速率超限（`asyncio.Semaphore(3)` 限并发 + 指数退避重试）

---

## 16.6 工作流编排

AI 工作流编排不是传统批处理的升级版，而是在概率执行和人类干预点之间寻找平衡——用 `PROGRESS.md` 作为跨会话的断点续写机制。

- **四阶段流水线**：Plan（探索 + 规划）→ Spec（规格文档化）→ Agent（自主执行）→ Review（人工验收）；每阶段有明确的机器可读完成标志（如 `PLAN_COMPLETE = true`），防止阶段间状态模糊
- **`PROGRESS.md` 断点续写**：记录当前阶段、已完成任务列表、待办事项、时间戳；Agent 每次启动先读此文件确定从哪里继续，实现跨会话的任务恢复
- **cron 驱动无状态调度**：每次 cron 触发启动一个完全无状态的 Agent，Agent 从共享状态文件读取当前进度、执行一个原子任务、写回状态——避免长时间运行的有状态进程
- **失败上下文注入**：`RetryableAgent` 在重试时把上次失败的错误信息和已尝试方案注入新的上下文，而非从零开始；这是与 Spring Batch 的关键差异——概率执行需要「失败知识」而非「状态机恢复」
- **与 Spring Batch 的本质区别**：Spring Batch 是确定性步骤序列（失败重试同样操作）；AI 工作流是概率执行（失败注入上下文 + 换策略重试）+ 人类干预点（高不确定性步骤自动暂停等待确认）

---

## 16.7 Harness Engineering

Harness Engineering（治具工程）是应对 AI 工程五大瓶颈的系统性方法论——不替代任何单一模式，而是作为底层基础设施让所有模式可靠运行。

- **五大工程瓶颈**：状态断层（会话结束状态丢失）/ 质量黑盒（AI 输出无法量化）/ 上下文稀释（长对话遗忘早期关键信息）/ 幻觉传播（错误输出被后续步骤当作事实引用）/ 经验沉没（踩过的坑下次还踩）
- **三大工程支柱**：上下文管理（`process.txt` 跨会话恢复文件，记录需求 ID、当前阶段、任务进度、变更日志、待确认项）/ 知识沉淀（CLAUDE.md 沉淀团队规范，失败案例转化为 Rules）/ 工具设计（自定义 slash commands 把专家经验变成可复用模板）
- **七阶段状态机**（`harness-dev-flow`）：需求分析 → Spec 生成 → 测试生成（红）→ 实现（绿）→ 重构 → Review → 交付；每个阶段有明确进入条件和退出标准
- **Spec Coding 七规则**：禁止幻觉（#1 最重要：所有业务逻辑必须来自 Spec，不得推测）/ 接口先行 / 测试优先 / 最小实现 / 失败显式化 / 变更可追溯 / 验收可机器执行
- **VERDICT 协议**：每个阶段结束时 AI 输出 `VERDICT: PASS`（附通过证据）或 `VERDICT: FAIL`（附失败详情和下一步建议），使质量评判机器可读、流程可自动化

---

## 16.8 选型指南

三问法快速定位正确模式：任务确定性如何？复杂度几何？成本约束是什么——三个问题比任何矩阵都管用。

- **三问选型法**：① 输出是否有唯一正确答案（确定性）→ 高确定性选 Spec/Plan，低确定性选 Vibe；② 任务是否超过 3 步且有外部依赖 → 是则考虑 Agent；③ 是否有时间/token 预算约束 → 有约束则从简单模式入手
- **四象限矩阵**：确定性高 × 复杂度低 = Spec Coding；确定性低 × 复杂度低 = Vibe Coding；确定性高 × 复杂度高 = Agent + Spec；确定性低 × 复杂度高 = Plan + Vibe → 收敛后转 Spec
- **五大反模式**：用 Vibe Coding 交付生产代码（跳过了 Spec 和测试）/ 用 Agent 模式做探索性任务（边界不清导致失控）/ 每次从零开始（不积累 CLAUDE.md 规范）/ 并行拆分有依赖的任务（链式依赖导致级联失败）/ 把 Harness 当成银弹（它是基础设施，不是替代其他模式的方案）
- **Harness 作为元模式**：Harness Engineering 不在四象限里，它是所有模式之下的工程基础——无论选哪种模式，`process.txt` + `PROGRESS.md` + VERDICT 协议 + 失败上下文注入都应该存在
- **ROI 盈亏点**：工作流自动化的搭建成本约等于 3–5 次手动执行成本；重复执行 3 次以上的工作流才值得 Harness 化；一次性任务用 Agent 直接跑，不要过度工程化
