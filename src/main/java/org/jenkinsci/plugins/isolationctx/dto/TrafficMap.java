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
package org.jenkinsci.plugins.isolationctx.dto;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.Set;
import hudson.Util;
import jenkins.runeduniverse.utils.net.IpNetworkUtils;
import net.runeduniverse.lib.utils.net.api.Ip4Address;
import net.runeduniverse.lib.utils.net.api.Ip6Address;
import net.runeduniverse.lib.utils.net.api.IpAddress;
import net.runeduniverse.lib.utils.net.api.IpNetAddress;

public class TrafficMap implements Serializable {

	public static enum Protocol {
		TCP("tcp"), UDP("tcp");

		protected final String text;

		private Protocol(final String text) {
			this.text = text;
		}

		@Override
		public String toString() {
			return this.text;
		}

		public static String toString(final Protocol protocol) {
			if (protocol == null)
				return null;
			return protocol.toString();
		}

		public static Protocol fromString(String text) {
			if ((text = Util.fixEmptyAndTrim(text)) == null)
				return null;
			final Protocol[] entries = Protocol.values();
			for (int i = 0; i < entries.length; i++) {
				if (entries[i].text.equalsIgnoreCase(text))
					return entries[i];
			}
			return null;
		}
	}

	private static final long serialVersionUID = 1L;

	public static final Class<?>[] SUPPORTED_IP_TYPES = new Class<?>[] { Ip4Address.class, Ip6Address.class };

	protected final boolean forceSameIpType;
	protected final Map<IpAddress<?>, Map<IpAddress<?>, Map<Protocol, Set<Integer>>>> data;

	public TrafficMap() {
		this(true);
	}

	public TrafficMap(final boolean forceSameIpType) {
		this.forceSameIpType = forceSameIpType;
		this.data = new LinkedHashMap<>();
	}

	public boolean isEmpty() {
		return this.data.isEmpty();
	}

	public TrafficMap add(final IpAddress<?> ipA, final IpAddress<?> ipB, final Protocol protocol,
			final Set<Integer> ports) {
		final Map<Protocol, Set<Integer>> portMap = new LinkedHashMap<>();
		portMap.put(protocol, ports);
		add(ipA, ipB, portMap);
		return this;
	}

	public TrafficMap add(final IpAddress<?> ipA, final IpAddress<?> ipB, final Map<Protocol, Set<Integer>> portMap) {
		final Map<IpAddress<?>, Map<Protocol, Set<Integer>>> innerMap = new LinkedHashMap<>();
		innerMap.put(ipB, portMap);
		add(ipA, innerMap);
		return this;
	}

	public TrafficMap add(final IpAddress<?> ipA, final Map<IpAddress<?>, Map<Protocol, Set<Integer>>> innerMap) {
		final Map<IpAddress<?>, Map<IpAddress<?>, Map<Protocol, Set<Integer>>>> data = new LinkedHashMap<>();
		data.put(ipA, innerMap);
		add(data);
		return this;
	}

	public TrafficMap add(final TrafficMap trafficMap) {
		if (trafficMap == null)
			return this;
		add(trafficMap.data);
		return this;
	}

