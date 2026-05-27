package com.tom.cpm.shared.editor.gui;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.elements.Checkbox;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.gui.elements.Spinner;
import com.tom.cpl.gui.elements.TextField;
import com.tom.cpl.gui.elements.Tooltip;
import com.tom.cpl.gui.util.FlowLayout;
import com.tom.cpl.math.Box;
import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.psl.particle.ParticleEmitter;
import com.tom.cpm.shared.psl.particle.ParticleEmitter.BillboardMode;
import com.tom.cpm.shared.psl.particle.ParticleEmitter.BlendMode;
import com.tom.cpm.shared.psl.particle.ParticleEmitter.EmitterType;

/**
 * Properties panel for editing a ParticleEmitter.
 * Shows particle-specific settings: texture, rate, lifetime, color, alpha, etc.
 */
public class ParticlePropertiesPanel extends Panel {
	private Editor editor;
	private EditorGui frm;
	private FlowLayout layout;

	private TextField textureField;
	private Spinner rateSpinner, maxParticlesSpinner, lifeMinSpinner, lifeMaxSpinner;
	private Spinner velX, velY, velZ, velVarSpinner;
	private Spinner gravitySpinner, scaleStartSpinner, scaleEndSpinner;
	private Spinner alphaStartSpinner, alphaEndSpinner, rotStartSpinner, rotEndSpinner;
	private Checkbox collisionCb, respectGfxCb;
	private Spinner emitterTypeSpinner, billboardSpinner, blendSpinner;

	public ParticlePropertiesPanel(IGui gui, EditorGui e) {
		super(gui);
		this.editor = e.getEditor();
		this.frm = e;
		setBounds(new Box(0, 0, 170, 350));
		setBackgroundColor(gui.getColors().panel_background);
		layout = new FlowLayout(this, 3, 1);

		// Texture
		addLabel("label.cpm.psl.particle.texture");
		textureField = new TextField(gui);
		textureField.setBounds(new Box(2, 0, 164, 18));
		addElement(textureField);

		// Emitter type
		addLabel("label.cpm.psl.particle.emitterType");
		emitterTypeSpinner = addSpinnerInt(0, 2, 1, v -> get().setEmitterType(EmitterType.VALUES[v]));

		// Rate & Max particles
		addLabel("label.cpm.psl.particle.rate");
		rateSpinner = addSpinnerFloat(0, 100, 10, 1, v -> get().setRate(v));
		addLabel("label.cpm.psl.particle.maxParticles");
		maxParticlesSpinner = addSpinnerInt(0, 500, 50, v -> get().setMaxParticles(v));

		// Lifetime
		addLabel("label.cpm.psl.particle.lifeMin");
		lifeMinSpinner = addSpinnerFloat(0, 60, 0.5f, 1, v -> get().setLifeMin(v));
		addLabel("label.cpm.psl.particle.lifeMax");
		lifeMaxSpinner = addSpinnerFloat(0, 60, 1.5f, 1, v -> get().setLifeMax(v));

		// Velocity
		addLabel("label.cpm.psl.particle.velocity");
		Panel velRow = new Panel(gui);
		velRow.setBounds(new Box(0, 0, 170, 18));
		velX = makeSmallSpinner(2, 0, v -> get().getVelocity().x = v);
		velY = makeSmallSpinner(42, 0, v -> get().getVelocity().y = v);
		velZ = makeSmallSpinner(82, 0, v -> get().getVelocity().z = v);
		velRow.addElement(velX); velRow.addElement(velY); velRow.addElement(velZ);
		addElement(velRow);

		// Velocity variation
		addLabel("label.cpm.psl.particle.velocityVar");
		velVarSpinner = addSpinnerFloat(0, 1, 0.5f, 2, v -> get().setVelocityVariation(v));

		// Gravity
		addLabel("label.cpm.psl.particle.gravity");
		gravitySpinner = addSpinnerFloat(-2, 2, 0, 2, v -> get().setGravity(v));

		// Scale
		addLabel("label.cpm.psl.particle.scale");
		scaleStartSpinner = addSpinnerFloat(0, 10, 1, 2, v -> get().setScaleStart(v));
		scaleEndSpinner = addSpinnerFloat(0, 10, 0, 2, v -> get().setScaleEnd(v));

		// Alpha
		addLabel("label.cpm.psl.particle.alpha");
		alphaStartSpinner = addSpinnerFloat(0, 1, 1, 2, v -> get().setAlphaStart(v));
		alphaEndSpinner = addSpinnerFloat(0, 1, 0, 2, v -> get().setAlphaEnd(v));

		// Rotation
		addLabel("label.cpm.psl.particle.rotation");
		rotStartSpinner = addSpinnerFloat(0, 360, 0, 1, v -> get().setRotationStart(v));
		rotEndSpinner = addSpinnerFloat(0, 360, 360, 1, v -> get().setRotationEnd(v));

		// Billboard mode
		addLabel("label.cpm.psl.particle.billboard");
		billboardSpinner = addSpinnerInt(0, BillboardMode.VALUES.length - 1, BillboardMode.CENTER.ordinal(),
			v -> get().setBillboard(BillboardMode.VALUES[v]));

		// Blend mode
		addLabel("label.cpm.psl.particle.blend");
		blendSpinner = addSpinnerInt(0, BlendMode.VALUES.length - 1, BlendMode.ALPHA.ordinal(),
			v -> get().setBlendMode(BlendMode.VALUES[v]));

		// Checkboxes
		collisionCb = new Checkbox(gui, gui.i18nFormat("label.cpm.psl.particle.collision"));
		collisionCb.setBounds(new Box(2, 0, 164, 16));
		collisionCb.setAction(() -> { if (get() != null) get().setCollision(collisionCb.isSelected()); });
		addElement(collisionCb);

		respectGfxCb = new Checkbox(gui, gui.i18nFormat("label.cpm.psl.particle.respectGfx"));
		respectGfxCb.setBounds(new Box(2, 0, 164, 16));
		respectGfxCb.setAction(() -> { if (get() != null) get().setRespectGraphicsSetting(respectGfxCb.isSelected()); });
		addElement(respectGfxCb);
	}

