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

import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.data.json.JSONUtils;
import org.jkiss.dbeaver.tools.transfer.DTConstants;
import org.jkiss.utils.CommonUtils;

/**
 * The JSON settings of one {@code @exportnow} block — the single place that knows the keys the
 * plugin itself reads. All other keys pass through to DBeaver's data transfer unchanged.
 */
record ExportConfig(@NotNull Map<String, Object> json) {

  private static final String TYPE = "type";
  private static final String PRESET = "preset";
  private static final String FORMATTER_PROFILE = "formatterProfile";
  private static final String MAX_JOB_COUNT = "maxJobCount";

  /** The export format, e.g. {@code csv}; mandatory in a valid command. */
  @Nullable
  String type() {
    return JSONUtils.getString(json, TYPE);
  }

  @NotNull
  Optional<String> preset() {
    return nonEmpty(JSONUtils.getString(json, PRESET));
  }

  /** Top-level shortcut for {@code consumer.formatterProfile}. */
  @NotNull
  Optional<String> formatterProfile() {
    return nonEmpty(JSONUtils.getString(json, FORMATTER_PROFILE));
  }

  /** How many exports of a run may run at the same time; 0 = unlimited. */
  int maxJobCount() {
    return CommonUtils.toInt(json.get(MAX_JOB_COUNT), 0);
  }

  @NotNull
  Map<String, Object> producer() {
    return JSONUtils.getObject(json, DTConstants.PROP_PRODUCER_TYPE);
  }

  @NotNull
  Map<String, Object> consumer() {
    return JSONUtils.getObject(json, DTConstants.PROP_CONSUMER_TYPE);
  }

  @NotNull
  Map<String, Object> processor() {
    return JSONUtils.getObject(json, DTConstants.PROP_PROCESSOR_TYPE);
  }

  @NotNull
  ExportConfig withJson(@NotNull UnaryOperator<Map<String, Object>> change) {
    return new ExportConfig(change.apply(json));
  }

  @NotNull
  private Optional<String> nonEmpty(@Nullable String value) {
    return Optional.ofNullable(value).filter(text -> !text.isEmpty());
  }
}
