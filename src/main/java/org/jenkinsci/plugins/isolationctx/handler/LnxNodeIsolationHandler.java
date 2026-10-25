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
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.jenkinsci.plugins.isolationctx.Scripts;
import org.jenkinsci.plugins.isolationctx.ctx.LinuxNodeNamespaceContext;
import org.jenkinsci.plugins.isolationctx.dto.LinuxMountEntry;
import org.jenkinsci.plugins.isolationctx.dto.TrafficMap;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import hudson.util.LogTaskListener;
import hudson.util.StreamTaskListener;
import jenkins.util.io.OnMaster;

public class LnxNodeIsolationHandler implements NodeIsolationHandler, OnMaster, OciRuntimeHandler {

	private static final Logger LOGGER = Logger.getLogger(LinuxNodeNamespaceContext.class.getName());

	protected final AtomicBoolean active = new AtomicBoolean(false);

	@NonNull
	protected final Computer computer;
	@NonNull
	protected final String _logPath;
	@NonNull
	protected final String _rootPath;
	@NonNull
	protected final String _dataRootPath;
	@NonNull
	protected final String _firewallToolPath;
	@NonNull
	protected final String _mountToolPath;
	@NonNull
	protected final String _ociRuntimeToolPath;

	@CheckForNull
	protected TaskListener logListener;

	public LnxNodeIsolationHandler(@NonNull final Computer computer) throws IOException, InterruptedException {
		// NOTE: never persist Node
		// -> it's a DTO and is updated on every Computer reconnect!
		Objects.requireNonNull(this.computer = computer);

		final FilePath rootPath = this.computer.getNode()
				.getRootPath();
		this._logPath = rootPath.child("logs/NodeIsolation.log")
				.absolutize()
				.getRemote();
		this._rootPath = rootPath.child("isolation-context")
				.absolutize()
				.getRemote();
		this._dataRootPath = "data";
		this._firewallToolPath = "firewall-tool.py";
		this._mountToolPath = "mount-tool.py";
		this._ociRuntimeToolPath = "oci-runtime-tool.sh";
	}

	@Override
	public boolean isActive() {
		return this.active.get();
	}

	protected final FilePath logPath() {
		return new FilePath(this.computer.getChannel(), this._logPath);
	}

	protected final FilePath rootPath() {
		return new FilePath(this.computer.getChannel(), this._rootPath);
	}

	protected final FilePath dataRootPath() {
		return rootPath().child(this._dataRootPath);
	}

	protected final FilePath firewallToolPath() {
		return rootPath().child(this._firewallToolPath);
	}

	protected final FilePath mountToolPath() {
		return rootPath().child(this._mountToolPath);
	}

	protected final FilePath ociRuntimeToolPath() {
		return rootPath().child(this._ociRuntimeToolPath);
	}

