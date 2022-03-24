// Copyright 2020 Google LLC
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

import com.google.cloud.spanner.ReadContext.QueryAnalyzeMode;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.connection.Connection;
import com.google.cloud.spanner.connection.StatementResult;
import com.google.cloud.spanner.pgadapter.metadata.DescribeMetadata;
import com.google.cloud.spanner.pgadapter.metadata.DescribeStatementMetadata;
import com.google.cloud.spanner.pgadapter.metadata.OptionsMetadata;
import com.google.cloud.spanner.pgadapter.parsers.Parser;
import com.google.cloud.spanner.pgadapter.parsers.Parser.FormatCode;
import com.google.cloud.spanner.pgadapter.utils.StatementParser;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.postgresql.core.Oid;

/**
 * Intermediate representation for prepared statements (i.e.: statements before they become portals)
 */
public class IntermediatePreparedStatement extends IntermediateStatement {

  private static final Charset UTF8 = StandardCharsets.UTF_8;
  protected List<Integer> parameterDataTypes;
  protected Statement statement;

  public IntermediatePreparedStatement(OptionsMetadata options, String sql, Connection connection) {
    super(options, sql);
    this.sql = replaceKnownUnsupportedQueries(sql);
    this.command = StatementParser.parseCommand(this.sql);
    this.connection = connection;
    this.parameterDataTypes = null;
  }

  /**
   * Given a set of parameters in byte format, return the designated type if stored by the user,
   * otherwise guess that type.
   *
   * @param parameters Array of all parameters in byte format.
   * @param index Index of the desired item.
   * @return The type of the item specified.
   */
  private int parseType(byte[][] parameters, int index) throws IllegalArgumentException {
    if (this.parameterDataTypes.size() > index) {
      return this.parameterDataTypes.get(index);
    } else {
      return Oid.UNSPECIFIED;
    }
  }

  public List<Integer> getParameterDataTypes() {
    return this.parameterDataTypes;
  }

  public void setParameterDataTypes(List<Integer> parameterDataTypes) {
    this.parameterDataTypes = parameterDataTypes;
  }

  @Override
  public void execute() {
    this.executed = true;
    try {
      StatementResult result = connection.execute(this.statement);
      this.updateResultCount(result);
    } catch (SpannerException e) {
      handleExecutionException(e);
    }
  }

  /**
   * Bind this statement (that is to say, transform it into a portal by giving it the data items to
   * complete the statement.
   *
   * @param parameters The array of parameters to be bound in byte format.
   * @param parameterFormatCodes A list of the format of each parameter.
   * @param resultFormatCodes A list of the desired format of each result.
   * @return An Intermediate Portal Statement (or rather a bound version of this statement)
   */
  public IntermediatePortalStatement bind(
      byte[][] parameters, List<Short> parameterFormatCodes, List<Short> resultFormatCodes) {
    IntermediatePortalStatement portal =
        new IntermediatePortalStatement(this.options, this.sql, this.connection);
    portal.setParameterFormatCodes(parameterFormatCodes);
    portal.setResultFormatCodes(resultFormatCodes);
    Statement.Builder builder = Statement.newBuilder(sql);
    for (int index = 0; index < parameters.length; index++) {
      short formatCode = portal.getParameterFormatCode(index);
      int type = this.parseType(parameters, index);
      Parser<?> parser = Parser.create(parameters[index], type, FormatCode.of(formatCode));
      parser.bind(builder, "p" + (index + 1));
    }
    this.statement = builder.build();
    portal.setBoundStatement(statement);

    return portal;
  }

  @Override
  public DescribeMetadata describe() {
    if (PARSER.isQuery(this.sql)) {
      Statement statement = Statement.of(this.sql);
      try (ResultSet resultSet = connection.analyzeQuery(statement, QueryAnalyzeMode.PLAN)) {
        // TODO: Remove ResultSet.next() call once this is supported in the client library.
        // See https://github.com/googleapis/java-spanner/pull/1691
        resultSet.next();
        return new DescribeStatementMetadata(getParameterTypes(), resultSet);
      }
    }
    return new DescribeStatementMetadata(getParameterTypes(), null);
  }

  /**
   * Returns the parameter types in the SQL string of this statement. The current implementation
   * always returns Oid.UNSPECIFIED for all parameters that were not already specified by the client
   * in the parse message, as we have no way to actually determine the parameter types.
   */
  private int[] getParameterTypes() {
    Set<String> parameters = PARSER.getQueryParameters(this.sql);
    int[] parameterTypes = new int[parameters.size()];
    if (this.parameterDataTypes != null) {
      for (int i = 0; i < parameterTypes.length && i < this.parameterDataTypes.size(); i++) {
        parameterTypes[i] = this.parameterDataTypes.get(i);
      }
    }
    return parameterTypes;
  }
}
