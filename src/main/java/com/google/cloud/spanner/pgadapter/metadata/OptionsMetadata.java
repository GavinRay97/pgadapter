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

package com.google.cloud.spanner.pgadapter.metadata;

import com.google.cloud.spanner.pgadapter.Server;
import com.google.cloud.spanner.pgadapter.utils.Credentials;
import com.google.common.base.Strings;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.json.simple.JSONObject;

/** Metadata extractor for CLI. */
public class OptionsMetadata {

  private static final Logger logger = Logger.getLogger(OptionsMetadata.class.getName());
  private static final String DEFAULT_SERVER_VERSION = "1.0.0";
  private static final String DEFAULT_USER_AGENT = "pg-adapter";

  private static final String OPTION_SERVER_PORT = "s";
  private static final String OPTION_PROJECT_ID = "p";
  private static final String OPTION_INSTANCE_ID = "i";
  private static final String OPTION_DATABASE_NAME = "d";
  private static final String OPTION_CREDENTIALS_FILE = "c";
  private static final String OPTION_BINARY_FORMAT = "b";
  private static final String OPTION_AUTHENTICATE = "a";
  private static final String OPTION_PSQL_MODE = "q";
  private static final String OPTION_JDBC_MODE = "jdbc";
  private static final String OPTION_COMMAND_METADATA_FILE = "j";
  private static final String OPTION_DISABLE_LOCALHOST_CHECK = "x";
  private static final String CLI_ARGS =
      "pgadapter -p <project> -i <instance> -d <database> -c <credentials_file>";
  private static final String OPTION_HELP = "h";
  private static final String DEFAULT_PORT = "5432";
  private static final int MIN_PORT = 0, MAX_PORT = 65535;
  /*Note: this is a private preview feature, not meant for GA version. */
  private static final String OPTION_SPANNER_ENDPOINT = "e";
  private static final String OPTION_JDBC_PROPERTIES = "r";
  private static final String OPTION_SERVER_VERSION = "v";

  private final CommandMetadataParser commandMetadataParser;
  private final String connectionURL;
  private final int proxyPort;
  private final TextFormat textFormat;
  private final boolean binaryFormat;
  private final boolean authenticate;
  private final boolean requiresMatcher;
  private final boolean replaceJdbcMetadataQueries;
  private final boolean disableLocalhostCheck;
  private final JSONObject commandMetadataJSON;
  private final Map<String, String> propertyMap;
  private final String serverVersion;

  public OptionsMetadata(String[] args) {
    CommandLine commandLine = buildOptions(args);
    this.commandMetadataParser = new CommandMetadataParser();
    this.connectionURL = buildConnectionURL(commandLine);
    this.proxyPort = buildProxyPort(commandLine);
    this.textFormat = TextFormat.POSTGRESQL;
    this.binaryFormat = commandLine.hasOption(OPTION_BINARY_FORMAT);
    this.authenticate = commandLine.hasOption(OPTION_AUTHENTICATE);
    this.requiresMatcher =
        commandLine.hasOption(OPTION_PSQL_MODE)
            || commandLine.hasOption(OPTION_COMMAND_METADATA_FILE);
    this.replaceJdbcMetadataQueries = commandLine.hasOption(OPTION_JDBC_MODE);
    this.commandMetadataJSON = buildCommandMetadataJSON(commandLine);
    this.propertyMap = parseProperties(commandLine.getOptionValue(OPTION_JDBC_PROPERTIES, ""));
    this.disableLocalhostCheck = commandLine.hasOption(OPTION_DISABLE_LOCALHOST_CHECK);
    this.serverVersion = commandLine.getOptionValue(OPTION_SERVER_VERSION, DEFAULT_SERVER_VERSION);
  }

