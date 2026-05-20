package io.github.eduardo.sybasemcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a JDBC ResultSet to one of the supported output formats with rich
 * metadata (row count, truncation flag, column types and elapsed time).
 */
public final class ResultFormatter {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static class FormatResult {
    public final String body;
    public final int rowCount;
    public final boolean truncated;
    public final List<ColumnMeta> columns;

    FormatResult(String body, int rowCount, boolean truncated, List<ColumnMeta> columns) {
      this.body = body;
      this.rowCount = rowCount;
      this.truncated = truncated;
      this.columns = columns;
    }
  }

  public static class ColumnMeta {
    public final String name;
    public final String typeName;
    public final int jdbcType;
    public final boolean nullable;

    public ColumnMeta(String name, String typeName, int jdbcType, boolean nullable) {
      this.name = name;
      this.typeName = typeName;
      this.jdbcType = jdbcType;
      this.nullable = nullable;
    }
  }

  private ResultFormatter() {}

  public static FormatResult format(ResultSet rs, Format format, int maxRows) throws SQLException {
    ResultSetMetaData meta = rs.getMetaData();
    int colCount = meta.getColumnCount();
    List<ColumnMeta> columns = new ArrayList<>(colCount);
    for (int i = 1; i <= colCount; i++) {
      columns.add(new ColumnMeta(
          meta.getColumnLabel(i),
          meta.getColumnTypeName(i),
          meta.getColumnType(i),
          meta.isNullable(i) == ResultSetMetaData.columnNullable
      ));
    }

    List<List<Object>> rows = new ArrayList<>();
    boolean truncated = false;
    int rowCount = 0;
    while (rs.next()) {
      if (maxRows > 0 && rowCount >= maxRows) {
        truncated = true;
        break;
      }
      List<Object> row = new ArrayList<>(colCount);
      for (int i = 1; i <= colCount; i++) {
        row.add(extractTypedValue(rs, i, meta.getColumnType(i)));
      }
      rows.add(row);
      rowCount++;
    }
    try { rs.close(); } catch (SQLException ignored) {}

    String body;
    switch (format) {
      case JSON:
        body = renderJson(columns, rows);
        break;
      case MARKDOWN:
        body = renderMarkdown(columns, rows, truncated);
        break;
      case CSV:
      default:
        body = renderCsv(columns, rows);
        break;
    }
    return new FormatResult(body, rowCount, truncated, columns);
  }

  /** Wraps the formatted body with envelope metadata (row count, elapsed, etc). */
  public static String wrapResponse(FormatResult fr, Format format, long elapsedMs, String warning) {
    ObjectNode env = MAPPER.createObjectNode();
    env.put("format", format.getName());
    env.put("rowCount", fr.rowCount);
    env.put("truncated", fr.truncated);
    env.put("elapsedMs", elapsedMs);
    if (warning != null && !warning.isBlank()) {
      env.put("warning", warning);
    }
    ArrayNode cols = env.putArray("columns");
    for (ColumnMeta c : fr.columns) {
      ObjectNode co = cols.addObject();
      co.put("name", c.name);
      co.put("type", c.typeName);
      co.put("nullable", c.nullable);
    }

    StringBuilder out = new StringBuilder();
    try {
      out.append("// meta: ")
          .append(MAPPER.writeValueAsString(env))
          .append("\n");
    } catch (Exception e) {
      out.append("// meta: {\"rowCount\":").append(fr.rowCount).append("}\n");
    }
    out.append(fr.body);
    return out.toString();
  }

