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
 * Returns the most frequent values of a column ("top N value distribution") -
 * indispensable for the agent to grasp the cardinality and the actual content
 * of a column without scanning the whole table.
 */
public class GetValueDistributionTool implements ITool {
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(GetValueDistributionTool.class);

  public GetValueDistributionTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_get_value_distribution";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String desc = "Returns the top N most frequent values of a column, with their counts and "
        + "percentage of the total. Useful for the agent to understand cardinality and "
        + "discover enumerated values without scanning the whole table.";
    String schema = new JsonSchemaBuilder()
        .addString("schema", "Schema/owner of the table.")
        .addString("table", "Table name.")
        .addString("column", "Column name.")
        .addInteger("topN", "How many top values to return. Default 10.", 1, null, 10)
        .addBoolean("includeNulls", "Include NULL as a possible value. Default true.", true)
        .addStringEnum("format", "Output format. Default: " + config.getDefaultFormat().getName(),
            "csv", "json", "markdown")
        .required("table", "column")
        .build();
    mcp.tool(new McpSchema.Tool(prefix + "_get_value_distribution", desc, schema), this::run);
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String schema = ToolResponses.optString(args, "schema");
    String table = ToolResponses.optString(args, "table");
    String column = ToolResponses.optString(args, "column");
    int topN = ToolResponses.optIntegerOr(args, "topN", 10);
    Boolean inclNulls = ToolResponses.optBoolean(args, "includeNulls");
    boolean includeNulls = inclNulls == null || inclNulls;
    Format fmt = Format.parse(ToolResponses.optString(args, "format"), config.getDefaultFormat());

    if (table == null) return ToolResponses.error("Missing 'table' argument.");
    if (column == null) return ToolResponses.error("Missing 'column' argument.");

    SchemaFilter filter = new SchemaFilter(config);
    if (schema != null) {
      try { filter.ensureAllowed(schema); }
      catch (SecurityException e) { return ToolResponses.error(e.getMessage()); }
    }

    String qualifiedTable;
    String safeColumn;
    try {
      qualifiedTable = SqlIdentifiers.qualify(schema, table);
      safeColumn = SqlIdentifiers.requireSafe(column, "column name");
    } catch (IllegalArgumentException e) {
      return ToolResponses.error(e.getMessage());
    }

    int safeTopN = Math.max(1, Math.min(topN, config.getMaxRows()));

    String distinctSql = "SELECT TOP " + safeTopN + " " + safeColumn + " AS value,"
        + " COUNT(*) AS freq"
        + " FROM " + qualifiedTable
        + (includeNulls ? "" : " WHERE " + safeColumn + " IS NOT NULL")
        + " GROUP BY " + safeColumn
        + " ORDER BY freq DESC, " + safeColumn;
    String totalSql = "SELECT COUNT(*) FROM " + qualifiedTable
        + (includeNulls ? "" : " WHERE " + safeColumn + " IS NOT NULL");

    this.logger.info("GetValueDistributionTool dist={} total={}", distinctSql, totalSql);
    long t0 = System.currentTimeMillis();
    try (Connection cn = config.newConnection(false);
         Statement st = cn.createStatement()) {
      try { st.setQueryTimeout(config.getQueryTimeoutSeconds()); } catch (Exception ignored) {}

      long total;
      try (ResultSet rs = st.executeQuery(totalSql)) {
        rs.next();
        total = rs.getLong(1);
      }

      List<String> headers = List.of("Value", "Frequency", "Percent");
      List<Map<String, Object>> rows = new ArrayList<>();
      try (ResultSet rs = st.executeQuery(distinctSql)) {
        while (rs.next()) {
          Map<String, Object> m = new LinkedHashMap<>();
          String v = rs.getString("value");
          m.put("Value", rs.wasNull() ? null : v);
          long freq = rs.getLong("freq");
          m.put("Frequency", freq);
          double pct = total == 0 ? 0.0 : (double) freq * 100.0 / total;
          m.put("Percent", Math.round(pct * 100.0) / 100.0);
          rows.add(m);
        }
      }

      String body = ResultFormatter.formatRows(headers, rows, fmt);
      long elapsed = System.currentTimeMillis() - t0;
      String meta = String.format(
          "// meta: {\"rowCount\":%d,\"topN\":%d,\"totalRows\":%d,\"elapsedMs\":%d,\"format\":\"%s\"}%n",
          rows.size(), safeTopN, total, elapsed, fmt.getName());
      return ToolResponses.text(meta + body);
    } catch (Exception e) {
      this.logger.error("get_value_distribution failed", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }
}
