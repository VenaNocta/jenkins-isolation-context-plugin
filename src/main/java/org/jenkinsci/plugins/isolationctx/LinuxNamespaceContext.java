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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkinsci.plugins.isolationctx.config.NodeIsolationProperty;
import org.jenkinsci.plugins.isolationctx.config.ScriptedLnxIsolationRuntime;
import org.jenkinsci.plugins.isolationctx.config.ComputerIsolationState;
import org.jenkinsci.plugins.isolationctx.config.ComputerIsolationStateAction;
import org.jenkinsci.plugins.isolationctx.config.IsolationRuntime;
import org.jenkinsci.plugins.isolationctx.dto.LnxMountEntry;
import org.jenkinsci.plugins.isolationctx.dto.net.DefaultLnxNetInterface;
import org.jenkinsci.plugins.isolationctx.dto.net.DefaultLnxNetLink;
import org.jenkinsci.plugins.isolationctx.dto.net.LnxNetLink;
import org.jenkinsci.plugins.isolationctx.dto.net.NetInterface;
import org.jenkinsci.plugins.isolationctx.dto.FirewallDelegateFilter;
import org.jenkinsci.plugins.isolationctx.dto.LnxIdentity;
import org.jenkinsci.plugins.isolationctx.handler.ComputerIsolationHandler;
import org.jenkinsci.plugins.isolationctx.handler.LnxHostHandler;
import org.jenkinsci.plugins.isolationctx.handler.LnxNetNamespaceHandler;
import org.jenkinsci.plugins.isolationctx.handler.OciRuntimeHandler;
import org.jenkinsci.plugins.isolationctx.handler.OciRuntimeHandler.OciBundleConfig;
import org.jenkinsci.plugins.isolationctx.handler.OverlayFsHandler;
import org.jenkinsci.plugins.isolationctx.handler.OverlayFsHandler.OverlayFsMountData;
import org.jenkinsci.plugins.isolationctx.launcher.LnxNamespaceLauncherDecorator;
import org.jenkinsci.plugins.isolationctx.utils.LauncherUtil;
import org.jenkinsci.plugins.workflow.actions.WorkspaceAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.Launcher;
import hudson.LauncherDecorator;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.Future;
import hudson.remoting.RemoteInputStream;
import hudson.remoting.RemoteOutputStream;
import hudson.remoting.VirtualChannel;
import hudson.slaves.WorkspaceList.Lease;
import hudson.util.LogTaskListener;
import jenkins.agents.ControllerToAgentFileCallable;
import jenkins.model.Jenkins;
import jenkins.security.XStreamNotDeserializable;
import net.runeduniverse.lib.utils.net.api.Ip4Address;
import net.runeduniverse.lib.utils.net.api.Ip6Address;
import net.runeduniverse.lib.utils.net.api.IpAddress;
import net.runeduniverse.lib.utils.net.api.IpNet4Address;
import net.runeduniverse.lib.utils.net.api.IpNet6Address;
import net.runeduniverse.lib.utils.net.api.IpNetAddress;

public class LinuxNamespaceContext implements IsolationContext {

	private static final long serialVersionUID = 1L;
	private static final Logger LOGGER = Logger.getLogger(LinuxNamespaceContext.class.getName());

	public static final String TEMPL_LINE = "[ Linux-Namespace » %d ] %s";
	public static final String TEMPL_ERR_LINE = "[ Linux-Namespace » %d ] [E:LNX:1:%03d] %s";

	protected final LnxNamespaceLauncherDecorator factory;
	protected final String containerIdAsPath;
	protected final String containerId;

	protected long processId = -1;
	protected OverlayFsMountData overlayData = null;

	protected transient final Object linkLock = new Object();

	protected transient final LauncherUtil launchUtil = //
			new LauncherUtil(() -> this.computer == null ? null : this.computer.getChannel(), this::logListener);
	@XStreamNotDeserializable
	protected transient Run<?, ?> run;
	@XStreamNotDeserializable
	protected transient Computer computer;
	@XStreamNotDeserializable
	protected transient WorkspaceAction ws;
	@XStreamNotDeserializable
	protected transient TaskListener listener;

	protected final SortedMap<Integer, LnxNetLink> _activeLinks = new TreeMap<>();
	@XStreamNotDeserializable
	protected transient TaskListener _logListener = null;
	@XStreamNotDeserializable
	protected transient Lease _overlayWorkspaceLease = null;

