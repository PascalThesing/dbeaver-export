/*
 * DBeaver headless export command
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.jkiss.dbeaver.ext.exportnow;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLQueryParameter;
import org.jkiss.dbeaver.model.sql.SQLScriptContext;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.runtime.IVariableResolver;

/**
 * Resolves the variables and parameters of all exports of a run before any export job starts:
 * {@code ${name}} in the JSON settings and {@code :name}, {@code ?}, {@code ${name}} in the
 * queries. Known values come from the {@link VariableLookup}; everything else is asked once in a
 * {@link ParameterDialog}.
 */
final class ExportVariables {

  /** A query parameter, its key across the run and its value if a variable already provides it. */
  private record QueryParameter(
      @NotNull SQLQueryParameter parameter, @NotNull String key, @Nullable String knownValue) {

    @Nullable
    String value(@NotNull Map<String, String> answers) {
      return knownValue != null ? knownValue : answers.get(key);
    }
  }

  /** One export with the parameters of its query. */
  private record ParsedExport(
      @NotNull ExportRequest request, @NotNull List<QueryParameter> parameters) {}

  private final SQLParserContextFactory parserContextFactory = new SQLParserContextFactory();
  private final ParameterDialog dialog = new ParameterDialog(parserContextFactory);
  private final Clock clock;

  ExportVariables() {
    this(Clock.systemDefaultZone());
  }

  ExportVariables(@NotNull Clock clock) {
    this.clock = clock;
  }

  /**
   * The exports with all variables and parameters substituted, or empty if the user cancelled the
   * parameters dialog. Query values are inserted as SQL text, like DBeaver does in the SQL editor;
   * in the settings one pair of enclosing single quotes is removed ({@code 'EU'} → {@code EU}).
   */
  @NotNull
  Optional<List<ExportRequest>> resolve(
      @NotNull SQLScriptContext scriptContext,
      @Nullable DBPDataSource dataSource,
      @NotNull List<ExportRequest> requests)
      throws DBException {
    final VariableLookup lookup =
        new VariableLookup(scriptContext, parserContextFactory.dialect(dataSource), clock);
    final List<ExportRequest> known = withKnownSettings(requests, lookup);
    if (scriptContext.isIgnoreParameters()) {
      return Optional.of(known);
    }
    final ParameterKeys keys = new ParameterKeys();
    final List<ParsedExport> exports = parse(known, dataSource, keys, lookup);
    final Map<String, String> settingsVariables = unknownSettingsVariables(known, lookup);
    final Set<String> unknownKeys = unknownKeys(exports, settingsVariables, keys);
    final Optional<Map<String, String>> answers =
        unknownKeys.isEmpty()
            ? Optional.of(Map.of())
            : dialog.ask(scriptContext, dataSource, sqlOf(known), settingsVariables, unknownKeys);
    if (answers.isEmpty()) {
      return Optional.empty();
    }
    if (scriptContext.isIgnoreParameters()) {
      return Optional.of(known); // "Ignore" in the dialog: run as written, like DBeaver does
    }
    return Optional.of(apply(exports, answers.get(), keys, lookup));
  }

  @NotNull
  private List<ExportRequest> withKnownSettings(
      @NotNull List<ExportRequest> requests, @NotNull VariableLookup lookup) {
    return requests.stream()
        .map(request -> request.withConfig(request.config().withJson(lookup::replaceIn)))
        .toList();
  }

  /** Parses every query in run order, so anonymous parameters get their keys in that order. */
  @NotNull
  private List<ParsedExport> parse(
      @NotNull List<ExportRequest> requests,
      @Nullable DBPDataSource dataSource,
      @NotNull ParameterKeys keys,
      @NotNull VariableLookup lookup) {
    final List<ParsedExport> exports = new ArrayList<>();
    for (ExportRequest request : requests) {
      final List<QueryParameter> parameters = new ArrayList<>();
      for (SQLQueryParameter parameter :
          parserContextFactory.parameters(dataSource, request.sql())) {
        parameters.add(
            new QueryParameter(
                parameter, keys.keyOf(parameter), knownValue(parameter, keys, lookup)));
      }
      exports.add(new ParsedExport(request, parameters));
    }
    return exports;
  }

  /**
   * The value of a variable named like the parameter. Like DBeaver's own parameters provider,
   * remembered dialog values are not used silently — they only prefill the dialog.
   */
  @Nullable
  private String knownValue(
      @NotNull SQLQueryParameter parameter,
      @NotNull ParameterKeys keys,
      @NotNull VariableLookup lookup) {
    return parameter.isNamed() ? lookup.get(keys.bareName(parameter)) : null;
  }

  @NotNull
  private Map<String, String> unknownSettingsVariables(
      @NotNull List<ExportRequest> requests, @NotNull VariableLookup lookup) {
    final Map<String, String> unknown = new LinkedHashMap<>();
    requests.forEach(
        request -> lookup.unknownNamesIn(request.config().json()).forEach(unknown::putIfAbsent));
    return unknown;
  }

  @NotNull
  private Set<String> unknownKeys(
      @NotNull List<ParsedExport> exports,
      @NotNull Map<String, String> settingsVariables,
      @NotNull ParameterKeys keys) {
    final Set<String> unknown = new LinkedHashSet<>();
    exports.stream()
        .flatMap(export -> export.parameters().stream())
        .filter(parameter -> parameter.knownValue() == null)
        .forEach(parameter -> unknown.add(parameter.key()));
    settingsVariables.keySet().forEach(name -> unknown.add(keys.keyOf(name)));
    return unknown;
  }

  @NotNull
  private List<ExportRequest> apply(
      @NotNull List<ParsedExport> exports,
      @NotNull Map<String, String> answers,
      @NotNull ParameterKeys keys,
      @NotNull VariableLookup lookup) {
    final IVariableResolver answered = name -> unquote(answers.get(keys.keyOf(name)));
    return exports.stream()
        .map(
            export ->
                new ExportRequest(
                    export.request().config().withJson(json -> lookup.replaceIn(json, answered)),
                    fillParameters(export, answers)))
        .toList();
  }

  /** Writes the values into the query with DBeaver's own routine (empty value → {@code NULL}). */
  @NotNull
  private String fillParameters(
      @NotNull ParsedExport export, @NotNull Map<String, String> answers) {
    if (export.parameters().isEmpty()) {
      return export.request().sql();
    }
    export.parameters().forEach(p -> p.parameter().setValue(p.value(answers)));
    final SQLQuery query = new SQLQuery(null, export.request().sql());
    SQLUtils.fillQueryParameters(
        query, export.parameters().stream().map(QueryParameter::parameter).toList());
    return query.getText();
  }

  @NotNull
  private List<String> sqlOf(@NotNull List<ExportRequest> requests) {
    return requests.stream().map(ExportRequest::sql).toList();
  }

  /** A file name or folder takes the value as text: {@code 'EU'} and {@code EU} give {@code EU}. */
  @Nullable
  private String unquote(@Nullable String value) {
    final boolean quoted =
        value != null && value.length() >= 2 && value.startsWith("'") && value.endsWith("'");
    return quoted ? value.substring(1, value.length() - 1) : value;
  }
}
