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

import java.util.LinkedList;
import java.util.List;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.GET;

import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.util.HttpResponses;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

public class ScriptedLnxIsolationRuntime implements IsolationRuntime {

	private final String adminGroup;
	private final String overlayRootPath;
	private final List<OciMountConfig> overlayFsMounts;

	public ScriptedLnxIsolationRuntime() {
		this(null, null, null);
	}

	@DataBoundConstructor
	public ScriptedLnxIsolationRuntime(final String adminGroup, final String overlayRootPath,
			final List<OciMountConfig> overlayFsMounts) {
		this.adminGroup = Util.fixEmptyAndTrim(adminGroup);
		this.overlayRootPath = Util.fixEmptyAndTrim(overlayRootPath);
		this.overlayFsMounts = overlayFsMounts == null ? getDescriptor().defaultOverlayFsMounts()
				: new LinkedList<>(overlayFsMounts);
	}

	public String getAdminGroup() {
		return this.adminGroup;
	}

	public String getOverlayRootPath() {
		return this.overlayRootPath;
	}

	public List<OciMountConfig> getOverlayFsMounts() {
		return this.overlayFsMounts;
	}

	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl) IsolationRuntime.super.getDescriptor();
	}

	@Extension
	public static class DescriptorImpl extends Descriptor<IsolationRuntime> {
		@Override
		public String getDisplayName() {
			return "Linux: Script-managed OCI-Runtime";
		}

		@Override
		public IsolationRuntime newInstance(final StaplerRequest2 req, final JSONObject formData) throws FormException {
			// if the repeatableProperty is empty in jelly it will not be added,
			// which would cause the default values to be loaded!
			final Object overlayFsMounts = formData.opt("overlayFsMounts");
			formData.put("overlayFsMounts", overlayFsMounts == null ? new JSONArray() : overlayFsMounts);
			return super.newInstance(req, formData);
		}

		@Restricted(NoExternalUse.class)
		@GET
		public List<OciMountConfig> defaultOverlayFsMounts() {
			final List<OciMountConfig> lst = new LinkedList<>();
			lst.add(new OciMountConfig("tmpfs", "tmpfs", "/tmp") //
					.setOptions("nosuid", "nodev", "mode=1777", "size=1g"));
			lst.add(new OciMountConfig("tmpfs", "tmpfs", "/dev") //
					.setOptions("nosuid", "strictatime", "mode=755", "size=64m"));
			lst.add(new OciMountConfig("bind", "/dev/null", "/dev/null").setOptions("rbind", "rw"));
			lst.add(new OciMountConfig("bind", "/dev/zero", "/dev/zero").setOptions("rbind", "rw"));
			lst.add(new OciMountConfig("bind", "/dev/full", "/dev/full").setOptions("rbind", "rw"));
			lst.add(new OciMountConfig("bind", "/dev/random", "/dev/random").setOptions("rbind", "rw"));
			lst.add(new OciMountConfig("bind", "/dev/urandom", "/dev/urandom").setOptions("rbind", "rw"));
			lst.add(new OciMountConfig("tmpfs", "tmpfs", "/dev/shm") //
					.setOptions("nosuid", "nodev", "mode=1777", "size=64m"));
			lst.add(new OciMountConfig("tmpfs", "tmpfs", "/boot").setOptions("mode=700", "size=64k"));
			lst.add(new OciMountConfig("tmpfs", "tmpfs", "/root").setOptions("mode=700"));
			return lst;
		}

		@Restricted(NoExternalUse.class)
		@GET
		public HttpResponse doResetOverlayFsMounts() {
			return HttpResponses.okJSON(//
					JSONArray.fromObject(defaultOverlayFsMounts().stream()
							.map(OciMountConfig::toJSON)
							.toList()));
		}
	}
}