	public LinuxNamespaceContext(final Run<?, ?> run, final FlowNode flowNode, final Computer computer,
			final TaskListener listener) {
		this.factory = new LnxNamespaceLauncherDecorator(this);
		this.run = run;
		this.computer = computer;
		this.ws = flowNode.getAction(WorkspaceAction.class);
		this.listener = listener;

		this.containerIdAsPath = "jenkins_" + this.run.getParent()
				.getFullName();
		this.containerId = this.containerIdAsPath.replace('/', '-');
	}

	@Override
	public void onLoad(final FlowNode parent) {
		Objects.requireNonNull(parent, "LinuxNamespaceContext can not be loaded with FlowNode: null");

		this.ws = parent.getAction(WorkspaceAction.class);
		this.computer = Jenkins.get()
				.getComputer(this.ws.getNode());

		final String flowNodeId = parent.getId();
		try {
			StepExecution.acceptAll(exec -> {
				try {
					final StepContext stepContext = exec.getContext();
					final String id = stepContext.get(FlowNode.class)
							.getId();

					if (flowNodeId.equals(id)) {
						LinuxNamespaceContext.this.run = stepContext.get(Run.class);
						LinuxNamespaceContext.this.listener = stepContext.get(TaskListener.class);
					}
				} catch (Exception ignore) {
				}
			})
					.get();
		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
		}

		// getNodeRuntime2() requires this.node to be set
		final ComputerIsolationState runtime = getComputerStateOrNull();
		if (runtime != null && runtime.isolationHandler() instanceof OverlayFsHandler handler) {
			// reinit overlayData
			if (this.overlayData != null)
				this.overlayData.initHandler(handler);
		}
	}

	@Override
	public LauncherDecorator launcherDecorator() {
		return this.factory;
	}

	public FilePath getEnvCachePath() {
		return this.ws.getWorkspace()
				.withSuffix("@env_cache");
	}

	public long getProcessId() {
		return this.processId;
	}

	protected ComputerIsolationState getComputerState() throws IOException, InterruptedException {
		return ComputerIsolationStateAction.get(this.computer);
	}

	protected ComputerIsolationState getComputerStateOrNull() {
		try {
			return getComputerState();
		} catch (IOException | InterruptedException ignored) {
			ignored.printStackTrace();
		}
		return null;
	}

	protected synchronized TaskListener logListener() {
		if (this._logListener != null)
			return _logListener;
		try {
			return _logListener = getComputerState().isolationHandler()
					.logTaskListener();
		} catch (Throwable e) {
			this._logListener = new LogTaskListener(LOGGER, Level.INFO);
			this._logListener.error("Failed to create Remote-Logger, redirection activated!");
			e.printStackTrace(this._logListener.getLogger());
			return this._logListener;
		}
	}

	protected LnxHostHandler hostHandler() throws IOException, InterruptedException {
		final ComputerIsolationState computerState = getComputerState();
		if (computerState != null && computerState.isolationHandler() instanceof LnxHostHandler handler)
			return handler;
		return null;
	}

	protected OciRuntimeHandler ociHandler() throws IOException, InterruptedException {
		final ComputerIsolationState computerState = getComputerState();
		if (computerState != null && computerState.isolationHandler() instanceof OciRuntimeHandler handler)
			return handler;
		return null;
	}

	protected LnxNetNamespaceHandler netHandler() throws IOException, InterruptedException {
		final ComputerIsolationState computerState = getComputerState();
		if (computerState != null && computerState.isolationHandler() instanceof LnxNetNamespaceHandler handler)
			return handler;
		return null;
	}

	@CheckForNull
	public LnxIdentity ctxLnxIdentity() throws IOException, InterruptedException {
		final LnxHostHandler handler = hostHandler();
		if (handler == null)
			return null;
		return handler.ctxLnxIdentity();
	}

	protected synchronized Lease overlayWorkspace(final FilePath workspacePath) throws InterruptedException {
		if (this._overlayWorkspaceLease != null)
			return this._overlayWorkspaceLease;
		final Node node = this.computer.getNode();
		final FilePath ociBundlePath;
		String override = null;
		// node specific root-path changes
		final IsolationRuntime isoRuntime = NodeIsolationProperty.getIsolationRuntime(node);
		if (isoRuntime instanceof ScriptedLnxIsolationRuntime runtime) {
			override = runtime.getOverlayRootPath();
		}
		// fallback root-path
		if (override != null) {
			ociBundlePath = node.getRootPath()
					.child(override)
					.child(this.containerIdAsPath);
		} else
			ociBundlePath = workspacePath.withSuffix("@overlay");
		return this._overlayWorkspaceLease = this.computer.getWorkspaceList()
				.allocate(ociBundlePath);
	}

