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
package org.jenkinsci.plugins.isolationctx.provider;

import org.jenkinsci.plugins.workflow.actions.WorkspaceAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStep;

import java.io.IOException;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.isolationctx.config.NodeIsolationProperty;
import org.jenkinsci.plugins.isolationctx.dto.IsolationContextAction;
import org.jenkinsci.plugins.isolationctx.IsolationContext;
import org.jenkinsci.plugins.isolationctx.UnsupportedContext;
import org.jenkinsci.plugins.isolationctx.config.ComputerIsolationStateAction;
import org.jenkinsci.plugins.isolationctx.handler.ComputerIsolationHandler;
import org.jenkinsci.plugins.isolationctx.hooks.IsolationContextRunListener;
import org.jenkinsci.plugins.variant.OptionalExtension;

import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;

@OptionalExtension(requireClasses = { ExecutorStep.class })
public class NodeIsolationCtxProvider implements IsolationContextProvider {

	@Override
	public boolean isApplicable(final StepDescriptor descriptor) {
		return descriptor instanceof ExecutorStep.DescriptorImpl;
	}

	@Override
	public IsolationContextBuilder createBuilder(final StepContext context) throws IOException, InterruptedException {
		return new Builder(context);
	}

	public static final class Builder implements IsolationContextBuilder {

		private static final long serialVersionUID = 1L;

		protected final transient StepContext _context;

		public Builder(final StepContext context) {
			this._context = context;
		}

		@Override
		public IsolationContext build(final FlowNode flowNode) throws IOException, InterruptedException {
			final WorkspaceAction ws = flowNode.getAction(WorkspaceAction.class);
			if (ws == null)
				return null;

			final Node node = StringUtils.trimToNull(ws.getNode()) == null ? Jenkins.get()
					: Jenkins.get()
							.getNode(ws.getNode());

			final Run<?, ?> run = (this._context == null ? //
					IsolationContextBuilder.findContext(flowNode) : this._context).get(Run.class);
			// bypass context isolation if allowed
			if (NodeIsolationProperty.isDisabled(node) && !IsolationContextRunListener.isIsolationForced(run)) {
				this._context.get(TaskListener.class)
						.getLogger()
						.println("INFO: Context Isolation is disabled");
				return null;
			}

			return selectIsolationContext(flowNode, node.toComputer(), this._context);
		}

		protected IsolationContext selectIsolationContext(final FlowNode flowNode, final Computer computer,
				final StepContext context) throws IOException, InterruptedException {
			final ComputerIsolationHandler isolationHandler;
			{
				final ComputerIsolationStateAction computerState = ComputerIsolationStateAction.get(computer);
				isolationHandler = computerState == null ? null : computerState.isolationHandler();
			}

			final Run<?, ?> run = context.get(Run.class);
			final TaskListener listener = context.get(TaskListener.class);

			if (isolationHandler == null) {
				// when the handler is null it usually means isolation is disabled on the Node
				listener.getLogger()
						.println("INFO: Context Isolation is not available");
				final IsolationContextAction action = run.getAction(IsolationContextAction.class);
				return action == null ? null : action.getContext();
			}

			if (isolationHandler.isActive() == false) {
				// when a handler is not active, something during the setup must have failed!
				listener.error("Context Isolation is currently unavailable, on this Node!");
				return new UnsupportedContext();
			}
			return isolationHandler.createIsolationContext(run, flowNode, computer, listener);
		}
	}
}
