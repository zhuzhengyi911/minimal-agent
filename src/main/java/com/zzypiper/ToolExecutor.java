package com.zzypiper;

import com.zzypiper.module.ToolException;

public interface ToolExecutor {

    String execute(String toolName, String input) throws ToolException;

}
