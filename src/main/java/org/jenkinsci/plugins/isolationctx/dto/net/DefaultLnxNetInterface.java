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
package org.jenkinsci.plugins.isolationctx.dto.net;

import org.jenkinsci.plugins.isolationctx.dto.FirewallDelegate;

import jenkins.runeduniverse.utils.net.IpNetworkUtils;
import net.runeduniverse.lib.utils.net.api.Ip4Address;
import net.runeduniverse.lib.utils.net.api.Ip6Address;
import net.runeduniverse.lib.utils.net.api.IpNet4Address;
import net.runeduniverse.lib.utils.net.api.IpNet6Address;

public class DefaultLnxNetInterface extends DefaultNetInterface implements LnxNetInterface {

	private static final long serialVersionUID = 1L;

	protected final String hostIfaceName;

	public DefaultLnxNetInterface(final FirewallDelegate firewall, final String hostIfaceName, //
			final IpNet4Address subnet4, final Ip4Address hostAddr4, //
			final IpNet6Address subnet6, final Ip6Address hostAddr6) {
		super(firewall, //
				subnet4, hostAddr4, //
				subnet6, hostAddr6);
		this.hostIfaceName = hostIfaceName;
	}

	@Override
	public String getHostIfaceName() {
		return this.hostIfaceName;
	}

	public static DefaultLnxNetInterface createLoopback(final FirewallDelegate firewall, final String hostIfaceName,
			final boolean ipv4, final boolean ipv6) {
		Ip4Address addr4 = null;
		IpNet4Address subnet4 = null;
		if (ipv4) {
			addr4 = IpNetworkUtils.createIp4Address(new byte[] { 0x7f, 0x00, 0x00, 0x01 });
			subnet4 = IpNetworkUtils.createIpNet4Address(addr4, (short) 32);
		}
		Ip6Address addr6 = null;
		IpNet6Address subnet6 = null;
		if (ipv6) {
			addr6 = IpNetworkUtils.createIp6Address(new byte[] { //
					0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, //
					0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01 });
			subnet6 = IpNetworkUtils.createIpNet6Address(addr6, (short) 128);
		}
		return new DefaultLnxNetInterface(firewall, hostIfaceName, //
				subnet4, addr4, //
				subnet6, addr6);
	}
}