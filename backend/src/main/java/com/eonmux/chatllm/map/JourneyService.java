package com.eonmux.chatllm.map;

import com.eonmux.chatllm.utils.ServiceHelper;
import com.eonmux.chatllm.agents.AgentPromptTemplates;
import com.eonmux.chatllm.config.RoleplayEventAddresses;
import com.eonmux.chatllm.map.models.*;
import com.eonmux.chatllm.models.Character;
import com.eonmux.chatllm.models.MapCharacterPosition;
import com.eonmux.chatllm.models.MapLocation;
import com.eonmux.chatllm.models.WorldState;
import com.eonmux.chatllm.services.GroupChatService;
import com.eonmux.chatllm.services.SceneService;
import com.eonmux.chatllm.services.WorldStateService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Orchestrates narrated step-by-step travel between map locations.
 * Ordinary steps generate prose; rolled encounters use a separate hostility judgment before effects.
 */
@Slf4j
@ApplicationScoped
public class JourneyService {

    private static final int MAX_STEPS = 8;
    private static final long STEP_DELAY_MS = 2000;
    private static final float DISCOVERY_RADIUS = 12f;

    @Inject
    Vertx vertx;

    @Inject
    Pool mysqlPool;

    @Inject
    MapService mapService;

    @Inject
    TravelService travelService;

    @Inject
    WorldStateService worldStateService;

    @Inject
    TravelSceneService travelSceneService;

    @Inject
    GroupChatService groupChatService;

    @Inject
    StageService stageService;

    @Inject com.eonmux.chatllm.config.AppConfig config;
    @Inject com.eonmux.chatllm.services.AgentModelConfigService modelConfigService;
    com.eonmux.chatllm.agents.SceneDecisionAgent encounterDecision;

    @jakarta.annotation.PostConstruct
    void initializeDecisions() {
        encounterDecision = new com.eonmux.chatllm.agents.SceneDecisionAgent(
            com.eonmux.chatllm.agents.SceneDecisionAgent.Question.ENCOUNTER, vertx, mysqlPool, config, modelConfigService);
    }

    @Inject com.eonmux.chatllm.services.BattleService battleService;
    @Inject com.eonmux.chatllm.services.StoryMessageService storyMessageService;
    @Inject com.eonmux.chatllm.services.LocationResidentService residents;

    /** Active journeys keyed by conversationId for fast lookup. */
    private final ConcurrentHashMap<String, JourneyState> activeJourneys = new ConcurrentHashMap<>();

    // ==================== Public API ====================

    /**
     * Start a narrated step-by-step journey from the user's current location to the destination.
     */
    public Uni<JourneyState> startJourney(String conversationId, String mapId, String userId,
                                              String toLocationId, List<String> characterIds) {
        if (activeJourneys.containsKey(conversationId)) {
            return Uni.createFrom().failure(new RuntimeException("Journey already in progress for this conversation"));
        }

        // Find user's current location
        return battleService.requirePeace(conversationId)
            .chain(() -> residents == null ? Uni.createFrom().item(characterIds == null ? List.<String>of() : characterIds)
                : residents.partyCharacters(conversationId,characterIds))
            .chain(travelingCharacters -> mapService.getEntityPositions(mapId).chain(positions -> {
            MapCharacterPosition userPos = positions.stream()
                .filter(p -> p.getUserId() != null && p.getUserId().toString().equals(userId))
                .findFirst()
                .orElse(null);

            if (userPos == null) {
                return Uni.createFrom().failure(new RuntimeException("User has no current position on this map"));
            }

            Uni<MapLocation> origin = userPos.getLocationId() == null
                ? mapService.getLocations(mapId).map(locations -> roadsideOrigin(userPos, locations))
                : mapService.getLocationById(userPos.getLocationId().toString());
            return origin.chain(from -> {
            String fromLocationId = from.getLocationId().toString();
            if (userPos.getLocationId() != null && fromLocationId.equals(toLocationId)) {
                return Uni.createFrom().failure(new RuntimeException("Already at destination"));
            }

            Uni<com.eonmux.chatllm.map.models.TravelResult> travel = userPos.getLocationId() == null
                ? travelService.calculateTravelFromPosition(mapId, from, toLocationId)
                : travelService.calculateTravel(mapId, fromLocationId, toLocationId);
            return travel.chain(travelResult -> {
                // Fetch from/to location data for waypoint generation
                Uni<MapLocation> fromUni = Uni.createFrom().item(from);
                Uni<MapLocation> toUni = mapService.getLocationById(toLocationId);
                Uni<TerrainData> terrainUni = mapService.getTerrainData(mapId);
                Uni<List<MapLocation>> allLocsUni = mapService.getLocations(mapId);

                return Uni.combine().all().unis(fromUni, toUni, terrainUni, allLocsUni)
                    .asTuple()
                    .chain(tuple -> {
                        MapLocation fromLoc = tuple.getItem1();
                        MapLocation toLoc = tuple.getItem2();
                        TerrainData terrain = tuple.getItem3();
                        List<MapLocation> allLocs = tuple.getItem4();

                        int totalSteps = Math.min(
                            Math.max(1, travelResult.getTimePeriodsRequired()),
                            MAX_STEPS
                        );

                        // Generate waypoints following road path (or linear fallback)
                        List<JourneyWaypoint> waypoints = generateWaypoints(
                            fromLoc, toLoc, terrain, allLocs, totalSteps,
                            travelResult.getRoadWaypoints()
                        );

                        // Roll events
                        waypoints = TravelEventRoller.rollEvents(
                            waypoints, travelResult.isUsesRoad(), System.nanoTime()
                        );

                        String journeyId = UUID.randomUUID().toString();
                        JourneyState state = JourneyState.builder()
                            .journeyId(journeyId)
                            .conversationId(conversationId)
                            .mapId(mapId)
                            .userId(userId)
                            .characterIds(List.copyOf(travelingCharacters))
                            .fromLocationId(fromLocationId)
                            .fromLocationName(fromLoc.getName())
                            .toLocationId(toLocationId)
                            .toLocationName(toLoc.getName())
                            .waypoints(waypoints)
                            .totalSteps(totalSteps)
                            .distance(travelResult.getDistance())
                            .effectiveCost(travelResult.getEffectiveCost())
                            .timePeriodsRequired(travelResult.getTimePeriodsRequired())
                            .usesRoad(travelResult.isUsesRoad())
                            .currentStep(0)
                            .status(JourneyStatus.TRAVELING)
                            .build();

                        if (activeJourneys.putIfAbsent(conversationId, state) != null) {
                            return Uni.createFrom().failure(new IllegalStateException("Journey already in progress for this conversation"));
                        }

                        return (residents == null ? Uni.createFrom().<Void>voidItem() : residents.leave(conversationId))
                            .chain(() -> Uni.createFrom().deferred(() -> persistJourneyState(state)))
                            .onFailure().invoke(failure -> activeJourneys.remove(conversationId, state))
                            .chain(v -> {
                                // Schedule the first step
                                scheduleNextStep(conversationId);
                                log.info("[journey] Started journey {} from '{}' to '{}' ({} steps)",
                                    journeyId, fromLoc.getName(), toLoc.getName(), totalSteps);
                                return Uni.createFrom().item(state);
                            });
                    });
            });
            });
        }));
    }

