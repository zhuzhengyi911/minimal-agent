package com.zzypiper.hook;

import lombok.Data;

import java.util.Arrays;
import java.util.List;

@Data
public class HookResult {
    private final boolean denied;
    public final List<String> messages;

    public static HookResult allow(List<String> messages) {
        return new HookResult(false, messages);
    }

    public static HookResult deny(String reason) {
        return new HookResult(true, Arrays.asList(reason));
    }
}
