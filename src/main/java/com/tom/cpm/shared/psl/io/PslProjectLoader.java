package com.tom.cpm.shared.psl.io;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.project.IProject;
import com.tom.cpm.shared.editor.project.JsonList;
import com.tom.cpm.shared.editor.project.JsonMap;
import com.tom.cpm.shared.editor.project.JsonMapImpl;
import com.tom.cpm.shared.editor.project.ProjectPartLoader;
import com.tom.cpm.shared.editor.project.ProjectWriter;
import com.tom.cpm.shared.psl.PslElement;
import com.tom.cpm.shared.psl.PslElementType;
import com.tom.cpm.shared.psl.PslSystem;
import com.tom.cpm.shared.psl.PslTrigger;
import com.tom.cpm.shared.psl.light.LightEmitter;
import com.tom.cpm.shared.psl.particle.ParticleEmitter;
import com.tom.cpm.shared.psl.physics.PhysicsBone;
import com.tom.cpm.shared.psl.sound.MidiEmitter;
import com.tom.cpm.shared.psl.sound.SoundEmitter;
import com.tom.cpm.shared.util.Log;

/**
 * Project part loader for PSL data.
 * Loads/saves psl_config.json in .cpmproject v2 format.
 */
public class PslProjectLoader implements ProjectPartLoader {

	@Override
	public String getId() {
		return "psl";
	}

	@Override
	public int getVersion() {
		return 2; // v2 projects only
	}

	@Override
	public void load(Editor editor, IProject project) throws IOException {
		project.jsonIfExists("psl_config.json", data -> loadPsl(editor, data));
	}

	@Override
	public void save(Editor editor, ProjectWriter project) throws IOException {
		if (editor.pslSystem != null && !editor.pslSystem.isEmpty()) {
			JsonMap data = project.getJson("psl_config.json");
			data.put("version", 1);
			savePsl(editor, data);
		}
	}

	private void loadPsl(Editor editor, JsonMap data) {
		PslSystem system = editor.pslSystem;
		if (system == null) {
			system = new PslSystem();
			editor.pslSystem = system;
		}
		system.clear();

		loadTypeList(system, data.getList("particles"), PslElementType.PARTICLE);
		loadTypeList(system, data.getList("physics"), PslElementType.PHYSICS);
		loadTypeList(system, data.getList("sounds"), PslElementType.SOUND);
		loadTypeList(system, data.getList("lights"), PslElementType.LIGHT);
		loadTypeList(system, data.getList("midi"), PslElementType.MIDI);

		system.markClean();
	}

	private void loadTypeList(PslSystem system, JsonList list, PslElementType type) {
		list.forEachMap(map -> {
			try {
				PslElement elem = createElement(type);
				if (elem == null) return;

				elem.setId(map.getLong("id"));
				elem.setName(map.getString("name", null));
				elem.setElementId(map.getInt("elementId", -1));

				JsonMap triggerMap = map.getMap("trigger");
				if (triggerMap != null) {
					elem.setTrigger(loadTrigger(triggerMap));
				}

				loadElementData(elem, map);
				system.addElement(elem);
			} catch (Exception e) {
				Log.warn("Failed to load PSL element of type " + type, e);
			}
		});
	}

	private PslElement createElement(PslElementType type) {
		switch (type) {
			case PARTICLE: return new ParticleEmitter();
			case PHYSICS: return new PhysicsBone();
			case SOUND: return new SoundEmitter();
			case LIGHT: return new LightEmitter();
			case MIDI: return new MidiEmitter();
			default: return null;
		}
	}

	private PslTrigger loadTrigger(JsonMap map) {
		String typeStr = map.getString("type", "ALWAYS");
		PslTrigger.TriggerType type;
		try {
			type = PslTrigger.TriggerType.valueOf(typeStr.toUpperCase());
		} catch (IllegalArgumentException e) {
			type = PslTrigger.TriggerType.ALWAYS;
		}
		PslTrigger trigger = new PslTrigger(type);
		trigger.setAnimName(map.getString("animName", null));
		trigger.setGestureName(map.getString("gestureName", null));
		trigger.setVanillaPoseName(map.getString("vanillaPoseName", null));
		trigger.setParamName(map.getString("paramName", null));
		trigger.setParamMin(map.getFloat("paramMin", 0));
		trigger.setParamMax(map.getFloat("paramMax", 0));
		trigger.setEventName(map.getString("eventName", null));
		return trigger;
	}

