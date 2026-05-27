package com.tom.cpm.shared.editor.gui;

import java.util.function.Consumer;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.elements.Button;
import com.tom.cpl.gui.elements.Checkbox;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.gui.elements.Spinner;
import com.tom.cpl.gui.elements.TextField;
import com.tom.cpl.gui.util.FlowLayout;
import com.tom.cpl.math.Box;
import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.gui.popup.ColorButton;
import com.tom.cpm.shared.psl.PslElement;
import com.tom.cpm.shared.psl.light.LightEmitter;
import com.tom.cpm.shared.psl.particle.ParticleEmitter;
import com.tom.cpm.shared.psl.particle.ParticleEmitter.BillboardMode;
import com.tom.cpm.shared.psl.particle.ParticleEmitter.BlendMode;
import com.tom.cpm.shared.psl.particle.ParticleEmitter.EmitterType;
import com.tom.cpm.shared.psl.particle.ParticleEmitter.ParticleSource;
import com.tom.cpm.shared.psl.particle.ParticleEmitter.PathMode;
import com.tom.cpm.shared.psl.physics.PhysicsBone;
import com.tom.cpm.shared.psl.physics.PhysicsBone.SimType;
import com.tom.cpm.shared.psl.sound.MidiEmitter;
import com.tom.cpm.shared.psl.sound.SoundEmitter;
import com.tom.cpm.shared.psl.sound.SoundEmitter.Attenuation;
import com.tom.cpm.shared.psl.sound.SoundEmitter.SoundCategory;

public class PslSettingsPanel extends Panel {
	private final Editor editor;
	private final EditorGui frm;
	private final FlowLayout layout;
	private final int formWidth;

	public PslSettingsPanel(IGui gui, EditorGui e, int width) {
		super(gui);
		this.editor = e.getEditor();
		this.frm = e;
		this.formWidth = Math.min(760, Math.max(420, width - 14));
		setBounds(new Box(0, 0, formWidth, 420));
		setBackgroundColor(gui.getColors().panel_background);
		layout = new FlowLayout(this, 5, 2);
	}

	public void refresh() {
		getElements().clear();
		PslElement selected = editor.selectedPslElement;
		if(selected == null) {
			addLabel("label.cpm.psl.noSelection");
			layout.reflow();
			return;
		}

		if(selected instanceof ParticleEmitter)particle((ParticleEmitter) selected);
		else if(selected instanceof PhysicsBone)physics((PhysicsBone) selected);
		else if(selected instanceof SoundEmitter)sound((SoundEmitter) selected);
		else if(selected instanceof MidiEmitter)midi((MidiEmitter) selected);
		else if(selected instanceof LightEmitter)light((LightEmitter) selected);

		layout.reflow();
	}

