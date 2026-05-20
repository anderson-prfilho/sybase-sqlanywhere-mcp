package io.github.eduardo.sybasemcp;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static io.github.eduardo.sybasemcp.StringUtil.isNullOrEmpty;

public class Config {
  private static final String PREFIX = "Prefix";
  private static final String DRIVER = "DriverClass";
  private static final String DRIVER_JAR = "DriverPath";
  private static final String JDBC_URL = "JdbcUrl";
  private static final String TABLES = "Tables";
  private static final String LOG_FILE = "LogFile";

  private static final String USER = "User";
  private static final String PASSWORD = "Password";
  private static final String READ_ONLY = "ReadOnly";
  private static final String ALLOW_WRITE = "AllowWrite";
  private static final String MAX_ROWS = "MaxRows";
  private static final String QUERY_TIMEOUT_SECONDS = "QueryTimeoutSeconds";
  private static final String ALLOW_SCHEMAS = "AllowSchemas";
  private static final String BLOCK_SCHEMAS = "BlockSchemas";
  private static final String DEFAULT_FORMAT = "DefaultFormat";

  private static final int DEFAULT_MAX_ROWS = 1000;
  private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;
  private static final Set<String> DEFAULT_BLOCK_SCHEMAS = new HashSet<>(Arrays.asList(
      "SYS", "DBO", "RS_SYSTABGROUP", "PUBLIC"
  ));

  private static final String ID_QUOTE_OPEN_CHAR = "IDENTIFIER_QUOTE_OPEN_CHAR";
  private static final String ID_QUOTE_CLOSE_CHAR = "IDENTIFIER_QUOTE_CLOSE_CHAR";
  private static final String SUPPORTS_MULTIPLE_CATALOGS = "SUPPORTS_MULTIPLE_CATALOGS";
  private static final String SUPPORTS_MULTIPLE_SCHEMAS = "SUPPORTS_MULTIPLE_SCHEMAS";
  private Properties props = new Properties();
  private Properties sqlInfo = new Properties();
  private Driver driver;
  private String defCatalog;
  private String defSchema;

  public void load(String filepath) throws IOException {
    try (FileInputStream fis = new FileInputStream(filepath)) {
      props.load(fis);
    }
  }

  public boolean validate(PrintStream errors) {
    boolean result = true;
    if (isNullOrEmpty(getPrefix())) {
      errors.println("The '" + PREFIX + "' option is missing");
      result = false;
    }

    if (isNullOrEmpty(getDriver())) {
      errors.println("The '" + DRIVER + "' option is missing");
      result = false;
    }

    if (isNullOrEmpty(getDriverJar())) {
      errors.println("The '" + DRIVER_JAR + "' option is missing");
      result = false;
    } else if (!verifyDriverLoad(errors)) {
      result = false;
    }

    if (isNullOrEmpty(getJdbcUrl())) {
      errors.println("The '" + JDBC_URL + "' option is missing");
      result = false;
    } else if (result && !verifyJdbcUrl(errors)) {
      result = false;
    }
    return result;
  }

  public String getServerName() {
    return this.getPrefix();
  }
  public String getServerVersion() {
    return "2.0";
  }
  public String getPrefix() {
    return this.props.getProperty(PREFIX);
  }
  public String getMcpScheme() {
    return getPrefix() + "://";
  }
  public String getDriver() {
    return this.props.getProperty(DRIVER);
  }
  public String getDriverJar() {
    return this.props.getProperty(DRIVER_JAR);
  }
  public String getJdbcUrl() {
    return this.props.getProperty(JDBC_URL);
  }
  public String getUser() {
    return this.props.getProperty(USER);
  }
  public String getPassword() {
    return this.props.getProperty(PASSWORD);
  }
  public boolean isReadOnly() {
    return parseBoolean(this.props.getProperty(READ_ONLY), true);
  }
  public boolean isWriteAllowed() {
    return parseBoolean(this.props.getProperty(ALLOW_WRITE), false);
  }
  public int getMaxRows() {
    return parseInt(this.props.getProperty(MAX_ROWS), DEFAULT_MAX_ROWS);
  }
  public int getQueryTimeoutSeconds() {
    return parseInt(this.props.getProperty(QUERY_TIMEOUT_SECONDS), DEFAULT_QUERY_TIMEOUT_SECONDS);
  }
  public Format getDefaultFormat() {
    return Format.parse(this.props.getProperty(DEFAULT_FORMAT), Format.CSV);
  }

