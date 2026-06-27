# CLI 设计文档

> 本文档描述 `Cli` 类及其周边组件的设计方案，对标 Claude Code 命令行的核心交互模型。

---

## 一、整体架构

```
Cli.main(args)
    │
    ├─ 解析 StartupArgs
    ├─ AgentBootstrap.build()  →  BuildResult
    │
    ├─ [非交互模式 -p]  PrintRunner.run(prompt)  →  打印结果 → exit
    │
    └─ [交互模式]  ReplLoop.start()
            │
            ├─ 打印欢迎头
            ├─ loop:
            │     readline
            │     ├─ "/" 开头  →  CommandRegistry.dispatch(line, ctx)
            │     ├─ "!" 开头  →  ShellPassthrough.run(line)
            │     └─ 其他       →  Agent.runTurn(line)  [流式渲染]
            └─ Ctrl-D / /quit  →  优雅退出
```

**类职责一览：**

| 类 | 职责 |
|---|---|
| `Cli` | `main()`：解析参数，引导到两种运行模式 |
| `StartupArgs` | 解析并持有启动参数（record） |
| `ReplLoop` | REPL 主循环：读取 → 分发 → 渲染 |
| `PrintRunner` | 非交互 `-p` 模式：单轮运行后 exit |
| `SlashCommand` | 斜杠命令接口：`name()` / `description()` / `run()` |
| `CommandRegistry` | 命令注册表，name → SlashCommand，支持别名 |
| `CommandContext` | 命令执行时能访问到的所有组件（agent、session、loaders…） |
| `Renderer` | 流式输出渲染：文字、tool call、token 摘要 |
| `ShellPassthrough` | `!cmd` 直通：直接 exec，stdout/stderr 流式打印 |
| `command/` | 各命令的具体实现（一命令一文件） |

---

## 二、启动参数 `StartupArgs`

```
Usage: java -jar agent.jar [OPTIONS] [PROMPT]

Arguments:
  PROMPT            直接在命令行传入 prompt（等价于 -p 但更简洁）

Options:
  -p, --print <prompt>    非交互模式：执行单轮后打印结果并退出
  -v, --verbose           详细模式：显示完整的 tool 调用参数和结果
      --model <id>        覆盖 settings.json 中的模型 ID
      --mode <level>      权限模式：read_only | workspace_write | danger_full_access
      --dir <path>        工作目录（默认为当前目录）
      --no-mcp            跳过 MCP server 初始化
      --version           打印版本后退出
      --help              打印帮助后退出
```

**StartupArgs（record）：**

```java
record StartupArgs(
    Optional<String> printPrompt,   // -p
    boolean verbose,
    Optional<String> modelOverride,
    Optional<ModeEnum> modeOverride,
    Optional<Path> workDir,
    boolean noMcp
)
```

---

## 三、REPL 交互模型

### 3.1 输入分类

| 输入形式 | 处理方式 |
|---|---|
| `/command [args]` | 斜杠命令，dispatch 到 `CommandRegistry` |
| `!shell cmd` | Shell 直通，`ShellPassthrough` 执行并流式打印 |
| 空行 | 忽略 |
| 其他文本 | 作为用户消息传给 `Agent.runTurn()` |

### 3.2 多行输入

用 `\` 结尾表示续行，输入空行结束多行块（与 Claude Code 一致）。

### 3.3 中断处理

- `Ctrl-C`：取消当前 Agent 运行（中断流式输出），回到 prompt
- `Ctrl-D` / EOF：等价于 `/quit`

---

## 四、斜杠命令

### 接口定义

```java
public interface SlashCommand {
    /** 主命令名，不含 /，如 "clear" */
    String name();

    /** 别名列表，如 ["exit"] */
    default List<String> aliases() { return List.of(); }

    /** 显示在 /help 中的一行描述 */
    String description();

    /** 完整用法（可选，/help <cmd> 时展示） */
    default String usage() { return "/" + name(); }

    /**
     * 执行命令。
     * @param args  空格分割的参数（已去掉命令名本身）
     * @param ctx   当前 REPL 上下文
     * @return      CONTINUE 继续循环，QUIT 退出程序
     */
    CommandResult run(List<String> args, CommandContext ctx);
}

enum CommandResult { CONTINUE, QUIT }
```

### `CommandContext`

```java
record CommandContext(
    Agent agent,
    Session session,
    UsageTracker usageTracker,
    MemoryLoader memoryLoader,
    SkillLoader skillLoader,
    ToolRegistry toolRegistry,
    AgentSettings settings,
    StartupArgs startupArgs,
    Renderer renderer
)
```

---

### 命令清单

#### `/help [command]`

列出所有命令及一行描述；带参数时展示单个命令的 `usage()`。

```
Available commands:
  /help [cmd]     Show help for a command
  /clear          Clear conversation history
  /compact        Force context compaction now
  /status         Show token usage and model info
  /memory         List memory entries
  /skills         List available skills
  /tools          List registered tools
  /mode [level]   Show or change permission mode
  /model [id]     Show or change model
  /session        Show session statistics
  /settings       Show current settings
  /quit           Exit (aliases: /exit)