	private void loadElementData(PslElement elem, JsonMap map) {
		if (elem instanceof ParticleEmitter) {
			ParticleEmitter p = (ParticleEmitter) elem;
			p.setTextureName(map.getString("texture", null));
			p.setSpriteWidth(map.getFloat("spriteWidth", 1));
			p.setSpriteHeight(map.getFloat("spriteHeight", 1));
			p.setSpriteU(map.getInt("spriteU", 0));
			p.setSpriteV(map.getInt("spriteV", 0));
			p.setSpriteTexW(map.getInt("spriteTexW", 16));
			p.setSpriteTexH(map.getInt("spriteTexH", 16));
			p.setEmitterType(parseEnum(ParticleEmitter.EmitterType.VALUES, map.getString("emitterType", "POINT")));
			p.setEmitterSize(parseVec3f(map.getMap("emitterSize")));
			p.setRate(map.getFloat("rate", 10));
			p.setMaxParticles(map.getInt("maxParticles", 50));
			p.setLifeMin(map.getFloat("lifeMin", 0.5f));
			p.setLifeMax(map.getFloat("lifeMax", 1.5f));
			p.setVelocity(parseVec3f(map.getMap("velocity")));
			p.setVelocityVariation(map.getFloat("velocityVariation", 0.5f));
			p.setGravity(map.getFloat("gravity", 0));
			p.setScaleStart(map.getFloat("scaleStart", 1));
			p.setScaleEnd(map.getFloat("scaleEnd", 0));
			p.setColorStart(parseColor(map.getString("colorStart", "#FFFFFFFF")));
			p.setColorEnd(parseColor(map.getString("colorEnd", "#00FFFFFF")));
			p.setAlphaStart(map.getFloat("alphaStart", 1));
			p.setAlphaEnd(map.getFloat("alphaEnd", 0));
			p.setRotationStart(map.getFloat("rotationStart", 0));
			p.setRotationEnd(map.getFloat("rotationEnd", 360));
			p.setCollision(map.getBoolean("collision", false));
			p.setBillboard(parseEnum(ParticleEmitter.BillboardMode.VALUES, map.getString("billboard", "CENTER")));
			p.setBlendMode(parseEnum(ParticleEmitter.BlendMode.VALUES, map.getString("blendMode", "ALPHA")));
			p.setRespectGraphicsSetting(map.getBoolean("respectGraphicsSetting", true));
		} else if (elem instanceof PhysicsBone) {
			PhysicsBone b = (PhysicsBone) elem;
			b.setParentElementId(map.getInt("parentElementId", -1));
			b.setSimType(parseEnum(PhysicsBone.SimType.VALUES, map.getString("simType", "CHAIN")));
			b.setGravity(map.getFloat("gravity", 1));
			b.setDamping(map.getFloat("damping", 0.3f));
			b.setStiffness(map.getFloat("stiffness", 0.2f));
			b.setMass(map.getFloat("mass", 0.5f));
			b.setWindInfluence(map.getFloat("windInfluence", 0.6f));
			b.setCollisionRadius(map.getFloat("collisionRadius", 0));
			b.setMaxStretch(map.getFloat("maxStretch", 1.05f));
			b.setLimitAngleX(map.getFloat("limitAngleX", 0));
			b.setLimitAngleY(map.getFloat("limitAngleY", 0));
			b.setLimitAngleZ(map.getFloat("limitAngleZ", 0));
			b.setIterations(map.getInt("iterations", 3));
			b.setInheritAnimation(map.getBoolean("inheritAnimation", false));
		} else if (elem instanceof SoundEmitter) {
			SoundEmitter s = (SoundEmitter) elem;
			s.setSoundFile(map.getString("soundFile", null));
			s.setVolume(map.getFloat("volume", 1));
			s.setPitch(map.getFloat("pitch", 1));
			s.setPitchVariation(map.getFloat("pitchVariation", 0));
			s.setLoop(map.getBoolean("loop", false));
			s.setLoopDelay(map.getFloat("loopDelay", 0));
			s.setAttenuation(parseEnum(SoundEmitter.Attenuation.VALUES, map.getString("attenuation", "LINEAR")));
			s.setMaxDistance(map.getFloat("maxDistance", 16));
			s.setCategory(parseEnum(SoundEmitter.SoundCategory.VALUES, map.getString("category", "PLAYER")));
			s.setCooldown(map.getFloat("cooldown", 0));
			s.setOneShot(map.getBoolean("oneShot", true));
		} else if (elem instanceof LightEmitter) {
			LightEmitter l = (LightEmitter) elem;
			l.setColor(parseColor(map.getString("color", "#FFFFFF")));
			l.setIntensity(map.getFloat("intensity", 0.7f));
			l.setRadius(map.getFloat("radius", 3));
			l.setFlicker(map.getBoolean("flicker", false));
			l.setFlickerSpeed(map.getFloat("flickerSpeed", 1));
			l.setFlickerAmount(map.getFloat("flickerAmount", 0.1f));
			l.setDynamic(map.getBoolean("dynamic", true));
			l.setCastShadows(map.getBoolean("castShadows", true));
		} else if (elem instanceof MidiEmitter) {
			MidiEmitter m = (MidiEmitter) elem;
			m.setMidiFile(map.getString("midiFile", null));
			JsonMap instMap = map.getMap("instrumentMap");
			if (instMap != null) {
				instMap.forEach((key, val) -> {
					try {
						int program = Integer.parseInt(key);
						m.getInstrumentMap().put(program, String.valueOf(val));
					} catch (NumberFormatException ignored) {}
				});
			}
			m.setTempo(map.getFloat("tempo", 1));
			m.setVolume(map.getFloat("volume", 0.6f));
			m.setTranspose(map.getInt("transpose", 0));
			m.setLoop(map.getBoolean("loop", false));
			m.setLoopDelay(map.getFloat("loopDelay", 0));
			m.setCategory(parseEnum(SoundEmitter.SoundCategory.VALUES, map.getString("category", "AMBIENT")));
			m.setPolyphony(map.getInt("polyphony", 8));
			m.setNoteFalloff(map.getFloat("noteFalloff", 0.3f));
		}
	}

