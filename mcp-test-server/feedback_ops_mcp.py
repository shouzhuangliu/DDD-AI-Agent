"""Local feedback and operations MCP used by feedback-ops-agent.

The server is dependency-free and uses JSON-RPC messages separated by newlines.
Data is stored in FEEDBACK_MCP_DATA_DIR (default: mcp-test-server/data), which
makes the demo persistent without coupling the agent to a database.
"""
from __future__ import annotations

import json
import os
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(os.environ.get("FEEDBACK_MCP_DATA_DIR", Path(__file__).parent / "data"))
ROOT.mkdir(parents=True, exist_ok=True)
FEEDBACK_FILE = ROOT / "feedback.jsonl"
CASE_FILE = ROOT / "cases.jsonl"
EVIDENCE_FILE = ROOT / "evidence.jsonl"

TOOLS = [
    {"name": "create_feedback", "description": "保存一条用户或运维反馈。", "inputSchema": {"type": "object", "properties": {"source": {"type": "string", "enum": ["USER", "OPS", "MONITOR", "INTERNAL"]}, "content": {"type": "string"}, "service": {"type": "string"}, "occurredAt": {"type": "string"}, "contact": {"type": "string"}}, "required": ["source", "content"]}},
    {"name": "search_feedback", "description": "按关键词、来源或服务查询反馈。", "inputSchema": {"type": "object", "properties": {"query": {"type": "string"}, "source": {"type": "string"}, "service": {"type": "string"}, "limit": {"type": "integer"}}}},
    {"name": "get_feedback_detail", "description": "读取一条反馈的完整内容。", "inputSchema": {"type": "object", "properties": {"feedbackId": {"type": "string"}}, "required": ["feedbackId"]}},
    {"name": "promote_feedback_to_case", "description": "在人工确认后将反馈升级为 Case。", "inputSchema": {"type": "object", "properties": {"feedbackId": {"type": "string"}, "category": {"type": "string"}, "priority": {"type": "string", "enum": ["P0", "P1", "P2", "P3"]}, "reason": {"type": "string"}}, "required": ["feedbackId", "category", "priority", "reason"]}},
    {"name": "append_case_evidence", "description": "为 Case 追加可审计证据。", "inputSchema": {"type": "object", "properties": {"caseId": {"type": "string"}, "kind": {"type": "string"}, "content": {"type": "string"}, "source": {"type": "string"}}, "required": ["caseId", "kind", "content", "source"]}},
    {"name": "get_case_timeline", "description": "读取 Case 和证据时间线。", "inputSchema": {"type": "object", "properties": {"caseId": {"type": "string"}}, "required": ["caseId"]}},
    {"name": "search_incidents", "description": "查询本地演示事件记录。", "inputSchema": {"type": "object", "properties": {"service": {"type": "string"}, "query": {"type": "string"}, "limit": {"type": "integer"}}}},
    {"name": "get_service_health", "description": "读取服务健康快照，只读诊断。", "inputSchema": {"type": "object", "properties": {"service": {"type": "string"}}, "required": ["service"]}},
]


def now():
    return datetime.now(timezone.utc).isoformat()


def read_lines(path):
    if not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            print(f"ignored invalid record in {path}", file=sys.stderr)
    return rows


def append(path, value):
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(value, ensure_ascii=False) + "\n")


def required(args, name):
    value = str(args.get(name, "")).strip()
    if not value:
        raise ValueError(f"{name} 不能为空")
    return value