  public Set<String> getAllowSchemas() {
    return parseCsvSet(this.props.getProperty(ALLOW_SCHEMAS));
  }

  public Set<String> getBlockSchemas() {
    Set<String> configured = parseCsvSet(this.props.getProperty(BLOCK_SCHEMAS));
    if (configured.isEmpty()) {
      return new HashSet<>(DEFAULT_BLOCK_SCHEMAS);
    }
    return configured;
  }

  public List<Table> getTables() throws SQLException {
    String tables = this.props.getProperty(TABLES);
    if (isNullOrEmpty(tables)) {
      return new ArrayList<>();
    }
    List<Table> entries = Table.parseList(tables);
    return completeTableList(entries);
  }
  public String getIdentifierQuotes() {
    return this.sqlInfo.getProperty(ID_QUOTE_OPEN_CHAR)
        + this.sqlInfo.getProperty(ID_QUOTE_CLOSE_CHAR);
  }
  public boolean supportsMultipleCatalogs() {
    String val = this.sqlInfo.getProperty(SUPPORTS_MULTIPLE_CATALOGS);
    return val.equalsIgnoreCase("YES");
  }
  public boolean supportsMultipleSchemas() {
    String val = this.sqlInfo.getProperty(SUPPORTS_MULTIPLE_SCHEMAS);
    return val.equalsIgnoreCase("YES");
  }
  public String defaultCatalog() {
    return this.defCatalog;
  }
  public String defaultSchema() {
    return this.defSchema;
  }

  public String getLogFile() {
    return this.props.getProperty(LOG_FILE);
  }

  public String quoteIdentifier(String id) {
    String open = this.sqlInfo.getProperty(ID_QUOTE_OPEN_CHAR);
    String close = this.sqlInfo.getProperty(ID_QUOTE_CLOSE_CHAR);
    return open + id.replace(close, close + close) + close;
  }

  public Connection newConnection() throws SQLException {
    Properties connProps = new Properties();
    if (!isNullOrEmpty(getUser())) {
      connProps.setProperty("user", getUser());
    }
    if (!isNullOrEmpty(getPassword())) {
      connProps.setProperty("password", getPassword());
    }
    Connection cn = this.driver.connect(this.getJdbcUrl(), connProps);
    if (cn == null) {
      throw new SQLException("JDBC driver returned null connection - check JdbcUrl/credentials");
    }
    return cn;
  }

  /**
   * Opens a connection with the appropriate readonly flag for the requested mode.
   * @param write true when the caller is going to run a write statement
   */
  public Connection newConnection(boolean write) throws SQLException {
    Connection cn = newConnection();
    try {
      cn.setReadOnly(!write && isReadOnly());
    } catch (SQLException ignored) {
      // some drivers refuse setReadOnly; keep going
    }
    return cn;
  }

  private boolean verifyDriverLoad(PrintStream errors) {
    if (!new File(getDriverJar()).exists()) {
      errors.println("The '" + DRIVER_JAR + "' option is not a valid JAR file");
      return false;
    }
    try {
      loadDriver();
      return true;
    } catch (Throwable t) {
      String msg = t.getClass().getName() + ": " + t.getMessage();
      errors.println("Attempting to load the JDBC driver failed: " + msg);
    }
    return false;
  }

  private boolean verifyJdbcUrl(PrintStream errors) {
    try {
      try (Connection cn = newConnection()) {
      }
      return true;
    } catch ( SQLException ex ) {
      errors.println("Failed to open JDBC connection: " + PasswordMasker.maskMessage(ex.getMessage()));
    }
    return false;
  }

