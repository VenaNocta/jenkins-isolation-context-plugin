document.addEventListener("DOMContentLoaded", function() {
	const button = document.getElementById("overlayFsMountsReset");
	if (button) {
		button.addEventListener("click", function() {
			resetOverlayFsMounts(button);
		});
	}
	initializeMountVisibility();
});
document.addEventListener("change", function(event) {
	if (event.target.matches('[name="_.type2"]')) {
		const chunk = event.target.closest(".repeated-chunk");
		if (chunk) {
			updateMountVisibility(chunk);
		}
	}
});

function initializeMountVisibility() {
	const section = document.getElementById("overlayFsMountsSection");
	if (!section)
		return;
	section.querySelectorAll(".repeated-chunk").forEach(updateMountVisibility);
	const observer = new MutationObserver(mutations => {
		const chunks = new Set();
		for (const mutation of mutations) {
			const target = mutation.target;
			const element = target instanceof Element
				? target
				: target.parentElement;
			const parentChunk = element?.closest(".repeated-chunk");
			if (parentChunk)
				chunks.add(parentChunk);
			for (const node of mutation.addedNodes) {
				if (!(node instanceof HTMLElement))
					continue;
				if (node.matches(".repeated-chunk"))
					chunks.add(node);
				const containingChunk = node.closest(".repeated-chunk");
				if (containingChunk)
					chunks.add(containingChunk);
				node.querySelectorAll?.(".repeated-chunk").forEach(chunk => {
					chunks.add(chunk);
				});
			}
		}
		chunks.forEach(updateMountVisibility);
	});
	observer.observe(section, {
		childList: true,
		subtree: true
	});
}

function updateMountVisibility(chunk) {
	const type = chunk.querySelector('[name="_.type2"]')?.value;
	const altType = chunk.querySelector(".mount-altType-field");
	const source = chunk.querySelector(".mount-source-field");
	if (altType)
		altType.hidden = type === "tmpfs" || type === "bind";
	if (source)
		source.hidden = type === "tmpfs";
}

async function waitUntil(predicate, description, timeout = 5000) {
	const start = Date.now();
	while (!predicate()) {
		if (Date.now() - start > timeout)
			throw new Error("Timed out waiting for " + description);
		await new Promise(resolve => setTimeout(resolve, 25));
	}
}

async function waitForSelectOption(select, value) {
	await waitUntil(
		() => Array.from(select.options).some(option => option.value === String(value)),
		"select option " + value
	);
}

async function resetOverlayFsMounts(button) {
	const section = document.getElementById("overlayFsMountsSection");
	const container = section?.querySelector(".repeated-container");
	if (!section || !container) {
		console.error("OverlayFS repeatable container not found");
		return;
	}
	button.disabled = true;
	const activeChunks = () =>
		Array.from(container.querySelectorAll(
			":scope > .repeated-chunk:not(.to-be-removed)"
		));

	try {
		const response = await fetch(section.dataset.resetUrl, {
			headers: { Accept: "application/json" }
		});
		if (!response.ok)
			throw new Error("Failed to retrieve default mounts: " + response.status);
		const payload = await response.json();
		const mounts = payload?.data;
		if (payload?.status !== "ok" || !Array.isArray(mounts))
			throw new Error("Unexpected reset response: " + JSON.stringify(payload));

		let chunks = activeChunks();
		// Add rows only if there are fewer rows than defaults.
		const addButton = section.querySelector(
			".repeatable-add:not(.repeatable-add-top)"
		);
		while (chunks.length < mounts.length) {
			if (!addButton)
				throw new Error("Jenkins repeatable Add button not found");
			const countBefore = chunks.length;
			addButton.click();
			await waitUntil(
				() => activeChunks().length > countBefore,
				"new repeatable row"
			);
			chunks = activeChunks();
		}

		// Populate the rows that will be retained.
		for (let i = 0; i < mounts.length; i++) {
			const chunk = chunks[i];
			const mount = mounts[i];
			const typeSelect = chunk.querySelector('[name="_.type2"]');
			if (!typeSelect)
				throw new Error("Could not find type2 select in row " + i);

			await waitForSelectOption(typeSelect, mount.type2);
			setMountField(chunk, "type2", mount.type2);
			setMountField(chunk, "altType", mount.altType);
			setMountField(chunk, "source", mount.source);
			setMountField(chunk, "destination", mount.destination);
			setMountField(chunk, "options2", mount.options2);

			// Reapply Jenkins behaviors to the populated row.
			if (typeof Behaviour !== "undefined" &&
				typeof Behaviour.applySubtree === "function") {
				Behaviour.applySubtree(chunk);
			}
			updateMountVisibility(chunk);
		}

		// Delete surplus rows, starting from the end.
		// Do not wait for Jenkins to remove them from the DOM.
		chunks = activeChunks();

		for (let i = chunks.length - 1; i >= mounts.length; i--) {
			const deleteButton = chunks[i].querySelector(".repeatable-delete");
			if (!deleteButton)
				throw new Error("Could not find Delete button in row " + i);
			deleteButton.click();
		}
	} catch (error) {
		console.error("Could not reset OverlayFS mounts:", error);
		alert("Could not reset OverlayFS mounts. See the browser console.");
	} finally {
		button.disabled = false;
	}
}

function setMountField(chunk, field, value) {
	const input = chunk.querySelector(`[name="_.${field}"]`);
	if (!input)
		throw new Error("Could not find field: " + field);
	input.value = value ?? "";
}
