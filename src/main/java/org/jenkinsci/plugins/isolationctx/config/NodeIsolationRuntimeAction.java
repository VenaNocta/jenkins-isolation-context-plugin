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
package org.jenkinsci.plugins.isolationctx.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.SortedSet;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.jenkinsci.plugins.isolationctx.dto.TrafficMap;
import org.jenkinsci.plugins.isolationctx.handler.NodeIsolationHandler;
import org.jenkinsci.plugins.isolationctx.handler.NodeIsolationHandlerProvider;

import com.thoughtworks.xstream.annotations.XStreamOmitField;

import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.InvisibleAction;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.Saveable;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;
import jenkins.runeduniverse.utils.net.IpNetworkUtils;
import jenkins.runeduniverse.utils.net.XStreamIpNet4Address;
import jenkins.runeduniverse.utils.net.XStreamIpNet6Address;
import net.runeduniverse.lib.utils.net.api.Ip4Address;
import net.runeduniverse.lib.utils.net.api.IpAddress;
import net.runeduniverse.lib.utils.net.api.IpNet4Address;
import net.runeduniverse.lib.utils.net.api.IpNet6Address;

// stores data required for per Run Context Isolation on this Node!
public class NodeIsolationRuntimeAction extends InvisibleAction
		implements NodeIsolationRuntime, Saveable, Serializable {

	private static final long serialVersionUID = 1L;

	@XStreamOmitField
	protected final Object lock4 = new Object();
	@XStreamOmitField
	protected final Object lock6 = new Object();
	@XStreamOmitField
	protected final Object lockPorts = new Object();

	protected transient Computer computer;
	protected transient NodeIsolationHandler handler;

	protected final SortedSet<IpNet4Address> subnet4Stack;
	protected final Map<IpNet4Address, String> subnet4Reservations;
	protected final SortedSet<IpNet6Address> subnet6Stack;
	protected final Map<IpNet6Address, String> subnet6Reservations;
	// host-ip : source-ip : protocol : ports
	protected final TrafficMap openPorts;

	protected IpNet4Address subnet4;
	protected IpNet6Address subnet6;

	public NodeIsolationRuntimeAction(final Computer owner) {
		this(owner,
				// Ipv4
				IpNetworkUtils.createIpNet4Address(new byte[] { (byte) 0xac, 0x14, 0x00, 0x01 }, (short) 16),
				new TreeSet<>(), new LinkedHashMap<>(), //
				// Ipv6
				null, new TreeSet<>(), new LinkedHashMap<>(), //
				// open ports
				new TrafficMap());
	}

	protected NodeIsolationRuntimeAction(final Computer computer, //
			final IpNet4Address subnet4, final SortedSet<IpNet4Address> subnet4Stack,
			final Map<IpNet4Address, String> subnet4Reservations, //
			final IpNet6Address subnet6, final SortedSet<IpNet6Address> subnet6Stack,
			final Map<IpNet6Address, String> subnet6Reservations, //
			final TrafficMap openPorts) {
		this.computer = computer;
		// Ipv4
		this.subnet4 = XStreamIpNet4Address.convert(subnet4);
		this.subnet4Stack = subnet4Stack.stream()
				.map(XStreamIpNet4Address::convert)
				.collect(Collectors.toCollection(TreeSet::new));
		this.subnet4Reservations = subnet4Reservations.entrySet()
				.stream()
				.collect(Collectors.toMap(//
						e -> XStreamIpNet4Address.convert(e.getKey()), Map.Entry::getValue, (a, b) -> a,
						LinkedHashMap::new));
		// Ipv6
		this.subnet6 = XStreamIpNet6Address.convert(subnet6);
		this.subnet6Stack = subnet6Stack.stream()
				.map(XStreamIpNet6Address::convert)
				.collect(Collectors.toCollection(TreeSet::new));
		this.subnet6Reservations = subnet6Reservations.entrySet()
				.stream()
				.collect(Collectors.toMap(//
						e -> XStreamIpNet6Address.convert(e.getKey()), Map.Entry::getValue, (a, b) -> a,
						LinkedHashMap::new));
		// Ports
		this.openPorts = openPorts;
	}

	public NodeIsolationHandler isolationHandler() {
		return this.handler;
	}

	public IpNet4Address getIpv4Subnet() {
		return this.subnet4;
	}

	public IpNet6Address getIpv6Subnet() {
		return this.subnet6;
	}

	public void setIpv4Subnet(final String value) throws UnknownHostException {
		synchronized (this.lock4) {
			this.subnet4 = IpNetworkUtils.parseIpNet4Address(value);
			trySave();
		}
	}

	public void setIpv4Subnet(final IpNet4Address subnet) throws UnknownHostException {
		synchronized (this.lock4) {
			this.subnet4 = XStreamIpNet4Address.convert(subnet);
			trySave();
		}
	}

	public void setIpv6Subnet(final String value) throws UnknownHostException {
		synchronized (this.lock6) {
			this.subnet6 = IpNetworkUtils.parseIpNet6Address(value);
			trySave();
		}
	}

	public void setIpv6Subnet(final IpNet6Address subnet) throws UnknownHostException {
		synchronized (this.lock6) {
			this.subnet6 = XStreamIpNet6Address.convert(subnet);
			trySave();
		}
	}

	@Override
	public void setup(final TaskListener listener) throws IOException, InterruptedException {
		isolationHandler().setup(listener);
		isolationHandler().firewallInputRetainOpen(this.openPorts, listener);
		// cleanup broken reservations
		final Set<String> runIds2Free = new LinkedHashSet<>();
		for (Entry<IpNet4Address, String> entry : this.subnet4Reservations.entrySet()) {
			final String runId = entry.getValue();
			try {
				final Run<?, ?> run = Run.fromExternalizableId(entry.getValue());
				if (!run.isInProgress()) {
					runIds2Free.add(runId);
					continue;
				}
			} catch (IllegalArgumentException ignored) {
				runIds2Free.add(runId);
			}
		}
		freeAllSubnetsOf(runIds2Free, listener);
	}

	public IpNet4Address reserveIpv4Subnet(final Run<?, ?> run, final TaskListener listener)
			throws IOException, InterruptedException {
		if (!run.isInProgress() || this.subnet4 == null)
			return null;

		synchronized (this.lock4) {
			final String id = run.getExternalizableId();
			// find first known subnet in stack that is not used
			IpNet4Address net = this.subnet4Stack.stream()
					.filter(((Predicate<IpNet4Address>) this.subnet4Reservations::containsKey).negate())
					.findFirst()
					.orElse(null);
			if (net == null) {
				// if none is unused let's see if another can be allocated!
				final Ip4Address lastSubnetAddr = //
						this.subnet4Stack.isEmpty() ? null
								: this.subnet4Stack.last()
										.getBaseAddress();
				final Iterator<IpNet4Address> i = this.subnet4.splitForMask(lastSubnetAddr, (short) 30, 1)
						.iterator();
				if (i.hasNext())
					net = i.next();
			}
			if (net == null) {
				// no available subnets!
				return null;
			}
			// reserve subnet
			this.subnet4Stack.add(net);
			this.subnet4Reservations.put(net, id);
			synchronized (this.lockPorts) {
				isolationHandler().firewallInputClose(this.openPorts.removeAll(net), listener);
			}
			trySave();
			return net;
		}
	}

	public IpNet6Address reserveIpv6Subnet(final Run<?, ?> run, final TaskListener listener)
			throws IOException, InterruptedException {
		if (!run.isInProgress() || this.subnet6 == null)
			return null;

		synchronized (this.lock6) {
			// TODO implement
			trySave();
			return null;
		}
	}

	public void freeIpv4Subnet(final IpNet4Address subnet, final TaskListener listener)
			throws IOException, InterruptedException {
		synchronized (this.lock4) {
			synchronized (this.lockPorts) {
				this.subnet4Reservations.remove(subnet);
				isolationHandler().firewallInputClose(this.openPorts.removeAll(subnet), listener);
			}
			trySave();
		}
	}

	public void freeIpv6Subnet(final IpNet6Address subnet, final TaskListener listener)
			throws IOException, InterruptedException {
		synchronized (this.lock6) {
			// TODO implement
			trySave();
		}
	}

	public void freeAllSubnetsOf(final Set<String> extIds, final TaskListener listener)
			throws IOException, InterruptedException {
		if (extIds.isEmpty())
			return;
		final TrafficMap map = new TrafficMap();
		synchronized (this.lock4) {
			synchronized (this.lockPorts) {
				for (Iterator<Entry<IpNet4Address, String>> i = this.subnet4Reservations.entrySet()
						.iterator(); i.hasNext();) {
					final Entry<IpNet4Address, String> entry = i.next();
					if (extIds.contains(entry.getValue())) {
						i.remove();
						map.add(this.openPorts.removeAll(entry.getKey()));
					}
				}
			}
			trySave();
		}
		isolationHandler().firewallInputClose(map, listener);
	}

	protected void trySave() {
		try {
			save();
		} catch (IOException ignored) {
			ignored.printStackTrace();
		}
	}

	@Override
	public void save() throws IOException {
		final FilePath configFile = getConfigFile(this.computer.getNode());
		try (OutputStream stream = configFile.write()) {
			Jenkins.XSTREAM2.toXMLUTF8(this, stream);
		} catch (InterruptedException e) {
			throw new IOException(e);
		}
	}

	protected static FilePath getConfigFile(final Node node) {
		return node.getRootPath()
				.child("isolation-ctx.xml");
	}

	protected static void injectHandler(final NodeIsolationRuntimeAction action, final Computer computer)
			throws IOException, InterruptedException {
		final NodeIsolationHandlerProvider provider = NodeIsolationHandlerProvider.providerFor(computer);
		if (provider != null)
			action.handler = provider.buildHandler(computer);
	}

	protected static NodeIsolationRuntimeAction load(final Computer computer) throws IOException, InterruptedException {
		final FilePath configFile = getConfigFile(computer.getNode());
		if (!configFile.exists()) {
			final NodeIsolationRuntimeAction action = new NodeIsolationRuntimeAction(computer);
			injectHandler(action, computer);
			action.save();
			return action;
		}

		final NodeIsolationRuntimeAction action;
		try (InputStream stream = configFile.read()) {
			action = (NodeIsolationRuntimeAction) Jenkins.XSTREAM2.fromXML(stream);
		}

		action.computer = computer;
		injectHandler(action, computer);
		return action;
	}

	public synchronized static NodeIsolationRuntimeAction get(final Computer computer)
			throws IOException, InterruptedException {
		if (computer == null)
			return null;
		NodeIsolationRuntimeAction action = computer.getAction(NodeIsolationRuntimeAction.class);
		if (action == null)
			computer.addAction(action = load(computer));
		return action;
	}

	@Override
	public void firewallInputOpen(final TrafficMap data, final TaskListener listener)
			throws IOException, InterruptedException {
		if (data == null)
			return;
		synchronized (this.lockPorts) {
			this.openPorts.add(data);
			trySave();
		}
		isolationHandler().firewallInputOpen(data, listener);
	}

	@Override
	public void firewallInputRetainOpen(final TrafficMap data, final TaskListener listener)
			throws IOException, InterruptedException {
		if (data == null)
			return;
		synchronized (this.lockPorts) {
			this.openPorts.clear();
			this.openPorts.add(data);
			trySave();
		}
		isolationHandler().firewallInputRetainOpen(data, listener);
	}

	@Override
	public void firewallInputClose(final TrafficMap data, final TaskListener listener)
			throws IOException, InterruptedException {
		if (data == null)
			return;
		synchronized (this.lockPorts) {
			this.openPorts.remove(data);
			trySave();
		}
		isolationHandler().firewallInputClose(data, listener);
	}

	@Override
	public void firewallInputCloseAll(final TaskListener listener) throws IOException, InterruptedException {
		synchronized (this.lockPorts) {
			this.openPorts.clear();
			trySave();
		}
		isolationHandler().firewallInputCloseAll(listener);
	}

	@Override
	public void firewallInputCloseAll(final IpAddress<?> host, final TaskListener listener)
			throws IOException, InterruptedException {
		final TrafficMap data = new TrafficMap();
		synchronized (this.lockPorts) {
			data.add(host, this.openPorts.removeAll(host));
			trySave();
		}
		isolationHandler().firewallInputClose(data, listener);
	}

	@Override
	public void firewallInputCloseAll(final IpAddress<?> host, final IpAddress<?> external, final TaskListener listener)
			throws IOException, InterruptedException {
		final TrafficMap data = new TrafficMap();
		synchronized (this.lockPorts) {
			data.add(host, external, this.openPorts.removeAll(host, external));
			trySave();
		}
		isolationHandler().firewallInputClose(data, listener);
	}
}