	public TrafficMap add(final Map<IpAddress<?>, Map<IpAddress<?>, Map<Protocol, Set<Integer>>>> dataMap) {
		if (dataMap == null || dataMap.isEmpty())
			return this;
		for (Entry<IpAddress<?>, Map<IpAddress<?>, Map<Protocol, Set<Integer>>>> dataEntry : dataMap.entrySet()) {
			// inner map
			final IpAddress<?> ipA = IpNetworkUtils.convert(dataEntry.getKey());
			final Map<IpAddress<?>, Map<Protocol, Set<Integer>>> innerMap = dataEntry.getValue();
			if (ipA == null || innerMap == null || innerMap.isEmpty())
				continue;

			for (Entry<IpAddress<?>, Map<Protocol, Set<Integer>>> innerEntry : innerMap.entrySet()) {
				final IpAddress<?> ipB = IpNetworkUtils.convert(innerEntry.getKey());
				final Map<Protocol, Set<Integer>> protoMap = innerEntry.getValue();
				// protocol map
				if (ipB == null || protoMap == null || protoMap.isEmpty())
					continue;

				for (Entry<Protocol, Set<Integer>> entry : protoMap.entrySet()) {
					final Protocol protocol = entry.getKey();
					final Set<Integer> ports = entry.getValue();
					// ports
					if (protocol == null || ports == null)
						continue;
					removeInvalidPorts(ports);
					if (ports.isEmpty() || this.forceSameIpType && getIpType(ipA.getClass(), ipB.getClass()) == null)
						continue;

					this.data.computeIfAbsent(ipA, k -> new LinkedHashMap<>())
							.computeIfAbsent(ipB, k -> new LinkedHashMap<>())
							.computeIfAbsent(protocol, k -> new LinkedHashSet<>())
							.addAll(ports);
				}
			}
		}
		return this;
	}

	public TrafficMap remove(final IpAddress<?> ipA, IpAddress<?> ipB, Protocol protocol, Set<Integer> ports) {
		final Map<Protocol, Set<Integer>> portMap = new LinkedHashMap<>();
		portMap.put(protocol, ports);
		remove(ipA, ipB, portMap);
		return this;
	}

	public TrafficMap remove(final IpAddress<?> ipA, IpAddress<?> ipB, Map<Protocol, Set<Integer>> portMap) {
		final Map<IpAddress<?>, Map<Protocol, Set<Integer>>> innerMap = new LinkedHashMap<>();
		innerMap.put(ipB, portMap);
		remove(ipA, innerMap);
		return this;
	}

	public TrafficMap remove(final IpAddress<?> ipA, Map<IpAddress<?>, Map<Protocol, Set<Integer>>> innerMap) {
		final Map<IpAddress<?>, Map<IpAddress<?>, Map<Protocol, Set<Integer>>>> data = new LinkedHashMap<>();
		data.put(ipA, innerMap);
		remove(data);
		return this;
	}

	public TrafficMap remove(final TrafficMap trafficMap) {
		if (trafficMap == null)
			return this;
		remove(trafficMap.data);
		return this;
	}

	public TrafficMap remove(final Map<IpAddress<?>, Map<IpAddress<?>, Map<Protocol, Set<Integer>>>> dataMap) {
		if (dataMap == null || dataMap.isEmpty())
			return this;
		for (Entry<IpAddress<?>, Map<IpAddress<?>, Map<Protocol, Set<Integer>>>> dataEntry : dataMap.entrySet()) {
			// inner map
			final IpAddress<?> ipA = dataEntry.getKey();
			final Map<IpAddress<?>, Map<Protocol, Set<Integer>>> innerMap = dataEntry.getValue();
			if (ipA == null || innerMap == null || innerMap.isEmpty())
				continue;

			for (Entry<IpAddress<?>, Map<Protocol, Set<Integer>>> innerEntry : innerMap.entrySet()) {
				final IpAddress<?> ipB = innerEntry.getKey();
				final Map<Protocol, Set<Integer>> protoMap = innerEntry.getValue();
				// protocol map
				if (ipB == null || protoMap == null || protoMap.isEmpty())
					continue;

				for (Entry<Protocol, Set<Integer>> entry : protoMap.entrySet()) {
					final Protocol protocol = entry.getKey();
					final Set<Integer> ports = entry.getValue();
					// ports
					if (protocol == null || ports == null || ports.isEmpty())
						continue;

					this.data.getOrDefault(ipA, Collections.emptyMap())
							.getOrDefault(ipB, Collections.emptyMap())
							.getOrDefault(protocol, Collections.emptySet())
							.removeAll(ports);
				}
			}
		}
		return this;
	}

	public Map<IpAddress<?>, Map<Protocol, Set<Integer>>> removeAll(final IpAddress<?> ipA) {
		final Map<IpAddress<?>, Map<Protocol, Set<Integer>>> innerMap = this.data.remove(ipA);
		return innerMap == null ? Collections.emptyMap() : innerMap;
	}

