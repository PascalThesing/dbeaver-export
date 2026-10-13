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

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.task.DBTTask;
import org.jkiss.dbeaver.model.task.DBTTaskExecutionListener;

/**
 * Keeps the first error of a data transfer. The transfer runs in nested jobs whose failures don't
 * surface as exceptions — DBeaver reports them only through these callbacks.
 */
final class TransferErrorCollector implements DBTTaskExecutionListener {

  private final AtomicReference<Throwable> firstError = new AtomicReference<>();

  @Override
  public void taskStarted(DBTTask task) {}

  @Override
  public void taskFinished(DBTTask task, Object result, Throwable error, Object settings) {
    record(error);
  }

  @Override
  public void subTaskFinished(DBTTask task, Throwable error, Object settings) {
    record(error);
  }

  @NotNull
  Optional<Throwable> firstError() {
    return Optional.ofNullable(firstError.get());
  }

  private void record(@Nullable Throwable error) {
    if (error != null) {
      firstError.compareAndSet(null, error);
    }
  }
}
