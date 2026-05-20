package io.github.eduardo.sybasemcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * One-shot tool that returns everything an agent typically needs to know about
 * a table: identity, remarks, estimated row count, columns, primary key,
 * foreign keys (outgoing and incoming) and indexes.
 *
 * The output is a single JSON document so the agent can navigate without
 * additional round-trips.
 */
public class GetTableInfoTool implements ITool {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final Config config;
  private final Logger logger = LoggerFactory.getLogger(GetTableInfoTool.class);

  public GetTableInfoTool(Config config) {
    this.config = config;
  }

  @Override
  public String name() {
    return config.getPrefix() + "_get_table_info";
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String prefix = config.getPrefix();
    String desc = "Returns a complete description of a table or view in a single JSON document: "
        + "identity, comments, estimated row count, columns (with default/length/scale/nullable), "
        + "primary key columns, outgoing and incoming foreign keys, and indexes. "
        + "Prefer this over calling get_columns + get_primary_keys + get_foreign_keys + get_indexes separately.";
    String schema = new JsonSchemaBuilder()
        .addString("schema", "Schema/owner of the table.")
        .addString("table", "Table or view name.")
        .required("table")
        .build();
    mcp.tool(new McpSchema.Tool(prefix + "_get_table_info", desc, schema), this::run);
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String schema = ToolResponses.optString(args, "schema");
    String table = ToolResponses.optString(args, "table");
    if (table == null) return ToolResponses.error("Missing 'table' argument.");

    SchemaFilter filter = new SchemaFilter(config);
    if (schema != null) {
      try { filter.ensureAllowed(schema); }
      catch (SecurityException e) { return ToolResponses.error(e.getMessage()); }
    }

    long t0 = System.currentTimeMillis();
    try (Connection cn = config.newConnection(false)) {
      ObjectNode root = MAPPER.createObjectNode();
      ObjectNode ident = root.putObject("identity");
      ident.put("schema", schema);
      ident.put("table", table);

      populateIdentity(cn, schema, table, ident, filter);
      populateColumns(cn, schema, table, root, filter);
      populatePrimaryKey(cn, schema, table, root, filter);
      populateForeignKeys(cn, schema, table, root, filter);
      populateIndexes(cn, schema, table, root, filter);

      root.put("elapsedMs", System.currentTimeMillis() - t0);
      return ToolResponses.text(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    } catch (Exception e) {
      this.logger.error("get_table_info failed", e);
      return ToolResponses.error(PasswordMasker.maskMessage(e.getMessage()));
    }
  }

  private void populateIdentity(Connection cn, String schema, String table, ObjectNode ident, SchemaFilter filter)
      throws Exception {
    String sql = "SELECT u.user_name AS schema_name,"
        + " CASE t.table_type WHEN 1 THEN 'TABLE' WHEN 2 THEN 'VIEW' ELSE 'OTHER' END AS table_type,"
        + " t.count AS estimated_rows,"
        + " CAST(COALESCE(r.remarks, '') AS VARCHAR(255)) AS remarks"
        + " FROM SYS.SYSTAB t"
        + " LEFT JOIN SYS.SYSUSER u ON u.user_id = t.creator"
        + " LEFT JOIN SYS.SYSREMARK r ON r.object_id = t.object_id"
        + " WHERE t.table_name = ?"
        + (schema != null ? " AND u.user_name = ?" : "");
    try (PreparedStatement ps = cn.prepareStatement(sql)) {
      ps.setString(1, table);
      if (schema != null) ps.setString(2, schema);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          String sc = rs.getString("schema_name");
          if (!filter.isAllowed(sc)) {
            throw new SecurityException("Schema '" + sc + "' is blocked by server policy.");
          }
          if (ident.get("schema") == null || ident.get("schema").isNull()) ident.put("schema", sc);
          ident.put("type", rs.getString("table_type"));
          ident.put("estimatedRows", rs.getLong("estimated_rows"));
          String rem = rs.getString("remarks");
          if (rem != null && !rem.isEmpty()) ident.put("remarks", rem);
        }
      }
    }
  }

  private void populateColumns(Connection cn, String schema, String table, ObjectNode root, SchemaFilter filter)
      throws Exception {
    String sql = "SELECT c.column_name, c.column_id, d.domain_name AS data_type,"
        + " c.width, c.scale,"
        + " CASE c.nulls WHEN 'Y' THEN 1 ELSE 0 END AS nullable,"
        + " c.\"default\" AS column_default,"
        + " CAST(COALESCE(r.remarks, '') AS VARCHAR(255)) AS remarks"
        + " FROM SYS.SYSCOLUMN c"
        + " JOIN SYS.SYSTAB t ON t.table_id = c.table_id"
        + " LEFT JOIN SYS.SYSDOMAIN d ON d.domain_id = c.domain_id"
        + " LEFT JOIN SYS.SYSUSER u ON u.user_id = t.creator"
        + " LEFT JOIN SYS.SYSREMARK r ON r.object_id = c.object_id"
        + " WHERE t.table_name = ?"
        + (schema != null ? " AND u.user_name = ?" : "")
        + " ORDER BY c.column_id";
    ArrayNode columns = root.putArray("columns");
    try (PreparedStatement ps = cn.prepareStatement(sql)) {
      ps.setString(1, table);
      if (schema != null) ps.setString(2, schema);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          ObjectNode c = columns.addObject();
          c.put("position", rs.getInt("column_id"));
          c.put("name", rs.getString("column_name"));
          c.put("type", rs.getString("data_type"));
          c.put("length", rs.getInt("width"));
          c.put("scale", rs.getInt("scale"));
          c.put("nullable", rs.getInt("nullable") == 1);
          String def = rs.getString("column_default");
          if (def != null) c.put("default", def);
          String rem = rs.getString("remarks");
          if (rem != null && !rem.isEmpty()) c.put("remarks", rem);
        }
      }
    }
  }

  private void populatePrimaryKey(Connection cn, String schema, String table, ObjectNode root, SchemaFilter filter)
      throws Exception {
    String sql = "SELECT c.column_name, ic.sequence"
        + " FROM SYS.SYSTAB t"
        + " JOIN SYS.SYSUSER u ON u.user_id = t.creator"
        + " JOIN SYS.SYSIDX i ON i.table_id = t.table_id AND i.index_category = 1"
        + " JOIN SYS.SYSIDXCOL ic ON ic.table_id = i.table_id AND ic.index_id = i.index_id"
        + " JOIN SYS.SYSCOLUMN c ON c.table_id = ic.table_id AND c.column_id = ic.column_id"
        + " WHERE t.table_name = ?"
        + (schema != null ? " AND u.user_name = ?" : "")
        + " ORDER BY ic.sequence";
    ArrayNode pk = root.putArray("primaryKey");
    try (PreparedStatement ps = cn.prepareStatement(sql)) {
      ps.setString(1, table);
      if (schema != null) ps.setString(2, schema);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) pk.add(rs.getString("column_name"));
      }
    }
  }

  private void populateForeignKeys(Connection cn, String schema, String table, ObjectNode root, SchemaFilter filter)
      throws Exception {
    ObjectNode fks = root.putObject("foreignKeys");
    fks.putArray("outgoing");
    fks.putArray("incoming");
    collectFks(cn, schema, table, true, (ArrayNode) fks.get("outgoing"), filter);
    collectFks(cn, schema, table, false, (ArrayNode) fks.get("incoming"), filter);
  }

  private void collectFks(Connection cn, String schema, String table, boolean outgoing,
                          ArrayNode out, SchemaFilter filter) throws Exception {
    String tableSide = outgoing ? "ft" : "pt";
    String userSide = outgoing ? "fu" : "pu";
    String sql = "SELECT fi.index_name AS fk_name,"
        + " fu.user_name AS from_schema, ft.table_name AS from_table, fc.column_name AS from_column,"
        + " pu.user_name AS to_schema, pt.table_name AS to_table, pc.column_name AS to_column"
        + " FROM SYS.SYSFKEY fk"
        + " JOIN SYS.SYSIDX fi ON fi.table_id = fk.foreign_table_id AND fi.index_id = fk.foreign_index_id"
        + " JOIN SYS.SYSIDXCOL fic ON fic.table_id = fi.table_id AND fic.index_id = fi.index_id"
        + " JOIN SYS.SYSTAB ft ON ft.table_id = fk.foreign_table_id"
        + " JOIN SYS.SYSUSER fu ON fu.user_id = ft.creator"
        + " JOIN SYS.SYSCOLUMN fc ON fc.table_id = fic.table_id AND fc.column_id = fic.column_id"
        + " JOIN SYS.SYSTAB pt ON pt.table_id = fk.primary_table_id"
        + " JOIN SYS.SYSUSER pu ON pu.user_id = pt.creator"
        + " JOIN SYS.SYSIDX pi ON pi.table_id = fk.primary_table_id AND pi.index_id = fk.primary_index_id"
        + " JOIN SYS.SYSIDXCOL pic ON pic.table_id = pi.table_id AND pic.index_id = pi.index_id"
        + "                     AND pic.sequence = fic.sequence"
        + " JOIN SYS.SYSCOLUMN pc ON pc.table_id = pic.table_id AND pc.column_id = pic.column_id"
        + " WHERE " + tableSide + ".table_name = ?"
        + (schema != null ? " AND " + userSide + ".user_name = ?" : "")
        + " ORDER BY fi.index_name, fic.sequence";
    try (PreparedStatement ps = cn.prepareStatement(sql)) {
      ps.setString(1, table);
      if (schema != null) ps.setString(2, schema);
      try (ResultSet rs = ps.executeQuery()) {
        ObjectNode current = null;
        String currentName = null;
        while (rs.next()) {
          if (!filter.isAllowed(rs.getString("from_schema"))) continue;
          if (!filter.isAllowed(rs.getString("to_schema"))) continue;
          String name = rs.getString("fk_name");
          if (current == null || !name.equals(currentName)) {
            current = out.addObject();
            current.put("name", name);
            current.put("fromSchema", rs.getString("from_schema"));
            current.put("fromTable", rs.getString("from_table"));
            current.put("toSchema", rs.getString("to_schema"));
            current.put("toTable", rs.getString("to_table"));
            current.putArray("columns");
            currentName = name;
          }
          ObjectNode pair = ((ArrayNode) current.get("columns")).addObject();
          pair.put("from", rs.getString("from_column"));
          pair.put("to", rs.getString("to_column"));
        }
      }
    }
  }

  private void populateIndexes(Connection cn, String schema, String table, ObjectNode root, SchemaFilter filter)
      throws Exception {
    String sql = "SELECT i.index_name,"
        + " CASE i.index_category WHEN 1 THEN 'PRIMARY KEY'"
        + "                         WHEN 2 THEN 'FOREIGN KEY'"
        + "                         WHEN 3 THEN 'INDEX'"
        + "                         WHEN 4 THEN 'UNIQUE CONSTRAINT'"
        + "                         ELSE CAST(i.index_category AS VARCHAR(20)) END AS index_type,"
        + " i.\"unique\" AS is_unique,"
        + " c.column_name, ic.sequence"
        + " FROM SYS.SYSTAB t"
        + " JOIN SYS.SYSUSER u ON u.user_id = t.creator"
        + " JOIN SYS.SYSIDX i ON i.table_id = t.table_id"
        + " JOIN SYS.SYSIDXCOL ic ON ic.table_id = i.table_id AND ic.index_id = i.index_id"
        + " JOIN SYS.SYSCOLUMN c ON c.table_id = ic.table_id AND c.column_id = ic.column_id"
        + " WHERE t.table_name = ?"
        + (schema != null ? " AND u.user_name = ?" : "")
        + " ORDER BY i.index_name, ic.sequence";
    ArrayNode idx = root.putArray("indexes");
    try (PreparedStatement ps = cn.prepareStatement(sql)) {
      ps.setString(1, table);
      if (schema != null) ps.setString(2, schema);
      try (ResultSet rs = ps.executeQuery()) {
        ObjectNode current = null;
        String currentName = null;
        while (rs.next()) {
          String name = rs.getString("index_name");
          if (current == null || !name.equals(currentName)) {
            current = idx.addObject();
            current.put("name", name);
            current.put("type", rs.getString("index_type"));
            String uniq = rs.getString("is_unique");
            current.put("unique", uniq != null && (uniq.equalsIgnoreCase("U") || uniq.equalsIgnoreCase("Y")));
            current.putArray("columns");
            currentName = name;
          }
          ((ArrayNode) current.get("columns")).add(rs.getString("column_name"));
        }
      }
    }
  }
}
