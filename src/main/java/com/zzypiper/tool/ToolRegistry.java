package com.zzypiper.tool;

import com.zzypiper.permission.ModeEnum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 工具注册表——注册层的核心组件。
 *
 * <h2>职责边界</h2>
 * <p>{@code ToolRegistry} 只负责"记录有哪些工具"，不负责过滤、也不负责执行。
 * <ul>
 *   <li>注册：{@link #register(ToolSpec, ToolHandler)} 同时存入完整规格和执行逻辑</li>
 *   <li>查询：{@link #getSpec(String)} / {@link #getSpecs()} 供上层读取</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>内部使用 {@link ReentrantReadWriteLock}：多个线程可并发读，写操作（注册）独占锁。
 * MCP 工具在后台线程动态注册时与 Agent 主线程并发读取均安全。
 */
public class ToolRegistry {

    @FunctionalInterface
    public interface ToolHandler {
        String handle(String input) throws ToolException;
    }

    private final Map<String, ToolSpec>   specs    = new LinkedHashMap<>();
    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();

    private final ReadWriteLock lock      = new ReentrantReadWriteLock();
    private final java.util.concurrent.locks.Lock readLock  = lock.readLock();
    private final java.util.concurrent.locks.Lock writeLock = lock.writeLock();

    public ToolRegistry register(ToolSpec spec, ToolHandler handler) {
        writeLock.lock();
        try {
            specs.put(spec.name(), spec);
            handlers.put(spec.name(), handler);
        } finally {
            writeLock.unlock();
        }
        return this;
    }

    public ToolSpec getSpec(String name) {
        readLock.lock();
        try {
            return specs.get(name);
        } finally {
            readLock.unlock();
        }
    }

    public Map<String, ToolSpec> getSpecs() {
        readLock.lock();
        try {
            return Collections.unmodifiableMap(new LinkedHashMap<>(specs));
        } finally {
            readLock.unlock();
        }
    }

    public ToolHandler getHandler(String name) {
        readLock.lock();
        try {
            return handlers.get(name);
        } finally {
            readLock.unlock();
        }
    }

    public boolean contains(String name) {
        readLock.lock();
        try {
            return specs.containsKey(name);
        } finally {
            readLock.unlock();
        }
    }

    public int size() {
        readLock.lock();
        try {
            return specs.size();
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 根据当前权限模式过滤出允许使用的工具定义列表，用于发给 LLM。
     * 每次 turn 开始时调用，动态反映最新注册的工具（包括运行时新增的 MCP 工具）。
     */
    public List<ToolDefinition> getDefinitions(ModeEnum currentMode) {
        readLock.lock();
        try {
            List<ToolDefinition> result = new ArrayList<>();
            for (ToolSpec spec : specs.values()) {
                if (spec.requiredMode().ordinal() <= currentMode.ordinal()) {
                    result.add(spec.toDefinition());
                }
            }
            return Collections.unmodifiableList(result);
        } finally {
            readLock.unlock();
        }
    }
}