	@SuppressWarnings("resource")
	@Override
	public void setup() {
		if (this.processId != -1)
			return;

		final PrintStream pipeLog = this.listener.getLogger();
		final PrintStream nodeLog = logListener().getLogger();

		try {
			final LnxHostHandler hostHandler = hostHandler();
			if (hostHandler == null) {
				pipeLog.println("[Linux-Namespace] [E:LNX:1:101] Failed to start isolated environment!");
				return;
			}
			final OciRuntimeHandler ociHandler = ociHandler();
			if (ociHandler == null) {
				pipeLog.println("[Linux-Namespace] [E:LNX:1:102] Failed to start isolated environment!");
				return;
			}

			final FilePath workspacePath = this.ws.getWorkspace();
			final String workspacePathRemote = workspacePath.absolutize()
					.getRemote();
			workspacePath.mkdirs();
			final FilePath envCachePath = getEnvCachePath();
			final String envCachePathRemote = envCachePath.absolutize()
					.getRemote();
			envCachePath.mkdirs();
			final FilePath tmpPath = workspacePath.withSuffix("@tmp");
			final String tmpPathRemote = tmpPath.absolutize()
					.getRemote();
			tmpPath.mkdirs();

			FilePath ociBundlePath = workspacePath.withSuffix("@ociBundle");

			final OciBundleConfig ociBundleConfig = ociHandler
					.ociBundleDefaultConfig(hostHandler.ctxLnxIdentity(), "jenkins-agent", this.listener)
					.addBindMount(workspacePathRemote, workspacePathRemote, "bind", "rw", "rshared")
					.addBindMount(tmpPathRemote, tmpPathRemote, "bind", "rw", "rshared")
					.addBindMount(envCachePathRemote, envCachePathRemote, "bind", "rw", "rshared");

			if (ociHandler instanceof OverlayFsHandler handler) {
				final OverlayFsMountData mountData = handler.overlayFsCreate(overlayWorkspace(workspacePath).path);
				ociBundleConfig.setRootFs("rootfs");
				ociBundlePath = mountData.basePath();
				mountData.computeWorkspacePath(workspacePath);
				this.overlayData = mountData;
			}

			ociBundlePath.mkdirs();
			final FilePath ociConfigPath = ociBundlePath.child("config.json");
			ociBundleConfig.writeTo(ociConfigPath);

			this.processId = ociHandler.ociRuntimeStart(ociBundlePath, this.containerId, this.listener);
		} catch (IOException | InterruptedException e) {
			pipeLog.println("[Linux-Namespace] [E:LNX:1:100] Failed to start isolated environment!");
			e.printStackTrace(nodeLog);
			return;
		}

		try {
			final LnxNetNamespaceHandler netHandler = netHandler();
			if (netHandler == null) {
				pipeLog.println(
						String.format(TEMPL_ERR_LINE, this.processId, 103, "Failed to start isolated environment!"));
				destroy();
				return;
			}

			netHandler.ctxCreateLoopbackIface(this.processId,
					DefaultLnxNetInterface.createLoopback(null, "lo", true, false));
		} catch (Throwable e) {
			pipeLog.println(String.format(TEMPL_ERR_LINE, this.processId, 104, "Network Setup Failed!"));
			e.printStackTrace(nodeLog);
			destroy();
			return;
		}

		pipeLog.println(String.format(TEMPL_LINE, this.processId, "Started isolated environment"));
	}

