package com.tom.cpm.shared.parts;

import java.io.IOException;

import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.psl.PslSystem;
import com.tom.cpm.shared.psl.io.PslIO;

/**
 * Stores PSL definitions inside the normal model part stream.
 */
public class ModelPartPsl implements IModelPart, IResolvedModelPart {
	private PslSystem system;

	public ModelPartPsl(IOHelper in, ModelDefinition def) throws IOException {
		system = new PslSystem();
		PslIO.read(system, in.readByteArray());
	}

	public ModelPartPsl(PslSystem system) {
		this.system = system;
	}

	@Override
	public IResolvedModelPart resolve() throws IOException {
		return this;
	}

	@Override
	public void write(IOHelper dout) throws IOException {
		dout.writeByteArray(PslIO.write(system));
	}

	@Override
	public ModelPartType getType() {
		return ModelPartType.PSL;
	}

	@Override
	public void apply(ModelDefinition def) {
		def.pslSystem = system;
	}

	@Override
	public String toString() {
		return "PSL elements: " + (system != null ? system.size() : 0);
	}
}