```

---

#### `/clear`

清空 Session 中的所有消息（含 compaction summary），同时重置 `UsageTracker` 的 per-session 计数。打印确认行：

```
Conversation cleared. (42 messages removed)
```

> **对应已有组件：** `Session` 需新增 `clear()` 方法。

---

#### `/compact`

立即触发一次上下文压缩，无论是否达到阈值。

```
Compacting 67 messages...
Done. Session reduced to 8 messages. (summary: 1 234 tokens)
```

> **对应已有组件：** 调用 `Compactor.compact(session, ...)` 即可，已有完整实现。

---

#### `/status`

显示当前会话的关键指标：

```
Model:    MiniMax-M3
Mode:     workspace_write
Context:  12 450 / 200 000 tokens (6.2%)
Session:  3 turns · 18 messages · 1 compaction
Usage:    ↑8 230  ↓1 450  cache-read 3 100  |  $0.0021 total
```

> **对应已有组件：** `UsageTracker`、`Session`、`AgentSettings`。

---

#### `/memory`

打印 `MemoryLoader` 当前加载的 MEMORY.md 全文（或提示文件不存在）。

```
── .agent/memory/MEMORY.md ──────────────────────────
- [git push 需要明确指令](feedback_git_push.md) — 只有用户明确说 push 才执行
...
```

可选子命令（第一期先只做 show）：
- `/memory show` — 同上（默认行为）
- `/memory reload` — 强制刷新缓存（清空 mtime 缓存）

> **对应已有组件：** `MemoryLoader.getMarkdown()` 或 `getSummary()`。

---

#### `/skills`

分两块展示：

```
Loaded skills (1):
  ● code_review     Guidelines for reviewing Java code

Available skills (2):
  ○ frontend        Frontend development guidelines
  ○ frontend/react  React-specific patterns
```

`●` 表示本 session 已加载到 context；`○` 表示仅有 summary。

> **对应已有组件：** `SkillLoader.getSummaries()` + `SkillLoader.getLoadedSkills()`（需确认接口存在）。

---

#### `/tools`

按来源分组列出所有已注册工具：

```
Built-in tools (9):
  bash            Execute shell commands           [workspace_write]
  read_file       Read file contents               [read_only]
  write_file      Create or overwrite a file       [workspace_write]
  edit_file       Edit existing file               [workspace_write]
  glob            File pattern matching            [read_only]
  grep            Content search                   [read_only]
  read_memory     Read memory entries              [read_only]
  write_memory    Write memory entry               [workspace_write]
  load_skill      Load skill into context          [read_only]

MCP tools (2):  [filesystem]
  mcp__filesystem__read_file    Read a file via MCP
  mcp__filesystem__list_dir     List directory contents
```

> **对应已有组件：** `ToolRegistry`（需区分 builtin vs. MCP 来源，可通过命名前缀）。

---

#### `/mode [level]`

不带参数：显示当前权限模式及各级说明。
带参数：切换权限模式（运行时生效）。

```
# 查看
Current mode: workspace_write

  read_only          Read-only operations only
  workspace_write    Write to project files (current)
  danger_full_access Full access including system commands

# 切换
/mode read_only
→ Mode changed: workspace_write → read_only
```

> **对应已有组件：** `PermissionPolicy`（需支持运行时 `setMode()`）。

---

#### `/model [id]`

不带参数：显示当前模型及上下文规格。
带参数：切换模型（需 `ApiClient` 支持动态模型切换，第一期可标注为 "requires restart"）。

```
Current model: MiniMax-M3
  Context window:   200 000 tokens
  Max output:         4 096 tokens
  Input price:      $0.80 / M tokens
  Output price:     $2.40 / M tokens
```

> **对应已有组件：** `ModelConfig`、`AgentSettings`。

---

#### `/session`

显示当前会话生命周期统计：

```
Session statistics:
  Turns:       5
  Messages:    34  (user: 5, assistant: 14, tool: 15)
  Compactions: 1
  Total tokens:  ↑24 100  ↓3 870
  Estimated cost:  $0.0231
  Started:     2026-06-27 14:32:11
  Duration:    00:08:43
