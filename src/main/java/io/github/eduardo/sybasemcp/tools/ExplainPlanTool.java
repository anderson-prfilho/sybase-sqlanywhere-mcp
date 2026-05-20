package io.github.eduardo.sybasemcp.tools;

import io.github.eduardo.sybasemcp.*;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

public class ExplainPlanTool implements ITool {
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(ExplainPlanTool.class);

  public ExplainPlanTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_explain_plan";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String schema = new JsonSchemaBuilder()
        .addString("sql", "SELECT statement whose plan you want to inspect.")
        .addBoolean("textual", "If true (default), return a short text plan; else return the XML plan.", true)
        .required("sql")
        .build();
    mcp.tool(
        new McpSchema.Tool(
            prefix + "_explain_plan",
            "Returns the SQL Anywhere execution plan for a SELECT statement, useful for query optimization. "
                + "Tries dbo.sa_get_plan(), then PLAN(), then EXPLANATION() until one succeeds.",
            schema),
        this::run);
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String sql = ToolResponses.optString(args, "sql");
    Boolean textual = ToolResponses.optBoolean(args, "textual");
    boolean shortText = textual == null || textual;
    if (sql == null) return ToolResponses.error("Missing 'sql' argument.");
    if (!SqlGuard.isReadOnly(sql)) {
      return ToolResponses.error("explain_plan only accepts SELECT/WITH statements.");
    }

    try (Connection cn = config.newConnection(false)) {
      // Try, in order:
      //   1) SELECT dbo.sa_get_plan(?, ?, ?, ?)  - documented entry point in SA 17
      //   2) SELECT EXPLANATION(?)               - older alias still present in some installs
      //   3) SELECT PLAN(?)                      - returns short plan text
      String[] candidates;
      if (shortText) {
        candidates = new String[] {
            "SELECT dbo.sa_get_plan(?, 'short_text', 1, 'detail')",
            "SELECT dbo.sa_get_plan(?)",
            "SELECT EXPLANATION(?)",
            "SELECT PLAN(?)"
        };
      } else {
        candidates = new String[] {
            "SELECT dbo.sa_get_plan(?, 'xml', 1, 'detail')",
            "SELECT dbo.sa_get_plan(?)",
            "SELECT EXPLANATION(?)",
            "SELECT PLAN(?)"
        };
      }

      SQLException lastErr = null;
      for (String wrapper : candidates) {
        try (PreparedStatement ps = cn.prepareStatement(wrapper)) {
          ps.setString(1, sql);
          ps.setQueryTimeout(config.getQueryTimeoutSeconds());
          try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
              String plan = rs.getString(1);
              if (plan != null) {
                return ToolResponses.text("-- via: " + wrapper + "\n" + plan);
              }
            }
          }
        } catch (SQLException ex) {
          lastErr = ex;
          this.logger.debug("explain_plan candidate failed ({}): {}", wrapper, ex.getMessage());
        }
      }
      String msg = lastErr == null ? "all plan engines returned empty"
          : PasswordMasker.maskMessage(lastErr.getMessage());
      return ToolResponses.error("Could not obtain execution plan: " + msg);
    } catch (Exception e) {
      this.logger.error("explain_plan failed", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }
}
