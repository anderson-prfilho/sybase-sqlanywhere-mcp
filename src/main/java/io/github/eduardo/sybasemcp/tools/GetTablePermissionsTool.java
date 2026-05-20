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

/**
 * Lists granted privileges on a table (or on every table when no table is
 * specified). Reads from SYS.SYSTABLEPERM.
 */
public class GetTablePermissionsTool implements ITool {
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(GetTablePermissionsTool.class);

  public GetTablePermissionsTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_get_table_permissions";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String desc = "Lists table-level privileges granted to users/groups. "
        + "Each privilege is shown as N (no), Y (yes) or G (with grant option). "
        + "When both 'schema' and 'table' are given, results are restricted to that table.";
    String schema = new JsonSchemaBuilder()
        .addString("schema", "Schema/owner of the table.")
        .addString("table", "Table name. If omitted, returns permissions for every table.")
        .addStringEnum("format", "Output format. Default: " + config.getDefaultFormat().getName(),
            "csv", "json", "markdown")
        .build();
    mcp.tool(new McpSchema.Tool(prefix + "_get_table_permissions", desc, schema), this::run);
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String schema = ToolResponses.optString(args, "schema");
    String table = ToolResponses.optString(args, "table");
    Format fmt = Format.parse(ToolResponses.optString(args, "format"), config.getDefaultFormat());

    SchemaFilter filter = new SchemaFilter(config);
    if (schema != null) {
      try { filter.ensureAllowed(schema); }
      catch (SecurityException e) { return ToolResponses.error(e.getMessage()); }
    }

    long t0 = System.currentTimeMillis();
    try (Connection cn = config.newConnection(false)) {
      StringBuilder sb = new StringBuilder();
      List<Object> params = new ArrayList<>();
      sb.append("SELECT owner.user_name AS schema_name,")
          .append(" t.table_name,")
          .append(" grantee.user_name AS grantee,")
          .append(" grantor.user_name AS grantor,")
          .append(" tp.selectauth, tp.insertauth, tp.deleteauth,")
          .append(" tp.updateauth, tp.referenceauth, tp.alterauth")
          .append(" FROM SYS.SYSTABLEPERM tp")
          .append(" JOIN SYS.SYSTAB t ON t.table_id = tp.stable_id")
          .append(" JOIN SYS.SYSUSER owner ON owner.user_id = t.creator")
          .append(" LEFT JOIN SYS.SYSUSER grantee ON grantee.user_id = tp.grantee")
          .append(" LEFT JOIN SYS.SYSUSER grantor ON grantor.user_id = tp.grantor")
          .append(" WHERE 1=1");
      if (table != null) { sb.append(" AND t.table_name = ?"); params.add(table); }
      if (schema != null) { sb.append(" AND owner.user_name = ?"); params.add(schema); }
      sb.append(" ORDER BY owner.user_name, t.table_name, grantee.user_name");

      try (PreparedStatement ps = cn.prepareStatement(sb.toString())) {
        for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
        ps.setQueryTimeout(config.getQueryTimeoutSeconds());
        try (ResultSet rs = ps.executeQuery()) {
          List<String> headers = List.of(
              "Schema", "Table", "Grantee", "Grantor",
              "Select", "Insert", "Delete", "Update", "References", "Alter");
          List<Map<String, Object>> rows = new ArrayList<>();
          while (rs.next()) {
            String sc = rs.getString("schema_name");
            if (!filter.isAllowed(sc)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("Schema", sc);
            m.put("Table", rs.getString("table_name"));
            m.put("Grantee", rs.getString("grantee"));
            m.put("Grantor", rs.getString("grantor"));
            m.put("Select", rs.getString("selectauth"));
            m.put("Insert", rs.getString("insertauth"));
            m.put("Delete", rs.getString("deleteauth"));
            m.put("Update", rs.getString("updateauth"));
            m.put("References", rs.getString("referenceauth"));
            m.put("Alter", rs.getString("alterauth"));
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
      this.logger.error("get_table_permissions failed", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }
}
