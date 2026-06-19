# SubAgent / Coordinator Mode 设计

> 参考来源：Claude Code 源码分析（第10章，共6节）

## 核心思路

Coordinator Mode 不是独立系统，而是主 Agent 的"角色切换"，通过两件事激活：
1. 给主 Agent 注入 Coordinator 专用 System Prompt
2. 给主 Agent 挂载 `SubAgentTool`（调用后内部孵化一个完整的 Agent 实例）

## 模块设计

### SubAgentTool（对应 Claude Code 的 AgentTool）

工具参数：
- `description`：3-5 字简述，供日志展示
- `prompt`：子 Agent 的完整任务指令
- `run_in_background`：（暂不实现）

execute 内部行为：
1. 创建新的 `Agent` 实例（独立 `Session`，继承父 Agent 的 `ToolRegistry` 但排除 `agent` 工具防止无限递归）
2. 调用 `subAgent.runTurn(prompt)`
3. 从 `TurnSummary` 中提取最后一条 assistant 文本返回

### SubAgent 实例

每次 `SubAgentTool.execute()` 都 new 一个新 Agent：
- 独立 `Session`（独立对话历史）
- 继承 `ApiClient`、`ToolRegistry`（子集，不含 `agent` 工具，防止无限递归）
- 专用 Worker System Prompt（与主 Agent 完全独立，见下）
- 运行单个 `runTurn(prompt)`，内部正常多轮迭代直至完成

**Worker System Prompt（存放于 `Defaults.WORKER_SYSTEM_PROMPT`）：**

```
You are a worker agent. You will be given a specific, well-defined task.
Execute it completely and report the result clearly.

## Behavior
- Focus only on the assigned task. Do not expand scope.
- Use tools directly to accomplish the task.
- When done, summarize what you did and what the result is.
- If you encounter an error you cannot recover from, report it clearly.
```

### isConcurrencySafe 并行调度

在 `ToolDefinition` 增加 `isConcurrencySafe()` 标记，`Agent` 在处理同一次响应的多个 tool_use 时按批次调度：
- 连续的 `concurrencySafe=true` 合并为一个并行批次，提交给注入的 `ExecutorService` 并行执行
- `concurrencySafe=false` 单独串行执行

### 线程池注入

并发批次的执行依赖注入的 `ExecutorService`，通过 `AgentOptions` 传入，整个 Agent 树（主 Agent + 所有子 Agent）共享同一个实例：

```java
public record AgentOptions(
    int maxIterations,
    HookRunner hookRunner,
    MemoryLoader memoryLoader,
    SkillLoader skillLoader,
    AgentMdLoader agentMdLoader,
    ExecutorService executor   // 新增
)
```

`SubAgentTool` 创建子 Agent 时将同一个 `executor` 透传进 `AgentOptions`，不新建池。

**不同场景的配置（Java 21）：**

| 场景 | ExecutorService |
|---|---|
| CLI（当前） | `Executors.newVirtualThreadPerTaskExecutor()`，轻量无界，适合单用户 |
| Web 服务（未来） | `Executors.newFixedThreadPool(N)`，有界，控制全局并发上限 |

并发批次内部使用 `Thread.startVirtualThread()` 启动每个工具调用，`Thread.join()` 等待全部完成后统一收集结果。

`AgentBootstrap.shutdown()` 时统一关闭 executor。

各工具的 safe 值：

| 工具 | isConcurrencySafe |
|---|---|
| ReadFileTool | true |
| GrepTool | true |
| GlobTool | true |
| LoadSkillTool | true |
| ReadMemoryTool | true |
| SubAgentTool | true（IO 密集，可并行） |
| BashTool | false |
| WriteFileTool | false |
| EditFileTool | false |
| WriteMemoryTool | false |

### Coordinator System Prompt

```
You are a coordinator. Decompose complex tasks into subtasks
and delegate them to worker agents using the `agent` tool.
Do not directly execute coding tasks — use workers for that.

When assigning parallel tasks, ensure workers do not write to
the same files. Read-only tasks (research, exploration) can be
parallelized freely. Write tasks must be scoped to non-overlapping
files or run sequentially.
```

### Coordinator 模式激活

Coordinator 模式默认开启，通过 `.agent/settings.json` 配置：

```json
{
  "coordinator": {
    "enabled": true
  }
}
```

`AgentBootstrap.build()` 读取配置，若 `enabled=true` 则：
1. 向 `ToolRegistry` 注册 `SubAgentTool`
2. 在 system prompt 中追加 Coordinator System Prompt

无需 CLI flag 或环境变量，与主 Agent 的其他配置统一管理。

## 数据流

```
用户 → Coordinator（主 Agent）
  → tool_use: agent(prompt="实现X功能")
    → SubAgentTool.execute()
      → new Agent(独立 Session)
      → subAgent.runTurn(prompt)   // 内部可多轮迭代
      → 提取最后一条 assistant 文本
      → 返回给 Coordinator
  → Coordinator 综合结果 → 回复用户
```

## 四阶段工作流（来自 coordinatorMode.ts）

| 阶段 | 执行者 | 目的 |
|---|---|---|
| Research | Workers（并行） | 探索代码库、理解问题 |
| Synthesis | Coordinator | 读懂调研结论，制定实现规格 |
| Implementation | Workers | 按规格实现代码 |
| Verification | Workers | 跑测试、验证结果 |

## 异常处理

子 Agent 内部发生异常时，`SubAgentTool.execute()` 捕获后作为 error tool result 返回给 Coordinator：

```
tool_result(isError=true, content="SubAgent failed: <异常信息>")
```

Coordinator（主 Agent）收到 error result 后自行决定后续策略——重试、跳过、或向用户报告。不在框架层强制重试，保持 Coordinator 的决策自主性。

## 并发安全改造

并行工具批次执行时，多个线程同时访问共享状态，以下模块需要改造：

### 需要改造

**Session**

`addMessage()` 会被并行工具结果同时调用，`List<Message>` 非线程安全。
解决方案：并行工具执行完后统一收集结果，再按顺序批量写入 Session，Session 本身不改。

**SkillLoader**

`loadedSkills` 是普通 `HashMap`，并行的 `load_skill` 调用会产生并发写：
```java
// 改为
private final Map<String, String> loadedSkills = new ConcurrentHashMap<>();
// loadSkill() 用 putIfAbsent 防止重复加载
```

**MemoryLoader**

缓存字段 `cachedContent`、`cachedMtime` 在并行的 `write_memory`（触发 invalidate）和 `load()`（读缓存）之间存在竞态：
```java
private volatile String cachedContent;
private volatile FileTime cachedMtime;
```

### 不需要改造

| 模块 | 原因 |
|---|---|
| `ApiClient` | 无状态 HTTP 客户端，天然线程安全 |
| `ToolRegistry` | 初始化后只读，无并发写 |
| `PermissionPolicy` | 只读 |
| `UsageTracker` | 在所有工具完成后才 `record()`，不在并行区间内 |
| `HookRunner` | 每次调用独立，无共享状态 |

## 与 Claude Code 的差异（有意简化）

| 特性 | Claude Code | minimal-agent |
|---|---|---|
| 异步子 Agent | `run_in_background=true` + `task-notification` XML | 暂不实现 |
| Git Worktree 隔离 | 每个 Worker 独立分支 | 共享工作目录 |
| SendMessage | 向后台 Agent 发消息 | 暂不实现 |
| AgentDefinition | 预定义多种 Agent 类型 | 只有 general-purpose |
| 并行工具批次 | `partitionToolCalls` 分区算法 | 实现核心分区逻辑 |
