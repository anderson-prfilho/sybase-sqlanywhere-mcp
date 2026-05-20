package io.github.eduardo.sybasemcp.tools;

import io.github.eduardo.sybasemcp.*;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists all schemas (= users that own at least one table or view) the agent is
 * allowed to see, honouring AllowSchemas / BlockSchemas.
 */
public class ListSchemasTool implements ITool {
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(ListSchemasTool.class);

  public ListSchemasTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_list_schemas";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String desc = "Lists distinct schemas/owners that contain tables or views. "
        + "Honours the AllowSchemas/BlockSchemas policy. "
        + "Returns Schema and ObjectCount.";
    String schema = new JsonSchemaBuilder()
        .addBoolean("includeEmpty", "Include schemas/users that don't own any table or view. Default false.", false)
        .addStringEnum("format", "Output format. Default: " + config.getDefaultFormat().getName(),
            "csv", "json", "markdown")
        .build();
    mcp.tool(new McpSchema.Tool(prefix + "_list_schemas", desc, schema), this::run);
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    Boolean inclEmpty = ToolResponses.optBoolean(args, "includeEmpty");
    boolean includeEmpty = inclEmpty != null && inclEmpty;
    Format fmt = Format.parse(ToolResponses.optString(args, "format"), config.getDefaultFormat());

    SchemaFilter filter = new SchemaFilter(config);
    long t0 = System.currentTimeMillis();

    String sql;
    if (includeEmpty) {
      sql = "SELECT u.user_name AS schema_name,"
          + " COUNT(t.table_id) AS object_count"
          + " FROM SYS.SYSUSER u"
          + " LEFT JOIN SYS.SYSTAB t ON t.creator = u.user_id AND t.table_type IN (1, 2)"
          + " GROUP BY u.user_name"
          + " ORDER BY u.user_name";
    } else {
      sql = "SELECT u.user_name AS schema_name,"
          + " COUNT(t.table_id) AS object_count"
          + " FROM SYS.SYSUSER u"
          + " JOIN SYS.SYSTAB t ON t.creator = u.user_id AND t.table_type IN (1, 2)"
          + " GROUP BY u.user_name"
          + " HAVING COUNT(t.table_id) > 0"
          + " ORDER BY u.user_name";
    }

    try (Connection cn = config.newConnection(false);
         Statement st = cn.createStatement();
         ResultSet rs = st.executeQuery(sql)) {
      List<String> headers = List.of("Schema", "ObjectCount");
      List<Map<String, Object>> rows = new ArrayList<>();
      while (rs.next()) {
        String sc = rs.getString("schema_name");
        if (!filter.isAllowed(sc)) continue;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Schema", sc);
        m.put("ObjectCount", rs.getInt("object_count"));
        rows.add(m);
      }
      String body = ResultFormatter.formatRows(headers, rows, fmt);
      long elapsed = System.currentTimeMillis() - t0;
      String meta = String.format(
          "// meta: {\"rowCount\":%d,\"elapsedMs\":%d,\"format\":\"%s\"}%n",
          rows.size(), elapsed, fmt.getName());
      return ToolResponses.text(meta + body);
    } catch (Exception e) {
      this.logger.error("list_schemas failed", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }
}
