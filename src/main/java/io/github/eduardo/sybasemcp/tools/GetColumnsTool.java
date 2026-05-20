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

public class GetColumnsTool implements ITool {
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(GetColumnsTool.class);

  public GetColumnsTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_get_columns";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String description = "Retrieves the column definitions of a table or view (name, data type, nullability, default, length/precision/scale, position). "
        + "Use `" + prefix + "_get_tables` first to find the correct schema (owner) for the table.";

    String schema = new JsonSchemaBuilder()
        .addString("schema", "Schema/owner of the table.")
        .addString("table", "Table or view name.")
        .addStringEnum("format", "Output format. Default: " + config.getDefaultFormat().getName(),
            "csv", "json", "markdown")
        .required("table")
        .build();

    mcp.tool(
        new McpSchema.Tool(prefix + "_get_columns", description, schema),
        this::run
    );
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String schema = ToolResponses.optString(args, "schema");
    String table = ToolResponses.optString(args, "table");
    Format fmt = Format.parse(ToolResponses.optString(args, "format"), config.getDefaultFormat());

    this.logger.info("GetColumnsTool(schema={}, table={})", schema, table);

    if (table == null) return ToolResponses.error("Missing 'table' argument.");

    SchemaFilter filter = new SchemaFilter(config);
    if (schema != null) {
      try { filter.ensureAllowed(schema); }
      catch (SecurityException e) { return ToolResponses.error(e.getMessage()); }
    }

    long t0 = System.currentTimeMillis();
    try (Connection cn = config.newConnection(false)) {
      StringBuilder sb = new StringBuilder();
      List<Object> params = new ArrayList<>();
      sb.append("SELECT u.user_name AS TABLE_SCHEM,")
          .append(" t.table_name AS TABLE_NAME,")
          .append(" c.column_name AS COLUMN_NAME,")
          .append(" c.column_id AS POSITION,")
          .append(" d.domain_name AS DATA_TYPE,")
          .append(" c.width AS LENGTH,")
          .append(" c.scale AS SCALE,")
          .append(" CASE c.nulls WHEN 'Y' THEN 'YES' ELSE 'NO' END AS NULLABLE,")
          .append(" c.\"default\" AS COLUMN_DEFAULT,")
          .append(" CAST(COALESCE(r.remarks, '') AS VARCHAR(255)) AS REMARKS")
          .append(" FROM SYS.SYSCOLUMN c")
          .append(" JOIN SYS.SYSTAB t ON c.table_id = t.table_id")
          .append(" LEFT JOIN SYS.SYSDOMAIN d ON c.domain_id = d.domain_id")
          .append(" LEFT JOIN SYS.SYSUSER u ON t.creator = u.user_id")
          .append(" LEFT JOIN SYS.SYSREMARK r ON r.object_id = c.object_id")
          .append(" WHERE t.table_name = ?");
      params.add(table);
      if (schema != null) {
        sb.append(" AND u.user_name = ?");
        params.add(schema);
      }
      sb.append(" ORDER BY u.user_name, t.table_name, c.column_id");

      try (PreparedStatement ps = cn.prepareStatement(sb.toString())) {
        for (int i = 0; i < params.size(); i++) {
          ps.setObject(i + 1, params.get(i));
        }
        ps.setQueryTimeout(config.getQueryTimeoutSeconds());
        try (ResultSet rs = ps.executeQuery()) {
          List<String> headers = List.of(
              "Schema", "Table", "Column", "Position", "DataType",
              "Length", "Scale", "Nullable", "Default", "Remarks");
          List<Map<String, Object>> rows = new ArrayList<>();
          while (rs.next()) {
            String sc = rs.getString("TABLE_SCHEM");
            if (!filter.isAllowed(sc)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("Schema", sc);
            m.put("Table", rs.getString("TABLE_NAME"));
            m.put("Column", rs.getString("COLUMN_NAME"));
            m.put("Position", rs.getInt("POSITION"));
            m.put("DataType", rs.getString("DATA_TYPE"));
            m.put("Length", rs.getInt("LENGTH"));
            m.put("Scale", rs.getInt("SCALE"));
            m.put("Nullable", rs.getString("NULLABLE"));
            String def = rs.getString("COLUMN_DEFAULT");
            m.put("Default", def == null ? null : def);
            String rem = rs.getString("REMARKS");
            m.put("Remarks", (rem == null || rem.isEmpty()) ? null : rem);
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
      this.logger.error("get_columns failed", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }
}
