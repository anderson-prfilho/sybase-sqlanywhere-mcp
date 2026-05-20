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
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Map;

/**
 * Convenience tool that runs a SELECT and returns only the first column of the
 * first row as a single typed value. Cheap shortcut for things like
 * "SELECT COUNT(*) FROM ..." or "SELECT MAX(date) FROM ...".
 */
public class RunScalarTool implements ITool {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(RunScalarTool.class);

  public RunScalarTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_run_scalar";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String desc = "Executes a SELECT statement and returns only the first column of the first row "
        + "as a typed value. Use for aggregates (COUNT/MAX/MIN/SUM) or single-row lookups. "
        + "Returns JSON: {\"value\": ..., \"type\": \"<sql-type>\", \"elapsedMs\": N}.";
    String schema = new JsonSchemaBuilder()
        .addString("sql", "SELECT statement. Should return exactly one row and one column.")
        .required("sql")
        .build();
    mcp.tool(new McpSchema.Tool(prefix + "_run_scalar", desc, schema), this::run);
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String sql = ToolResponses.optString(args, "sql");
    if (sql == null) return ToolResponses.error("Missing 'sql' argument.");
    if (!SqlGuard.isReadOnly(sql)) {
      return ToolResponses.error("run_scalar only accepts SELECT/WITH statements.");
    }

    long t0 = System.currentTimeMillis();
    try (Connection cn = config.newConnection(false);
         Statement st = cn.createStatement()) {
      try { st.setQueryTimeout(config.getQueryTimeoutSeconds()); } catch (Exception ignored) {}
      try (ResultSet rs = st.executeQuery(sql)) {
        ObjectNode out = MAPPER.createObjectNode();
        if (!rs.next()) {
          out.putNull("value");
          out.put("type", "NULL");
          out.put("rowsReturned", 0);
        } else {
          ResultSetMetaData md = rs.getMetaData();
          out.put("column", md.getColumnLabel(1));
          out.put("type", md.getColumnTypeName(1));
          Object v = rs.getObject(1);
          if (v == null || rs.wasNull()) {
            out.putNull("value");
          } else if (v instanceof Number) {
            out.put("value", v.toString());
            if (v instanceof Integer) out.put("typedValue", (Integer) v);
            else if (v instanceof Long) out.put("typedValue", (Long) v);
            else if (v instanceof Double || v instanceof Float)
              out.put("typedValue", ((Number) v).doubleValue());
            else if (v instanceof java.math.BigDecimal)
              out.put("typedValue", (java.math.BigDecimal) v);
          } else if (v instanceof Boolean) {
            out.put("value", v.toString());
            out.put("typedValue", (Boolean) v);
          } else {
            out.put("value", v.toString());
          }
          out.put("rowsReturned", rs.next() ? 2 : 1);
        }
        out.put("elapsedMs", System.currentTimeMillis() - t0);
        return ToolResponses.text(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out));
      }
    } catch (Exception e) {
      this.logger.error("run_scalar failed", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }
}
