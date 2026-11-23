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
import java.io.Serializable;
import hudson.FilePath;
import hudson.model.Computer;
import jenkins.security.XStreamNotDeserializable;

public interface OverlayFsHandler {

	public Computer getComputer();

	public OverlayFsMountData overlayFsCreate(FilePath overlayBasePath) throws IOException, InterruptedException;

	public void overlayFsRemove(FilePath overlayBasePath) throws IOException, InterruptedException;

	public class OverlayFsMountData implements Serializable {

		private static final long serialVersionUID = 1L;

		// /var/jenkins/workspace/<job>@overlay/
		protected final String basePath;
		// /var/jenkins/workspace/<job>@overlay/rootfs
		protected final String rootPath;
		protected String relWorkspacePath = "";

		@XStreamNotDeserializable
		protected transient OverlayFsHandler handler;

		protected OverlayFsMountData(final OverlayFsHandler handler, final FilePath overlayBasePath) {
			this.handler = handler;
			this.basePath = overlayBasePath.getRemote();
			this.rootPath = overlayBasePath.child("rootfs")
					.getRemote();
		}

		public void initHandler(final OverlayFsHandler handler) {
			this.handler = handler;
		}

		public FilePath basePath() {
			return new FilePath(this.handler.getComputer()
					.getChannel(), this.basePath);
		}

		public FilePath rootPath() {
			return new FilePath(this.handler.getComputer()
					.getChannel(), this.rootPath);
		}

		public FilePath workspacePath() {
			return rootPath().child(this.relWorkspacePath);
		}

		public void computeWorkspacePath(final FilePath wsPath) throws IOException, InterruptedException {
			this.relWorkspacePath = wsPath.absolutize()
					.getRemote()
					.substring(1);
		}
	}
}