  public OptionsMetadata(
      String connectionURL,
      int proxyPort,
      TextFormat textFormat,
      boolean forceBinary,
      boolean authenticate,
      boolean requiresMatcher,
      boolean replaceJdbcMetadataQueries,
      JSONObject commandMetadata) {
    this.commandMetadataParser = new CommandMetadataParser();
    this.connectionURL = connectionURL;
    this.proxyPort = proxyPort;
    this.textFormat = textFormat;
    this.binaryFormat = forceBinary;
    this.authenticate = authenticate;
    this.requiresMatcher = requiresMatcher;
    this.replaceJdbcMetadataQueries = replaceJdbcMetadataQueries;
    this.commandMetadataJSON = commandMetadata;
    this.propertyMap = new HashMap<>();
    this.disableLocalhostCheck = false;
    this.serverVersion = DEFAULT_SERVER_VERSION;
  }

  private Map<String, String> parseProperties(String propertyOptions) {
    Map<String, String> properties = new HashMap<>();
    if (!propertyOptions.isEmpty()) {
      String[] propertyList = propertyOptions.split(";");
      for (int i = 0; i < propertyList.length; ++i) {
        String[] keyValue = propertyList[i].split("=");
        if (keyValue.length == 2) {
          properties.put(keyValue[0], keyValue[1]);
        } else {
          throw new IllegalArgumentException("Invalid JDBC property specified: " + propertyOptions);
        }
      }
    }
    return properties;
  }

  /**
   * Takes the proxy port option result and parses it accordingly to fit port specs.
   *
   * @param commandLine The parsed options for CLI
   * @return The designated port if any, otherwise the default port.
   */
  private int buildProxyPort(CommandLine commandLine) {
    int port = Integer.parseInt(commandLine.getOptionValue(OPTION_SERVER_PORT, DEFAULT_PORT));
    if (port < MIN_PORT || port > MAX_PORT) {
      throw new IllegalArgumentException("Port must be between " + MIN_PORT + " and " + MAX_PORT);
    }
    return port;
  }

  /**
   * Get credential file path from either command line or application default. If neither throw
   * error.
   *
   * @param commandLine The parsed options for CLI
   * @return The absolute path of the credentials file.
   */
  private String buildCredentialsFile(CommandLine commandLine) {
    if (!commandLine.hasOption(OPTION_CREDENTIALS_FILE)) {
      String credentialsPath = Credentials.getApplicationDefaultCredentialsFilePath();
      if (credentialsPath == null) {
        throw new IllegalArgumentException(
            "User must specify a valid credential file, "
                + "or have application default credentials set-up.");
      }
      return credentialsPath;
    }
    return commandLine.getOptionValue(OPTION_CREDENTIALS_FILE);
  }

  /**
   * Takes user inputs and builds a JDBC connection string from them.
   *
   * @param commandLine The parsed options for CLI
   * @return The parsed JDBC connection string.
   */
  private String buildConnectionURL(CommandLine commandLine) {
    String host = commandLine.getOptionValue(OPTION_SPANNER_ENDPOINT, "");
    String jdbcEndpoint;
    if (host.isEmpty()) {
      jdbcEndpoint = "jdbc:cloudspanner:/";
    } else {
      jdbcEndpoint = "jdbc:cloudspanner://" + host + "/";
      logger.log(
          Level.INFO,
          () ->
              String.format(
                  "PG Adapter will connect to the following Cloud Spanner service endpoint %s",
                  host));
    }

    // Note that Credentials here is the credentials file, not the actual credentials
    String url =
        String.format(
            jdbcEndpoint
                + "projects/%s/"
                + "instances/%s/"
                + "databases/%s"
                + ";dialect=postgresql"
                + ";userAgent=%s",
            commandLine.getOptionValue(OPTION_PROJECT_ID),
            commandLine.getOptionValue(OPTION_INSTANCE_ID),
            commandLine.getOptionValue(OPTION_DATABASE_NAME),
            DEFAULT_USER_AGENT);

    String credentials = buildCredentialsFile(commandLine);
    if (!Strings.isNullOrEmpty(credentials)) {
      url = String.format("%s;credentials=%s", url, credentials);
    }

    return url;
  }

