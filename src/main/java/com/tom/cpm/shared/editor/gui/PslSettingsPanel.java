package com.tom.cpm.shared.editor.gui;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.elements.Button;
import com.tom.cpl.gui.elements.Checkbox;
import com.tom.cpl.gui.elements.DropDownBox;
import com.tom.cpl.gui.elements.Label;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.gui.elements.Spinner;
import com.tom.cpl.gui.elements.TextField;
import com.tom.cpl.gui.util.FlowLayout;
import com.tom.cpl.math.Box;
import com.tom.cpl.math.Vec3f;
import com.tom.cpl.util.Image;
import com.tom.cpl.util.ImageIO;
import com.tom.cpl.util.NamedElement;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.gui.popup.ColorButton;
import com.tom.cpm.shared.parts.anim.menu.AbstractGestureButtonData;
import com.tom.cpm.shared.parts.anim.menu.PslElementToggleButtonData;
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

		section("label.cpm.psl.section.general");
		checkRow("label.cpm.psl.element.enabled", selected.isEnabled(), v -> {
			selected.setEnabled(v);
			editor.markDirty();
		}, "label.cpm.psl.element.gestureToggle", isGestureToggleRegistered(selected), v -> {
			setGestureToggle(selected, v);
			editor.markDirty();
		});

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
		constrainedNumberRow("label.cpm.psl.particle.rate", p.getRate(), 1, 0f, null, p::setRate, "label.cpm.psl.particle.maxParticles", p.getMaxParticles(), 0, 1f, null, v -> p.setMaxParticles((int) v.floatValue()));
		constrainedNumberRow("label.cpm.psl.particle.lifeMin", p.getLifeMin(), 1, 0f, null, v -> p.setLifeMin(Math.min(v, p.getLifeMax())), "label.cpm.psl.particle.lifeMax", p.getLifeMax(), 1, 0f, null, v -> p.setLifeMax(Math.max(v, p.getLifeMin())));
		constrainedNumberRow("label.cpm.psl.particle.playbackSpeed", p.getPlaybackSpeed(), 2, 0.1f, 10f, p::setPlaybackSpeed, null, 0f, 0, null, null, null);
		vec3("label.cpm.psl.particle.emitterSize", p.getEmitterSize(), 0f, null, null);
		vec3("label.cpm.psl.particle.offset", p.getOffset());

		section("label.cpm.psl.section.motion");
		enumRow("label.cpm.psl.particle.pathMode", p.getPathMode().ordinal(), PathMode.VALUES, v -> p.setPathMode(PathMode.VALUES[v]), null, 0, null, null);
		text("label.cpm.psl.particle.pathAnimation", p.getPathAnimation(), p::setPathAnimation);
		checkRow("label.cpm.psl.particle.inheritTargetMotion", p.isInheritTargetMotion(), p::setInheritTargetMotion, null, false, null);
		vec3("label.cpm.psl.particle.velocity", p.getVelocity());
		constrainedNumberRow("label.cpm.psl.particle.velocityVar", p.getVelocityVariation(), 2, 0f, null, p::setVelocityVariation, "label.cpm.psl.particle.gravity", p.getGravity(), 2, 0f, null, p::setGravity);
		buttonRow("label.cpm.psl.particle.windConfig", () -> frm.openPopup(new PslWindPopup(frm, p, () -> editor.markDirty())));
		checkRow("label.cpm.psl.particle.collision", p.isCollision(), p::setCollision, "label.cpm.psl.particle.respectGfx", p.isRespectGraphicsSetting(), p::setRespectGraphicsSetting);

		section("label.cpm.psl.section.appearance");
		constrainedNumberRow("label.cpm.psl.particle.scaleStart", p.getScaleStart(), 2, 0f, null, p::setScaleStart, "label.cpm.psl.particle.scaleEnd", p.getScaleEnd(), 2, 0f, null, p::setScaleEnd);
		constrainedNumberRow("label.cpm.psl.particle.alphaStart", p.getAlphaStart(), 2, 0f, 1f, p::setAlphaStart, "label.cpm.psl.particle.alphaEnd", p.getAlphaEnd(), 2, 0f, 1f, p::setAlphaEnd);
		buttonRow("label.cpm.psl.particle.rotationConfig", () -> frm.openPopup(new PslRotationPopup(frm, p, () -> editor.markDirty())));
		colorRow("label.cpm.psl.particle.colorStart", p.getColorStart(), c -> p.setColorStart(c));
		colorRow("label.cpm.psl.particle.colorEnd", p.getColorEnd(), c -> p.setColorEnd(c));
	}

	private void physics(PhysicsBone b) {
		section("label.cpm.psl.section.source");
		Panel target = row();
		addReadout(target, 0, fieldWidth(), "label.cpm.psl.parent", PslUiUtil.describeTarget(editor, b.getParentElementId()));
		addNumber(target, fieldWidth(), fieldWidth(), "label.cpm.psl.physics.parentId", b.getParentElementId(), 0, 0f, null, v -> b.setParentElementId((int) v.floatValue()));
		enumRow("label.cpm.psl.physics.simType", b.getSimType().ordinal(), SimType.VALUES, v -> b.setSimType(SimType.VALUES[v]), null, 0, null, null);
		checkRow("label.cpm.psl.physics.inherit", b.isInheritAnimation(), b::setInheritAnimation, null, false, null);

		section("label.cpm.psl.section.simulation");
		constrainedNumberRow("label.cpm.psl.physics.gravity", b.getGravity(), 2, 0f, null, b::setGravity, "label.cpm.psl.physics.damping", b.getDamping(), 2, 0f, 1f, b::setDamping);
		constrainedNumberRow("label.cpm.psl.physics.stiffness", b.getStiffness(), 2, 0f, 1f, b::setStiffness, "label.cpm.psl.physics.mass", b.getMass(), 2, 0.01f, null, b::setMass);
		constrainedNumberRow("label.cpm.psl.physics.wind", b.getWindInfluence(), 2, 0f, 1f, b::setWindInfluence, "label.cpm.psl.physics.iterations", b.getIterations(), 0, 1f, 10f, v -> b.setIterations((int) v.floatValue()));

		section("label.cpm.psl.section.constraints");
		constrainedNumberRow("label.cpm.psl.physics.collision", b.getCollisionRadius(), 2, 0f, null, b::setCollisionRadius, "label.cpm.psl.physics.maxStretch", b.getMaxStretch(), 2, 1f, null, b::setMaxStretch);
		vec3("label.cpm.psl.physics.limits", new Vec3f(b.getLimitAngleX(), b.getLimitAngleY(), b.getLimitAngleZ()), 0f, 180f, v -> {
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
		constrainedNumberRow("label.cpm.psl.sound.volume", s.getVolume(), 2, 0f, 1f, s::setVolume, "label.cpm.psl.sound.pitch", s.getPitch(), 2, 0.5f, 2f, s::setPitch);
		constrainedNumberRow("label.cpm.psl.sound.pitchVar", s.getPitchVariation(), 2, 0f, null, s::setPitchVariation, "label.cpm.psl.sound.cooldown", s.getCooldown(), 2, 0f, null, s::setCooldown);
		constrainedNumberRow("label.cpm.psl.sound.loopDelay", s.getLoopDelay(), 2, 0f, null, s::setLoopDelay, "label.cpm.psl.sound.maxDist", s.getMaxDistance(), 1, 0f, null, s::setMaxDistance);
	}

	private void midi(MidiEmitter m) {
		section("label.cpm.psl.section.source");
		text("label.cpm.psl.midi.file", m.getMidiFile(), m::setMidiFile);
		enumRow("label.cpm.psl.midi.category", m.getCategory().ordinal(), SoundCategory.VALUES, v -> m.setCategory(SoundCategory.VALUES[v]), null, 0, null, null);
		checkRow("label.cpm.psl.midi.loop", m.isLoop(), m::setLoop, null, false, null);

		section("label.cpm.psl.section.playback");
		constrainedNumberRow("label.cpm.psl.midi.tempo", m.getTempo(), 2, 0.5f, 2f, m::setTempo, "label.cpm.psl.midi.volume", m.getVolume(), 2, 0f, 1f, m::setVolume);
		constrainedNumberRow("label.cpm.psl.midi.transpose", m.getTranspose(), 0, -24f, 24f, v -> m.setTranspose((int) v.floatValue()), "label.cpm.psl.midi.polyphony", m.getPolyphony(), 0, 1f, 32f, v -> m.setPolyphony((int) v.floatValue()));
		constrainedNumberRow("label.cpm.psl.midi.loopDelay", m.getLoopDelay(), 2, 0f, null, m::setLoopDelay, "label.cpm.psl.midi.noteFalloff", m.getNoteFalloff(), 2, 0f, null, m::setNoteFalloff);
	}

	private void light(LightEmitter l) {
		section("label.cpm.psl.section.emission");
		colorRow("label.cpm.psl.light.color", l.getColor(), l::setColor);
		constrainedNumberRow("label.cpm.psl.light.intensity", l.getIntensity(), 2, 0f, 1f, l::setIntensity, "label.cpm.psl.light.radius", l.getRadius(), 1, 1f, 15f, l::setRadius);
		checkRow("label.cpm.psl.light.dynamic", l.isDynamic(), l::setDynamic, "label.cpm.psl.light.shadows", l.isCastShadows(), l::setCastShadows);

		section("label.cpm.psl.section.animation");
		checkRow("label.cpm.psl.light.flicker", l.isFlicker(), l::setFlicker, null, false, null);
		constrainedNumberRow("label.cpm.psl.light.flickerSpeed", l.getFlickerSpeed(), 2, 0f, null, l::setFlickerSpeed, "label.cpm.psl.light.flickerAmount", l.getFlickerAmount(), 2, 0f, 1f, l::setFlickerAmount);
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
			frm.openPopup(new PslParticlePickerPopup(frm, editor, emitter.getParticleSource(), id, (source, value) -> {
				setParticleAsset(emitter, source, value);
				editor.markDirty();
				editor.updateGui.accept(null);
			}));
		});
		select.setBounds(new Box(formWidth - 126, 2, 118, 18));
		row.addElement(select);
	}

	private void setParticleAsset(ParticleEmitter emitter, ParticleSource source, String value) {
		emitter.setParticleSource(source);
		if(source == ParticleSource.MINECRAFT_BUILTIN)emitter.setMinecraftParticle(value);
		else emitter.setTextureName(value);
		Image image = loadParticleAssetImage(source, value);
		if(image != null && image.getWidth() > 0 && image.getHeight() > 0) {
			emitter.setSpriteU(0);
			emitter.setSpriteV(0);
			emitter.setSpriteWidth(image.getWidth());
			emitter.setSpriteHeight(image.getHeight());
			emitter.setSpriteTexW(image.getWidth());
			emitter.setSpriteTexH(image.getHeight());
		}
	}

	private Image loadParticleAssetImage(ParticleSource source, String value) {
		try {
			if(source == ParticleSource.CUSTOM_SPRITE) {
				byte[] data = editor.project.getEntry(value);
				return data != null ? ImageIO.read(new ByteArrayInputStream(data)) : null;
			}
			return MinecraftClientAccess.get().getPslRuntime().loadParticleImage(value);
		} catch (Exception ignored) {
			return null;
		}
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

	private void buttonRow(String key, Runnable action) {
		Panel row = row();
		Button btn = new Button(gui, gui.i18nFormat(key), action);
		btn.setBounds(new Box(4, 2, formWidth - 12, 18));
		row.addElement(btn);
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
		constrainedNumberRow(keyA, valueA, dpA, null, null, setterA, keyB, valueB, dpB, null, null, setterB);
	}

	private void constrainedNumberRow(String keyA, float valueA, int dpA, Float minA, Float maxA, Consumer<Float> setterA, String keyB, float valueB, int dpB, Float minB, Float maxB, Consumer<Float> setterB) {
		Panel row = row();
		addNumber(row, 0, fieldWidth(), keyA, valueA, dpA, minA, maxA, setterA);
		if(keyB != null)addNumber(row, fieldWidth(), fieldWidth(), keyB, valueB, dpB, minB, maxB, setterB);
	}

	private Spinner addNumber(Panel row, int x, int width, String key, float value, int dp, Consumer<Float> setter) {
		return addNumber(row, x, width, key, value, dp, null, null, setter);
	}

	private Spinner addNumber(Panel row, int x, int width, String key, float value, int dp, Float min, Float max, Consumer<Float> setter) {
		Label label = new Label(gui, gui.i18nFormat(key));
		label.setBounds(new Box(x + 4, 5, 96, 12));
		row.addElement(label);
		Spinner spinner = new Spinner(gui);
		spinner.setDp(dp);
		spinner.setValue(clamp(value, min, max));
		spinner.setBounds(new Box(x + 104, 2, width - 110, 18));
		spinner.addChangeListener(() -> {
			float clamped = clamp(spinner.getValue(), min, max);
			setSpinnerValue(spinner, clamped);
			setter.accept(clamped);
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
		List<NamedElement<Enum<?>>> options = new ArrayList<>();
		for(Enum<?> enumValue : values)options.add(new NamedElement<>(enumValue, e -> enumName(values, e.ordinal())));
		DropDownBox<NamedElement<Enum<?>>> dropDown = new DropDownBox<>(frm, options);
		dropDown.setSelected(options.get(Math.max(0, Math.min(options.size() - 1, value))));
		dropDown.setBounds(new Box(x + 86, 2, width - 92, 18));
		dropDown.setAction(() -> {
			NamedElement<Enum<?>> selected = dropDown.getSelected();
			if(selected == null)return;
			setter.accept(selected.getElem().ordinal());
			editor.markDirty();
		});
		row.addElement(dropDown);
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
			boolean selected = !checkbox.isSelected();
			checkbox.setSelected(selected);
			setter.accept(selected);
			editor.markDirty();
		});
		row.addElement(checkbox);
	}

	private void vec3(String key, Vec3f vec) {
		vec3(key, vec, null, null, null);
	}

	private void vec3(String key, Vec3f vec, Consumer<Vec3f> setter) {
		vec3(key, vec, null, null, setter);
	}

	private void vec3(String key, Vec3f vec, Float min, Float max, Consumer<Vec3f> setter) {
		Panel row = row();
		Label label = new Label(gui, gui.i18nFormat(key));
		label.setBounds(new Box(4, 5, 116, 12));
		row.addElement(label);
		Spinner x = axis(row, 124, clamp(vec.x, min, max));
		Spinner y = axis(row, 204, clamp(vec.y, min, max));
		Spinner z = axis(row, 284, clamp(vec.z, min, max));
		Runnable update = () -> {
			vec.x = clamp(x.getValue(), min, max);
			vec.y = clamp(y.getValue(), min, max);
			vec.z = clamp(z.getValue(), min, max);
			setSpinnerValue(x, vec.x);
			setSpinnerValue(y, vec.y);
			setSpinnerValue(z, vec.z);
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
			setSpinnerValue(r, rv);
			setSpinnerValue(g, gv);
			setSpinnerValue(b, bv);
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

	private float clamp(float value, Float min, Float max) {
		if(min != null && value < min)return min;
		if(max != null && value > max)return max;
		return value;
	}

	private void setSpinnerValue(Spinner spinner, float value) {
		if(Math.abs(spinner.getValue() - value) > 0.0001f)spinner.setValue(value);
	}

	private void addReadout(Panel row, int x, int width, String key, String value) {
		Label label = new Label(gui, gui.i18nFormat(key, value));
		label.setBounds(new Box(x + 4, 5, width - 8, 12));
		row.addElement(label);
	}

	private boolean isGestureToggleRegistered(PslElement element) {
		for(AbstractGestureButtonData btn : editor.definition.getAnimations().getNamedActions()) {
			if(btn instanceof PslElementToggleButtonData && ((PslElementToggleButtonData) btn).pslElementId == element.getId()) {
				return true;
			}
		}
		return false;
	}

	private void setGestureToggle(PslElement element, boolean add) {
		if(add) {
			if(!isGestureToggleRegistered(element)) {
				PslElementToggleButtonData btn = new PslElementToggleButtonData();
				btn.setPslElement(element);
				editor.definition.getAnimations().register(btn);
			}
		} else {
			editor.definition.getAnimations().getNamedActions().removeIf(btn ->
				btn instanceof PslElementToggleButtonData && ((PslElementToggleButtonData) btn).pslElementId == element.getId());
		}
		editor.updateGui.accept(null);
	}
}
