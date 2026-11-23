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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.jenkinsci.plugins.isolationctx.IsolationContext;
import org.jenkinsci.plugins.isolationctx.LinuxNamespaceContext;
import org.jenkinsci.plugins.isolationctx.config.NodeIsolationProperty;
import org.jenkinsci.plugins.isolationctx.config.OciMountConfig;
import org.jenkinsci.plugins.isolationctx.config.ScriptedLnxIsolationRuntime;
import org.jenkinsci.plugins.isolationctx.dto.LnxIdentity;
import org.jenkinsci.plugins.isolationctx.dto.LnxMountEntry;
import org.jenkinsci.plugins.isolationctx.dto.OciMountEntry;
import org.jenkinsci.plugins.isolationctx.dto.TrafficMap;
import org.jenkinsci.plugins.isolationctx.dto.net.LnxNetInterface;
import org.jenkinsci.plugins.isolationctx.dto.net.LnxNetLink;
import org.jenkinsci.plugins.isolationctx.utils.LauncherUtil;
import org.jenkinsci.plugins.isolationctx.utils.LazyAsyncRef;
import org.jenkinsci.plugins.isolationctx.utils.Scripts;
import org.jenkinsci.plugins.workflow.graph.FlowNode;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.Computer;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.remoting.VirtualChannel;
import hudson.util.ArgumentListBuilder;
import hudson.util.LogTaskListener;
import hudson.util.StreamTaskListener;
import jenkins.util.io.OnMaster;
import net.runeduniverse.lib.utils.net.api.IpNet4Address;
import net.runeduniverse.lib.utils.net.api.IpNet6Address;

