"""Small local MCP server for testing the Java Agent integration.

Transport: MCP JSON-RPC over stdin/stdout, one JSON object per line.
The process writes diagnostics to stderr so stdout remains protocol-safe.
"""

from __future__ import annotations

import json
import sys
from datetime import datetime, timezone


TOOLS = [
    {
        "name": "echo",
        "description": "Return the supplied text.",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    },
    {
        "name": "add",
        "description": "Add two numbers.",
        "inputSchema": {
            "type": "object",
            "properties": {"a": {"type": "number"}, "b": {"type": "number"}},
            "required": ["a", "b"],
        },
    },
    {
        "name": "current_time",
        "description": "Return the current UTC time.",
        "inputSchema": {"type": "object", "properties": {}},
    },
]


def reply(request_id, result=None, error=None):
    message = {"jsonrpc": "2.0", "id": request_id}
    if error is not None:
        message["error"] = error
    else:
        message["result"] = result
    sys.stdout.write(json.dumps(message, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def call_tool(name, arguments):
    arguments = arguments or {}
    if name == "echo":
        return f"echo: {arguments.get('text', '')}"
    if name == "add":
        return str(float(arguments.get("a", 0)) + float(arguments.get("b", 0)))
    if name == "current_time":
        return datetime.now(timezone.utc).isoformat()
    raise ValueError(f"Unknown tool: {name}")


def handle(message):
    method = message.get("method")
    request_id = message.get("id")
    if request_id is None:
        return
    if method == "initialize":
        reply(
            request_id,
            {
                "protocolVersion": message.get("params", {}).get("protocolVersion", "2024-11-05"),
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "local-test-mcp", "version": "1.0.0"},
            },
        )
    elif method == "ping":
        reply(request_id, {})
    elif method == "tools/list":
        reply(request_id, {"tools": TOOLS})
    elif method == "tools/call":
        params = message.get("params", {})
        try:
            value = call_tool(params.get("name", ""), params.get("arguments"))
            reply(request_id, {"content": [{"type": "text", "text": value}]})
        except (TypeError, ValueError) as exc:
            reply(request_id, error={"code": -32602, "message": str(exc)})
    else:
        reply(request_id, error={"code": -32601, "message": f"Method not found: {method}"})


for line in sys.stdin:
    try:
        handle(json.loads(line))
    except json.JSONDecodeError as exc:
        print(f"invalid JSON: {exc}", file=sys.stderr)
    except Exception as exc:  # Keep the test process alive for the next request.
        print(f"server error: {exc}", file=sys.stderr)
