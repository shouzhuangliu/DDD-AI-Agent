import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import unittest


MODULE_PATH = Path(__file__).resolve().parents[1] / "inventory_feedback_mcp.py"


def load_module():
    spec = importlib.util.spec_from_file_location("inventory_feedback_mcp", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class InventoryFeedbackMcpTest(unittest.TestCase):

    def test_get_today_feedback_returns_feedback_list(self):
        module = load_module()

        result = module.call_tool("get_today_feedback", {})

        self.assertIsInstance(result, list)
        self.assertGreaterEqual(len(result), 3)
        self.assertTrue(all("feedbackId" in item for item in result))

    def test_search_feedback_by_keyword_filters_inventory_issue(self):
        module = load_module()

        result = module.call_tool("search_feedback_by_keyword", {"query": "缺货"})

        self.assertTrue(result)
        self.assertTrue(any("缺货" in item["summary"] or "缺货" in item["content"] for item in result))

    def test_get_feedback_detail_returns_single_feedback(self):
        module = load_module()
        feedback = module.call_tool("get_today_feedback", {})[0]

        detail = module.call_tool("get_feedback_detail", {"feedbackId": feedback["feedbackId"]})

        self.assertEqual(feedback["feedbackId"], detail["feedbackId"])
        self.assertIn("content", detail)

    def test_mark_feedback_triaged_persists_record(self):
        module = load_module()
        feedback = module.call_tool("get_today_feedback", {})[0]

        result = module.call_tool(
            "mark_feedback_triaged",
            {
                "feedbackId": feedback["feedbackId"],
                "decision": "PROMOTE_CANDIDATE",
                "operator": "inventory-agent",
                "note": "高频库存反馈，建议升级候选 Case",
            },
        )

        self.assertEqual(feedback["feedbackId"], result["feedbackId"])
        self.assertEqual("PROMOTE_CANDIDATE", result["decision"])
        self.assertEqual("inventory-agent", result["operator"])

    def test_stdio_protocol_uses_utf8_json(self):
        process = subprocess.Popen(
            [sys.executable, str(MODULE_PATH)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
        )
        try:
            process.stdin.write(json.dumps({
                "jsonrpc": "2.0",
                "id": 1,
                "method": "tools/list",
                "params": {},
            }, ensure_ascii=False) + "\n")
            process.stdin.flush()

            response = process.stdout.readline()
            parsed = json.loads(response)

            self.assertEqual(1, parsed["id"])
            self.assertIn("tools", parsed["result"])
        finally:
            process.kill()
            process.wait(timeout=5)
            if process.stdin:
                process.stdin.close()
            if process.stdout:
                process.stdout.close()
            if process.stderr:
                process.stderr.close()


if __name__ == "__main__":
    unittest.main()
