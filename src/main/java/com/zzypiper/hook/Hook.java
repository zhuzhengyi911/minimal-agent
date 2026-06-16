package com.zzypiper.hook;

public interface Hook {
    HookDecision run(String toolName, String input, String output);
}
