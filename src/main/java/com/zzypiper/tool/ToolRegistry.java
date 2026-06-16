package com.zzypiper.tool;

import com.zzypiper.permission.ModeEnum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表——注册层的核心组件。
 *
 * <h2>职责边界</h2>
 * <p>{@code ToolRegistry} 只负责"记录有哪些工具"，不负责过滤、也不负责执行。
 * <ul>
 *   <li>注册：{@link #register(ToolSpec, ToolHandler)} 同时存入完整规格（{@link ToolSpec}）和执行逻辑</li>
 *   <li>查询：{@link #getSpec(String)} / {@link #getSpecs()} 供上层读取</li>
 * </ul>
 *
 * <h2>与其他组件的关系</h2>
 * <pre>
 *  ToolRegistry                    ← 注册层（本类）
 *    ├── ToolSpec (name/desc/schema/requiredMode)  ← 完整规格
 *    └── ToolHandler                               ← 执行逻辑
 *
 *  PermissionPolicy                ← 根据 ToolSpec.requiredMode 做运行时过滤/校验
 *  BuiltinTool 各实现类             ← 执行层，注册时通过 tool::execute 桥接进 ToolRegistry
 * </pre>
 *
 * <h2>插入顺序保留</h2>
 * <p>内部使用 {@link LinkedHashMap} 保证工具的注册顺序，从而保证发给 LLM 的
 * {@code tools} 数组顺序确定，方便调试和测试。
 */
public class ToolRegistry {

    /**
     * 工具执行逻辑的函数式接口。
     * <p>注册时传入，由 {@link com.zzypiper.agent.Agent} 在执行工具调用时取出执行。
     */
    @FunctionalInterface
    public interface ToolHandler {
        String handle(String input) throws ToolException;
    }

    // name → 完整规格（用于权限校验、生成 ToolDefinition）
    private final Map<String, ToolSpec> specs = new LinkedHashMap<>();
    // name → 执行逻辑（用于工具执行）
    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();

    /**
     * 注册一个工具。
     *
     * @param spec    工具的完整规格，包含名称、描述、参数 Schema 和所需权限级别
     * @param handler 工具的实际执行逻辑
     * @return {@code this}，支持链式调用
     */
    public ToolRegistry register(ToolSpec spec, ToolHandler handler) {
        specs.put(spec.name(), spec);
        handlers.put(spec.name(), handler);
        return this;
    }

    /**
     * 按名称查找工具规格。
     *
     * @return 找到则返回 {@link ToolSpec}，否则返回 {@code null}
     */
    public ToolSpec getSpec(String name) {
        return specs.get(name);
    }

    /**
     * 返回所有已注册工具的规格，保持注册顺序，不可修改。
     */
    public Map<String, ToolSpec> getSpecs() {
        return Collections.unmodifiableMap(specs);
    }

    /**
     * 返回指定工具的执行逻辑，供执行层调用。
     *
     * @return 找到则返回 {@link ToolHandler}，否则返回 {@code null}
     */
    public ToolHandler getHandler(String name) {
        return handlers.get(name);
    }

    /**
     * 判断工具是否已注册。
     */
    public boolean contains(String name) {
        return specs.containsKey(name);
    }

    /**
     * 返回当前已注册的工具数量。
     */
    public int size() {
        return specs.size();
    }

    /**
     * 根据当前权限模式，过滤出允许使用的工具定义列表，用于发给 LLM。
     *
     * <p>过滤规则：{@code spec.requiredMode().ordinal() <= currentMode.ordinal()}，
     * 即只返回所需权限不超过当前模式的工具。
     *
     * <p>对应 Claude Code Rust 层 {@code GlobalToolRegistry::definitions(allowed_tools)}：
     * 每次 API 请求前动态计算，而非静态列表，保证权限降级时工具集合即时收缩。
     *
     * @param currentMode 当前运行时权限模式
     * @return 允许使用的工具定义列表（保持注册顺序）
     */
    public List<ToolDefinition> getDefinitions(ModeEnum currentMode) {
        List<ToolDefinition> result = new ArrayList<>();
        for (ToolSpec spec : specs.values()) {
            if (spec.requiredMode().ordinal() <= currentMode.ordinal()) {
                result.add(spec.toDefinition());
            }
        }
        return Collections.unmodifiableList(result);
    }
}