  /**
   * Takes the content of the specified (or default) command file and parses it into JSON format. If
   * finding the file fails in any-way, print an error and keep going with an empty spec. Custom
   * metadata json file is allowed without PSQL mode.
   *
   * @param commandLine The parsed options for CLI
   * @return The JSON object corresponding to the string contained within the specified (or default)
   *     command file.
   */
  private JSONObject buildCommandMetadataJSON(CommandLine commandLine) {
    if (commandLine.hasOption(OPTION_COMMAND_METADATA_FILE)
        && commandLine.hasOption(OPTION_PSQL_MODE)) {
      throw new IllegalArgumentException(
          "PSQL Mode shouldn't be toggled (-q) together with the custom command metadata file"
              + " (-j).");
    }

    final String commandMetadataFileName = commandLine.getOptionValue(OPTION_COMMAND_METADATA_FILE);
    try {
      if (commandMetadataFileName != null) {
        return commandMetadataParser.parse(commandMetadataFileName);
      } else {
        return commandMetadataParser.defaultCommands();
      }
    } catch (IOException e) {
      System.err.printf(
          "Specified command metadata file %s not found! Ignoring commands metadata file.%n",
          commandMetadataFileName);

      try {
        return commandMetadataParser.emptyCommands();
      } catch (IOException | org.json.simple.parser.ParseException ex) {
        throw new IllegalArgumentException(
            "Something went wrong! Processing empty JSON file failed!", ex);
      }
    } catch (org.json.simple.parser.ParseException e) {
      throw new IllegalArgumentException(
          "Unable to process provided JSON file: " + commandMetadataFileName, e);
    }
  }

  /**
   * Simple setup for command line option parsing.
   *
   * @param args user's CLI args
   * @return The parsed command line options.
   */
  private CommandLine buildOptions(String[] args) {
    Options options = new Options();
    options.addOption(
        OPTION_SERVER_PORT, "server-port", true, "This proxy's port number (Default 5432).");
    options.addRequiredOption(
        OPTION_PROJECT_ID,
        "project",
        true,
        "The id of the GCP project wherein lives the Spanner database.");
    options.addRequiredOption(
        OPTION_INSTANCE_ID,
        "instance",
        true,
        "The id of the Spanner instance within the GCP project.");
    options.addRequiredOption(
        OPTION_DATABASE_NAME,
        "database",
        true,
        "The name of the Spanner database within the GCP project.");
    options.addOption(
        OPTION_CREDENTIALS_FILE,
        "credentials-file",
        true,
        "The full path of the file location wherein lives the GCP credentials."
            + "If not specified, will try to read application default credentials.");
    options.addOption(
        OPTION_SPANNER_ENDPOINT, "spanner-endpoint", true, "The Cloud Spanner service endpoint.");
    options.addOption(
        OPTION_AUTHENTICATE,
        "authenticate",
        false,
        "Whether you wish the proxy to perform an authentication step.");
    options.addOption(
        OPTION_PSQL_MODE,
        "psql-mode",
        false,
        "This option turns on PSQL mode. This mode allows better compatibility to PSQL, with an"
            + " added performance cost. PSQL mode is implemented using predefined dynamic matchers"
            + " and as such cannot be used with the option -j. This mode should not be used for"
            + " production, and we do not guarantee its functionality beyond the basics.");
    options.addOption(
        OPTION_JDBC_MODE,
        "jdbc-mode",
        false,
        "This option turns on JDBC mode. This mode allows better compatibility with the "
            + "PostgreSQL JDBC driver. It will automatically inspect incoming queries to look for "
            + "known JDBC metadata queries, and replace these with queries that are compatible with "
            + "Cloud Spanner. JDBC mode is implemented using predefined fixed matchers and should "
            + "not be used in combination with options -q (psql mode) or -j (custom matchers). It "
            + "should be enabled if you intend to connect to PGAdapter using the PostgreSQL JDBC "
            + "driver.");
    options.addOption(
        OPTION_COMMAND_METADATA_FILE,
        "options-metadata",
        true,
        "This option specifies the full path of the file containing the metadata specifications for"
            + " custom dynamic matchers. Each item in this matcher will create a runtime-generated"
            + " command which will translate incoming commands into whatever back-end SQL is"
            + " desired. This feature allows re-writing queries that are outside the user's control"
            + " (e.g: issued by client libraries and / or tools) and are not currently supported by"
            + " the backend with equivalent supported queries, but comes at a performance cost."
            + " This option cannot be used with option -q.");
    options.addOption(OPTION_HELP, "help", false, "Print help.");
    options.addOption(
        OPTION_BINARY_FORMAT,
        "force-binary-format",
        false,
        "Force the server to send data back in binary PostgreSQL format when no specific "
            + "format has been requested. The PostgreSQL wire protocol specifies that the server "
            + "should send data in text format in those cases. This setting overrides this default "
            + "and should be used with caution, for example for testing purposes, as clients might "
            + "not accept this behavior. This setting only affects query results in extended query "
            + "mode. Queries in simple query mode will always return results in text format. If "
            + "you do not know what extended query mode and simple query mode is, then you should "
            + "probably not be using this setting.");
    options.addOption(
        OPTION_JDBC_PROPERTIES,
        "jdbc-properties",
        true,
        "This option specifies additional properties that will be used with the JDBC connection. "
            + "They should be in the format <key1>=<value1>;<key2>=<value2>;...");
    options.addOption(
        OPTION_DISABLE_LOCALHOST_CHECK,
        "disable-localhost-check-for-docker",
        false,
        "By default, for safety, PG Adapter only accepts connections from localhost. "
            + "When running inside docker however the docker host IP will not show as localhost. "
            + "Instead, set this flag and restrict connections from localhost using docker, e.g: "
            + "`-p 127.0.0.1:5432:5432`");
    options.addOption(
        OPTION_SERVER_VERSION,
        "server-version",
        true,
        "This option specifies what server_version PG Adapter should claim to be. If not specified "
            + " it will default to version "
            + DEFAULT_SERVER_VERSION);

    CommandLineParser parser = new DefaultParser();
    HelpFormatter help = new HelpFormatter();
    try {
      CommandLine commandLine = parser.parse(options, args);
      if (commandLine.hasOption(OPTION_HELP)) {
        help.printHelp(CLI_ARGS, options);
        System.exit(0);
      }
      return commandLine;
    } catch (ParseException e) {
      help.printHelp(CLI_ARGS, options);
      throw new IllegalArgumentException(e.getMessage());
    }
  }

