package com.zzypiper;

import com.zzypiper.module.ModeEnum;
import com.zzypiper.module.Outcome;

public class PermissionPolicy {
    private final ModeEnum mode;

    public PermissionPolicy(ModeEnum mode) {
        this.mode = mode;
    }

    public Outcome authorize(String toolName, String input) {
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
        // 简化逻辑：包含"danger"或"bash"就需要完全权限
        String lower = toolName.toLowerCase();
        return lower.contains("danger")
                || lower.contains("delete")
                || lower.contains("rm");
    }

    private boolean isReadTool(String toolName) {
        // 简化逻辑
        String lower = toolName.toLowerCase();
        return lower.contains("read")
                || lower.contains("list")
                || lower.contains("get")
                || lower.contains("echo")
                || lower.equals("add");
    }
}
