# 第 14 章：从 Java 工程师到 Agent 工程师的思维转型

> 核心命题：Agent 工程师不是放弃后端工程思维，而是**扩展**它——用后端工程师的严谨性驾驭 LLM 的概率性。

---

## 14.0 章节导读

Agent 工程与传统后端的差距不是语法差别，而是**范式鸿沟**——用 HTTP 接口思维写 Agent，每次遇到问题都会用错误的直觉调试。

- 核心判断：「你在用写 HTTP 接口的方式写 Agent。」
- 五道鸿沟：命令式→声明式 / 确定性→概率性 / 同步→异步反应式 / 单体→工具组合 / 代码覆盖率→行为覆盖率
- Java 背景是**竞争优势**：稳定 API、可观测运行时、可测试模块边界——这些 Agent 系统同样需要，纯 prompt 工程师写不出可维护的系统
- 本章重心在**思维框架**，不在具体实现；源码是证据，不是目的

---

## 14.1 命令式 → 声明式

Agent 开发的核心范式转变：从「告诉机器怎么做」变为「描述想要什么结果」，控制权从程序员转移给运行时（LLM）。

- **Prompt 是面向 AI 引擎的声明式语言**，类比 SQL——你不告诉数据库怎么扫索引，只描述查询条件
- `SystemPrompt` 在 Turn Loop 中是不可变的（immutable）；「声明'是什么'的部分稳定，描述'发生了什么'的部分可变」
- `sideQuery` ≈ 面向 AI 的声明式 HTTP Endpoint：调用者只提供「AI 是谁 + 要做什么」，框架处理重试/header/归因
- **决策框架**：规则可穷举 → 命令式代码；需要语义理解/常识推理/规则频繁变化 → 声明式 Prompt
- 代码嗅觉：发现自己在写一堆 if-else 判断语义问题时，停下来问——这件事交给 Prompt 会不会更好？
- 每次技术跃迁都是把「怎么做」的责任向下层转移：Servlet → Spring MVC → Spring AI + Agent

---

## 14.2 确定性 → 概率性

LLM 不是幂等函数，接受概率性不是降低标准，而是**在更高抽象层面重建可靠性**。

- 概率性是**本质特性**（temperature > 0），不是 bug；重试不保证复现，而是新的采样
- Claude Code 的工程应对：
  - `structured_retry_limit: int = 2`——显式承认「它会失败」，设计降级而非假设成功
  - `withRetry.ts`：10 层重试，含指数退避、jitter、429/529 区分处理
  - `FallbackTriggeredError`：模型过载时切换备用模型（优雅降级，不是崩溃）
  - **温度分区**：创意任务 temperature=1；权限分类器/Hook 查询/结构化判断 temperature=0——在概率性系统中雕刻**确定性岛屿**
- **测试关键洞察**：用 `ScriptedApiClient` 模式——不测 LLM 输出什么，测 Agent 框架如何处理给定输出；把 LLM 不确定性隔离到测试边界之外
- 思维转变：确定性是需要**主动构建**的，不再是默认状态

---

## 14.3 同步 → 异步流式

「响应」不再是一个对象，而是一个**事件序列**；同步阻塞等全量结果是人为制造的延迟。

- LLM 逐 token 生成，每生成一个 token 即可发出——流式是自然状态，批量返回反而不自然
- Rust `AssistantEvent` 枚举定义了流的**状态机协议**：`TextDelta → ToolUse → Usage → MessageStop`，消费者必须维护当前状态
- TypeScript 用 `async function*`（异步生成器）实现**拉模式**流：`for await` 天然背压，消费者慢则生产者暂停
- Python 版「元信息先行」：`message_start → command_match → tool_match → permission_denial → message_delta → message_stop`，消费者不必等到最后才知道发生了什么
- **工具调用的 JSON 参数也是流式的**（`input_json_delta`）——需要消费者自行拼接碎片
- Java 类比：`AsyncGenerator` ≈ `Flux<T>`（WebFlux），但更轻量；拉模式 vs Reactor 的推模式

---

## 14.4 单体 → 工具组合

Agent 功能不是「写出来的」，而是从 LLM 推理能力与工具能力原语的碰撞中**涌现出来的**。

- Java 视角：功能 = 代码写死的 Service 调用链；Agent 视角：功能 = LLM 运行时动态选择工具组合
- **工具三设计原则**：
  1. **小而正交**：每个工具做一件事；不要设计「读并改」工具——那是把 LLM 的组合权收回来了
  2. **Schema 即契约**：`inputSchema` 是你唯一能控制 LLM 行为的地方，比 Javadoc 更重要
  3. **副作用显式声明**：`isReadOnly / isDestructive / PermissionMode`——影响权限检查和 LLM 的谨慎程度
