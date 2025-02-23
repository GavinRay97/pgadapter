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

import static com.google.cloud.spanner.pgadapter.parsers.copy.Copy.parse;
import static com.google.cloud.spanner.pgadapter.parsers.copy.CopyTreeParser.CopyOptions.Format;

import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.SpannerExceptionFactory;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.connection.AbstractStatementParser.ParsedStatement;
import com.google.cloud.spanner.connection.AutocommitDmlMode;
import com.google.cloud.spanner.connection.Connection;
import com.google.cloud.spanner.connection.StatementResult.ResultType;
import com.google.cloud.spanner.pgadapter.metadata.OptionsMetadata;
import com.google.cloud.spanner.pgadapter.parsers.copy.CopyTreeParser;
import com.google.cloud.spanner.pgadapter.parsers.copy.TokenMgrError;
import com.google.cloud.spanner.pgadapter.utils.MutationWriter;
import com.google.cloud.spanner.pgadapter.utils.MutationWriter.CopyTransactionMode;
import com.google.common.base.Strings;
import com.google.spanner.v1.TypeCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.commons.csv.CSVFormat;

public class CopyStatement extends IntermediateStatement {

  private static final String COLUMN_NAME = "column_name";
  private static final String DATA_TYPE = "data_type";
  private static final String CSV = "CSV";

  private CopyTreeParser.CopyOptions options = new CopyTreeParser.CopyOptions();
  private CSVFormat format;

  // Table columns read from information schema.
  private Map<String, TypeCode> tableColumns;
  private int indexedColumnsCount;
  private MutationWriter mutationWriter;
  private Future<Long> updateCount;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  public CopyStatement(
      OptionsMetadata options, ParsedStatement parsedStatement, Connection connection) {
    super(options, parsedStatement, connection);
  }

  @Override
  public Exception getException() {
    // Do not clear exceptions on a CopyStatement.
    return this.exception;
  }

  @Override
  public void close() throws Exception {
    if (this.mutationWriter != null) {
      this.mutationWriter.close();
    }
    this.executor.shutdown();
    super.close();
  }

  @Override
  public Long getUpdateCount() {
    try {
      return updateCount.get();
    } catch (ExecutionException e) {
      throw SpannerExceptionFactory.asSpannerException(e.getCause());
    } catch (InterruptedException e) {
      throw SpannerExceptionFactory.propagateInterrupt(e);
    }
  }

  @Override
  public ResultType getResultType() {
    return ResultType.UPDATE_COUNT;
  }

  /** @return Mapping of table column names to column type. */
  public Map<String, TypeCode> getTableColumns() {
    return this.tableColumns;
  }

  /** @return CSVFormat for parsing copy data based on COPY statement options specified. */
  public void setParserFormat(CopyTreeParser.CopyOptions options) {
    this.format = CSVFormat.POSTGRESQL_TEXT;
    if (options.getFormat() == Format.CSV) {
      this.format = CSVFormat.POSTGRESQL_CSV;
    }
    if (!Strings.isNullOrEmpty(options.getNullString())) {
      this.format = this.format.withNullString(options.getNullString());
    }
    if (options.getDelimiter() != '\0') {
      this.format = this.format.withDelimiter(options.getDelimiter());
    }
    if (options.getEscape() != '\0') {
      this.format = this.format.withEscape(options.getEscape());
    }
    if (options.getQuote() != '\0') {
      this.format = this.format.withQuote(options.getQuote());
    }
    this.format = this.format.withHeader(this.tableColumns.keySet().toArray(new String[0]));
  }

  public CSVFormat getParserFormat() {
    return this.format;
  }

  public String getTableName() {
    return options.getTableName();
  }

  /** @return List of column names specified in COPY statement, if provided. */
  public List<String> getCopyColumnNames() {
    return options.getColumnNames();
  }

  /** @return Format type specified in COPY statement, if provided. */
  public String getFormatType() {
    return options.getFormat().toString();
  }

  /** @return True if copy data contains a header, false otherwise. */
  public boolean hasHeader() {
    return options.hasHeader();
  }

  /** @return Null string specified in COPY statement, if provided. */
  public String getNullString() {
    return this.format.getNullString();
  }

  /** @return Delimiter character specified in COPY statement, if provided. */
  public char getDelimiterChar() {
    return this.format.getDelimiter();
  }

  /** @return Escape character specified in COPY statement, if provided. */
  public char getEscapeChar() {
    return this.format.getEscapeCharacter();
  }

  /** @return Quote character specified in COPY statement, if provided. */
  public char getQuoteChar() {
    return this.format.getQuoteCharacter();
  }

  public MutationWriter getMutationWriter() {
    return this.mutationWriter;
  }

  /** @return 0 for text/csv formatting and 1 for binary */
  public int getFormatCode() {
    return (options.getFormat() == CopyTreeParser.CopyOptions.Format.BINARY) ? 1 : 0;
  }

