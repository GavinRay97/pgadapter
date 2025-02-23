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

package com.google.cloud.spanner.pgadapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.cloud.ByteArray;
import com.google.cloud.Date;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.MockSpannerServiceImpl.StatementResult;
import com.google.cloud.spanner.Statement;
import com.google.protobuf.Value;
import com.google.spanner.admin.database.v1.UpdateDatabaseDdlRequest;
import com.google.spanner.v1.ExecuteBatchDmlRequest;
import com.google.spanner.v1.ExecuteSqlRequest;
import com.google.spanner.v1.ExecuteSqlRequest.QueryMode;
import com.google.spanner.v1.Type;
import com.google.spanner.v1.TypeAnnotationCode;
import com.google.spanner.v1.TypeCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.postgresql.core.Oid;

@RunWith(JUnit4.class)
public class JdbcMockServerTest extends AbstractMockServerTest {

  @BeforeClass
  public static void loadPgJdbcDriver() throws Exception {
    // Make sure the PG JDBC driver is loaded.
    Class.forName("org.postgresql.Driver");
  }

  /**
   * Creates a JDBC connection string that instructs the PG JDBC driver to use the default extended
   * mode for queries and DML statements.
   */
  private String createUrl() {
    // Force binary transfer of date parameters to prevent PostgreSQL from sending them with type
    // Oid.UNSPECIFIED. This setting is however at this moment ignored and depends on this PR:
    // https://github.com/pgjdbc/pgjdbc/pull/2476
    return String.format(
        "jdbc:postgresql://localhost:%d/?binaryTransferEnable=%d",
        pgServer.getLocalPort(), Oid.DATE);
  }

  @Test
  public void testQuery() throws SQLException {
    String sql = "SELECT 1";

    try (Connection connection = DriverManager.getConnection(createUrl())) {
      try (ResultSet resultSet = connection.createStatement().executeQuery(sql)) {
        assertTrue(resultSet.next());
        assertEquals(1L, resultSet.getLong(1));
        assertFalse(resultSet.next());
      }
    }

    // The statement is only sent once to the mock server. The DescribePortal message will trigger
    // the execution of the query, and the result from that execution will be used for the Execute
    // message.
    assertEquals(1, mockSpanner.countRequestsOfType(ExecuteSqlRequest.class));
    ExecuteSqlRequest executeRequest =
        mockSpanner.getRequestsOfType(ExecuteSqlRequest.class).get(0);
    assertEquals(QueryMode.NORMAL, executeRequest.getQueryMode());

    for (ExecuteSqlRequest request : mockSpanner.getRequestsOfType(ExecuteSqlRequest.class)) {
      assertEquals(sql, request.getSql());
      assertTrue(request.getTransaction().hasSingleUse());
      assertTrue(request.getTransaction().getSingleUse().hasReadOnly());
    }
  }

