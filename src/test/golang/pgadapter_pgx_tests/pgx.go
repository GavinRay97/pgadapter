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

package main

import "C"
import (
	"context"
	"fmt"
	"github.com/jackc/pgconn"
	"github.com/jackc/pgtype"
	"github.com/jackc/pgx/v4"
	"reflect"
	"time"
)

// This file defines tests that can be called from Java and that will connect to any PGAdapter
// instance that is defined in the connection string that is passed in to each of the test
// functions. The PGAdapter instance can be an in-process instance that is created and started by
// the Java test framework, and the Spanner database that PGAdapter is connected to can be a mock
// Spanner database or a real Spanner database.
// Test errors are returned as C strings.

// An empty main method is required to build a shard C lib.
func main() {
}

//export TestHelloWorld
func TestHelloWorld(connString string) *C.char {
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, connString)
	if err != nil {
		return C.CString(err.Error())
	}
	defer conn.Close(ctx)

	var greeting string
	err = conn.QueryRow(ctx, "select 'Hello world!' as hello").Scan(&greeting)
	if err != nil {
		return C.CString(err.Error())
	}
	if g, w := greeting, "Hello world!"; g != w {
		return C.CString(fmt.Sprintf("greeting mismatch\n Got: %v\nWant: %v", g, w))
	}

	return nil
}

//export TestSelect1
func TestSelect1(connString string) *C.char {
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, connString)
	if err != nil {
		return C.CString(err.Error())
	}
	defer conn.Close(ctx)

	var value int64
	err = conn.QueryRow(ctx, "SELECT 1").Scan(&value)
	if err != nil {
		return C.CString(err.Error())
	}
	if g, w := value, int64(1); g != w {
		return C.CString(fmt.Sprintf("value mismatch\n Got: %v\nWant: %v", g, w))
	}

	return nil
}

//export TestQueryWithParameter
func TestQueryWithParameter(connString string) *C.char {
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, connString)
	if err != nil {
		return C.CString(err.Error())
	}
	defer conn.Close(ctx)

	var value string
	err = conn.QueryRow(ctx, "SELECT * FROM FOO WHERE BAR=$1", "baz").Scan(&value)
	if err != nil {
		return C.CString(fmt.Sprintf("Failed to execute query: %v", err.Error()))
	}
	if g, w := value, "baz"; g != w {
		return C.CString(fmt.Sprintf("value mismatch\n Got: %v\nWant: %v", g, w))
	}

	return nil
}

//export TestQueryAllDataTypes
func TestQueryAllDataTypes(connString string) *C.char {
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, connString)
	if err != nil {
		return C.CString(err.Error())
	}
	defer conn.Close(ctx)

	var bigintValue int64
	var boolValue bool
	var byteaValue []byte
	var float8Value float64
	var intValue int
	var numericValue pgtype.Numeric // pgx default maps numeric to string
	var timestamptzValue time.Time
	var dateValue time.Time
	var varcharValue string

	err = conn.QueryRow(ctx, "SELECT * FROM all_types WHERE col_bigint=1").Scan(
		&bigintValue,
		&boolValue,
		&byteaValue,
		&float8Value,
		&intValue,
		&numericValue,
		&timestamptzValue,
		&dateValue,
		&varcharValue,
	)
	if err != nil {
		return C.CString(fmt.Sprintf("Failed to execute query: %v", err.Error()))
	}
	if g, w := bigintValue, int64(1); g != w {
		return C.CString(fmt.Sprintf("value mismatch\n Got: %v\nWant: %v", g, w))
	}
	if g, w := boolValue, true; g != w {
		return C.CString(fmt.Sprintf("value mismatch\n Got: %v\nWant: %v", g, w))
	}
	if g, w := byteaValue, []byte("test"); !reflect.DeepEqual(g, w) {
		return C.CString(fmt.Sprintf("value mismatch\n Got: %v\nWant: %v", g, w))
	}
	if g, w := float8Value, 3.14; g != w {
		return C.CString(fmt.Sprintf("value mismatch\n Got: %v\nWant: %v", g, w))
	}
	if g, w := intValue, 100; g != w {
		return C.CString(fmt.Sprintf("value mismatch\n Got: %v\nWant: %v", g, w))
	}
	var wantNumericValue pgtype.Numeric
	_ = wantNumericValue.Scan("6.626")
	if g, w := numericValue, wantNumericValue; !reflect.DeepEqual(g, w) {
		return C.CString(fmt.Sprintf("value mismatch\n Got: %v\nWant: %v", g, w))
	}
	wantDateValue, _ := time.Parse("2006-01-02", "2022-03-29")
	if g, w := dateValue, wantDateValue; !reflect.DeepEqual(g, w) {
		return C.CString(fmt.Sprintf("value mismatch\n Got: %v\nWant: %v", g, w))
	}
	wantTimestamptzValue, _ := time.Parse(time.RFC3339Nano, "2022-02-16T13:18:02.123456789+00:00")
	if g, w := timestamptzValue.String(), wantTimestamptzValue.String(); g != w {
		return C.CString(fmt.Sprintf("value mismatch\n Got: %v\nWant: %v", g, w))
	}
	if g, w := varcharValue, "test"; g != w {
		return C.CString(fmt.Sprintf("value mismatch\n Got: %v\nWant: %v", g, w))
	}

	return nil
}

