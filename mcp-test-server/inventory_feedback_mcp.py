"""库存业务 Feedback MCP。

Transport: JSON-RPC over stdin/stdout，一行一个 JSON 对象。

这个脚本故意做成“本地可运行、数据写死”的 MCP Server，用来演示：
1. 智能体通过 MCP 拉取今日用户/运维/监控 Feedback；
2. 再结合库存业务 Skill 判断是否应升级为 Case；
3. 将分诊结果写入本地 jsonl，形成闭环。
"""

from __future__ import annotations

import json
import os
import sys
import uuid
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(os.environ.get("INVENTORY_FEEDBACK_MCP_DATA_DIR", Path(__file__).parent / "data"))
ROOT.mkdir(parents=True, exist_ok=True)
TRIAGE_FILE = ROOT / "inventory_triage.jsonl"

if hasattr(sys.stdin, "reconfigure"):
    sys.stdin.reconfigure(encoding="utf-8")
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

TODAY = date.today().isoformat()


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


INVENTORY_SERVICES = [
    {"service": "inventory-core", "label": "库存中心", "owner": "supply-chain"},
    {"service": "stock-reservation", "label": "锁库存服务", "owner": "order-center"},
    {"service": "product-availability", "label": "商品可售服务", "owner": "product-center"},
    {"service": "replenishment-sync", "label": "补货同步服务", "owner": "warehouse-ops"},
]

TODAY_FEEDBACK = [
    {
        "feedbackId": "fb-inv-20260731-001",
        "source": "USER",
        "service": "product-availability",
        "summary": "DDR5 内存详情页显示可下单，但支付后被通知缺货",
        "content": "用户反馈 DDR5 32G 5600MHz 内存商品详情页显示有货，可正常下单；支付后客服通知实际缺货，希望尽快补货并排查库存展示问题。",
        "occurredAt": f"{TODAY}T09:12:00+08:00",
        "tags": ["DDR5", "缺货", "前台可售", "超卖风险"],
        "severityHint": "P1",
        "triageStatus": "NEW",
    },
    {
        "feedbackId": "fb-inv-20260731-002",
        "source": "OPS",
        "service": "inventory-core",
        "summary": "运营反馈 RTX 5060 库存缓存与后台库存不一致",
        "content": "运营巡检发现 RTX 5060 显卡在频道页展示剩余 12 件，但后台库存中心实际只有 2 件，怀疑缓存未及时刷新。",
        "occurredAt": f"{TODAY}T10:05:00+08:00",
        "tags": ["5060", "库存不一致", "缓存", "频道页"],
        "severityHint": "P1",
        "triageStatus": "NEW",
    },
    {
        "feedbackId": "fb-inv-20260731-003",
        "source": "MONITOR",
        "service": "stock-reservation",
        "summary": "锁库存释放延迟导致部分商品短时间无法再次下单",
        "content": "监控显示锁库存释放任务出现积压，部分取消订单后的库存没有及时回补，影响用户再次下单。",
        "occurredAt": f"{TODAY}T11:26:00+08:00",
        "tags": ["锁库存", "释放延迟", "再次下单失败"],
        "severityHint": "P0",
        "triageStatus": "NEW",
    },
    {
        "feedbackId": "fb-inv-20260731-004",
        "source": "USER",
        "service": "replenishment-sync",
        "summary": "用户希望补货一款 2TB SSD，但暂未提供 SKU",
        "content": "用户表示一款 2TB SSD 长期缺货，希望平台补货，但没有提供品牌、型号或商品 ID。",
        "occurredAt": f"{TODAY}T13:42:00+08:00",
        "tags": ["补货诉求", "信息不足", "SSD"],
        "severityHint": "P3",
        "triageStatus": "NEW",
    },
    {
        "feedbackId": "fb-inv-20260731-005",
        "source": "OPS",
        "service": "inventory-core",
        "summary": "订单创建成功但库存扣减流水缺失，需要人工关注",
        "content": "运营在复盘售卖活动时发现部分订单创建成功，但库存扣减流水没有按预期写入，怀疑存在扣减异常。",
        "occurredAt": f"{TODAY}T15:08:00+08:00",
        "tags": ["库存扣减", "订单创建", "日志缺失"],
        "severityHint": "P0",
        "triageStatus": "NEW",
    },
]