- 工具注册是简单数组，不是 IoC 容器——这是刻意的简化
- 三件套（Bash + FileRead + FileEdit）构成图灵完备的文件操作子集；`simple_mode` 时退化到此
- MCP 协议 = 工具生态的开放扩展点；内部封闭静态，外部通过 MCP 动态接入，LLM 视角完全统一
- `ToolSearch` 机制：工具过多时两阶段发现（先搜索再调用），类比微服务注册与发现

---

## 14.5 测试策略

**不要测试 LLM 说了什么，要测试 Agent 在给定 LLM 输出时做了什么**——这是 Agent 测试哲学的根本转变。

- 三层测试金字塔：
  1. **L1 单元测试（ScriptedApiClient）**：给 LLM 写剧本（按 `call_count` 返回预设响应序列），在内存中完整跑通「大脑→手→大脑」循环，无需网络；Mock 内嵌断言，主动验证请求格式
  2. **L2 集成测试（MockAnthropicService）**：本地真实 HTTP 服务器，场景标记驱动响应，捕获并断言 HTTP 层细节（Header/流式/重试触发次数）
  3. **L3 端到端测试**：直接运行编译产物，`env_clear()` + 指向 Mock 服务器，断言行为结构而非输出内容
- **结构性断言**而非内容断言：`iterations==2 / tool_results.len()==1 / message.contains(keyword)` ✓；`message == "精确文本"` ✗
- TypeScript 版：`NODE_ENV=test` 运行时条件分支 + `resetStateForTests()`；Rust 版：`#[cfg(test)]` 编译隔离 + 独立 Mock 服务二进制——语言特性决定隔离方式

---

## 14.6 可观测性

Agent 可观测性的第一优先级是**Token 成本**，而非传统的延迟/吞吐量；可观测性必须内嵌在业务逻辑中，不能是事后添加的。

- `UsageTracker`：`latest_turn`（Gauge 语义，快照覆盖）+ `cumulative`（Counter 语义，单调递增）；`from_session()` 从历史消息重建，成本追踪幂等可恢复
- `pricing_for_model()` 返回 `Option`，未知模型触发降级并标注 `pricing=estimated-default`——诚实设计，不假装精确
- **事件总线**：Sink 未就绪时事件排队（不丢弃），Sink 就绪后 `queueMicrotask` 异步排水；幂等附接，类比 SLF4J 的 StaticLoggerBinder
- Datadog 集成的工程细节：白名单过滤（45 个 `tengu_*` 事件）/ MCP 工具名归一化（降低基数）/ `never` 类型强制字符串脱敏审计
- OTel 三信号：8 个 `claude_code.*` 指标 / 结构化日志 / BatchSpanProcessor 追踪——兼容任意 OTel 后端
- 三个可迁移模式：Token 成本作为第一指标 / 事件总线先于 Sink 存在 / 会话可恢复性（成本随 sessionId 持久化）

---

## 14.7 职业发展路线图

能让 AI 可靠工作的工程师，将比能写 AI 模型的工程师更为稀缺；Java 工程师的优势是**站在已有工程能力的肩膀上转型**。

- **技能迁移矩阵**：
  - 直接迁移：REST API 设计 → Tool 接口设计；权限系统 → Permission 层；结构化日志 → OTel/成本追踪
  - 升级迁移：微服务治理 → Multi-Agent 编排；单元/集成测试 → Agent 测试策略（ScriptedClient/parity）
  - 全新学习：Token 经济学 / Context Window 管理 / 系统级 Prompt Engineering / 概率性行为调试 / MCP 生命周期
- **五层技能树**：LLM 基础 → 工具系统 → Agent 架构（Turn Loop / 状态机 / Multi-Agent）→ 可观测性 → 工程化（测试/安全/生产部署）
- **三条职业路径**：
  - 产品型：Prompt Engineering + Tool Design + UX（侧重 L1+L2）
  - 平台型：Agent 框架 + 性能优化 + 基础设施（侧重 L3+L4+L5）——Java 中间件背景最顺
  - 研究型：Multi-Agent 推理 + 实验设计（需要接受实验性结论，用数据说话）
- 12 个月里程碑：[1-3月] 第一个生产 Agent → [4-6月] MCP 工具系统 → [7-9月] 多 Agent 编排 → [10-12月] 生产化 + 安全设计
- 终极总结：Agent 工程本质上还是工程——「Agent 的大多数生产事故，本质上都是工程问题，不是 AI 问题」
