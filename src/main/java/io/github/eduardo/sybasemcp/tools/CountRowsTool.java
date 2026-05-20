package io.github.eduardo.sybasemcp.tools;

import io.github.eduardo.sybasemcp.*;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

public class CountRowsTool implements ITool {
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(CountRowsTool.class);

  public CountRowsTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_count_rows";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String schema = new JsonSchemaBuilder()
        .addString("schema", "Schema/owner of the table.")
        .addString("table", "Table name.")
        .addString("where", "Optional WHERE clause (without the WHERE keyword). Be careful with quoting.")
        .required("table")
        .build();
    mcp.tool(
        new McpSchema.Tool(
            prefix + "_count_rows",
            "Returns the total row count of a table, optionally filtered by a WHERE clause.",
            schema),
        this::run);
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String schema = ToolResponses.optString(args, "schema");
    String table = ToolResponses.optString(args, "table");
    String where = ToolResponses.optString(args, "where");
    if (table == null) return ToolResponses.error("Missing 'table' argument.");

    SchemaFilter filter = new SchemaFilter(config);
    if (schema != null) {
      try { filter.ensureAllowed(schema); }
      catch (SecurityException e) { return ToolResponses.error(e.getMessage()); }
    }

    String qualified;
    try {
      qualified = SqlIdentifiers.qualify(schema, table);
    } catch (IllegalArgumentException e) {
      return ToolResponses.error(e.getMessage());
    }
    StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(qualified);
    if (where != null) sql.append(" WHERE ").append(where);
    this.logger.info("CountRowsTool sql={}", sql);

    long t0 = System.currentTimeMillis();
    try (Connection cn = config.newConnection(false);
         Statement st = cn.createStatement();
         ResultSet rs = st.executeQuery(sql.toString())) {
      rs.next();
      long count = rs.getLong(1);
      long elapsed = System.currentTimeMillis() - t0;
      String body = String.format(
          "{\"schema\":%s,\"table\":\"%s\",\"where\":%s,\"count\":%d,\"elapsedMs\":%d}",
          schema == null ? "null" : "\"" + schema + "\"",
          table,
          where == null ? "null" : "\"" + where.replace("\"", "\\\"") + "\"",
          count, elapsed);
      return ToolResponses.text(body);
    } catch (Exception e) {
      this.logger.error("count_rows failed", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }
}