	private void savePsl(Editor editor, JsonMap data) {
		PslSystem system = editor.pslSystem;
		if (system == null) return;

		JsonList particles = data.putList("particles");
		JsonList physics = data.putList("physics");
		JsonList sounds = data.putList("sounds");
		JsonList lights = data.putList("lights");
		JsonList midi = data.putList("midi");

		for (PslElement elem : system.getElements()) {
			Map<String, Object> rawMap = new HashMap<>();
			JsonMap map = new JsonMapImpl(rawMap);
			map.put("id", elem.getId());
			if (elem.getName() != null) map.put("name", elem.getName());
			map.put("elementId", elem.getElementId());
			map.put("trigger", saveTrigger(elem.getTrigger()));

			saveElementData(elem, map);

			switch (elem.getType()) {
				case PARTICLE: particles.add(rawMap); break;
				case PHYSICS: physics.add(rawMap); break;
				case SOUND: sounds.add(rawMap); break;
				case LIGHT: lights.add(rawMap); break;
				case MIDI: midi.add(rawMap); break;
			}
		}
	}

	private JsonMap saveTrigger(PslTrigger trigger) {
		JsonMap map = new com.tom.cpm.shared.editor.project.JsonMapImpl(new java.util.HashMap<>());
		map.put("type", trigger.getType().name());
		if (trigger.getAnimName() != null) map.put("animName", trigger.getAnimName());
		if (trigger.getGestureName() != null) map.put("gestureName", trigger.getGestureName());
		if (trigger.getVanillaPoseName() != null) map.put("vanillaPoseName", trigger.getVanillaPoseName());
		if (trigger.getParamName() != null) map.put("paramName", trigger.getParamName());
		if (trigger.getType() == PslTrigger.TriggerType.VALUE_RANGE) {
			map.put("paramMin", trigger.getParamMin());
			map.put("paramMax", trigger.getParamMax());
		}
		if (trigger.getEventName() != null) map.put("eventName", trigger.getEventName());
		return map;
	}