	private void particle(ParticleEmitter p) {
		section("label.cpm.psl.section.source");
		enumRow("label.cpm.psl.particle.source", p.getParticleSource().ordinal(), ParticleSource.VALUES, v -> p.setParticleSource(ParticleSource.VALUES[v]),
				"label.cpm.psl.particle.emitterType", p.getEmitterType().ordinal(), EmitterType.VALUES, v -> p.setEmitterType(EmitterType.VALUES[v]));
		particleAssetRow(p);
		enumRow("label.cpm.psl.particle.billboard", p.getBillboard().ordinal(), BillboardMode.VALUES, v -> p.setBillboard(BillboardMode.VALUES[v]),
				"label.cpm.psl.particle.blend", p.getBlendMode().ordinal(), BlendMode.VALUES, v -> p.setBlendMode(BlendMode.VALUES[v]));

		section("label.cpm.psl.section.emission");
		numberRow("label.cpm.psl.particle.rate", p.getRate(), 1, p::setRate, "label.cpm.psl.particle.maxParticles", p.getMaxParticles(), 0, v -> p.setMaxParticles((int) v.floatValue()));
		numberRow("label.cpm.psl.particle.lifeMin", p.getLifeMin(), 1, p::setLifeMin, "label.cpm.psl.particle.lifeMax", p.getLifeMax(), 1, p::setLifeMax);
		vec3("label.cpm.psl.particle.emitterSize", p.getEmitterSize());

		section("label.cpm.psl.section.motion");
		enumRow("label.cpm.psl.particle.pathMode", p.getPathMode().ordinal(), PathMode.VALUES, v -> p.setPathMode(PathMode.VALUES[v]), null, 0, null, null);
		text("label.cpm.psl.particle.pathAnimation", p.getPathAnimation(), p::setPathAnimation);
		checkRow("label.cpm.psl.particle.inheritTargetMotion", p.isInheritTargetMotion(), p::setInheritTargetMotion, null, false, null);
		vec3("label.cpm.psl.particle.velocity", p.getVelocity());
		numberRow("label.cpm.psl.particle.velocityVar", p.getVelocityVariation(), 2, p::setVelocityVariation, "label.cpm.psl.particle.gravity", p.getGravity(), 2, p::setGravity);
		checkRow("label.cpm.psl.particle.collision", p.isCollision(), p::setCollision, "label.cpm.psl.particle.respectGfx", p.isRespectGraphicsSetting(), p::setRespectGraphicsSetting);

		section("label.cpm.psl.section.appearance");
		numberRow("label.cpm.psl.particle.scaleStart", p.getScaleStart(), 2, p::setScaleStart, "label.cpm.psl.particle.scaleEnd", p.getScaleEnd(), 2, p::setScaleEnd);
		numberRow("label.cpm.psl.particle.alphaStart", p.getAlphaStart(), 2, p::setAlphaStart, "label.cpm.psl.particle.alphaEnd", p.getAlphaEnd(), 2, p::setAlphaEnd);
		numberRow("label.cpm.psl.particle.rotationStart", p.getRotationStart(), 1, p::setRotationStart, "label.cpm.psl.particle.rotationEnd", p.getRotationEnd(), 1, p::setRotationEnd);
		colorRow("label.cpm.psl.particle.colorStart", p.getColorStart(), c -> p.setColorStart(c));
		colorRow("label.cpm.psl.particle.colorEnd", p.getColorEnd(), c -> p.setColorEnd(c));
	}

	private void physics(PhysicsBone b) {
		section("label.cpm.psl.section.source");
		Panel target = row();
		addReadout(target, 0, fieldWidth(), "label.cpm.psl.parent", PslUiUtil.describeTarget(editor, b.getParentElementId()));
		addNumber(target, fieldWidth(), fieldWidth(), "label.cpm.psl.physics.parentId", b.getParentElementId(), 0, v -> b.setParentElementId((int) v.floatValue()));
		enumRow("label.cpm.psl.physics.simType", b.getSimType().ordinal(), SimType.VALUES, v -> b.setSimType(SimType.VALUES[v]), null, 0, null, null);
		checkRow("label.cpm.psl.physics.inherit", b.isInheritAnimation(), b::setInheritAnimation, null, false, null);

		section("label.cpm.psl.section.simulation");
		numberRow("label.cpm.psl.physics.gravity", b.getGravity(), 2, b::setGravity, "label.cpm.psl.physics.damping", b.getDamping(), 2, b::setDamping);
		numberRow("label.cpm.psl.physics.stiffness", b.getStiffness(), 2, b::setStiffness, "label.cpm.psl.physics.mass", b.getMass(), 2, b::setMass);
		numberRow("label.cpm.psl.physics.wind", b.getWindInfluence(), 2, b::setWindInfluence, "label.cpm.psl.physics.iterations", b.getIterations(), 0, v -> b.setIterations((int) v.floatValue()));

		section("label.cpm.psl.section.constraints");
		numberRow("label.cpm.psl.physics.collision", b.getCollisionRadius(), 2, b::setCollisionRadius, "label.cpm.psl.physics.maxStretch", b.getMaxStretch(), 2, b::setMaxStretch);
		vec3("label.cpm.psl.physics.limits", new Vec3f(b.getLimitAngleX(), b.getLimitAngleY(), b.getLimitAngleZ()), v -> {
			b.setLimitAngleX(v.x);
			b.setLimitAngleY(v.y);
			b.setLimitAngleZ(v.z);
		});
	}

