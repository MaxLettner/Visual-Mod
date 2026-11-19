package com.maxlettner.visualmod.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import net.minecraft.core.Direction;

import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;

/**
 * Reads Sodium quad data across Sodium versions: 0.9.2 names its getters {@code getX}, {@code getLight}, ...,
 * while 0.9.3 renamed them to {@code sodium$getX}, {@code sodium$getLight}, ... The right names are looked up once;
 * calls go through {@code static final} method handles, which the JIT turns into direct calls.
 */
public final class SodiumQuads {
	private static final MethodHandle X, Y, Z, LIGHT, LIGHT_FACE;

	static {
		try {
			Class<?> quad = MutableQuadViewImpl.class;
			String prefix = hasMethod(quad, "sodium$getX") ? "sodium$get" : "get";
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			MethodType coord = MethodType.methodType(float.class, int.class);
			X = lookup.findVirtual(quad, prefix + "X", coord);
			Y = lookup.findVirtual(quad, prefix + "Y", coord);
			Z = lookup.findVirtual(quad, prefix + "Z", coord);
			LIGHT = lookup.findVirtual(quad, prefix + "Light", MethodType.methodType(int.class, int.class));
			LIGHT_FACE = lookup.findVirtual(quad, prefix + "LightFace", MethodType.methodType(Direction.class));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Unsupported Sodium version: quad getters not found", e);
		}
	}

	private SodiumQuads() {
	}

	private static boolean hasMethod(Class<?> type, String name) {
		for (var method : type.getMethods()) {
			if (method.getName().equals(name)) {
				return true;
			}
		}

		return false;
	}

	public static float x(MutableQuadViewImpl quad, int i) {
		try {
			return (float) X.invokeExact(quad, i);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	public static float y(MutableQuadViewImpl quad, int i) {
		try {
			return (float) Y.invokeExact(quad, i);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	public static float z(MutableQuadViewImpl quad, int i) {
		try {
			return (float) Z.invokeExact(quad, i);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	public static int light(MutableQuadViewImpl quad, int i) {
		try {
			return (int) LIGHT.invokeExact(quad, i);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	public static Direction lightFace(MutableQuadViewImpl quad) {
		try {
			return (Direction) LIGHT_FACE.invokeExact(quad);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}
}
