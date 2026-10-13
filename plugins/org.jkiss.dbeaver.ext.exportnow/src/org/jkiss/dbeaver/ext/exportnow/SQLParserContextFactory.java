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
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocumentPartitioner;
import org.eclipse.jface.text.rules.FastPartitioner;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.impl.sql.BasicSQLDialect;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.jkiss.dbeaver.model.sql.SQLPartitionScanner;
import org.jkiss.dbeaver.model.sql.SQLQueryParameter;
import org.jkiss.dbeaver.model.sql.SQLSyntaxManager;
import org.jkiss.dbeaver.model.sql.parser.SQLParserContext;
import org.jkiss.dbeaver.model.sql.parser.SQLParserPartitions;
import org.jkiss.dbeaver.model.sql.parser.SQLRuleManager;
import org.jkiss.dbeaver.model.sql.parser.SQLScriptParser;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

/**
 * Builds a {@link SQLParserContext} over a plain text with the data source's dialect rules and
 * preferences (string escapes, comments, delimiters, parameter/variable syntax), as DBeaver's SQL
 * editor would.
 */
final class SQLParserContextFactory {

  @NotNull
  SQLDialect dialect(@Nullable DBPDataSource dataSource) {
    return dataSource == null ? BasicSQLDialect.INSTANCE : dataSource.getSQLDialect();
  }

  @NotNull
  SQLParserContext create(@Nullable DBPDataSource dataSource, @NotNull String text) {
    final SQLDialect dialect = dialect(dataSource);
    final DBPPreferenceStore preferences = preferences(dataSource);
    final SQLSyntaxManager syntaxManager = new SQLSyntaxManager();
    syntaxManager.init(dialect, preferences);
    final SQLRuleManager ruleManager = new SQLRuleManager(syntaxManager);
    ruleManager.loadRules(dataSource, false);
    final Document document =
        partitionedDocument(text, new SQLPartitionScanner(dataSource, dialect, ruleManager));
    final SQLParserContext context =
        new SQLParserContext(dataSource, syntaxManager, ruleManager, document);
    context.setPreferenceStore(preferences);
    return context;
  }

  /**
   * {@code :name}, {@code ?} and {@code ${name}} in the text, as DBeaver's SQL editor finds them.
   */
  @NotNull
  List<SQLQueryParameter> parameters(@Nullable DBPDataSource dataSource, @NotNull String text) {
    return CommonUtils.safeList(
        SQLScriptParser.parseParametersAndVariables(create(dataSource, text), 0, text.length()));
  }

  @NotNull
  private DBPPreferenceStore preferences(@Nullable DBPDataSource dataSource) {
    if (dataSource != null && dataSource.getContainer().getPreferenceStore() != null) {
      return dataSource.getContainer().getPreferenceStore();
    }
    return DBWorkbench.getPlatform().getPreferenceStore();
  }

  @NotNull
  private Document partitionedDocument(@NotNull String text, @NotNull SQLPartitionScanner scanner) {
    final Document document = new Document(text);
    final IDocumentPartitioner partitioner =
        new FastPartitioner(scanner, SQLParserPartitions.SQL_CONTENT_TYPES);
    partitioner.connect(document);
    document.setDocumentPartitioner(SQLParserPartitions.SQL_PARTITIONING, partitioner);
    return document;
  }
}
