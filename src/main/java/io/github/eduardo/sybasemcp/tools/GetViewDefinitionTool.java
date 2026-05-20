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

public class GetViewDefinitionTool implements ITool {
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(GetViewDefinitionTool.class);

  public GetViewDefinitionTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_get_view_definition";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String schema = new JsonSchemaBuilder()
        .addString("schema", "Schema/owner of the view.")
        .addString("name", "View name.")
        .required("name")
        .build();
    mcp.tool(
        new McpSchema.Tool(
            prefix + "_get_view_definition",
            "Returns the SQL source (the SELECT body) of a view. "
                + "Tries SYS.SYSTAB.view_def first, then falls back to INFORMATION_SCHEMA.VIEWS.",
            schema),
        this::run);
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String schema = ToolResponses.optString(args, "schema");
    String name = ToolResponses.optString(args, "name");
    if (name == null) return ToolResponses.error("Missing 'name' argument.");

    SchemaFilter filter = new SchemaFilter(config);
    if (schema != null) {
      try { filter.ensureAllowed(schema); }
      catch (SecurityException e) { return ToolResponses.error(e.getMessage()); }
    }

    try (Connection cn = config.newConnection(false)) {
      String result = tryFromSystab(cn, schema, name, filter);
      if (result == null) {
        result = tryFromInformationSchema(cn, schema, name, filter);
      }
      if (result == null) {
        return ToolResponses.error("No view found with name '" + name + "'"
            + (schema != null ? " in schema '" + schema + "'" : "")
            + " in either SYS.SYSTAB.view_def or INFORMATION_SCHEMA.VIEWS.");
      }
      return ToolResponses.text(result);
    } catch (Exception e) {
      this.logger.error("get_view_definition failed", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }

  private String tryFromSystab(Connection cn, String schema, String name, SchemaFilter filter) {
    String sql = "SELECT u.user_name AS schema_name, t.table_name, t.view_def"
        + " FROM SYS.SYSTAB t"
        + " LEFT JOIN SYS.SYSUSER u ON u.user_id = t.creator"
        + " WHERE t.table_type = 2 AND t.table_name = ?"
        + (schema != null ? " AND u.user_name = ?" : "")
        + " ORDER BY u.user_name";
    try (PreparedStatement ps = cn.prepareStatement(sql)) {
      ps.setString(1, name);
      if (schema != null) ps.setString(2, schema);
      ps.setQueryTimeout(config.getQueryTimeoutSeconds());
      try (ResultSet rs = ps.executeQuery()) {
        return collect(rs, "schema_name", "table_name", "view_def", filter);
      }
    } catch (SQLException ex) {
      this.logger.debug("view_def via SYSTAB failed: {}", ex.getMessage());
      return null;
    }
  }

  private String tryFromInformationSchema(Connection cn, String schema, String name, SchemaFilter filter) {
    String sql = "SELECT TABLE_SCHEMA AS schema_name, TABLE_NAME AS table_name, VIEW_DEFINITION AS view_def"
        + " FROM INFORMATION_SCHEMA.VIEWS"
        + " WHERE TABLE_NAME = ?"
        + (schema != null ? " AND TABLE_SCHEMA = ?" : "")
        + " ORDER BY TABLE_SCHEMA";
    try (PreparedStatement ps = cn.prepareStatement(sql)) {
      ps.setString(1, name);
      if (schema != null) ps.setString(2, schema);
      ps.setQueryTimeout(config.getQueryTimeoutSeconds());
      try (ResultSet rs = ps.executeQuery()) {
        return collect(rs, "schema_name", "table_name", "view_def", filter);
      }
    } catch (SQLException ex) {
      this.logger.debug("view_def via INFORMATION_SCHEMA failed: {}", ex.getMessage());
      return null;
    }
  }

  private String collect(ResultSet rs, String schemaCol, String tableCol, String bodyCol, SchemaFilter filter)
      throws SQLException {
    StringBuilder out = new StringBuilder();
    int count = 0;
    while (rs.next()) {
      String sc = rs.getString(schemaCol);
      if (!filter.isAllowed(sc)) continue;
      if (count > 0) out.append("\n\n");
      out.append("-- VIEW ").append(sc).append('.').append(rs.getString(tableCol)).append("\n");
      String body = rs.getString(bodyCol);
      out.append(body == null ? "(no view definition available)" : body);
      count++;
    }
    return count == 0 ? null : out.toString();
  }
}