TOOLS = [
    {
        "name": "get_today_feedback",
        "description": "读取今日库存业务 Feedback 列表，支持按来源、服务域和数量过滤。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "limit": {"type": "integer", "description": "最多返回条数，默认返回全部，最大 50"},
                "source": {"type": "string", "description": "来源：USER / OPS / MONITOR"},
                "service": {"type": "string", "description": "库存服务域，例如 inventory-core"},
            },
        },
    },
    {
        "name": "get_feedback_detail",
        "description": "读取单条库存 Feedback 的完整详情。",
        "inputSchema": {
            "type": "object",
            "properties": {"feedbackId": {"type": "string", "description": "Feedback 编号"}},
            "required": ["feedbackId"],
        },
    },
    {
        "name": "list_inventory_services",
        "description": "列出库存业务相关服务域，用于判断 Feedback 归属。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "search_feedback_by_keyword",
        "description": "按关键词搜索今日库存 Feedback。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "关键词，例如 DDR5、5060、锁库存"},
                "limit": {"type": "integer", "description": "最多返回条数，默认返回全部，最大 50"},
            },
            "required": ["query"],
        },
    },
    {
        "name": "mark_feedback_triaged",
        "description": "写入一条本地分诊记录，用于演示 Feedback -> Case 评测闭环。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "feedbackId": {"type": "string", "description": "Feedback 编号"},
                "decision": {"type": "string", "description": "分诊结论，例如 PROMOTE_CASE / NEED_MORE_INFO / IGNORE"},
                "operator": {"type": "string", "description": "执行分诊的智能体或操作人"},
                "note": {"type": "string", "description": "分诊说明"},
            },
            "required": ["feedbackId", "decision", "operator"],
        },
    },
]


def _read_triage_rows() -> list[dict[str, Any]]:
    if not TRIAGE_FILE.exists():
        return []
    rows = []
    for line in TRIAGE_FILE.read_text(encoding="utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def _append_triage_row(row: dict[str, Any]) -> None:
    with TRIAGE_FILE.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(row, ensure_ascii=False) + "\n")


def _required(arguments: dict[str, Any], name: str) -> str:
    value = str(arguments.get(name, "")).strip()
    if not value:
        raise ValueError(f"{name} 不能为空")
    return value


def _feedback_by_id(feedback_id: str) -> dict[str, Any] | None:
    return next((item for item in TODAY_FEEDBACK if item["feedbackId"] == feedback_id), None)


def call_tool(name: str, arguments: dict[str, Any] | None) -> Any:
    arguments = arguments or {}
    if name == "get_today_feedback":
        source = str(arguments.get("source", "")).upper().strip()
        service = str(arguments.get("service", "")).strip().lower()
        limit = max(1, min(int(arguments.get("limit", len(TODAY_FEEDBACK))), 50))
        rows = [
            item for item in TODAY_FEEDBACK
            if (not source or item["source"] == source)
            and (not service or item["service"].lower() == service)
        ]
        return rows[:limit]
    if name == "get_feedback_detail":
        feedback_id = _required(arguments, "feedbackId")
        detail = _feedback_by_id(feedback_id)
        if not detail:
            raise ValueError("未找到对应的反馈")
        return detail
    if name == "list_inventory_services":
        return INVENTORY_SERVICES
    if name == "search_feedback_by_keyword":
        query = _required(arguments, "query").lower()
        limit = max(1, min(int(arguments.get("limit", len(TODAY_FEEDBACK))), 50))
        rows = [
            item for item in TODAY_FEEDBACK
            if query in json.dumps(item, ensure_ascii=False).lower()
        ]
        return rows[:limit]
    if name == "mark_feedback_triaged":
        feedback_id = _required(arguments, "feedbackId")
        if not _feedback_by_id(feedback_id):
            raise ValueError("未找到对应的反馈")
        row = {
            "triageId": "triage-" + uuid.uuid4().hex[:12],
            "feedbackId": feedback_id,
            "decision": _required(arguments, "decision"),
            "operator": _required(arguments, "operator"),
            "note": str(arguments.get("note", "")).strip(),
            "createdAt": now(),
        }
        _append_triage_row(row)
        return row
    raise ValueError(f"未知工具: {name}")


def reply(request_id: Any, result: Any = None, error: dict[str, Any] | None = None) -> None:
    message = {"jsonrpc": "2.0", "id": request_id}
    if error is not None:
        message["error"] = error
    else:
        message["result"] = result
    sys.stdout.write(json.dumps(message, ensure_ascii=False, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def handle(message: dict[str, Any]) -> None:
    request_id = message.get("id")
    if request_id is None:
        return
    method = message.get("method")
    if method == "initialize":
        reply(
            request_id,
            {
                "protocolVersion": message.get("params", {}).get("protocolVersion", "2024-11-05"),
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "inventory-feedback-mcp", "version": "1.0.0"},
            },
        )
        return
    if method == "ping":
        reply(request_id, {})
        return
    if method == "tools/list":
        reply(request_id, {"tools": TOOLS})
        return
    if method == "tools/call":
        params = message.get("params", {})
        try:
            value = call_tool(params.get("name", ""), params.get("arguments"))
            reply(request_id, {"content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False)}]})
        except Exception as exc:
            reply(request_id, error={"code": -32602, "message": str(exc)})
        return
    reply(request_id, error={"code": -32601, "message": f"方法不存在: {method}"})


if __name__ == "__main__":
    for raw in sys.stdin:
        try:
            handle(json.loads(raw))
        except json.JSONDecodeError as exc:
            print(f"invalid JSON: {exc}", file=sys.stderr)
        except Exception as exc:
            print(f"server error: {exc}", file=sys.stderr)
