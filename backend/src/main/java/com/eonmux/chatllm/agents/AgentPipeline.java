package com.eonmux.chatllm.agents;

import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.config.RoleplayEventAddresses;
import com.eonmux.chatllm.map.LocalMapService;
import com.eonmux.chatllm.map.MapService;
import com.eonmux.chatllm.map.StageContext;
import com.eonmux.chatllm.map.StageMovementPolicy;
import com.eonmux.chatllm.map.StageMovementResult;
import com.eonmux.chatllm.map.StageService;
import com.eonmux.chatllm.models.CharacterProposal;
import com.eonmux.chatllm.services.*;
import com.eonmux.chatllm.utils.CharacterFacts;
import com.eonmux.chatllm.utils.PronounHelper;
import com.eonmux.chatllm.utils.ServiceHelper;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class AgentPipeline {

    private final int maxSkepticRetries;
    private ConversationProgressService progressService;
    private LocationResidentService residents;
    public void setResidentService(LocationResidentService service) { this.residents = service; }
    public void setProgressService(ConversationProgressService service) { this.progressService = service; }


    private final Vertx vertx;
    private final Pool mysqlPool;
    private final AppConfig config;
    private final HighlighterAgent highlighter;
    private final MuseAgent muse;
    private final NarratorAgent narrator;
    private final AccountantAgent accountant;
    private final SkepticAgent skeptic;
    private final CastingDirectorAgent caster;
    private final SceneDecisionAgent encounterDecision;
    private final SceneDecisionAgent travelDecision;
    private final SceneDecisionAgent interactionDecision;
    private final KnowledgeSieveService knowledgeSieveService;
    private final WorldStateService worldStateService;
    private final SceneService sceneService;
    private final RetrievalService retrievalService;
    private final CastingService castingService;
    private final CharacterStatService characterStatService;
    private final InventoryService inventoryService;
    private final RelationshipService relationshipService;
    private final MapService mapService;
    private final StageService stageService;
    private final BattleService battleService;

    public AgentPipeline(Vertx vertx, Pool mysqlPool, AppConfig config,
                         AgentModelConfigService modelConfigService,
                         KnowledgeSieveService knowledgeSieveService,
                         WorldStateService worldStateService,
                         SceneService sceneService,
                         RetrievalService retrievalService,
                         CastingService castingService,
                         CharacterStatService characterStatService,
                         InventoryService inventoryService,
                         RelationshipService relationshipService,
                         MapService mapService,
                         StageService stageService, BattleService battleService) {
        this.vertx = vertx;
        this.mysqlPool = mysqlPool;
        this.config = config;
        this.knowledgeSieveService = knowledgeSieveService;
        this.worldStateService = worldStateService;
        this.sceneService = sceneService;
        this.retrievalService = retrievalService;
        this.castingService = castingService;
        this.characterStatService = characterStatService;
        this.inventoryService = inventoryService;
        this.relationshipService = relationshipService;
        this.mapService = mapService;
        this.stageService = stageService;
        this.battleService = battleService;
        this.maxSkepticRetries = config.getAgentSkepticMaxRetries();

        this.highlighter = new HighlighterAgent(vertx, mysqlPool, config, modelConfigService);
        this.muse = new MuseAgent(vertx, mysqlPool, config, modelConfigService);
        this.narrator = new NarratorAgent(vertx, mysqlPool, config, modelConfigService);
        this.accountant = new AccountantAgent(vertx, mysqlPool, config, modelConfigService);
        this.skeptic = new SkepticAgent(vertx, mysqlPool, config, modelConfigService);
        this.caster = new CastingDirectorAgent(vertx, mysqlPool, config, modelConfigService);
        this.encounterDecision = new SceneDecisionAgent(SceneDecisionAgent.Question.ENCOUNTER, vertx, mysqlPool, config, modelConfigService);
        this.travelDecision = new SceneDecisionAgent(true, vertx, mysqlPool, config, modelConfigService);
        this.interactionDecision = new SceneDecisionAgent(false, vertx, mysqlPool, config, modelConfigService);
    }

    /**
     * Run the full pipeline for a roleplay message.
     * Returns narration + agent metadata. Character response generation is handled separately.
     */
    public Uni<PipelineResult> process(String conversationId, String triggerMessageId,
                                           String triggerContent, JsonArray recentMessages) {
        return process(conversationId, triggerMessageId, triggerContent, recentMessages, false, null);
    }

    public Uni<PipelineResult> process(String conversationId, String triggerMessageId,
                                      String triggerContent, JsonArray recentMessages,
                                      boolean postDialogue, String roundLastMessageId) {
        long startTime = System.currentTimeMillis();
        log.info("[pipeline] Starting for conversation {} triggered by message {}",
            conversationId, triggerMessageId);

        PipelineResult.PipelineResultBuilder resultBuilder = PipelineResult.builder();

        // All state operations are awaited; retain per-operation application warnings.
        java.util.List<String> applyWarnings = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        // Build initial context
        return buildContext(conversationId, triggerMessageId, triggerContent, recentMessages)
            .invoke(context -> {
                context.setPostDialogue(postDialogue);
                context.setRoundLastMessageId(roundLastMessageId);
                String run = roundLastMessageId != null ? roundLastMessageId : "opening:" + conversationId;
                context.setProgressRunId(run);
                if (progressService != null) {
                    context.setProgressReporter((stage,label) -> progressService.update(conversationId,run,stage,label));
                }
            })
            .call(this::hydrateTriggerPlayer)
            .call(this::requireCurrentRound)
            .invoke(context -> { if (progressService != null) progressService.begin(conversationId,context.getProgressRunId()); })
            .call(context -> mysqlPool.preparedQuery("SELECT battleId, status, currentNarration, battleData FROM battle WHERE conversationId = ? ORDER BY (status = 'ACTIVE') DESC, createdAt DESC, battleId DESC LIMIT 1")
                .execute(Tuple.of(conversationId)).invoke(rows -> {
                    if (rows.size() > 0) {
                        Row row = rows.iterator().next();
                        context.setBattleContext(new JsonObject().put("battleId", row.getString("battleId"))
                            .put("status", row.getString("status")).put("currentNarration", row.getString("currentNarration"))
                            .put("battleData", ServiceHelper.safeGetJsonText(row, "battleData")));
                    }
                }))
            // Step 1: Highlighter
            .chain(context -> {
                log.info("[pipeline] Step 1: Running Highlighter");
                return highlighter.run(context)
                    .map(hlResult -> {
                        resultBuilder.highlighterResult(hlResult);
                        // Update context with highlighter output
                        if (hlResult.getStructuredOutput() != null) {
                            context.setHighlighterOutput(
                                hlResult.getStructuredOutput().getJsonArray("entries", new JsonArray()));
                        }
                        return context;
                    });
            })
            .call(this::requireCurrentRound)
            // Step 1.5: RAG -- Promote + Retrieve
            .chain(context -> {
                if (!config.isRagEnabled()) {
                    return Uni.createFrom().item(context);
                }
                log.info("[pipeline] Step 1.5: RAG (promote + retrieve)");

                // A) Promote high-importance highlights to knowledge_entry
                Uni<Integer> promoteUni;
                if (config.isRagPromotionEnabled() && context.getHighlighterOutput() != null) {
                    promoteUni = retrievalService.getHighlighterVersion(conversationId)
                        .chain(version -> retrievalService.promoteFromHighlighter(
                            conversationId, context.getHighlighterOutput(),
                            triggerMessageId, version));
                } else {
                    promoteUni = Uni.createFrom().item(0);
                }

                return promoteUni.chain(promotedCount -> {
                    // B) Build query from trigger + highlights + world state
                    String queryText = buildRetrievalQuery(context);
                    List<String> queryTags = extractTagsFromHighlighter(context.getHighlighterOutput());

                    // C) Retrieve relevant entries
                    return retrievalService.retrieve(conversationId, queryText, queryTags,
                            null, config.getRagMaxRetrievedEntries())
                        .map(scoredEntries -> {
                            // D) Set retrieved knowledge on context
                            if (!scoredEntries.isEmpty()) {
                                String contextString = retrievalService.buildContextString(scoredEntries);
                                context.setRetrievedKnowledge(contextString);
                                JsonArray entriesArray = new JsonArray();
                                for (RetrievalService.ScoredEntry se : scoredEntries) {
                                    entriesArray.add(new JsonObject()
                                        .put("summary", se.entry().getSummary())
                                        .put("score", se.score())
                                        .put("importance", se.entry().getImportance()));
                                }
                                context.setRetrievedEntries(entriesArray);
                                log.info("[pipeline] RAG retrieved {} entries (promoted {})",
                                    scoredEntries.size(), promotedCount);
                            }
                            return context;
                        });
                });
            })
            .chain(context -> resolveEncounter(context, encounterDecision::run))
            // Resolve one physical question at a time, before the state bookkeeping.
            .chain(context -> resolvePostDialogueScene(context, travelDecision::run, interactionDecision::run))
            .chain(this::prepareArrivalResident)
            // Step 2: Muse + Accountant in parallel
            .chain(context -> {
                log.info("[pipeline] Step 2: Running Muse + Accountant in parallel");
                Uni<AgentResult> museUni = muse.run(context);
                Uni<AgentResult> accountantUni = accountant.run(context);

                return Uni.combine().all().unis(museUni, accountantUni).asTuple()
                    .map(tuple -> {
                        AgentResult museResult = tuple.getItem1();
                        AgentResult accountantResult = tuple.getItem2();
                        resultBuilder.museResult(museResult);
                        resultBuilder.accountantResult(accountantResult);

                        // Only use output from successful agents -- error messages must not
                        // leak into downstream prompts (e.g. "Connection refused" as Muse guidance)
                        context.setMuseOutput(
                            "error".equals(museResult.getStatus()) ? "" : museResult.getContent());
                        context.setAccountantOutput(
                            "error".equals(accountantResult.getStatus()) ? "" : accountantResult.getContent());
                        return context;
                    });
            })
            // A paused/interrupted round must not apply delayed model output.
            .call(this::requireCurrentRound)
            // Step 2.5: Apply the scene-affecting changes before narration so the
            // narrator sees the same room the characters will speak from.
            .chain(context -> {
                PipelineResult interim = resultBuilder.build();
                AgentResult acctResult = interim.getAccountantResult();
                if (acctResult == null || "error".equals(acctResult.getStatus())
                        || acctResult.getStructuredOutput() == null) {
                    applyWarnings.add("Bookkeeping decisions were unavailable; no bookkeeping proposals were applied");
                    return Uni.createFrom().item(context);
                }
                if (context.getBattleContext() != null && "ACTIVE".equals(context.getBattleContext().getString("status")))
                    return Uni.createFrom().item(context);
                JsonObject output = acctResult.getStructuredOutput().copy();
                if (context.isSceneResolutionComplete()) {
                    JsonObject changes = output.getJsonObject("worldStateChanges", new JsonObject());
                    for (String field : List.of("currentArea", "currentLocation", "currentScene", "movementBlockedReason")) changes.remove(field);
                    output.put("worldStateChanges", changes);
                }
                return applyAccountantSceneState(context, output)
                    .chain(v -> worldStateService.getWorldContextForPrompt(context.getConversationId()))
                    .map(world -> {
                        context.setWorldState(world);
                        return world;
                    })
                    .chain(world -> stageService.getStageContext(context.getConversationId()))
                    .map(stage -> {
                        context.setStage(stage);
                        refreshMapFocus(context);
                        return context;
                    })
                    .onFailure().recoverWithItem(error -> {
                        log.warn("[pipeline:accountant] Scene state apply failed: {}", error.getMessage());
                        applyWarnings.add("Scene state apply failed: " + error.getMessage());
                        return context;
                    });
            })
            // Inventory actions run in order and finish before narration (e.g. grant, then equip).
            .call(this::requireCurrentRound)
            .chain(context -> {
                AgentResult acctResult = resultBuilder.build().getAccountantResult();
                if (acctResult == null || "error".equals(acctResult.getStatus()) || acctResult.getStructuredOutput() == null)
                    return Uni.createFrom().item(context);
                if (context.getBattleContext() != null && "ACTIVE".equals(context.getBattleContext().getString("status")))
                    return Uni.createFrom().item(context);
                JsonObject output = acctResult.getStructuredOutput();
                return applyAccountantInventory(context, output.getJsonArray("inventoryChanges", new JsonArray()), applyWarnings)
                    .chain(() -> applyAccountantProgress(context, output, applyWarnings))
                    .chain(() -> refreshInventories(context))
                    .onFailure().recoverWithItem(error -> {
                        applyWarnings.add("Inventory refresh failed: " + error.getMessage());
                        log.warn("[pipeline:inventory] Retaining confirmed scene despite refresh failure: {}", error.getMessage());
                        return context;
                    });
            })
            // Step 3: Narrator
            .call(this::requireCurrentRound)
            .chain(context -> {
                if (context.isEncounterNarrationSaved()) {
                    context.setNarratorOutput("");
                    return Uni.createFrom().item(context);
                }
                log.info("[pipeline] Step 3: Running Narrator");
                return narrator.run(context)
                    .map(narResult -> {
                        resultBuilder.narratorResult(narResult);
                        // Don't pass error messages as narration
                        String narration = "error".equals(narResult.getStatus()) ? "" : narResult.getContent();
                        if ((narration == null || narration.isBlank()) && (context.getStageMoveDescription() != null || context.getStageMoveFailure() != null)) {
                            narration = NarratorAgent.movementFallback(context);
                        }
                        context.setNarratorOutput(narration);
                        return context;
                    });
            })
            // Step 4: Skeptic Pass 1 -- Narration Gate
            .chain(context -> {
                if (context.getNarratorOutput() == null || context.getNarratorOutput().isEmpty()) {
                    log.info("[pipeline] Step 4: Skipping Skeptic Pass 1 (no narration)");
                    return Uni.createFrom().item(context);
                }
                log.info("[pipeline] Step 4: Running Skeptic Pass 1 (Narration Gate)");
                return validateNarrationWithRetry(context, 0)
                    .map(validatedResult -> {
                        resultBuilder.skepticNarrationResult(validatedResult);
                        return context;
                    });
            })
            // Build final result and kick off async casting
            .call(this::requireCurrentRound)
            .map(context -> {
                ensureRequiredNarration(context);
                PipelineResult result = resultBuilder
                    .narration(context.getNarratorOutput())
                    .arrivalResident(context.getArrivalResident())
                    .totalDurationMs(System.currentTimeMillis() - startTime)
                    .build();

                // Every operation has settled, so these warnings cover all failed proposals.
                if (!applyWarnings.isEmpty()) {
                    java.util.List<String> existing = result.getWarnings();
                    if (existing == null) existing = new java.util.ArrayList<>();
                    else existing = new java.util.ArrayList<>(existing);
                    existing.addAll(applyWarnings);
                    result.setWarnings(existing);
                }

                // Extract casting triggers from narrator structured output
                List<String> castTriggers = extractCastingTriggersFromNarrator(result.getNarratorResult());
                result.setCastingTriggers(castTriggers);

                // Fire-and-forget async casting for each trigger (does not block narration delivery)
                if (!castTriggers.isEmpty() && config.isAgentCastingEnabled()) {
                    log.info("[pipeline] Found {} casting triggers, processing async", castTriggers.size());
                    processCastingTriggersAsync(context, castTriggers);
                }

                log.info("[pipeline] Completed in {}ms", result.getTotalDurationMs());
                return result;
            })
            .onFailure().recoverWithItem(err -> {
                log.error("[pipeline] Failed: {}", err.getMessage(), err);
                return PipelineResult.builder()
                    .totalDurationMs(System.currentTimeMillis() - startTime)
                    .failed(true)
                    .errorMessage(err.getMessage())
                    .build();
            });
    }

    private Uni<AgentContext> prepareArrivalResident(AgentContext context) {
        if (residents == null || context.getStageMoveDescription() == null) return Uni.createFrom().item(context);
        if (context.getProgressReporter() != null) context.getProgressReporter().accept("residents","Meeting the locals…");
        return requireCurrentRound(context).chain(() -> residents.prepare(context)).invoke(context::setArrivalResident)
            .chain(() -> residents.onstage(context.getConversationId())).invoke(ids -> {
                context.setCharacterIdsInScene(ids);
                if (context.getCharacterSheet() != null) context.getCharacterSheet().fieldNames().stream()
                    .filter(id -> !id.startsWith("user:") && !ids.contains(id)).toList().forEach(context.getCharacterSheet()::remove);
                JsonObject resident=context.getArrivalResident();
                if (resident != null) {
                    if (context.getCharacterNames() == null) context.setCharacterNames(new JsonObject());
                    if (context.getCharacterDescriptions() == null) context.setCharacterDescriptions(new JsonObject());
                    context.getCharacterNames().put(resident.getString("id"),resident.getString("name"));
                    context.getCharacterDescriptions().put(resident.getString("id"),resident.getString("description"));
                }
            }).replaceWith(context)
            .onFailure().recoverWithItem(error -> { log.warn("[arrival] Could not establish local residents: {}",error.getMessage()); return context; });
    }

    Uni<Void> hydrateTriggerPlayer(AgentContext context) {
        if (context.getTriggerMessageId() == null) return Uni.createFrom().voidItem();
        return mysqlPool.preparedQuery("SELECT ownerId, displayName FROM message WHERE messageUUID = ? "
                + "AND conversationId = ? AND role = 'user' AND isDeleted = FALSE")
            .execute(Tuple.of(context.getTriggerMessageId(), context.getConversationId()))
            .invoke(rows -> {
                if (rows.size() == 0) return;
                Row author = rows.iterator().next();
                String id = author.getString("ownerId"), name = author.getString("displayName");
                if (id != null && !id.isBlank()) context.setUserId(id);
                if (name != null && !name.isBlank()) context.setPlayerName(name);
            }).replaceWithVoid();
    }

    Uni<AgentContext> resolveEncounter(AgentContext context,
            java.util.function.Function<AgentContext, Uni<AgentResult>> decide) {
        if (!context.isPostDialogue() || (context.getBattleContext() != null
                && "ACTIVE".equals(context.getBattleContext().getString("status")))) return Uni.createFrom().item(context);
        return requireCurrentRound(context).chain(() -> decide.apply(context)).chain(result -> {
            if (!result.isSuccess() || result.getStructuredOutput() == null)
                return Uni.createFrom().failure(new IllegalStateException("Encounter decision unavailable"));
            JsonObject value = result.getStructuredOutput();
            if (!value.getBoolean("encounter")) return Uni.createFrom().item(context);
            return requireCurrentRound(context).chain(() -> battleService.initiateBattle(context.getConversationId(), context.getUserId(),
                value.getString("enemyName"), value.getString("enemyDescription"), value.getDouble("enemyHp"), context.getTriggerContent()))
                .map(battle -> { context.setBattleContext(JsonObject.mapFrom(battle)); context.setEncounterNarrationSaved(true); return context; });
        });
    }

    /** Travel, application, then (only without travel) the independent interaction question. */
    Uni<AgentContext> resolvePostDialogueScene(AgentContext context,
            java.util.function.Function<AgentContext, Uni<AgentResult>> travel,
            java.util.function.Function<AgentContext, Uni<AgentResult>> interaction) {
        if (!context.isPostDialogue() || (context.getBattleContext() != null
                && "ACTIVE".equals(context.getBattleContext().getString("status")))) return Uni.createFrom().item(context);
        StageContext before = context.getStage();
        return requireCurrentRound(context).chain(() -> travel.apply(context))
            .chain(decision -> requireCurrentRound(context).chain(() -> {
                if ((!decision.isSuccess() || decision.getStructuredOutput() == null)
                        && PlayerMovementIntent.localDestination(context) == null)
                    return Uni.createFrom().failure(new IllegalStateException("Travel decision unavailable"));
                JsonObject value = decision.getStructuredOutput();
                JsonObject changes = new JsonObject();
                if (value != null && !"error".equals(decision.getStatus())) {
                    if (value.getBoolean("move", false)) {
                        changes.put("currentArea", value.getString("currentArea"));
                        changes.put("currentLocation", value.getString("currentLocation"));
                    }
                    if (value.getString("movementBlockedReason") != null)
                        changes.put("movementBlockedReason", value.getString("movementBlockedReason"));
                }
                if (changes.isEmpty() && PlayerMovementIntent.localDestination(context) == null)
                    return Uni.createFrom().voidItem();
                if (context.getProgressReporter() != null) context.getProgressReporter().accept("movement","Updating the map…");
                return applyAccountantSceneState(context, new JsonObject().put("worldStateChanges", changes));
            }))
            .chain(() -> refreshResolvedScene(context, before))
            .chain(ignored -> {
                if (context.getStageMoveDescription() != null || context.getStageMoveFailure() != null)
                    return Uni.createFrom().item(context);
                return requireCurrentRound(context).chain(() -> interaction.apply(context)).invoke(decision -> {
                    if (!decision.isSuccess() || decision.getStructuredOutput() == null)
                        throw new IllegalStateException("Interaction decision unavailable");
                    JsonObject value = decision.getStructuredOutput();
                    if (!"error".equals(decision.getStatus()) && value != null && value.getBoolean("occurred", false))
                        context.setInteractionResult(value.getString("result"));
                }).replaceWith(context);
            })
            .invoke(ignored -> context.setSceneResolutionComplete(true));
    }

    private Uni<AgentContext> refreshResolvedScene(AgentContext context, StageContext before) {
        return worldStateService.getWorldContextForPrompt(context.getConversationId())
            .invoke(context::setWorldState)
            .chain(() -> stageService.getStageContext(context.getConversationId()))
            .chain(after -> {
                context.setStage(after);
                refreshMapFocus(context);
                // A move may commit before a later visit/event refresh fails. Compare
                // actual stages as well as the returned result so its narration survives.
                if (before != null && before.isPresent() && after != null && after.isPresent()
                        && (!before.getNode().getLocationId().equals(after.getNode().getLocationId())
                            || !before.getArea().getLocalAreaId().equals(after.getArea().getLocalAreaId()))) {
                    context.setStageMoveDescription(describeStageMove(before, StageMovementResult.builder()
                        .kind(before.getNode().getLocationId().equals(after.getNode().getLocationId())
                            ? StageMovementPolicy.Kind.AREA : StageMovementPolicy.Kind.NODE).stage(after).build()));
                }
                if (context.getStageMoveDescription() != null && after != null && after.isPresent()) {
                    String scene = "The party arrived in the " + after.getArea().getName() + " at " + after.getNode().getName() + ".";
                    if (context.getWorldState() != null && scene.equals(context.getWorldState().getString("currentScene")))
                        return Uni.createFrom().item(context);
                    return worldStateService.updateWorldState(context.getConversationId(), new JsonObject().put("currentScene", scene))
                        .replaceWith(context);
                }
                return Uni.createFrom().item(context);
            }).onFailure().recoverWithItem(error -> {
                log.warn("[pipeline:scene] Could not refresh the resolved scene: {}", error.getMessage());
                return context;
            });
    }

    static void ensureRequiredNarration(AgentContext context) {
        String text = context.getNarratorOutput();
        if (text != null && !text.isBlank() && !NarratorAgent.isNoNarration(text)) return;
        if (context.getStageMoveDescription() != null || context.getStageMoveFailure() != null)
            context.setNarratorOutput(NarratorAgent.movementFallback(context));
        else if (context.getInteractionResult() != null && !context.getInteractionResult().isBlank())
            context.setNarratorOutput(context.getInteractionResult());
    }

    Uni<Void> requireCurrentRound(AgentContext context) {
        return requireCurrentRound(mysqlPool, context);
    }

    public static Uni<Void> requireCurrentRound(Pool mysqlPool, AgentContext context) {
        if (!context.isPostDialogue()) return Uni.createFrom().voidItem();
        return mysqlPool.preparedQuery("""
                SELECT c.conversationGUID FROM conversation c
                WHERE c.conversationGUID = ? AND c.groupState = 'CHARACTER_RESPONDING'
                  AND c.currentSpeakerId IS NULL
                  AND (SELECT m.messageUUID FROM message m WHERE m.conversationId = c.conversationGUID
                       AND m.role = 'user' AND m.isDeleted = FALSE ORDER BY m.seq DESC LIMIT 1) = ?
                  AND (SELECT m.messageUUID FROM message m WHERE m.conversationId = c.conversationGUID
                       AND m.role = 'assistant' AND m.isDeleted = FALSE ORDER BY m.seq DESC LIMIT 1) = ?
                """).execute(Tuple.of(context.getConversationId(), context.getTriggerMessageId(), context.getRoundLastMessageId()))
                .chain(rows -> rows.size() > 0 ? Uni.createFrom().voidItem()
                    : Uni.createFrom().failure(new IllegalStateException("Narrative round was paused or superseded")));
    }

    /**
     * Validate a character response through Skeptic Pass 2.
     * Called externally after the character response has been generated.
     */
    public Uni<AgentResult> validateCharacterResponse(String conversationId,
                                                          String characterResponse,
                                                          String characterId,
                                                          JsonArray sievedMemory,
                                                          JsonObject characterSheet) {
        return characterFactsFor(characterId)
            .map(facts -> AgentContext.builder()
                .conversationId(conversationId)
                .triggerContent(characterResponse)
                .characterId(characterId)
                .highlighterOutput(sievedMemory)
                .characterSheet(characterSheet)
                .characterNames(facts.getJsonObject("names"))
                .characterPronouns(facts.getJsonObject("pronouns"))
                .characterDescriptions(facts.getJsonObject("descriptions"))
                .characterAppearances(facts.getJsonObject("appearances"))
                .characterProfiles(facts.getJsonObject("profiles"))
                .characterAttributes(facts.getJsonObject("attributes"))
                .build())
            .chain(context -> validateCharacterWithRetry(context, 0));
    }

    /**
     * Loads the prompt facts for one character. A lookup failure leaves the
     * gate with the sheet alone instead of blocking the response.
     */
    private Uni<JsonObject> characterFactsFor(String characterId) {
        if (characterId == null || characterId.isBlank()) {
            return Uni.createFrom().item(emptyCharacterFacts());
        }
        return mysqlPool.preparedQuery("""
                SELECT characterUUID, name, pronouns, gender, ageYears, height, build, hair, eyes,
                       strength, dexterity, constitution, intelligence, wisdom, charisma,
                       LEFT(systemPrompt, 800) AS identity,
                       LEFT(description, 240) AS description,
                       LEFT(appearance, 180) AS appearance
                FROM `character` WHERE characterUUID = ?
                """)
            .execute(Tuple.of(characterId))
            .map(AgentPipeline::characterFactsFromRows)
            .onFailure().recoverWithItem(emptyCharacterFacts());
    }

    private static JsonObject emptyCharacterFacts() {
        return new JsonObject()
            .put("names", new JsonObject())
            .put("pronouns", new JsonObject())
            .put("descriptions", new JsonObject())
            .put("appearances", new JsonObject())
            .put("profiles", new JsonObject())
            .put("attributes", new JsonObject());
    }

    /**
     * Retry loop for Narrator validation.
     */
    private Uni<AgentResult> validateNarrationWithRetry(AgentContext context, int attempt) {
        return validateNarrationWithRetry(context, attempt,
            current -> skeptic.evaluate(current, SkepticAgent.Pass.NARRATION), narrator::executeWithFeedback);
    }

    Uni<AgentResult> validateNarrationWithRetry(AgentContext context, int attempt,
            java.util.function.Function<AgentContext, Uni<AgentResult>> review,
            java.util.function.BiFunction<AgentContext, String, Uni<AgentResult>> revise) {
        ensureRequiredNarration(context);
        if (context.getProgressReporter() != null) context.getProgressReporter().accept("review","Reviewing the narration…");
        return review.apply(context).chain(verdict -> {
            if (!"rejected".equals(verdict.getStatus())) return Uni.createFrom().item(verdict);
            if (attempt >= maxSkepticRetries) {
                verdict.addAnnotation("skeptic_warning", "Narration review retry limit reached");
                return Uni.createFrom().item(verdict);
            }
            JsonObject details = verdict.getStructuredOutput();
            String feedback = details == null ? "" : details.getString("reason", "");
            String suggestion = details == null ? "" : details.getString("suggestion", "");
            if ((feedback == null || feedback.isBlank()) && (suggestion == null || suggestion.isBlank())) {
                verdict.addAnnotation("skeptic_warning", "No actionable revision feedback; retained the established event");
                return Uni.createFrom().item(verdict);
            }
            String instruction = (feedback == null ? "" : feedback) + "\n" + (suggestion == null ? "" : suggestion);
            return revise.apply(context, instruction).chain(revision -> {
                context.setNarratorOutput("error".equals(revision.getStatus()) ? "" : revision.getContent());
                ensureRequiredNarration(context);
                return validateNarrationWithRetry(context, attempt + 1, review, revise);
            });
        }).onFailure().recoverWithItem(error -> {
            // Review is editorial: a transport failure must not erase an event
            // already committed by the movement/inventory services.
            ensureRequiredNarration(context);
            log.warn("[pipeline:narration] Review failed; retaining the resolved event: {}", error.getMessage());
            AgentResult warning = AgentResult.error("skeptic:narration", error.getMessage());
            warning.addAnnotation("skeptic_warning", "Review unavailable; retained established narration");
            return warning;
        });
    }

    /**
     * Evaluate a character response through the Skeptic.
     * Returns the verdict directly -- character re-generation is handled by the caller
     * because it requires streaming and the full PromptBuilder pipeline.
     */
    private Uni<AgentResult> validateCharacterWithRetry(AgentContext context, int attempt) {
        return skeptic.evaluate(context, SkepticAgent.Pass.CHARACTER)
            .chain(verdictResult -> {
                if (!"rejected".equals(verdictResult.getStatus())) {
                    return Uni.createFrom().item(verdictResult);
                }

                String reason = verdictResult.getStructuredOutput() != null
                    ? verdictResult.getStructuredOutput().getString("reason", "Rejected by quality check")
                    : "Rejected by quality check";
                log.info("[pipeline] Character response rejected (attempt {}), feedback: {}", attempt, reason);

                if (attempt >= maxSkepticRetries) {
                    log.warn("[pipeline] Character response max retries reached ({}), returning final verdict", attempt);
                    verdictResult.addAnnotation("skeptic_warning", "Max retries reached");
                }

                return Uni.createFrom().item(verdictResult);
            });
    }

    /**
     * Applies the scene-affecting part of the Accountant output before the
     * narrator runs. Location changes go through {@link StageService}, so a
     * model cannot move the party to an unconnected room or an unreachable
     * place.
     */
    Uni<Void> applyAccountantSceneState(AgentContext context, JsonObject accountantOutput) {
        String conversationId = context.getConversationId();
        JsonObject changes = accountantOutput.getJsonObject("worldStateChanges");
        if (changes == null) changes = new JsonObject();

        JsonObject updates = new JsonObject();
        String currentScene = changes.getString("currentScene");
        String weather = changes.getString("weatherChange");
        if (weather != null && !weather.isEmpty()) updates.put("weather", weather);
        JsonObject flags = changes.getJsonObject("flagChanges");
        if (flags != null && !flags.isEmpty()) updates.put("globalFlags", flags);

        String requestedLocation = changes.getString("currentLocation");
        String requestedArea = changes.getString("currentArea");
        String blockedReason = changes.getString("movementBlockedReason");
        String committedArea = context.isSceneResolutionComplete() ? null : PlayerMovementIntent.localDestination(context);
        StageContext stage = context.getStage();
        boolean sameLocation = requestedLocation == null || requestedLocation.isBlank()
                || (stage != null && stage.isPresent() && (requestedLocation.equalsIgnoreCase(stage.getNode().getName())
                    || requestedLocation.equals(stage.getNode().getLocationId().toString())));
        boolean sameArea = requestedArea == null || requestedArea.isBlank()
                || (stage != null && stage.isPresent() && (requestedArea.equalsIgnoreCase(stage.getArea().getName())
                    || requestedArea.equals(stage.getArea().getLocalAreaId().toString())));
        if (blockedReason != null && !blockedReason.isBlank()) {
            context.setStageMoveFailure(blockedReason);
            requestedLocation = null;
            requestedArea = null;
            currentScene = null;
        } else if (committedArea != null && sameLocation && sameArea) {
            // A repeated source area is not a resolved travel decision. Recover
            // the explicit known destination, then use the normal movement policy.
            requestedLocation = null;
            requestedArea = committedArea;
            log.info("[pipeline:stage] Resolving player travel command to '{}'", committedArea);
        }
        boolean moveRequested = (requestedLocation != null && !requestedLocation.isBlank())
                || (requestedArea != null && !requestedArea.isBlank());

        // The opening stage is fixed. The map and the entry area were just
        // created, and the narrator is told not to move the party. An
        // accountant that reads "preparing to enter the tunnel" as a move must
        // not put the party in the tunnel before anyone crosses the threshold.
        boolean opening = isSessionStart(context);
        if (opening) {
            if (moveRequested) {
                log.info("[pipeline:accountant] Opening keeps the party in '{}'; ignoring move to area='{}' location='{}'",
                        stageLabel(context.getStage()), requestedArea, requestedLocation);
            }
            requestedLocation = null;
            requestedArea = null;
            moveRequested = false;
            if (sceneImpliesMove(currentScene)) {
                log.warn("[pipeline:accountant] Opening scene described a move; keeping the stage area and dropping it");
                currentScene = null;
            }
        }

        // A scene that says the party moved must name the area it entered, or
        // currentScene and currentArea disagree and every later prompt reads
        // the party as moving through a room it is still standing in. Recover
        // the area from the stage when the scene names exactly one reachable
        // room; otherwise drop the move from the scene text.
        boolean repeatsSourceArea = sameLocation && stage != null && stage.isPresent() && requestedArea != null
                && (requestedArea.equalsIgnoreCase(stage.getArea().getName())
                    || requestedArea.equals(stage.getArea().getLocalAreaId().toString()));
        if (!opening && context.getStageMoveFailure() == null && (!moveRequested || repeatsSourceArea)
                && sceneImpliesMove(currentScene)) {
            String inferredArea = inferAreaFromScene(currentScene, context.getStage());
            if (inferredArea != null) {
                requestedArea = inferredArea;
                moveRequested = true;
                log.info("[pipeline:accountant] Scene names a move to '{}'; syncing the area.", inferredArea);
            } else {
                log.warn("[pipeline:accountant] Scene records a move but names no reachable area; "
                        + "keeping the current area and dropping the move from the scene text");
                currentScene = null;
            }
        }
        if (currentScene != null && !currentScene.isEmpty()) updates.put("currentScene", currentScene);

        final String moveLocation = requestedLocation;
        final String moveArea = requestedArea;
        final boolean move = moveRequested;
        Uni<StageMovementResult> moveUni = move
                ? stageService.move(conversationId, moveLocation, moveArea)
                : Uni.createFrom().item(StageMovementResult.builder()
                        .kind(StageMovementPolicy.Kind.NONE).build());

        Uni<Void> chain = moveUni.invoke(result -> {
            if (result.getKind() == StageMovementPolicy.Kind.REJECTED) {
                log.warn("[pipeline:stage] Rejected move to location='{}' area='{}': {}",
                        moveLocation, moveArea, result.getReason());
                context.setStageMoveFailure(result.getReason());
                updates.remove("currentScene");
                log.warn("[pipeline:accountant] Dropped currentScene because its requested move was rejected");
            } else if (result.moved()) {
                log.info("[pipeline:stage] {}", result.getReason());
                // Tell the narrator what just moved so it can show the passage;
                // without this it only sees the party already standing in the
                // new room and stays silent.
                context.setStageMoveDescription(describeStageMove(context.getStage(), result));
                if (result.getStage() != null && result.getStage().isPresent()) {
                    updates.put("currentScene", "The party arrived in the " + result.getStage().getArea().getName()
                            + " at " + result.getStage().getNode().getName() + ".");
                }
                if (result.getStage() != null && result.getStage().isPresent()) context.setStage(result.getStage());
                publishStageMovement(conversationId, result);
            }
        }).replaceWithVoid();

        if (changes.getBoolean("timeAdvance", false)) {
            chain = chain.chain(v -> context.getStageMoveFailure() == null
                    ? worldStateService.progressTimeOfDay(conversationId) : Uni.createFrom().voidItem());
        }
        chain = chain.chain(v -> updates.isEmpty() ? Uni.createFrom().voidItem()
                : worldStateService.updateWorldState(conversationId, updates).replaceWithVoid());
        return chain
                .chain(v -> worldStateService.getOrCreateWorldState(conversationId))
                .invoke(state -> vertx.eventBus().publish(
                        RoleplayEventAddresses.WORLD_STATE_CHANGED + conversationId,
                        WorldStateService.toGraphQL(state)))
                .replaceWithVoid()
                .onFailure().recoverWithUni(error -> {
                    log.warn("[pipeline:accountant] Failed to apply scene state: {}", error.getMessage());
                    return Uni.createFrom().voidItem();
                });
    }

    /**
     * One human sentence for the narrator: where the party moved from and to.
     * Returns null when there is nothing worth narrating.
     */
    static String describeStageMove(com.eonmux.chatllm.map.StageContext before,
                                            StageMovementResult result) {
        com.eonmux.chatllm.map.StageContext after = result.getStage();
        if (after == null || !after.isPresent() || before == null || !before.isPresent()) return null;
        if (result.getKind() == StageMovementPolicy.Kind.NODE) {
            return "from " + before.getNode().getName() + " to " + after.getNode().getName();
        }
        if (before.getArea().getLocalAreaId().equals(after.getArea().getLocalAreaId())) return null;
        List<UUID> route = LocalMapService.route(before.getArea().getLocalAreaId(),
                after.getArea().getLocalAreaId(), before.getConnections());
        List<String> passages = new ArrayList<>();
        for (int i = 1; i < route.size(); i++) {
            UUID from = route.get(i - 1), to = route.get(i);
            for (var connection : before.getConnections()) {
                if ((from.equals(connection.getFromAreaId()) && to.equals(connection.getToAreaId()))
                        || (to.equals(connection.getFromAreaId()) && from.equals(connection.getToAreaId()))) {
                    if (connection.getLabel() != null && !connection.getLabel().isBlank()) passages.add(connection.getLabel());
                    break;
                }
            }
        }
        return "from the " + before.getArea().getName() + " to the " + after.getArea().getName()
                + (passages.isEmpty() ? "" : " via " + String.join(" then ", passages));
    }

    private void publishStageMovement(String conversationId, StageMovementResult result) {        StageContext stage = result.getStage();
        if (stage == null || !stage.isPresent()) return;
        for (var discovered : result.getDiscovered()) {
            vertx.eventBus().publish(
                    RoleplayEventAddresses.MAP_LOCATION_DISCOVERED + conversationId,
                    new JsonObject()
                            .put("locationId", discovered.getLocationId().toString())
                            .put("mapId", discovered.getMapId().toString())
                            .put("name", discovered.getName())
                            .put("description", discovered.getDescription())
                            .put("x", discovered.getX())
                            .put("y", discovered.getY())
                            .put("z", discovered.getZ())
                            .put("icon", discovered.getIcon())
                            .put("locationType", discovered.getLocationType())
                            .put("isDiscovered", true));
        }
        if (result.getKind() == StageMovementPolicy.Kind.NODE) {
            vertx.eventBus().publish(
                    RoleplayEventAddresses.MAP_ENTITY_MOVED + stage.getMapId(),
                    new JsonObject()
                            .put("mapId", stage.getMapId())
                            .put("toLocationId", stage.getNode().getLocationId().toString())
                            .put("toLocationName", stage.getNode().getName()));
        }
    }

    /**
     * Re-points the narrator's macro map context at the node the stage now
     * occupies. Without this, a move during the current message would leave
     * the MAP CONTEXT block describing the previous node.
     */
    private void refreshMapFocus(AgentContext context) {
        StageContext stage = context.getStage();
        JsonArray locations = context.getMapLocations();
        if (stage == null || !stage.isPresent() || locations == null) return;
        String nodeId = stage.getNode().getLocationId().toString();
        JsonObject current = null;
        for (int i = 0; i < locations.size(); i++) {
            JsonObject location = locations.getJsonObject(i);
            if (nodeId.equals(location.getString("locationId"))) {
                current = location;
                break;
            }
        }
        if (current == null) return;
        context.setCurrentMapLocation(current);

        JsonArray nearby = new JsonArray();
        float cx = current.getFloat("x", 0f);
        float cy = current.getFloat("y", 0f);
        float cz = current.getFloat("z", 0f);
        for (int i = 0; i < locations.size(); i++) {
            JsonObject location = locations.getJsonObject(i);
            if (location == current) continue;
            float dx = location.getFloat("x", 0f) - cx;
            float dy = location.getFloat("y", 0f) - cy;
            float dz = location.getFloat("z", 0f) - cz;
            float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance <= 40f) {
                nearby.add(location.copy().put("distance", Math.round(distance * 10f) / 10f));
            }
        }
        context.setNearbyLocations(nearby);
    }

    Uni<Void> applyAccountantInventory(AgentContext context, JsonArray changes, List<String> warnings) {
        Uni<Void> sequence = Uni.createFrom().voidItem();
        for (int index = 0; index < changes.size(); index++) {
            final int current = index;
            sequence = sequence.chain(() -> requireCurrentRound(context).chain(() -> Uni.createFrom().deferred(() -> {
                JsonObject change = changes.getJsonObject(current);
                String characterId = change.getString("characterId");
                String userId = change.getString("userId");
                if (userId == null && characterId != null && characterId.equals(context.getUserId())) {
                    userId = characterId;
                    characterId = null;
                }
                com.eonmux.chatllm.services.InventoryService.validateOwner(characterId, userId);
                if (characterId != null && (context.getCharacterIdsInScene() == null || !context.getCharacterIdsInScene().contains(characterId)))
                    throw new IllegalArgumentException("Inventory character is not in the scene");
                String conversationId = context.getConversationId();
                String name = change.getString("itemName");
                String action = change.getString("action", "");
                if (name == null || name.isBlank()) throw new IllegalArgumentException("Item name is required");
                if ("add".equals(action)) return inventoryService.add(conversationId, characterId, userId, name,
                    change.getString("itemDescription"), change.getInteger("quantity", 1), change.getString("category"),
                    change.getJsonObject("properties"), false, null).replaceWithVoid();
                if ("remove".equals(action)) return inventoryService.removeByName(conversationId, characterId, userId, name,
                    change.getInteger("quantity", 1)).replaceWithVoid();
                Uni<List<com.eonmux.chatllm.models.CharacterInventory>> inventory = characterId != null
                    ? inventoryService.getInventory(conversationId, characterId) : inventoryService.getUserInventory(conversationId, userId);
                return inventory.chain(items -> {
                    var matches = items.stream().filter(item -> name.equalsIgnoreCase(item.getItemName())
                        && (change.getString("inventoryId") == null || change.getString("inventoryId").equals(item.getInventoryId().toString()))).toList();
                    if (matches.size() != 1) throw new IllegalArgumentException("Item is missing or its name is ambiguous: " + name);
                    String id = matches.getFirst().getInventoryId().toString();
                    return switch (action) {
                        case "equip", "unequip" -> inventoryService.equipItem(id, "equip".equals(action)).replaceWithVoid();
                        case "use" -> inventoryService.useItem(id, null, change.getString("targetCharacterId")).replaceWithVoid();
                        default -> Uni.createFrom().failure(new IllegalArgumentException("Unknown inventory action: " + action));
                    };
                });
            }).onFailure().recoverWithItem(error -> {
                log.warn("[pipeline:accountant] Inventory change failed: {}", error.getMessage());
                warnings.add("Inventory change failed: " + error.getMessage());
                return null;
            })));
        }
        return sequence;
    }

    private Uni<AgentContext> refreshInventories(AgentContext context) {
        JsonObject sheets = context.getCharacterSheet() == null ? new JsonObject() : context.getCharacterSheet();
        List<Uni<?>> reads = new ArrayList<>();
        for (String id : context.getCharacterIdsInScene() == null ? List.<String>of() : context.getCharacterIdsInScene()) {
            reads.add(characterStatService.getStats(context.getConversationId(), id).invoke(stats -> {
                JsonArray values = new JsonArray(); stats.forEach(stat -> values.add(JsonObject.mapFrom(stat)));
                JsonObject sheet = sheets.getJsonObject(id, new JsonObject()); sheet.put("stats", values); sheets.put(id, sheet);
            }));
            reads.add(relationshipService.getRelationships(context.getConversationId(), id).invoke(relationships -> {
                JsonArray values = new JsonArray(); relationships.forEach(value -> values.add(JsonObject.mapFrom(value)));
                JsonObject sheet = sheets.getJsonObject(id, new JsonObject()); sheet.put("relationships", values); sheets.put(id, sheet);
            }));
            reads.add(inventoryService.getInventory(context.getConversationId(), id).invoke(items -> {
                JsonArray inventory = new JsonArray();
                items.forEach(item -> inventory.add(JsonObject.mapFrom(item)));
                JsonObject sheet = sheets.getJsonObject(id, new JsonObject());
                sheet.put("inventory", inventory);
                sheets.put(id, sheet);
            }));
        }
        if (context.getUserId() != null) reads.add(inventoryService.getUserInventory(context.getConversationId(), context.getUserId()).invoke(items -> {
            JsonArray inventory = new JsonArray();
            items.forEach(item -> inventory.add(JsonObject.mapFrom(item)));
            sheets.put("user:" + context.getUserId(), new JsonObject().put("inventory", inventory));
        }));
        reads.add(inventoryService.recentActions(context.getConversationId()).invoke(context::setInventoryActions));
        return Uni.combine().all().unis(reads).with(ignored -> {
            context.setCharacterSheet(sheets);
            return context;
        });
    }

    /** Apply each accepted progress operation serially and await it before narration. */
    Uni<Void> applyAccountantProgress(AgentContext context, JsonObject output, List<String> warnings) {
        String conversation = context.getConversationId();
        Uni<Void> sequence = Uni.createFrom().voidItem();
        for (Object raw : output.getJsonArray("statChanges", new JsonArray())) {
            JsonObject change = (JsonObject) raw;
            sequence = sequence.chain(() -> requireCurrentRound(context).chain(() -> characterStatService.modifyStat(conversation,
                change.getString("characterId"), change.getString("statName"), change.getDouble("delta"))
                .invoke(stat -> { if (stat != null) vertx.eventBus().publish(RoleplayEventAddresses.STAT_CHANGED + conversation, JsonObject.mapFrom(stat)); })
                .replaceWithVoid().onFailure().recoverWithItem(error -> {
                    warnings.add("Stat proposal was not applied: " + error.getMessage()); return null;
                })));
        }
        for (Object raw : output.getJsonArray("relationshipChanges", new JsonArray())) {
            JsonObject change = (JsonObject) raw;
            sequence = sequence.chain(() -> requireCurrentRound(context).chain(() -> relationshipService.adjustRelationship(conversation,
                change.getString("fromId"), change.getString("toId"), change.getString("type"), change.getDouble("intensityDelta"), "Established round consequence")
                .invoke(relationship -> { if (relationship != null) vertx.eventBus().publish(RoleplayEventAddresses.RELATIONSHIP_CHANGED + conversation, JsonObject.mapFrom(relationship)); })
                .replaceWithVoid().onFailure().recoverWithItem(error -> {
                    warnings.add("Relationship proposal was not applied: " + error.getMessage()); return null;
                })));
        }
        return sequence;
    }

    /**
     * Build the initial AgentContext with all necessary data.
     */
    Uni<AgentContext> buildContext(String conversationId, String triggerMessageId,
                                              String triggerContent, JsonArray recentMessages) {
        AgentContext.AgentContextBuilder contextBuilder = AgentContext.builder()
            .conversationId(conversationId)
            .triggerMessageId(triggerMessageId)
            .triggerContent(triggerContent)
            .recentMessages(recentMessages);

        // Extract player userId and name from trigger content
        // (format: "Player: <name> (userId: <uuid>)").
        if (triggerContent != null) {
            java.util.regex.Matcher uidMatcher = java.util.regex.Pattern
                .compile("userId:\\s*([0-9a-f-]{36})")
                .matcher(triggerContent);
            if (uidMatcher.find()) {
                contextBuilder.userId(uidMatcher.group(1));
            }
            java.util.regex.Matcher nameMatcher = java.util.regex.Pattern
                .compile("Player:\\s*(.+?)\\s*\\(userId:")
                .matcher(triggerContent);
            if (nameMatcher.find()) {
                String name = nameMatcher.group(1).trim();
                if (!name.isEmpty()) contextBuilder.playerName(name);
            }
        }

        // Fetch world state and active scene characters in parallel
        Uni<JsonObject> worldStateUni = worldStateService.getWorldContextForPrompt(conversationId)
            .onFailure().recoverWithItem(new JsonObject());

        // Try active scene characters first, fall back to conversation_character (needed during session init)
        // The onstage flag is the authoritative presence list for narration and
        // dialogue. Unintroduced roster members stay out of every prompt.
        Uni<java.util.List<String>> sceneCharsUni = mysqlPool.preparedQuery("""
                    SELECT cc.characterId FROM conversation_character cc
                    WHERE cc.conversationId = ? AND cc.isActive = TRUE
                      AND (cc.isIntroduced = TRUE OR cc.isIntroduced IS NULL)
                    ORDER BY cc.turnOrder
                    """)
            .execute(Tuple.of(conversationId))
            .map(rows -> {
                java.util.List<String> ids = new java.util.ArrayList<>();
                for (var row : rows) ids.add(row.getString("characterId"));
                return ids;
            })
            .onFailure().recoverWithItem(java.util.Collections.emptyList());

        // Get template rules from conversation
        Uni<JsonObject> templateRulesUni = getTemplateRules(conversationId);

        // Fetch roleplay description from conversation
        Uni<String> descriptionUni = mysqlPool.preparedQuery(
                "SELECT description FROM conversation WHERE conversationGUID = ?")
            .execute(Tuple.of(conversationId))
            .map(rows -> {
                if (rows.size() > 0) {
                    return rows.iterator().next().getString("description");
                }
                return null;
            })
            .onFailure().recoverWithItem((String) null);

        // Fetch map data in parallel with world state
        Uni<JsonObject> mapDataUni = mapService.getMaps(conversationId)
            .chain(maps -> {
                if (maps == null || maps.isEmpty()) {
                    return Uni.createFrom().item(new JsonObject());
                }
                var map = maps.get(0);
                String mapId = map.getMapId().toString();
                Uni<java.util.List<com.eonmux.chatllm.models.MapLocation>> locsUni =
                    mapService.getLocations(mapId);
                Uni<java.util.List<com.eonmux.chatllm.models.MapCharacterPosition>> posUni =
                    mapService.getEntityPositions(mapId);

                return Uni.combine().all().unis(locsUni, posUni).asTuple()
                    .map(tuple -> {
                        java.util.List<com.eonmux.chatllm.models.MapLocation> locs = tuple.getItem1();
                        java.util.List<com.eonmux.chatllm.models.MapCharacterPosition> positions = tuple.getItem2();

                        JsonArray locArray = new JsonArray();
                        for (var loc : locs) {
                            locArray.add(new JsonObject()
                                .put("locationId", loc.getLocationId().toString())
                                .put("name", loc.getName())
                                .put("description", loc.getDescription())
                                .put("locationType", loc.getLocationType())
                                .put("x", loc.getX())
                                .put("y", loc.getY())
                                .put("z", loc.getZ())
                                .put("isDiscovered", loc.getIsDiscovered()));
                        }

                        JsonArray posArray = new JsonArray();
                        for (var pos : positions) {
                            JsonObject pObj = new JsonObject()
                                .put("x", pos.getX())
                                .put("y", pos.getY())
                                .put("z", pos.getZ());
                            if (pos.getCharacterId() != null) pObj.put("characterId", pos.getCharacterId().toString());
                            if (pos.getUserId() != null) pObj.put("userId", pos.getUserId().toString());
                            if (pos.getLocationId() != null) pObj.put("locationId", pos.getLocationId().toString());
                            posArray.add(pObj);
                        }

                        return new JsonObject()
                            .put("mapName", map.getName())
                            .put("mapScale", map.getMapScale())
                            .put("locations", locArray)
                            .put("entityPositions", posArray);
                    });
            })
            .onFailure().recoverWithItem(err -> {
                log.debug("[pipeline] No map data available: {}", err.getMessage());
                return new JsonObject();
            });

        // Resolve the authoritative stage. This also initializes the local
        // layout for the current node when it is missing.
        Uni<StageContext> stageUni = stageService.getStageContext(conversationId)
            .onFailure().recoverWithItem(error -> {
                log.debug("[pipeline] No stage available: {}", error.getMessage());
                return StageContext.empty();
            });

        return Uni.combine().all().unis(worldStateUni, sceneCharsUni, templateRulesUni,
                        descriptionUni, mapDataUni, stageUni).asTuple()
            .chain(tuple -> {
                JsonObject worldState = tuple.getItem1();
                java.util.List<String> sceneChars = tuple.getItem2();
                JsonObject templateRules = tuple.getItem3();
                String roleplayDescription = tuple.getItem4();
                JsonObject mapData = tuple.getItem5();
                StageContext stage = tuple.getItem6();

                contextBuilder
                    .worldState(worldState)
                    .characterIdsInScene(sceneChars)
                    .templateRules(templateRules)
                    .roleplayDescription(roleplayDescription)
                    .stage(stage);

                // Populate map context if available
                if (mapData != null && mapData.containsKey("locations")) {
                    contextBuilder
                        .mapName(mapData.getString("mapName"))
                        .mapScale(mapData.getString("mapScale"))
                        .mapLocations(mapData.getJsonArray("locations"))
                        .entityMapPositions(mapData.getJsonArray("entityPositions"));

                    // Find the current location from world state
                    String currentLocationName = worldState != null
                        ? worldState.getString("currentLocation", "") : "";
                    String currentLocationId = worldState != null
                        ? worldState.getString("currentLocationId", "") : "";
                    if (!currentLocationName.isEmpty() || !currentLocationId.isEmpty()) {
                        JsonArray locs = mapData.getJsonArray("locations");
                        JsonArray nearby = new JsonArray();
                        JsonObject currentLoc = null;
                        for (int i = 0; i < locs.size(); i++) {
                            JsonObject loc = locs.getJsonObject(i);
                            if (!currentLocationId.isEmpty()
                                    && currentLocationId.equals(loc.getString("locationId"))) {
                                currentLoc = loc;
                                break;
                            }
                            if (currentLocationName.equalsIgnoreCase(loc.getString("name", ""))) {
                                currentLoc = loc;
                            }
                        }
                        // If we found the current location, compute nearby locations
                        if (currentLoc != null) {
                            contextBuilder.currentMapLocation(currentLoc);
                            float cx = currentLoc.getFloat("x", 0f);
                            float cy = currentLoc.getFloat("y", 0f);
                            float cz = currentLoc.getFloat("z", 0f);
                            float nearbyRadius = 40f; // units
                            for (int i = 0; i < locs.size(); i++) {
                                JsonObject loc = locs.getJsonObject(i);
                                if (loc == currentLoc) continue;
                                float dx = loc.getFloat("x", 0f) - cx;
                                float dy = loc.getFloat("y", 0f) - cy;
                                float dz = loc.getFloat("z", 0f) - cz;
                                float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                                if (dist <= nearbyRadius) {
                                    nearby.add(loc.copy().put("distance", Math.round(dist * 10f) / 10f));
                                }
                            }
                            contextBuilder.nearbyLocations(nearby);
                        }
                    }
                }

                // Fetch character sheet data (stats, inventory, relationships) for scene characters
                if (sceneChars == null || sceneChars.isEmpty()) {
                    return Uni.createFrom().item(contextBuilder.build());
                }

                // Fetch character names, pronouns, roles, appearance and the
                // physical facts the card shows. Every agent that writes about
                // a character reads the same values, so a height or a hair
                // color cannot change between prompts.
                Uni<JsonObject> namesUni;
                if (!sceneChars.isEmpty()) {
                    String placeholders = sceneChars.stream().map(id -> "?").collect(Collectors.joining(","));
                    String nameQuery = "SELECT characterUUID, name, pronouns, gender, ageYears, "
                        + "LEFT(systemPrompt, 800) AS identity, "
                        + "LEFT(description, 240) AS description, LEFT(appearance, 180) AS appearance, "
                        + "height, build, hair, eyes, strength, dexterity, constitution, "
                        + "intelligence, wisdom, charisma "
                        + "FROM `character` WHERE characterUUID IN (" + placeholders + ")";
                    Tuple nameTuple = Tuple.tuple();
                    for (String id : sceneChars) {
                        nameTuple.addValue(id);
                    }
                    namesUni = mysqlPool.preparedQuery(nameQuery)
                        .execute(nameTuple)
                        .map(AgentPipeline::characterFactsFromRows)
                        .onFailure().recoverWithItem(emptyCharacterFacts());
                } else {
                    namesUni = Uni.createFrom().item(emptyCharacterFacts());
                }

                // Build sheet unis for each character (3 per character: stats, inventory, relationships)
                List<Uni<?>> sheetUnis = new ArrayList<>();
                for (String charId : sceneChars) {
                    sheetUnis.add(characterStatService.getStats(conversationId, charId));
                    sheetUnis.add(inventoryService.getInventory(conversationId, charId));
                    sheetUnis.add(relationshipService.getRelationships(conversationId, charId));
                }

                sheetUnis.add(contextBuilder.build().getUserId() == null ? Uni.createFrom().item(List.of())
                    : inventoryService.getUserInventory(conversationId, contextBuilder.build().getUserId()));
                sheetUnis.add(inventoryService.recentActions(conversationId));

                // Combine all sheet unis into a single list
                Uni<List<?>> allSheetsUni = sheetUnis.isEmpty()
                    ? Uni.createFrom().item(Collections.emptyList())
                    : Uni.combine().all().unis(sheetUnis).with(results -> results);

                return Uni.combine().all().unis(namesUni, allSheetsUni).asTuple()
                    .map(allTuple -> {
                        JsonObject nameData = allTuple.getItem1();
                        List<?> sheetResults = allTuple.getItem2();
                        contextBuilder.characterNames(nameData.getJsonObject("names"))
                            .characterPronouns(nameData.getJsonObject("pronouns"))
                            .characterDescriptions(nameData.getJsonObject("descriptions"))
                            .characterAppearances(nameData.getJsonObject("appearances"))
                            .characterProfiles(nameData.getJsonObject("profiles"))
                            .characterAttributes(nameData.getJsonObject("attributes"));

                        JsonObject characterSheet = new JsonObject();
                        for (int i = 0; i < sceneChars.size(); i++) {
                            String charId = sceneChars.get(i);
                            int base = i * 3;
                            JsonArray statsArray = new JsonArray();
                            @SuppressWarnings("unchecked")
                            var stats = (java.util.List<?>) sheetResults.get(base);
                            for (Object stat : stats) {
                                statsArray.add(JsonObject.mapFrom(stat));
                            }
                            JsonArray invArray = new JsonArray();
                            @SuppressWarnings("unchecked")
                            var inv = (java.util.List<?>) sheetResults.get(base + 1);
                            for (Object item : inv) {
                                invArray.add(JsonObject.mapFrom(item));
                            }
                            JsonArray relArray = new JsonArray();
                            @SuppressWarnings("unchecked")
                            var rels = (java.util.List<?>) sheetResults.get(base + 2);
                            for (Object rel : rels) {
                                relArray.add(JsonObject.mapFrom(rel));
                            }
                            characterSheet.put(charId, new JsonObject()
                                .put("stats", statsArray)
                                .put("inventory", invArray)
                                .put("relationships", relArray));
                        }
                        var playerInventory = new JsonArray();
                        for (Object item : (List<?>) sheetResults.get(sceneChars.size() * 3)) playerInventory.add(JsonObject.mapFrom(item));
                        String playerId = contextBuilder.build().getUserId();
                        if (playerId != null) characterSheet.put("user:" + playerId, new JsonObject().put("inventory", playerInventory));
                        return contextBuilder.inventoryActions((JsonArray) sheetResults.get(sceneChars.size() * 3 + 1)).characterSheet(characterSheet).build();
                    })
                    .onFailure().recoverWithItem(err -> {
                        log.warn("[pipeline] Failed to fetch character sheets: {}", err.getMessage());
                        return contextBuilder.build();
                    });
            });
    }

    /**
     * Turns the character rows into the maps every prompt shares. The stored
     * pronoun field wins; the canonical identity line remains the fallback for
     * characters created before that field existed.
     */
    static JsonObject characterFactsFromRows(Iterable<Row> nameRows) {
        JsonObject names = new JsonObject();
        JsonObject pronouns = new JsonObject();
        JsonObject descriptions = new JsonObject();
        JsonObject appearances = new JsonObject();
        JsonObject profiles = new JsonObject();
        JsonObject attributes = new JsonObject();
        for (Row row : nameRows) {
            String id = row.getString("characterUUID");
            if (id == null || id.isBlank()) continue;
            names.put(id, row.getString("name"));
            String pronoun = PronounHelper.resolve(
                row.getString("pronouns"), row.getString("identity"));
            if (pronoun != null) pronouns.put(id, pronoun);
            String description = row.getString("description");
            if (description != null && !description.isBlank()) {
                descriptions.put(id, description.strip());
            }
            String appearance = row.getString("appearance");
            if (appearance != null && !appearance.isBlank()) {
                appearances.put(id, appearance.strip());
            }
            JsonObject profile = CharacterFacts.profile(
                row.getString("gender"), row.getInteger("ageYears"),
                row.getString("height"), row.getString("build"),
                row.getString("hair"), row.getString("eyes"));
            if (!profile.isEmpty()) profiles.put(id, profile);
            JsonObject scores = CharacterFacts.attributes(
                row.getInteger("strength"), row.getInteger("dexterity"),
                row.getInteger("constitution"), row.getInteger("intelligence"),
                row.getInteger("wisdom"), row.getInteger("charisma"));
            if (!scores.isEmpty()) attributes.put(id, scores);
        }
        return new JsonObject()
            .put("names", names)
            .put("pronouns", pronouns)
            .put("descriptions", descriptions)
            .put("appearances", appearances)
            .put("profiles", profiles)
            .put("attributes", attributes);
    }

    /**
     * Build a query string for RAG retrieval from context.
     */
    private String buildRetrievalQuery(AgentContext context) {
        StringBuilder query = new StringBuilder();

        // Include trigger content
        if (context.getTriggerContent() != null) {
            query.append(context.getTriggerContent()).append(" ");
        }

        // Include last 3 highlighter summaries
        if (context.getHighlighterOutput() != null) {
            int start = Math.max(0, context.getHighlighterOutput().size() - 3);
            for (int i = start; i < context.getHighlighterOutput().size(); i++) {
                JsonObject entry = context.getHighlighterOutput().getJsonObject(i);
                query.append(entry.getString("summary", "")).append(" ");
            }
        }

        // Include world state location
        if (context.getWorldState() != null) {
            String location = context.getWorldState().getString("currentLocation");
            if (location != null && !location.isBlank()) {
                query.append(location).append(" ");
            }
        }

        return query.toString().trim();
    }

    /**
     * Extract unique tags from highlighter output entries.
     */
    private List<String> extractTagsFromHighlighter(JsonArray highlighterEntries) {
        if (highlighterEntries == null || highlighterEntries.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> tags = new LinkedHashSet<>();
        for (int i = 0; i < highlighterEntries.size(); i++) {
            JsonObject entry = highlighterEntries.getJsonObject(i);
            JsonArray entryTags = entry.getJsonArray("tags");
            if (entryTags != null) {
                for (int j = 0; j < entryTags.size(); j++) {
                    String tag = entryTags.getString(j);
                    if (tag != null && !tag.isBlank()) {
                        tags.add(tag.trim().toLowerCase());
                    }
                }
            }
        }
        return new ArrayList<>(tags);
    }

    /**
     * Extract casting triggers from narrator's structured output.
     */
    List<String> extractCastingTriggersFromNarrator(AgentResult narratorResult) {
        if (narratorResult == null || narratorResult.getStructuredOutput() == null) {
            return Collections.emptyList();
        }
        JsonArray triggers = narratorResult.getStructuredOutput().getJsonArray("castingTriggers");
        if (triggers == null || triggers.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < triggers.size(); i++) {
            result.add(triggers.getString(i));
        }
        return result;
    }

    /**
     * Process casting triggers asynchronously (fire-and-forget).
     * For each trigger: CastingDirector -> Accountant review -> Skeptic CASTING pass -> persist proposal.
     */
    private void processCastingTriggersAsync(AgentContext parentContext, List<String> triggers) {
        for (String trigger : triggers) {
            processSingleCastingTrigger(parentContext, trigger)
                .subscribe().with(
                    proposal -> {
                        log.info("[pipeline:casting] Proposal created: {} ({})",
                            proposal.getProposedName(), proposal.getProposalId());
                        // Publish event for frontend subscription
                        vertx.eventBus().publish(
                            RoleplayEventAddresses.CASTING_PROPOSAL + parentContext.getConversationId(),
                            JsonObject.mapFrom(proposal));
                    },
                    err -> log.error("[pipeline:casting] Failed to process trigger '{}': {}",
                        trigger, err.getMessage()));
        }
    }

    /**
     * Process a single casting trigger through the CastingDirector -> Accountant -> Skeptic flow.
     */
    private Uni<CharacterProposal> processSingleCastingTrigger(AgentContext parentContext, String trigger) {
        // Build a casting-specific context
        AgentContext castContext = AgentContext.builder()
            .conversationId(parentContext.getConversationId())
            .triggerMessageId(parentContext.getTriggerMessageId())
            .triggerContent(parentContext.getTriggerContent())
            .userId(parentContext.getUserId())
            .worldState(parentContext.getWorldState())
            .templateRules(parentContext.getTemplateRules())
            .recentMessages(parentContext.getRecentMessages())
            .characterIdsInScene(parentContext.getCharacterIdsInScene())
            .characterNames(parentContext.getCharacterNames())
            .characterPronouns(parentContext.getCharacterPronouns())
            .characterDescriptions(parentContext.getCharacterDescriptions())
            .characterAppearances(parentContext.getCharacterAppearances())
            .characterProfiles(parentContext.getCharacterProfiles())
            .characterAttributes(parentContext.getCharacterAttributes())
            .retrievedKnowledge(parentContext.getRetrievedKnowledge())
            .castingPrompt(trigger)
            .build();

        return caster.run(castContext)
            .chain(casterResult -> {
                if (!casterResult.isSuccess()) {
                    return Uni.createFrom().failure(new RuntimeException("Caster failed: " + casterResult.getContent()));
                }

                // Set caster output on context for accountant and skeptic
                castContext.setCasterOutput(casterResult.getContent());

                // Run Accountant review on the proposal
                return accountant.reviewLoadout(castContext)
                    .chain(accountantResult -> {
                        // Run Skeptic CASTING pass
                        Uni<AgentResult> review = accountantResult.getStructuredOutput().getBoolean("approved",false)
                            ? skeptic.evaluate(castContext, SkepticAgent.Pass.CASTING)
                            : Uni.createFrom().item(AgentResult.skipped("skeptic:casting", "Loadout review rejected the proposal"));
                        return review.chain(skepticResult -> {
                                // Build and persist the proposal
                                JsonObject casterJson = casterResult.getStructuredOutput() != null
                                    ? casterResult.getStructuredOutput()
                                    : new JsonObject().put("raw", casterResult.getContent());
                                // Determine if Skeptic approved the casting
                                boolean skepticApproved = accountantResult.isSuccess() && accountantResult.getStructuredOutput().getBoolean("approved", false)
                                    && skepticResult.isSuccess() && skepticResult.getStructuredOutput() != null
                                    && skepticResult.getStructuredOutput().getBoolean("approved", false);

                                CharacterProposal proposal = CharacterProposal.builder()
                                    .conversationId(parentContext.getConversationId())
                                    .triggerMessageId(parentContext.getTriggerMessageId())
                                    .castingPrompt(trigger)
                                    .proposedName(casterJson.getString("name", "Unknown"))
                                    .proposedDescription(casterJson.getString("description", ""))
                                    .proposedSystemPrompt(casterJson.getString("systemPrompt", ""))
                                    .proposedGreeting(casterJson.getString("greeting", ""))
                                    .proposedPersonality(casterJson.getString("personality", ""))
                                    .proposedAppearance(casterJson.getString("appearance", ""))
                                    .proposedBackstory(casterJson.getString("backstory", ""))
                                    .proposedGoals(casterJson.getString("goals", ""))
                                    .accountantReview(accountantResult.getContent())
                                    .skepticReview(skepticResult.getContent())
                                    .status(skepticApproved ? "PENDING" : "REJECTED")
                                    .build();

                                return castingService.createProposal(proposal)
                                    .chain(savedProposal -> {
                                        // Auto-approve if Skeptic passed and we have a userId
                                        if (skepticApproved && parentContext.getUserId() != null) {
                                            log.info("[pipeline:casting] Auto-approving proposal {} (Skeptic approved)",
                                                savedProposal.getProposalId());
                                            return castingService.approveProposal(
                                                    savedProposal.getProposalId(),
                                                    parentContext.getUserId())
                                                .map(character -> {
                                                    log.info("[pipeline:casting] Auto-created character '{}' from proposal",
                                                        character.getString("name", savedProposal.getProposedName()));

                                                    // Apply starting inventory from caster output
                                                    String characterId = character.getString("characterUUID",
                                                        character.getString("id"));
                                                    if (characterId != null) {
                                                        applyStartingInventory(
                                                            parentContext.getConversationId(),
                                                            characterId, casterJson);
                                                    }

                                                    return savedProposal;
                                                })
                                                .onFailure().recoverWithItem(err -> {
                                                    log.warn("[pipeline:casting] Auto-approve failed for {}: {}",
                                                        savedProposal.getProposalId(), err.getMessage());
                                                    return savedProposal;
                                                });
                                        }
                                        return Uni.createFrom().item(savedProposal);
                                    });
                            });
                    });
            });
    }

    /**
     * Apply starting inventory items from the CastingDirector's output to a newly created character.
     * Fire-and-forget: failures are logged but do not block the pipeline.
     */
    private void applyStartingInventory(String conversationId, String characterId, JsonObject casterJson) {
        JsonArray items = casterJson.getJsonArray("startingInventory");
        if (items == null || items.isEmpty()) {
            return;
        }

        for (int i = 0; i < items.size(); i++) {
            JsonObject item = items.getJsonObject(i);
            if (item == null) continue;

            String itemName = item.getString("itemName", "Unknown Item");
            String description = item.getString("description", "");
            int quantity = item.getInteger("quantity", 1);
            String category = item.getString("category", "misc");

            inventoryService.addItem(conversationId, characterId, itemName, description, quantity, category, null)
                .subscribe().with(
                    added -> log.debug("[pipeline:casting] Added starting item '{}' x{} for character {}",
                        itemName, quantity, characterId),
                    err -> log.warn("[pipeline:casting] Failed to add starting item '{}' for character {}: {}",
                        itemName, characterId, err.getMessage()));
        }

        log.info("[pipeline:casting] Applied {} starting inventory items for character {}",
            items.size(), characterId);
    }

    private static final java.util.regex.Pattern SCENE_MOVE =
            java.util.regex.Pattern.compile(
                    "(?i)\\b((moves|moved)\\s+(through|into|inside)"
                            + "|(enters|entered|crosses|crossed)"
                            + "|(steps|stepped|walks|walked|heads|headed|slips|slipped)"
                            + "\\s+(into|through))\\b");

    /** True when the scene text says the party actually entered a new area. */
    static boolean sceneImpliesMove(String scene) {
        return scene != null && SCENE_MOVE.matcher(scene).find();
    }

    /** True for the pipeline run that opens a new roleplay session. */
    static boolean isSessionStart(AgentContext context) {
        return context != null && context.getTriggerContent() != null
                && context.getTriggerContent().contains("[SESSION_START]");
    }

    private static String stageLabel(StageContext stage) {
        if (stage == null || !stage.isPresent()) return "unknown";
        return stage.getNode().getName() + " / " + stage.getArea().getName();
    }

    /**
     * Finds the one reachable area the scene names.
     *
     * The accountant sometimes writes "moves through the warehouse lane" and
     * leaves currentArea at the old room. The stage still knows which areas
     * border the current one, so the move can be completed rather than left
     * half-written. When the text names no single reachable area, the caller
     * must not record the move.
     */
    static String inferAreaFromScene(String scene, StageContext stage) {
        if (scene == null || stage == null || !stage.isPresent()
                || stage.getAreas() == null || stage.getArea() == null
                || stage.getArea().getLocalAreaId() == null) {
            return null;
        }
        Set<UUID> reachable = LocalMapService.reachableAreas(
                stage.getArea().getLocalAreaId(), stage.getConnections());
        if (reachable.isEmpty()) return null;

        String lower = scene.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (var area : stage.getAreas()) {
            String name = area.getName();
            if (name == null || name.isBlank() || area.getLocalAreaId() == null) continue;
            if (area.getLocalAreaId().equals(stage.getArea().getLocalAreaId())) continue;
            if (reachable.contains(area.getLocalAreaId())
                    && wholeWord(lower, name.toLowerCase(Locale.ROOT))) {
                matches.add(name);
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static boolean wholeWord(String text, String word) {
        return java.util.regex.Pattern
                .compile("(?<![a-z0-9])" + java.util.regex.Pattern.quote(word) + "(?![a-z0-9])")
                .matcher(text)
                .find();
    }

    /**
     * Get template rules for the conversation's story template.
     */
    private Uni<JsonObject> getTemplateRules(String conversationId) {
        String query = """
            SELECT st.rules, st.description, st.genre, st.name,
                   st.suggestedCharacterArchetypes, st.moodPresets
            FROM conversation c
            JOIN story_template st ON c.storyTemplateId = st.templateId
            WHERE c.conversationGUID = ?
            """;
        return mysqlPool.preparedQuery(query)
            .execute(Tuple.of(conversationId))
            .map(rows -> {
                if (rows.size() == 0) {
                    return new JsonObject();
                }
                var row = rows.iterator().next();
                JsonObject result = new JsonObject();
                // Core rules (combatSystem, magicEnabled, techLevel, toneGuidance, narratorStyle, forbiddenElements, etc.)
                JsonObject rules = ServiceHelper.safeGetJsonObject(row, "rules");
                if (rules != null) result.mergeIn(rules);
                // Template metadata for additional agent context
                result.put("templateName", row.getString("name"));
                result.put("templateGenre", row.getString("genre"));
                result.put("templateDescription", row.getString("description"));
                JsonObject archetypes = ServiceHelper.safeGetJsonObject(row, "suggestedCharacterArchetypes");
                if (archetypes != null) result.put("suggestedCharacterArchetypes", archetypes);
                JsonObject moods = ServiceHelper.safeGetJsonObject(row, "moodPresets");
                if (moods != null) result.put("moodPresets", moods);
                return result;
            })
            .onFailure().recoverWithItem(err -> {
                log.warn("[pipeline] Failed to fetch template rules for conversation {}: {}",
                    conversationId, err.getMessage());
                return new JsonObject();
            });
    }

}
