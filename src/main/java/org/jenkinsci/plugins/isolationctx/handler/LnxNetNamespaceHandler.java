/*
 * Copyright © 2026 VenaNocta (venanocta@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jenkinsci.plugins.isolationctx.handler;

import java.io.IOException;

import org.jenkinsci.plugins.isolationctx.dto.net.LnxNetInterface;
import org.jenkinsci.plugins.isolationctx.dto.net.LnxNetLink;

public interface LnxNetNamespaceHandler {

	public boolean ctxCreateLoopbackIface(long processId, LnxNetInterface netInterface)
			throws IOException, InterruptedException;

	public boolean ctxConnectToHost(long processId, LnxNetLink netLink) throws IOException, InterruptedException;

	public boolean ctxDisconnectFromHost(long processId, LnxNetLink netLink) throws IOException, InterruptedException;

}
