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

package com.google.cloud.spanner.pgadapter.golang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.cloud.ByteArray;
import com.google.cloud.Date;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.Dialect;
import com.google.cloud.spanner.MockSpannerServiceImpl.StatementResult;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.pgadapter.AbstractMockServerTest;
import com.google.protobuf.ListValue;
import com.google.protobuf.Value;
import com.google.spanner.v1.ExecuteSqlRequest;
import com.google.spanner.v1.ExecuteSqlRequest.QueryMode;
import com.google.spanner.v1.ResultSet;
import com.google.spanner.v1.ResultSetMetadata;
import com.google.spanner.v1.StructType;
import com.google.spanner.v1.StructType.Field;
import com.google.spanner.v1.Type;
import com.google.spanner.v1.TypeCode;
import java.io.IOException;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests PGAdapter using the native Go pgx driver. The Go code can be found in
 * src/test/golang/pgadapter_pgx_tests/pgx.go.
 */
@Category(GolangTest.class)
@RunWith(JUnit4.class)
public class PgxMockServerTest extends AbstractMockServerTest {
  private static PgxTest pgxTest;

  @BeforeClass
  public static void compile() throws IOException, InterruptedException {
    pgxTest = PgxTest.compile();
  }

  private GoString createConnString() {
    return new GoString(
        String.format(
            "postgres://uid:pwd@localhost:%d/?statement_cache_capacity=0&sslmode=disable",
            pgServer.getLocalPort()));
  }

  @Test
  public void testHelloWorld() {
    String sql = "select 'Hello world!' as hello";

    mockSpanner.putStatementResult(
        StatementResult.query(
            Statement.of(sql),
            com.google.spanner.v1.ResultSet.newBuilder()
                .setMetadata(
                    ResultSetMetadata.newBuilder()
                        .setRowType(
                            StructType.newBuilder()
                                .addFields(
                                    Field.newBuilder()
                                        .setName("hello")
                                        .setType(Type.newBuilder().setCode(TypeCode.STRING).build())
                                        .build())
                                .build())
                        .build())
                .addRows(
                    ListValue.newBuilder()
                        .addValues(Value.newBuilder().setStringValue("Hello world!").build())
                        .build())
                .build()));

    String res = pgxTest.TestHelloWorld(createConnString());

    assertNull(res);
    List<ExecuteSqlRequest> requests = mockSpanner.getRequestsOfType(ExecuteSqlRequest.class);
    // The patched version of pgx sends the query one time.
    assertEquals(1, requests.size());
    ExecuteSqlRequest request = requests.get(0);
    assertEquals(sql, request.getSql());
    assertEquals(QueryMode.NORMAL, request.getQueryMode());
  }

  @Test
  public void testSelect1() {
    String sql = "SELECT 1";

    String res = pgxTest.TestSelect1(createConnString());

    assertNull(res);
    List<ExecuteSqlRequest> requests = mockSpanner.getRequestsOfType(ExecuteSqlRequest.class);
    // The patched version of pgx sends the query one time.
    assertEquals(1, requests.size());
    ExecuteSqlRequest request = requests.get(0);
    assertEquals(sql, request.getSql());
    assertEquals(QueryMode.NORMAL, request.getQueryMode());
  }

  @Test
  public void testQueryWithParameter() {
    String sql = "SELECT * FROM FOO WHERE BAR=$1";
    Statement statement = Statement.newBuilder(sql).bind("p1").to("baz").build();

    ResultSetMetadata metadata =
        ResultSetMetadata.newBuilder()
            .setRowType(
                StructType.newBuilder()
                    .addFields(
                        Field.newBuilder()
                            .setName("BAR")
                            .setType(Type.newBuilder().setCode(TypeCode.STRING).build())
                            .build())
                    .build())
            .build();

    // Add a query result with only the metadata for the query without parameter values.
    mockSpanner.putStatementResult(
        StatementResult.query(
            Statement.of(sql), ResultSet.newBuilder().setMetadata(metadata).build()));
    // Also add a query result with both metadata and rows for the statement with parameter values.
    mockSpanner.putStatementResult(
        StatementResult.query(
            statement,
            ResultSet.newBuilder()
                .setMetadata(metadata)
                .addRows(
                    ListValue.newBuilder()
                        .addValues(Value.newBuilder().setStringValue("baz").build())
                        .build())
                .build()));

    String res = pgxTest.TestQueryWithParameter(createConnString());

    assertNull(res);
    List<ExecuteSqlRequest> requests = mockSpanner.getRequestsOfType(ExecuteSqlRequest.class);
    // The patched version of pgx sends the query one time.
    assertEquals(1, requests.size());
    ExecuteSqlRequest request = requests.get(0);
    assertEquals(sql, request.getSql());
    assertEquals(QueryMode.NORMAL, request.getQueryMode());
  }

