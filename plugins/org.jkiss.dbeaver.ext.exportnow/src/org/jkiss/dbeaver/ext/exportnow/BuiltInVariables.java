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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.runtime.IVariableResolver;

/**
 * The built-in variables {@code ${current_date}}, {@code ${current_time}} and {@code
 * ${current_timestamp}} in ISO format. The time is taken once, so all exports of a run see the same
 * value.
 */
final class BuiltInVariables implements IVariableResolver {

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final Map<String, String> values;

  BuiltInVariables(@NotNull Clock clock) {
    final LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    values =
        Map.of(
            "current_date", now.toLocalDate().toString(),
            "current_time", now.format(TIME),
            "current_timestamp", now.format(TIMESTAMP));
  }

  @Nullable
  @Override
  public String get(@NotNull String name) {
    return values.get(name.toLowerCase(Locale.ROOT));
  }
}
