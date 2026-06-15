package com.zzypiper;

import com.zzypiper.module.HookDecision;
import com.zzypiper.module.HookResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Data
@AllArgsConstructor
public class HookRunner {

    private final List<Hook> preToolUseHooks;
    private final List<Hook> postToolUseHooks;

    public HookResult runPreToolUse(String toolName, String input) {
        return runHooks(preToolUseHooks, toolName, input, null);
    }

    public HookResult runPostToolUse(String toolName, String input, String output) {
        return runHooks(postToolUseHooks, toolName, input, output);
    }

    private HookResult runHooks(List<Hook> hooks, String toolName, String input, String output) {
        if (hooks.isEmpty()) {
            return HookResult.allow(Collections.emptyList());
        }
        List<String> messages = new ArrayList<>();

        for (Hook hook : hooks) {
            HookDecision decision = hook.run(toolName, input, output != null ? output : "");
            switch (decision){
                case ALLOW -> { /* 继续执行下一个Hook */ }
                case DENY -> {
                    // 一旦有Hook拒绝，立即返回，不再执行后续Hook
                    String denyMsg = "Hook denied tool '" + toolName + "'";
                    messages.add(denyMsg);
                    return HookResult.deny(denyMsg);
                }
                case WARN -> {
                    // 记录告警，继续执行
                    messages.add("Hook warning for tool '" + toolName + "'");
                }
            }
        }

        return HookResult.allow(messages);
    }
}