	@Override
	public void destroy() {
		if (this.processId == -1)
			return;

		final PrintStream pipeLog = this.listener.getLogger();
		final PrintStream nodeLog = logListener().getLogger();

		// Kill process tree
		try {
			final OciRuntimeHandler ociHandler = ociHandler();
			if (ociHandler == null) {
				pipeLog.println(
						"[Linux-Namespace] [E:LNX:1:202] Failed to stop isolated environment, Oci-Runtime unavailable!");
				return;
			}

			ociHandler.ociRuntimeKill(this.processId, this.containerId, this.listener);
			getEnvCachePath().deleteRecursive();

			pipeLog.println(String.format(TEMPL_LINE, this.processId, "Stopped isolated environment"));
			this.processId = -1;
		} catch (InterruptedException | IOException e) {
			pipeLog.println(String.format(TEMPL_ERR_LINE, this.processId, 200, "Failed to stop isolated environment!"));
			e.printStackTrace(nodeLog);
		} finally {
			// Cleanup active LnxLinks
			final ComputerIsolationState computerState = getComputerStateOrNull();
			if (computerState != null) {
				for (LnxNetLink link : this._activeLinks.values()) {
					try {
						// Remove veth interfaces
						disconnectNetInterface(link);
					} catch (IOException | InterruptedException e) {
						e.printStackTrace(nodeLog);
					}
				}
				if (this._overlayWorkspaceLease != null
						&& computerState.isolationHandler() instanceof OverlayFsHandler handler) {
					try {
						// just try it even if it wasn't created - maybe it was lost before!
						handler.overlayFsRemove(this._overlayWorkspaceLease.path);
					} catch (IOException | InterruptedException e) {
						e.printStackTrace(nodeLog);
					}
				}
			}
			// Cleanup Workspaces
			if (this._overlayWorkspaceLease != null) {
				try {
					this._overlayWorkspaceLease.path.deleteRecursive();
				} catch (IOException | InterruptedException e) {
					e.printStackTrace(nodeLog);
				}
				this._overlayWorkspaceLease.release();
			}
		}
	}

	public FilePath getRawWorkspace() {
		return this.ws.getWorkspace();
	}

	@Override
	public FilePath getWorkspace() {
		if (this.overlayData == null) {
			return this.ws.getWorkspace();
		}
		return this.overlayData.workspacePath();
	}

	@Override
	public <T> T actInContext(final FileCallable<T> callable) throws IOException, InterruptedException {
		return getWorkspace().act(callable);
	}

	@Override
	public <T> Future<T> actInContextAsync(final FileCallable<T> callable) throws IOException, InterruptedException {
		return getWorkspace().actAsync(callable);
	}

	@Override
	public boolean mountWorkspacePath(final FilePath workspacePath) throws IOException, InterruptedException {
		final OciRuntimeHandler ociHandler = ociHandler();
		if (ociHandler == null || !workspacePath.exists() || this.processId == -1)
			return false;
		final String workspacePathRemote = workspacePath.absolutize()
				.getRemote();
		final LnxMountEntry entry = new LnxMountEntry("bind", workspacePathRemote, workspacePathRemote);
		return ociHandler.ociRuntimeMountsAdd(this.processId, this.containerId, Set.of(entry), this.listener);
	}

	@Override
	public boolean unmountWorkspacePath(final FilePath workspacePath) throws IOException, InterruptedException {
		final OciRuntimeHandler ociHandler = ociHandler();
		if (ociHandler == null || !workspacePath.exists() || this.processId == -1)
			return false;
		final String workspacePathRemote = workspacePath.absolutize()
				.getRemote();
		final LnxMountEntry entry = new LnxMountEntry("bind", workspacePathRemote, workspacePathRemote);
		return ociHandler.ociRuntimeMountsRemove(this.processId, this.containerId, Set.of(entry), this.listener);
	}

	@Override
	public LnxNetLink connectNetInterface() throws IOException, InterruptedException {
		final ComputerIsolationState computerState = getComputerStateOrNull();
		if (computerState == null || this.processId == -1)
			return null;

		final TaskListener nodeLog = logListener();

		synchronized (this.linkLock) {
			final ComputerIsolationHandler handler = computerState.isolationHandler();
			int linkNum = this._activeLinks.isEmpty() ? 0 : this._activeLinks.lastKey() + 1;

			final String hostIfaceName = String.format("jciv-h%d-%d", this.processId, linkNum);
			final String contextIfaceName = String.format("jciv-n%d-%d", this.processId, linkNum);

			final Set<IpAddress<?>> hostIpSet = new LinkedHashSet<>();
			final Set<IpNetAddress<?, ?>> subnetSet = new LinkedHashSet<>();
			final IpNet4Address subnet4 = computerState.reserveIpv4Subnet(this.run, nodeLog);
			Ip4Address hostAddr4 = null, contextAddr4 = null;
			if (subnet4 != null) {
				hostAddr4 = subnet4.getLowestAddress()
						.increment();
				contextAddr4 = hostAddr4.increment();

				hostIpSet.add(hostAddr4);
				subnetSet.add(subnet4);
			}

			final IpNet6Address subnet6 = computerState.reserveIpv6Subnet(this.run, nodeLog);
			Ip6Address hostAddr6 = null, contextAddr6 = null;
			if (subnet6 != null) {
				hostAddr6 = subnet6.getLowestAddress()
						.increment();
				contextAddr6 = hostAddr6.increment();

				hostIpSet.add(hostAddr6);
				subnetSet.add(subnet6);
			}

			final LnxNetLink link = new DefaultLnxNetLink(//
					new FirewallDelegateFilter(computerState, //
							Collections.unmodifiableSet(hostIpSet), //
							Collections.unmodifiableSet(subnetSet)), //
					hostIfaceName, contextIfaceName, //
					subnet4, hostAddr4, contextAddr4, //
					subnet6, hostAddr6, contextAddr6);
			this._activeLinks.put(linkNum, link);

			try {
				boolean success = false;
				if (handler instanceof LnxNetNamespaceHandler netHandler) {
					success = netHandler.ctxConnectToHost(this.processId, link);
				}
				if (success == false) {
					final PrintStream pipeLog = this.listener.getLogger();
					pipeLog.println(String.format(TEMPL_ERR_LINE, this.processId, 401,
							"Failed to connect host-only veth-link to isolated environment!"));

					disconnectNetInterface(link);
					return null;
				}
			} catch (InterruptedException | IOException e) {
				final PrintStream pipeLog = this.listener.getLogger();
				pipeLog.println(String.format(TEMPL_LINE, this.processId, 400,
						"Failed to connect host-only veth-link to isolated environment!"));
				e.printStackTrace(pipeLog);

				disconnectNetInterface(link);
				return null;
			}
			return link;
		}
	}

