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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SearchObjectsTool implements ITool {
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(SearchObjectsTool.class);

  public SearchObjectsTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_search_objects";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String schema = new JsonSchemaBuilder()
        .addString("query", "Search term. SQL LIKE wildcards (%, _) are supported. If the term has no wildcard it's wrapped with %.")
        .addStringEnum("kind", "Restrict to a specific object kind.",
            "any", "table", "view", "procedure", "function", "column")
        .addInteger("limit", "Max rows. Default 200.", 1, null, 200)
        .addStringEnum("format", "Output format. Default: " + config.getDefaultFormat().getName(),
            "csv", "json", "markdown")
        .required("query")
        .build();
    mcp.tool(
        new McpSchema.Tool(
            prefix + "_search_objects",
            "Searches tables, views, procedures, functions and columns by name. Returns Schema, Name, Kind.",
            schema),
        this::run);
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String q = ToolResponses.optString(args, "query");
    String kind = ToolResponses.optString(args, "kind");
    if (kind == null) kind = "any";
    int limit = ToolResponses.optIntegerOr(args, "limit", 200);
    Format fmt = Format.parse(ToolResponses.optString(args, "format"), config.getDefaultFormat());
    if (q == null) return ToolResponses.error("Missing 'query' argument.");

    String like = (q.contains("%") || q.contains("_")) ? q : "%" + q + "%";
    SchemaFilter filter = new SchemaFilter(config);
    long t0 = System.currentTimeMillis();

    try (Connection cn = config.newConnection(false)) {
      List<Map<String, Object>> all = new ArrayList<>();

      if (kind.equals("any") || kind.equals("table") || kind.equals("view")) {
        String sqlT = "SELECT u.user_name AS schema_name, t.table_name AS object_name,"
            + " CASE t.table_type WHEN 1 THEN 'TABLE' WHEN 2 THEN 'VIEW' ELSE 'OTHER' END AS kind"
            + " FROM SYS.SYSTAB t"
            + " LEFT JOIN SYS.SYSUSER u ON u.user_id = t.creator"
            + " WHERE t.table_name LIKE ?"
            + " AND t.table_type IN ("
            + (kind.equals("table") ? "1" : kind.equals("view") ? "2" : "1, 2")
            + ")";
        runSearch(cn, sqlT, like, filter, all);
      }
      if (kind.equals("any") || kind.equals("procedure") || kind.equals("function")) {
        String sqlP = "SELECT u.user_name AS schema_name, p.proc_name AS object_name,"
            + " CASE WHEN EXISTS ("
            + "   SELECT 1 FROM SYS.SYSPROCPARM pp"
            + "   WHERE pp.proc_id = p.proc_id AND pp.parm_type = 4"
            + " ) THEN 'FUNCTION' ELSE 'PROCEDURE' END AS kind"
            + " FROM SYS.SYSPROCEDURE p"
            + " LEFT JOIN SYS.SYSUSER u ON u.user_id = p.creator"
            + " WHERE p.proc_name LIKE ?";
        if (kind.equals("procedure")) {
          sqlP += " AND NOT EXISTS (SELECT 1 FROM SYS.SYSPROCPARM pp WHERE pp.proc_id = p.proc_id AND pp.parm_type = 4)";
        } else if (kind.equals("function")) {
          sqlP += " AND EXISTS (SELECT 1 FROM SYS.SYSPROCPARM pp WHERE pp.proc_id = p.proc_id AND pp.parm_type = 4)";
        }
        runSearch(cn, sqlP, like, filter, all);
      }
      if (kind.equals("any") || kind.equals("column")) {
        String sqlC = "SELECT u.user_name AS schema_name,"
            + " (t.table_name || '.' || c.column_name) AS object_name,"
            + " 'COLUMN' AS kind"
            + " FROM SYS.SYSCOLUMN c"
            + " JOIN SYS.SYSTAB t ON t.table_id = c.table_id"
            + " LEFT JOIN SYS.SYSUSER u ON u.user_id = t.creator"
            + " WHERE c.column_name LIKE ?";
        runSearch(cn, sqlC, like, filter, all);
      }

      if (all.size() > limit) {
        all = all.subList(0, limit);
      }

      List<String> headers = Arrays.asList("Schema", "Name", "Kind");
      String body = ResultFormatter.formatRows(headers, all, fmt);
      long elapsed = System.currentTimeMillis() - t0;
      String meta = String.format(
          "// meta: {\"rowCount\":%d,\"limit\":%d,\"elapsedMs\":%d,\"format\":\"%s\"}%n",
          all.size(), limit, elapsed, fmt.getName());
      return ToolResponses.text(meta + body);
    } catch (Exception e) {
      this.logger.error("search_objects failed", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }

  private void runSearch(Connection cn, String sql, String like, SchemaFilter filter,
                         List<Map<String, Object>> out) throws Exception {
    try (PreparedStatement ps = cn.prepareStatement(sql)) {
      ps.setString(1, like);
      ps.setQueryTimeout(config.getQueryTimeoutSeconds());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String sc = rs.getString("schema_name");
          if (!filter.isAllowed(sc)) continue;
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("Schema", sc);
          m.put("Name", rs.getString("object_name"));
          m.put("Kind", rs.getString("kind"));
          out.add(m);
        }
      }
    }
  }
}