	public Map<Protocol, Set<Integer>> removeAll(final IpAddress<?> ipA, final IpAddress<?> ipB) {
		final Map<IpAddress<?>, Map<Protocol, Set<Integer>>> innerMap = this.data.get(ipA);
		if (innerMap == null)
			return Collections.emptyMap();
		final Map<Protocol, Set<Integer>> protoMap = innerMap.remove(ipA);
		return protoMap == null ? Collections.emptyMap() : protoMap;
	}

	public Set<Integer> removeAll(final IpAddress<?> ipA, final IpAddress<?> ipB, final Protocol protocol) {
		final Map<IpAddress<?>, Map<Protocol, Set<Integer>>> innerMap = this.data.get(ipA);
		if (innerMap == null)
			return Collections.emptySet();
		final Map<Protocol, Set<Integer>> protoMap = innerMap.get(ipB);
		if (protoMap == null)
			return Collections.emptySet();
		final Set<Integer> ports = protoMap.remove(protocol);
		return ports == null ? Collections.emptySet() : ports;
	}

	public TrafficMap removeAll(final IpNetAddress<?, ?> netA) {
		return removeAll(netA == null ? null : Set.of(netA));
	}

	public TrafficMap removeAll(final Set<IpNetAddress<?, ?>> setNetA) {
		final TrafficMap map = new TrafficMap();
		if (setNetA == null || setNetA.isEmpty())
			return map;
		for (IpAddress<?> ipA : new HashSet<>(this.data.keySet())) {
			if (!setNetA.stream()
					.anyMatch(netA -> netA != null && netA.contains(ipA)))
				continue;
			final Map<IpAddress<?>, Map<Protocol, Set<Integer>>> innerMap = this.data.remove(ipA);
			if (innerMap == null || innerMap.isEmpty())
				continue;
			map.add(ipA, innerMap);
		}
		return map;
	}

	public TrafficMap removeAll(final IpNetAddress<?, ?> netA, final IpNetAddress<?, ?> netB) {
		return removeAll(netA == null ? null : Set.of(netA), netB == null ? null : Set.of(netB));
	}

	public TrafficMap removeAll(final Set<IpNetAddress<?, ?>> setNetA, final Set<IpNetAddress<?, ?>> setNetB) {
		final TrafficMap map = new TrafficMap();
		if (setNetA == null || setNetA.isEmpty() || setNetB == null || setNetB.isEmpty())
			return map;
		for (IpAddress<?> ipA : this.data.keySet()) {
			if (!setNetA.stream()
					.anyMatch(netA -> netA != null && netA.contains(ipA)))
				continue;
			final Map<IpAddress<?>, Map<Protocol, Set<Integer>>> innerMap = this.data.get(ipA);
			if (innerMap == null || innerMap.isEmpty())
				continue;
			for (IpAddress<?> ipB : new HashSet<>(innerMap.keySet())) {
				if (!setNetB.stream()
						.anyMatch(netB -> netB != null && netB.contains(ipB)))
					continue;
				final Map<Protocol, Set<Integer>> protoMap = innerMap.remove(ipB);
				if (protoMap == null || protoMap.isEmpty())
					continue;
				map.add(ipA, ipB, protoMap);
			}
		}
		return map;
	}

	public TrafficMap removeAll(final IpNetAddress<?, ?> netA, final IpNetAddress<?, ?> netB, final Protocol protocol) {
		return removeAll(netA == null ? null : Set.of(netA), netB == null ? null : Set.of(netB), protocol);
	}