	private ParticleEmitter get() {
		if (editor.selectedPslElement instanceof ParticleEmitter)
			return (ParticleEmitter) editor.selectedPslElement;
		return null;
	}

	public void refresh() {
		ParticleEmitter p = get();
		boolean en = p != null;
		textureField.setEnabled(en);
		if (en) textureField.setText(p.getTextureName() != null ? p.getTextureName() : "");
		rateSpinner.setEnabled(en); if (en) rateSpinner.setValue(p.getRate());
		maxParticlesSpinner.setEnabled(en); if (en) maxParticlesSpinner.setValue(p.getMaxParticles());
		lifeMinSpinner.setEnabled(en); if (en) lifeMinSpinner.setValue(p.getLifeMin());
		lifeMaxSpinner.setEnabled(en); if (en) lifeMaxSpinner.setValue(p.getLifeMax());
		velX.setEnabled(en); if (en) velX.setValue(p.getVelocity().x);
		velY.setEnabled(en); if (en) velY.setValue(p.getVelocity().y);
		velZ.setEnabled(en); if (en) velZ.setValue(p.getVelocity().z);
		velVarSpinner.setEnabled(en); if (en) velVarSpinner.setValue(p.getVelocityVariation());
		gravitySpinner.setEnabled(en); if (en) gravitySpinner.setValue(p.getGravity());
		scaleStartSpinner.setEnabled(en); if (en) scaleStartSpinner.setValue(p.getScaleStart());
		scaleEndSpinner.setEnabled(en); if (en) scaleEndSpinner.setValue(p.getScaleEnd());
		alphaStartSpinner.setEnabled(en); if (en) alphaStartSpinner.setValue(p.getAlphaStart());
		alphaEndSpinner.setEnabled(en); if (en) alphaEndSpinner.setValue(p.getAlphaEnd());
		rotStartSpinner.setEnabled(en); if (en) rotStartSpinner.setValue(p.getRotationStart());
		rotEndSpinner.setEnabled(en); if (en) rotEndSpinner.setValue(p.getRotationEnd());
		collisionCb.setEnabled(en); if (en) collisionCb.setSelected(p.isCollision());
		respectGfxCb.setEnabled(en); if (en) respectGfxCb.setSelected(p.isRespectGraphicsSetting());
	}

	private void addLabel(String key) {
		Label l = new Label(gui, gui.i18nFormat(key));
		l.setBounds(new Box(2, 0, 164, 12));
		addElement(l);
	}

	private Spinner addSpinnerFloat(float min, float max, float def, int dp, java.util.function.Consumer<Float> onChange) {
		Spinner s = new Spinner(gui);
		s.setBounds(new Box(2, 0, 164, 18));
		s.setDp(dp);
		s.setValue(def);
		s.addChangeListener(() -> { if (get() != null) onChange.accept(s.getValue()); });
		addElement(s);
		return s;
	}

	private Spinner addSpinnerInt(int min, int max, int def, java.util.function.Consumer<Integer> onChange) {
		Spinner s = new Spinner(gui);
		s.setBounds(new Box(2, 0, 164, 18));
		s.setDp(0);
		s.setValue(def);
		s.addChangeListener(() -> { if (get() != null) onChange.accept((int) s.getValue()); });
		addElement(s);
		return s;
	}

	private Spinner makeSmallSpinner(int x, float def, java.util.function.Consumer<Float> onChange) {
		Spinner s = new Spinner(gui);
		s.setBounds(new Box(x, 0, 38, 18));
		s.setDp(1);
		s.setValue(def);
		s.addChangeListener(() -> { if (get() != null) onChange.accept(s.getValue()); });
		return s;
	}
}
