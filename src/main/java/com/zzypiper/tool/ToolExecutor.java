package com.zzypiper.tool;

public interface ToolExecutor {

    String execute(String toolName, String input) throws ToolException;
}
