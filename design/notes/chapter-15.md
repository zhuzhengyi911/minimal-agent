# Chapter 15: Agent 配置与工具体系

> 核心命题：Claude Code 的四层配置体系（Rules / Commands / MCP / Skills）是把「通用 AI」变成「懂你团队的 AI」的完整工程框架。

---

## 15.1 CLAUDE.md 与 Rules

CLAUDE.md 是把团队规范系统化注入 AI 上下文的核心机制——它不执行代码，但约束 AI 如何生成代码。

- **四层作用域**：Managed（`/etc/claude-code/`）→ User（`~/.claude/`）→ Project（`.claude/`）→ Local（`CLAUDE.local.md`），后加载优先级更高（CSS 层叠逻辑）
- **CWD 向上遍历**：从当前目录到根目录沿途所有 CLAUDE.md 都被加载，子目录优先级最高——monorepo 天然继承
- **`@include` 模块化**：可引用外部文件，最多 5 层递归；代码块内的 `@path` 不会被展开
- **条件规则 `paths` frontmatter**：`.claude/rules/*.md` 可声明 glob 路径，只对匹配文件类型生效
- **注入 System Prompt**：所有文件拼接后带 `IMPORTANT: These instructions OVERRIDE any default behavior` 前缀；上限 40000 字符
- **`claudeMdExcludes`**：黑名单机制，防止第三方依赖的 CLAUDE.md 造成 prompt injection
- 写作原则：只写「不写就会出错」的规范，加上「为什么」才能让 AI 在特殊情况做合理权衡

---

## 15.2 Slash Commands

Slash Commands 的本质不是让 AI 更聪明，而是把工程师的专业经验编码成可复用的提示词模板。

- **三种类型**：`local`（纯本地代码执行）、`local-jsx`（带 UI）、`prompt`（调用 AI）；命令的「壳」是本地的，「核」可以调用 AI
- **自定义命令**：`.claude/commands/*.md`，文件名即命令名，正文是提示词，支持 YAML frontmatter（`description`、`allowed-tools`、`model`、`context`）
- **`$ARGUMENTS` 参数机制**：支持完整参数 `$ARGUMENTS`、位置参数 `$0/$1`、命名参数（frontmatter 声明 `arguments` 字段）；用 `shell-quote` 解析以支持带空格的参数
- **`context: fork` 异步模式**：命令在独立 Sub-Agent 中运行，主线程立即释放；结果通过 `enqueuePendingNotification` 回注主对话队列（`isMeta: true`，用户不可见）
- 核心区别：Claude Code 是**显式调用**（`/cmd`，可预测）；OpenClaw 是**意图匹配**（AI 自动识别，更自然但不确定）

---

## 15.3 MCP（Model Context Protocol）

MCP 是 AI 工具集成的行业标准——一次实现，所有支持 MCP 的 Client 都能用，解决 Function Calling 各平台私有格式无法复用的根本问题。

- **协议层**：JSON-RPC 2.0，三种传输——stdio（子进程，最简单最安全）、SSE（旧版 HTTP 推送）、Streamable HTTP（MCP 1.0，推荐远程服务）
- **三类能力**：Tool（AI 主动调用，有明确 schema）、Resource（被动暴露的 URI 数据）、Prompt（可参数化的提示词模板）
- **工具命名空间**：`mcp__服务器名__工具名`，避免冲突同时让模型知道工具来源
- **连接管理**：`memoize + onclose 清缓存`实现透明重连；`stderr: 'pipe'` 隔离子进程日志避免污染 UI；输出超 25000 token 自动截断
- **安全三道防线**：进程隔离（stdio 天然沙箱）→ 用户确认（首次使用弹窗，`allow`/`deny` 写入 settings）→ 输出截断
- MCP vs Function Calling：前者工具定义耦合在 API 调用里，后者独立进程、主动 `tools/list`、跨平台复用

---

## 15.4 Skills（能力包）

Skill 是配置体系中粒度最大的复用单元——带 SKILL.md 的目录，既是知识包也是可自动触发的微型 Agent。

- **关键区分**：CLAUDE.md 始终注入（always-on 规则）；Skill 按需惰性加载——摘要常驻上下文（占 1% token budget），全文只在被调用时加载
- **三层目录**：`managed > user（~/.claude/skills/）> project（.claude/skills/）`，与 CLAUDE.md 作用域对称
- **`when_to_use` 字段**：Skill 的触发器，AI 凭 name + description + when_to_use 摘要自动识别调用——这是 Skill 比 Command 更强大的地方
- **`context: fork`**：Skill 在隔离子 Agent 中运行，不污染主对话 token；inline（默认）则共享上下文
- **`paths` 条件激活**：匹配特定文件路径时才出现在上下文预算中，避免大量 Skill 同时占用 token
- 决策原则：用 CLAUDE.md 改变全局默认行为；用 Skill 封装可复用多步骤工作流；用 Plugin 扩展运行时能力；用 MCP 连接外部系统

