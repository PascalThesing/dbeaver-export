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

import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.data.DBDDataFormatterProfile;
import org.jkiss.utils.CommonUtils;

/**
 * The {@code "excel"} CSV preset: a CSV that Excel opens directly. Like Excel itself, the column
 * delimiter follows the decimal separator of the number format — {@code ;} for a decimal comma,
 * {@code ,} for a decimal point — so formatted numbers never collide with the delimiter.
 */
final class ExcelCsvPreset {

  static final String NAME = "excel";

  private static final String DELIMITER = "delimiter";
  private static final String FORMAT_NUMBERS = "formatNumbers";

  /** CSV exporter defaults for numbers written with the given data-format profile. */
  @NotNull
  Map<String, Object> processorDefaults(@NotNull DBDDataFormatterProfile profile) {
    final String delimiter = decimalSeparator(profile) == ',' ? ";" : ",";
    return Map.of(DELIMITER, delimiter, FORMAT_NUMBERS, true);
  }

  /** UTF-8 with BOM, so Excel shows non-ASCII characters correctly. */
  @NotNull
  Map<String, Object> consumerDefaults() {
    return Map.of("outputEncoding", "UTF-8", "outputEncodingBOM", true);
  }

  /** Warns when formatted numbers contain the column delimiter: Excel would split them. */
  @NotNull
  Optional<String> splitColumnsWarning(
      @NotNull Map<String, Object> processorSettings, @NotNull DBDDataFormatterProfile profile) {
    final String delimiter = CommonUtils.toString(processorSettings.get(DELIMITER));
    if (!CommonUtils.toBoolean(processorSettings.get(FORMAT_NUMBERS))
        || !delimiter.equals(String.valueOf(decimalSeparator(profile)))) {
      return Optional.empty();
    }
    return Optional.of(
        "delimiter '"
            + delimiter
            + "' equals the decimal separator of data-format profile '"
            + profile.getProfileName()
            + "' ("
            + locale(profile)
            + "); Excel will split numbers into columns");
  }

  private char decimalSeparator(@NotNull DBDDataFormatterProfile profile) {
    return DecimalFormatSymbols.getInstance(locale(profile)).getDecimalSeparator();
  }

  @NotNull
  private Locale locale(@NotNull DBDDataFormatterProfile profile) {
    return profile.getLocale() == null ? Locale.getDefault() : profile.getLocale();
  }
}
