package io.github.eduardo.sybasemcp.tools;

import io.github.eduardo.sybasemcp.*;

import static io.github.eduardo.sybasemcp.StringUtil.*;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GetTablesTool implements ITool {
  private Config config;
  private Logger logger = LoggerFactory.getLogger(GetTablesTool.class);

  public GetTablesTool(Config config) {
    this.config = config;
  }

  public void register(McpServer.SyncSpec mcp) throws Exception {
    String schema = new JsonSchemaBuilder()
        .addString("catalog", "The catalog name")
        .addString("schema", "The schema name (table owner)")
        .build();
    mcp.tool(
        new McpSchema.Tool(
            config.getPrefix() + "_get_tables",
            "Retrieves a list of objects, entities, collections, etc. (as tables) available in the data source. Use the `" + config.getPrefix() + "_get_columns` tool to list available columns on a table. " +
                "Both `catalog` and `schema` are optional parameters. "
                + "IMPORTANT: When querying tables, always use the Schema (owner) column to qualify table names in run_query (e.g., `SELECT * FROM schema.table_name`). "
                + Constants.FORMAT_DESC,
            schema
        ),
        this::run
    );
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String catalog = (String)args.get("catalog");
    String schema = (String)args.get("schema");

    this.logger.info("GetTablesTool({}, {})", catalog, schema);
    try {
      try (Connection cn = config.newConnection()) {
        List<McpSchema.Content> content = new ArrayList<>();
        String csv = tablesToCsv(cn, catalog, schema);

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

  private String tablesToCsv(Connection cn, String catalog, String schema) throws SQLException {
    StringBuilder sql = new StringBuilder(
        "SELECT TABLE_SCHEM, TABLE_NAME, TABLE_TYPE, REMARKS FROM ("
        + " SELECT u.user_name AS TABLE_SCHEM, t.table_name AS TABLE_NAME, "
        + " CASE t.table_type WHEN 1 THEN 'TABLE' WHEN 2 THEN 'VIEW' WHEN 4 THEN 'PROCEDURE' ELSE 'OTHER' END AS TABLE_TYPE, "
        + " CAST('' AS VARCHAR(255)) AS REMARKS "
        + " FROM SYS.SYSTAB t"
        + " LEFT JOIN SYS.SYSUSER u ON t.creator = u.user_id"
        + " WHERE t.table_type IN (1, 2)"
        + ") subq");

    List<String> conditions = new ArrayList<>();
    if (!isNullOrEmpty(emptyNull(schema))) {
      conditions.add("TABLE_SCHEM = '" + schema.replace("'", "''") + "'");
    }
    if (!conditions.isEmpty()) {
      sql.append(" WHERE ").append(String.join(" AND ", conditions));
    }
    sql.append(" ORDER BY table_name");

    List<String[]> META_COLS = new ArrayList<>();
    META_COLS.add(new String[]{"TABLE_SCHEM", "Schema"});
    META_COLS.add(new String[]{"TABLE_NAME", "Table"});
    META_COLS.add(new String[]{"TABLE_TYPE", "Type"});
    META_COLS.add(new String[]{"REMARKS", "Remarks"});

    try (Statement st = cn.createStatement()) {
      try (ResultSet rs = st.executeQuery(sql.toString())) {
        return CsvUtils.resultSetToCsv(rs, META_COLS.toArray(new String[0][]));
      }
    }
  }
}