  @Test
  public void testQueryWithParameters() throws SQLException {
    String jdbcSql =
        "select col_bigint, col_bool, col_bytea, col_float8, col_numeric, col_timestamptz, col_date, col_varchar "
            + "from all_types "
            + "where col_bigint=? "
            + "and col_bool=? "
            + "and col_bytea=? "
            + "and col_float8=? "
            + "and col_numeric=? "
            + "and col_timestamptz=? "
            + "and col_date=? "
            + "and col_varchar=?";
    String pgSql =
        "select col_bigint, col_bool, col_bytea, col_float8, col_numeric, col_timestamptz, col_date, col_varchar "
            + "from all_types "
            + "where col_bigint=$1 "
            + "and col_bool=$2 "
            + "and col_bytea=$3 "
            + "and col_float8=$4 "
            + "and col_numeric=$5 "
            + "and col_timestamptz=$6 "
            + "and col_date=$7 "
            + "and col_varchar=$8";
    mockSpanner.putStatementResult(StatementResult.query(Statement.of(pgSql), ALL_TYPES_RESULTSET));
    mockSpanner.putStatementResult(
        StatementResult.query(
            Statement.newBuilder(pgSql)
                .bind("p1")
                .to(1L)
                .bind("p2")
                .to(true)
                .bind("p3")
                .to(ByteArray.copyFrom("test"))
                .bind("p4")
                .to(3.14d)
                .bind("p5")
                .to(com.google.cloud.spanner.Value.pgNumeric("6.626"))
                .bind("p6")
                .to(Timestamp.parseTimestamp("2022-02-16T13:18:02.123457000Z"))
                .bind("p7")
                .to(Date.parseDate("2022-03-29"))
                .bind("p8")
                .to("test")
                .build(),
            ALL_TYPES_RESULTSET));

    OffsetDateTime zonedDateTime =
        LocalDateTime.of(2022, 2, 16, 13, 18, 2, 123456789).atOffset(ZoneOffset.UTC);
    try (Connection connection = DriverManager.getConnection(createUrl())) {
      try (PreparedStatement preparedStatement = connection.prepareStatement(jdbcSql)) {
        int index = 0;
        preparedStatement.setLong(++index, 1L);
        preparedStatement.setBoolean(++index, true);
        preparedStatement.setBytes(++index, "test".getBytes(StandardCharsets.UTF_8));
        preparedStatement.setDouble(++index, 3.14d);
        preparedStatement.setBigDecimal(++index, new BigDecimal("6.626"));
        preparedStatement.setTimestamp(
            ++index, java.sql.Timestamp.from(Instant.from(zonedDateTime)));
        preparedStatement.setDate(++index, java.sql.Date.valueOf(LocalDate.of(2022, 3, 29)));
        preparedStatement.setString(++index, "test");
        try (ResultSet resultSet = preparedStatement.executeQuery()) {
          assertTrue(resultSet.next());
          assertEquals(1L, resultSet.getLong(1));
          assertFalse(resultSet.next());
        }
      }
    }

    List<ExecuteSqlRequest> requests = mockSpanner.getRequestsOfType(ExecuteSqlRequest.class);
    assertEquals(1, requests.size());

    ExecuteSqlRequest executeRequest = requests.get(0);
    assertEquals(QueryMode.NORMAL, executeRequest.getQueryMode());
    assertEquals(pgSql, executeRequest.getSql());

    Map<String, Value> params = executeRequest.getParams().getFieldsMap();
    Map<String, Type> types = executeRequest.getParamTypesMap();

    assertEquals(TypeCode.INT64, types.get("p1").getCode());
    assertEquals("1", params.get("p1").getStringValue());
    assertEquals(TypeCode.BOOL, types.get("p2").getCode());
    assertTrue(params.get("p2").getBoolValue());
    assertEquals(TypeCode.BYTES, types.get("p3").getCode());
    assertEquals(
        Base64.getEncoder().encodeToString("test".getBytes(StandardCharsets.UTF_8)),
        params.get("p3").getStringValue());
    assertEquals(TypeCode.FLOAT64, types.get("p4").getCode());
    assertEquals(3.14d, params.get("p4").getNumberValue(), 0.0d);
    assertEquals(TypeCode.NUMERIC, types.get("p5").getCode());
    assertEquals(TypeAnnotationCode.PG_NUMERIC, types.get("p5").getTypeAnnotation());
    assertEquals("6.626", params.get("p5").getStringValue());
    assertEquals(TypeCode.TIMESTAMP, types.get("p6").getCode());
    assertEquals("2022-02-16T13:18:02.123457000Z", params.get("p6").getStringValue());
    assertEquals(TypeCode.DATE, types.get("p7").getCode());
    assertEquals("2022-03-29", params.get("p7").getStringValue());
    assertEquals(TypeCode.STRING, types.get("p8").getCode());
    assertEquals("test", params.get("p8").getStringValue());
  }