	private void sound(SoundEmitter s) {
		section("label.cpm.psl.section.source");
		text("label.cpm.psl.sound.file", s.getSoundFile(), s::setSoundFile);
		previewSoundRow(s);
		enumRow("label.cpm.psl.sound.category", s.getCategory().ordinal(), SoundCategory.VALUES, v -> s.setCategory(SoundCategory.VALUES[v]),
				"label.cpm.psl.sound.attenuation", s.getAttenuation().ordinal(), Attenuation.VALUES, v -> s.setAttenuation(Attenuation.VALUES[v]));
		checkRow("label.cpm.psl.sound.loop", s.isLoop(), s::setLoop, "label.cpm.psl.sound.oneShot", s.isOneShot(), s::setOneShot);

		section("label.cpm.psl.section.playback");
		numberRow("label.cpm.psl.sound.volume", s.getVolume(), 2, s::setVolume, "label.cpm.psl.sound.pitch", s.getPitch(), 2, s::setPitch);
		numberRow("label.cpm.psl.sound.pitchVar", s.getPitchVariation(), 2, s::setPitchVariation, "label.cpm.psl.sound.cooldown", s.getCooldown(), 2, s::setCooldown);
		numberRow("label.cpm.psl.sound.loopDelay", s.getLoopDelay(), 2, s::setLoopDelay, "label.cpm.psl.sound.maxDist", s.getMaxDistance(), 1, s::setMaxDistance);
	}

	private void midi(MidiEmitter m) {
		section("label.cpm.psl.section.source");
		text("label.cpm.psl.midi.file", m.getMidiFile(), m::setMidiFile);
		enumRow("label.cpm.psl.midi.category", m.getCategory().ordinal(), SoundCategory.VALUES, v -> m.setCategory(SoundCategory.VALUES[v]), null, 0, null, null);
		checkRow("label.cpm.psl.midi.loop", m.isLoop(), m::setLoop, null, false, null);

		section("label.cpm.psl.section.playback");
		numberRow("label.cpm.psl.midi.tempo", m.getTempo(), 2, m::setTempo, "label.cpm.psl.midi.volume", m.getVolume(), 2, m::setVolume);
		numberRow("label.cpm.psl.midi.transpose", m.getTranspose(), 0, v -> m.setTranspose((int) v.floatValue()), "label.cpm.psl.midi.polyphony", m.getPolyphony(), 0, v -> m.setPolyphony((int) v.floatValue()));
		numberRow("label.cpm.psl.midi.loopDelay", m.getLoopDelay(), 2, m::setLoopDelay, "label.cpm.psl.midi.noteFalloff", m.getNoteFalloff(), 2, m::setNoteFalloff);
	}

	private void light(LightEmitter l) {
		section("label.cpm.psl.section.emission");
		colorRow("label.cpm.psl.light.color", l.getColor(), l::setColor);
		numberRow("label.cpm.psl.light.intensity", l.getIntensity(), 2, l::setIntensity, "label.cpm.psl.light.radius", l.getRadius(), 1, l::setRadius);
		checkRow("label.cpm.psl.light.dynamic", l.isDynamic(), l::setDynamic, "label.cpm.psl.light.shadows", l.isCastShadows(), l::setCastShadows);

		section("label.cpm.psl.section.animation");
		checkRow("label.cpm.psl.light.flicker", l.isFlicker(), l::setFlicker, null, false, null);
		numberRow("label.cpm.psl.light.flickerSpeed", l.getFlickerSpeed(), 2, l::setFlickerSpeed, "label.cpm.psl.light.flickerAmount", l.getFlickerAmount(), 2, l::setFlickerAmount);
	}

	private void section(String key) {
		Label label = new Label(gui, gui.i18nFormat(key));
		label.setBounds(new Box(4, 0, formWidth - 8, 12));
		addElement(label);
	}

	private void particleAssetRow(ParticleEmitter emitter) {
		Panel row = row();
		String id = emitter.isMinecraftParticle() ? emitter.getMinecraftParticle() : emitter.getTextureName();
		addReadout(row, 0, formWidth - 136, "label.cpm.psl.particle.asset", id != null ? id : gui.i18nFormat("label.cpm.psl.target.none"));
		Button select = new Button(gui, gui.i18nFormat("button.cpm.psl.selectParticle"), () -> {
			frm.openPopup(new PslParticlePickerPopup(frm, editor, (source, value) -> {
				emitter.setParticleSource(source);
				if(source == ParticleSource.MINECRAFT_BUILTIN)emitter.setMinecraftParticle(value);
				else emitter.setTextureName(value);
				editor.markDirty();
				editor.updateGui.accept(null);
			}));
		});
		select.setBounds(new Box(formWidth - 126, 2, 118, 18));
		row.addElement(select);
	}

