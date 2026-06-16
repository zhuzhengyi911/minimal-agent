package com.zzypiper.tool;

import com.zzypiper.permission.ModeEnum;

/**
 * 工具的完整规格说明——注册层使用的内部数据结构。
 *
 * <h2>三层工具体系中的位置</h2>
 * <pre>
 *  ┌─────────────────────────────────────────────────────────────┐
 *  │  协议层  ToolDefinition  →  序列化后发给 LLM                  │
 *  │          name / description / inputSchemaJson               │
 *  ├─────────────────────────────────────────────────────────────┤
 *  │  注册层  ToolSpec        →  Agent 运行时内部持有              │
 *  │          name / description / inputSchemaJson / requiredMode │
 *  ├─────────────────────────────────────────────────────────────┤
 *  │  执行层  BuiltinTool 各实现类  →  实际运行工具逻辑               │
 *  └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>ToolSpec 与 ToolDefinition 的关系</h2>
 * <ul>
 *   <li>{@code ToolSpec} 是工具的"完整身份证"，供 Agent 运行时内部使用，包含权限声明。</li>
 *   <li>{@link ToolDefinition} 是工具的"对外接口说明"，序列化后发给 LLM，
 *       <strong>不包含 {@code requiredMode}</strong>。</li>
 *   <li>转换方向单向：{@code ToolSpec → ToolDefinition}，通过 {@link #toDefinition()} 完成，
 *       转换时故意丢弃 {@code requiredMode}。</li>
 * </ul>
 *
 * <h2>为什么 LLM 不应该看到 requiredMode（最小知识原则）</h2>
 * <p>权限级别是 Agent 运行时的内部安全策略，与 LLM 的"我要调用哪个工具"决策无关。
 * 把 {@code requiredMode} 暴露给 LLM 既无意义，也可能被提示注入攻击利用。
 * 因此 {@link ToolDefinition} 仅保留 LLM 做决策所需的最少字段。
 *
 * <h2>权限校验流程</h2>
 * <p>LLM 返回 tool_use → Agent 从注册表查找对应 {@code ToolSpec} →
 * 比较 {@code ToolSpec.requiredMode} 与当前 {@link com.zzypiper.permission.PermissionPolicy}
 * 的模式（{@code READ_ONLY < WORKSPACE_WRITE < DANGER_FULL_ACCESS}）→
 * 不足则拒绝执行，足够则放行给 {@link ToolRegistry} 中对应的 {@link ToolRegistry.ToolHandler}。
 *
 * <p>对应 Claude Code 源码参考：{@code rust/crates/tools/src/lib.rs}
 */
public record ToolSpec(
        String name,
        String description,
        String inputSchemaJson,

        /**
         * 执行此工具所需的最低权限级别。
         * <p>此字段仅供运行时权限校验使用，<strong>不会发送给 LLM</strong>。
         * 未知工具默认应要求 {@link ModeEnum#DANGER_FULL_ACCESS}（安全兜底原则）。
         */
        ModeEnum requiredMode
) {
    /**
     * 将 ToolSpec 裁剪为 {@link ToolDefinition}，用于填入 {@link com.zzypiper.api.ApiRequest#getTools()}
     * 发送给 LLM。
     *
     * <p>转换过程中丢弃 {@code requiredMode}：LLM 只需要知道工具叫什么、做什么、
     * 参数长什么样，不需要知道 Agent 内部的权限控制策略。
     */
    public ToolDefinition toDefinition() {
        return new ToolDefinition(name, description, inputSchemaJson);
    }
}
