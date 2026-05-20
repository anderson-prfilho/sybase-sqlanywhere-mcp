package io.github.eduardo.sybasemcp;

import java.util.regex.Pattern;

/**
 * Helpers for building safe qualified table names from user-supplied identifiers.
 */
public final class SqlIdentifiers {
  private static final Pattern SAFE_IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$#]*$");

  private SqlIdentifiers() {}

  public static boolean isSafe(String id) {
    return id != null && SAFE_IDENT.matcher(id).matches();
  }

  public static String requireSafe(String id, String label) {
    if (!isSafe(id)) {
      throw new IllegalArgumentException(
          "Invalid " + label + " '" + id + "'. Identifiers must match [A-Za-z_][A-Za-z0-9_$#]*");
    }
    return id;
  }

  public static String qualify(String schema, String name) {
    requireSafe(name, "table/object name");
    if (schema == null || schema.isBlank()) {
      return name;
    }
    requireSafe(schema, "schema");
    return schema + "." + name;
  }
}