    /**
     * Cancel an active journey. Entities stay at their last waypoint.
     */
    static MapLocation roadsideOrigin(MapCharacterPosition position, List<MapLocation> locations) {
        MapLocation anchor = locations.stream().min(java.util.Comparator.comparingDouble(location -> {
            double dx = location.getX() - position.getX(), dy = location.getY() - position.getY();
            return dx * dx + dy * dy;
        })).orElseThrow(() -> new IllegalStateException("This map has no destinations"));
        return MapLocation.builder().locationId(anchor.getLocationId()).mapId(position.getMapId())
            .name("the roadside near " + anchor.getName()).locationType("wilderness")
            .x(position.getX()).y(position.getY()).z(position.getZ()).build();
    }

    public Uni<Boolean> cancelJourney(String conversationId, String caller) {
        return getActiveJourney(conversationId).chain(state -> {
            if (state == null) return Uni.createFrom().failure(new IllegalStateException("No active journey"));
            return battleService.canManage(conversationId, caller).chain(manager -> {
                if (!manager && !caller.equals(state.getUserId())) return Uni.createFrom().failure(new SecurityException("Only the traveler or host may abandon this route"));
                activeJourneys.putIfAbsent(conversationId, state);
                return cancelJourney(conversationId);
            });
        });
    }

    public Uni<Boolean> cancelJourney(String conversationId) {
        JourneyState state = activeJourneys.get(conversationId);
        if (state == null) {
            return Uni.createFrom().failure(new RuntimeException("No active journey for this conversation"));
        }

        // Cancel the timer
        if (state.getTimerId() >= 0) {
            vertx.cancelTimer(state.getTimerId());
        }

        JourneyState cancelled = state.toBuilder()
            .status(JourneyStatus.CANCELLED)
            .timerId(-1)
            .build();

        activeJourneys.remove(conversationId);

        return updateJourneyInDb(cancelled).chain(v -> {
            // Publish final step to let frontend know
            JourneyWaypoint lastWp = cancelled.getWaypoints().get(
                Math.max(0, Math.min(cancelled.getCurrentStep() - 1, cancelled.getWaypoints().size() - 1))
            );
            publishStepResult(conversationId, JourneyStepResult.builder()
                .journeyId(cancelled.getJourneyId())
                .stepNumber(Math.max(0, cancelled.getCurrentStep() - 1))
                .totalSteps(cancelled.getTotalSteps())
                .narration("The journey was cancelled.")
                .interruptReason("Cancelled")
                .eventType("none")
                .x(lastWp.getX())
                .y(lastWp.getY())
                .z(lastWp.getZ())
                .biome(lastWp.getBiome())
                .journeyComplete(false)
                .journeyInterrupted(true)
                .build());

            log.info("[journey] Cancelled journey {} at step {}/{}",
                cancelled.getJourneyId(), cancelled.getCurrentStep(), cancelled.getTotalSteps());
            return Uni.createFrom().item(true);
        });
    }

    /**
     * Get the active journey for a conversation, checking memory first then DB.
     */
    public Uni<JourneyState> getActiveJourney(String conversationId) {
        JourneyState inMemory = activeJourneys.get(conversationId);
        if (inMemory != null) {
            return Uni.createFrom().item(inMemory);
        }

        // Check DB for a TRAVELING journey
        return mysqlPool.preparedQuery(
                "SELECT * FROM journey_state WHERE conversationId = ? AND status IN ('TRAVELING','PAUSED') LIMIT 1")
            .execute(Tuple.of(conversationId))
            .map(rows -> {
                if (rows.size() == 0) return null;
                return rowToJourneyState(rows.iterator().next());
            });
    }

    /**
     * Resume active journeys on startup (crash recovery).
     */
    public Uni<Void> resumeActiveJourneys() {
        return mysqlPool.preparedQuery("SELECT * FROM journey_state WHERE status IN ('TRAVELING','PAUSED')")
            .execute(Tuple.tuple())
            .chain(rows -> {
                int count = 0;
                for (var row : rows) {
                    JourneyState state = rowToJourneyState(row);
                    if (state != null) {
                        activeJourneys.put(state.getConversationId(), state);
                        if (state.getStatus() == JourneyStatus.TRAVELING) scheduleNextStep(state.getConversationId());
                        count++;
                    }
                }
                if (count > 0) {
                    log.info("[journey] Resumed {} active journeys from DB", count);
                }
                return Uni.createFrom().voidItem();
            });
    }

    // ==================== Step Processing ====================

    private void scheduleNextStep(String conversationId) {
        activeJourneys.computeIfPresent(conversationId, (id, current) -> {
            long timerId = vertx.setTimer(STEP_DELAY_MS, ignored -> processStep(conversationId));
            return current.toBuilder().timerId(timerId).build();
        });
    }

    private void processStep(String conversationId) {
        JourneyState state = activeJourneys.get(conversationId);
        if (state == null || state.getStatus() != JourneyStatus.TRAVELING) {
            return;
        }

        int step = state.getCurrentStep();
        if (step >= state.getWaypoints().size()) {
            finalizeJourney(conversationId, JourneyStatus.COMPLETED, null);
            return;
        }

        JourneyWaypoint waypoint = state.getWaypoints().get(step);

        // A fight started from chat or another player pauses this route as well.
        battleService.getActiveBattle(conversationId).chain(active -> {
            if (active != null) return pauseForBattle(state, active.getBattleId().toString()).replaceWith((String) null);
            return narrateStep(state, waypoint);
        }).chain(narration -> {
            if (narration == null) return Uni.createFrom().voidItem();
            return battleService.getActiveBattle(conversationId).chain(active -> active == null
                ? processNarratedStep(state, waypoint, narration) : pauseForBattle(state, active.getBattleId().toString()));
        }).subscribe().with(v -> {}, error -> {
            log.error("[journey] Step failed", error);
            JourneyState current = activeJourneys.get(conversationId);
            if (current == null || !current.getJourneyId().equals(state.getJourneyId())) return;
            JourneyWaypoint wp = state.getWaypoints().get(Math.min(state.getCurrentStep(), state.getWaypoints().size() - 1));
            publishStepResult(conversationId, JourneyStepResult.builder().journeyId(state.getJourneyId())
                .stepNumber(state.getCurrentStep()).totalSteps(state.getTotalSteps()).eventType("none")
                .narration("The journey could not continue. Return to chat before setting out again.")
                .journeyInterrupted(true).interruptReason("Could not update travel state")
                .x(wp.getX()).y(wp.getY()).z(wp.getZ()).build());
            finalizeJourney(conversationId, JourneyStatus.INTERRUPTED, "Could not update travel state");
        });
    }

