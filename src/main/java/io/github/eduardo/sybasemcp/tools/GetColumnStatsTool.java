package io.github.eduardo.sybasemcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.eduardo.sybasemcp.*;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

/**
 * Profiles a single column of a table: total rows, null count, distinct count
 * and (for comparable types) min/max values.
 */
public class GetColumnStatsTool implements ITool {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(GetColumnStatsTool.class);

  public GetColumnStatsTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_get_column_stats";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String desc = "Profiles a single column of a table: total rows, null count, distinct count, "
        + "and min/max values when the column type supports comparison. Returns JSON.";
    String schema = new JsonSchemaBuilder()
        .addString("schema", "Schema/owner of the table.")
        .addString("table", "Table name.")
        .addString("column", "Column name.")
        .addBoolean("includeMinMax", "Include MIN/MAX values (skip for BLOB/CLOB). Default true.", true)
        .required("table", "column")
        .build();
    mcp.tool(new McpSchema.Tool(prefix + "_get_column_stats", desc, schema), this::run);
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String schema = ToolResponses.optString(args, "schema");
    String table = ToolResponses.optString(args, "table");
    String column = ToolResponses.optString(args, "column");
    Boolean inclMinMax = ToolResponses.optBoolean(args, "includeMinMax");
    boolean includeMinMax = inclMinMax == null || inclMinMax;

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

    StringBuilder sb = new StringBuilder();
    sb.append("SELECT COUNT(*) AS total_rows,")
        .append(" SUM(CASE WHEN ").append(safeColumn).append(" IS NULL THEN 1 ELSE 0 END) AS null_count,")
        .append(" COUNT(DISTINCT ").append(safeColumn).append(") AS distinct_count");
    if (includeMinMax) {
      sb.append(", CAST(MIN(").append(safeColumn).append(") AS VARCHAR(255)) AS min_value")
          .append(", CAST(MAX(").append(safeColumn).append(") AS VARCHAR(255)) AS max_value");
    }
    sb.append(" FROM ").append(qualifiedTable);

    this.logger.info("GetColumnStatsTool sql={}", sb);
    long t0 = System.currentTimeMillis();
    try (Connection cn = config.newConnection(false);
         Statement st = cn.createStatement()) {
      try { st.setQueryTimeout(config.getQueryTimeoutSeconds()); } catch (Exception ignored) {}
      try (ResultSet rs = st.executeQuery(sb.toString())) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("schema", schema);
        out.put("table", table);
        out.put("column", column);
        if (rs.next()) {
          long total = rs.getLong("total_rows");
          long nulls = rs.getLong("null_count");
          long distinct = rs.getLong("distinct_count");
          out.put("totalRows", total);
          out.put("nullCount", nulls);
          out.put("nonNullCount", total - nulls);
          out.put("distinctCount", distinct);
          out.put("nullRatio", total == 0 ? 0.0 : (double) nulls / total);
          out.put("cardinalityRatio", total == 0 ? 0.0 : (double) distinct / Math.max(total - nulls, 1));
          if (includeMinMax) {
            String min = rs.getString("min_value");
            String max = rs.getString("max_value");
            if (min == null) out.putNull("minValue"); else out.put("minValue", min);
            if (max == null) out.putNull("maxValue"); else out.put("maxValue", max);
          }
        }
        out.put("elapsedMs", System.currentTimeMillis() - t0);
        return ToolResponses.text(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out));
      }
    } catch (Exception e) {
      this.logger.error("get_column_stats failed", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }
}
