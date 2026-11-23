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
package org.jenkinsci.plugins.isolationctx.utils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

public class LazyAsyncRef<V> {

	private final Executor executor;
	private final BiFunction<V, Throwable, V> resolver;

	private CompletableFuture<V> future = null;

	public LazyAsyncRef(final Executor executor, final BiFunction<V, Throwable, V> resolver) {
		this.executor = executor;
		this.resolver = resolver;
	}

	public void reset() {
		synchronized (this) {
			this.future = this.future.handleAsync(this.resolver, this.executor);
		}
	}

	protected CompletableFuture<V> asFuture() {
		synchronized (this) {
			if (this.future == null) {
				this.future = CompletableFuture.supplyAsync(() -> this.resolver.apply(null, null), this.executor);
			}
			return this.future;
		}
	}

	public V get() throws ExecutionException, InterruptedException {
		return asFuture().get();
	}
}
