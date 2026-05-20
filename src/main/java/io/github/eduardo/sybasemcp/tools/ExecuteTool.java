package io.github.eduardo.sybasemcp.tools;

import io.github.eduardo.sybasemcp.*;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * Tool for executing INSERT/UPDATE/DELETE/DDL statements. Only registered when
 * the configuration has AllowWrite=true. Even then, statements are still
 * executed only after explicit confirmation through the `confirm=true` argument.
 */
public class ExecuteTool implements ITool {
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(ExecuteTool.class);

  public ExecuteTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_execute";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String desc = "Executes a single non-SELECT statement (INSERT, UPDATE, DELETE, MERGE, CREATE, ALTER, DROP, CALL, TRUNCATE). "
        + "Bind values with `?` placeholders and pass them in `params`. "
        + "Returns the affected row count. Requires `confirm=true` to actually run; otherwise returns a dry-run preview. "
        + "Only enabled because the server was started with AllowWrite=true.";
    String schema = new JsonSchemaBuilder()
        .addString("sql", "The statement to execute. Only one statement at a time.")
        .add("params", "array",
            "Optional array of values to bind to ? placeholders in `sql`, in order.")
        .addBoolean("confirm", "Set to true to actually execute. Without it, the tool only validates the verb and returns a preview.", false)
        .addInteger("timeoutSeconds", "Statement timeout in seconds.", 1, null, config.getQueryTimeoutSeconds())
        .required("sql")
        .build();
    mcp.tool(
        new McpSchema.Tool(prefix + "_execute", desc, schema),
        this::run);
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String sql = ToolResponses.optString(args, "sql");
    Boolean confirm = ToolResponses.optBoolean(args, "confirm");
    boolean confirmed = confirm != null && confirm;
    int timeout = Math.min(
        ToolResponses.optIntegerOr(args, "timeoutSeconds", config.getQueryTimeoutSeconds()),
        config.getQueryTimeoutSeconds());
    Object paramsObj = args.get("params");

    if (sql == null) return ToolResponses.error("Missing 'sql' argument.");
    try {
      SqlGuard.ensureSingleStatement(sql);
    } catch (IllegalArgumentException e) {
      return ToolResponses.error(e.getMessage());
    }
    String verb = SqlGuard.firstVerb(sql);
    if (SqlGuard.isReadOnly(sql)) {
      return ToolResponses.error(
          "Use the '" + config.getPrefix() + "_run_query' tool for SELECT statements. Detected verb: " + verb);
    }
    if (!SqlGuard.isWrite(sql)) {
      return ToolResponses.error("Unrecognized or empty SQL verb: '" + verb + "'.");
    }
    if (!confirmed) {
      return ToolResponses.text(
          "DRY RUN. Statement looks valid (verb=" + verb + "). " +
              "Re-call this tool with `confirm: true` to actually execute it.");
    }

    List<Object> params;
    try {
      params = BindParams.parse(paramsObj);
    } catch (IllegalArgumentException e) {
      return ToolResponses.error(e.getMessage());
    }

    long t0 = System.currentTimeMillis();
    try (Connection cn = config.newConnection(true)) {
      int affected;
      if (params == null) {
        try (Statement st = cn.createStatement()) {
          try { st.setQueryTimeout(timeout); } catch (SQLException ignored) {}
          affected = st.executeUpdate(sql);
        }
      } else {
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
          BindParams.apply(ps, params);
          try { ps.setQueryTimeout(timeout); } catch (SQLException ignored) {}
          affected = ps.executeUpdate();
        }
      }
      long elapsed = System.currentTimeMillis() - t0;
      return ToolResponses.text(
          "{\"verb\":\"" + verb + "\",\"affectedRows\":" + affected + ",\"elapsedMs\":" + elapsed + "}");
    } catch (Exception e) {
      this.logger.error("execute failed", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }
}