    private Uni<String> narrateStep(JourneyState state, JourneyWaypoint waypoint) {
        return buildNarratorPrompt(state, waypoint).chain(prompt -> {
            JsonArray messages = new JsonArray()
                .add(new JsonObject().put("role", "system").put("content", AgentPromptTemplates.NARRATOR_TRAVEL_SYSTEM))
                .add(new JsonObject().put("role", "user").put("content", prompt));

            JsonObject params = new JsonObject()
                .put("messages", messages)
                .put("temperature", 0.85)
                .put("max_tokens", 400)
                .put("stream", false);

            DeliveryOptions opts = new DeliveryOptions().setSendTimeout(60000);

            return vertx.eventBus().<JsonObject>request("llm.agent.completion", params, opts)
                .map(reply -> {
                    JsonObject body = reply.body();
                    return extractLlmContent(body);
                });
        }).onFailure().recoverWithItem(error -> {
            log.warn("[journey] Narration failed for journey {}, using a fallback", state.getJourneyId(), error);
            return waypoint.isDestination() ? "The party arrived at " + state.getToLocationName() + "."
                : "The party continued along the route.";
        });
    }

    private Uni<Void> processNarratedStep(JourneyState state, JourneyWaypoint waypoint, String narration) {
        String conversationId = state.getConversationId();
        int step = state.getCurrentStep();
        if (narration == null) return Uni.createFrom().voidItem();
        if (activeJourneys.get(conversationId) != state) return Uni.createFrom().voidItem();
        return judgeEncounter(state, waypoint, narration).chain(combat -> battleService.getActiveBattle(conversationId).chain(active -> {
            if (activeJourneys.get(conversationId) != state) return Uni.createFrom().voidItem();
            if (active != null) return pauseForBattle(state, active.getBattleId().toString());
            String effectiveNarration = narration;
            if (effectiveNarration == null || effectiveNarration.isBlank()) {
                effectiveNarration = "The journey continued through the " + waypoint.getBiome().toLowerCase() + " terrain.";
            }

            // Parse outcome tags
            boolean arrived = waypoint.isDestination();

            // Strip outcome tags from narration text (including hallucinated [INTERRUPT])
            String cleanNarration = effectiveNarration.replaceAll("(?s)<combat>.*?</combat>", "")
                .replaceAll("\\[CONTINUE\\]", "")
                .replaceAll("\\[ARRIVED\\]", "")
                .replaceAll("\\[INTERRUPT:\\s*.+?\\]", "")
                .strip();

            // Move entities to waypoint position
            List<Uni<Void>> moveUnis = new ArrayList<>();
            moveUnis.add(mapService.updateUserPosition(
                state.getMapId(), state.getUserId(), null,
                waypoint.getX(), waypoint.getY(), waypoint.getZ()));
            for (String charId : state.getCharacterIds()) {
                moveUnis.add(mapService.updateCharacterPosition(
                    state.getMapId(), charId, null,
                    waypoint.getX(), waypoint.getY(), waypoint.getZ()));
            }

            boolean finalArrived = arrived;
            String finalNarration = cleanNarration;

            return Uni.combine().all().unis(moveUnis).discardItems()
                .chain(v ->
                    // Progress time of day
                    advanceTimeForStep(state)
                )
                .chain(v ->
                    // Discover nearby locations
                    mapService.discoverNearbyLocations(
                        state.getMapId(), waypoint.getX(), waypoint.getY(), waypoint.getZ(), DISCOVERY_RADIUS)
                )
                .chain(discoveredLocs -> {
                    List<String> discoveredIds = discoveredLocs.stream()
                        .map(l -> l.getLocationId().toString())
                        .collect(Collectors.toList());

                    // Publish discovery events
                    for (MapLocation loc : discoveredLocs) {
                        JsonObject discoverEvent = new JsonObject()
                            .put("locationId", loc.getLocationId().toString())
                            .put("mapId", loc.getMapId().toString())
                            .put("name", loc.getName())
                            .put("description", loc.getDescription())
                            .put("x", loc.getX())
                            .put("y", loc.getY())
                            .put("z", loc.getZ())
                            .put("icon", loc.getIcon())
                            .put("locationType", loc.getLocationType())
                            .put("isDiscovered", true);
                        vertx.eventBus().publish(
                            RoleplayEventAddresses.MAP_LOCATION_DISCOVERED + conversationId, discoverEvent);
                    }

                    // Get current world state for time/day info
                    return worldStateService.getOrCreateWorldState(conversationId).chain(ws -> {
                        // Build and publish step result
                        JourneyStepResult stepResult = JourneyStepResult.builder()
                            .journeyId(state.getJourneyId())
                            .stepNumber(step)
                            .totalSteps(state.getTotalSteps())
                            .narration(finalNarration)
                            .eventType(waypoint.getEventType())
                            .x(waypoint.getX())
                            .y(waypoint.getY())
                            .z(waypoint.getZ())
                            .biome(waypoint.getBiome())
                            .timeOfDay(ws.getTimeOfDay())
                            .dayNumber(ws.getDayNumber() != null ? ws.getDayNumber() : 1)
                            .journeyComplete(finalArrived)
                            .journeyInterrupted(false)
                            .interruptReason(null)
                            .discoveredLocationIds(discoveredIds)
                            .build();

                        if (!"encounter".equals(waypoint.getEventType()) && !finalArrived) publishStepResult(conversationId, stepResult);

                        // Publish entity moved events
                        publishEntityMoved(state);

                        // Update nearby location in world state if applicable
                        // and publish WORLD_STATE_CHANGED so frontend syncs
                        Uni<Void> wsUpdateUni = Uni.createFrom().voidItem();
                        if (!waypoint.isDestination()) {
                            wsUpdateUni = worldStateService.updateWorldState(conversationId, new JsonObject()
                                .put("currentLocation", "On the route from " + state.getFromLocationName() + " to " + state.getToLocationName())
                                .put("currentLocationId", (String) null).put("currentArea", "encounter".equals(waypoint.getEventType()) ? "Route encounter" : "On the road")
                                .put("currentAreaId", (String) null))
                                .invoke(updated -> vertx.eventBus().publish(RoleplayEventAddresses.WORLD_STATE_CHANGED + conversationId, WorldStateService.toGraphQL(updated)))
                                .replaceWithVoid();
                        }

                        return wsUpdateUni.chain(v2 -> {
                            // Save narration as system message
                            return storyMessageService.narrate(conversationId, "journey:" + state.getJourneyId() + ":" + step, finalNarration, null);
                        }).chain(v2 -> {
                            if ("encounter".equals(waypoint.getEventType())) {
                                JourneyState current = activeJourneys.get(conversationId);
                                if (current == null || !current.getJourneyId().equals(state.getJourneyId())) return Uni.createFrom().voidItem();
                                JourneyState paused = current.toBuilder().status(JourneyStatus.PAUSED).currentStep(step + 1)
                                    .interruptReason("Encounter: choose how to proceed in chat.").timerId(-1).build();
                                if (!activeJourneys.replace(conversationId, current, paused)) return Uni.createFrom().voidItem();
                                return updateJourneyInDb(paused).chain(() -> {
                                    if (combat == null) return Uni.createFrom().<String>nullItem();
                                    return battleService.initiateBattle(conversationId, state.getUserId(), combat.getString("enemyName"),
                                        combat.getString("enemyDescription", ""), combat.getDouble("enemyHp", 20.0), finalNarration)
                                        .map(battle -> battle.getBattleId().toString())
                                        .onFailure().recoverWithItem(error -> {
                                            log.warn("[journey] Encounter paused but battle setup failed; host may retry in chat", error);
                                            return null;
                                        });
                                }).invoke(battleId -> {
                                    stepResult.setJourneyPaused(true); stepResult.setBattleId(battleId);
                                    stepResult.setInterruptReason("Encounter: choose how to proceed in chat.");
                                    publishStepResult(conversationId, stepResult);
                                }).replaceWithVoid();
                            }
                            if (finalArrived) {
                                activeJourneys.computeIfPresent(conversationId, (id, current) -> {
                                    if (!current.getJourneyId().equals(state.getJourneyId())) return current;
                                    List<JourneyStepResult> results = new ArrayList<>(current.getStepResults());
                                    results.add(stepResult); return current.toBuilder().stepResults(results).currentStep(step + 1).build();
                                });
                                return finishJourney(conversationId, JourneyStatus.COMPLETED, null)
                                    .invoke(() -> publishStepResult(conversationId, stepResult));
                            }
                            return advanceStep(state, step, false);
                        });
                    });
                });
        }));
    }

