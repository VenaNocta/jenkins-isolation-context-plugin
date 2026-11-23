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
package org.jenkinsci.plugins.isolationctx.provider;

import java.io.IOException;

import org.jenkinsci.plugins.isolationctx.config.NodeIsolationProperty;
import org.jenkinsci.plugins.isolationctx.handler.ComputerIsolationHandler;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.remoting.Channel;

public interface NodeIsolationHandlerProvider extends ExtensionPoint {

	public boolean isApplicable(Computer computer, Channel channel);

	public ComputerIsolationHandler buildHandler(Computer computer, Channel channel, FilePath rootPath)
			throws IOException, InterruptedException;

	public static ExtensionList<NodeIsolationHandlerProvider> all() {
		return ExtensionList.lookup(NodeIsolationHandlerProvider.class);
	}

	public static NodeIsolationHandlerProvider providerFor(@NonNull final Computer computer,
			@NonNull final Channel channel) {
		if (NodeIsolationProperty.isDisabled(computer.getNode()))
			return null;
		return all().stream()
				.filter(p -> p.isApplicable(computer, channel))
				.findFirst()
				.orElse(null);
	}
}
