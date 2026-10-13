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

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.DBPMessageType;
import org.jkiss.dbeaver.runtime.DBeaverNotifications;
import org.jkiss.dbeaver.utils.GeneralUtils;

/** The plugin's user-facing notifications. The ids are registered in {@code plugin.xml}. */
final class ExportNotifications {

  static final String FINISHED = "exportnow.ok";
  static final String CANCELLED = "exportnow.cancelled";
  static final String FAILED = "exportnow.error";

  void finished(@NotNull String text) {
    show(FINISHED, "Export finished", text, DBPMessageType.INFORMATION);
  }

  void cancelled(@NotNull String description) {
    show(
        CANCELLED,
        "Export cancelled",
        description + " — a partially written output file may remain",
        DBPMessageType.INFORMATION);
  }

  void runCancelled() {
    show(
        CANCELLED,
        "Export cancelled",
        "Parameters dialog cancelled — no export started",
        DBPMessageType.INFORMATION);
  }

  void failed(@NotNull String description, @NotNull Throwable error) {
    show(
        FAILED,
        "Export failed",
        description + "\n" + GeneralUtils.getExceptionMessage(error),
        DBPMessageType.ERROR);
  }

  void blockSkipped(int offset, @NotNull String reason) {
    show(
        FAILED,
        "Export block skipped",
        "Malformed @exportnow block at offset " + offset + ": " + reason,
        DBPMessageType.WARNING);
  }

  private void show(String id, String title, String text, DBPMessageType type) {
    DBeaverNotifications.showNotification(id, title, text, type, null);
  }
}
