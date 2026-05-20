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
 * Lists foreign key relationships of a given table - both outgoing references
 * (this table referencing others) and incoming references (other tables that
 * reference this one).
 */
public class GetForeignKeysTool implements ITool {
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(GetForeignKeysTool.class);

  public GetForeignKeysTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_get_foreign_keys";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String schema = new JsonSchemaBuilder()
        .addString("schema", "Schema/owner of the table.")
        .addString("table", "Table name.")
        .addStringEnum("direction", "Which direction of FK to list. Default: all",
            "outgoing", "incoming", "all")
        .addStringEnum("format", "Output format. Default: " + config.getDefaultFormat().getName(),
            "csv", "json", "markdown")
        .required("table")
        .build();
    String desc = "Lists foreign key relationships for a table. "
        + "Returns both outgoing FKs (this table -> others) and incoming FKs (others -> this table). "
        + "Critical for the agent to understand JOIN paths in the schema.";
    mcp.tool(new McpSchema.Tool(prefix + "_get_foreign_keys", desc, schema), this::run);
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String schema = ToolResponses.optString(args, "schema");
    String table = ToolResponses.optString(args, "table");
    String direction = ToolResponses.optString(args, "direction");
    if (direction == null) direction = "all";
    Format fmt = Format.parse(ToolResponses.optString(args, "format"), config.getDefaultFormat());

    if (table == null) return ToolResponses.error("Missing 'table' argument.");
    SchemaFilter filter = new SchemaFilter(config);
    if (schema != null) {
      try { filter.ensureAllowed(schema); }
      catch (SecurityException e) { return ToolResponses.error(e.getMessage()); }
    }

    long t0 = System.currentTimeMillis();
    try (Connection cn = config.newConnection(false)) {
      List<Map<String, Object>> rows = new ArrayList<>();
      if ("outgoing".equalsIgnoreCase(direction) || "all".equalsIgnoreCase(direction)) {
        collectFks(cn, schema, table, "outgoing", rows, filter);
      }
      if ("incoming".equalsIgnoreCase(direction) || "all".equalsIgnoreCase(direction)) {
        collectFks(cn, schema, table, "incoming", rows, filter);
      }

      List<String> headers = List.of(
          "Direction", "FkName", "FromSchema", "FromTable", "FromColumn",
          "ToSchema", "ToTable", "ToColumn", "OnDelete", "OnUpdate");
      String body = ResultFormatter.formatRows(headers, rows, fmt);
      long elapsed = System.currentTimeMillis() - t0;
      String meta = String.format(
          "// meta: {\"rowCount\":%d,\"elapsedMs\":%d,\"format\":\"%s\"}%n",
          rows.size(), elapsed, fmt.getName());
      return ToolResponses.text(meta + body);
    } catch (Exception e) {
      this.logger.error("get_foreign_keys failed", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }

  private void collectFks(Connection cn, String schema, String table, String direction,
                          List<Map<String, Object>> rows, SchemaFilter filter) throws Exception {
    String sql;
    if ("outgoing".equals(direction)) {
      sql = "SELECT fi.index_name AS fk_name,"
          + " fu.user_name AS from_schema, ft.table_name AS from_table, fc.column_name AS from_column,"
          + " pu.user_name AS to_schema, pt.table_name AS to_table, pc.column_name AS to_column"
          + " FROM SYS.SYSFKEY fk"
          + " JOIN SYS.SYSIDX fi ON fi.table_id = fk.foreign_table_id AND fi.index_id = fk.foreign_index_id"
          + " JOIN SYS.SYSIDXCOL fic ON fic.table_id = fi.table_id AND fic.index_id = fi.index_id"
          + " JOIN SYS.SYSTAB ft ON ft.table_id = fk.foreign_table_id"
          + " JOIN SYS.SYSUSER fu ON fu.user_id = ft.creator"
          + " JOIN SYS.SYSCOLUMN fc ON fc.table_id = fic.table_id AND fc.column_id = fic.column_id"
          + " JOIN SYS.SYSTAB pt ON pt.table_id = fk.primary_table_id"
          + " JOIN SYS.SYSUSER pu ON pu.user_id = pt.creator"
          + " JOIN SYS.SYSIDX pi ON pi.table_id = fk.primary_table_id AND pi.index_id = fk.primary_index_id"
          + " JOIN SYS.SYSIDXCOL pic ON pic.table_id = pi.table_id AND pic.index_id = pi.index_id"
          + "                     AND pic.sequence = fic.sequence"
          + " JOIN SYS.SYSCOLUMN pc ON pc.table_id = pic.table_id AND pc.column_id = pic.column_id"
          + " WHERE ft.table_name = ?"
          + (schema != null ? " AND fu.user_name = ?" : "")
          + " ORDER BY fi.index_name, fic.sequence";
    } else {
      sql = "SELECT fi.index_name AS fk_name,"
          + " fu.user_name AS from_schema, ft.table_name AS from_table, fc.column_name AS from_column,"
          + " pu.user_name AS to_schema, pt.table_name AS to_table, pc.column_name AS to_column"
          + " FROM SYS.SYSFKEY fk"
          + " JOIN SYS.SYSIDX fi ON fi.table_id = fk.foreign_table_id AND fi.index_id = fk.foreign_index_id"
          + " JOIN SYS.SYSIDXCOL fic ON fic.table_id = fi.table_id AND fic.index_id = fi.index_id"
          + " JOIN SYS.SYSTAB ft ON ft.table_id = fk.foreign_table_id"
          + " JOIN SYS.SYSUSER fu ON fu.user_id = ft.creator"
          + " JOIN SYS.SYSCOLUMN fc ON fc.table_id = fic.table_id AND fc.column_id = fic.column_id"
          + " JOIN SYS.SYSTAB pt ON pt.table_id = fk.primary_table_id"
          + " JOIN SYS.SYSUSER pu ON pu.user_id = pt.creator"
          + " JOIN SYS.SYSIDX pi ON pi.table_id = fk.primary_table_id AND pi.index_id = fk.primary_index_id"
          + " JOIN SYS.SYSIDXCOL pic ON pic.table_id = pi.table_id AND pic.index_id = pi.index_id"
          + "                     AND pic.sequence = fic.sequence"
          + " JOIN SYS.SYSCOLUMN pc ON pc.table_id = pic.table_id AND pc.column_id = pic.column_id"
          + " WHERE pt.table_name = ?"
          + (schema != null ? " AND pu.user_name = ?" : "")
          + " ORDER BY fi.index_name, fic.sequence";
    }
    try (PreparedStatement ps = cn.prepareStatement(sql)) {
      ps.setString(1, table);
      if (schema != null) ps.setString(2, schema);
      ps.setQueryTimeout(config.getQueryTimeoutSeconds());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          if (!filter.isAllowed(rs.getString("from_schema"))) continue;
          if (!filter.isAllowed(rs.getString("to_schema"))) continue;
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("Direction", direction);
          m.put("FkName", rs.getString("fk_name"));
          m.put("FromSchema", rs.getString("from_schema"));
          m.put("FromTable", rs.getString("from_table"));
          m.put("FromColumn", rs.getString("from_column"));
          m.put("ToSchema", rs.getString("to_schema"));
          m.put("ToTable", rs.getString("to_table"));
          m.put("ToColumn", rs.getString("to_column"));
          m.put("OnDelete", null);
          m.put("OnUpdate", null);
          rows.add(m);
        }
      }
    }
  }
}
