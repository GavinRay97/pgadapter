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

package com.google.cloud.spanner.pgadapter.commands;

import java.util.regex.Pattern;

/**
 * Fallthrough Command which should match most meta-commands. We are expected to run this last in
 * the matching logic to present the user with better error statements.
 */
public class InvalidMetaCommand extends Command {

  private static final Pattern INPUT_REGEX = Pattern.compile(".*pg_catalog.*");

  public InvalidMetaCommand(String sql) {
    super(sql);
  }

  @Override
  public Pattern getPattern() {
    return INPUT_REGEX;
  }

  @Override
  public String translate() {
    throw new IllegalArgumentException("Unsupported Meta Command");
  }
}
