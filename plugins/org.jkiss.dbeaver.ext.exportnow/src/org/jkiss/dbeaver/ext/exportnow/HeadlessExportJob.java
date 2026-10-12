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

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.exportnow.TransferSettingsFactory.ExportSettings;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.tools.transfer.DataTransferSettings;
import org.jkiss.dbeaver.tools.transfer.task.DTTaskHandlerTransfer;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;

/**
 * Runs one headless data transfer for an {@code @exportnow} block in the background — via {@link
 * DTTaskHandlerTransfer} instead of DBeaver's export wizard — and tells the user how it ended.
 */
class HeadlessExportJob extends AbstractJob {

  private static final Log log = Log.getLog(HeadlessExportJob.class);
  private static final int MAX_NAME_LENGTH = 60;

  private final DBSDataContainer container;
  private final ExportConfig config;
  private final TransferSettingsFactory settingsFactory = new TransferSettingsFactory();
  private final ExportNotifications notifications = new ExportNotifications();

  HeadlessExportJob(@NotNull DBSDataContainer container, @NotNull ExportConfig config) {
    super("Export data: " + CommonUtils.truncateString(container.getName(), MAX_NAME_LENGTH));
    this.container = container;
    this.config = config;
    setUser(true);
  }

  @Override
  protected IStatus run(DBRProgressMonitor monitor) {
    try {
      final ExportSettings settings = settingsFactory.create(container, config);
      settings.warnings().forEach(warning -> log.warn(describe() + ": " + warning));
      final Optional<Throwable> transferError = transfer(monitor, settings.transferSettings());
      if (monitor.isCanceled()) {
        notifications.cancelled(describe());
        return Status.CANCEL_STATUS;
      }
      if (transferError.isPresent()) {
        return failed(transferError.get());
      }
      notifications.finished(describe() + warningLines(settings.warnings()));
      return Status.OK_STATUS;
    } catch (Throwable e) {
      return failed(e);
    }
  }

  @NotNull
  private IStatus failed(@NotNull Throwable error) {
    log.error("Export failed: " + describe(), error);
    notifications.failed(describe(), error);
    return GeneralUtils.makeExceptionStatus("Export failed: " + describe(), error);
  }

  @NotNull
  private String warningLines(@NotNull List<String> warnings) {
    return warnings.stream().map("\nWarning: "::concat).collect(Collectors.joining());
  }

  /** Runs the transfer; returns the first error its nested jobs reported. */
  @NotNull
  private Optional<Throwable> transfer(
      @NotNull DBRProgressMonitor monitor, @NotNull DataTransferSettings settings)
      throws Throwable {
    settings.loadNodeSettings(monitor);
    // Setup failures are collected in the state, not thrown - without this check a
    // misconfigured export would silently run on defaults
    if (!settings.getState().getLoadErrors().isEmpty()) {
      throw settings.getState().getLoadErrors().get(0);
    }
    final TransferErrorCollector errors = new TransferErrorCollector();
    new DTTaskHandlerTransfer()
        .executeWithSettings(
            (fork, cancelable, runnable) -> runnable.run(monitor),
            null,
            Locale.getDefault(),
            log,
            null,
            errors,
            settings);
    return errors.firstError();
  }

  /** Query text, prefixed with the datasource name when known. */
  @NotNull
  private String describe() {
    final DBPDataSource dataSource = container.getDataSource();
    final String name = CommonUtils.truncateString(container.getName(), MAX_NAME_LENGTH);
    return dataSource == null ? name : dataSource.getContainer().getName() + ": " + name;
  }
}
