package io.github.eduardo.sybasemcp.tools;

import io.github.eduardo.sybasemcp.*;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RunQueryTool implements ITool {
  private Config config;
  private Logger logger = LoggerFactory.getLogger(RunQueryTool.class);

  public RunQueryTool(Config config) {
    this.config = config;
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = this.config.getPrefix();
    String description = "The SELECT statement to execute. "
        + "Use the `" + prefix + "_get_tables` tool to get a list of available tables (shows Schema/owner and Table name), "
        + "and the `" + prefix + "_get_columns` tool to list table columns. "
        + "CRITICAL: Always qualify table names with their owner/schema in the FROM clause, "
        + "e.g. `SELECT * FROM owner.tablename`. Get the owner from the `get_tables` result (the Schema column). "
        + "Do NOT use double-quoted identifiers — they are not supported by this driver. "
        + "The SQL dialect is SQL Anywhere TDS. "
        + "Valid clauses: FROM, INNER JOIN, LEFT JOIN, GROUP BY, ORDER BY, TOP (for LIMIT). "
        + Constants.FORMAT_DESC;

    String schema = new JsonSchemaBuilder()
        .addString("sql", description)
        .build();
    mcp.tool(
        new Tool(
            prefix + "_run_query",
            "Execute a SQL SELECT statement.",
            schema
        ),
        this::run
    );
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String sql = (String)args.get("sql");
    this.logger.info("RunQueryTool({})", sql);
    try {
      try (Connection cn = config.newConnection()) {
        List<McpSchema.Content> content = new ArrayList<>();
        String csv = queryToCsv(cn, sql);

        List<McpSchema.Role> roles = new ArrayList<>();
        roles.add(McpSchema.Role.USER);
        content.add(
            new McpSchema.TextContent(roles, 1.0, csv)
        );
        return new McpSchema.CallToolResult(content, false);
      }
    } catch ( Exception ex ) {
      throw new RuntimeException("ERROR: " + ex.getMessage());
    }
  }

  private String queryToCsv(Connection cn, String sql) throws SQLException {
    try (Statement st = cn.createStatement()) {
      return CsvUtils.resultSetToCsv(st.executeQuery(sql));
    } catch (SQLException e) {
      if (e.getMessage() != null && e.getMessage().contains("not found")) {
        throw new SQLException(
            e.getMessage() + " — Table names must be qualified with their owner. "
            + "Use the get_tables tool to find the correct owner (Schema column), "
            + "then use format: owner.table_name",
            e
        );
      }
      throw e;
    }
  }

}
