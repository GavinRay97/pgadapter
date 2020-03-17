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

package com.google.cloud.spanner.pgadapter.wireprotocol;

import com.google.cloud.spanner.pgadapter.ConnectionHandler;
import java.io.DataInputStream;

/**
 * Handles a flush command from the user. JDBC does not require this step, so server-side this is a
 * noop.
 */
public class FlushMessage extends WireMessage {

  public FlushMessage(ConnectionHandler connection, DataInputStream input) throws Exception {
    super(connection, input);
  }

  @Override
  public void send() throws Exception {
    this.connection.handleFlush();
  }
}
