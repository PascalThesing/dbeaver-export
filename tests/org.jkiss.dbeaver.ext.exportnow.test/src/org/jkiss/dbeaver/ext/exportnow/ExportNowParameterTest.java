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

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.data.json.JSONUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for @exportnow parameter parsing: leading JSON object, optional
 * inline SQL remainder, and the trailing semicolon DBeaver's script parser
 * includes in the command parameter.
 */
public class ExportNowParameterTest {

    private final ExportCommandParser parser = new ExportCommandParser();

    @Test
    public void classicFormWithoutSql() throws DBException {
        var parsed = parser.parseParameters(
            "{\"type\": \"csv\", \"consumer\": {\"outputFolder\": \"/tmp/out\", \"outputFilePattern\": \"t1\"}};");
        assertEquals("csv", JSONUtils.getString(parsed.params(), "type"));
        assertEquals("/tmp/out", JSONUtils.getString(JSONUtils.getObject(parsed.params(), "consumer"), "outputFolder"));
        assertNull(parsed.sql(), "no inline SQL expected");
    }

    @Test
    public void inlineSqlAfterJson() throws DBException {
        var parsed = parser.parseParameters(
            "{\"type\": \"csv\", \"consumer\": {\"outputFilePattern\": \"c\"}} select * from customers;");
        assertEquals("csv", JSONUtils.getString(parsed.params(), "type"));
        assertEquals("select * from customers", parsed.sql());
    }

    @Test
    public void inlineSqlWithBracesAndSemicolonsInsideJsonStrings() throws DBException {
        var parsed = parser.parseParameters(
            "{\"type\": \"csv\", \"consumer\": {\"outputFilePattern\": \"we};rd\"}} select ';' as x;");
        assertEquals("we};rd",
            JSONUtils.getString(JSONUtils.getObject(parsed.params(), "consumer"), "outputFilePattern"));
        assertEquals("select ';' as x", parsed.sql());
    }

    @Test
    public void multilineInlineSql() throws DBException {
        var parsed = parser.parseParameters(
            "{\"type\": \"json\"}\nselect *\nfrom orders\n;");
        assertEquals("json", JSONUtils.getString(parsed.params(), "type"));
        assertEquals("select *\nfrom orders", parsed.sql());
    }

    @Test
    public void whitespaceAndNewlinesAfterJsonMeanNoInlineSql() throws DBException {
        var parsed = parser.parseParameters(
            "{\"type\": \"csv\"}   \n\n  \n ;");
        assertEquals("csv", JSONUtils.getString(parsed.params(), "type"));
        assertNull(parsed.sql(), "whitespace/empty lines after the JSON must not count as inline SQL");
    }

    @Test
    public void escapedQuoteBeforeClosingBrace() throws DBException {
        var parsed = parser.parseParameters(
            "{\"type\": \"csv\", \"a\": \"x\\\"\"} select 1;");
        assertEquals("x\"", JSONUtils.getString(parsed.params(), "a"));
        assertEquals("select 1", parsed.sql());
    }

    @Test
    public void nestedObjectsWithInlineSql() throws DBException {
        var parsed = parser.parseParameters(
            "{\"type\": \"csv\", \"consumer\": {\"a\": {\"b\": 1}}} select 1;");
        assertEquals("select 1", parsed.sql());
    }

    @Test
    public void rejectsUnterminatedJson() {
        assertThrows(DBException.class, () ->
            parser.parseParameters("{\"type\": \"csv\""));
        assertThrows(DBException.class, () ->
            parser.parseParameters("{\"type\": \"csv\", \"a\": \"\\"));
    }

    @Test
    public void rejectsMissingType() {
        assertThrows(DBException.class, () ->
            parser.parseParameters("{\"consumer\": {\"outputFolder\": \"/tmp\"}}"));
    }

    @Test
    public void rejectsMalformedJson() {
        assertThrows(DBException.class, () ->
            parser.parseParameters("{\"type\": \"csv\" oops"));
    }

    @Test
    public void rejectsMissingJsonObject() {
        assertThrows(DBException.class, () ->
            parser.parseParameters("select * from customers;"));
        assertThrows(DBException.class, () -> parser.parseParameters(null));
        assertThrows(DBException.class, () -> parser.parseParameters("   "));
    }
}
