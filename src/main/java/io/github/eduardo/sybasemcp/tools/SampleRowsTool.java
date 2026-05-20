package io.github.eduardo.sybasemcp.tools;

import io.github.eduardo.sybasemcp.*;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

public class SampleRowsTool implements ITool {
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(SampleRowsTool.class);

  public SampleRowsTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_sample_rows";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String schema = new JsonSchemaBuilder()
        .addString("schema", "Schema/owner of the table.")
        .addString("table", "Table name.")
        .addInteger("rows", "Number of rows to sample. Default 20.", 1, null, 20)
        .addBoolean("random", "If true, sample rows randomly (ORDER BY RAND()). Default false.", false)
        .addStringEnum("format", "Output format. Default: " + config.getDefaultFormat().getName(),
            "csv", "json", "markdown")
        .required("table")
        .build();
    mcp.tool(
        new McpSchema.Tool(
            prefix + "_sample_rows",
            "Returns the first N rows of a table (optionally random). Safer shortcut than writing a SELECT *.",
            schema),
        this::run);
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String schema = ToolResponses.optString(args, "schema");
    String table = ToolResponses.optString(args, "table");
    int rows = ToolResponses.optIntegerOr(args, "rows", 20);
    Boolean rand = ToolResponses.optBoolean(args, "random");
    boolean random = rand != null && rand;
    Format fmt = Format.parse(ToolResponses.optString(args, "format"), config.getDefaultFormat());

    if (table == null) return ToolResponses.error("Missing 'table' argument.");
    SchemaFilter filter = new SchemaFilter(config);
    if (schema != null) {
      try { filter.ensureAllowed(schema); }
      catch (SecurityException e) { return ToolResponses.error(e.getMessage()); }
    }

    String qualified;
    try {
      qualified = SqlIdentifiers.qualify(schema, table);
    } catch (IllegalArgumentException e) {
      return ToolResponses.error(e.getMessage());
    }
    int safeRows = Math.min(rows, config.getMaxRows());
    String sql = "SELECT TOP " + safeRows + " * FROM " + qualified
        + (random ? " ORDER BY RAND()" : "");
    this.logger.info("SampleRowsTool sql={}", sql);

    long t0 = System.currentTimeMillis();
    try (Connection cn = config.newConnection(false);
         Statement st = cn.createStatement()) {
      try { st.setQueryTimeout(config.getQueryTimeoutSeconds()); } catch (SQLException ignored) {}
      try (ResultSet rs = st.executeQuery(sql)) {
        ResultFormatter.FormatResult fr = ResultFormatter.format(rs, fmt, safeRows);
        long elapsed = System.currentTimeMillis() - t0;
        return ToolResponses.text(ResultFormatter.wrapResponse(fr, fmt, elapsed, null));
      }
    } catch (Exception e) {
      this.logger.error("sample_rows failed", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }
}
