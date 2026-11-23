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
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.isolationctx.IsolationContext;
import org.jenkinsci.plugins.isolationctx.LinuxNamespaceContext;
import org.jenkinsci.plugins.isolationctx.UnsupportedContext;
import org.jenkinsci.plugins.isolationctx.dto.TrafficMap;
import org.jenkinsci.plugins.workflow.graph.FlowNode;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.remoting.VirtualChannel;
import hudson.util.LogTaskListener;
import hudson.util.StreamTaskListener;
import jenkins.util.io.OnMaster;

public class UnsupportedIsolationHandler implements ComputerIsolationHandler, OnMaster {

	private static final Logger LOGGER = Logger.getLogger(LinuxNamespaceContext.class.getName());

	@NonNull
	protected final Channel channel;
	@NonNull
	protected final String _logPath;

	@CheckForNull
	protected TaskListener logListener;

	public UnsupportedIsolationHandler(final Computer computer, final Channel channel, final FilePath rootPath)
			throws IOException, InterruptedException {
		// NOTE: retain Channel as setup runs before channel is persisted in Computer
		Objects.requireNonNull(this.channel = channel);

		this._logPath = rootPath.child("logs/NodeIsolation.log")
				.absolutize()
				.getRemote();
	}

	@Override
	public boolean isActive() {
		return false;
	}

	protected final FilePath logPath() {
		return new FilePath(this.channel, this._logPath);
	}

	@Override
	public synchronized TaskListener logTaskListener() {
		if (this.logListener != null)
			return this.logListener;
		try {
			return this.logListener = new StreamTaskListener(logPath().write(), StandardCharsets.UTF_8);
		} catch (IOException | InterruptedException e) {
			final TaskListener listener = new LogTaskListener(LOGGER, Level.INFO);
			listener.error("Failed to create Remote-Logger, redirection activated!");
			e.printStackTrace(listener.getLogger());
			return this.logListener = listener;
		}
	}

	@Override
	public void setup(final VirtualChannel channel, final TaskListener listener)
			throws IOException, InterruptedException {
	}

	@Override
	public void shutdown(final VirtualChannel channel, final TaskListener listener)
			throws IOException, InterruptedException {
	}

	@Override
	public IsolationContext createIsolationContext(final Run<?, ?> run, final FlowNode flowNode,
			final Computer computer, final TaskListener listener) {
		return new UnsupportedContext();
	}

	@Override
	public void firewallInputOpen(final TrafficMap data, final TaskListener listener) {
	}

	@Override
	public void firewallInputRetainOpen(final TrafficMap data, final TaskListener listener) {
	}

	@Override
	public void firewallInputClose(final TrafficMap data, final TaskListener listener) {
	}

	@Override
	public void firewallInputCloseAll(final TaskListener listener) throws IOException, InterruptedException {
	}
}
