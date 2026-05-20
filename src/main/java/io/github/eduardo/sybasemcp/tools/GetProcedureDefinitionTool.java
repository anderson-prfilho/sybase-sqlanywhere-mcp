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

public class GetProcedureDefinitionTool implements ITool {
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(GetProcedureDefinitionTool.class);

  public GetProcedureDefinitionTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_get_procedure_definition";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String schema = new JsonSchemaBuilder()
        .addString("schema", "Schema/owner of the procedure.")
        .addString("name", "Procedure or function name.")
        .required("name")
        .build();
    mcp.tool(
        new McpSchema.Tool(
            prefix + "_get_procedure_definition",
            "Returns the SQL source code of a stored procedure or function (the CREATE PROCEDURE / CREATE FUNCTION body).",
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
      String sql = "SELECT u.user_name AS schema_name, p.proc_name,"
          + " CASE WHEN EXISTS ("
          + "   SELECT 1 FROM SYS.SYSPROCPARM pp"
          + "   WHERE pp.proc_id = p.proc_id AND pp.parm_type = 4"
          + " ) THEN 'FUNCTION' ELSE 'PROCEDURE' END AS proc_kind,"
          + " p.proc_defn AS body"
          + " FROM SYS.SYSPROCEDURE p"
          + " LEFT JOIN SYS.SYSUSER u ON u.user_id = p.creator"
          + " WHERE p.proc_name = ?"
          + (schema != null ? " AND u.user_name = ?" : "")
          + " ORDER BY u.user_name";
      try (PreparedStatement ps = cn.prepareStatement(sql)) {
        ps.setString(1, name);
        if (schema != null) ps.setString(2, schema);
        ps.setQueryTimeout(config.getQueryTimeoutSeconds());
        try (ResultSet rs = ps.executeQuery()) {
          StringBuilder out = new StringBuilder();
          int count = 0;
          while (rs.next()) {
            String sc = rs.getString("schema_name");
            if (!filter.isAllowed(sc)) continue;
            if (count > 0) out.append("\n\n");
            out.append("-- ").append(rs.getString("proc_kind")).append(" ")
                .append(sc).append('.').append(rs.getString("proc_name"))
                .append("\n");
            String body = rs.getString("body");
            out.append(body == null ? "(no body available)" : body);
            count++;
          }
          if (count == 0) {
            return ToolResponses.error("No procedure or function found with name '"
                + name + "'" + (schema != null ? " in schema '" + schema + "'" : "") + ".");
          }
          return ToolResponses.text(out.toString());
        }
      }
    } catch (Exception e) {
      this.logger.error("get_procedure_definition failed", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }
}
