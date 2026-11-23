package com.eonmux.chatllm.agents;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import com.eonmux.chatllm.map.StageContext;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContext {
    private String conversationId;
    private String progressRunId;
    private java.util.function.BiConsumer<String,String> progressReporter;
    private JsonObject arrivalResident;
    private String triggerMessageId;
    private String triggerContent;
    private boolean postDialogue;      // Resolves the completed character round, not a preview of it
    private String roundLastMessageId; // Fences paused/interrupted/replaced rounds
    private String stageMoveFailure;
    private boolean sceneResolutionComplete; // Focused travel/interaction checks have already run
    private String interactionResult; // Established interaction requiring a narrated result
    private String userId;             // The player's user ID (for inventory/ownership tracking)
    private String playerName;         // The player's display name, for narration
    private String characterId;       // For character-specific agents
    private JsonObject worldState;
    private JsonObject battleContext;
    private boolean encounterNarrationSaved;
    private JsonArray recentMessages;  // Raw recent messages
    private JsonArray highlighterOutput; // Tagged bullet summaries
    private String museOutput;         // Muse suggestions
    private String accountantOutput;   // Accountant analysis
    private String narratorOutput;     // Narrator text
    private String skepticFeedback;    // Feedback from Skeptic rejection (for retry)
    private JsonObject templateRules;  // Story template rules
    private JsonArray inventoryActions; // Committed actions; never apply these again
    private JsonObject characterSheet; // Character stats/inventory/relationships
    private List<String> characterIdsInScene; // Characters in current scene
    private String retrievedKnowledge;    // Formatted RAG context string for prompts
    private JsonArray retrievedEntries;   // Raw retrieved entries for structured access
    private String castingPrompt;         // Current casting trigger being processed
    private String casterOutput;          // Casting director's raw output for skeptic review
    private JsonObject characterNames;       // Map of characterId -> name for display in prompts
    private JsonObject characterPronouns;    // Map of characterId -> pronouns (stored field, identity fallback)
    private JsonObject characterDescriptions; // Map of characterId -> short role/relationship line
    private JsonObject characterAppearances; // Map of characterId -> short appearance line
    private JsonObject characterProfiles;    // Map of characterId -> physical facts (gender, age, height, build, hair, eyes)
    private JsonObject characterAttributes;  // Map of characterId -> base attribute scores
    private String resolvedModel;         // Per-agent resolved model (set by BaseAgent.run())
    private String roleplayDescription;   // Conversation description / scenario for setting the scene

    // Map data for spatial awareness
    private JsonArray mapLocations;        // All locations on the current map (name, type, x, y, z, discovered)
    private JsonArray entityMapPositions;   // Character/user positions on the map
    private JsonObject currentMapLocation;  // The location matching worldState.currentLocation
    private JsonArray nearbyLocations;      // Locations within a radius of the current position
    private String mapName;                // Name of the current world map
    private String mapScale;               // Scale of the map (room, city, region, continent, etc.)
    private StageContext stage;            // Authoritative current place and reachable areas
    // Set by the pipeline when the Accountant's move is applied, so the
    // narrator can show the passage that just happened. Null when the
    // party did not move this turn.
    private String stageMoveDescription;
}
