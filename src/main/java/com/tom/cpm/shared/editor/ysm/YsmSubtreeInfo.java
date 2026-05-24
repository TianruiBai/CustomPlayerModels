package com.tom.cpm.shared.editor.ysm;

import com.tom.cpm.shared.editor.ysm.BedrockModelParser.BedrockBone;
import com.tom.cpm.shared.model.PlayerModelParts;

/**
 * Metadata for a YSM bone subtree that maps to a single CPM root part.
 *
 * <p>A "subtree" is a connected set of YSM bones that all belong under the
 * same CPM root part. Subtrees are identified by walking the YSM hierarchy
 * and detecting boundaries where a bone maps to a different CPM part than
 * its YSM parent.
 */
public class YsmSubtreeInfo {

	/** The root bone of this subtree (top-most bone in the YSM tree for this group). */
	public final BedrockBone rootBone;

	/** The CPM root part this entire subtree maps to. */
	public final PlayerModelParts targetPart;

	public YsmSubtreeInfo(BedrockBone rootBone, PlayerModelParts targetPart) {
		this.rootBone = rootBone;
		this.targetPart = targetPart;
	}

	@Override
	public String toString() {
		return "SubtreeInfo{root='" + rootBone.name + "', part=" + targetPart + "}";
	}
}
