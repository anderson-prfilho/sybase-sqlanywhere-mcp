package io.github.eduardo.sybasemcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;

public interface ITool {
  /**
   * The fully-qualified tool name as registered with MCP, e.g. "sybase_get_tables".
   * Must equal the name passed to {@code mcp.tool(new McpSchema.Tool(NAME, ...))}.
   */
  String name();

  void register(McpServer.SyncSpec mcp) throws Exception;

  McpSchema.CallToolResult run(Map<String, Object> args);
}
