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

import org.jenkinsci.plugins.isolationctx.handler.ScriptedLnxIsolationHandler;
import org.jenkinsci.plugins.isolationctx.config.IsolationRuntime;
import org.jenkinsci.plugins.isolationctx.config.NodeIsolationProperty;
import org.jenkinsci.plugins.isolationctx.config.ScriptedLnxIsolationRuntime;
import org.jenkinsci.plugins.isolationctx.handler.ComputerIsolationHandler;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.remoting.Channel;
import hudson.util.RemotingDiagnostics;

@Extension
public class ScriptedLnxIsolationHandlerProvider implements NodeIsolationHandlerProvider {

	@Override
	public boolean isApplicable(final Computer computer, final Channel channel) {
		if (computer == null)
			return false;
		final IsolationRuntime runtime = NodeIsolationProperty.getIsolationRuntime(computer.getNode());
		if (!(runtime instanceof ScriptedLnxIsolationRuntime))
			return false;
		try {
			final Object value = RemotingDiagnostics.getSystemProperties(channel)
					.get("os.name");
			if (value instanceof String os) {
				return os.toLowerCase()
						.contains("linux");
			}
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
		return false;
	}

	@Override
	public ComputerIsolationHandler buildHandler(final Computer computer, final Channel channel,
			final FilePath rootPath) throws IOException, InterruptedException {
		return new ScriptedLnxIsolationHandler(computer, channel, rootPath);
	}
}
