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
package org.jenkinsci.plugins.isolationctx;

import java.io.IOException;
import org.jenkinsci.plugins.isolationctx.dto.net.DefaultNetInterface;
import org.jenkinsci.plugins.isolationctx.dto.net.NetInterface;
import org.jenkinsci.plugins.workflow.graph.FlowNode;

import hudson.FilePath.FileCallable;
import hudson.FilePath;
import hudson.LauncherDecorator;
import hudson.remoting.Future;

public class UnisolatedContext implements IsolationContext {

	private static final long serialVersionUID = 1L;

	public UnisolatedContext() {
	}

	@Override
	public void onLoad(final FlowNode parent) {
	}

	@Override
	public LauncherDecorator launcherDecorator() {
		// NOOP
		return null;
	}

	@Override
	public void setup() {
	}

	@Override
	public void destroy() {
	}

	@Override
	public FilePath getWorkspace() {
		return null;
	}

	@Override
	public <T> T actInContext(final FileCallable<T> callable) throws IOException, InterruptedException {
		throw new UnsupportedOperationException();
	}

	@Override
	public <T> Future<T> actInContextAsync(final FileCallable<T> callable) throws IOException, InterruptedException {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean mountWorkspacePath(final FilePath workspacePath) throws IOException, InterruptedException {
		return true;
	}

	@Override
	public boolean unmountWorkspacePath(final FilePath workspacePath) throws IOException, InterruptedException {
		return true;
	}

	@Override
	public NetInterface connectNetInterface() {
		return DefaultNetInterface.createLoopback(null, true, true);
	}

	@Override
	public void disconnectNetInterface(NetInterface iface) throws IOException, InterruptedException {
	}
}
