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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLQueryParameter;
import org.jkiss.dbeaver.model.sql.SQLScriptContext;

/**
 * Asks the unknown parameters of an export run in one DBeaver bind-parameters dialog. The dialog
 * shows every query plus one {@code key = ${name}} line per unknown settings variable; a name used
 * in several places is asked once.
 */
final class ParameterDialog {

  private static final String SEPARATOR = ";\n";
  private static final Pattern NOT_A_KEY_CHARACTER = Pattern.compile("[^\\w.-]");
  private static final String DEFAULT_DISPLAY_KEY = "value";

  private final SQLParserContextFactory parserContextFactory;

  ParameterDialog(@NotNull SQLParserContextFactory parserContextFactory) {
    this.parserContextFactory = parserContextFactory;
  }

  /**
   * The answers by parameter key ({@link ParameterKeys}), or empty if the user cancelled. After
   * "Ignore" the answers are empty and the script context reports {@code isIgnoreParameters()}.
   */
  @NotNull
  Optional<Map<String, String>> ask(
      @NotNull SQLScriptContext scriptContext,
      @Nullable DBPDataSource dataSource,
      @NotNull List<String> queries,
      @NotNull Map<String, String> settingsVariables,
      @NotNull Set<String> unknownKeys)
      throws DBException {
    final String text = dialogText(queries, settingsVariables);
    final Map<String, SQLQueryParameter> asked = askedParameters(dataSource, text, unknownKeys);
    final SQLQuery dialogQuery = new SQLQuery(dataSource, text);
    dialogQuery.setParameters(new ArrayList<>(asked.values()));
    if (!scriptContext.fillQueryParameters(dialogQuery, () -> null, false)) {
      return Optional.empty();
    }
    if (scriptContext.isIgnoreParameters()) {
      return Optional.of(Map.of());
    }
    final Map<String, String> answers = answers(asked);
    requireAll(unknownKeys, answers);
    return Optional.of(answers);
  }

  @NotNull
  private String dialogText(
      @NotNull List<String> queries, @NotNull Map<String, String> settingsVariables) {
    final StringJoiner text = new StringJoiner(SEPARATOR, "", SEPARATOR);
    queries.forEach(text::add);
    // DBeaver's SQL parser only recognises lower-case variable names; keys are lower-case anyway
    settingsVariables.forEach(
        (name, key) -> text.add(displayKey(key) + " = ${" + name.toLowerCase(Locale.ROOT) + "}"));
    return text.toString();
  }

  /** The first occurrence of every unknown parameter, in dialog-text order. */
  @NotNull
  private Map<String, SQLQueryParameter> askedParameters(
      @Nullable DBPDataSource dataSource, @NotNull String text, @NotNull Set<String> unknownKeys) {
    final ParameterKeys keys = new ParameterKeys();
    final Map<String, SQLQueryParameter> asked = new LinkedHashMap<>();
    for (SQLQueryParameter parameter : parserContextFactory.parameters(dataSource, text)) {
      final String key = keys.keyOf(parameter);
      if (unknownKeys.contains(key)) {
        asked.putIfAbsent(key, parameter);
      }
    }
    return asked;
  }

  @NotNull
  private Map<String, String> answers(@NotNull Map<String, SQLQueryParameter> asked) {
    final Map<String, String> answers = new LinkedHashMap<>();
    asked.forEach((key, parameter) -> answers.put(key, parameter.getValue()));
    return answers;
  }

  /** Every unknown parameter needs an answer — also one the dialog text didn't show. */
  private void requireAll(@NotNull Set<String> unknownKeys, @NotNull Map<String, String> answers)
      throws DBException {
    final String missing =
        unknownKeys.stream()
            .filter(key -> answers.get(key) == null)
            .collect(Collectors.joining(", "));
    if (!missing.isEmpty()) {
      throw new DBException("@exportnow: unresolved parameters: " + missing);
    }
  }

  /** The JSON key is only shown; characters the SQL parser might read as parameters are dropped. */
  @NotNull
  private String displayKey(@NotNull String key) {
    final String displayKey = NOT_A_KEY_CHARACTER.matcher(key).replaceAll("");
    return displayKey.isEmpty() ? DEFAULT_DISPLAY_KEY : displayKey;
  }
}