```

> **对应已有组件：** `Session`、`UsageTracker`（需增加 start timestamp）。

---

#### `/settings`

打印当前运行时生效的配置（不含密钥，apiKey 脱敏）：

```
Settings:
  api.provider:    minimax
  api.model:       MiniMax-M3
  api.maxTokens:   4096
  api.apiKey:      sk-****1234
  coordinator:     enabled
  mode:            workspace_write
  workDir:         /Users/zzypiper/minimal-agent
  verbose:         false
```

---

#### `/quit` (alias `/exit`)

打印 token 汇总后退出：

```
Goodbye. Session total: ↑24 100 ↓3 870 | $0.0231
```

---

## 五、Shell 直通 `!cmd`

`!` 后的内容作为 shell 命令直接执行（通过 `ProcessBuilder`），stdout/stderr 实时流式打印到终端，无需经过 Agent。

```
! ls -la
! git diff HEAD~1
```

执行完后打印退出码（非 0 时标红）：

```
Exit code: 0
```

> **注意：** 这与 Agent 的 `bash` tool 不同——shell 直通完全在 CLI 层，不消耗任何 token，也不进入对话历史。

---

## 六、Renderer（输出渲染）

### 6.1 流式文字

LLM 输出的 `TextDelta` 事件直接逐字符写入 stdout，无缓冲。

### 6.2 Tool 调用

每次 tool 被调用时，Renderer 输出一行前缀展示（verbose 模式可展开参数）：

```
# 默认模式
◆ bash  ls -la src/

# verbose 模式
◆ bash
  command: ls -la src/
  (允许执行...)
```

Tool 执行完毕后：

```
  ✓ 23 lines
```

若 tool 失败：

```
  ✗ error: command not found: xyz
```

### 6.3 每轮 Token 摘要

每个 `runTurn()` 完成后，在 prompt 上方打印：

```
[↑1 234  ↓456  cache-read 890  |  $0.0008]
```

`--verbose` 时展开：

```
[Turn 3 · 2 iters · 3 tools]
  input:       1 234 tokens
  output:        456 tokens
  cache-read:    890 tokens
  cost:         $0.0008
```

---

## 七、非交互模式 `PrintRunner`

启动时指定 `-p "..."` 或位置参数：

```bash
java -jar agent.jar -p "summarize this PR"
java -jar agent.jar "list all TODO in src/"
```

行为：
1. 初始化 Agent（相同 bootstrap 流程）
2. 调用 `agent.runTurn(prompt)` 一次
3. 将 assistant 文字输出打印到 stdout
4. 以退出码 0 退出（工具调用失败则非 0）

适合脚本/CI 集成，无交互提示符，无颜色转义码（可检测 `!System.console().isTerminal()`）。

---

## 八、包结构

```
com.zzypiper.cli/
├── Cli.java                  main() 入口
├── StartupArgs.java          启动参数 record
├── ReplLoop.java             REPL 主循环
├── PrintRunner.java          非交互单轮运行
├── SlashCommand.java         命令接口
├── CommandResult.java        CONTINUE / QUIT
├── CommandContext.java       命令执行上下文
├── CommandRegistry.java      命令注册 + dispatch
├── Renderer.java             输出渲染
├── ShellPassthrough.java     ! 直通执行
└── command/
    ├── HelpCommand.java
    ├── ClearCommand.java
    ├── CompactCommand.java
    ├── StatusCommand.java
    ├── MemoryCommand.java
    ├── SkillsCommand.java
    ├── ToolsCommand.java
    ├── ModeCommand.java
    ├── ModelCommand.java
    ├── SessionCommand.java
    ├── SettingsCommand.java
    └── QuitCommand.java
```

---

## 九、对已有组件的改动

下表列出实现 CLI 时需要在**现有类**上新增或修改的内容：

| 组件 | 变更 | 原因 |
|---|---|---|
| `Session` | 新增 `clear()` 方法，清空 messages 列表 | `/clear` 命令 |
| `Session` | 新增 `getStartedAt()` 时间戳 | `/session` 显示启动时间 |
| `UsageTracker` | 新增 `reset()` 方法 | `/clear` 后重置统计 |
| `PermissionPolicy` | 新增 `setMode(ModeEnum)` 方法 | `/mode` 运行时切换 |
| `SkillLoader` | 确认 `getLoadedSkills()` 对外可见 | `/skills` 显示已加载标记 |
| `BuildResult` | 暴露 `agentSettings()` | `/settings` 显示配置 |

---

## 十、实现优先级

| 优先级 | 内容 |
|---|---|
| P0（核心可用）| `Cli.main`、`ReplLoop`、`Renderer`、`/help`、`/quit`、`/clear`、`/status` |
| P1（常用）| `/compact`、`/memory`、`/skills`、`/tools`、`/mode`、`!` 直通、`-p` 非交互 |
| P2（补全）| `/session`、`/settings`、`/model`、多行输入、Ctrl-C 中断 |
