package com.zzypiper.permission;

import com.zzypiper.tool.ToolRegistry;
import com.zzypiper.tool.ToolSpec;

public class PermissionPolicy {
    private ModeEnum mode;
    private final ToolRegistry registry;  // nullable — may be null for legacy usage

    public PermissionPolicy(ModeEnum mode) {
        this(mode, null);
    }

    /**
     * 带注册表的构造器，优先使用 {@link ToolSpec#requiredMode()} 做精确权限校验。
     * 对注册表中没有的工具，回退到字符串启发式判断。
     */
    public PermissionPolicy(ModeEnum mode, ToolRegistry registry) {
        this.mode = mode;
        this.registry = registry;
    }

    public ModeEnum getMode() {
        return mode;
    }

    /** 运行时切换权限模式（用于 /mode 命令）。 */
    public void setMode(ModeEnum mode) {
        this.mode = mode;
    }

    public Outcome authorize(String toolName, String input) {
        // 优先走 spec-based 精确校验
        if (registry != null) {
            ToolSpec spec = registry.getSpec(toolName);
            if (spec != null) {
                if (spec.requiredMode().ordinal() <= mode.ordinal()) {
                    return new Outcome.Allow();
                } else {
                    return new Outcome.Deny("tool '" + toolName + "' requires "
                            + spec.requiredMode() + " but current mode is " + mode);
                }
            }
            // 工具不在注册表中：安全兜底，拒绝执行
            return new Outcome.Deny("tool '" + toolName + "' is not registered");
        }

        // 无注册表时回退到字符串启发式（兼容旧测试）
        return switch (mode) {
            case DANGER_FULL_ACCESS -> new Outcome.Allow();
            case WORKSPACE_WRITE -> {
                if (isDangerousOnly(toolName)) {
                    yield new Outcome.Deny("tool '" + toolName + "' requires DangerFullAccess mode");
                }
                yield new Outcome.Allow();
            }
            case READ_ONLY -> {
                if (!isReadTool(toolName)) {
                    yield new Outcome.Deny("tool '" + toolName + "' not allowed in ReadOnly mode");
                }
                yield new Outcome.Allow();
            }
        };
    }

    private boolean isDangerousOnly(String toolName) {
        String lower = toolName.toLowerCase();
        return lower.contains("danger")
                || lower.contains("delete")
                || lower.contains("rm");
    }

    private boolean isReadTool(String toolName) {
        String lower = toolName.toLowerCase();
        return lower.contains("read")
                || lower.contains("list")
                || lower.contains("get")
                || lower.contains("echo")
                || lower.equals("add");
    }
}