  @Test
  public void testQueryAllDataTypes() {
    String sql = "SELECT * FROM all_types WHERE col_bigint=1";
    mockSpanner.putStatementResult(StatementResult.query(Statement.of(sql), ALL_TYPES_RESULTSET));

    String res = pgxTest.TestQueryAllDataTypes(createConnString());

    assertNull(res);
    List<ExecuteSqlRequest> requests = mockSpanner.getRequestsOfType(ExecuteSqlRequest.class);
    // The patched version of pgx sends the query one time.
    assertEquals(1, requests.size());
    ExecuteSqlRequest request = requests.get(0);
    assertEquals(QueryMode.NORMAL, request.getQueryMode());
  }

  @Test
  public void testInsertAllDataTypes() {
    String sql =
        "INSERT INTO all_types "
            + "(col_bigint, col_bool, col_bytea, col_float8, col_numeric, col_timestamptz, col_date, col_varchar) "
            + "values ($1, $2, $3, $4, $5, $6, $7, $8)";
    mockSpanner.putStatementResult(
        StatementResult.update(
            Statement.newBuilder(sql)
                .bind("p1")
                .to(100L)
                .bind("p2")
                .to(true)
                .bind("p3")
                .to(ByteArray.copyFrom("test_bytes"))
                .bind("p4")
                .to(3.14d)
                .bind("p5")
                .to(com.google.cloud.spanner.Value.pgNumeric("6.626"))
                .bind("p6")
                .to(Timestamp.parseTimestamp("2022-03-24T06:39:10.123456000Z"))
                .bind("p7")
                .to(Date.parseDate("2022-04-02"))
                .bind("p8")
                .to("test_string")
                .build(),
            1L));

    String res = pgxTest.TestInsertAllDataTypes(createConnString(), true);

    assertNull(res);
    List<ExecuteSqlRequest> requests = mockSpanner.getRequestsOfType(ExecuteSqlRequest.class);
    // The patched version of pgx sends the query only once!
    assertEquals(1, requests.size());
    ExecuteSqlRequest request = requests.get(0);
    assertEquals(QueryMode.NORMAL, request.getQueryMode());
  }

  @Test
  public void testInsertNullsAllDataTypes() {
    String sql =
        "INSERT INTO all_types "
            + "(col_bigint, col_bool, col_bytea, col_float8, col_int, col_numeric, col_timestamptz, col_date, col_varchar) "
            + "values ($1, $2, $3, $4, $5, $6, $7, $8, $9)";
    mockSpanner.putStatementResult(
        StatementResult.update(
            Statement.newBuilder(sql)
                .bind("p1")
                .to(100L)
                .bind("p2")
                .to((com.google.cloud.spanner.Value) null)
                .bind("p3")
                .to((com.google.cloud.spanner.Value) null)
                .bind("p4")
                .to((com.google.cloud.spanner.Value) null)
                .bind("p5")
                .to((com.google.cloud.spanner.Value) null)
                .bind("p6")
                .to((com.google.cloud.spanner.Value) null)
                .bind("p7")
                .to((com.google.cloud.spanner.Value) null)
                .bind("p8")
                .to((com.google.cloud.spanner.Value) null)
                .bind("p9")
                .to((com.google.cloud.spanner.Value) null)
                .build(),
            1L));

    String res = pgxTest.TestInsertNullsAllDataTypes(createConnString(), true);

    assertNull(res);
    List<ExecuteSqlRequest> requests = mockSpanner.getRequestsOfType(ExecuteSqlRequest.class);
    // The patched version of pgx sends the query only once!
    assertEquals(1, requests.size());
    ExecuteSqlRequest request = requests.get(0);
    assertEquals(QueryMode.NORMAL, request.getQueryMode());
  }

  @Test
  public void testWrongDialect() {
    // Let the mock server respond with the Google SQL dialect instead of PostgreSQL. The
    // connection should be gracefully rejected. Close all open pooled Spanner objects so we know
    // that we will get a fresh one for our connection. This ensures that it will execute a query to
    // determine the dialect of the database.
    closeSpannerPool();
    try {
      mockSpanner.putStatementResult(
          StatementResult.detectDialectResult(Dialect.GOOGLE_STANDARD_SQL));

      String result = pgxTest.TestWrongDialect(createConnString());

      assertNotNull(result);
      assertTrue(result, result.contains("failed to connect to PG"));
      assertTrue(result, result.contains("The database uses dialect GOOGLE_STANDARD_SQL"));
    } finally {
      mockSpanner.putStatementResult(StatementResult.detectDialectResult(Dialect.POSTGRESQL));
      closeSpannerPool();
    }
  }
}
