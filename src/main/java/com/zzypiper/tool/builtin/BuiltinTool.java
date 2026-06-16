package com.zzypiper.tool.builtin;

import com.zzypiper.tool.ToolException;
import com.zzypiper.tool.ToolSpec;

/**
 * 内置工具的公共接口。
 *
 * <p>每个内置工具自治管理自身的规格（{@link ToolSpec}）和执行逻辑，
 * 启动时统一注册进 {@link com.zzypiper.tool.ToolRegistry}：
 *
 * <pre>
 * List.of(new ReadFileTool(), new WriteFileTool(), ...)
 *     .forEach(tool -> registry.register(tool.spec(), tool::execute));
 * </pre>
 */
public interface BuiltinTool {

    /** 返回该工具的完整规格，包括名称、描述、参数 schema 和所需权限级别。 */
    ToolSpec spec();

    /** 执行工具调用，{@code input} 为 JSON 字符串。 */
    String execute(String input) throws ToolException;
}