---

## 15.5 CLI 深度解析

每个 CLI flag 背后是一套精心设计的状态机——核心是交互/非交互的二元模型切换。

- **`isNonInteractive` 四种触发**：`--print`、`--init-only`、`--sdk-url`、`!process.stdout.isTTY`——不加 `--print` 但 stdout 被重定向同样进入批处理模式（CI 常见陷阱）
- **模型四级优先级**：`/model` 命令 > `--model` flag > `ANTHROPIC_MODEL` 环境变量 > 用户设置
- **`--bare` 极简模式**：跳过 hooks、LSP、CLAUDE.md 自动发现、Keychain 读取，是 CI/CD 最快配置；只走 `ANTHROPIC_API_KEY` 环境变量
- **子进程安全清洗**：GHA 环境下 `ANTHROPIC_API_KEY` 等 40+ 敏感变量被静默从子进程环境变量中清除，防止 prompt injection 窃取 secrets
- **权限模式**：`default`（逐次询问）→ `autoEdit`（编辑自动批准）→ `bypassPermissions`（仅限沙箱/CI）；`--dangerously-skip-permissions` 用长名称是刻意制造「设计阻力」
- **CI/CD 黄金配方**：`claude --print --bare --output-format stream-json --dangerously-skip-permissions --model claude-sonnet-4-5 "..."`

---

## 15.6 横向对比：Claude Code vs CatPaw/OpenClaw

两套体系同根异形——本质差异在设计哲学：Claude Code 是「工具视角」（服务于这一次运行），CatPaw/OpenClaw 是「同事视角」（持久化 Agent 有身份有记忆）。

- **Rules 差异**：CLAUDE.md 随工作目录漂移（适合多项目多仓库）；OpenClaw 的 SOUL.md 塑造身份、AGENTS.md 定义全局行为规则（适合持久化个人助手）
- **Commands 差异**：Claude Code 偏工作流触发（`$ARGUMENTS` 参数化）；OpenClaw 内置命令是运行时控制指令（切模型/查状态），不经 AI
- **MCP 角色差异**：Claude Code 是纯 MCP Client；OpenClaw 同时作为 Client（消费外部 Server）和 Server（被其他 Client 连接），还有大量原生工具无需 MCP
- **Skills 生态差异**：OpenClaw 支持热重载（修改 3 秒生效）、Skill 广场（版本管理/权限审计）；Claude Code 更轻量但需重启会话
- **典型误用**：全局规范放进项目 CLAUDE.md（每仓库重复）；临时工作流放进 Skill（应用 Command）；项目规范放进 SOUL.md（身份污染）
- 选型结论：代码开发/CI 集成 → Claude Code；个人全能助手/企业 AI 平台 → CatPaw/OpenClaw

---

## 15.7 实践：搭建完整配置体系

从零到一的正确节奏：先搭 CLAUDE.md（两小时见效），再按需迭代 Commands → Skills → MCP。

- **CLAUDE.md 写作原则**：只写「不写 AI 就会出错」的内容——具体禁止事项（`throw RuntimeException` → `throw BizException`）、领域知识（状态机、业务约束）、常用命令；写「原因」比写「规则」更重要
- **Slash Commands 设计**：`/review-pr`（diff → 检查 → 报告）、`/gen-test`（三类测试：正常/异常/边界）、`/fix-issue`（读工单 → 分析 → 方案）
- **MCP 接入模式**：本地 Python Server（stdio）+ 环境变量传 token；工具细粒度拆分（只读工具加 `readOnlyHint: true` 减少确认弹窗）
- **`/init` 命令**：让 Claude 自动审查代码库并生成 settings.json（hooks + permissions）；`PostToolUse` hook 在每次 Write/Edit 后自动运行 checkstyle
- **permissions 最小化**：`allow: [Bash(mvn:*), Read(**/*.java)]` + `deny: [Bash(rm:*), Write(pom.xml)]`——最小权限原则落地
- **演进路径**：第 1 天 CLAUDE.md → 第 2 周 Commands → 第 1 月 Skills → 第 3 月 MCP；用「修改率 < 10%」和「同类错误重复率」度量配置效果
