package com.eonmux.chatllm.agents;

import com.eonmux.chatllm.map.StageContext;
import com.eonmux.chatllm.models.LocalArea;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative recovery for explicit local travel commands, never NPC prose or map creation. */
final class PlayerMovementIntent {
    private PlayerMovementIntent() {}

    private static final Pattern COMMAND = Pattern.compile(
        "(?i)^(?:(?:okay|ok|alright|then|well)[,\\s]+)*"
        + "(?:(?:let['’]?s|let us|i|we)\\s+"
        + "(?:(?:go|head|walk|move|travel|return|proceed)\\s+(?:over\\s+)?(?:to|towards?|for|into)"
        + "|check(?:\\s+out)?|visit|enter)"
        + "|(?:take|lead)\\s+us\\s+to)\\s+(?:the\\s+)?(.+)$");
    private static final Pattern UNCOMMITTED = Pattern.compile(
        "(?i)\\b(if|unless|once|after|before|until|when|later|tomorrow|eventually|maybe|perhaps|"
        + "might|could|would|should|not|never|instead|cancel|wait|stay|stop|either|or|remotely|don't|don’t)\\b|\\?");

    static String localDestination(AgentContext context) {
        if (!context.isPostDialogue() || AgentPipeline.isSessionStart(context)) return null;
        StageContext stage = context.getStage();
        String text = context.getTriggerContent();
        if (stage == null || !stage.isPresent() || text == null || UNCOMMITTED.matcher(text).find()
                || Pattern.compile("(?i)\\b(on the map|from here|in my memory)\\b").matcher(text).find()) return null;
        // Anchor to the player's own command. A quoted/reported suggestion is not authorization.
        var command = COMMAND.matcher(text.strip());
        if (!command.matches()) return null;
        String destination = command.group(1).toLowerCase(Locale.ROOT);
        List<LocalArea> matches = stage.getAreas().stream().filter(area -> {
            String name = area.getName();
            return name != null && !name.isBlank() && area.getLocalAreaId() != null
                && Pattern.compile("^" + Pattern.quote(name.toLowerCase(Locale.ROOT)) + "(?=$|[\\s.,!;])")
                    .matcher(destination).find();
        }).toList();
        if (matches.size() != 1) return null;
        LocalArea target = matches.getFirst();
        // Multiple destinations or a later change of plan need semantic adjudication by the agent.
        if (stage.getAreas().stream().anyMatch(area -> area.getName() != null && !area.getName().isBlank()
                && area.getLocalAreaId() != null
                && !area.getLocalAreaId().equals(target.getLocalAreaId())
                && !area.getLocalAreaId().equals(stage.getArea().getLocalAreaId())
                && Pattern.compile("(?i)(?<![\\p{L}\\p{N}])" + Pattern.quote(area.getName()) + "(?![\\p{L}\\p{N}])")
                    .matcher(text).find())) return null;
        return target.getName();
    }
}
