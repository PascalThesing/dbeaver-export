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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.core.runtime.IStatus;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPMessageType;
import org.jkiss.dbeaver.model.data.DBDDataFilter;
import org.jkiss.dbeaver.model.data.DBDDataReceiver;
import org.jkiss.dbeaver.model.exec.DBCExecutionSource;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatistics;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.runtime.DBeaverNotifications;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * UI-level notification tests: a capturing handler stands in for DBeaver's notification popups, and
 * the real data-transfer machinery runs end-to-end. These pin the user-facing contract: a
 * notification is ALWAYS shown after an export finishes — "Export finished" only on success,
 * "Export failed" on a failed transfer (e.g. unwritable output folder), never a false success.
 */
public class ExportNowNotificationTest {

  private record Captured(String id, String title) {}

  private final List<Captured> notifications = new CopyOnWriteArrayList<>();

  @BeforeEach
  public void captureNotifications() {
    DBeaverNotifications.setHandler(
        new DBeaverNotifications.NotificationHandler() {
          @Override
          public void sendNotification(
              DBPDataSource dataSource,
              String id,
              String text,
              DBPMessageType messageType,
              Runnable feedback) {
            notifications.add(new Captured(id, text));
          }

          @Override
          public void sendNotification(
              String id, String title, String text, DBPMessageType messageType, Runnable feedback) {
            notifications.add(new Captured(id, title));
          }
        });
  }

  @AfterEach
  public void muteNotifications() {
    // DBeaverNotifications has no getter, so the original handler cannot be
    // restored - install a no-op, muting notifications for the rest of the
    // runtime (a deliberate, runtime-wide side effect)
    DBeaverNotifications.setHandler(
        new DBeaverNotifications.NotificationHandler() {
          @Override
          public void sendNotification(
              DBPDataSource dataSource,
              String id,
              String text,
              DBPMessageType messageType,
              Runnable feedback) {}

          @Override
          public void sendNotification(
              String id,
              String title,
              String text,
              DBPMessageType messageType,
              Runnable feedback) {}
        });
  }

  @Test
  public void failedExportNotifiesFailureNotSuccess() throws Exception {
    // unwritable regardless of uid (works under act/containers running as root)
    Path unwritable = Files.createTempFile("exportnow-blocker", "");
    HeadlessExportJob job =
        new HeadlessExportJob(
            new EmptyContainer(),
            new ExportConfig(
                Map.of(
                    "type",
                    "csv",
                    "consumer",
                    Map.of(
                        "outputFolder",
                        unwritable.resolve("sub").toString(),
                        "outputFilePattern",
                        "x"))));
    IStatus status = job.run(new VoidProgressMonitor());
    Files.deleteIfExists(unwritable);

    assertEquals(IStatus.ERROR, status.getSeverity(), "failed transfer must yield an error status");
    assertTrue(
        notifications.stream().anyMatch(n -> n.id().equals("exportnow.error")),
        "'Export failed' notification must be shown, got: " + notifications);
    assertFalse(
        notifications.stream().anyMatch(n -> n.id().equals("exportnow.ok")),
        "a failed export must never show 'Export finished', got: " + notifications);
  }

  @Test
  public void everyExportEmitsExactlyOneNotification() throws Exception {
    // A full success needs a live DB connection (covered by the live GUI
    // verification); headless, the invariant is: one export = exactly one
    // user-visible notification — never silence, never a false success.
    Path outDir = Files.createTempDirectory("exportnow-test");
    try {
      HeadlessExportJob job =
          new HeadlessExportJob(
              new EmptyContainer(),
              new ExportConfig(
                  Map.of(
                      "type",
                      "csv",
                      "consumer",
                      Map.of("outputFolder", outDir.toString(), "outputFilePattern", "empty"))));
      IStatus status = job.run(new VoidProgressMonitor());

      // The null-datasource container cannot fully succeed in the real transfer;
      // the invariant is: one export = exactly one notification, never a false success
      long total = notifications.stream().filter(n -> n.id().startsWith("exportnow.")).count();
      assertEquals(1, total, "exactly one notification per export, got: " + notifications);
      if (!status.isOK()) {
        assertFalse(
            notifications.stream().anyMatch(n -> n.id().equals("exportnow.ok")),
            "a non-OK export must never show 'Export finished', got: " + notifications);
      }
    } finally {
      try (var files = Files.list(outDir)) {
        for (Path p : files.toList()) {
          Files.deleteIfExists(p);
        }
      }
      Files.deleteIfExists(outDir);
    }
  }

  @Test
  public void cancelledExportNotifiesCancellationNotSuccess() throws Exception {
    Path outDir = Files.createTempDirectory("exportnow-cancel");
    try {
      HeadlessExportJob job =
          new HeadlessExportJob(
              new EmptyContainer(),
              new ExportConfig(
                  Map.of(
                      "type",
                      "csv",
                      "consumer",
                      Map.of("outputFolder", outDir.toString(), "outputFilePattern", "c"))));
      VoidProgressMonitor cancelled =
          new VoidProgressMonitor() {
            @Override
            public boolean isCanceled() {
              return true;
            }
          };
      IStatus status = job.run(cancelled);

      assertEquals(
          IStatus.CANCEL, status.getSeverity(), "cancelled export must yield CANCEL status");
      assertTrue(
          notifications.stream().anyMatch(n -> n.id().equals("exportnow.cancelled")),
          "'Export cancelled' notification must be shown, got: " + notifications);
      assertFalse(
          notifications.stream().anyMatch(n -> n.id().equals("exportnow.ok")),
          "a cancelled export must never show 'Export finished', got: " + notifications);
    } finally {
      try (var files = Files.list(outDir)) {
        for (Path p : files.toList()) {
          Files.deleteIfExists(p);
        }
      }
      Files.deleteIfExists(outDir);
    }
  }

  /** Container that yields zero rows through the real transfer pipeline. */
  private static final class EmptyContainer implements DBSDataContainer {
    @Override
    public String getName() {
      return "empty-container";
    }

    @Override
    public String getDescription() {
      return null;
    }

    @Override
    public DBSObject getParentObject() {
      return null;
    }

    @Override
    public DBPDataSource getDataSource() {
      return null;
    }

    @Override
    public boolean isPersisted() {
      return false;
    }

    @Override
    public String[] getSupportedFeatures() {
      return new String[0];
    }

    @NotNull
    @Override
    public DBCStatistics readData(
        @Nullable DBCExecutionSource source,
        @NotNull DBCSession session,
        @NotNull DBDDataReceiver dataReceiver,
        @Nullable DBDDataFilter dataFilter,
        long firstRow,
        long maxRows,
        long flags,
        int fetchSize) {
      return new DBCStatistics();
    }

    @Override
    public long countData(
        @NotNull DBCExecutionSource source,
        @NotNull DBCSession session,
        @Nullable DBDDataFilter dataFilter,
        long flags) {
      return 0;
    }
  }
}
