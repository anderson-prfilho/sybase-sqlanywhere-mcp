package io.github.eduardo.sybasemcp;

public class CsvWriter {
  private static final String NULL_TOKEN = "";
  private StringBuilder buffer = new StringBuilder();

  public Row row() {
    return new Row();
  }

  public String end() {
    return this.buffer.toString();
  }

  public class Row {
    private StringBuilder row = new StringBuilder();
    private int columnCount = 0;

    public Row column(String value) {
      if (columnCount > 0) {
        row.append(',');
      }
      columnCount++;
      if (value == null) {
        row.append(NULL_TOKEN);
      } else {
        quote(value);
      }
      return this;
    }

    public void end() {
      buffer.append(this.row).append("\n");
    }

    private void quote(String val) {
      row.append('"');
      for (int i = 0; i < val.length(); i++) {
        char ch = val.charAt(i);
        if (ch == '"') {
          row.append(ch);
        }
        row.append(ch);
      }
      row.append('"');
    }
  }
}