	private void previewSoundRow(SoundEmitter sound) {
		Panel row = row();
		addReadout(row, 0, formWidth - 136, "label.cpm.psl.sound.previewTarget", sound.getSoundFile() != null ? sound.getSoundFile() : gui.i18nFormat("label.cpm.psl.target.none"));
		Button preview = new Button(gui, gui.i18nFormat("button.cpm.psl.previewSfx"), () -> PslPreviewUtil.previewSound(sound));
		preview.setBounds(new Box(formWidth - 126, 2, 118, 18));
		row.addElement(preview);
	}

	private void addLabel(String key) {
		Label label = new Label(gui, gui.i18nFormat(key));
		label.setBounds(new Box(4, 0, formWidth - 8, 12));
		addElement(label);
	}

	private Panel row() {
		Panel row = new Panel(gui);
		row.setBounds(new Box(0, 0, formWidth, 22));
		addElement(row);
		return row;
	}

	private int fieldWidth() {
		return (formWidth - 12) / 2;
	}

	private void text(String key, String value, Consumer<String> setter) {
		Panel row = row();
		Label label = new Label(gui, gui.i18nFormat(key));
		label.setBounds(new Box(4, 5, 116, 12));
		row.addElement(label);
		TextField field = new TextField(gui);
		field.setText(value != null ? value : "");
		field.setBounds(new Box(124, 2, formWidth - 132, 18));
		field.setEventListener(() -> {
			setter.accept(field.getText());
			editor.markDirty();
		});
		row.addElement(field);
	}

	private void numberRow(String keyA, float valueA, int dpA, Consumer<Float> setterA, String keyB, float valueB, int dpB, Consumer<Float> setterB) {
		Panel row = row();
		addNumber(row, 0, fieldWidth(), keyA, valueA, dpA, setterA);
		if(keyB != null)addNumber(row, fieldWidth(), fieldWidth(), keyB, valueB, dpB, setterB);
	}

	private Spinner addNumber(Panel row, int x, int width, String key, float value, int dp, Consumer<Float> setter) {
		Label label = new Label(gui, gui.i18nFormat(key));
		label.setBounds(new Box(x + 4, 5, 96, 12));
		row.addElement(label);
		Spinner spinner = new Spinner(gui);
		spinner.setDp(dp);
		spinner.setValue(value);
		spinner.setBounds(new Box(x + 104, 2, width - 110, 18));
		spinner.addChangeListener(() -> {
			setter.accept(spinner.getValue());
			editor.markDirty();
		});
		row.addElement(spinner);
		return spinner;
	}

	private void enumRow(String keyA, int valueA, Enum<?>[] valuesA, Consumer<Integer> setterA, String keyB, int valueB, Enum<?>[] valuesB, Consumer<Integer> setterB) {
		Panel row = row();
		addEnum(row, 0, fieldWidth(), keyA, valueA, valuesA, setterA);
		if(keyB != null)addEnum(row, fieldWidth(), fieldWidth(), keyB, valueB, valuesB, setterB);
	}

	private void addEnum(Panel row, int x, int width, String key, int value, Enum<?>[] values, Consumer<Integer> setter) {
		Label label = new Label(gui, gui.i18nFormat(key));
		label.setBounds(new Box(x + 4, 5, 80, 12));
		row.addElement(label);
		Spinner spinner = new Spinner(gui);
		spinner.setDp(0);
		spinner.setValue(value);
		spinner.setBounds(new Box(x + 86, 2, 42, 18));
		row.addElement(spinner);
		Label valueLabel = new Label(gui, enumName(values, value));
		valueLabel.setBounds(new Box(x + 132, 5, width - 136, 12));
		row.addElement(valueLabel);
		spinner.addChangeListener(() -> {
			int idx = Math.max(0, Math.min(values.length - 1, (int) spinner.getValue()));
			setter.accept(idx);
			valueLabel.setText(enumName(values, idx));
			editor.markDirty();
		});
	}

