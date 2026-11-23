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
import java.io.Serializable;

import org.jenkinsci.plugins.isolationctx.dto.net.NetInterface;
import org.jenkinsci.plugins.workflow.graph.FlowNode;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.FilePath;
import hudson.LauncherDecorator;
import hudson.FilePath.FileCallable;
import hudson.remoting.Future;

public interface IsolationContext extends Serializable {

	public void onLoad(FlowNode parent);

	public LauncherDecorator launcherDecorator();

	public void setup() throws IOException, InterruptedException;

	public void destroy() throws IOException, InterruptedException;

	@CheckForNull
	public FilePath getWorkspace();

	public <T> T actInContext(FileCallable<T> callable) throws IOException, InterruptedException;

	public <T> Future<T> actInContextAsync(FileCallable<T> callable) throws IOException, InterruptedException;

	// try to mount a path that is a child of the $JENKINS_HOME/workspace/ path
	public boolean mountWorkspacePath(FilePath workspacePath) throws IOException, InterruptedException;

	// try to unmount a path that is a child of the $JENKINS_HOME/workspace/ path
	public boolean unmountWorkspacePath(FilePath workspacePath) throws IOException, InterruptedException;

	public NetInterface connectNetInterface() throws IOException, InterruptedException;

	public void disconnectNetInterface(NetInterface netInterface) throws IOException, InterruptedException;

}