	@Override
	public void disconnectNetInterface(final NetInterface anyNetInterface) throws IOException, InterruptedException {
		final ComputerIsolationState computerState = getComputerStateOrNull();

		synchronized (this.linkLock) {
			if (!this._activeLinks.containsValue(anyNetInterface))
				return;
			if (anyNetInterface instanceof LnxNetLink link) {
				if (computerState != null) {
					final ComputerIsolationHandler handler = computerState.isolationHandler();
					final TaskListener nodeLog = handler.logTaskListener();

					if (handler instanceof LnxNetNamespaceHandler netHandler) {
						netHandler.ctxDisconnectFromHost(this.processId, link);
					}
					computerState.freeIpv4Subnet(link.getSubnetV4(), nodeLog);
					computerState.freeIpv6Subnet(link.getSubnetV6(), nodeLog);
				}
			}
		}
	}

	protected static String outputToString(final Process process) {
		try {
			return new String(process.getInputStream()
					.readAllBytes()).trim();
		} catch (IOException e) {
			throw new CompletionException(e);
		}
	}

	public static class IOTriplet2 extends Launcher.IOTriplet {
		@CheckForNull
		public InputStream stdout, stderr;
		@CheckForNull
		public OutputStream stdin;
		private static final long serialVersionUID = 1L;
	}

	public static interface RemoteProcess2 extends Launcher.RemoteProcess {

		public long pid();

		@NonNull
		IOTriplet2 getIOtriplet();
	}

	public static class ExecRemoteProcess implements ControllerToAgentFileCallable<RemoteProcess2> {

		private static final long serialVersionUID = 1L;

		protected final List<String> cmd;

		public ExecRemoteProcess(final List<String> cmd) {
			this.cmd = cmd;
		}

		@Override
		public RemoteProcess2 invoke(final File wsDir, final VirtualChannel channel)
				throws IOException, InterruptedException {
			final ProcessBuilder pb = new ProcessBuilder(cmd);

			// final Map<String, String> env = pb.environment();
			// env.clear();
			// env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin");
			// env.put("LANG", "C");
			// env.put("LC_ALL", "C");

			pb.directory(wsDir);
			final Process process = pb.start();

			return channel.export(RemoteProcess2.class, new RemoteProcess2() {

				@Override
				public long pid() {
					return process.pid();
				}

				@Override
				public void kill() throws IOException, InterruptedException {
					process.destroy();
				}

				@Override
				public int join() throws InterruptedException, IOException {
					try {
						return process.waitFor();
					} catch (InterruptedException e) {
						kill();
						throw e;
					}
				}

				@Override
				public boolean isAlive() throws IOException, InterruptedException {
					return process.isAlive();
				}

				@Override
				public IOTriplet2 getIOtriplet() {
					IOTriplet2 r = new IOTriplet2();
					r.stdout = new RemoteInputStream(process.getInputStream(), RemoteInputStream.Flag.NOT_GREEDY);
					r.stderr = new RemoteInputStream(process.getErrorStream(), RemoteInputStream.Flag.NOT_GREEDY);
					r.stdin = new RemoteOutputStream(process.getOutputStream());
					return r;
				}
			});
		}
	}
}