	private String enumName(Enum<?>[] values, int value) {
		if(values == null || values.length == 0)return "";
		int idx = Math.max(0, Math.min(values.length - 1, value));
		return values[idx].name();
	}

	private void checkRow(String keyA, boolean valueA, Consumer<Boolean> setterA, String keyB, boolean valueB, Consumer<Boolean> setterB) {
		Panel row = row();
		addCheck(row, 4, fieldWidth() - 8, keyA, valueA, setterA);
		if(keyB != null)addCheck(row, fieldWidth() + 4, fieldWidth() - 8, keyB, valueB, setterB);
	}

	private void addCheck(Panel row, int x, int width, String key, boolean value, Consumer<Boolean> setter) {
		Checkbox checkbox = new Checkbox(gui, gui.i18nFormat(key));
		checkbox.setSelected(value);
		checkbox.setBounds(new Box(x, 3, width, 16));
		checkbox.setAction(() -> {
			setter.accept(checkbox.isSelected());
			editor.markDirty();
		});
		row.addElement(checkbox);
	}

	private void vec3(String key, Vec3f vec) {
		vec3(key, vec, null);
	}

	private void vec3(String key, Vec3f vec, Consumer<Vec3f> setter) {
		Panel row = row();
		Label label = new Label(gui, gui.i18nFormat(key));
		label.setBounds(new Box(4, 5, 116, 12));
		row.addElement(label);
		Spinner x = axis(row, 124, vec.x);
		Spinner y = axis(row, 204, vec.y);
		Spinner z = axis(row, 284, vec.z);
		Runnable update = () -> {
			vec.x = x.getValue();
			vec.y = y.getValue();
			vec.z = z.getValue();
			if(setter != null)setter.accept(vec);
			editor.markDirty();
		};
		x.addChangeListener(update);
		y.addChangeListener(update);
		z.addChangeListener(update);
	}

	private Spinner axis(Panel row, int x, float value) {
		Spinner spinner = new Spinner(gui);
		spinner.setDp(2);
		spinner.setValue(value);
		spinner.setBounds(new Box(x, 2, 72, 18));
		row.addElement(spinner);
		return spinner;
	}

	private void colorRow(String key, int color, Consumer<Integer> setter) {
		Panel row = row();
		Label label = new Label(gui, gui.i18nFormat(key));
		label.setBounds(new Box(4, 5, 116, 12));
		row.addElement(label);
		ColorButton picker = new ColorButton(gui, gui.i18nFormat("button.cpm.psl.pickColor"), frm, c -> {
			setter.accept(c);
			editor.markDirty();
			editor.updateGui.accept(null);
		});
		picker.setColor(color);
		picker.setBounds(new Box(124, 2, 84, 18));
		row.addElement(picker);
		Spinner r = colorAxis(row, 214, (color >> 16) & 0xFF);
		Spinner g = colorAxis(row, 278, (color >> 8) & 0xFF);
		Spinner b = colorAxis(row, 342, color & 0xFF);
		Runnable update = () -> {
			int rv = clampColor(r.getValue());
			int gv = clampColor(g.getValue());
			int bv = clampColor(b.getValue());
			setter.accept((rv << 16) | (gv << 8) | bv);
			editor.markDirty();
		};
		r.addChangeListener(update);
		g.addChangeListener(update);
		b.addChangeListener(update);
	}

	private Spinner colorAxis(Panel row, int x, int value) {
		Spinner spinner = new Spinner(gui);
		spinner.setDp(0);
		spinner.setValue(value);
		spinner.setBounds(new Box(x, 2, 58, 18));
		row.addElement(spinner);
		return spinner;
	}

	private int clampColor(float value) {
		return Math.max(0, Math.min(255, (int) value));
	}

	private void addReadout(Panel row, int x, int width, String key, String value) {
		Label label = new Label(gui, gui.i18nFormat(key, value));
		label.setBounds(new Box(x + 4, 5, width - 8, 12));
		row.addElement(label);
	}
}