	private void saveElementData(PslElement elem, JsonMap map) {
		if (elem instanceof ParticleEmitter) {
			ParticleEmitter p = (ParticleEmitter) elem;
			if (p.getTextureName() != null) map.put("texture", p.getTextureName());
			map.put("spriteWidth", p.getSpriteWidth());
			map.put("spriteHeight", p.getSpriteHeight());
			map.put("spriteU", p.getSpriteU());
			map.put("spriteV", p.getSpriteV());
			map.put("spriteTexW", p.getSpriteTexW());
			map.put("spriteTexH", p.getSpriteTexH());
			map.put("emitterType", p.getEmitterType().name());
			map.put("emitterSize", vec3fToMap(p.getEmitterSize()));
			map.put("rate", p.getRate());
			map.put("maxParticles", p.getMaxParticles());
			map.put("lifeMin", p.getLifeMin());
			map.put("lifeMax", p.getLifeMax());
			map.put("velocity", vec3fToMap(p.getVelocity()));
			map.put("velocityVariation", p.getVelocityVariation());
			map.put("gravity", p.getGravity());
			map.put("scaleStart", p.getScaleStart());
			map.put("scaleEnd", p.getScaleEnd());
			map.put("colorStart", colorToString(p.getColorStart()));
			map.put("colorEnd", colorToString(p.getColorEnd()));
			map.put("alphaStart", p.getAlphaStart());
			map.put("alphaEnd", p.getAlphaEnd());
			map.put("rotationStart", p.getRotationStart());
			map.put("rotationEnd", p.getRotationEnd());
			map.put("collision", p.isCollision());
			map.put("billboard", p.getBillboard().name());
			map.put("blendMode", p.getBlendMode().name());
			map.put("respectGraphicsSetting", p.isRespectGraphicsSetting());
		} else if (elem instanceof PhysicsBone) {
			PhysicsBone b = (PhysicsBone) elem;
			map.put("parentElementId", b.getParentElementId());
			map.put("simType", b.getSimType().name());
			map.put("gravity", b.getGravity());
			map.put("damping", b.getDamping());
			map.put("stiffness", b.getStiffness());
			map.put("mass", b.getMass());
			map.put("windInfluence", b.getWindInfluence());
			map.put("collisionRadius", b.getCollisionRadius());
			map.put("maxStretch", b.getMaxStretch());
			map.put("limitAngleX", b.getLimitAngleX());
			map.put("limitAngleY", b.getLimitAngleY());
			map.put("limitAngleZ", b.getLimitAngleZ());
			map.put("iterations", b.getIterations());
			map.put("inheritAnimation", b.isInheritAnimation());
		} else if (elem instanceof SoundEmitter) {
			SoundEmitter s = (SoundEmitter) elem;
			if (s.getSoundFile() != null) map.put("soundFile", s.getSoundFile());
			map.put("volume", s.getVolume());
			map.put("pitch", s.getPitch());
			map.put("pitchVariation", s.getPitchVariation());
			map.put("loop", s.isLoop());
			map.put("loopDelay", s.getLoopDelay());
			map.put("attenuation", s.getAttenuation().name());
			map.put("maxDistance", s.getMaxDistance());
			map.put("category", s.getCategory().name());
			map.put("cooldown", s.getCooldown());
			map.put("oneShot", s.isOneShot());
		} else if (elem instanceof LightEmitter) {
			LightEmitter l = (LightEmitter) elem;
			map.put("color", colorToString(l.getColor()));
			map.put("intensity", l.getIntensity());
			map.put("radius", l.getRadius());
			map.put("flicker", l.isFlicker());
			map.put("flickerSpeed", l.getFlickerSpeed());
			map.put("flickerAmount", l.getFlickerAmount());
			map.put("dynamic", l.isDynamic());
			map.put("castShadows", l.isCastShadows());
		} else if (elem instanceof MidiEmitter) {
			MidiEmitter m = (MidiEmitter) elem;
			if (m.getMidiFile() != null) map.put("midiFile", m.getMidiFile());
			JsonMap instMap = map.putMap("instrumentMap");
			for (java.util.Map.Entry<Integer, String> e : m.getInstrumentMap().entrySet()) {
				instMap.put(e.getKey().toString(), e.getValue());
			}
			map.put("tempo", m.getTempo());
			map.put("volume", m.getVolume());
			map.put("transpose", m.getTranspose());
			map.put("loop", m.isLoop());
			map.put("loopDelay", m.getLoopDelay());
			map.put("category", m.getCategory().name());
			map.put("polyphony", m.getPolyphony());
			map.put("noteFalloff", m.getNoteFalloff());
		}
	}

	// --- Helper methods ---

	private static <E extends Enum<E>> E parseEnum(E[] values, String name) {
		for (E v : values) {
			if (v.name().equalsIgnoreCase(name)) return v;
		}
		return values[0];
	}

	private static com.tom.cpl.math.Vec3f parseVec3f(JsonMap map) {
		if (map == null) return new com.tom.cpl.math.Vec3f(0, 0, 0);
		return new com.tom.cpl.math.Vec3f(
			map.getFloat("x", 0),
			map.getFloat("y", 0),
			map.getFloat("z", 0)
		);
	}

	private static JsonMap vec3fToMap(com.tom.cpl.math.Vec3f v) {
		JsonMap map = new com.tom.cpm.shared.editor.project.JsonMapImpl(new java.util.HashMap<>());
		map.put("x", v.x);
		map.put("y", v.y);
		map.put("z", v.z);
		return map;
	}

	private static int parseColor(String hex) {
		if (hex == null) return 0xFFFFFFFF;
		if (hex.startsWith("#")) hex = hex.substring(1);
		try {
			return (int) Long.parseLong(hex, 16);
		} catch (NumberFormatException e) {
			return 0xFFFFFFFF;
		}
	}

	private static String colorToString(int color) {
		return "#" + String.format("%08X", color);
	}
}
