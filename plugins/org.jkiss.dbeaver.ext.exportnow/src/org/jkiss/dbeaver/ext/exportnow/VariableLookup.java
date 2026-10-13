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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.jkiss.dbeaver.model.sql.SQLScriptContext;
import org.jkiss.dbeaver.model.sql.commands.SQLCommandSet;
import org.jkiss.dbeaver.runtime.IVariableResolver;
import org.jkiss.dbeaver.tools.transfer.stream.StreamTransferConsumer;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;

/**
 * Variable values along a chain of resolvers — script variables (named like {@code @set} stores
 * them), connection and system variables, then the {@link BuiltInVariables} — and their use in the
 * JSON settings of an export. In the settings, DBeaver's own file-name variables ({@code ${table}},
 * {@code ${timestamp}}, …) and {@code ${password}} are never touched.
 */
final class VariableLookup implements IVariableResolver {

  /**
   * DBeaver's own variable syntax ({@code GeneralUtils}): {@code ${name}} or {@code
   * ${name:default}}.
   */
  private static final Pattern VARIABLE =
      Pattern.compile("\\$\\{([\\w.\\-]+)(:[^${}]+)?}", Pattern.CASE_INSENSITIVE);

  /** JSON key reported for a string that is not inside any map entry. */
  private static final String ROOT_KEY = "value";

  /** Expanded by DBeaver's stream consumer itself, or never to be written into a file. */
  private static final Set<String> PROTECTED_NAMES =
      Stream.of(
              StreamTransferConsumer.VARIABLE_DATASOURCE,
              StreamTransferConsumer.VARIABLE_CATALOG,
              StreamTransferConsumer.VARIABLE_SCHEMA,
              StreamTransferConsumer.VARIABLE_TABLE,
              StreamTransferConsumer.VARIABLE_TIMESTAMP,
              StreamTransferConsumer.VARIABLE_INDEX,
              StreamTransferConsumer.VARIABLE_DATE,
              StreamTransferConsumer.VARIABLE_PROJECT,
              StreamTransferConsumer.VARIABLE_CONN_TYPE,
              StreamTransferConsumer.VARIABLE_SCRIPT_FILE,
              StreamTransferConsumer.VARIABLE_YEAR,
              StreamTransferConsumer.VARIABLE_MONTH,
              StreamTransferConsumer.VARIABLE_DAY,
              StreamTransferConsumer.VARIABLE_HOUR,
              StreamTransferConsumer.VARIABLE_MINUTE,
              DBPConnectionConfiguration.VARIABLE_PASSWORD)
          .map(name -> name.toLowerCase(Locale.ROOT))
          .collect(Collectors.toUnmodifiableSet());

  private final List<IVariableResolver> chain;

  VariableLookup(
      @NotNull SQLScriptContext scriptContext, @NotNull SQLDialect dialect, @NotNull Clock clock) {
    this.chain = List.of(scriptVariables(scriptContext, dialect), new BuiltInVariables(clock));
  }

  /** The first value along the chain, or {@code null} if no resolver knows the name. */
  @Nullable
  @Override
  public String get(@NotNull String name) {
    return chain.stream()
        .map(resolver -> resolver.get(name))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  /** Copy of the settings with every known {@code ${name}} replaced in all string values. */
  @NotNull
  Map<String, Object> replaceIn(@NotNull Map<String, Object> settings) {
    return replaceIn(settings, this);
  }

  /** Copy of the settings with {@code ${name}} replaced from the given resolver. */
  @NotNull
  Map<String, Object> replaceIn(
      @NotNull Map<String, Object> settings, @NotNull IVariableResolver resolver) {
    final IVariableResolver guarded = name -> isProtected(name) ? null : resolver.get(name);
    return transformMap(settings, text -> GeneralUtils.replaceVariables(text, guarded));
  }

  /**
   * {@code ${name}}s of the settings that are left without a value: name → JSON key. A variable
   * with a default ({@code ${name:default}}) always has one — DBeaver uses the default.
   */
  @NotNull
  Map<String, String> unknownNamesIn(@NotNull Map<String, Object> settings) {
    final Map<String, String> unknown = new LinkedHashMap<>();
    strings(ROOT_KEY, settings)
        .forEach(
            entry ->
                namesWithoutDefault(entry.getValue())
                    .filter(name -> !isProtected(name) && get(name) == null)
                    .forEach(name -> unknown.putIfAbsent(name, entry.getKey())));
    return unknown;
  }

  /** Script, connection and system variables, with names normalised the way {@code @set} does. */
  @NotNull
  private IVariableResolver scriptVariables(
      @NotNull SQLScriptContext scriptContext, @NotNull SQLDialect dialect) {
    return name -> {
      final String variableName = SQLCommandSet.prepareVarName(dialect, name);
      return scriptContext.hasVariable(variableName)
          ? CommonUtils.toString(scriptContext.getVariable(variableName), null)
          : null;
    };
  }

  private boolean isProtected(@NotNull String name) {
    return PROTECTED_NAMES.contains(name.trim().toLowerCase(Locale.ROOT));
  }

  @NotNull
  private Stream<String> namesWithoutDefault(@NotNull String text) {
    return VARIABLE
        .matcher(text)
        .results()
        .filter(match -> match.group(2) == null)
        .map(match -> match.group(1));
  }

  @NotNull
  private Map<String, Object> transformMap(
      @NotNull Map<?, ?> map, @NotNull UnaryOperator<String> operation) {
    final Map<String, Object> result = new LinkedHashMap<>();
    map.forEach((key, value) -> result.put(String.valueOf(key), transform(value, operation)));
    return result;
  }

  @Nullable
  private Object transform(@Nullable Object value, @NotNull UnaryOperator<String> operation) {
    if (value instanceof String text) {
      return operation.apply(text);
    }
    if (value instanceof Map<?, ?> map) {
      return transformMap(map, operation);
    }
    if (value instanceof Collection<?> list) {
      return list.stream().map(item -> transform(item, operation)).toList();
    }
    return value;
  }

  /** All string values with the key of their nearest map entry. */
  @NotNull
  private Stream<Map.Entry<String, String>> strings(@NotNull String key, @Nullable Object value) {
    if (value instanceof String text) {
      return Stream.of(Map.entry(key, text));
    }
    if (value instanceof Map<?, ?> map) {
      return map.entrySet().stream()
          .flatMap(entry -> strings(String.valueOf(entry.getKey()), entry.getValue()));
    }
    if (value instanceof Collection<?> list) {
      return list.stream().flatMap(item -> strings(key, item));
    }
    return Stream.empty();
  }
}
