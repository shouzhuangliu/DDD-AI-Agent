# Local Test MCP

This is a dependency-free STDIO MCP server used to verify the Java Agent flow.
It exposes `echo`, `add`, and `current_time`.

Use the following values when creating an MCP version in the web console:

- Transport: `stdio`
- Endpoint config:

```json
{
  "command": "python",
  "args": ["D:/javacode/ai-agent/ai-agent-station-study/mcp-test-server/test_mcp_server.py"]
}
```

Run the normal MCP lifecycle: connectivity, discovery, scan, sandbox, review,
then release. Discovery input can be:

```json
[
  {"name":"echo","description":"Return the supplied text.","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}},
  {"name":"add","description":"Add two numbers.","inputSchema":{"type":"object","properties":{"a":{"type":"number"},"b":{"type":"number"}},"required":["a","b"]}},
  {"name":"current_time","description":"Return the current UTC time.","inputSchema":{"type":"object","properties":{}}}
]
```

After release, open Agent edit. The MCP selector lists active released MCP
versions. Select the test MCP and save the Agent; release does not ask for an
Agent and does not start a client by itself.

The feedback-ops MCP and its dedicated Agent are defined in
`FEEDBACK_OPS_MCP.md` and migration `V20260729__seed_feedback_ops_agent.sql`.
