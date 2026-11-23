package com.eonmux.chatllm.config;

/**
 * Shared constants for roleplay event bus addresses.
 * Publishers and subscribers must use the same address prefix to ensure events are delivered.
 */
public final class RoleplayEventAddresses {

    private RoleplayEventAddresses() {}

    public static final String WORLD_STATE_CHANGED = "rp.worldstate.changed.";
    public static final String DICE_ROLLED = "rp.dice.rolled.";
    public static final String SCENE_CHANGED = "rp.scene.changed.";
    public static final String STAT_CHANGED = "rp.stat.changed.";
    public static final String RELATIONSHIP_CHANGED = "rp.relationship.changed.";
    public static final String INVENTORY_CHANGED = "rp.inventory.changed.";
    public static final String IMAGE_PROGRESS = "rp.image.progress.";
    public static final String IMAGE_GENERATED = "rp.image.generated.";
    public static final String ACHIEVEMENT_UNLOCKED = "rp.achievement.unlocked.";
    public static final String CASTING_PROPOSAL = "rp.casting.proposal.";
    public static final String MAP_ENTITY_MOVED = "rp.map.entity.moved.";
    public static final String MAP_ITEM_DISCOVERED = "rp.map.item.discovered.";
    public static final String MAP_LOCATION_DISCOVERED = "rp.map.location.discovered.";
    public static final String MAP_GENERATED = "rp.map.generated.";
    public static final String TRAVEL_JOURNEY_STEP = "rp.travel.journey.step.";

    // Battle events
    public static final String BATTLE_STARTED = "rp.battle.started.";
    public static final String BATTLE_ROUND = "rp.battle.round.";
    public static final String BATTLE_VOTE = "rp.battle.vote.";
    public static final String BATTLE_RESOLVED = "rp.battle.resolved.";
    public static final String BATTLE_ENDED = "rp.battle.ended.";
}
