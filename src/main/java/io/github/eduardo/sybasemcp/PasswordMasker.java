package io.github.eduardo.sybasemcp;

import java.util.regex.Pattern;

/**
 * Replaces password values in JDBC URLs and exception messages so they don't
 * leak into log files or error output.
 */
public final class PasswordMasker {
  private static final Pattern[] PATTERNS = new Pattern[] {
      Pattern.compile("(?i)(password\\s*=\\s*)([^;,\\s\"'&]+)"),
      Pattern.compile("(?i)(pwd\\s*=\\s*)([^;,\\s\"'&]+)"),
      Pattern.compile("(?i)(passwd\\s*=\\s*)([^;,\\s\"'&]+)"),
      Pattern.compile("(?i)(\\?|&)(password|pwd)=([^&]*)")
  };

  private PasswordMasker() {}

  public static String maskMessage(String input) {
    if (input == null) return null;
    String out = input;
    for (Pattern p : PATTERNS) {
      out = p.matcher(out).replaceAll(matchResult -> {
        if (matchResult.groupCount() == 3) {
          return matchResult.group(1) + matchResult.group(2) + "=***";
        }
        return matchResult.group(1) + "***";
      });
    }
    return out;
  }

  public static String maskUrl(String url) {
    return maskMessage(url);
  }
}