  public boolean isBinaryFormat() {
    return this.binaryFormat;
  }

  public JSONObject getCommandMetadataJSON() {
    return this.commandMetadataJSON;
  }

  public String getConnectionURL() {
    return this.connectionURL;
  }

  public int getProxyPort() {
    return this.proxyPort;
  }

  public TextFormat getTextFormat() {
    return this.textFormat;
  }

  public boolean shouldAuthenticate() {
    return this.authenticate;
  }

  public boolean requiresMatcher() {
    return this.requiresMatcher;
  }

  public boolean isReplaceJdbcMetadataQueries() {
    return this.replaceJdbcMetadataQueries;
  }

  public boolean disableLocalhostCheck() {
    return this.disableLocalhostCheck;
  }

  public Map<String, String> getPropertyMap() {
    return this.propertyMap;
  }

  public String getServerVersion() {
    return serverVersion;
  }

  /**
   * The PostgreSQL wire protocol can send data in both binary and text format. When using text
   * format, the {@link Server} will normally send output back to the client using a format
   * understood by PostgreSQL clients. If you are using the server with a text-only client that does
   * not try to interpret the data that is returned by the server, such as for example psql, then it
   * is advisable to use Cloud Spanner formatting. The server will then return all data in a format
   * understood by Cloud Spanner.
   *
   * <p>The default format used by the server is {@link TextFormat}.
   */
  public enum TextFormat {
    /**
     * The default format. Data is returned to the client in a format that PostgreSQL clients should
     * be able to understand and stringParse. Use this format if you are using the {@link Server}
     * with a client that tries to interpret the data that is returned by the server, such as for
     * example the PostgreSQL JDBC driver.
     */
    POSTGRESQL,
    /**
     * Data is returned to the client in Cloud Spanner format. Use this format if you are using the
     * server with a text-only client, such as psql, that does not try to interpret and stringParse
     * the data that is returned.
     */
    SPANNER
  }
}
