package com.zzypiper;

import com.zzypiper.module.HookDecision;

public interface Hook {
    HookDecision run(String toolName, String input, String output);
}