  @Test
  public void testNullValues() throws SQLException {
    mockSpanner.putStatementResult(
        StatementResult.update(
            Statement.newBuilder(
                    "insert into all_types "
                        + "(col_bigint, col_bool, col_bytea, col_float8, col_int, col_numeric, col_timestamptz, col_date, col_varchar) "
                        + "values ($1, $2, $3, $4, $5, $6, $7, $8, $9)")
                .bind("p1")
                .to(2L)
                .bind("p2")
                .to((Boolean) null)
                .bind("p3")
                .to((ByteArray) null)
                .bind("p4")
                .to((Double) null)
                .bind("p5")
                .to((Long) null)
                .bind("p6")
                .to(com.google.cloud.spanner.Value.pgNumeric(null))
                .bind("p7")
                .to((com.google.cloud.spanner.Value) null)
                .bind("p8")
                .to((Date) null)
                .bind("p9")
                .to((String) null)
                .build(),
            1L));
    mockSpanner.putStatementResult(
        StatementResult.query(
            Statement.of("select * from all_types where col_bigint is null"),
            ALL_TYPES_NULLS_RESULTSET));

    try (Connection connection = DriverManager.getConnection(createUrl())) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "insert into all_types "
                  + "(col_bigint, col_bool, col_bytea, col_float8, col_int, col_numeric, col_timestamptz, col_date, col_varchar) "
                  + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
        int index = 0;
        statement.setLong(++index, 2);
        statement.setNull(++index, Types.BOOLEAN);
        statement.setNull(++index, Types.BINARY);
        statement.setNull(++index, Types.DOUBLE);
        statement.setNull(++index, Types.INTEGER);
        statement.setNull(++index, Types.NUMERIC);
        statement.setNull(++index, Types.TIMESTAMP_WITH_TIMEZONE);
        statement.setNull(++index, Types.DATE);
        statement.setNull(++index, Types.VARCHAR);

        assertEquals(1, statement.executeUpdate());
      }

