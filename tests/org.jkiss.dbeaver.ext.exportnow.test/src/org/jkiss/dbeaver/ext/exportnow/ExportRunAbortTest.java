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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.io.StringReader;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.exportnow.ExportBlockScanner.Problem;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPMessageType;
import org.jkiss.dbeaver.model.data.json.JSONUtils;
import org.jkiss.dbeaver.runtime.DBeaverNotifications;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A problem found before exporting aborts the whole run: every bad block is named with its line,
 * one persistent error reaches the user, and no export starts.
 */
public class ExportRunAbortTest {

  private record Shown(String id, String title, String text, Runnable feedback) {}

  private final List<Shown> shown = new CopyOnWriteArrayList<>();
  private final SQLCommandExportNow handler = new SQLCommandExportNow();

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
            shown.add(new Shown(id, null, text, feedback));
          }

          @Override
          public void sendNotification(
              String id, String title, String text, DBPMessageType messageType, Runnable feedback) {
            shown.add(new Shown(id, title, text, feedback));
          }
        });
  }

  @AfterEach
  public void muteNotifications() {
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

  private static ExportRequest request(String json, int line) throws DBException {
    return new ExportRequest(
        new ExportConfig(JSONUtils.parseMap(new Gson(), new StringReader(json))),
        "select " + line,
        line);
  }

  @Test
  public void validateNamesEveryInvalidBlockWithItsLine() throws Exception {
    List<Problem> problems =
        handler.validate(
            List.of(
                request("{\"type\": \"csv\"}", 1),
                request("{\"type\": \"xls\"}", 3),
                request("{\"type\": \"csv\", \"formatterProfile\": \"no-such-profile\"}", 5),
                request("{\"type\": \"csv\", \"preset\": \"no-such-preset\"}", 7),
                request("{\"type\": \"xlsx\"}", 9)));
    assertEquals(List.of(3, 5, 7), problems.stream().map(Problem::line).toList());
    assertTrue(problems.get(0).reason().contains("'xls'"), problems.toString());
    assertTrue(problems.get(1).reason().contains("no-such-profile"), problems.toString());
    assertTrue(problems.get(2).reason().contains("no-such-preset"), problems.toString());
  }

  @Test
  public void abortNamesEveryProblemAndNotifiesOnce() {
    DBException error = handler.abort(List.of(new Problem(3, "bad A"), new Problem(9, "bad B")));
    assertTrue(error.getMessage().contains("No export started"), error.getMessage());
    assertTrue(error.getMessage().contains("line 3: bad A"), error.getMessage());
    assertTrue(error.getMessage().contains("line 9: bad B"), error.getMessage());
    assertEquals(1, shown.size(), "exactly one notification per aborted run: " + shown);
    assertEquals("exportnow.error", shown.get(0).id());
    assertEquals("Export run aborted", shown.get(0).title());
    assertTrue(shown.get(0).text().contains("line 9: bad B"), shown.get(0).text());
    assertNull(shown.get(0).feedback(), "no click action without an xls problem");
  }

  @Test
  public void xlsProblemMakesTheNotificationOpenInstallNewSoftware() throws Exception {
    handler.abort(handler.validate(List.of(request("{\"type\": \"xls\"}", 2))));
    assertEquals(1, shown.size());
    assertNotNull(shown.get(0).feedback(), "clicking must open Install New Software");
  }

  @Test
  public void problemWithoutKnownLineHasNoLinePrefix() {
    DBException error = handler.abort(List.of(new Problem(0, "no query found")));
    assertTrue(error.getMessage().endsWith("\nno query found"), error.getMessage());
  }
}
