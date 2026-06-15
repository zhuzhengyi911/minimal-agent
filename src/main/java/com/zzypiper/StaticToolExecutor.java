package com.zzypiper;

import com.zzypiper.module.ToolException;

import java.util.HashMap;
import java.util.Map;

public class StaticToolExecutor implements ToolExecutor {

    @FunctionalInterface
    public interface ToolHandler {
        String handle(String input) throws ToolException;
    }

    public final Map<String, ToolHandler> handlers = new HashMap<>();

    public StaticToolExecutor register(String toolName, ToolHandler toolHandler) {
        handlers.put(toolName, toolHandler);
        return this;
    }

    @Override
    public String execute(String toolName, String input) throws ToolException {
        ToolHandler handler = handlers.get(toolName);
        if (handler == null) {
            throw new ToolException("unknow tool: " + toolName);
        }
        return handler.handle(input);
    }
}