  private void verifyCopyColumns() {
    if (options.getColumnNames().size() == 0) {
      // Use all columns if none were specified.
      return;
    }
    if (options.getColumnNames().size() > this.tableColumns.size()) {
      throw SpannerExceptionFactory.newSpannerException(
          ErrorCode.INVALID_ARGUMENT, "Number of copy columns provided exceed table column count");
    }
    LinkedHashMap<String, TypeCode> tempTableColumns = new LinkedHashMap<>();
    // Verify that every copy column given is a valid table column name
    for (String copyColumn : options.getColumnNames()) {
      if (!this.tableColumns.containsKey(copyColumn)) {
        throw SpannerExceptionFactory.newSpannerException(
            ErrorCode.INVALID_ARGUMENT,
            "Column \"" + copyColumn + "\" of relation \"" + getTableName() + "\" does not exist");
      }
      // Update table column ordering with the copy column ordering
      tempTableColumns.put(copyColumn, tableColumns.get(copyColumn));
    }
    this.tableColumns = tempTableColumns;
  }

  private static TypeCode parsePostgreSQLDataType(String columnType) {
    // Eliminate size modifiers in column type (e.g. character varying(100), etc.)
    int index = columnType.indexOf("(");
    columnType = (index > 0) ? columnType.substring(0, index) : columnType;
    switch (columnType) {
      case "boolean":
        return TypeCode.BOOL;
      case "bigint":
        return TypeCode.INT64;
      case "float8":
      case "double precision":
        return TypeCode.FLOAT64;
      case "numeric":
        return TypeCode.NUMERIC;
      case "bytea":
        return TypeCode.BYTES;
      case "character varying":
      case "text":
        return TypeCode.STRING;
      case "date":
        return TypeCode.DATE;
      case "timestamp with time zone":
        return TypeCode.TIMESTAMP;
      case "jsonb":
        return TypeCode.JSON;
      default:
        throw new IllegalArgumentException(
            "Unrecognized or unsupported column data type: " + columnType);
    }
  }

  private void queryInformationSchema() {
    Map<String, TypeCode> tableColumns = new LinkedHashMap<>();
    Statement statement =
        Statement.newBuilder(
                "SELECT "
                    + COLUMN_NAME
                    + ", "
                    + DATA_TYPE
                    + " FROM information_schema.columns WHERE table_name = $1")
            .bind("p1")
            .to(getTableName())
            .build();
    try (ResultSet result = connection.executeQuery(statement)) {
      while (result.next()) {
        String columnName = result.getString(COLUMN_NAME);
        TypeCode type = parsePostgreSQLDataType(result.getString(DATA_TYPE));
        tableColumns.put(columnName, type);
      }
    }

    if (tableColumns.isEmpty()) {
      throw SpannerExceptionFactory.newSpannerException(
          ErrorCode.INVALID_ARGUMENT,
          "Table " + getTableName() + " is not found in information_schema");
    }

    this.tableColumns = tableColumns;

    if (options.getColumnNames() != null) {
      verifyCopyColumns();
    }
    this.indexedColumnsCount = queryIndexedColumnsCount(tableColumns.keySet());
  }

  private int queryIndexedColumnsCount(Set<String> columnNames) {
    String sql =
        "SELECT COUNT(*) FROM information_schema.index_columns "
            + "WHERE table_schema='public' "
            + "and table_name=$1 "
            + "and column_name in "
            + IntStream.rangeClosed(2, columnNames.size() + 1)
                .mapToObj(i -> String.format("$%d", i))
                .collect(Collectors.joining(", ", "(", ")"));
    Statement.Builder builder = Statement.newBuilder(sql);
    builder.bind("p1").to(getTableName());
    int paramIndex = 2;
    for (String columnName : columnNames) {
      builder.bind(String.format("p%d", paramIndex)).to(columnName);
      paramIndex++;
    }
    Statement statement = builder.build();
    try (ResultSet resultSet = connection.executeQuery(statement)) {
      if (resultSet.next()) {
        return (int) resultSet.getLong(0);
      }
    }
    return 0;
  }

  @Override
  public void handleExecutionException(SpannerException e) {
    executor.shutdownNow();
    super.handleExecutionException(e);
  }

  @Override
  public void execute() {
    this.executed = true;
    try {
      parseCopyStatement();
      queryInformationSchema();
      setParserFormat(this.options);
      mutationWriter =
          new MutationWriter(
              getTransactionMode(),
              connection,
              options.getTableName(),
              getTableColumns(),
              indexedColumnsCount,
              getParserFormat(),
              hasHeader());
      updateCount = executor.submit(mutationWriter);
    } catch (Exception e) {
      SpannerException spannerException = SpannerExceptionFactory.asSpannerException(e);
      handleExecutionException(spannerException);
    }
  }

  private CopyTransactionMode getTransactionMode() {
    if (connection.isInTransaction()) {
      return CopyTransactionMode.Explicit;
    } else {
      if (connection.getAutocommitDmlMode() == AutocommitDmlMode.PARTITIONED_NON_ATOMIC) {
        return CopyTransactionMode.ImplicitNonAtomic;
      }
      return CopyTransactionMode.ImplicitAtomic;
    }
  }

  private void parseCopyStatement() throws Exception {
    try {
      parse(parsedStatement.getSqlWithoutComments(), this.options);
    } catch (Exception | TokenMgrError e) {
      throw SpannerExceptionFactory.newSpannerException(
          ErrorCode.INVALID_ARGUMENT, "Invalid COPY statement syntax: " + e);
    }
  }
}