	public TrafficMap removeAll(final Set<IpNetAddress<?, ?>> setNetA, final Set<IpNetAddress<?, ?>> setNetB,
			final Protocol protocol) {
		final TrafficMap map = new TrafficMap();
		if (setNetA == null || setNetA.isEmpty() || setNetB == null || setNetB.isEmpty())
			return map;
		for (IpAddress<?> ipA : this.data.keySet()) {
			if (!setNetA.stream()
					.anyMatch(netA -> netA != null && netA.contains(ipA)))
				continue;
			final Map<IpAddress<?>, Map<Protocol, Set<Integer>>> innerMap = this.data.get(ipA);
			if (innerMap == null || innerMap.isEmpty())
				continue;
			for (IpAddress<?> ipB : innerMap.keySet()) {
				if (!setNetB.stream()
						.anyMatch(netB -> netB != null && netB.contains(ipB)))
					continue;
				final Map<Protocol, Set<Integer>> protoMap = innerMap.get(ipB);
				if (protoMap == null || protoMap.isEmpty())
					continue;
				final Set<Integer> ports = protoMap.remove(protocol);
				if (ports == null || ports.isEmpty())
					continue;
				map.add(ipA, ipB, protocol, ports);
			}
		}
		return map;
	}

	public TrafficMap clear() {
		this.data.clear();
		return this;
	}

	public void forEach(final EntryConsumer<IpAddress<?>> consumer) throws IOException, InterruptedException {
		if (consumer == null)
			return;
		for (Entry<IpAddress<?>, Map<IpAddress<?>, Map<Protocol, Set<Integer>>>> dataEntry : this.data.entrySet()) {
			// inner map
			final IpAddress<?> ipA = dataEntry.getKey();
			final Map<IpAddress<?>, Map<Protocol, Set<Integer>>> innerMap = dataEntry.getValue();
			if (innerMap.isEmpty())
				continue;

			for (Entry<IpAddress<?>, Map<Protocol, Set<Integer>>> innerEntry : innerMap.entrySet()) {
				final IpAddress<?> ipB = innerEntry.getKey();
				// copy map
				final Map<Protocol, Set<Integer>> protoMap = innerEntry.getValue()
						.entrySet()
						.stream()
						.collect(Collectors.toMap(Map.Entry::getKey, e -> new HashSet<>(e.getValue())));
				// protocol map
				consumer.accept(ipA, ipB, protoMap);
			}
		}
	}

	public <A extends IpAddress<?>> void forEach(final Class<A> type, final EntryConsumer<A> consumer)
			throws IOException, InterruptedException {
		if (type == null || consumer == null)
			return;
		for (Entry<IpAddress<?>, Map<IpAddress<?>, Map<Protocol, Set<Integer>>>> dataEntry : this.data.entrySet()) {
			// inner map
			final IpAddress<?> ipA = dataEntry.getKey();
			final Map<IpAddress<?>, Map<Protocol, Set<Integer>>> innerMap = dataEntry.getValue();
			if (innerMap.isEmpty())
				continue;

			for (Entry<IpAddress<?>, Map<Protocol, Set<Integer>>> innerEntry : innerMap.entrySet()) {
				final IpAddress<?> ipB = innerEntry.getKey();
				// copy map
				final Map<Protocol, Set<Integer>> protoMap = innerEntry.getValue()
						.entrySet()
						.stream()
						.collect(Collectors.toMap(Map.Entry::getKey, e -> new HashSet<>(e.getValue())));
				// protocol map
				if (type.isAssignableFrom(getIpType(ipA.getClass(), ipB.getClass())))
					consumer.accept((A) ipA, (A) ipB, protoMap);
			}
		}
	}

	protected void removeInvalidPorts(final Set<Integer> ports) {
		for (Iterator<Integer> i = ports.iterator(); i.hasNext();) {
			final Integer port = i.next();
			if (port == null || port < 1 || 65535 < port)
				i.remove();
		}
	}

	protected <A extends IpAddress<?>, B extends IpAddress<?>, R extends IpAddress<?>> Class<R> getIpType(
			final Class<A> typeA, final Class<B> typeB) {
		for (Class<?> type : SUPPORTED_IP_TYPES) {
			if (type.isAssignableFrom(typeA) && type.isAssignableFrom(typeB))
				return (Class<R>) type;
		}
		return null;
	}

	@FunctionalInterface
	public static interface EntryConsumer<T> {
		public void accept(T a, T b, Map<Protocol, Set<Integer>> protoMap) throws IOException, InterruptedException;
	}
}
