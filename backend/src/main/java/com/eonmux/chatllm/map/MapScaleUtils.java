package com.eonmux.chatllm.map;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * Shared utility methods for map coordinate and data encoding.
 * Centralizes worldSizeForScale and binary encoding to prevent drift
 * across MapService, MapResolver, TravelService, JourneyService,
 * TerrainGenerator, MapGenerator, LocationPlacer, and RoadGenerator.
 *
 * <p>World size values must match frontend {@code scaleToWorldSize()} in
 * {@code frontend/src/components/map/utils.ts}.
 */
public final class MapScaleUtils {

    private MapScaleUtils() {}

    /**
     * Maps a mapScale string to the world size in Three.js units.
     * Must stay in sync with the frontend's {@code scaleToWorldSize()}.
     */
    public static float worldSizeForScale(String scale) {
        if (scale == null) return 200f;
        return switch (scale) {
            case "room" -> 30f;
            case "building" -> 60f;
            case "city" -> 200f;
            case "continent" -> 1000f;
            case "planet" -> 4000f;
            default -> 200f; // region
        };
    }

    /** Encode a float[] to a Base64 string (little-endian). */
    public static String encodeFloatArray(float[] arr) {
        ByteBuffer buf = ByteBuffer.allocate(arr.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : arr) buf.putFloat(v);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    /** Encode an int[] to a Base64 string (little-endian). */
    public static String encodeIntArray(int[] arr) {
        ByteBuffer buf = ByteBuffer.allocate(arr.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : arr) buf.putInt(v);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    /** Decode a Base64 string to a float[] (little-endian). */
    public static float[] decodeFloatArray(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] arr = new float[bytes.length / 4];
        for (int i = 0; i < arr.length; i++) arr[i] = buf.getFloat();
        return arr;
    }

    /** Decode a Base64 string to an int[] (little-endian). */
    public static int[] decodeIntArray(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int[] arr = new int[bytes.length / 4];
        for (int i = 0; i < arr.length; i++) arr[i] = buf.getInt();
        return arr;
    }
}