  private static Object extractTypedValue(ResultSet rs, int idx, int sqlType) throws SQLException {
    switch (sqlType) {
      case Types.DATE: {
        java.sql.Date v = rs.getDate(idx);
        if (rs.wasNull() || v == null) return null;
        return v.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
      }
      case Types.TIME:
      case Types.TIME_WITH_TIMEZONE: {
        java.sql.Time v = rs.getTime(idx);
        if (rs.wasNull() || v == null) return null;
        return v.toLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME);
      }
      case Types.TIMESTAMP:
      case Types.TIMESTAMP_WITH_TIMEZONE: {
        java.sql.Timestamp v = rs.getTimestamp(idx);
        if (rs.wasNull() || v == null) return null;
        return v.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
      }
      case Types.BIT:
      case Types.BOOLEAN: {
        boolean b = rs.getBoolean(idx);
        if (rs.wasNull()) return null;
        return b;
      }
      case Types.TINYINT:
      case Types.SMALLINT:
      case Types.INTEGER: {
        int n = rs.getInt(idx);
        if (rs.wasNull()) return null;
        return n;
      }
      case Types.BIGINT: {
        long n = rs.getLong(idx);
        if (rs.wasNull()) return null;
        return n;
      }
      case Types.REAL:
      case Types.FLOAT:
      case Types.DOUBLE: {
        double d = rs.getDouble(idx);
        if (rs.wasNull()) return null;
        return d;
      }
      case Types.NUMERIC:
      case Types.DECIMAL: {
        java.math.BigDecimal v = rs.getBigDecimal(idx);
        if (rs.wasNull() || v == null) return null;
        return v;
      }
      case Types.BINARY:
      case Types.VARBINARY:
      case Types.LONGVARBINARY:
      case Types.BLOB: {
        byte[] v = rs.getBytes(idx);
        if (rs.wasNull() || v == null) return null;
        return "0x" + bytesToHex(v, Math.min(v.length, 512))
            + (v.length > 512 ? "...(" + v.length + " bytes)" : "");
      }
      default: {
        String s = rs.getString(idx);
        if (rs.wasNull()) return null;
        return s;
      }
    }
  }

  private static String bytesToHex(byte[] bytes, int len) {
    StringBuilder sb = new StringBuilder(len * 2);
    for (int i = 0; i < len; i++) {
      sb.append(String.format("%02x", bytes[i]));
    }
    return sb.toString();
  }

  private static String renderCsv(List<ColumnMeta> columns, List<List<Object>> rows) {
    CsvWriter csv = new CsvWriter();
    CsvWriter.Row header = csv.row();
    for (ColumnMeta c : columns) {
      header.column(c.name);
    }
    header.end();
    for (List<Object> row : rows) {
      CsvWriter.Row r = csv.row();
      for (Object v : row) {
        r.column(v == null ? null : v.toString());
      }
      r.end();
    }
    return csv.end();
  }

  private static String renderJson(List<ColumnMeta> columns, List<List<Object>> rows) {
    ArrayNode arr = MAPPER.createArrayNode();
    for (List<Object> row : rows) {
      ObjectNode obj = arr.addObject();
      for (int i = 0; i < columns.size(); i++) {
        String name = columns.get(i).name;
        Object v = row.get(i);
        if (v == null) obj.putNull(name);
        else if (v instanceof Integer) obj.put(name, (Integer) v);
        else if (v instanceof Long) obj.put(name, (Long) v);
        else if (v instanceof Double) obj.put(name, (Double) v);
        else if (v instanceof Boolean) obj.put(name, (Boolean) v);
        else if (v instanceof java.math.BigDecimal) obj.put(name, (java.math.BigDecimal) v);
        else obj.put(name, v.toString());
      }
    }
    try {
      return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(arr);
    } catch (Exception e) {
      return arr.toString();
    }
  }

  private static String renderMarkdown(List<ColumnMeta> columns, List<List<Object>> rows, boolean truncated) {
    StringBuilder sb = new StringBuilder();
    sb.append('|');
    for (ColumnMeta c : columns) {
      sb.append(' ').append(escapeMd(c.name)).append(" |");
    }
    sb.append('\n').append('|');
    for (int i = 0; i < columns.size(); i++) {
      sb.append("---|");
    }
    sb.append('\n');
    for (List<Object> row : rows) {
      sb.append('|');
      for (Object v : row) {
        sb.append(' ').append(v == null ? "" : escapeMd(v.toString())).append(" |");
      }
      sb.append('\n');
    }
    if (truncated) {
      sb.append("\n_(truncated)_\n");
    }
    return sb.toString();
  }

  private static String escapeMd(String s) {
    if (s == null) return "";
    return s.replace("|", "\\|").replace("\n", " ");
  }

  /**
   * Convenience: format an arbitrary in-memory tabular structure (used by tools
   * that build their own rows rather than reading from a ResultSet).
   */
  public static String formatRows(List<String> headers, List<Map<String, Object>> rows, Format format) {
    List<ColumnMeta> cols = new ArrayList<>();
    for (String h : headers) {
      cols.add(new ColumnMeta(h, "STRING", Types.VARCHAR, true));
    }
    List<List<Object>> data = new ArrayList<>();
    for (Map<String, Object> m : rows) {
      List<Object> r = new ArrayList<>();
      for (String h : headers) {
        r.add(m.get(h));
      }
      data.add(r);
    }
    switch (format) {
      case JSON: return renderJson(cols, data);
      case MARKDOWN: return renderMarkdown(cols, data, false);
      case CSV:
      default: return renderCsv(cols, data);
    }
  }

  public static Map<String, Object> singletonRow(String k1, Object v1) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put(k1, v1);
    return m;
  }
}