    private Uni<Void> advanceStep(JourneyState state, int step, boolean arrived) {
        String conversationId = state.getConversationId();
        JourneyState current = activeJourneys.get(conversationId);
        if (current == null || !current.getJourneyId().equals(state.getJourneyId()) || current.getCurrentStep() != step)
            return Uni.createFrom().voidItem();
        if (arrived) {
            finalizeJourney(conversationId, JourneyStatus.COMPLETED, null);
            return Uni.createFrom().voidItem();
        }
        JourneyState updated = current.toBuilder().currentStep(step + 1).build();
        if (!activeJourneys.replace(conversationId, current, updated)) return Uni.createFrom().voidItem();
        return updateJourneyInDb(updated).invoke(() -> scheduleNextStep(conversationId));
    }

    Uni<JsonObject> judgeEncounter(JourneyState state, JourneyWaypoint waypoint, String narration) {
        if (!"encounter".equals(waypoint.getEventType())) return Uni.createFrom().nullItem();
        String clean = narration.replaceAll("(?s)<combat>.*?</combat>", "")
            .replaceAll("\\[(?:CONTINUE|ARRIVED|INTERRUPT:[^\\]]*)\\]", "").strip();
        var context = com.eonmux.chatllm.agents.AgentContext.builder().conversationId(state.getConversationId())
            .userId(state.getUserId()).triggerContent("Established rolled journey event (judge hostility only):\n" + clean).build();
        return encounterDecision.run(context).map(result -> {
            if (!result.isSuccess() || result.getStructuredOutput() == null)
                throw new IllegalStateException("Journey encounter judgment unavailable");
            JsonObject decision = result.getStructuredOutput();
            return decision.getBoolean("encounter") ? decision : null;
        });
    }

    private Uni<Void> pauseForBattle(JourneyState state, String battleId) {
        JourneyState paused = state.toBuilder().status(JourneyStatus.PAUSED).timerId(-1)
            .interruptReason("An encounter is in progress. Return to chat.").build();
        if (!activeJourneys.replace(state.getConversationId(), state, paused)) return Uni.createFrom().voidItem();
        JourneyWaypoint wp = state.getWaypoints().get(Math.max(0, state.getCurrentStep() - 1));
        return updateJourneyInDb(paused).invoke(() -> publishStepResult(state.getConversationId(), JourneyStepResult.builder()
            .journeyId(state.getJourneyId()).stepNumber(state.getCurrentStep() - 1).totalSteps(state.getTotalSteps())
            .journeyPaused(true).battleId(battleId).eventType("encounter")
            .narration("Travel paused while the party handles an encounter.").interruptReason(paused.getInterruptReason())
            .x(wp.getX()).y(wp.getY()).z(wp.getZ()).build()));
    }

    public Uni<JourneyState> resumeJourney(String conversationId, String caller) {
        return battleService.requirePeace(conversationId).chain(() -> getActiveJourney(conversationId)).chain(state -> {
            if (state == null || state.getStatus() != JourneyStatus.PAUSED)
                return Uni.createFrom().failure(new IllegalStateException("No paused journey to continue"));
            return battleService.canManage(conversationId, caller).chain(manager -> {
                if (!manager && !caller.equals(state.getUserId())) return Uni.createFrom().failure(new SecurityException("Only the traveler or host may continue this journey"));
                List<JourneyStepResult> results = state.getStepResults().stream()
                    .map(result -> result.toBuilder().journeyPaused(false).interruptReason(null).build()).toList();
                JourneyState resumed = state.toBuilder().status(JourneyStatus.TRAVELING).interruptReason(null).stepResults(results).build();
                return mysqlPool.preparedQuery("UPDATE journey_state SET status = 'TRAVELING', interruptReason = NULL WHERE journeyId = ? AND status = 'PAUSED'")
                    .execute(Tuple.of(state.getJourneyId())).invoke(rows -> {
                        if (rows.rowCount() != 1) throw new IllegalStateException("This journey is already continuing");
                    }).chain(() -> updateJourneyInDb(resumed)).invoke(() -> {
                    activeJourneys.put(conversationId, resumed); scheduleNextStep(conversationId);
                    if (!results.isEmpty()) publishStepResult(conversationId, results.getLast());
                }).replaceWith(resumed);
            });
        });
    }

    // ==================== Finalization ====================

    private void finalizeJourney(String conversationId, JourneyStatus status, String reason) {
        finishJourney(conversationId, status, reason).subscribe().with(v -> {}, error -> log.error("[journey] Finalization failed", error));
    }