public class ScriptedLnxIsolationHandler implements ComputerIsolationHandler, OnMaster, //
		LnxHostHandler, LnxNetNamespaceHandler, OverlayFsHandler, OciRuntimeHandler {

	private static final Logger LOGGER = Logger.getLogger(LinuxNamespaceContext.class.getName());

	protected final AtomicBoolean active = new AtomicBoolean(false);

	@NonNull
	protected final Computer computer;
	@NonNull
	protected final Channel channel;
	@NonNull
	protected final String _logPath;
	@NonNull
	protected final String _rootPath;
	@NonNull
	protected final String _dataRootPath;
	@NonNull
	protected final String _firewallToolPath;
	@NonNull
	protected final String _networkToolPath;
	@NonNull
	protected final String _mountToolPath;
	@NonNull
	protected final String _ociRuntimeToolPath;
	@NonNull
	protected final LazyAsyncRef<LnxIdentity> hostLnxIdentityRef;
	@NonNull
	protected final LazyAsyncRef<LnxIdentity> ctxLnxIdentityRef;

	@CheckForNull
	protected TaskListener logListener;

	public ScriptedLnxIsolationHandler(@NonNull final Computer computer, final Channel channel, final FilePath rootPath)
			throws IOException, InterruptedException {
		// NOTE: never persist Node
		// -> it's a DTO and is replaced on every Computer reconnect!
		Objects.requireNonNull(this.computer = computer);
		// NOTE: retain Channel as setup runs before channel is persisted in Computer
		Objects.requireNonNull(this.channel = channel);

		this._logPath = rootPath.child("logs/NodeIsolation.log")
				.absolutize()
				.getRemote();
		this._rootPath = rootPath.child("isolation-context")
				.absolutize()
				.getRemote();
		this._dataRootPath = "data";
		this._firewallToolPath = "firewall-tool.py";
		this._networkToolPath = "network-tool.py";
		this._mountToolPath = "mount-tool.py";
		this._ociRuntimeToolPath = "oci-runtime-tool.sh";

		this.hostLnxIdentityRef = new LazyAsyncRef<>(Computer.threadPoolForRemoting, this::_supplyHostLnxIdentity);
		this.ctxLnxIdentityRef = new LazyAsyncRef<>(Computer.threadPoolForRemoting, this::_supplyCtxLnxIdentity);
	}

	@Override
	public boolean isActive() {
		return this.active.get();
	}

	@Override
	public Computer getComputer() {
		return this.computer;
	}

	protected final FilePath logPath() {
		return new FilePath(this.computer.getChannel(), this._logPath);
	}

	protected final FilePath rootPath() {
		return new FilePath(this.channel, this._rootPath);
	}

	protected final FilePath dataRootPath() {
		return rootPath().child(this._dataRootPath);
	}

	protected final FilePath firewallToolPath() {
		return rootPath().child(this._firewallToolPath);
	}

	protected final FilePath networkToolPath() {
		return rootPath().child(this._networkToolPath);
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

	protected final LauncherUtil launchUtil() {
		return new LauncherUtil(this.computer::getChannel, this::logTaskListener);
	}

	protected final ScriptedLnxIsolationRuntime isolationRuntime() {
		if (NodeIsolationProperty
				.getIsolationRuntime(this.computer.getNode()) instanceof ScriptedLnxIsolationRuntime runtime)
			return runtime;
		return null;
	}

	protected final Proc wsExecAndJoinCmd(final List<String> cmd, final boolean readStderr)
			throws IOException, InterruptedException {
		return launchUtil().wsExecAndJoinCmd(cmd, readStderr);
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

	protected void deployScripts() throws IOException, InterruptedException {
		// -- deploy scripts from resources -> path --
		// jar:/scripts/network/elinux-python3.py
		Scripts.NETWORK__ENTERPRISE_LINUX_PYTHON3.deployTo(networkToolPath());
		// jar:/scripts/firewall/nftables-python3.py
		Scripts.FIREWALL__NFTABLES_PYTHON3.deployTo(firewallToolPath());
		// jar:/scripts/data/linux-mount-python3.py
		Scripts.DATA__LINUX_MOUNT_PYTHON3.deployTo(mountToolPath());
		// jar:/scripts/runtime/linux-crun.sh
		Scripts.RUNTIME__LINUX_CRUN_SHELL.deployTo(ociRuntimeToolPath());
	}

	protected boolean execScriptTask(final VirtualChannel channel, final Collection<String> tasks,
			final TaskListener listener) throws IOException, InterruptedException {
		final Launcher launcher = LauncherUtil.createLauncher(channel, listener);
		boolean allOk = true;
		// network setup
		final FilePath networkToolPath = new FilePath(channel, this._rootPath + "/" + this._networkToolPath);
		if (networkToolPath.exists()) {
			launcher.launch()
					.cmds(new ArgumentListBuilder("/usr/bin/sudo", "/usr/bin/python3", networkToolPath.getRemote())
							.add(tasks))
					.start()
					.joinWithTimeout(30, TimeUnit.SECONDS, listener);
		} else
			allOk = false;
		// firewall setup
		final FilePath firewallToolPath = new FilePath(channel, this._rootPath + "/" + this._firewallToolPath);
		if (firewallToolPath.exists()) {
			launcher.launch()
					.cmds(new ArgumentListBuilder("/usr/bin/sudo", "/usr/bin/python3", firewallToolPath.getRemote())
							.add(tasks))
					.start()
					.joinWithTimeout(30, TimeUnit.SECONDS, listener);
		} else
			allOk = false;
		// mount isolation setup
		final FilePath mountToolPath = new FilePath(channel, this._rootPath + "/" + this._mountToolPath);
		if (mountToolPath.exists()) {
			launcher.launch()
					.cmds(new ArgumentListBuilder("/usr/bin/sudo", "/usr/bin/python3", mountToolPath.getRemote())
							.add(tasks))
					.start()
					.joinWithTimeout(30, TimeUnit.SECONDS, listener);
		} else
			allOk = false;
		// runtime setup
		final FilePath ociRuntimeToolPath = new FilePath(channel, this._rootPath + "/" + this._ociRuntimeToolPath);
		if (ociRuntimeToolPath.exists()) {
			launcher.launch()
					.cmds(new ArgumentListBuilder("/usr/bin/sudo", "/usr/bin/bash", ociRuntimeToolPath.getRemote())
							.add(tasks))
					.start()
					.joinWithTimeout(30, TimeUnit.SECONDS, listener);
		} else
			allOk = false;
		return allOk;
	}

	@Override
	public void setup(final VirtualChannel channel, final TaskListener listener)
			throws IOException, InterruptedException {
		try {
			deployScripts();
			this.active.set(execScriptTask(channel, Arrays.asList("setup"), listener));
		} catch (Throwable e) {
			this.active.set(false);
			throw e;
		}
	}

	@Override
	public void shutdown(final VirtualChannel channel, final TaskListener listener)
			throws IOException, InterruptedException {
		try {
			execScriptTask(channel, Arrays.asList("shutdown"), listener);
			this.active.set(false);
		} catch (Throwable e) {
			throw e;
		}
	}

	@Override
	public IsolationContext createIsolationContext(final Run<?, ?> run, final FlowNode flowNode,
			final Computer computer, final TaskListener listener) {
		return new LinuxNamespaceContext(run, flowNode, computer, listener);
	}

	protected void firewallHostInterfaceExec(final Set<String> data, final String operation,
			final TaskListener listener) throws IOException, InterruptedException {
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

	public void firewallHostInterfaceAdd(final Set<String> data, final TaskListener listener)
			throws IOException, InterruptedException {
		if (data == null || data.isEmpty())
			return;
		firewallHostInterfaceExec(data, "add", listener);
	}

	public void firewallHostInterfaceRetain(final Set<String> data, final TaskListener listener)
			throws IOException, InterruptedException {
		if (data == null || data.isEmpty())
			return;
		firewallHostInterfaceExec(data, "set", listener);
	}

	public void firewallHostInterfaceRemove(final Set<String> data, final TaskListener listener)
			throws IOException, InterruptedException {
		if (data == null || data.isEmpty())
			return;
		firewallHostInterfaceExec(data, "del", listener);
	}

	public void firewallHostInterfaceRemoveAll(final TaskListener listener) throws IOException, InterruptedException {
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

	private final LnxIdentity _supplyHostLnxIdentity(final LnxIdentity oldIdentity, final Throwable oldEx) {
		final LnxIdentity identity = LnxIdentity.identify(launchUtil());
		if (oldIdentity == null)
			return identity;
		return identity.copyTo(oldIdentity);
	}

	private final LnxIdentity _supplyCtxLnxIdentity(final LnxIdentity oldIdentity, final Throwable oldEx) {
		try {
			LnxIdentity identity = Objects.requireNonNull(this.hostLnxIdentityRef.get());
			identity = restrictLinuxIdentity(identity.copy());
			if (oldIdentity == null)
				return identity;
			return identity.copyTo(oldIdentity);
		} catch (CancellationException ex) {
			throw ex;
		} catch (Throwable ex) {
			if (ex instanceof ExecutionException)
				ex = ex.getCause();
			throw new CompletionException(ex);
		}
	}

	protected LnxIdentity restrictLinuxIdentity(final LnxIdentity identity) {
		final ScriptedLnxIsolationRuntime isoRuntime = isolationRuntime();
		if (isoRuntime != null) {
			identity.removeGroupIds(LnxIdentity.groupIdByName(launchUtil(), isoRuntime.getAdminGroup()));
		}
		return identity;
	}

	public LnxIdentity hostLnxIdentity() throws IOException, InterruptedException {
		try {
			return this.hostLnxIdentityRef.get();
		} catch (ExecutionException e) {
			final Throwable cause = e.getCause();
			if (cause instanceof IOException ex)
				throw ex;
			if (cause instanceof InterruptedException ex)
				throw ex;
			if (cause instanceof RuntimeException ex)
				throw ex;
			throw new RuntimeException(cause);
		}
	}

	public LnxIdentity ctxLnxIdentity() throws IOException, InterruptedException {
		try {
			return this.ctxLnxIdentityRef.get();
		} catch (ExecutionException e) {
			final Throwable cause = e.getCause();
			if (cause instanceof IOException ex)
				throw ex;
			if (cause instanceof InterruptedException ex)
				throw ex;
			if (cause instanceof RuntimeException ex)
				throw ex;
			throw new RuntimeException(cause);
		}
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

	@Override
	public OverlayFsHandler.OverlayFsMountData overlayFsCreate(final FilePath overlayBasePath)
			throws IOException, InterruptedException {
		final TaskListener nodeLog = logTaskListener();
		mountToolExec(Arrays.asList("mount", "overlay", "create", overlayBasePath.absolutize()
				.getRemote()), nodeLog);
		final OverlayFsHandler.OverlayFsMountData overlay = new OverlayFsHandler.OverlayFsMountData(this,
				overlayBasePath);
		return overlay;
	}

	@Override
	public void overlayFsRemove(final FilePath overlayBasePath) throws IOException, InterruptedException {
		final TaskListener nodeLog = logTaskListener();
		mountToolExec(Arrays.asList("mount", "overlay", "cleanup", overlayBasePath.absolutize()
				.getRemote()), nodeLog);

	}

	@Override
	public OciBundleConfig ociBundleDefaultConfig(final LnxIdentity identity, final String hostname,
			final TaskListener listener) {
		final ScriptedLnxIsolationRuntime runtime = isolationRuntime();
		final OciBundleConfig ociConfig = new OciBundleConfig(identity, hostname);
		// apply custom overlay mounts
		if (runtime != null) {
			runtime.getOverlayFsMounts()
					.stream()
					.filter(OciMountConfig::isValid)
					.map(ScriptedLnxIsolationHandler::toOciMountEntry)
					.forEachOrdered(ociConfig::addMount);
		}
		return ociConfig.addProcMount()
				.addTmpfsMount(this.computer.getNode()
						.getRootPath()
						.getRemote(), "mode=755", "size=64m");
	}

	protected static OciMountEntry toOciMountEntry(final OciMountConfig config) {
		return new LnxMountEntry(config.getAltType(), config.getSource(), config.getDestination())
				.addOptions(config.getOptionsAsStream());
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
		final String remoteBundlePath = bundlePath.absolutize()
				.getRemote();
		final String remotePidFilePath = remoteBundlePath + "/handle.pid";

		ociRuntimeToolExec(Arrays.asList("run", containerId, remoteBundlePath, remotePidFilePath), nodeLog);

		return Long.parseLong(bundlePath.child(remotePidFilePath)
				.readToString());
	}

	@Override
	public boolean ociRuntimeKill(final long processId, final String containerId, final TaskListener listener)
			throws IOException, InterruptedException {
		final TaskListener nodeLog = logTaskListener();
		return ociRuntimeToolExec(Arrays.asList("kill", Long.toString(processId), containerId), nodeLog) == 0;
	}

	@Override
	public boolean ociRuntimeMountsAdd(final long processId, final String containerId, final Set<LnxMountEntry> configs,
			final TaskListener listener) throws IOException, InterruptedException {
		if (processId == -1 || configs.isEmpty())
			return false;
		final TaskListener nodeLog = logTaskListener();
		final FilePath tmpFilePath = dataRootPath().createTempFile("ociMounts-", ".json");
		try (OutputStream stream = tmpFilePath.write()) {
			final String content = "[" + configs.stream()
					.map(LnxMountEntry::toOciJson)
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
			final Set<LnxMountEntry> configs, final TaskListener listener) throws IOException, InterruptedException {
		if (processId == -1 || configs.isEmpty())
			return false;
		final TaskListener nodeLog = logTaskListener();
		final FilePath tmpFilePath = dataRootPath().createTempFile("ociMounts-", ".json");
		try (OutputStream stream = tmpFilePath.write()) {
			final String content = "[" + configs.stream()
					.map(LnxMountEntry::toOciJson)
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

	protected List<String> nsenterNetCmd(final long processId) {
		final List<String> cmd = new ArrayList<>();

		// sudo nsenter --target=75008 --net --
		cmd.add("sudo");
		cmd.add("nsenter");
		cmd.add("--target=" + processId);
		cmd.add("--net");
		cmd.add("--");

		return cmd;
	}

	@Override
	public boolean ctxCreateLoopbackIface(final long processId, final LnxNetInterface iface)
			throws IOException, InterruptedException {
		if (processId == -1 || iface == null)
			return false;

		final List<String> netCmd = nsenterNetCmd(processId);
		netCmd.add("ip");
		netCmd.add("link");
		netCmd.add("set");
		netCmd.add(iface.getHostIfaceName());
		netCmd.add("up");
		wsExecAndJoinCmd(netCmd, true);
		return true;
	}

	@Override
	public boolean ctxConnectToHost(final long processId, final LnxNetLink link)
			throws IOException, InterruptedException {
		if (processId == -1)
			return false;

		final String hostIfaceName = link.getHostIfaceName();
		final String contextIfaceName = link.getContextIfaceName();
		wsExecAndJoinCmd(//
				Arrays.asList("sudo", "ip", "link", "add", hostIfaceName, "type", "veth", "peer", "name",
						contextIfaceName)//
				, true);
		wsExecAndJoinCmd(//
				Arrays.asList("sudo", "ip", "link", "set", contextIfaceName, "netns", Long.toString(processId))//
				, true);

		final IpNet4Address subnet4 = link.getSubnetV4();
		if (subnet4 != null) {
			wsExecAndJoinCmd(//
					Arrays.asList("sudo", "ip", "addr", "add", link.getHostAddressV4()
							.toCidrNotation() + "/" + subnet4.getMask(), "dev", hostIfaceName)//
					, true);
		}

		final IpNet6Address subnet6 = link.getSubnetV6();
		if (subnet6 != null) {
			wsExecAndJoinCmd(//
					Arrays.asList("sudo", "ip", "addr", "add", link.getHostAddressV6()
							.toCidrNotation() + "/" + subnet6.getMask(), "dev", hostIfaceName)//
					, true);
		}
		wsExecAndJoinCmd(//
				Arrays.asList("sudo", "ip", "link", "set", hostIfaceName, "up")//
				, true);

		List<String> cmd;
		if (subnet4 != null) {
			cmd = nsenterNetCmd(processId);
			cmd.addAll(Arrays.asList("ip", "addr", "add", link.getContextAddressV4()
					.toCidrNotation() + "/30", "dev", contextIfaceName));
			wsExecAndJoinCmd(cmd, true);
		}
		if (subnet6 != null) {
			cmd = nsenterNetCmd(processId);
			cmd.addAll(Arrays.asList("ip", "addr", "add", link.getContextAddressV6()
					.toCidrNotation() + "/126", "dev", contextIfaceName));
			wsExecAndJoinCmd(cmd, true);
		}
		cmd = nsenterNetCmd(processId);
		cmd.addAll(Arrays.asList("ip", "link", "set", contextIfaceName, "up"));
		wsExecAndJoinCmd(cmd, true);

		// add connected link to firewall interface filter
		firewallHostInterfaceAdd(Set.of(hostIfaceName), logTaskListener());
		return true;
	}

	@Override
	public boolean ctxDisconnectFromHost(final long processId, final LnxNetLink link)
			throws IOException, InterruptedException {
		final TaskListener nodeLog = logTaskListener();
		try {
			wsExecAndJoinCmd(Arrays.asList("sudo", "ip", "link", "del", link.getHostIfaceName()), true);
		} finally {
			// remove disconnected link from firewall interface filter
			firewallHostInterfaceRemove(Set.of(link.getHostIfaceName()), nodeLog);
		}
		return true;
	}
}
