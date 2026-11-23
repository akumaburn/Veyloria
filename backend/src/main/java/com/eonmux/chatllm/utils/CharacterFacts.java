package com.eonmux.chatllm.utils;

import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Static facts a character owns: the body it was generated with and the base
 * attributes that limit it. Agents format these facts through one place so no
 * prompt invents a height, hair color, or score that the character does not
 * have.
 */
public final class CharacterFacts {

    private CharacterFacts() {
    }

    /** Physical profile fields, in the order they are shown to an agent. */
    public static JsonObject profile(String gender, Integer ageYears, String height,
                                     String build, String hair, String eyes) {
        JsonObject profile = new JsonObject();
        putText(profile, "gender", gender);
        if (ageYears != null && ageYears >= 0) profile.put("ageYears", ageYears);
        putText(profile, "height", height);
        putText(profile, "build", build);
        putText(profile, "hair", hair);
        putText(profile, "eyes", eyes);
        return profile;
    }

    /** The six base attributes on their 1-20 scale. */
    public static JsonObject attributes(Integer strength, Integer dexterity, Integer constitution,
                                        Integer intelligence, Integer wisdom, Integer charisma) {
        JsonObject attributes = new JsonObject();
        putScore(attributes, "strength", strength);
        putScore(attributes, "dexterity", dexterity);
        putScore(attributes, "constitution", constitution);
        putScore(attributes, "intelligence", intelligence);
        putScore(attributes, "wisdom", wisdom);
        putScore(attributes, "charisma", charisma);
        return attributes;
    }

    /**
     * A compact line such as
     * {@code gender Woman; age 19; height 5 ft 8 in; build Lean; hair Dark brown; eyes Gray}.
     * Empty fields are omitted, so a partial profile stays honest.
     */
    public static String describeProfile(JsonObject profile) {
        if (profile == null || profile.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        String gender = text(profile, "gender");
        if (gender != null) parts.add("gender " + gender);
        Integer age = profile.getInteger("ageYears");
        if (age != null) parts.add("age " + age);
        String height = text(profile, "height");
        if (height != null) parts.add("height " + height);
        String build = text(profile, "build");
        if (build != null) parts.add("build " + build);
        String hair = text(profile, "hair");
        if (hair != null) parts.add("hair " + hair);
        String eyes = text(profile, "eyes");
        if (eyes != null) parts.add("eyes " + eyes);
        return String.join("; ", parts);
    }

    /**
     * A compact line such as
     * {@code STR 9; DEX 13; CON 11; INT 16; WIS 12; CHA 10}.
     * A character without scores produces nothing.
     */
    public static String describeAttributes(JsonObject attributes) {
        if (attributes == null || attributes.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        appendScore(parts, attributes, "STR", "strength");
        appendScore(parts, attributes, "DEX", "dexterity");
        appendScore(parts, attributes, "CON", "constitution");
        appendScore(parts, attributes, "INT", "intelligence");
        appendScore(parts, attributes, "WIS", "wisdom");
        appendScore(parts, attributes, "CHA", "charisma");
        return String.join("; ", parts);
    }

    private static void appendScore(List<String> parts, JsonObject attributes,
                                    String label, String field) {
        Integer score = attributes.getInteger(field);
        if (score != null) parts.add(label + " " + score);
    }

    private static void putText(JsonObject target, String field, String value) {
        if (value == null) return;
        String clean = value.strip();
        if (!clean.isEmpty()) target.put(field, clean);
    }

    private static void putScore(JsonObject target, String field, Integer value) {
        if (value != null) target.put(field, value);
    }

    private static String text(JsonObject source, String field) {
        String value = source.getString(field);
        if (value == null || value.isBlank()) return null;
        return value.strip();
    }
}
