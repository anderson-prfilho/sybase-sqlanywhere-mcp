package io.github.eduardo.sybasemcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.eduardo.sybasemcp.*;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

/**
 * Lightweight health check: opens a connection and runs SELECT 1.
 * Returns elapsed time and a flag.
 */
public class PingTool implements ITool {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(PingTool.class);

  public PingTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_ping";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String desc = "Lightweight health check. Opens a connection, runs SELECT 1, and reports "
        + "ok plus elapsed time.";
    String schema = new JsonSchemaBuilder().build();
    mcp.tool(new McpSchema.Tool(prefix + "_ping", desc, schema), this::run);
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    long t0 = System.currentTimeMillis();
    ObjectNode out = MAPPER.createObjectNode();
    try (Connection cn = config.newConnection(false);
         Statement st = cn.createStatement();
         ResultSet rs = st.executeQuery("SELECT 1")) {
      rs.next();
      int v = rs.getInt(1);
      out.put("ok", v == 1);
      out.put("elapsedMs", System.currentTimeMillis() - t0);
      return ToolResponses.text(MAPPER.writeValueAsString(out));
    } catch (Exception e) {
      this.logger.warn("ping failed: {}", PasswordMasker.maskMessage(e.getMessage()));
      out.put("ok", false);
      out.put("error", PasswordMasker.maskMessage(e.getMessage()));
      out.put("elapsedMs", System.currentTimeMillis() - t0);
      try {
        return ToolResponses.text(MAPPER.writeValueAsString(out));
      } catch (Exception ex) {
        return ToolResponses.error(ex.getMessage());
      }
    }
  }
}
