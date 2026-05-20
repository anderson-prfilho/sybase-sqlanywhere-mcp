package io.github.eduardo.sybasemcp;

/**
 * Output format for tool responses that return tabular data.
 */
public enum Format {
  CSV("csv", "text/csv"),
  JSON("json", "application/json"),
  MARKDOWN("markdown", "text/markdown");

  private final String name;
  private final String mime;

  Format(String name, String mime) {
    this.name = name;
    this.mime = mime;
  }

  public String getName() {
    return name;
  }

  public String getMime() {
    return mime;
  }

  public static Format parse(String value, Format fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    String v = value.trim().toLowerCase();
    for (Format f : values()) {
      if (f.name.equals(v)) return f;
    }
    if (v.equals("md")) return MARKDOWN;
    return fallback;
  }
}
