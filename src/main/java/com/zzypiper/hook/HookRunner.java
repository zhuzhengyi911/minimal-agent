package com.zzypiper.hook;

import lombok.AllArgsConstructor;
import lombok.Data;

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
            switch (decision) {
                case ALLOW -> { /* 继续执行下一个Hook */ }
                case DENY -> {
                    String denyMsg = "Hook denied tool '" + toolName + "'";
                    messages.add(denyMsg);
                    return HookResult.deny(denyMsg);
                }
                case WARN -> messages.add("Hook warning for tool '" + toolName + "'");
            }
        }

        return HookResult.allow(messages);
    }
}
