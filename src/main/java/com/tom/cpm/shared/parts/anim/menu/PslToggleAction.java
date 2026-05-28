package com.tom.cpm.shared.parts.anim.menu;

import com.tom.cpl.nbt.NBTTagCompound;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.psl.PslElement;

public class PslToggleAction implements CommandAction {
	private final String name;
	public final long pslElementId;
	private boolean cc;

	public PslToggleAction(String name, long pslElementId, boolean cc) {
		this.name = name;
		this.pslElementId = pslElementId;
		this.cc = cc;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void write(NBTTagCompound tag) {
		tag.setLong("pslId", pslElementId);
		tag.setBoolean("cc", cc);
	}

	@Override
	public ActionType getType() {
		return ActionType.SIMPLE;
	}

	@Override
	public int getValue() {
		ModelDefinition def = MinecraftClientAccess.get().getCurrentClientPlayer().getModelDefinition0();
		if (def != null && def.pslSystem != null) {
			PslElement elem = def.pslSystem.findElement(pslElementId);
			return (elem != null && elem.isEnabled()) ? 1 : 0;
		}
		return 1;
	}

	@Override
	public void setValue(int v) {
		ModelDefinition def = MinecraftClientAccess.get().getCurrentClientPlayer().getModelDefinition0();
		if (def != null && def.pslSystem != null) {
			PslElement elem = def.pslSystem.findElement(pslElementId);
			if (elem != null) {
				if (v == -1) elem.setEnabled(!elem.isEnabled());
				else elem.setEnabled(v != 0);
			}
		}
	}

	@Override
	public int getMaxValue() {
		return 1;
	}

	@Override
	public boolean isCommandControlled() {
		return cc;
	}
}
