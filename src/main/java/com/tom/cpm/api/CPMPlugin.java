package com.tom.cpm.api;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Annotate your CPMPlugin class to load your plugin.
 * Only works on Forge/NeoForge
 * On Fabric: You must register your plugin in your fabric.mod.json
 * Your class must implement {@link ICPMPlugin}
 * */
@Documented
@Retention(RUNTIME)
@Target(TYPE)
public @interface CPMPlugin {
}
