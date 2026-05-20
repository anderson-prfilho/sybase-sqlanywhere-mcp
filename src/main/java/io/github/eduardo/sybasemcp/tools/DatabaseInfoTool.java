package io.github.eduardo.sybasemcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.eduardo.sybasemcp.*;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

public class DatabaseInfoTool implements ITool {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(DatabaseInfoTool.class);

  public DatabaseInfoTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_database_info";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String schema = new JsonSchemaBuilder().build();
    mcp.tool(
        new McpSchema.Tool(
            prefix + "_database_info",
            "Returns server/database information: product, version, current user, default schema, charset, collation, and read-only flag.",
            schema),
        this::run);
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    try (Connection cn = config.newConnection(false)) {
      DatabaseMetaData md = cn.getMetaData();
      ObjectNode out = MAPPER.createObjectNode();
      out.put("product", md.getDatabaseProductName());
      out.put("productVersion", md.getDatabaseProductVersion());
      out.put("driverName", md.getDriverName());
      out.put("driverVersion", md.getDriverVersion());
      out.put("url", PasswordMasker.maskUrl(md.getURL()));
      out.put("user", md.getUserName());
      out.put("catalog", cn.getCatalog());
      try { out.put("schema", cn.getSchema()); } catch (Throwable t) {}
      out.put("readOnly", cn.isReadOnly());
      out.put("serverReadOnlyMode", config.isReadOnly());
      out.put("writeAllowed", config.isWriteAllowed());
      out.put("maxRows", config.getMaxRows());
      out.put("queryTimeoutSeconds", config.getQueryTimeoutSeconds());
      out.put("defaultFormat", config.getDefaultFormat().getName());

      try (Statement st = cn.createStatement();
           ResultSet rs = st.executeQuery(
               "SELECT db_property('CharSet') AS charset, db_property('Collation') AS collation,"
                   + " db_property('PageSize') AS page_size, db_property('FileSize') AS file_size_pages,"
                   + " current user AS current_user_name, current database AS current_database")) {
        if (rs.next()) {
          out.put("charset", rs.getString("charset"));
          out.put("collation", rs.getString("collation"));
          out.put("pageSizeBytes", rs.getInt("page_size"));
          out.put("fileSizePages", rs.getLong("file_size_pages"));
          out.put("currentUser", rs.getString("current_user_name"));
          out.put("currentDatabase", rs.getString("current_database"));
        }
      } catch (Exception ex) {
        // ignore - some properties may not be available
      }

      return ToolResponses.text(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out));
    } catch (Exception e) {
      this.logger.error("database_info failed", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }
}
