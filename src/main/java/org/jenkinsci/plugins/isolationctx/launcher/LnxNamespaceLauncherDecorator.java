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
package org.jenkinsci.plugins.isolationctx.launcher;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.jenkinsci.plugins.isolationctx.LinuxNamespaceContext;
import org.jenkinsci.plugins.isolationctx.dto.LnxIdentity;
import org.jenkinsci.plugins.isolationctx.utils.WriteTempLnxEnvFileCallable;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.LauncherDecorator;
import hudson.Proc;
import hudson.model.Node;
import hudson.remoting.VirtualChannel;

public class LnxNamespaceLauncherDecorator extends LauncherDecorator implements Serializable {

	private static final long serialVersionUID = 1L;

	private final LinuxNamespaceContext ctx;

	public LnxNamespaceLauncherDecorator(final LinuxNamespaceContext lnxContext) {
		this.ctx = lnxContext;
	}

	protected List<String> nsenterCmd(final boolean isolateNetwork, FilePath workspace) throws Throwable {
		if (workspace == null)
			workspace = this.ctx.getRawWorkspace();
		final List<String> cmd = new ArrayList<>();

		final LnxIdentity lnxIdentity = this.ctx.ctxLnxIdentity();
		if (lnxIdentity == null)
			throw new AbortException("[Linux-Namespace] [E:LNX:1:302] Identity unavailable!");

		// sudo nsenter --target=75008 --mount --uts --ipc --net --pid --
		cmd.add("sudo");
		cmd.add("nsenter");
		cmd.add("--target=" + this.ctx.getProcessId());
		cmd.add("--mount");
		cmd.add("--uts");
		cmd.add("--ipc");
		if (isolateNetwork)
			cmd.add("--net");
		cmd.add("--pid");
		// --wdns is not yet available on EL9
		// cmd.add("--wdns=" + workspace.absolutize().getRemote());
		cmd.add("--");
		// setpriv --reuid=<uid> --regid=<gid> --groups=<groups> --inh-caps=-all
		// --reset-env
		// --
		cmd.add("setpriv");
		cmd.add("--reuid=" + lnxIdentity.getUserId());
		cmd.add("--regid=" + lnxIdentity.getGroupId());
		cmd.add("--groups=" + lnxIdentity.joinGroupIds(","));
		cmd.add("--inh-caps=-all");
		cmd.add("--reset-env");
		cmd.add("--");

		return cmd;
	}

	@Override
	public Launcher decorate(final Launcher launcher, final Node node) {
		return new DecoratedIsolationLauncher(launcher) {
			@Override
			public Proc launch(final ProcStarter starter) throws IOException {
				if (LnxNamespaceLauncherDecorator.this.ctx.getProcessId() == -1)
					throw new AbortException("[Linux-Namespace] [E:LNX:1:301] Namespace unavailable!");

				// Wrap command with nsenter
				final List<String> cmd;
				final String envFileName;
				try {
					cmd = LnxNamespaceLauncherDecorator.this.nsenterCmd(isNetworkIsolated(), starter.pwd());
					final FilePath envCachePath = LnxNamespaceLauncherDecorator.this.ctx.getEnvCachePath();
					envFileName = envCachePath.act(
							new WriteTempLnxEnvFileCallable(inherit(envCachePath.getChannel(), starter.envs()), true));
				} catch (Throwable e) {
					if (e instanceof RuntimeException runE)
						throw runE;
					throw new RuntimeException(e);
				}

				// as nsenter from linux-utils on EL9 can not change the working dir yet,
				// do it now!
				String cdCmd = "";
				{
					FilePath workspace = starter.pwd();
					if (workspace == null)
						workspace = LnxNamespaceLauncherDecorator.this.ctx.getRawWorkspace();
					try {
						cdCmd = "cd \"" + workspace.absolutize()
								.getRemote() + "\"; ";
					} catch (IOException | InterruptedException ignored) {
						ignored.printStackTrace();
					}
				}
				cmd.addAll(Arrays.asList("sh", "-c", "(" + cdCmd + ". \"" + envFileName + "\"; exec \"$@\")"));

				// recalculate mask: based on nsenter cmd prefix
				boolean[] oldMasks = starter.masks();
				int oldSize = oldMasks == null ? 0 : oldMasks.length;
				int cmdSize = cmd.size();
				boolean[] masks = new boolean[cmdSize + oldSize];
				for (int i = 0; i < cmdSize; i++)
					masks[i] = false;
				for (int i = 0; i < oldSize; i++)
					masks[cmdSize + i] = oldMasks[i];
				starter.masks(masks);

				// merge: nsenter with actual cmd

				cmd.addAll(starter.cmds());

				starter.cmds(cmd);

				// launcher.getListener()
				// .getLogger()
				// .println("============= \n" + String.join("\n", cmd));

				return super.launch(starter);
			}
		};
	}

	/**
	 * Expands the list of environment variables by inheriting current env
	 * variables.
	 *
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private static EnvVars inherit(@NonNull final VirtualChannel channel, @NonNull final String[] env)
			throws IOException, InterruptedException {
		// convert String[] to Map first
		EnvVars m = new EnvVars();
		if (env != null) {
			for (String e : env) {
				int index = e.indexOf('=');
				m.put(e.substring(0, index), e.substring(index + 1));
			}
		}
		m = EnvVars.getRemote(channel)
				.overrideExpandingAll(m);

		// apply filters
		for (Iterator<Map.Entry<String, String>> i = m.entrySet()
				.iterator(); i.hasNext();) {
			final Map.Entry<String, String> entry = i.next();
			final String key;
			// Filter out bash functions
			if (entry == null || (key = entry.getKey()) == null || key.endsWith("%%"))
				i.remove();
		}

		return m;
	}
}