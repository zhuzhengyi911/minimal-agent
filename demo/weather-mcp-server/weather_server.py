#!/usr/bin/env python3
"""
Weather MCP Server

通过 wttr.in 查询城市天气，无需 API Key。
MCP 传输协议：stdio，换行符分隔的 JSON-RPC 2.0 消息。
"""

import json
import sys
import urllib.request
import urllib.parse


TOOLS = [
    {
        "name": "get_weather",
        "description": "查询指定城市的当前天气情况",
        "inputSchema": {
            "type": "object",
            "properties": {
                "city": {
                    "type": "string",
                    "description": "城市名称，支持中英文，如 Beijing 或 北京"
                }
            },
            "required": ["city"]
        }
    }
]


def get_weather(city: str) -> str:
    url = "https://wttr.in/" + urllib.parse.quote(city) + "?format=3"
    req = urllib.request.Request(url, headers={"User-Agent": "curl/7.0"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return resp.read().decode("utf-8").strip()


def handle(request: dict):
    method = request.get("method", "")
    req_id = request.get("id")

    # 通知类消息（无 id）不需要响应
    if method == "notifications/initialized":
        return None

    if method == "initialize":
        return ok(req_id, {
            "protocolVersion": "2024-11-05",
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "weather-mcp-server", "version": "0.1.0"}
        })

    if method == "tools/list":
        return ok(req_id, {"tools": TOOLS})

    if method == "tools/call":
        params = request.get("params", {})
        tool_name = params.get("name")
        arguments = params.get("arguments", {})

        if tool_name == "get_weather":
            city = arguments.get("city", "").strip()
            if not city:
                return tool_error(req_id, "参数 city 不能为空")
            try:
                result = get_weather(city)
                return tool_ok(req_id, result)
            except Exception as e:
                return tool_error(req_id, f"查询失败: {e}")

        return error(req_id, -32602, f"未知工具: {tool_name}")

    return error(req_id, -32601, f"Method not found: {method}")


# ── 响应构造 ──────────────────────────────────────────────

def ok(req_id, result):
    return {"jsonrpc": "2.0", "id": req_id, "result": result}

def error(req_id, code, message):
    return {"jsonrpc": "2.0", "id": req_id, "error": {"code": code, "message": message}}

def tool_ok(req_id, text):
    return ok(req_id, {"content": [{"type": "text", "text": text}]})

def tool_error(req_id, message):
    return ok(req_id, {"content": [{"type": "text", "text": message}], "isError": True})


# ── 主循环 ────────────────────────────────────────────────

def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
            response = handle(request)
            if response is not None:
                print(json.dumps(response, ensure_ascii=False), flush=True)
        except json.JSONDecodeError as e:
            print(json.dumps(error(None, -32700, f"Parse error: {e}")), flush=True)
        except Exception as e:
            print(json.dumps(error(None, -32603, f"Internal error: {e}")), flush=True)


if __name__ == "__main__":
    main()
