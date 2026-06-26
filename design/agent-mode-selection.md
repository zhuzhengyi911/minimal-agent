# Agent 模式选择指南

## 三种模式对比

| | 单 Agent | Subagents | Agent Teams |
|---|---|---|---|
| Token 成本 | 1x | 1.5x | ~5x |
| Agent 间通信 | 无 | 无（结果汇聚到 Coordinator） | 有（Teammate 之间可互相发消息） |
| 适合并行 | 否 | 是 | 是 |
| 需要辩论/多视角 | 否 | 否 | 是 |

## 适用场景

**单 Agent**：任务不可拆分，或拆分收益小于协调成本
- bug 修复、代码解释、单文件重构
- 简单功能添加
- 任务有严格顺序依赖，并行没有意义

**Subagents**：任务可以拆成独立子任务，子任务之间不需要沟通
- 并行探索多个模块（各自负责不同文件）
- 同时跑多个测试场景
- 并行搜索/研究，结果最终汇总
- 关键前提：**子任务产出物互相独立**，Coordinator 只需要聚合结果

**Agent Teams**：子任务之间需要互相挑战、协商、迭代
- 调试竞争假设（三个 Agent 各持一个假设，互相论证）
- 多角度代码审查（安全、性能、测试覆盖三个审查员互相补充）
- 架构设计（支持方 vs 反对方，需要辩论收敛）
- 关键前提：**多个视角的碰撞本身产生价值**，而不只是并行执行

## 决策树

```
任务复杂吗？
├── 否 → 单 Agent
└── 是 → 能拆成独立子任务吗？
         ├── 否（强依赖顺序）→ 单 Agent（多轮对话）
         └── 是 → 子任务需要互相沟通吗？
                  ├── 否 → Subagents（1.5x 成本）
                  └── 是 → Agent Teams（5x 成本，值不值得？）
```

## 常见误区

用 Agent Teams 做 Subagents 能干的事——子任务本来就独立，不需要 Teammate 互相讨论，却付了 5x 的成本。大部分并行开发场景，Subagents 够用。

## 参考

- [subagent.md](subagent.md)：Subagents/Coordinator 模式的架构设计
- Claude Code Agent Teams 官方文档：https://code.claude.com/docs/en/agent-teams
