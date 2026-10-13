/*
 * DBeaver headless export command — tests
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.jkiss.dbeaver.ext.exportnow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.StringReader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.connection.DBPDriverLibrary;
import org.jkiss.dbeaver.model.data.DBDDataFormatterProfile;
import org.jkiss.dbeaver.model.data.json.JSONUtils;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLScriptContext;
import org.jkiss.dbeaver.model.sql.data.SQLQueryDataContainer;
import org.jkiss.dbeaver.registry.DataSourceProviderRegistry;
import org.jkiss.dbeaver.registry.driver.DriverDescriptor;
import org.jkiss.dbeaver.registry.driver.DriverLibraryAbstract;
import org.jkiss.dbeaver.registry.formatter.DataFormatterRegistry;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Integration tests against a real PostgreSQL (production uses server databases, not SQLite): true
 * concurrent exports over per-export connections, and — in the full tier ({@code
 * -Dexportnow.perf=full}) — volume/performance runs with 500,000+ rows × 110 columns. Skipped
 * unless EXPORTNOW_PG_URL/USER/PASSWORD are set (see scripts/test-db.sh).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "EXPORTNOW_PG_URL", matches = ".+")
public class PostgresExportTest {

  private static final Log log = Log.getLog(PostgresExportTest.class);

  private final VoidProgressMonitor monitor = new VoidProgressMonitor();
  private DBPDataSourceContainer dataSource;
  private Path outDir;

  @BeforeAll
  public void connect() throws Exception {
    final String url = System.getenv("EXPORTNOW_PG_URL");
    final URI uri = URI.create(url.replaceFirst("^jdbc:", ""));

    DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
    if (project == null) {
      // DesktopWorkspaceImpl.createProject only adopts an EXISTING Eclipse project
      // (inverted exists() check upstream) - pre-create it via the resources API
      final IProject eclipseProject =
          ResourcesPlugin.getWorkspace().getRoot().getProject("exportnow-test");
      if (!eclipseProject.exists()) {
        eclipseProject.create(null);
      }
      eclipseProject.open(null);
      // the workspace resource listener registers the DBPProject wrapper
      project = DBWorkbench.getPlatform().getWorkspace().getProject("exportnow-test");
      if (project == null) {
        project = DBWorkbench.getPlatform().getWorkspace().createProject("exportnow-test", null);
      }
    }
    assertNotNull(project, "active project required");
    final DBPDriver driver =
        DataSourceProviderRegistry.getInstance()
            .getDataSourceProvider("postgresql")
            .getDriver("postgres-jdbc");
    assertNotNull(driver, "postgres-jdbc driver must exist");

    // The headless test runtime has no driver-download service: fetch the JDBC
    // jar directly and register it as a local driver library.
    final Path driverJar =
        Path.of(System.getProperty("java.io.tmpdir"), "exportnow-driver", "postgresql-42.7.13.jar");
    if (!Files.exists(driverJar)) {
      Files.createDirectories(driverJar.getParent());
      try (InputStream in =
          java.net
              .URI
              .create(
                  "https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.13/postgresql-42.7.13.jar")
              .toURL()
              .openStream()) {
        Files.copy(in, driverJar);
      }
    }
    final DriverDescriptor driverDescriptor = (DriverDescriptor) driver;
    // an unresolved downloadable (maven) library makes validateFilesPresence return
    // an empty list, dropping our local jar too - disable the stock libraries
    for (DBPDriverLibrary lib : driverDescriptor.getDriverLibraries()) {
      if (lib instanceof DriverLibraryAbstract abstractLib) {
        abstractLib.setDisabled(true);
      }
    }
    driverDescriptor.addDriverLibrary(
        DriverLibraryAbstract.createFromPath(
            driverDescriptor,
            DBPDriverLibrary.FileType.jar,
            driverJar.toAbsolutePath().toString(),
            null),
        true);

    final DBPConnectionConfiguration cfg = new DBPConnectionConfiguration();
    cfg.setHostName(uri.getHost());
    cfg.setHostPort(String.valueOf(uri.getPort()));
    cfg.setDatabaseName(uri.getPath().replaceFirst("^/", ""));
    cfg.setUserName(System.getenv("EXPORTNOW_PG_USER"));
    cfg.setUserPassword(System.getenv("EXPORTNOW_PG_PASSWORD"));
    cfg.setUrl(url);

    dataSource = project.getDataSourceRegistry().createDataSource(driver, cfg);
    dataSource.setName("exportnow-pg-test");
    dataSource.setSavePassword(true);
    project.getDataSourceRegistry().addDataSource(dataSource);
    assertTrue(dataSource.connect(monitor, true, true), "connection must succeed");

    outDir = Files.createTempDirectory("exportnow-pg");
  }

  @AfterAll
  public void cleanup() throws Exception {
    execute("drop table if exists exportnow_conc");
    if (dataSource != null) {
      for (DBPDriverLibrary lib :
          ((DriverDescriptor) dataSource.getDriver()).getDriverLibraries()) {
        if (lib instanceof DriverLibraryAbstract abstractLib) {
          abstractLib.setDisabled(false);
        }
      }
      dataSource.disconnect(monitor);
      dataSource.getRegistry().removeDataSource(dataSource);
    }
    if (outDir != null) {
      try (var files = Files.walk(outDir)) {
        files.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
      }
    }
  }

  /** 55 concurrent exports; verifies completion, exact row counts and connection fan-out. */
  @Test
  public void fiftyFivePlusConcurrentExports() throws Exception {
    final int jobs = 55;
    final int rows = 2000;
    execute("drop table if exists exportnow_conc");
    execute("create unlogged table exportnow_conc as " + wideQuery(rows, 110, 0));
    final List<HeadlessExportJob> scheduled = new ArrayList<>();
    for (int i = 0; i < jobs; i++) {
      scheduled.add(export("select * from exportnow_conc", "conc" + i, Map.of()));
    }
    final int baselineBackends = countBackends();
    final int maxBackends = watchBackendsWhileRunning(scheduled);
    joinAll(scheduled);

    final Set<String> names = new HashSet<>();
    for (int i = 0; i < jobs; i++) {
      final Path csv = outDir.resolve("conc" + i + ".csv");
      assertTrue(Files.exists(csv), csv + " missing");
      assertEquals(rows + 1, countLines(csv), csv + " line count");
      names.add(csv.getFileName().toString());
    }
    try (var files = Files.list(outDir)) {
      assertEquals(
          jobs,
          files.filter(f -> f.getFileName().toString().matches("conc\\d+\\.csv")).count(),
          "all output files distinct");
    }
    // fast exports drain before all 55 overlap; >=10 extra backends still proves
    // per-export connections rather than a single shared one
    assertTrue(
        maxBackends - baselineBackends >= 10,
        "expected per-export connections (openNewConnections): baseline "
            + baselineBackends
            + ", max "
            + maxBackends);
  }

  /** The scanner must also work with the real PostgreSQL dialect rules (e.g. $$-quoting). */
  @Test
  public void scannerHandlesPostgresDialect() {
    final String text =
        """
        @exportnow {"type": "csv"};
        select $$a;b$$ as dollar_quoted, 1 as n;
        """;
    final var blocks = new ExportBlockScanner().findBlocks(dataSource.getDataSource(), text);
    assertEquals(1, blocks.size());
    assertTrue(
        blocks.get(0).request().sql().contains("dollar_quoted"),
        "semicolon inside $$-quotes must not split the statement: " + blocks);
  }

  /** Excel preset + German data-format profile: UTF-8 BOM, ';' columns, decimal comma. */
  @Test
  public void excelPresetWithGermanProfile() throws Exception {
    final DataFormatterRegistry formatters = DataFormatterRegistry.getInstance();
    final DBDDataFormatterProfile german = formatters.createCustomProfile("exportnow-pg-de");
    german.setLocale(Locale.GERMANY);
    // as the Data Formats preference page does
    german.saveProfile(german.getPreferenceStore());
    try {
      joinAll(
          List.of(
              export(
                  "select 1234.5::numeric as amount, 'a' as name",
                  "excel_de",
                  Map.of("preset", "excel", "formatterProfile", "exportnow-pg-de"))));
      final byte[] bytes = Files.readAllBytes(outDir.resolve("excel_de.csv"));
      assertTrue(
          bytes.length > 3
              && (bytes[0] & 0xff) == 0xEF
              && (bytes[1] & 0xff) == 0xBB
              && (bytes[2] & 0xff) == 0xBF,
          "UTF-8 BOM expected");
      final List<String> lines =
          new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8).lines().toList();
      assertEquals("\"amount\";\"name\"", lines.get(0));
      assertTrue(
          lines.get(1).matches("1\\.?234,5;a"), "decimal comma and ';' expected: " + lines.get(1));
    } finally {
      formatters.deleteCustomProfile(german);
    }
  }

  @Test
  public void tabDelimiter() throws Exception {
    joinAll(
        List.of(
            export(
                "select 1 as a, 2 as b",
                "tabbed",
                Map.of("processor", Map.of("delimiter", "\t")))));
    assertEquals(List.of("\"a\"\t\"b\"", "1\t2"), Files.readAllLines(outDir.resolve("tabbed.csv")));
  }

  /** ${user} (DB login) resolves in the query and in the file name, before the job runs. */
  @Test
  public void connectionUserVariableInQueryAndFileName() throws Exception {
    final SQLScriptContext scriptContext =
        new SQLScriptContext(
            null,
            () -> DBUtils.getDefaultContext(dataSource.getDataSource(), false),
            null,
            Writer.nullWriter(),
            null);
    final ExportVariables variables = new ExportVariables();
    final ExportRequest resolved =
        variables
            .resolve(
                scriptContext,
                dataSource.getDataSource(),
                List.of(
                    new ExportRequest(
                        new ExportConfig(Map.of("outputFilePattern", "who_${user}")),
                        "select '${user}' as login, current_user as db_user")))
            .orElseThrow()
            .get(0);
    final String sql = resolved.sql();
    final String pattern = (String) resolved.config().json().get("outputFilePattern");
    final String user = System.getenv("EXPORTNOW_PG_USER");
    assertEquals("who_" + user, pattern);

    joinAll(List.of(export(sql, pattern, Map.of())));
    assertEquals(
        List.of("\"login\",\"db_user\"", user + "," + user),
        Files.readAllLines(outDir.resolve("who_" + user + ".csv")));
  }

  /** Full tier: 500k × 110 volume run plus benchmarks and 55×100k combined stress. */
  @Test
  @EnabledIfSystemProperty(named = "exportnow.perf", matches = "full")
  public void volumeAndBenchmarks() throws Exception {

    // Materialize the dataset once so the export measures the exporter,
    // not the server's md5() row generation (16x slower than serving a table)
    execute("drop table if exists exportnow_wide");
    execute("create table exportnow_wide as " + wideQuery(500_000, 110, 0));

    // Volume: 500,000 rows x 110 columns, default settings
    long t =
        runTimed("volume-500k-default", "select * from exportnow_wide", "vol_default", Map.of());
    final Path vol = outDir.resolve("vol_default.csv");
    assertEquals(500_001, countLines(vol), "volume export row count");
    final long bytes = Files.size(vol);
    log.warn(
        String.format(
            "[perf] 500k x 110 default: %d ms, %d MB, %.1f MB/s",
            t, bytes / 1_000_000, bytes / 1000.0 / t));
    Files.delete(vol);

    // Benchmark: fetchSize 50k
    t =
        runTimed(
            "volume-500k-fetch50k",
            "select * from exportnow_wide",
            "vol_fetch",
            Map.of("producer", Map.of("fetchSize", 50_000)));
    log.warn("[perf] 500k x 110 fetchSize=50000: " + t + " ms");
    assertEquals(
        500_001, countLines(outDir.resolve("vol_fetch.csv")), "fetchSize variant row count");
    Files.deleteIfExists(outDir.resolve("vol_fetch.csv"));

    // Benchmark: native value format
    t =
        runTimed(
            "volume-500k-native",
            "select * from exportnow_wide",
            "vol_native",
            Map.of("consumer", Map.of("valueFormat", "NATIVE")));
    log.warn("[perf] 500k x 110 valueFormat=NATIVE: " + t + " ms");
    assertEquals(
        500_001, countLines(outDir.resolve("vol_native.csv")), "valueFormat variant row count");
    Files.deleteIfExists(outDir.resolve("vol_native.csv"));
    execute("drop table if exists exportnow_wide");
  }

  // --- helpers -------------------------------------------------------------

  /** ~1KB/row: id + 109 substr(md5) columns. */
  private String wideQuery(int rows, int cols, int seed) {
    final String columns =
        IntStream.range(1, cols)
            .mapToObj(i -> "substr(md5((g+" + (seed * 1000 + i) + ")::text),1,8) as c" + i)
            .collect(Collectors.joining(", "));
    return "select g as id, " + columns + " from generate_series(1," + rows + ") g";
  }

  private HeadlessExportJob createJob(String sql, String filePattern, Map<String, Object> extra)
      throws Exception {
    final Map<String, Object> params =
        JSONUtils.parseMap(
            new Gson(),
            new StringReader(
                "{\"type\": \"csv\", \"consumer\": {\"outputFolder\": \""
                    + outDir.toString().replace("\\", "\\\\")
                    + "\", \"outputFilePattern\": \""
                    + filePattern
                    + "\"}}"));
    extra.forEach(
        (section, value) -> {
          if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> target =
                (Map<String, Object>)
                    params.computeIfAbsent(section, k -> new HashMap<String, Object>());
            map.forEach((k, v) -> target.put(String.valueOf(k), v));
          } else {
            params.put(section, value);
          }
        });
    final SQLScriptContext scriptContext =
        new SQLScriptContext(
            null,
            () -> DBUtils.getDefaultContext(dataSource.getDataSource(), false),
            null,
            Writer.nullWriter(),
            null);
    final SQLQuery query = new SQLQuery(dataSource.getDataSource(), sql);
    return new HeadlessExportJob(
        new SQLQueryDataContainer(scriptContext::getExecutionContext, query, scriptContext, log),
        new ExportConfig(params));
  }

  private HeadlessExportJob export(String sql, String filePattern, Map<String, Object> extra)
      throws Exception {
    final HeadlessExportJob job = createJob(sql, filePattern, extra);
    job.schedule();
    return job;
  }

  private long runTimed(String label, String sql, String filePattern, Map<String, Object> extra)
      throws Exception {
    final long start = System.currentTimeMillis();
    final HeadlessExportJob job = export(sql, filePattern, extra);
    assertTrue(job.join(300_000, null), label + " did not finish within 5 minutes");
    assertTrue(job.getResult().isOK(), label + " failed: " + job.getResult());
    return System.currentTimeMillis() - start;
  }

  private void joinAll(List<HeadlessExportJob> jobs) throws Exception {
    for (HeadlessExportJob job : jobs) {
      assertTrue(job.join(120_000, null), "export did not finish within 2 minutes");
      assertTrue(job.getResult().isOK(), "export failed: " + job.getResult());
    }
  }

  /** Samples pg_stat_activity while jobs run; returns the max backend count seen. */
  private int watchBackendsWhileRunning(List<HeadlessExportJob> jobs) throws Exception {
    final AtomicInteger max = new AtomicInteger();
    final long deadline = System.currentTimeMillis() + 300_000;
    while (System.currentTimeMillis() < deadline
        && jobs.stream().anyMatch(j -> j.getResult() == null)) {
      max.accumulateAndGet(countBackends(), Math::max);
      Thread.sleep(100);
    }
    return max.get();
  }

  private void execute(String sql) throws Exception {
    try (DBCSession session =
        DBUtils.getDefaultContext(dataSource.getDataSource(), false)
            .openSession(monitor, DBCExecutionPurpose.UTIL, "test setup")) {
      try (DBCStatement stmt =
          session.prepareStatement(DBCStatementType.SCRIPT, sql, false, false, false)) {
        stmt.executeStatement();
      }
    }
  }

  private int countBackends() throws Exception {
    try (DBCSession session =
        DBUtils.getDefaultContext(dataSource.getDataSource(), false)
            .openSession(monitor, DBCExecutionPurpose.UTIL, "count backends")) {
      try (DBCStatement stmt =
          session.prepareStatement(
              DBCStatementType.QUERY,
              "select count(*) from pg_stat_activity where datname = current_database()",
              false,
              false,
              false)) {
        stmt.executeStatement();
        try (DBCResultSet rs = stmt.openResultSet()) {
          rs.nextRow();
          return ((Number) rs.getAttributeValue(0)).intValue();
        }
      }
    }
  }

  private long countLines(Path file) throws Exception {
    long lines = 0;
    final byte[] buffer = new byte[1 << 16];
    try (InputStream in = Files.newInputStream(file)) {
      int n;
      while ((n = in.read(buffer)) >= 0) {
        for (int i = 0; i < n; i++) {
          if (buffer[i] == '\n') {
            lines++;
          }
        }
      }
    }
    return lines;
  }
}