    private Uni<Void> finishJourney(String conversationId, JourneyStatus status, String reason) {
        JourneyState state = activeJourneys.get(conversationId);
        if (state == null) return Uni.createFrom().voidItem();
        if (state.getTimerId() >= 0) vertx.cancelTimer(state.getTimerId());
        JourneyState finished = state.toBuilder().status(status).interruptReason(reason).timerId(-1).build();
        Uni<Void> commit = Uni.createFrom().voidItem();
        if (status == JourneyStatus.COMPLETED) {
            commit = mapService.getLocationById(state.getToLocationId()).chain(destination -> {
                if (destination == null) return Uni.createFrom().failure(new IllegalStateException("Destination is missing"));
                List<Uni<Void>> moves = new ArrayList<>();
                moves.add(mapService.updateUserPosition(state.getMapId(), state.getUserId(), state.getToLocationId(), destination.getX(), destination.getY(), destination.getZ()));
                for (String character : state.getCharacterIds()) moves.add(mapService.updateCharacterPosition(state.getMapId(), character,
                    state.getToLocationId(), destination.getX(), destination.getY(), destination.getZ()));
                return Uni.combine().all().unis(moves).discardItems()
                    .chain(() -> mysqlPool.preparedQuery("UPDATE map_location SET isDiscovered = TRUE WHERE locationId = ?")
                        .execute(Tuple.of(state.getToLocationId())))
                    .chain(() -> stageService.placeAtNode(conversationId, state.getToLocationId()))
                    .chain(() -> travelSceneService.enter(conversationId, destination, state.getUserId(), state.getCharacterIds()))
                    .chain(() -> worldStateService.getOrCreateWorldState(conversationId))
                    .invoke(world -> vertx.eventBus().publish(RoleplayEventAddresses.WORLD_STATE_CHANGED + conversationId, WorldStateService.toGraphQL(world)))
                    .replaceWithVoid();
            });
        }
        return commit.chain(() -> updateJourneyInDb(finished))
            .call(() -> {
                if (status != JourneyStatus.COMPLETED) return Uni.createFrom().voidItem();
                String arrival="The party arrived at "+state.getToLocationName()+".";
                return storyMessageService.narrate(conversationId,"journey-arrival:"+state.getJourneyId(),arrival,null)
                    .chain(() -> {
                        Uni<Void> welcome=travelSceneService.welcome(conversationId,state.getUserId(),state.getJourneyId(),arrival);
                        return welcome == null ? Uni.createFrom().voidItem() : welcome;
                    });
            }).invoke(() -> {
            activeJourneys.remove(conversationId, state);
            publishEntityMoved(finished);
            if (status == JourneyStatus.COMPLETED) triggerCharacterResponseOnArrival(conversationId, state.getUserId());
        });
    }

    // ==================== Prompt Building ====================

    private Uni<String> buildNarratorPrompt(JourneyState state, JourneyWaypoint waypoint) {
        return mysqlPool.preparedQuery("SELECT description FROM conversation WHERE conversationGUID = ?")
            .execute(Tuple.of(state.getConversationId())).chain(scenarioRows -> {
                String scenario = scenarioRows.size() == 0 ? "" : scenarioRows.iterator().next().getString("description");
                return worldStateService.getOrCreateWorldState(state.getConversationId()).chain(ws -> {
            // Get character names
            if (state.getCharacterIds().isEmpty()) {
                return Uni.createFrom().item(formatPrompt(state, waypoint, ws, List.of()));
            }

            String placeholders = state.getCharacterIds().stream()
                .map(id -> "?").collect(Collectors.joining(","));
            Tuple tuple = Tuple.tuple();
            state.getCharacterIds().forEach(tuple::addString);

            return mysqlPool.preparedQuery(
                    "SELECT name FROM `character` WHERE characterUUID IN (" + placeholders + ")")
                .execute(tuple)
                .map(rows -> {
                    List<String> names = new ArrayList<>();
                    for (var row : rows) names.add(row.getString("name"));
                    return formatPrompt(state, waypoint, ws, names);
                });
        }).map(prompt -> "=== STORY SETTING ===\n" + (scenario == null ? "" : scenario) + "\n\n" + prompt);
        });
    }

    private String formatPrompt(JourneyState state, JourneyWaypoint waypoint,
                                 WorldState ws, List<String> characterNames) {
        StringBuilder sb = new StringBuilder();
        if (ws.getCurrentScene() != null) sb.append("Established scene: ").append(ws.getCurrentScene()).append("\n");
        sb.append("[TRAVEL_STEP] Step ").append(waypoint.getStepIndex() + 1)
          .append("/").append(state.getTotalSteps()).append("\n");
        sb.append("From: ").append(state.getFromLocationName())
          .append(" -> To: ").append(state.getToLocationName()).append("\n");

        // Current terrain and direction of travel
        String terrainStr = waypoint.getBiome() != null ? waypoint.getBiome() : "unknown";
        sb.append("Current terrain: ").append(terrainStr);
        if (waypoint.getHeadingDirection() != null) {
            sb.append(", heading ").append(waypoint.getHeadingDirection());
        }
        sb.append("\n");

        // Upcoming terrain
        if (waypoint.getAheadBiome() != null) {
            sb.append("Terrain ahead: ").append(waypoint.getAheadBiome()).append("\n");
        }

        // Nearby location with terrain context
        if (waypoint.getNearbyLocationName() != null) {
            sb.append("Nearby: ").append(waypoint.getNearbyLocationName());
            if (waypoint.getNearbyLocationBiome() != null) {
                sb.append(" (").append(waypoint.getNearbyLocationBiome()).append(")");
            }
            sb.append("\n");
        }

        sb.append("Event: ").append(waypoint.getEventType() != null ? waypoint.getEventType() : "none").append("\n");
        sb.append("Time: ").append(ws.getTimeOfDay() != null ? ws.getTimeOfDay() : "morning");
        sb.append(", Day ").append(ws.getDayNumber() != null ? ws.getDayNumber() : 1);
        sb.append(". Weather: ").append(ws.getWeather() != null ? ws.getWeather() : "clear").append("\n");

        if (!characterNames.isEmpty()) {
            sb.append("Characters: ").append(String.join(", ", characterNames)).append("\n");
        }

        if (waypoint.isDestination()) {
            sb.append("\nThis is the FINAL step. The party is arriving at ")
              .append(state.getToLocationName()).append(". End with [ARRIVED].\n");
        }

        return sb.toString();
    }

    // ==================== Waypoint Generation ====================