      try (ResultSet resultSet =
          connection
              .createStatement()
              .executeQuery("select * from all_types where col_bigint is null")) {
        assertTrue(resultSet.next());

        int index = 0;
        // Note: JDBC returns the zero-value for primitive types if the value is NULL, and you have
        // to call wasNull() to determine whether the value was NULL or zero.
        assertEquals(0L, resultSet.getLong(++index));
        assertTrue(resultSet.wasNull());
        assertFalse(resultSet.getBoolean(++index));
        assertTrue(resultSet.wasNull());
        assertNull(resultSet.getBytes(++index));
        assertTrue(resultSet.wasNull());
        assertEquals(0d, resultSet.getDouble(++index), 0.0d);
        assertTrue(resultSet.wasNull());
        assertNull(resultSet.getBigDecimal(++index));
        assertTrue(resultSet.wasNull());
        assertNull(resultSet.getTimestamp(++index));
        assertTrue(resultSet.wasNull());
        assertNull(resultSet.getDate(++index));
        assertTrue(resultSet.wasNull());
        assertNull(resultSet.getString(++index));
        assertTrue(resultSet.wasNull());

        assertFalse(resultSet.next());
      }
    }
    assertEquals(2, mockSpanner.countRequestsOfType(ExecuteSqlRequest.class));
  }

  @Test
  public void testTwoDmlStatements() throws SQLException {
    try (Connection connection = DriverManager.getConnection(createUrl())) {
      try (java.sql.Statement statement = connection.createStatement()) {
        // The PG JDBC driver will internally split the following SQL string into two statements and
        // execute these sequentially. We still get the results back as if they were executed as one
        // batch on the same statement.
        assertFalse(
            statement.execute(String.format("%s; %s;", INSERT_STATEMENT, UPDATE_STATEMENT)));

        // Note that we have sent two DML statements to the database in one string. These should be
        // treated as separate statements, and there should therefore be two results coming back
        // from the server. That is; The first update count should be 1 (the INSERT), and the second
        // should be 2 (the UPDATE).
        assertEquals(1, statement.getUpdateCount());

        // The following is a prime example of how not to design an API, but this is how JDBC works.
        // getMoreResults() returns true if the next result is a ResultSet. However, if the next
        // result is an update count, it returns false, and we have to check getUpdateCount() to
        // verify whether there were any more results.
        assertFalse(statement.getMoreResults());
        assertEquals(2, statement.getUpdateCount());

        // There are no more results. This is indicated by getMoreResults returning false AND
        // getUpdateCount returning -1.
        assertFalse(statement.getMoreResults());
        assertEquals(-1, statement.getUpdateCount());
      }
    }

    // The DML statements are split by the JDBC driver and sent as separate statements to PgAdapter.
    // PgAdapter therefore also simply executes these as separate DML statements.
    assertTrue(mockSpanner.getRequestsOfType(ExecuteBatchDmlRequest.class).isEmpty());
    List<ExecuteSqlRequest> requests = mockSpanner.getRequestsOfType(ExecuteSqlRequest.class);
    assertEquals(2, requests.size());
    assertEquals(INSERT_STATEMENT.getSql(), requests.get(0).getSql());
    assertEquals(UPDATE_STATEMENT.getSql(), requests.get(1).getSql());
  }

  @Test
  public void testJdbcBatch() throws SQLException {
    try (Connection connection = DriverManager.getConnection(createUrl())) {
      try (java.sql.Statement statement = connection.createStatement()) {
        statement.addBatch(INSERT_STATEMENT.getSql());
        statement.addBatch(UPDATE_STATEMENT.getSql());
        int[] updateCounts = statement.executeBatch();

        assertEquals(2, updateCounts.length);
        assertEquals(1, updateCounts[0]);
        assertEquals(2, updateCounts[1]);
      }
    }

    // The PostgreSQL JDBC driver will send the DML statements as separated statements to PG when
    // executing a batch using simple mode. This means that Spanner will receive two separate DML
    // requests.
    assertTrue(mockSpanner.getRequestsOfType(ExecuteBatchDmlRequest.class).isEmpty());
    List<ExecuteSqlRequest> requests = mockSpanner.getRequestsOfType(ExecuteSqlRequest.class);

    assertEquals(2, requests.size());
    assertEquals(INSERT_STATEMENT.getSql(), requests.get(0).getSql());
    assertEquals(UPDATE_STATEMENT.getSql(), requests.get(1).getSql());
  }

  @Test
  public void testTwoQueries() throws SQLException {
    try (Connection connection = DriverManager.getConnection(createUrl())) {
      try (java.sql.Statement statement = connection.createStatement()) {
        // Statement#execute(String) returns true if the result is a result set.
        assertTrue(statement.execute("SELECT 1; SELECT 2;"));

        try (ResultSet resultSet = statement.getResultSet()) {
          assertTrue(resultSet.next());
          assertEquals(1L, resultSet.getLong(1));
          assertFalse(resultSet.next());
        }

        // getMoreResults() returns true if the next result is a ResultSet.
        assertTrue(statement.getMoreResults());
        try (ResultSet resultSet = statement.getResultSet()) {
          assertTrue(resultSet.next());
          assertEquals(2L, resultSet.getLong(1));
          assertFalse(resultSet.next());
        }

        // getMoreResults() should now return false. We should also check getUpdateCount() as that
        // method should return -1 to indicate that there is also no update count available.
        assertFalse(statement.getMoreResults());
        assertEquals(-1, statement.getUpdateCount());
      }
    }
    assertEquals(2, mockSpanner.countRequestsOfType(ExecuteSqlRequest.class));
  }

  @Test
  public void testDdl() throws SQLException {
    String sql = "CREATE TABLE foo (id bigint primary key)";

    try (Connection connection = DriverManager.getConnection(createUrl())) {
      try (java.sql.Statement statement = connection.createStatement()) {
        // Statement#execute(String) returns false if the result was either an update count or there
        // was no result. Statement#getUpdateCount() returns 0 if there was no result.
        assertFalse(statement.execute(sql));
        assertEquals(0, statement.getUpdateCount());
      }
    }

    List<UpdateDatabaseDdlRequest> updateDatabaseDdlRequests =
        mockDatabaseAdmin.getRequests().stream()
            .filter(request -> request instanceof UpdateDatabaseDdlRequest)
            .map(UpdateDatabaseDdlRequest.class::cast)
            .collect(Collectors.toList());
    assertEquals(1, updateDatabaseDdlRequests.size());
    assertEquals(1, updateDatabaseDdlRequests.get(0).getStatementsCount());
    assertEquals(sql, updateDatabaseDdlRequests.get(0).getStatements(0));
  }
}
