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

import java.util.Locale;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.sql.SQLQueryParameter;

/**
 * Keys that identify a parameter across the queries of a run: named parameters ({@code :name},
 * {@code ${name}}) by their lower-case name, anonymous {@code ?} by their order ({@code ?1}, {@code
 * ?2}, …). Use one instance per pass over the queries; passes in the same order give the same keys,
 * which is how the dialog's answers find their way back into each query.
 */
final class ParameterKeys {

  private int anonymousCount;

  /** Key of the next parameter in text order. */
  @NotNull
  String keyOf(@NotNull SQLQueryParameter parameter) {
    return parameter.isNamed() ? keyOf(bareName(parameter)) : "?" + ++anonymousCount;
  }

  /** Key of a variable name. */
  @NotNull
  String keyOf(@NotNull String variableName) {
    return variableName.toLowerCase(Locale.ROOT);
  }

  /** Name of a {@code :name} or {@code ${name}} parameter as variables are named. */
  @NotNull
  String bareName(@NotNull SQLQueryParameter parameter) {
    final String name =
        parameter.getVarName() != null ? parameter.getVarName() : parameter.getName();
    return SQLQueryParameter.stripVariablePattern(name).replaceFirst("^:", "");
  }
}
