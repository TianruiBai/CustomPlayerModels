package com.tom.cpm.shared.psl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Core system managing all PSL (Particle · Physics · Sound · Light) elements for a model.
 * Provides registration, iteration, and lifecycle management.
 */
public class PslSystem {

	private final List<PslElement> elements = new ArrayList<>();
	private boolean dirty;

	/**
	 * Add a PSL element to the system.
	 */
	public void addElement(PslElement element) {
		elements.add(element);
		dirty = true;
	}

	/**
	 * Remove a PSL element by ID.
	 */
	public boolean removeElement(long id) {
		boolean removed = elements.removeIf(e -> e.getId() == id);
		if (removed) dirty = true;
		return removed;
	}

	/**
	 * Get an unmodifiable view of all PSL elements.
	 */
	public List<PslElement> getElements() {
		return Collections.unmodifiableList(elements);
	}

	/**
	 * Get all elements of a specific type.
	 */
	@SuppressWarnings("unchecked")
	public <T extends PslElement> List<T> getElementsOfType(PslElementType type) {
		List<T> result = new ArrayList<>();
		for (PslElement e : elements) {
			if (e.getType() == type) {
				result.add((T) e);
			}
		}
		return result;
	}

	/**
	 * Find an element by its ID.
	 */
	public PslElement getElement(long id) {
		for (PslElement e : elements) {
			if (e.getId() == id) return e;
		}
		return null;
	}

	/**
	 * Replace all elements with a new list.
	 */
	public void setElements(List<PslElement> elements) {
		this.elements.clear();
		if (elements != null) {
			this.elements.addAll(elements);
		}
		dirty = true;
	}

	/**
	 * Clear all elements.
	 */
	public void clear() {
		elements.clear();
		dirty = true;
	}

	/**
	 * Check if any elements exist.
	 */
	public boolean isEmpty() {
		return elements.isEmpty();
	}

	/**
	 * Get the total number of elements.
	 */
	public int size() {
		return elements.size();
	}

	/**
	 * Check if the system has been modified since last save.
	 */
	public boolean isDirty() {
		return dirty;
	}

	/**
	 * Mark as clean (e.g., after successful save).
	 */
	public void markClean() {
		dirty = false;
	}

	/**
	 * Tick the PSL system (called each game tick from AnimationEngine).
	 * This is a placeholder for Phase 2+ runtime integration.
	 */
	public void tick(PslTriggerState state, IPslRuntime runtime) {
		// Phase 2+: Iterate active elements and drive their runtimes
	}
}
