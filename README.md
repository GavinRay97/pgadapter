# Google Cloud Spanner PGAdapter

PGAdapter is a proxy which translates the PostgreSQL wire-protocol into the
equivalent for Spanner databases [that use the PostgreSQL interface](https://cloud.google.com/spanner/docs/postgresql-interface).

PGAdapter can be used with the following clients:
1. `psql`: Versions 11, 12, 13 and 14 are supported. See [psql support](docs/psql.md) for more details.
2. `JDBC`: Versions 42.x and higher have __limited support__. See [JDBC support](docs/jdbc.md) for more details.
3. `pgx`: Version 4.15 and higher have __limited support__. See [pgx support](docs/pgx.md) for more details.

## Usage
PGAdapter can be started both as a Docker container, a standalone process as well as an
in-process server (the latter is only supported for Java applications).

### Docker

See [running PGAdapter using Docker](docs/docker.md) for more examples for running PGAdapter in Docker.

Replace the project, instance and database names and the credentials file in the example below to
run PGAdapter from a pre-built Docker image.

```shell
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/credentials.json
docker pull gcr.io/cloud-spanner-pg-adapter/pgadapter
docker run \
  -d -p 5432:5432 \
  -v ${GOOGLE_APPLICATION_CREDENTIALS}:${GOOGLE_APPLICATION_CREDENTIALS}:ro \
  -e GOOGLE_APPLICATION_CREDENTIALS \
  gcr.io/cloud-spanner-pg-adapter/pgadapter \
  -p my-project -i my-instance -d my-database \
  -x
```

See [Running in Docker](docs/docker.md) for more information.

See [Options](#Options) for an explanation of all further options.

### Standalone with pre-built jar

A pre-built jar containing all dependencies can be downloaded from https://storage.googleapis.com/pgadapter-jar-releases/pgadapter.jar

```shell
wget https://storage.googleapis.com/pgadapter-jar-releases/pgadapter.jar
java -jar pgadapter.jar -p my-project -i my-instance -d my-database
```

Use the `-s` option to specify a different local port than the default 5432 if you already have
PostgreSQL running on your local system.

You can also download a specific version of the jar. Example (replace `v0.2.1` with the version you want to download):

```shell
VERSION=v0.2.1
wget https://storage.googleapis.com/pgadapter-jar-releases/pgadapter-${VERSION}.jar
java -jar pgadapter-${VERSION}.jar -p my-project -i my-instance -d my-database
```

See [Options](#Options) for an explanation of all further options.

### Standalone with locally built jar
1. Build a jar file containing all dependencies by running

```shell
mvn package -P shade
```

2. Execute
```shell
java -jar <jar-file> <options>
```

See [Options](#Options) for an explanation of all further options.

### In-process
This option is only available for Java/JVM-based applications.

1. Add `google-cloud-spanner-pgadapter` as a dependency to your project by adding this to your `pom.xml` file:

```xml
<dependency>
  <groupId>com.google.cloud</groupId>
  <artifactId>google-cloud-spanner-pgadapter</artifactId>
  <version>0.3.0</version>
</dependency>
```

2. Build a server using the `com.google.cloud.spanner.pgadapter.ProxyServer` class:

```java
class PGProxyRunner {
    public static void main() {
        ProxyServer server = new ProxyServer(
          new OptionsMetadata(
                "jdbc:cloudspanner:/projects/my-project"
                + "/instances/my-instance"
                + "/databases/my-database"
                + ";credentials=/home/user/service-account-credentials.json",
                portNumber,
                textFormat,
                forceBinary,
                authenticate,
                requiresMatcher,
                commandMetadataJSON)
        );
        server.startServer();
    }
}
```

Wherein the first item is the JDBC connection string containing pertinent
information regarding project id, instance id, database name, credentials file
path; All other items map directly to previously mentioned CLI options.

### Options

#### Required

The following options are required to run the proxy:

```    
-p <projectname>
  * The project name where the desired Spanner database is running.
    
-i <instanceid>
  * The instance ID where the desired Spanner database is running.

-d <databasename>
  * The Spanner database name.

-c <credentialspath>
  * This is only required if you have not already set up default credentials on the system where you
    are running PGAdapter. See https://cloud.google.com/spanner/docs/getting-started/set-up#set_up_authentication_and_authorization
    for more information on setting up authentication for Cloud Spanner.
  * The full path for the file containing the service account credentials in JSON format.
  * Do remember to grant the service account sufficient credentials to access the database. See
    https://cloud.google.com/docs/authentication/production for more information.
```

#### Optional

The following options are optional:

```    
-s <port>
  * The inbound port for the proxy. Defaults to 5432. Choose a different port if you already have
    PostgreSQL running on your local system.
   
-a
  * Use authentication when connecting. Currently authentication is not strictly
    implemented in the proxy layer, as it is expected to be run locally, and
    will ignore any connection not stemming from localhost. However, it is a
    useful compatibility option if the PostgreSQL client is set to always 
    authenticate. Note that SSL is not included for the same reason that
    authentication logic is not: since all connections are local, sniffing
    traffic should not generally be a concern.

-q
  * PSQL Mode. Use this option when fronting PSQL. This option will incur some
    performance penalties due to query matching and translation and as such is
    not recommended for production. It is further not guaranteed to perfectly
    match PSQL logic. Please only use this mode when using PSQL.
    
-jdbc
  * JDBC Mode. Use this option when fronting the native PostgreSQL JDBC driver.
    This option will inspect queries to look for native JDBC metadata queries and
    replace these with queries that are suppported by Cloud Spanner PostgreSQL
    databases.
    
-c <multi-statementcommand>
    Runs a single command before exiting. This command can be comprised of multiple 
    statments separated by ';'. Each SQL command string passed to -c is sent to the 
    server as a single request. Because of this, the server executes it as a single 
    transaction even if the string contains multiple SQL commands. There are some
    limitations in Cloud Spanner which require this option to operate in a different
    manner from PSQL. 'SELECT' statements are not allowed in batched commands. Mixing
    DML and DDL is not allowed in a single multi-statement command. Also, 
    'BEGIN TRANSACTION' and 'COMMIT' statements are not allowed in batched commands.

-b
  * Force the server to send data back in binary PostgreSQL format when no
    specific format has been requested. The PostgreSQL wire protocol specifies 
    that the server should send data in text format in those cases. This 
    setting overrides this default and should be used with caution, for example
    for testing purposes, as clients might not accept this behavior. This 
    setting only affects query results in extended query mode. Queries in 
    simple query mode will always return results in text format. If you do not 
    know what extended query mode and simple query mode is, then you should 
    probably not be using this setting.

-j <commandmetadatapath>
  * The full path for a file containing a JSON object to do SQL translation
    based on RegEx replacement. Any item matching the input_pattern will be
    replaced by the output_pattern string, wherein capture groups are allowed and
    their order is specified via the matcher_array item. Match replacement must be
    defined via %s in the output_pattern string. Set matcher_array to [] if no
    matches exist. Alternatively, you may place the matching group names 
    directly within the output_pattern string using matcher.replaceAll() rules
    (that is to say, placing the item within braces, preceeeded by a dollar sign);
    For this specific case, matcher_array must be left empty. User-specified 
    patterns will precede internal matches. Escaped and general regex syntax 
    matches Java RegEx syntax; more information on the Java RegEx syntax found 
    here: 
    https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html
    * Example:
        { 
          "commands": 
            [ 
              {
                "input_pattern": "^SELECT * FROM users;$", 
                "output_pattern": "SELECT 1;",
                "matcher_array": []
              }, 
              {
                "input_pattern": "^ab(?<firstgroup>\\d)(?<secondgroup>\\d)$",
                "output_pattern": "second number: %s, first number: %s",
                "matcher_array": ["secondgroup", "firstgroup"] 
              },
              {
                "input_pattern": "^cd(?<firstgroup>\\d)(?<secondgroup>\\d)$",
                "output_pattern": "second number: ${secondgroup}, first number: ${firstgroup}",
                "matcher_array": [] 
              }
            ]
        }
        
        
        Input queries:
        "SELECT * FROM users;"
        "ab12"
        "cd34"

        Output queries:
        "SELECT 1;"
        "second number: 2, first number: 1"
        "second number: 4, first number: 3"
```
An example of a simple run string:

```shell
java -jar <jar-file> -p <project name> -i <instance id> -d <database name> -c
<path to credentials file> -s 5432 
```

## Details
Google Cloud Spanner PGAdapter is a simple, MITM, forward, non-transparent proxy, which translates
the PostgreSQL wire-protocol into the Cloud Spanner equivalent. It can only be used with Cloud
Spanner databases that use the [PostgreSQL interface](https://cloud.google.com/spanner/docs/postgresql-interface).
By running this proxy locally, any PostgreSQL client (including the SQL command-line client PSQL)
should function seamlessly by simply pointing its outbound port to this proxy's inbound port.
The proxy does not support all parts of the PostgreSQL wire-protocol. See [Limitations](#Limitations)
for a list of current limitations.

In addition to translation, this proxy also concerns itself with authentication
and to some extent, connection pooling. Translation for the most part is simply
a transformation of the [PostgreSQL wire protocol](https://www.postgresql.org/docs/current/protocol-message-formats.html)
except for some cases concerning PSQL, wherein the query itself is translated.

Simple query mode and extended query mode are supported, and any data type
supported by Spanner is also supported. Items, tables and language not native to
Spanner are not supported, unless otherwise specified.

Though the majority of functionality inherent in most PostgreSQL clients
(including PSQL and JDBC) are included out of the box, the following items are
not supported:
* Prepared Statement DESCRIBE
* SSL
* Functions
* COPY <table_name> TO ...
* COPY <table_name> FROM <filename | PROGRAM program>

See [COPY <table-name> FROM STDIN](#copy-support) for more information on COPY.

Only the following `psql` meta-commands are supported:
  * `\d <table>` 
  * `\dt <table>`
  * `\dn <table>`
  * `\di <table>`
  * `\l`

Other `psql` meta-commands are __not__ supported.

## COPY support
`COPY <table-name> FROM STDIN` is supported. This option can be used to insert bulk data to a Cloud
Spanner database. `COPY` operations are atomic by default, but the standard transaction limits of
Cloud Spanner apply to these transactions. That means that at most 20,000 mutations can be included
in one `COPY` operation. `COPY` can also be executed in non-atomic mode by executing the statement
`SET AUTOCOMMIT_DML_MODE='PARTITIONED_NON_ATOMIC'` before executing the copy operation.

Although only `STDIN` is supported, export files can still be imported using `COPY` by piping files
into `psql`. See the examples below.

### Atomic COPY example
```sql
create table numbers (number bigint not null primary key, name varchar);
```

```shell
cat numbers.txt | psql -h localhost -d test-db -c "copy numbers from stdin;"
```

The above operation will fail if the `numbers.txt` file contains more than 20,000 mutations.

### Non-atomic COPY example
```sql
create table numbers (number bigint not null primary key, name varchar);
```

```shell
cat numbers.txt | psql -h localhost -d test-db -c "set autocommit_dml_mode='partitioned_non_atomic'" -c "copy numbers from stdin;"
```

The above operation will automatically split the data over multiple transactions if the file
contains more than 20,000 mutations.

Note that this also means that if an error is encountered
during the `COPY` operation, some rows may already have been persisted to the database. This will
not be rolled back after the error was encountered. The transactions are executed in parallel,
which means that data after the row that caused the error in the import file can still have been
imported to the database before the `COPY` operation was halted.

### Streaming data from PostgreSQL
`COPY` can also be used to stream data directly from a real PostgreSQL database to Cloud Spanner.
This makes it easy to quickly copy an entire table from PostgreSQL to Cloud Spanner, or to generate
test data for Cloud Spanner using PostgreSQL queries.

The following examples assume that a real PostgreSQL server is running on `localhost:5432` and
PGAdapter is running on `localhost:5433`.

```shell
psql -h localhost -p 5432 -d my-local-db \
  -c "copy (select i, to_char(i, 'fm000') from generate_series(1, 10) s(i)) to stdout" \
  | psql -h localhost -p 5433 -d my-spanner-db \
  -c "copy numbers from stdin;"
```

Larger datasets require that the Cloud Spanner database is set to `PARTITIONED_NON_ATOMIC` mode:

```shell
psql -h localhost -p 5432 -d my-local-db \
  -c "copy (select i, to_char(i, 'fm000') from generate_series(1, 1000000) s(i)) to stdout" \
  | psql -h localhost -p 5433 -d my-spanner-db \
  -c "set autocommit_dml_mode='partitioned_non_atomic'" \
  -c "copy numbers from stdin;"
```

## Limitations

PGAdapter has the following known limitations at this moment:
- [PostgreSQL prepared statements](https://www.postgresql.org/docs/current/sql-prepare.html) are not
- fully supported. It is not recommended to use this feature.
  Note: This limitation relates specifically to server side PostgreSQL prepared statements.
  The JDBC `java.sql.PreparedStatement` interface is supported.
- DESCRIBE statement wire-protocol messages currently return `Oid.UNSPECIFIED` for all parameters in the query.
- DESCRIBE statement wire-protocol messages return `NoData` as the row description of the statement.
- The COPY protocol only supports COPY FROM STDIN.

## Logging

PGAdapter uses `java.util.logging` for logging. Create a `logging.properties` file to configure
logging messages. See the following example for an example to get fine-grained logging.

```
handlers=java.util.logging.ConsoleHandler,java.util.logging.FileHandler
com.google.cloud.spanner.pgadapter.level=FINEST
java.util.logging.ConsoleHandler.level=FINEST
java.util.logging.FileHandler.level=INFO
java.util.logging.FileHandler.pattern=%h/log/pgadapter-%u.log
java.util.logging.FileHandler.append=false
io.grpc.internal.level = WARNING

java.util.logging.SimpleFormatter.format=[%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS] [%4$s] (%2$s): %5$s%6$s%n
java.util.logging.FileHandler.formatter=java.util.logging.SimpleFormatter
```

Start PGAdapter with `-Djava.util.logging.config.file=logging.properties` when running PGAdapter as
a jar.

### Logging in Docker

You can configure PGAdapter to log to a file on your local system when running it in Docker by
following these steps.

1. Create a `logging.properties` on your local system like this:

```
handlers=java.util.logging.FileHandler
java.util.logging.FileHandler.level=INFO
java.util.logging.FileHandler.pattern=/home/pgadapter/log/pgadapter.log
java.util.logging.FileHandler.append=true
io.grpc.internal.level = WARNING

java.util.logging.SimpleFormatter.format=[%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS] [%4$s] (%2$s): %5$s%6$s%n
java.util.logging.FileHandler.formatter=java.util.logging.SimpleFormatter
```

2. Start the PGAdapter Docker container with these options:

```shell
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/credentials.json
export LOGGING_PROPERTIES=/full/path/to/logging.properties
docker run \
    -d -p 5432:5432 \
    -v ${GOOGLE_APPLICATION_CREDENTIALS}:${GOOGLE_APPLICATION_CREDENTIALS}:ro \
    -v ${LOGGING_PROPERTIES}:${LOGGING_PROPERTIES}:ro \
    -v /home/my-user-name/log:/home/pgadapter/log \
    -e GOOGLE_APPLICATION_CREDENTIALS \
    gcr.io/cloud-spanner-pg-adapter/pgadapter \
    -Djava.util.logging.config.file=${LOGGING_PROPERTIES} \
    -p my-project -i my-instance -d my-database \
    -x
```

The directory `/home/my-user-name/log` will be created automatically and the log file will be
placed in this directory.

## Support Level

We are not currently accepting external code contributions to this project. 
Please feel free to file feature requests using GitHub's issue tracker or 
using the existing Cloud Spanner support channels.