    private List<JourneyWaypoint> generateWaypoints(MapLocation from, MapLocation to,
                                                      TerrainData terrain, List<MapLocation> allLocs,
                                                      int totalSteps, List<float[]> roadWaypoints) {
        // Build base path: road waypoints if available, else linear [from, to]
        // Anchor endpoints to exact location coordinates so the first step starts
        // at the departure and the last step ends precisely at the destination.
        List<float[]> basePath;
        if (roadWaypoints != null && roadWaypoints.size() >= 2) {
            basePath = new ArrayList<>(roadWaypoints);
            basePath.set(0, new float[]{ from.getX(), from.getY(), from.getZ() });
            basePath.set(basePath.size() - 1, new float[]{ to.getX(), to.getY(), to.getZ() });
        } else {
            basePath = List.of(
                new float[]{ from.getX(), from.getY(), from.getZ() },
                new float[]{ to.getX(), to.getY(), to.getZ() }
            );
        }

        // Compute cumulative arc lengths along the polyline
        float[] cumLengths = computeCumulativeLengths(basePath);
        float totalLength = cumLengths[cumLengths.length - 1];

        List<JourneyWaypoint> waypoints = new ArrayList<>();

        for (int i = 0; i < totalSteps; i++) {
            float t = (float) (i + 1) / totalSteps;
            float targetDist = t * totalLength;

            // Sample position along the polyline at targetDist
            float[] pos = sampleAlongPath(basePath, cumLengths, targetDist);
            float x = pos[0];
            float y = pos[1];
            float z = pos[2];

            // Fix Z to actual terrain elevation to prevent no-clip through the map
            if (terrain != null && terrain.getHeightmap() != null) {
                z = sampleElevation(x, y, terrain);
            }

            String biome = sampleBiome(x, y, terrain);
            boolean isDestination = (i == totalSteps - 1);

            // Find nearest named location
            String nearbyName = null;
            String nearbyId = null;
            float nearestDist = Float.MAX_VALUE;
            for (MapLocation loc : allLocs) {
                if (loc.getIsDiscovered() == null || !loc.getIsDiscovered()) continue;
                float dx = loc.getX() - x;
                float dy = loc.getY() - y;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist < DISCOVERY_RADIUS && dist < nearestDist) {
                    nearestDist = dist;
                    nearbyName = loc.getName();
                    nearbyId = loc.getLocationId().toString();
                }
            }

            String nearbyBiome = null;
            String finalNearbyId = nearbyId;
            if (finalNearbyId != null) {
                MapLocation nearbyLoc = allLocs.stream()
                    .filter(l -> l.getLocationId().toString().equals(finalNearbyId))
                    .findFirst().orElse(null);
                if (nearbyLoc != null) {
                    nearbyBiome = sampleBiome(nearbyLoc.getX(), nearbyLoc.getY(), terrain);
                }
            }

            waypoints.add(JourneyWaypoint.builder()
                .stepIndex(i)
                .x(x)
                .y(y)
                .z(z)
                .biome(biome)
                .nearbyLocationName(nearbyName)
                .nearbyLocationId(nearbyId)
                .nearbyLocationBiome(nearbyBiome)
                .headingDirection(null) // filled in second pass
                .aheadBiome(null)       // filled in second pass
                .isDestination(isDestination)
                .eventType("none") // Will be overwritten by TravelEventRoller
                .build());
        }

        // Second pass: compute heading direction and ahead biome for each waypoint
        for (int i = 0; i < waypoints.size(); i++) {
            JourneyWaypoint wp = waypoints.get(i);
            String heading = null;
            String aheadBiome = null;

            if (!wp.isDestination() && i + 1 < waypoints.size()) {
                JourneyWaypoint next = waypoints.get(i + 1);
                heading = computeHeading(wp.getX(), wp.getY(), next.getX(), next.getY());
                aheadBiome = next.getBiome();
            } else {
                // Last step: heading toward destination (no next waypoint, already there)
                heading = null;
                aheadBiome = null;
            }

            waypoints.set(i, wp.toBuilder()
                .headingDirection(heading)
                .aheadBiome(aheadBiome)
                .build());
        }

