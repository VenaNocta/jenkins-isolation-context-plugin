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

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import edu.umd.cs.findbugs.annotations.NonNull;

public class LnxMountEntry implements Serializable, OciMountEntry {

	private static final long serialVersionUID = 1L;

	protected final List<String> options = new LinkedList<>();

	protected final String type;
	protected final String source;
	protected final String destination;

	public LnxMountEntry(@NonNull final String type, @NonNull final String source, @NonNull final String destination) {
		this.type = Objects.requireNonNull(type);
		this.source = Objects.requireNonNull(source);
		this.destination = Objects.requireNonNull(destination);
	}

	@Override
	public String getType() {
		return this.type;
	}

	@Override
	public String getSource() {
		return this.source;
	}

	@Override
	public String getDestination() {
		return this.destination;
	}

	@Override
	public List<String> getOptions() {
		return Collections.unmodifiableList(this.options);
	}

	public Stream<String> getOptionsAsStream() {
		return this.options.stream();
	}

	public LnxMountEntry options(final String... options) {
		if (options == null)
			return this;
		for (int i = 0; i < options.length; i++)
			this.options.add(options[i]);
		return this;
	}

	public LnxMountEntry addOptions(final Stream<String> options) {
		if (options == null)
			return this;
		options.filter(Objects::nonNull)
				.forEachOrdered(this.options::add);
		return this;
	}

	@Override
	public boolean equals(final Object obj) {
		if (this == obj)
			return true;
		if (!(obj instanceof LnxMountEntry))
			return false;
		final LnxMountEntry other = (LnxMountEntry) obj;

		return Objects.equals(this.type, other.type) && Objects.equals(this.source, other.source)
				&& Objects.equals(this.destination, other.destination) && this.options.size() == other.options.size()
				&& other.options.containsAll(this.options);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(this.type) ^ Objects.hashCode(this.source) ^ Objects.hashCode(this.destination);
	}
}