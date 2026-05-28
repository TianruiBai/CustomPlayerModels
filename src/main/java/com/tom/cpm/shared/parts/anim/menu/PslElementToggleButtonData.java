package com.tom.cpm.shared.parts.anim.menu;

import java.io.IOException;

import com.tom.cpl.config.ConfigEntry;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.parts.anim.AnimLoaderState;
import com.tom.cpm.shared.psl.PslElement;

public class PslElementToggleButtonData extends BoolParameterToggleButtonData {
	public long pslElementId;
	private PslToggleAction pslAction;

	@Override
	protected void parseData(IOHelper block, AnimLoaderState state) throws IOException {
		super.parseData(block, state);
		pslElementId = block.readLong();
	}

	@Override
	public void onRegistered() {
		super.onRegistered();
		// Replace the parent's BitmaskParameterValueAction with our PSL toggle
		commandActions.clear();
		commandActions.add(pslAction = new PslToggleAction(name, pslElementId, command));
	}

	@Override
	public GestureButtonType getType() {
		return GestureButtonType.PSL_ELEMENT_TOGGLE;
	}

	@Override
	public void write(IOHelper block) throws IOException {
		super.write(block);
		block.writeLong(pslElementId);
	}

	public void setPslElement(PslElement element) {
		this.pslElementId = element.getId();
		this.name = element.getName() != null ? element.getName() : "PSL " + element.getId();
	}

	@Override
	public void toggle() {
		if (pslAction != null) pslAction.setValue(-1);
	}

	@Override
	public boolean getValue() {
		if (pslAction != null) return pslAction.getValue() > 0;
		ModelDefinition def = MinecraftClientAccess.get().getCurrentClientPlayer().getModelDefinition0();
		if (def != null && def.pslSystem != null) {
			PslElement elem = def.pslSystem.findElement(pslElementId);
			return elem != null && elem.isEnabled();
		}
		return true;
	}

	@Override
	public void onKeybind(String arg, boolean press, boolean toggleMode) {
		if (pslAction != null) {
			if (toggleMode) pslAction.setValue(-1);
			else pslAction.setValue(press ? 1 : 0);
		}
	}

	@Override
	public String getKeybindId() {
		return "psl" + pslElementId;
	}

	@Override
	public void storeTo(ConfigEntry ce) {
		if (pslAction != null) ce.setFloat(getKeybindId(), pslAction.getValue());
	}

	@Override
	public void loadFrom(ConfigEntry ce) {
		if (pslAction != null) pslAction.setValue(ce.getFloat(getKeybindId(), 1) > 0.5f ? 1 : 0);
	}
}