        return waypoints;
    }

    private String computeHeading(float fromX, float fromY, float toX, float toY) {
        double angle = Math.toDegrees(Math.atan2(toY - fromY, toX - fromX));
        angle = (angle + 360) % 360;
        String[] directions = {"east", "northeast", "north", "northwest", "west", "southwest", "south", "southeast"};
        int index = (int) Math.round(angle / 45) % 8;
        return directions[index];
    }

    /**
     * Compute cumulative arc lengths for a polyline path.
     */
    private float[] computeCumulativeLengths(List<float[]> path) {
        float[] lengths = new float[path.size()];
        lengths[0] = 0f;
        for (int i = 1; i < path.size(); i++) {
            float[] prev = path.get(i - 1);
            float[] curr = path.get(i);
            float dx = curr[0] - prev[0];
            float dy = curr[1] - prev[1];
            float dz = curr.length > 2 && prev.length > 2 ? curr[2] - prev[2] : 0f;
            lengths[i] = lengths[i - 1] + (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return lengths;
    }

    /**
     * Sample a position along a polyline at a given arc-length distance.
     */
    private float[] sampleAlongPath(List<float[]> path, float[] cumLengths, float targetDist) {
        // Clamp to path bounds
        if (targetDist <= 0f) return path.get(0);
        if (targetDist >= cumLengths[cumLengths.length - 1]) return path.get(path.size() - 1);

        // Find the segment containing targetDist
        for (int i = 1; i < cumLengths.length; i++) {
            if (targetDist <= cumLengths[i]) {
                float segLength = cumLengths[i] - cumLengths[i - 1];
                if (segLength <= 0f) return path.get(i);
                float localT = (targetDist - cumLengths[i - 1]) / segLength;
                float[] a = path.get(i - 1);
                float[] b = path.get(i);
                float az = a.length > 2 ? a[2] : 0f;
                float bz = b.length > 2 ? b[2] : 0f;
                return new float[]{
                    a[0] + localT * (b[0] - a[0]),
                    a[1] + localT * (b[1] - a[1]),
                    az + localT * (bz - az)
                };
            }
        }
        return path.get(path.size() - 1);
    }

    private String sampleBiome(float x, float y, TerrainData terrain) {
        if (terrain == null || terrain.getBiomeGrid() == null) return "GRASSLAND";
        int width = terrain.getWidth();
        int height = terrain.getHeight();
        float ws = worldSizeForScale(terrain.getMapScale());
        float wo = ws * 0.5f;
        int gx = Math.round((x + wo) / ws * width);
        int gy = Math.round((y + wo) / ws * height);
        gx = Math.max(0, Math.min(width - 1, gx));
        gy = Math.max(0, Math.min(height - 1, gy));
        int biomeId = terrain.getBiomeGrid()[gy * width + gx];
        return Biome.fromId(biomeId).name();
    }

    private float sampleElevation(float x, float y, TerrainData terrain) {
        if (terrain == null || terrain.getHeightmap() == null) return 0;
        int width = terrain.getWidth();
        int height = terrain.getHeight();
        float ws = worldSizeForScale(terrain.getMapScale());
        float wo = ws * 0.5f;
        int gx = Math.round((x + wo) / ws * width);
        int gy = Math.round((y + wo) / ws * height);
        gx = Math.max(0, Math.min(width - 1, gx));
        gy = Math.max(0, Math.min(height - 1, gy));
        return terrain.getHeightmap()[gy * width + gx];
    }

    private static float worldSizeForScale(String scale) {
        return MapScaleUtils.worldSizeForScale(scale);
    }

    // ==================== Time Advancement ====================

    private Uni<Void> advanceTimeForStep(JourneyState state) {
        // For multi-step journeys that span many time periods, advance proportionally
        int step = state.getCurrentStep();
        int periodsPerStep = (int) (Math.ceil((double) state.getTimePeriodsRequired() * (step + 1) / state.getTotalSteps())
            - Math.ceil((double) state.getTimePeriodsRequired() * step / state.getTotalSteps()));

        Uni<Void> uni = Uni.createFrom().voidItem();
        for (int i = 0; i < periodsPerStep; i++) {
            uni = uni.chain(v -> worldStateService.progressTimeOfDay(state.getConversationId()));
        }
        return uni;
    }

    // ==================== DB Persistence ====================

    private Uni<Void> persistJourneyState(JourneyState state) {
        JsonArray charIds = new JsonArray();
        state.getCharacterIds().forEach(charIds::add);

        JsonArray waypointsJson = new JsonArray();
        for (JourneyWaypoint wp : state.getWaypoints()) {
            waypointsJson.add(new JsonObject()
                .put("stepIndex", wp.getStepIndex())
                .put("x", wp.getX())
                .put("y", wp.getY())
                .put("z", wp.getZ())
                .put("biome", wp.getBiome())
                .put("nearbyLocationName", wp.getNearbyLocationName())
                .put("nearbyLocationId", wp.getNearbyLocationId())
                .put("isDestination", wp.isDestination())
                .put("eventType", wp.getEventType()));
        }

        JsonArray stepResultsJson = stepResultsToJson(state.getStepResults());

        String sql = """
            INSERT INTO journey_state (journeyId, conversationId, mapId, userId, characterIds,
                fromLocationId, fromLocationName, toLocationId, toLocationName,
                waypoints, totalSteps, currentStep, status, distance, effectiveCost,
                timePeriodsRequired, usesRoad, stepResults)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        return mysqlPool.preparedQuery(sql)
            .execute(Tuple.tuple()
                .addValue(state.getJourneyId()).addValue(state.getConversationId()).addValue(state.getMapId())
                .addValue(state.getUserId()).addValue(charIds.encode()).addValue(state.getFromLocationId())
                .addValue(state.getFromLocationName()).addValue(state.getToLocationId())
                .addValue(state.getToLocationName()).addValue(waypointsJson.encode())
                .addValue(state.getTotalSteps()).addValue(state.getCurrentStep())
                .addValue(state.getStatus().name()).addValue(state.getDistance()).addValue(state.getEffectiveCost())
                .addValue(state.getTimePeriodsRequired()).addValue(state.isUsesRoad())
                .addValue(stepResultsJson.encode()))
            .replaceWithVoid();
    }

    private Uni<Void> updateJourneyInDb(JourneyState state) {
        JsonArray stepResultsJson = stepResultsToJson(state.getStepResults());
        return mysqlPool.preparedQuery(
                "UPDATE journey_state SET currentStep = ?, status = ?, interruptReason = ?, stepResults = ? WHERE journeyId = ?")
            .execute(Tuple.of(state.getCurrentStep(), state.getStatus().name(),
                state.getInterruptReason(), stepResultsJson.encode(), state.getJourneyId()))
            .replaceWithVoid();
    }

    private JourneyState rowToJourneyState(Row row) {
        try {
            List<String> charIds = new ArrayList<>();
            String charIdsJson = ServiceHelper.safeGetJsonText(row, "characterIds");
            if (charIdsJson != null && !charIdsJson.isBlank()) {
                JsonArray arr = new JsonArray(charIdsJson);
                for (int i = 0; i < arr.size(); i++) {
                    charIds.add(arr.getString(i));
                }
            }

            List<JourneyWaypoint> waypoints = new ArrayList<>();
            String waypointsJson = ServiceHelper.safeGetJsonText(row, "waypoints");
            if (waypointsJson != null && !waypointsJson.isBlank()) {
                JsonArray arr = new JsonArray(waypointsJson);
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject wpObj = arr.getJsonObject(i);
                    waypoints.add(JourneyWaypoint.builder()
                        .stepIndex(wpObj.getInteger("stepIndex", i))
                        .x(wpObj.getFloat("x", 0f))
                        .y(wpObj.getFloat("y", 0f))
                        .z(wpObj.getFloat("z", 0f))
                        .biome(wpObj.getString("biome"))
                        .nearbyLocationName(wpObj.getString("nearbyLocationName"))
                        .nearbyLocationId(wpObj.getString("nearbyLocationId"))
                        .isDestination(wpObj.getBoolean("isDestination", false))
                        .eventType(wpObj.getString("eventType", "none"))
                        .build());
                }
            }

            List<JourneyStepResult> stepResults = new ArrayList<>();
            String stepResultsJson = row.getString("stepResults");
            if (stepResultsJson != null && !stepResultsJson.isBlank()) {
                JsonArray arr = new JsonArray(stepResultsJson);
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject obj = arr.getJsonObject(i);
                    List<String> discIds = new ArrayList<>();
                    JsonArray discArr = obj.getJsonArray("discoveredLocationIds");
                    if (discArr != null) {
                        for (int j = 0; j < discArr.size(); j++) {
                            discIds.add(discArr.getString(j));
                        }
                    }
                    stepResults.add(JourneyStepResult.builder()
                        .journeyId(obj.getString("journeyId"))
                        .stepNumber(obj.getInteger("stepNumber"))
                        .totalSteps(obj.getInteger("totalSteps"))
                        .narration(obj.getString("narration"))
                        .eventType(obj.getString("eventType"))
                        .x(obj.getFloat("x", 0f))
                        .y(obj.getFloat("y", 0f))
                        .z(obj.getFloat("z", 0f))
                        .biome(obj.getString("biome"))
                        .timeOfDay(obj.getString("timeOfDay"))
                        .dayNumber(obj.getInteger("dayNumber"))
                        .journeyComplete(obj.getBoolean("journeyComplete", false))
                        .journeyInterrupted(obj.getBoolean("journeyInterrupted", false))
                        .journeyPaused(obj.getBoolean("journeyPaused", false)).battleId(obj.getString("battleId"))
                        .interruptReason(obj.getString("interruptReason"))
                        .discoveredLocationIds(discIds)
                        .build());
                }
            }

            return JourneyState.builder()
                .journeyId(row.getString("journeyId"))
                .conversationId(row.getString("conversationId"))
                .mapId(row.getString("mapId"))
                .userId(row.getString("userId"))
                .characterIds(charIds)
                .fromLocationId(row.getString("fromLocationId"))
                .fromLocationName(row.getString("fromLocationName"))
                .toLocationId(row.getString("toLocationId"))
                .toLocationName(row.getString("toLocationName"))
                .waypoints(waypoints)
                .stepResults(stepResults)
                .totalSteps(row.getInteger("totalSteps"))
                .currentStep(row.getInteger("currentStep"))
                .status(JourneyStatus.valueOf(row.getString("status")))
                .interruptReason(row.getString("interruptReason"))
                .distance(row.getFloat("distance"))
                .effectiveCost(row.getFloat("effectiveCost"))
                .timePeriodsRequired(row.getInteger("timePeriodsRequired"))
                .usesRoad(row.getBoolean("usesRoad"))
                .build();
        } catch (Exception e) {
            log.error("[journey] Failed to deserialize journey state from DB", e);
            return null;
        }
    }

    // ==================== Event Publishing ====================

    private void publishStepResult(String conversationId, JourneyStepResult result) {
        JsonObject payload = stepResultToJson(result);
        vertx.eventBus().publish(RoleplayEventAddresses.TRAVEL_JOURNEY_STEP + conversationId, payload);

        // Persist step result for backfill on reconnect
        AtomicReference<JourneyState> persisted = new AtomicReference<>();
        activeJourneys.computeIfPresent(conversationId, (id, current) -> {
            if (!current.getJourneyId().equals(result.getJourneyId())) return current;
            List<JourneyStepResult> updatedSteps = new ArrayList<>(current.getStepResults());
            updatedSteps.removeIf(stepResult -> stepResult.getStepNumber() == result.getStepNumber());
            updatedSteps.add(result);
            JourneyState updated = current.toBuilder().stepResults(updatedSteps).build();
            persisted.set(updated);
            return updated;
        });
        JourneyState updated = persisted.get();
        if (updated != null) {
            updateStepResultsInDb(updated).subscribe().with(
                v -> {}, err -> log.warn("[journey] Failed to persist step result: {}", err.getMessage()));
        }
    }

    private JsonObject stepResultToJson(JourneyStepResult result) {
        JsonObject payload = new JsonObject()
            .put("journeyId", result.getJourneyId())
            .put("stepNumber", result.getStepNumber())
            .put("totalSteps", result.getTotalSteps())
            .put("narration", result.getNarration())
            .put("eventType", result.getEventType())
            .put("x", result.getX())
            .put("y", result.getY())
            .put("z", result.getZ())
            .put("biome", result.getBiome())
            .put("timeOfDay", result.getTimeOfDay())
            .put("dayNumber", result.getDayNumber())
            .put("journeyComplete", result.isJourneyComplete())
            .put("journeyInterrupted", result.isJourneyInterrupted())
            .put("journeyPaused", result.isJourneyPaused()).put("battleId", result.getBattleId())
            .put("interruptReason", result.getInterruptReason());
        JsonArray discIds = new JsonArray();
        if (result.getDiscoveredLocationIds() != null) {
            result.getDiscoveredLocationIds().forEach(discIds::add);
        }
        payload.put("discoveredLocationIds", discIds);
        return payload;
    }

    private JsonArray stepResultsToJson(List<JourneyStepResult> results) {
        JsonArray arr = new JsonArray();
        for (JourneyStepResult r : results) {
            arr.add(stepResultToJson(r));
        }
        return arr;
    }

    private Uni<Void> updateStepResultsInDb(JourneyState state) {
        JsonArray stepResultsJson = stepResultsToJson(state.getStepResults());
        return mysqlPool.preparedQuery(
                "UPDATE journey_state SET stepResults = ? WHERE journeyId = ?")
            .execute(Tuple.of(stepResultsJson.encode(), state.getJourneyId()))
            .replaceWithVoid();
    }

    private void publishEntityMoved(JourneyState state) {
        mapService.getEntityPositions(state.getMapId())
            .subscribe().with(
                positions -> {
                    for (MapCharacterPosition pos : positions) {
                        boolean isMovedEntity = false;
                        if (pos.getUserId() != null && pos.getUserId().toString().equals(state.getUserId())) {
                            isMovedEntity = true;
                        }
                        if (pos.getCharacterId() != null) {
                            for (String charId : state.getCharacterIds()) {
                                if (pos.getCharacterId().toString().equals(charId)) {
                                    isMovedEntity = true;
                                    break;
                                }
                            }
                        }
                        if (isMovedEntity) {
                            JsonObject moveEvent = new JsonObject()
                                .put("id", pos.getId().toString())
                                .put("mapId", pos.getMapId().toString())
                                .put("characterId", pos.getCharacterId() != null ? pos.getCharacterId().toString() : null)
                                .put("userId", pos.getUserId() != null ? pos.getUserId().toString() : null)
                                .put("locationId", pos.getLocationId() != null ? pos.getLocationId().toString() : null)
                                .put("x", pos.getX())
                                .put("y", pos.getY())
                                .put("z", pos.getZ());
                            vertx.eventBus().publish(RoleplayEventAddresses.MAP_ENTITY_MOVED + state.getMapId(), moveEvent);
                        }
                    }
                },
                err -> log.warn("[journey] Failed to publish entity moved events: {}", err.getMessage())
            );
    }

    // ==================== Scene Linkage ====================

    // ==================== Message Saving ====================

    // ==================== LLM Content Extraction ====================

    private static String extractLlmContent(JsonObject response) {
        JsonArray choices = response.getJsonArray("choices");
        if (choices == null || choices.isEmpty()) return null;
        JsonObject message = choices.getJsonObject(0).getJsonObject("message");
        return message != null ? message.getString("content") : null;
    }

    // ==================== Turn System Integration ====================

    /**
     * Trigger a character response after journey arrival so NPCs react.
     * Fire-and-forget — only triggers if the conversation is idle (WAITING_FOR_USER).
     */
    private void triggerCharacterResponseOnArrival(String conversationId, String userId) {
        groupChatService.triggerArrivalResponse(conversationId, userId)
            .subscribe().with(
                v -> {},
                err -> log.warn("[journey] Failed to trigger character response on arrival: {}",
                    err.getMessage())
            );
    }

}
