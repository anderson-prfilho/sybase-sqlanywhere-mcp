package io.github.eduardo.sybasemcp;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Helpers for converting tool arguments into JDBC PreparedStatement bind
 * parameters. The expected input is a JSON array (deserialized as a List)
 * passed via the `params` tool argument.
 */
public final class BindParams {
  private BindParams() {}

  /**
   * Returns null when no `params` argument was provided (meaning the SQL is
   * a plain Statement). Returns a non-null list when `params` is an array
   * (even if empty).
   */
  @SuppressWarnings("unchecked")
  public static List<Object> parse(Object raw) {
    if (raw == null) return null;
    if (raw instanceof List<?>) {
      return new ArrayList<>((List<Object>) raw);
    }
    if (raw instanceof Object[]) {
      List<Object> out = new ArrayList<>();
      for (Object o : (Object[]) raw) out.add(o);
      return out;
    }
    if (raw instanceof Map<?, ?>) {
      throw new IllegalArgumentException(
          "`params` must be a JSON array (e.g. [\"foo\", 42]), not an object. "
              + "Named parameters are not supported by this server.");
    }
    throw new IllegalArgumentException(
        "`params` must be a JSON array of values. Got: " + raw.getClass().getSimpleName());
  }

  public static void apply(PreparedStatement ps, List<Object> params) throws SQLException {
    for (int i = 0; i < params.size(); i++) {
      bind(ps, i + 1, params.get(i));
    }
  }

  private static void bind(PreparedStatement ps, int idx, Object v) throws SQLException {
    if (v == null) {
      ps.setNull(idx, Types.NULL);
      return;
    }
    if (v instanceof Boolean) ps.setBoolean(idx, (Boolean) v);
    else if (v instanceof Integer) ps.setInt(idx, (Integer) v);
    else if (v instanceof Long) ps.setLong(idx, (Long) v);
    else if (v instanceof Short) ps.setShort(idx, (Short) v);
    else if (v instanceof Byte) ps.setByte(idx, (Byte) v);
    else if (v instanceof Float) ps.setFloat(idx, (Float) v);
    else if (v instanceof Double) ps.setDouble(idx, (Double) v);
    else if (v instanceof java.math.BigDecimal) ps.setBigDecimal(idx, (java.math.BigDecimal) v);
    else if (v instanceof java.math.BigInteger)
      ps.setBigDecimal(idx, new java.math.BigDecimal((java.math.BigInteger) v));
    else if (v instanceof byte[]) ps.setBytes(idx, (byte[]) v);
    else ps.setString(idx, v.toString());
  }
}
