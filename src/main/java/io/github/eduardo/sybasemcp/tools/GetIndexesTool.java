package io.github.eduardo.sybasemcp.tools;

import io.github.eduardo.sybasemcp.*;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GetIndexesTool implements ITool {
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(GetIndexesTool.class);

  public GetIndexesTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_get_indexes";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String schema = new JsonSchemaBuilder()
        .addString("schema", "Schema/owner of the table.")
        .addString("table", "Table name.")
        .addStringEnum("format", "Output format. Default: " + config.getDefaultFormat().getName(),
            "csv", "json", "markdown")
        .required("table")
        .build();
    mcp.tool(
        new McpSchema.Tool(
            prefix + "_get_indexes",
            "Lists indexes of a table, including unique flag and column composition.",
            schema),
        this::run);
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String schema = ToolResponses.optString(args, "schema");
    String table = ToolResponses.optString(args, "table");
    Format fmt = Format.parse(ToolResponses.optString(args, "format"), config.getDefaultFormat());
    if (table == null) return ToolResponses.error("Missing 'table' argument.");

    SchemaFilter filter = new SchemaFilter(config);
    if (schema != null) {
      try { filter.ensureAllowed(schema); }
      catch (SecurityException e) { return ToolResponses.error(e.getMessage()); }
    }

    long t0 = System.currentTimeMillis();
    try (Connection cn = config.newConnection(false)) {
      String sql = "SELECT u.user_name AS schema_name, t.table_name, i.index_name,"
          + " CASE i.index_category WHEN 1 THEN 'PRIMARY KEY'"
          + "                         WHEN 2 THEN 'FOREIGN KEY'"
          + "                         WHEN 3 THEN 'INDEX'"
          + "                         WHEN 4 THEN 'UNIQUE CONSTRAINT'"
          + "                         ELSE CAST(i.index_category AS VARCHAR(20)) END AS index_type,"
          + " i.\"unique\" AS is_unique,"
          + " c.column_name, ic.sequence AS ordinal"
          + " FROM SYS.SYSTAB t"
          + " JOIN SYS.SYSUSER u ON u.user_id = t.creator"
          + " JOIN SYS.SYSIDX i ON i.table_id = t.table_id"
          + " JOIN SYS.SYSIDXCOL ic ON ic.table_id = i.table_id AND ic.index_id = i.index_id"
          + " JOIN SYS.SYSCOLUMN c ON c.table_id = ic.table_id AND c.column_id = ic.column_id"
          + " WHERE t.table_name = ?"
          + (schema != null ? " AND u.user_name = ?" : "")
          + " ORDER BY i.index_name, ic.sequence";

      try (PreparedStatement ps = cn.prepareStatement(sql)) {
        ps.setString(1, table);
        if (schema != null) ps.setString(2, schema);
        ps.setQueryTimeout(config.getQueryTimeoutSeconds());
        try (ResultSet rs = ps.executeQuery()) {
          List<String> headers = List.of(
              "Schema", "Table", "IndexName", "Type", "Unique", "Column", "Ordinal");
          List<Map<String, Object>> rows = new ArrayList<>();
          while (rs.next()) {
            if (!filter.isAllowed(rs.getString("schema_name"))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("Schema", rs.getString("schema_name"));
            m.put("Table", rs.getString("table_name"));
            m.put("IndexName", rs.getString("index_name"));
            m.put("Type", rs.getString("index_type"));
            String uniq = rs.getString("is_unique");
            m.put("Unique", uniq != null && (uniq.equalsIgnoreCase("U") || uniq.equalsIgnoreCase("Y")));
            m.put("Column", rs.getString("column_name"));
            m.put("Ordinal", rs.getInt("ordinal"));
            rows.add(m);
          }
          String body = ResultFormatter.formatRows(headers, rows, fmt);
          long elapsed = System.currentTimeMillis() - t0;
          String meta = String.format(
              "// meta: {\"rowCount\":%d,\"elapsedMs\":%d,\"format\":\"%s\"}%n",
              rows.size(), elapsed, fmt.getName());
          return ToolResponses.text(meta + body);
        }
      }
    } catch (Exception e) {
      this.logger.error("get_indexes failed", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }
}
