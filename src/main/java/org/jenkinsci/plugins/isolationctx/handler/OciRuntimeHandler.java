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
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jenkinsci.plugins.isolationctx.dto.LnxIdentity;
import org.jenkinsci.plugins.isolationctx.dto.LnxMountEntry;
import org.jenkinsci.plugins.isolationctx.dto.OciMountEntry;

import hudson.FilePath;
import hudson.model.TaskListener;

public interface OciRuntimeHandler {

	public OciBundleConfig ociBundleDefaultConfig(LnxIdentity identity, String hostname, TaskListener listener);

	public long ociRuntimeStart(FilePath bundlePath, String containerId, TaskListener listener)
			throws IOException, InterruptedException;

	public boolean ociRuntimeKill(long processId, String containerId, TaskListener listener)
			throws IOException, InterruptedException;

	public boolean ociRuntimeMountsAdd(long processId, String containerId, Set<LnxMountEntry> configs,
			TaskListener listener) throws IOException, InterruptedException;

	public boolean ociRuntimeMountsRemove(long processId, String containerId, Set<LnxMountEntry> configs,
			TaskListener listener) throws IOException, InterruptedException;

	public static boolean supported(final Object handler) {
		return handler instanceof OciRuntimeHandler;
	}

	public static class OciBundleConfig {

		public static final String OCI_VERSION = "1.0.2";

		protected final List<OciMountEntry> mounts = new LinkedList<>();

		protected final LnxIdentity identity;
		protected final String hostname;

		protected String rootfs = "/";

		public OciBundleConfig(final LnxIdentity identity, final String hostname) {
			this.identity = identity;
			this.hostname = hostname;
		}

		public Stream<OciMountEntry> getMountEntriesAsStream() {
			return this.mounts.stream();
		}

		public OciBundleConfig setRootFs(final String rootfs) {
			this.rootfs = rootfs;
			return this;
		}

		public OciBundleConfig addProcMount() {
			this.mounts.add(new LnxMountEntry("proc", "proc", "/proc"));
			return this;
		}

		public OciBundleConfig addTmpfsMount(final String destination, final String... options) {
			this.mounts.add(new LnxMountEntry("tmpfs", "tmpfs", destination).options(options));
			return this;
		}

		public OciBundleConfig addBindMount(final String source, final String destination, final String... options) {
			this.mounts.add(new LnxMountEntry("bind", source, destination).options(options));
			return this;
		}

		public OciBundleConfig addMount(final OciMountEntry mountEntry) {
			this.mounts.add(mountEntry);
			return this;
		}

		public void writeTo(final FilePath targetPath) throws IOException, InterruptedException {
			targetPath.write(toJson(), StandardCharsets.UTF_8.name());
		}

		public String toJson() {
			return "{\"ociVersion\":\"" + OCI_VERSION + "\"," //
					+ "\"process\":{\"terminal\": false," //
					+ "\"user\":{\"uid\":" + this.identity.getUserId() + ",\"gid\":" + this.identity.getGroupId() + "}," //
					+ "\"args\":[\"/usr/bin/tail\",\"-f\",\"/dev/null\"],\"env\":[],\"cwd\":\"/\"}," //
					+ "\"root\":{\"path\":\"" + this.rootfs + "\",\"readonly\":false}," //
					+ "\"mounts\":[" //
					+ this.mounts.stream()
							.map(OciMountEntry::toOciJson)
							.collect(Collectors.joining(",")) //
					+ "],\"hostname\":\"" + this.hostname //
					+ "\",\"linux\":{\"namespaces\":[{\"type\":\"uts\"},{\"type\":\"ipc\"}," //
					+ "{\"type\":\"network\"},{\"type\":\"pid\"},{\"type\":\"mount\"}]}}\n";
		}
	}
}