def call_tool(name, args):
    args = args or {}
    if name == "create_feedback":
        content = required(args, "content")
        row = {"feedbackId": "fb-" + uuid.uuid4().hex[:12], "source": required(args, "source"), "content": content, "service": str(args.get("service", "")), "occurredAt": str(args.get("occurredAt", now())), "contact": str(args.get("contact", "")), "status": "CANDIDATE", "createdAt": now()}
        append(FEEDBACK_FILE, row)
        return row
    if name == "search_feedback":
        query, source, service = str(args.get("query", "")).lower(), str(args.get("source", "")).upper(), str(args.get("service", "")).lower()
        rows = [r for r in read_lines(FEEDBACK_FILE) if (not query or query in json.dumps(r, ensure_ascii=False).lower()) and (not source or r.get("source") == source) and (not service or service in r.get("service", "").lower())]
        return rows[-max(1, min(int(args.get("limit", 20)), 100)):]
    if name == "get_feedback_detail":
        feedback_id = required(args, "feedbackId")
        row = next((r for r in read_lines(FEEDBACK_FILE) if r.get("feedbackId") == feedback_id), None)
        if not row:
            raise ValueError("反馈不存在")
        return row
    if name == "promote_feedback_to_case":
        feedback_id = required(args, "feedbackId")
        if not next((r for r in read_lines(FEEDBACK_FILE) if r.get("feedbackId") == feedback_id), None):
            raise ValueError("反馈不存在")
        case = {"caseId": "case-" + uuid.uuid4().hex[:12], "feedbackId": feedback_id, "category": required(args, "category"), "priority": required(args, "priority"), "reason": required(args, "reason"), "status": "PENDING_REVIEW", "createdAt": now()}
        append(CASE_FILE, case)
        return case
    if name == "append_case_evidence":
        case_id = required(args, "caseId")
        if not next((r for r in read_lines(CASE_FILE) if r.get("caseId") == case_id), None):
            raise ValueError("Case 不存在")
        evidence = {"evidenceId": "ev-" + uuid.uuid4().hex[:12], "caseId": case_id, "kind": required(args, "kind"), "content": required(args, "content"), "source": required(args, "source"), "createdAt": now()}
        append(EVIDENCE_FILE, evidence)
        return evidence
    if name == "get_case_timeline":
        case_id = required(args, "caseId")
        case = next((r for r in read_lines(CASE_FILE) if r.get("caseId") == case_id), None)
        if not case:
            raise ValueError("Case 不存在")
        return {"case": case, "evidence": [r for r in read_lines(EVIDENCE_FILE) if r.get("caseId") == case_id]}
    if name == "search_incidents":
        rows = read_lines(ROOT / "incidents.jsonl")
        query, service = str(args.get("query", "")).lower(), str(args.get("service", "")).lower()
        return [r for r in rows if (not query or query in json.dumps(r, ensure_ascii=False).lower()) and (not service or service in r.get("service", "").lower())][-max(1, min(int(args.get("limit", 20)), 100)):]
    if name == "get_service_health":
        service = required(args, "service")
        rows = read_lines(ROOT / "service_health.jsonl")
        return next((r for r in reversed(rows) if r.get("service") == service), {"service": service, "status": "UNKNOWN", "message": "没有本地健康快照", "checkedAt": now()})
    raise ValueError(f"未知工具: {name}")


def reply(request_id, result=None, error=None):
    message = {"jsonrpc": "2.0", "id": request_id}
    message["error" if error else "result"] = error if error else result
    print(json.dumps(message, ensure_ascii=False, separators=(",", ":")), flush=True)


for line in sys.stdin:
    message = None
    try:
        message = json.loads(line)
        if message.get("id") is None:
            continue
        method, request_id = message.get("method"), message.get("id")
        if method == "initialize":
            reply(request_id, {"protocolVersion": "2024-11-05", "capabilities": {"tools": {}}, "serverInfo": {"name": "feedback-ops-mcp", "version": "1.0.0"}})
        elif method == "ping":
            reply(request_id, {})
        elif method == "tools/list":
            reply(request_id, {"tools": TOOLS})
        elif method == "tools/call":
            params = message.get("params", {})
            reply(request_id, {"content": [{"type": "text", "text": json.dumps(call_tool(params.get("name", ""), params.get("arguments")), ensure_ascii=False)}]})
        else:
            reply(request_id, error={"code": -32601, "message": f"方法不存在: {method}"})
    except Exception as exc:
        reply(message.get("id") if isinstance(message, dict) else None, error={"code": -32602, "message": str(exc)})
