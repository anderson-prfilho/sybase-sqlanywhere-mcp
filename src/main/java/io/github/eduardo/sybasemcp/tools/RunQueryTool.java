package io.github.eduardo.sybasemcp.tools;

import io.github.eduardo.sybasemcp.*;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

public class RunQueryTool implements ITool {
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(RunQueryTool.class);

  public RunQueryTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_run_query";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = this.config.getPrefix();
    String description = "Execute a SQL SELECT (or WITH/EXPLAIN) statement against the database. "
        + "Use `" + prefix + "_get_tables` to list tables, `" + prefix + "_get_columns` to inspect columns, "
        + "and `" + prefix + "_get_foreign_keys` to understand relationships before joining. "
        + "ALWAYS qualify table names with their owner/schema (e.g. `SELECT * FROM owner.tablename`). "
        + "Do NOT use double-quoted identifiers. SQL dialect is SAP SQL Anywhere. "
        + "Use TOP N for LIMIT. "
        + "Bind parameters with `?` placeholders and pass values in `params` to avoid SQL injection. "
        + "The server enforces a row limit (default " + config.getMaxRows() + ") and a query timeout (default "
        + config.getQueryTimeoutSeconds() + "s). When the server is in read-only mode, only SELECT/WITH/EXPLAIN are accepted.";

    String schema = new JsonSchemaBuilder()
        .addString("sql", description)
        .add("params", "array",
            "Optional array of values to bind to ? placeholders in `sql`, in order. "
                + "Strongly preferred over interpolating literals into the SQL string.")
        .addStringEnum("format", "Output format. Default: " + config.getDefaultFormat().getName(),
            "csv", "json", "markdown")
        .addInteger("maxRows", "Maximum rows to return. Capped by server MaxRows (" + config.getMaxRows() + ").",
            1, null, null)
        .addInteger("timeoutSeconds", "Query timeout in seconds. Capped by server QueryTimeoutSeconds ("
            + config.getQueryTimeoutSeconds() + ").", 1, null, null)
        .required("sql")
        .build();

    mcp.tool(
        new Tool(
            prefix + "_run_query",
            "Execute a SQL SELECT statement (optionally parameterized) and return rows with metadata.",
            schema
        ),
        this::run
    );
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String sql = ToolResponses.optString(args, "sql");
    Format fmt = Format.parse(ToolResponses.optString(args, "format"), config.getDefaultFormat());
    int maxRows = capMaxRows(ToolResponses.optInteger(args, "maxRows"));
    int timeout = capTimeout(ToolResponses.optInteger(args, "timeoutSeconds"));
    Object paramsObj = args.get("params");

    this.logger.info("RunQueryTool(format={}, maxRows={}, timeout={}s, params={}) sql={}",
        fmt, maxRows, timeout, paramsObj != null, sql);

    if (sql == null) {
      return ToolResponses.error("Missing 'sql' argument.");
    }
    try {
      SqlGuard.ensureSingleStatement(sql);
    } catch (IllegalArgumentException e) {
      return ToolResponses.error(e.getMessage());
    }
    if (config.isReadOnly() && !SqlGuard.isReadOnly(sql)) {
      return ToolResponses.error(
          "Server is in read-only mode. Only SELECT / WITH / EXPLAIN / DESCRIBE are allowed. " +
              "Detected verb: '" + SqlGuard.firstVerb(sql) + "'. " +
              "To run write statements, use the '" + config.getPrefix() + "_execute' tool " +
              "(requires AllowWrite=true in the .prp file).");
    }

    List<Object> params;
    try {
      params = BindParams.parse(paramsObj);
    } catch (IllegalArgumentException e) {
      return ToolResponses.error(e.getMessage());
    }

    long t0 = System.currentTimeMillis();
    try (Connection cn = config.newConnection(false)) {
      if (params == null) {
        try (Statement st = cn.createStatement()) {
          tune(st, timeout, maxRows);
          try (ResultSet rs = st.executeQuery(sql)) {
            return formatResponse(rs, fmt, maxRows, t0);
          }
        }
      } else {
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
          BindParams.apply(ps, params);
          tune(ps, timeout, maxRows);
          try (ResultSet rs = ps.executeQuery()) {
            return formatResponse(rs, fmt, maxRows, t0);
          }
        }
      }
    } catch (SQLException e) {
      String msg = PasswordMasker.maskMessage(e.getMessage());
      if (msg != null && msg.contains("not found")) {
        msg += " - Table names must be qualified with their owner. " +
            "Use the get_tables tool to find the correct owner (Schema column), " +
            "then use format: owner.table_name";
      }
      this.logger.warn("Query failed: {}", msg);
      return ToolResponses.error("SQL error: " + msg);
    } catch (Exception e) {
      this.logger.error("Unexpected error running query", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }

  private McpSchema.CallToolResult formatResponse(ResultSet rs, Format fmt, int maxRows, long t0) throws SQLException {
    ResultFormatter.FormatResult fr = ResultFormatter.format(rs, fmt, maxRows);
    long elapsed = System.currentTimeMillis() - t0;
    String warning = fr.truncated
        ? "Result truncated to " + maxRows + " rows. Add a TOP clause or increase maxRows to see more."
        : null;
    return ToolResponses.text(ResultFormatter.wrapResponse(fr, fmt, elapsed, warning));
  }

  private void tune(Statement st, int timeout, int maxRows) {
    try { st.setQueryTimeout(timeout); } catch (SQLException ignored) {}
    try { st.setFetchSize(Math.min(maxRows, 1000)); } catch (SQLException ignored) {}
  }

  private int capMaxRows(Integer requested) {
    int server = config.getMaxRows();
    if (requested == null || requested <= 0) return server;
    return Math.min(requested, server);
  }

  private int capTimeout(Integer requested) {
    int server = config.getQueryTimeoutSeconds();
    if (requested == null || requested <= 0) return server;
    return Math.min(requested, server);
  }
}