  private void loadDriver() throws Exception {
    URLClassLoader ucl = new URLClassLoader(
        new URL[] {
            new File(this.getDriverJar()).toURI().toURL(),
        },
        this.getClass().getClassLoader()
    );
    Class dc = ucl.loadClass(this.getDriver());
    this.driver = (Driver)dc.getDeclaredConstructor().newInstance();

    loadSqlInfo();
  }

  private void loadSqlInfo() throws SQLException {
    try (Connection cn = newConnection()) {
      retrieveSqlInfo(cn);
      if (!this.supportsMultipleCatalogs()) {
        if (!this.supportsMultipleSchemas()) {
          retrieveDefaultCatalogAndSchema(cn);
        } else {
          retrieveDefaultCatalog(cn);
        }
      }
    }
  }

  private void retrieveDefaultCatalogAndSchema(Connection cn) throws SQLException {
    DatabaseMetaData meta = cn.getMetaData();
    try (ResultSet rs = meta.getSchemas()) {
      rs.next();
      this.defCatalog = rs.getString("TABLE_CATALOG");
      this.defSchema = rs.getString("TABLE_SCHEM");
    }
  }

  private void retrieveDefaultCatalog(Connection cn) throws SQLException {
    DatabaseMetaData meta = cn.getMetaData();
    try (ResultSet rs = meta.getCatalogs()) {
      rs.next();
      this.defCatalog = rs.getString(1);
    }
  }

  private void retrieveSqlInfo(Connection cn) throws SQLException {
    try {
      DatabaseMetaData meta = cn.getMetaData();
      String quoteStr = meta.getIdentifierQuoteString();
      if (quoteStr == null || quoteStr.isEmpty()) {
        quoteStr = "\"";
      }
      this.sqlInfo.put(ID_QUOTE_OPEN_CHAR, quoteStr);
      this.sqlInfo.put(ID_QUOTE_CLOSE_CHAR, quoteStr);
    } catch (SQLException e) {
      this.sqlInfo.put(ID_QUOTE_OPEN_CHAR, "\"");
      this.sqlInfo.put(ID_QUOTE_CLOSE_CHAR, "\"");
    }
    this.sqlInfo.put(SUPPORTS_MULTIPLE_CATALOGS, "YES");
    this.sqlInfo.put(SUPPORTS_MULTIPLE_SCHEMAS, "YES");
  }

  private List<Table> completeTableList(List<Table> list) throws SQLException {
    List<Table> result = new ArrayList<>();
    try (Connection cn = newConnection()) {
      DatabaseMetaData meta = cn.getMetaData();
      for (Table t : list) {
        addMatchingTables(t, meta, result);
      }
    }
    return result;
  }

  private void addMatchingTables(Table t, DatabaseMetaData meta, List<Table> result) throws SQLException {
    String catalog = t.hasCatalog() ? t.catalog() : null;
    String schema = t.hasSchema() ? t.schema() : null;
    String name = t.name();
    try (ResultSet rs = meta.getTables(catalog, schema, name, null)) {
      while (rs.next()) {
        catalog = rs.getString("TABLE_CAT");
        schema = rs.getString("TABLE_SCHEM");
        name = rs.getString("TABLE_NAME");
        result.add(new Table(catalog, schema, name));
      }
    }
  }

  private static boolean parseBoolean(String value, boolean defaultValue) {
    if (isNullOrEmpty(value)) {
      return defaultValue;
    }
    String v = value.trim().toLowerCase();
    return v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on");
  }

  private static int parseInt(String value, int defaultValue) {
    if (isNullOrEmpty(value)) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  private static Set<String> parseCsvSet(String csv) {
    if (isNullOrEmpty(csv)) {
      return Collections.emptySet();
    }
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(String::toUpperCase)
        .collect(Collectors.toCollection(HashSet::new));
  }
}
