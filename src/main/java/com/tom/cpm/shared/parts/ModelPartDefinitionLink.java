package com.tom.cpm.shared.parts;

import java.io.IOException;

import com.tom.cpm.shared.definition.Link;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.io.IOHelper;

@Deprecated
public class ModelPartDefinitionLink extends ModelPartLink {

	public ModelPartDefinitionLink(IOHelper in, ModelDefinition def) throws IOException {
		super(in, def);
	}

	public ModelPartDefinitionLink(Link link) {
		super(link);
	}

	@Override
	public ModelPartType getType() {
		return ModelPartType.DEFINITION_LINK;
	}

	@Override
	protected IModelPart load(IOHelper din, ModelDefinition def) throws IOException {
		try {
			IModelPart part = din.readObjectBlock(ModelPartType.VALUES, (t, d) -> t.getFactory().create(d, def));
			if (part instanceof ModelPartDefinition) {
				while (true) {
					IModelPart extra = din.readObjectBlock(ModelPartType.VALUES, (t, d) -> t.getFactory().create(d, def));
					if (extra == null) continue;
					if (extra instanceof ModelPartEnd) break;
					throw new IOException("Invalid tag after linked definition: " + extra.getType());
				}
				return part;
			}
		} catch (IOException e) {
			din.reset();
		}
		return new ModelPartDefinition(din, def);
	}
}
