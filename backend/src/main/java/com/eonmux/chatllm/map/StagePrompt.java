package com.eonmux.chatllm.map;

import java.util.List;

/**
 * Formats a {@link StageContext} for one kind of prompt. All audiences share
 * the same place names; the movement rules differ by responsibility.
 */
public final class StagePrompt {

    public enum Audience {NARRATOR, CHARACTER, ACCOUNTANT, MUSE, CASTING, SKEPTIC, TRAVEL, STATE}

    private StagePrompt() {
    }

    public static String format(StageContext stage, Audience audience) {
        if (stage == null || !stage.isPresent()) return "";
        StringBuilder prompt = new StringBuilder("\n\n=== STAGE (authoritative for place) ===\n");
        prompt.append("World: ").append(nullSafe(stage.getMapName(), "the world"));
        if (stage.getMapScale() != null) prompt.append(" (").append(stage.getMapScale()).append(" scale)");
        prompt.append('\n');
        prompt.append("Place: ").append(stage.getNode().getName());
        if (stage.getNode().getLocationType() != null) {
            prompt.append(" [").append(stage.getNode().getLocationType()).append(']');
        }
        prompt.append('\n');
        if (notBlank(stage.getNode().getDescription())) {
            prompt.append(stage.getNode().getDescription().strip()).append('\n');
        }
        if (audience == Audience.ACCOUNTANT || audience == Audience.TRAVEL) prompt.append("Place id: ").append(stage.getNode().getLocationId()).append('\n');
        prompt.append("Room: ").append(stage.getArea().getName());
        if (stage.getArea().getAreaType() != null) {
            prompt.append(" [").append(stage.getArea().getAreaType()).append(']');
        }
        prompt.append('\n');
        if (notBlank(stage.getArea().getDescription())) {
            prompt.append(stage.getArea().getDescription().strip()).append('\n');
        }

        if (audience == Audience.ACCOUNTANT || audience == Audience.TRAVEL) prompt.append("Room id: ").append(stage.getArea().getLocalAreaId()).append('\n');
        List<StageExit> exits = stage.getExits();
        if (exits == null || exits.isEmpty()) {
            prompt.append("Adjacent areas: none. The current room has no other exit.\n");
        } else {
            prompt.append("Adjacent areas reachable on foot:\n");
            for (StageExit exit : exits) {
                prompt.append("  - ").append(exit.name());
                if (notBlank(exit.direction())) prompt.append(" (").append(exit.direction()).append(")");
                if (notBlank(exit.label())) prompt.append(" — through ").append(exit.label());
                if (notBlank(exit.description())) prompt.append(". ").append(exit.description().strip());
                if (audience == Audience.ACCOUNTANT || audience == Audience.TRAVEL) prompt.append(" [areaId: ").append(exit.areaId()).append(']');
                prompt.append('\n');
            }
            // Anchor against invented directions: the model must not name a
            // passage, door, or path in any direction that is not listed here.
            prompt.append("These are the only exits. There is no passage in any other direction.\n");
        }

        // What the party has already seen, so narration can reference it and
        // so unvisited areas are described as unknown, not catalogued.
        if (stage.getVisitedAreaIds() != null && !stage.getVisitedAreaIds().isEmpty()
                && stage.getAreas() != null) {
            List<String> seen = new java.util.ArrayList<>();
            for (var area : stage.getAreas()) {
                if (stage.getVisitedAreaIds().contains(area.getLocalAreaId().toString())
                        && !area.getLocalAreaId().toString().equals(
                                stage.getArea().getLocalAreaId().toString())) {
                    seen.add(area.getName());
                }
            }
            if (!seen.isEmpty()) {
                prompt.append("Areas the party has already seen: ")
                        .append(String.join(", ", seen)).append(".\n");
            }
            prompt.append("Every other area in this place is unexplored; the party has not seen it.\n");
        }

        if (stage.isAtGateway()) {
            if (stage.getDestinations() == null || stage.getDestinations().isEmpty()) {
                prompt.append("This is a gateway, but no destination is known from here.\n");
            } else {
                prompt.append("From this gateway, the party may leave for:\n");
                for (StageDestination destination : stage.getDestinations()) {
                    prompt.append("  - ").append(destination.name());
                    if (destination.locationType() != null) {
                        prompt.append(" [").append(destination.locationType()).append(']');
                    }
                    prompt.append(" — ").append(destination.distanceLabel());
                    if (destination.viaRoad()) prompt.append(" by road");
                    prompt.append('\n');
                }
            }
        } else if (stage.getRouteToGateway() != null && !stage.getRouteToGateway().isEmpty()) {
            prompt.append("To leave this place, reach a gateway: ")
                    .append(String.join(" -> ", stage.getRouteToGateway()))
                    .append(".\n");
        }

        prompt.append("Movement rules:\n");
        prompt.append("- The party is in the Room named above. No other room, building, street, or region is part of this scene.\n");
        prompt.append("- Move inside this place only through an adjacent area listed above. A longer walk must follow those connections.\n");
        prompt.append("- Leave this place only after reaching a gateway area, and only to a listed destination.\n");
        prompt.append("- Never invent a room, building, street, or destination. Never move a character without showing the passage.\n");
        prompt.append("- Compass directions follow the labels above: a direction with no listed passage has no passage.\n");
        switch (audience) {
            case NARRATOR -> prompt.append(
                    "- Describe only the Room above and the listed adjacent areas. Show each change of area through its connection.\n");
            case CHARACTER -> prompt.append(
                    "- Your body is in the Room above. Speak and act from there. You may describe moving through one listed connection, but you cannot arrive anywhere unlisted.\n");
            case ACCOUNTANT -> prompt.append(
                    "- Record an area move in \"currentArea\" only when the party actually moves through a listed connection. Record a place move in \"currentLocation\" only when the party leaves through a gateway for a listed destination. Omit both when nothing moves.\n");
            case TRAVEL -> prompt.append(
                    "- Decide only whether the player committed to travel now. A clear travel instruction resolves after the replies; do not require another threshold-crossing message. Use an existing destination and its connected route.\n");
            case STATE -> prompt.append(
                    "- This is the already resolved scene. Do not make another travel decision or change its place.\n");
            case MUSE -> prompt.append(
                    "- Keep suggested complications inside this place and its listed connections.\n");
            case CASTING -> prompt.append(
                    "- A new character must enter from a listed adjacent area or a listed gateway destination.\n");
            case SKEPTIC -> prompt.append(
                    "- Reject narration that places anyone outside this stage without showing the passage.\n");
        }
        if (audience == Audience.ACCOUNTANT) {
            prompt.append("Use an exact area name or areaId above for currentArea. Connection labels name the route, not a new area: Tavern via tavern lane means currentArea=Tavern, never Tavern Lane.\n");
            prompt.append("A clear player decision such as 'Let us go to the tavern' authorizes this round's move once responses are complete. Resolve it along the recorded connections unless blocked, conditional, cancelled, or merely hypothetical.\n");
        }
        prompt.append("=== END STAGE ===\n");
        return prompt.toString();
    }

    /** A compact one-line location label for logs and secondary prompts. */
    public static String label(StageContext stage) {
        if (stage == null || !stage.isPresent()) return "unknown";
        return stage.getNode().getName() + " / " + stage.getArea().getName();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
