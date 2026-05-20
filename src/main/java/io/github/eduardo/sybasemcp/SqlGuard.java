package io.github.eduardo.sybasemcp;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lightweight SQL guard. Not a full parser - intended to prevent the common
 * cases of an agent accidentally running DML/DDL when the server is in
 * read-only mode.
 */
public final class SqlGuard {
  private static final Pattern STRIP_COMMENTS_BLOCK = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
  private static final Pattern STRIP_COMMENTS_LINE = Pattern.compile("--[^\\n]*");

  /** Verbs that only read data. */
  private static final Set<String> READ_VERBS = Set.of(
      "SELECT", "WITH", "EXPLAIN", "DESCRIBE", "DESC", "SHOW", "VALUES", "TABLE"
  );

  /** Verbs that mutate or change schema. */
  private static final Set<String> WRITE_VERBS = Set.of(
      "INSERT", "UPDATE", "DELETE", "MERGE", "TRUNCATE", "DROP", "CREATE",
      "ALTER", "GRANT", "REVOKE", "COMMENT", "RENAME", "CALL", "EXEC", "EXECUTE",
      "BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT", "LOCK", "SET", "USE", "LOAD",
      "UNLOAD", "BACKUP", "RESTORE"
  );

  private SqlGuard() {}

  /**
   * Returns the first significant SQL verb in uppercase, or empty string when the
   * statement is empty/comments only.
   */
  public static String firstVerb(String sql) {
    if (sql == null) return "";
    String s = STRIP_COMMENTS_BLOCK.matcher(sql).replaceAll(" ");
    s = STRIP_COMMENTS_LINE.matcher(s).replaceAll(" ").trim();
    if (s.isEmpty()) return "";

    int i = 0;
    while (i < s.length() && Character.isLetter(s.charAt(i))) {
      i++;
    }
    if (i == 0) return "";
    return s.substring(0, i).toUpperCase();
  }

  public static boolean isReadOnly(String sql) {
    String verb = firstVerb(sql);
    return READ_VERBS.contains(verb);
  }

  public static boolean isWrite(String sql) {
    String verb = firstVerb(sql);
    return WRITE_VERBS.contains(verb);
  }

  /**
   * Checks that the SQL contains a single statement (no stacked queries through
   * a stray ;). Trailing semicolons are tolerated.
   */
  public static void ensureSingleStatement(String sql) {
    if (sql == null) return;
    String s = STRIP_COMMENTS_BLOCK.matcher(sql).replaceAll(" ");
    s = STRIP_COMMENTS_LINE.matcher(s).replaceAll(" ");
    s = stripStringLiterals(s);

    int semicolonCount = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == ';') {
        boolean isTrailing = true;
        for (int j = i + 1; j < s.length(); j++) {
          if (!Character.isWhitespace(s.charAt(j))) {
            isTrailing = false;
            break;
          }
        }
        if (!isTrailing) {
          semicolonCount++;
        }
      }
    }
    if (semicolonCount > 0) {
      throw new IllegalArgumentException(
          "Multiple SQL statements are not allowed in a single call. " +
              "Submit one statement at a time.");
    }
  }

  private static String stripStringLiterals(String sql) {
    StringBuilder out = new StringBuilder(sql.length());
    boolean inString = false;
    char quote = 0;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (inString) {
        if (c == quote) {
          if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
            i++;
            continue;
          }
          inString = false;
        }
        out.append(' ');
      } else {
        if (c == '\'' || c == '"') {
          inString = true;
          quote = c;
          out.append(' ');
        } else {
          out.append(c);
        }
      }
    }
    return out.toString();
  }
}
