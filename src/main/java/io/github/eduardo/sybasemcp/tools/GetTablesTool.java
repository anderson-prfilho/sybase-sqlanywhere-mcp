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
import java.util.List;
import java.util.Map;

public class GetTablesTool implements ITool {
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(GetTablesTool.class);

  public GetTablesTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_get_tables";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String description = "Retrieves a list of tables/views available in the database. "
        + "Use the `" + prefix + "_get_columns` tool to list the columns of a specific table. "
        + "IMPORTANT: Always qualify table names with their owner (Schema column) when querying, e.g. `SELECT * FROM owner.table_name`. "
        + "All filters are optional. Use `pattern` (SQL LIKE) to narrow the list, e.g. `pattern=fact_%`.";

    String schema = new JsonSchemaBuilder()
        .addString("schema", "Filter by exact schema/owner name (case-sensitive).")
        .addString("pattern", "SQL LIKE pattern on the table name. Use % as wildcard.")
        .addStringEnum("type", "Filter by object type.", "TABLE", "VIEW", "ALL")
        .addInteger("limit", "Max rows to return (default 500).", 1, null, 500)
        .addInteger("offset", "Rows to skip (for pagination).", 0, null, 0)
        .addStringEnum("format", "Output format. Default: " + config.getDefaultFormat().getName(),
            "csv", "json", "markdown")
        .build();

    mcp.tool(
        new McpSchema.Tool(
            prefix + "_get_tables",
            description,
            schema
        ),
        this::run
    );
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String schema = ToolResponses.optString(args, "schema");
    String pattern = ToolResponses.optString(args, "pattern");
    String type = ToolResponses.optString(args, "type");
    int limit = ToolResponses.optIntegerOr(args, "limit", 500);
    int offset = ToolResponses.optIntegerOr(args, "offset", 0);
    Format fmt = Format.parse(ToolResponses.optString(args, "format"), config.getDefaultFormat());

    this.logger.info("GetTablesTool(schema={}, pattern={}, type={}, limit={}, offset={})",
        schema, pattern, type, limit, offset);

    SchemaFilter filter = new SchemaFilter(config);
    if (schema != null) {
      try { filter.ensureAllowed(schema); }
      catch (SecurityException e) { return ToolResponses.error(e.getMessage()); }
    }

    long t0 = System.currentTimeMillis();
    try (Connection cn = config.newConnection(false)) {
      StringBuilder sb = new StringBuilder();
      List<Object> params = new ArrayList<>();
      sb.append("SELECT TABLE_SCHEM, TABLE_NAME, TABLE_TYPE, REMARKS FROM (")
          .append(" SELECT u.user_name AS TABLE_SCHEM, t.table_name AS TABLE_NAME,")
          .append(" CASE t.table_type WHEN 1 THEN 'TABLE' WHEN 2 THEN 'VIEW' WHEN 3 THEN 'MAT_VIEW'")
          .append(" WHEN 4 THEN 'PROCEDURE' WHEN 21 THEN 'TEMP' ELSE 'OTHER' END AS TABLE_TYPE,")
          .append(" CAST(COALESCE(r.remarks, '') AS VARCHAR(255)) AS REMARKS")
          .append(" FROM SYS.SYSTAB t")
          .append(" LEFT JOIN SYS.SYSUSER u ON t.creator = u.user_id")
          .append(" LEFT JOIN SYS.SYSREMARK r ON r.object_id = t.object_id");

      List<String> innerConds = new ArrayList<>();
      if ("VIEW".equalsIgnoreCase(type)) {
        innerConds.add("t.table_type = 2");
      } else if ("TABLE".equalsIgnoreCase(type)) {
        innerConds.add("t.table_type = 1");
      } else {
        innerConds.add("t.table_type IN (1, 2)");
      }
      if (!innerConds.isEmpty()) {
        sb.append(" WHERE ").append(String.join(" AND ", innerConds));
      }
      sb.append(") subq");

      List<String> outerConds = new ArrayList<>();
      if (schema != null) {
        outerConds.add("TABLE_SCHEM = ?");
        params.add(schema);
      }
      if (pattern != null) {
        outerConds.add("TABLE_NAME LIKE ?");
        params.add(pattern);
      }
      if (!outerConds.isEmpty()) {
        sb.append(" WHERE ").append(String.join(" AND ", outerConds));
      }
      sb.append(" ORDER BY TABLE_SCHEM, TABLE_NAME");

      try (PreparedStatement ps = cn.prepareStatement(sb.toString())) {
        for (int i = 0; i < params.size(); i++) {
          ps.setObject(i + 1, params.get(i));
        }
        ps.setQueryTimeout(config.getQueryTimeoutSeconds());
        try (ResultSet rs = ps.executeQuery()) {
          List<List<String>> all = new ArrayList<>();
          while (rs.next()) {
            String sc = rs.getString(1);
            if (!filter.isAllowed(sc)) continue;
            List<String> row = new ArrayList<>(4);
            row.add(nullable(rs.getString(1)));
            row.add(nullable(rs.getString(2)));
            row.add(nullable(rs.getString(3)));
            row.add(nullable(rs.getString(4)));
            all.add(row);
          }

          int total = all.size();
          int from = Math.max(0, Math.min(offset, total));
          int to = Math.min(total, from + limit);
          List<List<String>> page = all.subList(from, to);

          String body = renderTabular(page, fmt);
          long elapsed = System.currentTimeMillis() - t0;
          String meta = String.format(
              "// meta: {\"rowCount\":%d,\"totalRows\":%d,\"offset\":%d,\"limit\":%d,\"truncated\":%b,\"elapsedMs\":%d,\"format\":\"%s\"}%n",
              page.size(), total, from, limit, to < total, elapsed, fmt.getName());
          return ToolResponses.text(meta + body);
        }
      }
    } catch (Exception e) {
      this.logger.error("get_tables failed", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }

  private static String nullable(String s) {
    return (s == null || s.isEmpty()) ? null : s;
  }

  private String renderTabular(List<List<String>> rows, Format fmt) {
    List<String> headers = java.util.Arrays.asList("Schema", "Table", "Type", "Remarks");
    List<Map<String, Object>> data = new ArrayList<>(rows.size());
    for (List<String> r : rows) {
      Map<String, Object> m = new java.util.LinkedHashMap<>();
      m.put(headers.get(0), r.get(0));
      m.put(headers.get(1), r.get(1));
      m.put(headers.get(2), r.get(2));
      m.put(headers.get(3), r.get(3));
      data.add(m);
    }
    return ResultFormatter.formatRows(headers, data, fmt);
  }
}
