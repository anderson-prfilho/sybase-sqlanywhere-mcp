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

public class GetProceduresTool implements ITool {
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(GetProceduresTool.class);

  public GetProceduresTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_get_procedures";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String schema = new JsonSchemaBuilder()
        .addString("schema", "Filter by schema/owner.")
        .addString("pattern", "SQL LIKE on procedure name.")
        .addStringEnum("type", "Filter by routine type.", "PROCEDURE", "FUNCTION", "ALL")
        .addInteger("limit", "Max rows. Default 500.", 1, null, 500)
        .addInteger("offset", "Rows to skip.", 0, null, 0)
        .addStringEnum("format", "Output format. Default: " + config.getDefaultFormat().getName(),
            "csv", "json", "markdown")
        .build();
    mcp.tool(
        new McpSchema.Tool(
            prefix + "_get_procedures",
            "Lists stored procedures and functions in the database. Use `"
                + prefix + "_get_procedure_definition` to view the source code. "
                + "Routine type is inferred from SYS.SYSPROCPARM (parm_type=4 means RETURNS -> FUNCTION).",
            schema),
        this::run);
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String schema = ToolResponses.optString(args, "schema");
    String pattern = ToolResponses.optString(args, "pattern");
    String type = ToolResponses.optString(args, "type");
    int limit = ToolResponses.optIntegerOr(args, "limit", 500);
    int offset = ToolResponses.optIntegerOr(args, "offset", 0);
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
      sb.append("SELECT u.user_name AS schema_name, p.proc_name,")
          .append(" CASE WHEN EXISTS (")
          .append("   SELECT 1 FROM SYS.SYSPROCPARM pp")
          .append("   WHERE pp.proc_id = p.proc_id AND pp.parm_type = 4")
          .append(" ) THEN 'FUNCTION' ELSE 'PROCEDURE' END AS proc_kind,")
          .append(" CAST(COALESCE(r.remarks, '') AS VARCHAR(255)) AS remarks")
          .append(" FROM SYS.SYSPROCEDURE p")
          .append(" LEFT JOIN SYS.SYSUSER u ON u.user_id = p.creator")
          .append(" LEFT JOIN SYS.SYSREMARK r ON r.object_id = p.object_id")
          .append(" WHERE 1=1");
      if (schema != null) { sb.append(" AND u.user_name = ?"); params.add(schema); }
      if (pattern != null) { sb.append(" AND p.proc_name LIKE ?"); params.add(pattern); }
      sb.append(" ORDER BY u.user_name, p.proc_name");

      try (PreparedStatement ps = cn.prepareStatement(sb.toString())) {
        for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
        ps.setQueryTimeout(config.getQueryTimeoutSeconds());
        try (ResultSet rs = ps.executeQuery()) {
          List<String> headers = Arrays.asList("Schema", "Name", "Type", "Remarks");
          List<Map<String, Object>> all = new ArrayList<>();
          while (rs.next()) {
            String sc = rs.getString("schema_name");
            if (!filter.isAllowed(sc)) continue;
            String kind = rs.getString("proc_kind");
            if (type != null && !type.equalsIgnoreCase("ALL")
                && !type.equalsIgnoreCase(kind)) {
              continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("Schema", sc);
            m.put("Name", rs.getString("proc_name"));
            m.put("Type", kind);
            String rem = rs.getString("remarks");
            m.put("Remarks", (rem == null || rem.isEmpty()) ? null : rem);
            all.add(m);
          }
          int total = all.size();
          int from = Math.max(0, Math.min(offset, total));
          int to = Math.min(total, from + limit);
          List<Map<String, Object>> page = all.subList(from, to);

          String body = ResultFormatter.formatRows(headers, page, fmt);
          long elapsed = System.currentTimeMillis() - t0;
          String meta = String.format(
              "// meta: {\"rowCount\":%d,\"totalRows\":%d,\"offset\":%d,\"limit\":%d,\"truncated\":%b,\"elapsedMs\":%d,\"format\":\"%s\"}%n",
              page.size(), total, from, limit, to < total, elapsed, fmt.getName());
          return ToolResponses.text(meta + body);
        }
      }
    } catch (Exception e) {
      this.logger.error("get_procedures failed", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }
}
