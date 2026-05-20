package io.github.eduardo.sybasemcp.tools;

import io.github.eduardo.sybasemcp.*;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

/**
 * Describes the result set of a SELECT statement WITHOUT executing it.
 * Uses SQL Anywhere's sa_describe_query system procedure.
 *
 * Useful for the agent to validate a query and discover the returned column
 * names, types and nullability before paying the cost of execution.
 */
public class DescribeQueryTool implements ITool {
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(DescribeQueryTool.class);

  public DescribeQueryTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_describe_query";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String desc = "Describes the result set of a SELECT statement without executing it "
        + "(via sa_describe_query). Returns column name, position, type, length, scale, "
        + "nullability, and - when resolvable - the base table and base column. "
        + "Use this to validate a query and discover its shape before calling run_query.";
    String schema = new JsonSchemaBuilder()
        .addString("sql", "SELECT statement to describe.")
        .addStringEnum("format", "Output format. Default: " + config.getDefaultFormat().getName(),
            "csv", "json", "markdown")
        .required("sql")
        .build();
    mcp.tool(new McpSchema.Tool(prefix + "_describe_query", desc, schema), this::run);
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String sql = ToolResponses.optString(args, "sql");
    Format fmt = Format.parse(ToolResponses.optString(args, "format"), config.getDefaultFormat());

    if (sql == null) return ToolResponses.error("Missing 'sql' argument.");
    if (!SqlGuard.isReadOnly(sql)) {
      return ToolResponses.error(
          "describe_query only accepts SELECT/WITH statements. Detected verb: '"
              + SqlGuard.firstVerb(sql) + "'.");
    }

    long t0 = System.currentTimeMillis();
    try (Connection cn = config.newConnection(false)) {
      try (PreparedStatement ps = cn.prepareStatement("SELECT * FROM sa_describe_query(?)")) {
        ps.setString(1, sql);
        ps.setQueryTimeout(config.getQueryTimeoutSeconds());
        try (ResultSet rs = ps.executeQuery()) {
          ResultFormatter.FormatResult fr =
              ResultFormatter.format(rs, fmt, config.getMaxRows());
          long elapsed = System.currentTimeMillis() - t0;
          return ToolResponses.text(ResultFormatter.wrapResponse(fr, fmt, elapsed, null));
        }
      }
    } catch (Exception e) {
      this.logger.error("describe_query failed", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }
}
