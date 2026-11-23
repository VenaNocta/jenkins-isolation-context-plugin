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

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.FormValidation.Kind;
import hudson.util.ListBoxModel;
import hudson.util.ListBoxModel.Option;
import net.sf.json.JSONObject;

public class OciMountConfig implements Describable<OciMountConfig> {

	protected final List<String> options = new LinkedList<>();

	@NonNull
	protected final MountType type;
	@CheckForNull
	protected final String altType;
	@CheckForNull
	protected final String source;
	@CheckForNull
	protected final String destination;

	@DataBoundConstructor
	public OciMountConfig(final String type2, final String altType, final String source, final String destination) {
		this.type = MountType.fromKey(type2);
		this.altType = this.type.fixAltType(altType);
		this.source = this.type.fixSource(source);
		this.destination = this.type.fixDestination(destination);
	}

	public OciMountConfig(final String altType, final String source, final String destination) {
		this(MountType.fromKey(altType)
				.key(), altType, source, destination);
	}

	public MountType getType() {
		return this.type;
	}

	public String getType2() {
		return this.type.key();
	}

	public String getAltType() {
		return this.altType;
	}

	public String getSource() {
		return this.source;
	}

	public String getDestination() {
		return this.destination;
	}

	public List<String> getOptions() {
		return Collections.unmodifiableList(this.options);
	}

	public String getOptions2() {
		return getOptionsAsStream().collect(Collectors.joining(" | "));
	}

	public Stream<String> getOptionsAsStream() {
		return this.options.stream();
	}

	public OciMountConfig setOptions(final String... options) {
		synchronized (this.options) {
			this.options.clear();
			if (options == null)
				return this;
			this.type.fixOptions(Stream.of(options))
					.forEach(this.options::add);
		}
		return this;
	}

	@DataBoundSetter
	public void setOptions2(final String value) {
		if (value == null)
			return;
		synchronized (this.options) {
			this.options.clear();
			this.type.fixOptions(Stream.of(value.split("\\|")))
					.forEach(this.options::add);
		}
	}

	public boolean isValid() {
		return this.type.checkAltType(this.altType).kind != Kind.ERROR //
				&& this.type.checkSource(this.source).kind != Kind.ERROR //
				&& this.type.checkDestination(this.destination).kind != Kind.ERROR //
				&& this.type.checkOptions(this.options.iterator()).kind != Kind.ERROR;
	}

	@Override
	public boolean equals(final Object obj) {
		if (this == obj)
			return true;
		if (!(obj instanceof OciMountConfig))
			return false;
		final OciMountConfig other = (OciMountConfig) obj;

		return Objects.equals(this.type, other.type) && Objects.equals(this.source, other.source)
				&& Objects.equals(this.destination, other.destination) && this.options.size() == other.options.size()
				&& other.options.containsAll(this.options);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(this.type) ^ Objects.hashCode(this.source) ^ Objects.hashCode(this.destination);
	}

	public JSONObject toJSON() {
		final JSONObject json = new JSONObject();
		json.put("type2", getType2());
		json.put("altType", getAltType());
		json.put("source", getSource());
		json.put("destination", getDestination());
		json.put("options2", getOptions2());
		return json;
	}

	public static enum MountType {
		TMPFS("tmpfs") {
			@Override
			public String fixAltType(final String altType) {
				return key();
			}

			@Override
			public String fixSource(final String source) {
				return key();
			}
		},
		BIND("bind") {
			@Override
			public String fixAltType(final String altType) {
				return key();
			}
		},
		OTHER("---") {
			@Override
			public Option asOption() {
				return new Option("OTHER", key());
			}
		};

		private final String key;

		private MountType(final String key) {
			this.key = key;
		}

		public final String key() {
			return this.key;
		}

		public Option asOption() {
			return new Option(this.key.toUpperCase(), this.key);
		}

		public String fixAltType(final String altType) {
			return Util.fixEmptyAndTrim(altType);
		}

		public FormValidation checkAltType(final String altType) {
			return FormValidation.ok();
		}

		public String fixSource(final String source) {
			return Util.fixEmptyAndTrim(source);
		}

		public FormValidation checkSource(final String source) {
			return FormValidation.ok();
		}

		public String fixDestination(final String destination) {
			return Util.fixEmptyAndTrim(destination);
		}

		public FormValidation checkDestination(final String destination) {
			if (destination == null || destination.isEmpty() || destination.charAt(0) != '/')
				return FormValidation.error("The Destination path must be absolute!");
			return FormValidation.ok();
		}

		public Stream<String> fixOptions(final Stream<String> options) {
			return options.map(Util::fixEmptyAndTrim)
					.filter(Objects::nonNull);
		}

		public FormValidation checkOptions(final Iterator<String> options) {
			return FormValidation.ok();
		}

		public static MountType fromKey(final String key) {
			if (key == null || key.length() == 0)
				return OTHER;
			for (MountType type : values()) {
				if (key.equals(type.key()))
					return type;
			}
			return OTHER;
		}
	}

	@Extension
	public static class DescriptorImpl extends Descriptor<OciMountConfig> {
		@Override
		public String getDisplayName() {
			return "Raw Mount Entry";
		}

		public ListBoxModel doFillType2Items() {
			final ListBoxModel dropdown = new ListBoxModel();
			for (MountType mountType : MountType.values()) {
				dropdown.add(mountType.asOption());
			}
			return dropdown;
		}

		@Restricted(NoExternalUse.class)
		@POST
		public FormValidation doCheckAltType(@QueryParameter final String type2, @QueryParameter final String value) {
			final MountType type = MountType.fromKey(type2);
			return type.checkAltType(type.fixAltType(value));
		}

		@Restricted(NoExternalUse.class)
		@POST
		public FormValidation doCheckSource(@QueryParameter final String type2, @QueryParameter final String value) {
			final MountType type = MountType.fromKey(type2);
			return type.checkSource(type.fixSource(value));
		}

		@Restricted(NoExternalUse.class)
		@POST
		public FormValidation doCheckDestination(@QueryParameter final String type2,
				@QueryParameter final String value) {
			final MountType type = MountType.fromKey(type2);
			return type.checkDestination(type.fixDestination(value));
		}

		@Restricted(NoExternalUse.class)
		@POST
		public FormValidation doCheckOptions2(@QueryParameter final String type2, @QueryParameter final String value) {
			final MountType type = MountType.fromKey(type2);
			return type.checkOptions(type.fixOptions(Stream.of(value.split("\\|")))
					.iterator());
		}
	}
}