	public final Launcher createLauncher(final TaskListener listener) {
		return this.computer.getNode()
				.createLauncher(listener);
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

	protected void execScriptTask(final Collection<String> tasks, final TaskListener listener)
			throws IOException, InterruptedException {
		// deploy file from resources -> path
		final FilePath networkPath = rootPath().child("network-tool.py");
		// jar:/scripts/network/elinux-python3.py
		Scripts.NETWORK__ENTERPRISE_LINUX_PYTHON3.deployTo(networkPath);
		// jar:/scripts/firewall/nftables-python3.py
		Scripts.FIREWALL__NFTABLES_PYTHON3.deployTo(firewallToolPath());
		// jar:/scripts/data/linux-mount-python3.py
		Scripts.DATA__LINUX_MOUNT_PYTHON3.deployTo(mountToolPath());
		// jar:/scripts/runtime/linux-crun.sh
		Scripts.RUNTIME__LINUX_CRUN_SHELL.deployTo(ociRuntimeToolPath());

		final Launcher launcher = createLauncher(listener);
		// network setup
		launcher.launch()
				.cmds(new ArgumentListBuilder("/usr/bin/sudo", "/usr/bin/python3", networkPath.absolutize()
						.getRemote()).add(tasks))
				.start()
				.joinWithTimeout(30, TimeUnit.SECONDS, listener);
		// firewall setup
		launcher.launch()
				.cmds(new ArgumentListBuilder("/usr/bin/sudo", "/usr/bin/python3", firewallToolPath().absolutize()
						.getRemote()).add(tasks))
				.start()
				.joinWithTimeout(30, TimeUnit.SECONDS, listener);
		// mount isolation setup
		launcher.launch()
				.cmds(new ArgumentListBuilder("/usr/bin/sudo", "/usr/bin/python3", mountToolPath().absolutize()
						.getRemote()).add(tasks))
				.start()
				.joinWithTimeout(30, TimeUnit.SECONDS, listener);
		// runtime setup
		launcher.launch()
				.cmds(new ArgumentListBuilder("/usr/bin/sudo", "/usr/bin/bash", ociRuntimeToolPath().absolutize()
						.getRemote()).add(tasks))
				.start()
				.joinWithTimeout(30, TimeUnit.SECONDS, listener);
	}

	@Override
	public void setup(final TaskListener listener) throws IOException, InterruptedException {
		try {
			execScriptTask(Arrays.asList("setup"), listener);
			this.active.set(true);
		} catch (Throwable e) {
			this.active.set(false);
			throw e;
		}
	}

	@Override
	public void shutdown(final TaskListener listener) throws IOException, InterruptedException {
		try {
			execScriptTask(Arrays.asList("shutdown"), listener);
			this.active.set(false);
		} catch (Throwable e) {
			throw e;
		}
	}

	protected void networkHostInterfaceExec(final Set<String> data, final String operation, final TaskListener listener)
			throws IOException, InterruptedException {
		dataRootPath().mkdirs();
		final FilePath tmpFilePath = dataRootPath().createTempFile("hostif-", ".csv");
		try {
			try (OutputStream stream = tmpFilePath.write()) {
				for (String hostif : data) {
					stream.write(String.format("%s\n", hostif)
							.getBytes(StandardCharsets.UTF_8));
				}
				stream.flush();
			}
			final Launcher launcher = createLauncher(listener);
			launcher.launch()
					.cmds(new ArgumentListBuilder("/usr/bin/sudo", "/usr/bin/python3", firewallToolPath().absolutize()
							.getRemote()).add("set", "hostif", operation, tmpFilePath.absolutize()
									.getRemote()))
					.start()
					.joinWithTimeout(30, TimeUnit.SECONDS, listener);
		} finally {
			tmpFilePath.delete();
		}
	}

	public void networkHostInterfaceAdd(final Set<String> data, final TaskListener listener)
			throws IOException, InterruptedException {
		if (data == null || data.isEmpty())
			return;
		networkHostInterfaceExec(data, "add", listener);
	}

	public void networkHostInterfaceRetain(final Set<String> data, final TaskListener listener)
			throws IOException, InterruptedException {
		if (data == null || data.isEmpty())
			return;
		networkHostInterfaceExec(data, "set", listener);
	}

	public void networkHostInterfaceRemove(final Set<String> data, final TaskListener listener)
			throws IOException, InterruptedException {
		if (data == null || data.isEmpty())
			return;
		networkHostInterfaceExec(data, "del", listener);
	}

	public void networkHostInterfaceRemoveAll(final TaskListener listener) throws IOException, InterruptedException {
		final Launcher launcher = createLauncher(listener);
		launcher.launch()
				.cmds(new ArgumentListBuilder("/usr/bin/sudo", "/usr/bin/python3", firewallToolPath().absolutize()
						.getRemote()).add("set", "hostif", "clear"))
				.start()
				.joinWithTimeout(30, TimeUnit.SECONDS, listener);
	}

	protected void firewallInputExec(final TrafficMap data, final String operation, final TaskListener listener)
			throws IOException, InterruptedException {
		dataRootPath().mkdirs();
		final FilePath tmpFilePath = dataRootPath().createTempFile("traffic-", ".csv");
		try {
			try (OutputStream stream = tmpFilePath.write()) {
				data.forEach((host, external, protoMap) -> {
					if (host == null || external == null || protoMap == null || protoMap.isEmpty())
						return;
					for (Map.Entry<TrafficMap.Protocol, Set<Integer>> entry : protoMap.entrySet()) {
						final TrafficMap.Protocol protocol = entry.getKey();
						final Set<Integer> ports = entry.getValue();
						if (protocol == null || ports == null || ports.isEmpty())
							continue;
						for (Integer port : ports) {
							stream.write(String
									.format("%d|%s|%s|%s|%d\n", host.getIpFamily(), host.toCidrNotation(),
											external.toCidrNotation(), protocol.toString(), port)
									.getBytes(StandardCharsets.UTF_8));
						}
					}
					stream.flush();
				});
				stream.flush();
			}
			createLauncher(listener).launch()
					.cmds(new ArgumentListBuilder("/usr/bin/sudo", "/usr/bin/python3", firewallToolPath().absolutize()
							.getRemote()).add("set", "traffic-port", operation, tmpFilePath.absolutize()
									.getRemote()))
					.start()
					.joinWithTimeout(30, TimeUnit.SECONDS, listener);
		} finally {
			tmpFilePath.delete();
		}
	}

	@Override
	public void firewallInputOpen(final TrafficMap data, final TaskListener listener)
			throws IOException, InterruptedException {
		if (data == null || data.isEmpty())
			return;
		firewallInputExec(data, "add", logTaskListener());
	}

	@Override
	public void firewallInputRetainOpen(final TrafficMap data, final TaskListener listener)
			throws IOException, InterruptedException {
		if (data == null || data.isEmpty())
			return;
		firewallInputExec(data, "set", logTaskListener());
	}

	@Override
	public void firewallInputClose(final TrafficMap data, final TaskListener listener)
			throws IOException, InterruptedException {
		if (data == null || data.isEmpty())
			return;
		firewallInputExec(data, "del", logTaskListener());
	}

	@Override
	public void firewallInputCloseAll(final TaskListener listener) throws IOException, InterruptedException {
		final TaskListener nodeLog = logTaskListener();
		final Launcher launcher = createLauncher(nodeLog);
		launcher.launch()
				.cmds(new ArgumentListBuilder("/usr/bin/sudo", "/usr/bin/python3", firewallToolPath().absolutize()
						.getRemote()).add("set", "traffic-port", "clear"))
				.start()
				.joinWithTimeout(30, TimeUnit.SECONDS, nodeLog);
	}

	protected void mountToolExec(final List<String> operations, final TaskListener listener)
			throws IOException, InterruptedException {
		final Launcher launcher = createLauncher(listener);
		launcher.launch()
				.cmds(new ArgumentListBuilder("/usr/bin/sudo", "/usr/bin/python3", mountToolPath().absolutize()
						.getRemote()).add(operations))
				.start()
				.joinWithTimeout(30, TimeUnit.SECONDS, listener);
	}

	public OverlayMountData overlayCreate(final FilePath overlayBasePath) throws IOException, InterruptedException {
		final TaskListener nodeLog = logTaskListener();
		mountToolExec(Arrays.asList("mount", "overlay", "create", overlayBasePath.absolutize()
				.getRemote()), nodeLog);
		final OverlayMountData overlay = new OverlayMountData(this, overlayBasePath);
		return overlay;
	}

	public void overlayRemove(final FilePath overlayBasePath) throws IOException, InterruptedException {
		final TaskListener nodeLog = logTaskListener();
		mountToolExec(Arrays.asList("mount", "overlay", "cleanup", overlayBasePath.absolutize()
				.getRemote()), nodeLog);

	}

	public static class OverlayMountData implements Serializable {

		private static final long serialVersionUID = 1L;

		protected final Set<String> bindMounts = new LinkedHashSet<>();

		// /var/jenkins/workspace/<job>@overlay/
		protected final String basePath;
		// /var/jenkins/workspace/<job>@overlay/rootfs
		protected final String rootPath;
		protected String relWorkspacePath = "";

		protected transient LnxNodeIsolationHandler handler;

		protected OverlayMountData(final LnxNodeIsolationHandler handler, final FilePath overlayBasePath) {
			this.handler = handler;
			this.basePath = overlayBasePath.getRemote();
			this.rootPath = overlayBasePath.child("rootfs")
					.getRemote();
		}

		public void initHandler(final LnxNodeIsolationHandler handler) {
			this.handler = handler;
		}

		public FilePath basePath() {
			return new FilePath(this.handler.computer.getChannel(), this.basePath);
		}

		public FilePath rootPath() {
			return new FilePath(this.handler.computer.getChannel(), this.rootPath);
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

	protected int ociRuntimeToolExec(final List<String> operations, final TaskListener listener)
			throws IOException, InterruptedException {
		final Launcher launcher = createLauncher(listener);
		return launcher.launch()
				.cmds(new ArgumentListBuilder("/usr/bin/sudo", "/usr/bin/bash", ociRuntimeToolPath().absolutize()
						.getRemote()).add(operations))
				.start()
				.joinWithTimeout(20, TimeUnit.SECONDS, listener);
	}

	@Override
	public long ociRuntimeStart(final FilePath bundlePath, final String containerId, final TaskListener listener)
			throws IOException, InterruptedException {
		final TaskListener nodeLog = logTaskListener();
		final FilePath pidFilePath = bundlePath.child("handle.pid")
				.absolutize();

		ociRuntimeToolExec(Arrays.asList("run", containerId, bundlePath.absolutize()
				.getRemote(), pidFilePath.getRemote()), nodeLog);

		return Long.parseLong(pidFilePath.readToString());
	}

	@Override
	public boolean ociRuntimeKill(final long processId, final String containerId, final TaskListener listener)
			throws IOException, InterruptedException {
		final TaskListener nodeLog = logTaskListener();
		return ociRuntimeToolExec(Arrays.asList("kill", Long.toString(processId), containerId), nodeLog) == 0;
	}

	@Override
	public boolean ociRuntimeMountsAdd(final long processId, final String containerId,
			final Set<LinuxMountEntry> configs, final TaskListener listener) throws IOException, InterruptedException {
		if (processId == -1 || configs.isEmpty())
			return false;
		final TaskListener nodeLog = logTaskListener();
		final FilePath tmpFilePath = dataRootPath().createTempFile("ociMounts-", ".json");
		try (OutputStream stream = tmpFilePath.write()) {
			final String content = "[" + configs.stream()
					.map(LinuxMountEntry::toOciJson)
					.collect(Collectors.joining(",")) + "]";
			stream.write(content.getBytes(StandardCharsets.UTF_8));
			stream.flush();
			return ociRuntimeToolExec(
					Arrays.asList("mounts", "add", Long.toString(processId), containerId, tmpFilePath.absolutize()
							.getRemote()),
					nodeLog) == 0;
		} finally {
			tmpFilePath.delete();
		}
	}

	@Override
	public boolean ociRuntimeMountsRemove(final long processId, final String containerId,
			final Set<LinuxMountEntry> configs, final TaskListener listener) throws IOException, InterruptedException {
		if (processId == -1 || configs.isEmpty())
			return false;
		final TaskListener nodeLog = logTaskListener();
		final FilePath tmpFilePath = dataRootPath().createTempFile("ociMounts-", ".json");
		try (OutputStream stream = tmpFilePath.write()) {
			final String content = "[" + configs.stream()
					.map(LinuxMountEntry::toOciJson)
					.collect(Collectors.joining(",")) + "]";
			stream.write(content.getBytes(StandardCharsets.UTF_8));
			stream.flush();
			return ociRuntimeToolExec(
					Arrays.asList("mounts", "remove", Long.toString(processId), containerId, tmpFilePath.absolutize()
							.getRemote()),
					nodeLog) == 0;
		} finally {
			tmpFilePath.delete();
		}
	}
}
