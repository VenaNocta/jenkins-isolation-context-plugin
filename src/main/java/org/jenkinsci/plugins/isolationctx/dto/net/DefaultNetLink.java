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

import jenkins.runeduniverse.utils.net.XStreamIp4Address;
import jenkins.runeduniverse.utils.net.XStreamIp6Address;
import net.runeduniverse.lib.utils.net.api.Ip4Address;
import net.runeduniverse.lib.utils.net.api.Ip6Address;
import net.runeduniverse.lib.utils.net.api.IpNet4Address;
import net.runeduniverse.lib.utils.net.api.IpNet6Address;

public class DefaultNetLink extends DefaultNetInterface implements NetLink {

	private static final long serialVersionUID = 1L;

	protected final Ip4Address contextAddr4;
	protected final Ip6Address contextAddr6;

	public DefaultNetLink(final FirewallDelegate firewall, //
			final IpNet4Address subnet4, final Ip4Address hostAddr4, final Ip4Address contextAddr4, //
			final IpNet6Address subnet6, final Ip6Address hostAddr6, final Ip6Address contextAddr6) {
		super(firewall, // )
				subnet4, hostAddr4, //
				subnet6, hostAddr6);
		this.contextAddr4 = XStreamIp4Address.convert(contextAddr4);
		this.contextAddr6 = XStreamIp6Address.convert(contextAddr6);
	}

	@Override
	public Ip4Address getContextAddressV4() {
		return this.contextAddr4;
	}

	@Override
	public Ip6Address getContextAddressV6() {
		return this.contextAddr6;
	}
}
