// Copyright 2022 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.cloud.spanner.pgadapter.statements;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.cloud.spanner.Dialect;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.connection.AbstractStatementParser;
import com.google.cloud.spanner.connection.AbstractStatementParser.ParsedStatement;
import com.google.cloud.spanner.connection.Connection;
import com.google.cloud.spanner.connection.StatementResult;
import com.google.cloud.spanner.connection.StatementResult.ResultType;
import com.google.cloud.spanner.pgadapter.metadata.OptionsMetadata;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

@RunWith(JUnit4.class)
public class IntermediateStatementTest {
  private static final AbstractStatementParser PARSER =
      AbstractStatementParser.getInstance(Dialect.POSTGRESQL);

  private static ParsedStatement parse(String sql) {
    return PARSER.parse(Statement.of(sql));
  }

  @Mock private Connection connection;

  @Test
  public void testUpdateResultCount_ResultSet() {
    IntermediateStatement statement =
        new IntermediateStatement(
            mock(OptionsMetadata.class), parse("select foo from bar"), connection);
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.next()).thenReturn(true, false);
    StatementResult result = mock(StatementResult.class);
    when(result.getResultType()).thenReturn(ResultType.RESULT_SET);
    when(result.getResultSet()).thenReturn(resultSet);
    when(result.getUpdateCount()).thenThrow(new IllegalStateException());

    statement.updateResultCount(result);

    assertTrue(statement.hasMoreData);
    assertNull(statement.updateCount);
    assertSame(resultSet, statement.statementResult);
  }

  @Test
  public void testUpdateResultCount_UpdateCount() {
    IntermediateStatement statement =
        new IntermediateStatement(
            mock(OptionsMetadata.class), parse("update bar set foo=1"), connection);
    StatementResult result = mock(StatementResult.class);
    when(result.getResultType()).thenReturn(ResultType.UPDATE_COUNT);
    when(result.getResultSet()).thenThrow(new IllegalStateException());
    when(result.getUpdateCount()).thenReturn(100L);

    statement.updateResultCount(result);

    assertFalse(statement.hasMoreData);
    assertEquals(100L, statement.updateCount.longValue());
    assertNull(statement.statementResult);
  }

  @Test
  public void testUpdateResultCount_NoResult() {
    IntermediateStatement statement =
        new IntermediateStatement(
            mock(OptionsMetadata.class),
            parse("create table bar (foo bigint primary key)"),
            connection);
    StatementResult result = mock(StatementResult.class);
    when(result.getResultType()).thenReturn(ResultType.NO_RESULT);
    when(result.getResultSet()).thenThrow(new IllegalStateException());
    when(result.getUpdateCount()).thenThrow(new IllegalStateException());

    statement.updateResultCount(result);

    assertFalse(statement.hasMoreData);
    assertNull(statement.updateCount);
    assertNull(statement.statementResult);
  }
}
