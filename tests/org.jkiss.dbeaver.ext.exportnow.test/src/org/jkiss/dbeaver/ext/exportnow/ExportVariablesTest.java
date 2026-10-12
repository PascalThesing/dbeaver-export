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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Writer;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.data.DBDDataReceiver;
import org.jkiss.dbeaver.model.impl.sql.BasicSQLDialect;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.sql.SQLParametersProvider;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLQueryParameter;
import org.jkiss.dbeaver.model.sql.SQLScriptContext;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.junit.jupiter.api.Test;

/**
 * Variables in the JSON settings and parameters in the queries: resolved from the script context,
 * the rest asked once per run through the context's parameters provider (in DBeaver: the SQL
 * editor's bind-parameters dialog).
 */
public class ExportVariablesTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(
          LocalDateTime.of(2026, 1, 2, 3, 4, 5, 999).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

  private final ExportVariables variables = new ExportVariables();

  /** Settings and queries of a run after resolving; {@code null} when cancelled. */
  private record Resolved(List<Map<String, Object>> settings, List<String> queries) {}

  private SQLScriptContext context(SQLParametersProvider provider) {
    return new SQLScriptContext(null, () -> null, null, Writer.nullWriter(), provider);
  }

  private VariableLookup lookup(SQLScriptContext context) {
    return new VariableLookup(context, BasicSQLDialect.INSTANCE, Clock.systemDefaultZone());
  }

  /** Resolves a run of exports; settings and queries are matched by index. */
  private Resolved resolveRun(
      ExportVariables variables,
      SQLScriptContext context,
      List<Map<String, Object>> settings,
      List<String> queries)
      throws DBException {
    List<ExportRequest> requests =
        IntStream.range(0, queries.size())
            .mapToObj(
                i ->
                    new ExportRequest(
                        new ExportConfig(i < settings.size() ? settings.get(i) : Map.of()),
                        queries.get(i)))
            .toList();
    return variables
        .resolve(context, null, requests)
        .map(
            resolved ->
                new Resolved(
                    resolved.stream().map(request -> request.config().json()).toList(),
                    resolved.stream().map(ExportRequest::sql).toList()))
        .orElse(null);
  }

  private Resolved resolveRun(
      SQLScriptContext context, List<Map<String, Object>> settings, List<String> queries)
      throws DBException {
    return resolveRun(variables, context, settings, queries);
  }

  /** Resolves queries only (no JSON settings). */
  private List<String> queries(SQLScriptContext context, List<String> queries) throws DBException {
    Resolved resolved = resolveRun(context, List.of(), queries);
    return resolved == null ? null : resolved.queries();
  }

  // --- JSON settings -------------------------------------------------------

  @Test
  public void settingsResolveScriptAndSystemVariables() {
    SQLScriptContext context = context(null);
    context.setVariable("region", "EU");
    Map<String, Object> resolved =
        lookup(context)
            .replaceIn(
                Map.of(
                    "type",
                    "csv",
                    "maxJobCount",
                    4,
                    "consumer",
                    Map.of(
                        "outputFolder", "/exports/${user.name}",
                        "outputFilePattern", "orders_${region}",
                        "list", List.of("${region}", 1))));
    @SuppressWarnings("unchecked")
    Map<String, Object> consumer = (Map<String, Object>) resolved.get("consumer");
    assertEquals("/exports/" + System.getProperty("user.name"), consumer.get("outputFolder"));
    assertEquals("orders_EU", consumer.get("outputFilePattern"));
    assertEquals(List.of("EU", 1), consumer.get("list"));
    assertEquals(4, resolved.get("maxJobCount"), "non-string values are kept");
  }

  @Test
  public void settingsKeepDBeaverPatternVariablesUnknownNamesAndPassword() {
    Map<String, Object> resolved =
        lookup(context(null))
            .replaceIn(
                Map.of(
                    "consumer",
                    Map.of(
                        "outputFilePattern",
                        "x_${date}_${table}_${timestamp}_${index}_${nope}_${password}")),
                name -> "RESOLVED");
    @SuppressWarnings("unchecked")
    Map<String, Object> consumer = (Map<String, Object>) resolved.get("consumer");
    assertEquals(
        "x_${date}_${table}_${timestamp}_${index}_RESOLVED_${password}",
        consumer.get("outputFilePattern"),
        "DBeaver's own file-pattern variables and the password must never be pre-resolved");

    Map<String, Object> unknown =
        lookup(context(null))
            .replaceIn(Map.of("consumer", Map.of("outputFilePattern", "x_${nope}")));
    assertEquals(
        Map.of("outputFilePattern", "x_${nope}"),
        unknown.get("consumer"),
        "unknown names stay literal");
  }

  // --- query parameters ----------------------------------------------------

  @Test
  public void queriesWithoutParametersAreUnchanged() throws DBException {
    List<String> queries = List.of("select 1", "select ':x' as s from t");
    assertEquals(queries, queries(context(null), queries));
  }

  @Test
  public void variablesAreSubstitutedWithoutAsking() throws DBException {
    RecordingProvider provider = new RecordingProvider(Map.of());
    SQLScriptContext context = context(provider);
    context.setVariable("region", "'EU'");
    List<String> resolved =
        queries(
            context,
            List.of("select * from a where r = ${region}", "select * from b where r = :region"));
    assertEquals(
        List.of("select * from a where r = 'EU'", "select * from b where r = 'EU'"), resolved);
    assertEquals(0, provider.calls, "nothing to ask");
  }

  @Test
  public void rememberedDialogValuesAreAskedAgain() throws DBException {
    RecordingProvider provider = new RecordingProvider(Map.of("minId", "20"));
    SQLScriptContext context = context(provider);
    // what DBeaver's dialog stores: used to prefill the next dialog, not silently
    context.setParameterDefaultValue("minId", "10");
    List<String> resolved = queries(context, List.of("select * from b where id > :minId"));
    assertEquals(1, provider.calls, "asked on every execution, like DBeaver");
    assertEquals(List.of("select * from b where id > 20"), resolved);
  }

  @Test
  public void systemVariablesResolveInQueries() throws DBException {
    List<String> resolved =
        queries(context(null), List.of("select * from t where owner = '${user.name}'"));
    assertEquals(
        List.of("select * from t where owner = '" + System.getProperty("user.name") + "'"),
        resolved);
  }

  @Test
  public void unresolvedParametersAreAskedOnceForAllQueries() throws DBException {
    RecordingProvider provider = new RecordingProvider(Map.of("region", "'EU'", "year", "2026"));
    List<String> queries =
        List.of(
            "select * from a where r = :region",
            "select * from bb where r = :region and y = :year");
    List<String> resolved = queries(context(provider), queries);

    assertEquals(1, provider.calls, "a single dialog per run");
    assertEquals(List.of("region", "year"), provider.askedNames, "same name is asked once");
    assertEquals(
        List.of(":region", ":year"),
        provider.askedTokens,
        "parameter offsets must point into the text shown in the dialog");
    assertEquals(
        List.of("select * from a where r = 'EU'", "select * from bb where r = 'EU' and y = 2026"),
        resolved);
  }

  @Test
  public void cancelledDialogCancelsTheRun() throws DBException {
    RecordingProvider provider = new RecordingProvider(Map.of());
    provider.result = false;
    assertNull(queries(context(provider), List.of("select :a")));
  }

  @Test
  public void ignoreInDialogRunsQueriesAsWritten() throws DBException {
    RecordingProvider provider = new RecordingProvider(Map.of());
    provider.result = null;
    List<String> queries = List.of("select * from t where a = :a");
    assertEquals(queries, queries(context(provider), queries));
  }

  @Test
  public void unresolvedParametersWithoutUiFail() {
    DBException e =
        assertThrows(
            DBException.class,
            () -> queries(context(null), List.of("select * from t where a = :missing")));
    assertTrue(e.getMessage().contains("missing"), e.getMessage());
  }

  @Test
  public void anonymousParametersAreAskedOnceEachInOrder() throws DBException {
    DBPPreferenceStore preferences = DBWorkbench.getPlatform().getPreferenceStore();
    boolean enabled = preferences.getBoolean(ModelPreferences.SQL_ANONYMOUS_PARAMETERS_ENABLED);
    preferences.setValue(ModelPreferences.SQL_ANONYMOUS_PARAMETERS_ENABLED, true);
    try {
      RecordingProvider provider = new RecordingProvider(Map.of());
      provider.anonymousValues.addAll(List.of("1", "2", "3"));
      List<String> resolved =
          queries(
              context(provider),
              List.of("select * from a where x = ?", "select * from b where y = ? and z = ?"));
      assertEquals(1, provider.calls, "a single dialog per run");
      assertEquals(List.of("?", "?", "?"), provider.askedNames, "every ? is asked, in order");
      assertEquals(
          List.of("select * from a where x = 1", "select * from b where y = 2 and z = 3"),
          resolved);
    } finally {
      preferences.setValue(ModelPreferences.SQL_ANONYMOUS_PARAMETERS_ENABLED, enabled);
    }
  }

  @Test
  public void dialogConfirmedWithoutValuesFailsClearly() {
    RecordingProvider provider = new RecordingProvider(Map.of());
    DBException e =
        assertThrows(
            DBException.class,
            () ->
                queries(
                    context(provider),
                    List.of("select * from t where a = :first and b = :second")));
    assertTrue(e.getMessage().contains("first, second"), e.getMessage());
  }

  // --- unknown variables in the JSON settings -------------------------------

  @Test
  public void unknownSettingsVariableIsAskedInTheSameDialog() throws DBException {
    RecordingProvider provider =
        new RecordingProvider(Map.of("region", "'EU'", "batch", "nightly"));
    Resolved resolved =
        resolveRun(
            context(provider),
            List.of(
                Map.of(
                    "consumer",
                    Map.of(
                        "outputFolder", "/exports/${batch}",
                        "outputFilePattern", "orders_${region}_${table}_${password}"))),
            List.of("select * from orders where region = :region"));

    assertEquals(1, provider.calls, "query and file-name variables share one dialog");
    assertEquals(
        List.of("region", "batch"),
        provider.askedNames,
        "same name in query and file name asked once; ${table} and ${password} never asked");
    assertEquals(
        List.of(":region", "${batch}"), provider.askedTokens, "offsets point into the dialog text");
    assertTrue(provider.lastText.contains("outputFolder = ${batch}"), provider.lastText);
    assertEquals(List.of("select * from orders where region = 'EU'"), resolved.queries());
    assertEquals(
        Map.of(
            "outputFolder",
            "/exports/nightly",
            "outputFilePattern",
            "orders_EU_${table}_${password}"),
        resolved.settings().get(0).get("consumer"),
        "quotes removed for file names; DBeaver's own variables kept");
  }

  @Test
  public void knownSettingsVariablesAreNotAsked() throws DBException {
    RecordingProvider provider = new RecordingProvider(Map.of());
    Resolved resolved =
        resolveRun(
            context(provider),
            List.of(Map.of("consumer", Map.of("outputFolder", "/exports/${user.name}"))),
            List.of("select 1"));
    assertEquals(0, provider.calls);
    assertEquals(
        Map.of("outputFolder", "/exports/" + System.getProperty("user.name")),
        resolved.settings().get(0).get("consumer"));
  }

  @Test
  public void settingsVariableWithDefaultIsNotAsked() throws DBException {
    RecordingProvider provider = new RecordingProvider(Map.of());
    Resolved resolved =
        resolveRun(
            context(provider),
            List.of(
                Map.of(
                    "consumer",
                    Map.of(
                        "outputFolder", "/exports/${batch:nightly}",
                        "outputFilePattern", "orders${suffix:''}"))),
            List.of("select 1"));
    assertEquals(0, provider.calls, "DBeaver's ${name:default} syntax supplies the value");
    assertEquals(
        Map.of("outputFolder", "/exports/nightly", "outputFilePattern", "orders"),
        resolved.settings().get(0).get("consumer"));
  }

  @Test
  public void settingsVariablesWithHyphenOrUpperCaseAreAsked() throws DBException {
    RecordingProvider provider =
        new RecordingProvider(Map.of("my-batch", "hyphen", "batch", "upper"));
    Resolved resolved =
        resolveRun(
            context(provider),
            List.of(Map.of("consumer", Map.of("outputFolder", "/x/${my-batch}/${Batch}"))),
            List.of("select 1"));
    assertEquals(List.of("my-batch", "batch"), provider.askedNames);
    assertEquals(
        Map.of("outputFolder", "/x/hyphen/upper"), resolved.settings().get(0).get("consumer"));
  }

  @Test
  public void unknownSettingsVariableWithoutUiFails() {
    DBException e =
        assertThrows(
            DBException.class,
            () ->
                resolveRun(
                    context(null),
                    List.of(Map.of("consumer", Map.of("outputFilePattern", "x_${typo}"))),
                    List.of("select 1")));
    assertTrue(e.getMessage().contains("typo"), e.getMessage());
  }

  @Test
  public void cancelledDialogForSettingsVariableCancelsTheRun() throws DBException {
    RecordingProvider provider = new RecordingProvider(Map.of());
    provider.result = false;
    assertNull(
        resolveRun(
            context(provider),
            List.of(Map.of("consumer", Map.of("outputFilePattern", "x_${typo}"))),
            List.of("select 1")));
  }

  // --- built-in date variables ---------------------------------------------

  @Test
  public void builtInDateVariablesHaveIsoFormat() {
    BuiltInVariables builtIns = new BuiltInVariables(FIXED_CLOCK);
    assertEquals("2026-01-02", builtIns.get("current_date"));
    assertEquals("03:04:05", builtIns.get("current_time"));
    assertEquals("2026-01-02 03:04:05", builtIns.get("current_timestamp"));
    assertEquals("2026-01-02", builtIns.get("CURRENT_DATE"), "names are case-insensitive");
    assertNull(builtIns.get("current_week"), "unknown names have no value");
  }

  @Test
  public void builtInDateWorksInQueryAndFileNameWithoutAsking() throws DBException {
    RecordingProvider provider = new RecordingProvider(Map.of());
    Resolved resolved =
        resolveRun(
            new ExportVariables(FIXED_CLOCK),
            context(provider),
            List.of(Map.of("consumer", Map.of("outputFilePattern", "orders_${current_date}"))),
            List.of(
                "select * from orders where created < date '${current_date}' and :current_date is"
                    + " not null"));
    String today = "2026-01-02";
    assertEquals(0, provider.calls, "built-ins are never asked");
    assertEquals(
        List.of(
            "select * from orders where created < date '"
                + today
                + "' and "
                + today
                + " is not null"),
        resolved.queries());
    assertEquals(
        Map.of("outputFilePattern", "orders_" + today), resolved.settings().get(0).get("consumer"));
  }

  @Test
  public void setVariableOverridesBuiltIn() throws DBException {
    SQLScriptContext context = context(null);
    context.setVariable("current_date", "2000-01-01");
    assertEquals(
        List.of("select '2000-01-01'"), queries(context, List.of("select '${current_date}'")));
  }

  @Test
  public void dbeaverFileNameVariablesAreNeverResolvedInSettings() {
    SQLScriptContext context = context(null);
    context.setVariable("table", "my_table");
    context.setVariable("password", "secret");
    Map<String, Object> resolved =
        lookup(context).replaceIn(Map.of("outputFilePattern", "${table}_${password}"));
    assertEquals(
        "${table}_${password}",
        resolved.get("outputFilePattern"),
        "left for DBeaver's consumer; the password never reaches a file name");
  }

  /**
   * Stands in for DBeaver's SQLEditorParametersProvider: records what would be shown, fills values.
   */
  private static final class RecordingProvider implements SQLParametersProvider {
    private final Map<String, String> values;
    private final List<String> askedNames = new ArrayList<>();
    private final List<String> askedTokens = new ArrayList<>();
    private final Deque<String> anonymousValues = new ArrayDeque<>();
    private int calls;
    private String lastText;
    private Boolean result = true;

    private RecordingProvider(Map<String, String> values) {
      this.values = values;
    }

    @Override
    public Boolean prepareStatementParameters(
        SQLScriptContext scriptContext,
        SQLQuery sqlStatement,
        List<SQLQueryParameter> parameters,
        Supplier<DBDDataReceiver> dataReceiver,
        boolean useDefaults) {
      calls++;
      lastText = sqlStatement.getText();
      for (SQLQueryParameter parameter : parameters) {
        String name =
            SQLQueryParameter.stripVariablePattern(parameter.getName()).replaceFirst("^:", "");
        askedNames.add(name);
        askedTokens.add(
            sqlStatement
                .getText()
                .substring(
                    parameter.getTokenOffset(),
                    parameter.getTokenOffset() + parameter.getTokenLength()));
        parameter.setValue("?".equals(name) ? anonymousValues.poll() : values.get(name));
      }
      if (result == null) {
        scriptContext.setIgnoreParameters(true);
      }
      return result;
    }
  }
}
