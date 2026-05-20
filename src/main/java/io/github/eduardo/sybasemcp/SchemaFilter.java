package io.github.eduardo.sybasemcp;

import java.util.Set;

/**
 * Applies the AllowSchemas / BlockSchemas policy configured in the .prp file.
 * Comparisons are case-insensitive.
 */
public final class SchemaFilter {
  private final Set<String> allow;
  private final Set<String> block;

  public SchemaFilter(Config config) {
    this.allow = config.getAllowSchemas();
    this.block = config.getBlockSchemas();
  }

  public boolean isAllowed(String schema) {
    if (schema == null) return true;
    String s = schema.trim().toUpperCase();
    if (!allow.isEmpty() && !allow.contains(s)) {
      return false;
    }
    if (block.contains(s)) {
      return false;
    }
    return true;
  }

  public void ensureAllowed(String schema) {
    if (!isAllowed(schema)) {
      throw new SecurityException(
          "Access to schema '" + schema + "' is not allowed by server policy " +
              "(see AllowSchemas/BlockSchemas in the .prp file).");
    }
  }

  public Set<String> getAllow() {
    return allow;
  }

  public Set<String> getBlock() {
    return block;
  }
}