//export TestInsertAllDataTypes
func TestInsertAllDataTypes(connString string, dateSupported bool) *C.char {
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, connString)
	if err != nil {
		return C.CString(err.Error())
	}
	defer conn.Close(ctx)

	// There is no data type registered by default for NUMERIC in pgx, as there is no native
	// decimal/numeric data in Go. We therefore need to register the default that we want to use.
	if dt, ok := conn.ConnInfo().DataTypeForOID(pgtype.NumericOID); ok {
		conn.ConnInfo().RegisterDefaultPgType(pgtype.Numeric{}, dt.Name)
	} else {
		return C.CString("could not register default type for numeric")
	}
	if dt, ok := conn.ConnInfo().DataTypeForOID(pgtype.DateOID); ok {
		conn.ConnInfo().RegisterDefaultPgType(pgtype.Date{}, dt.Name)
	} else {
		return C.CString("could not register default type for date")
	}

	sql := "INSERT INTO all_types (col_bigint, col_bool, col_bytea, col_float8, col_numeric, col_timestamptz, col_date, col_varchar) values ($1, $2, $3, $4, $5, $6, $7, $8)"
	if !dateSupported {
		sql = "INSERT INTO all_types (col_bigint, col_bool, col_bytea, col_float8, col_numeric, col_timestamptz, col_varchar) values ($1, $2, $3, $4, $5, $6, $7)"
	}
	numeric := pgtype.Numeric{}
	_ = numeric.Set("6.626")
	timestamptz, _ := time.Parse(time.RFC3339Nano, "2022-03-24T07:39:10.123456789+01:00")
	var tag pgconn.CommandTag
	if dateSupported {
		date := pgtype.Date{}
		_ = date.Set("2022-04-02")
		tag, err = conn.Exec(ctx, sql, 100, true, []byte("test_bytes"), 3.14, numeric, timestamptz, date, "test_string")
	} else {
		tag, err = conn.Exec(ctx, sql, 100, true, []byte("test_bytes"), 3.14, numeric, timestamptz, "test_string")
	}
	if err != nil {
		return C.CString(fmt.Sprintf("failed to execute insert statement: %v", err))
	}
	if !tag.Insert() {
		return C.CString("statement was not recognized as an insert")
	}
	if g, w := tag.RowsAffected(), int64(1); g != w {
		return C.CString(fmt.Sprintf("rows affected mismatch:\n Got: %v\nWant: %v", g, w))
	}

	return nil
}

//export TestInsertNullsAllDataTypes
func TestInsertNullsAllDataTypes(connString string, dateSupported bool) *C.char {
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, connString)
	if err != nil {
		return C.CString(err.Error())
	}
	defer conn.Close(ctx)

	var tag pgconn.CommandTag
	if dateSupported {
		sql := "INSERT INTO all_types (col_bigint, col_bool, col_bytea, col_float8, col_int, col_numeric, col_timestamptz, col_date, col_varchar) values ($1, $2, $3, $4, $5, $6, $7, $8, $9)"
		tag, err = conn.Exec(ctx, sql, int64(100), nil, nil, nil, nil, nil, nil, nil, nil)
	} else {
		var b *bool
		sql := "INSERT INTO all_types (col_bigint, col_bool, col_bytea, col_float8, col_int, col_numeric, col_timestamptz, col_varchar) values ($1, $2, $3, $4, $5, $6, $7, $8)"
		tag, err = conn.Exec(ctx, sql, int64(100), b, nil, nil, nil, nil, nil, nil, nil)
	}
	if err != nil {
		return C.CString(fmt.Sprintf("failed to execute insert statement: %v", err))
	}
	if !tag.Insert() {
		return C.CString("statement was not recognized as an insert")
	}
	if g, w := tag.RowsAffected(), int64(1); g != w {
		return C.CString(fmt.Sprintf("rows affected mismatch:\n Got: %v\nWant: %v", g, w))
	}

	return nil
}

//export TestWrongDialect
func TestWrongDialect(connString string) *C.char {
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, connString)
	if err != nil {
		return C.CString(fmt.Sprintf("failed to connect to PG: %v", err))
	}
	defer conn.Close(ctx)

	return nil
}
