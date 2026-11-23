package com.eonmux.chatllm.graphql.resolvers;

import com.eonmux.chatllm.graphql.auth.GraphQLAuthContext;
import com.eonmux.chatllm.models.Battle;
import com.eonmux.chatllm.models.BattleVote;
import com.eonmux.chatllm.models.inputs.InitiateBattleInput;
import com.eonmux.chatllm.models.inputs.VoteActionInput;
import com.eonmux.chatllm.services.BattleService;
import com.eonmux.chatllm.services.auth.AuthContext;
import com.eonmux.chatllm.utils.ServiceHelper;
import io.smallrye.graphql.api.Subscription;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;


@Slf4j
@GraphQLApi
@ApplicationScoped
public class BattleResolver {

    @Inject
    io.vertx.mutiny.core.Vertx mutinyVertx;

    @Inject
    Vertx vertx;

    @Inject
    BattleService battleService;

    @Inject
    GraphQLAuthContext graphQLAuthContext;

    @Inject
    ServiceHelper serviceHelper;

    @Query
    public Uni<java.util.List<Battle>> battles(@Name("conversationId") String conversationId) {
        String caller = graphQLAuthContext.requireAuth().getUserId().toString();
        return verifyAccess(conversationId, caller).chain(allowed -> allowed
            ? battleService.getBattlesByConversation(conversationId)
            : Uni.createFrom().failure(new SecurityException("Access denied")));
    }

    public Uni<Boolean> canManage(@org.eclipse.microprofile.graphql.Source Battle battle) {
        return battleService.canManage(battle.getConversationId().toString(), graphQLAuthContext.requireAuth().getUserId().toString());
    }

    public Uni<java.util.List<BattleVote>> votes(@org.eclipse.microprofile.graphql.Source Battle battle) {
        return battleService.getVotes(battle.getBattleId().toString(), battle.getRoundNumber());
    }

    @Query
    public Uni<Battle> battle(@Name("battleId") String battleId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        return battleService.getBattle(battleId)
            .chain(battle -> verifyAccess(battle.getConversationId().toString(), auth.getUserId().toString())
                .chain(hasAccess -> hasAccess
                    ? Uni.createFrom().item(battle)
                    : Uni.createFrom().failure(new RuntimeException("Access denied"))));
    }

    @Query
    public Uni<Battle> activeBattle(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        return verifyAccess(conversationId, auth.getUserId().toString())
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Access denied"));
                }
                return battleService.getActiveBattle(conversationId);
            });
    }

    @Mutation
    public Uni<Battle> initiateBattle(@Name("input") InitiateBattleInput input) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        return battleService.canManage(input.getConversationId(), userId)
            .chain(hasAccess -> {
                if (!hasAccess) {
                    return Uni.createFrom().failure(new RuntimeException("Access denied"));
                }
                return battleService.initiateBattle(
                        input.getConversationId(),
                        userId,
                        input.getEnemyName(),
                        input.getEnemyDescription(),
                        input.getEnemyHp(),
                        input.getNarrativeContext());
            });
    }

    @Mutation
    public Uni<BattleVote> voteAction(@Name("input") VoteActionInput input) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        return battleService.getBattle(input.getBattleId())
            .chain(battle -> {
                if (!"ACTIVE".equals(battle.getStatus())) {
                    return Uni.createFrom().failure(new RuntimeException("Cannot vote: battle is not active"));
                }
                return verifyAccess(battle.getConversationId().toString(), userId)
                    .chain(hasAccess -> {
                        if (!hasAccess) {
                            return Uni.createFrom().failure(new RuntimeException("Access denied"));
                        }
                        return battleService.castVote(input.getBattleId(), userId, input.getOptionIndex(), input.getRoundNumber());
                    });
            });
    }

    @Mutation
    public Uni<Battle> resolveBattleRound(@Name("battleId") String battleId, @Name("roundNumber") Integer roundNumber) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        return battleService.getBattle(battleId)
            .chain(battle -> {
                if (roundNumber != null && !roundNumber.equals(battle.getRoundNumber()))
                    return Uni.createFrom().failure(new IllegalStateException("This battle round has already advanced"));
                if (!"ACTIVE".equals(battle.getStatus())) {
                    return Uni.createFrom().failure(new RuntimeException("Cannot resolve: battle is not active"));
                }
                return verifyAccess(battle.getConversationId().toString(), userId)
                    .chain(hasAccess -> {
                        if (!hasAccess) {
                            return Uni.createFrom().failure(new RuntimeException("Access denied"));
                        }
                        return battleService.canResolve(battle, userId).chain(allowed -> allowed
                            ? resolveBattleRoundInternal(battle) : Uni.createFrom().failure(new SecurityException("Waiting for the rest of the party to vote")));
                    });
            });
    }

    private Uni<Battle> resolveBattleRoundInternal(Battle battle) {
        String battleId = battle.getBattleId().toString();
        String conversationId = battle.getConversationId().toString();

        return battleService.getVotes(battleId, battle.getRoundNumber())
            .chain(votes -> {
                if (votes.isEmpty()) {
                    return Uni.createFrom().failure(
                        new RuntimeException("No votes cast for current round"));
                }

                return battleService.resolveRound(battleId, conversationId, battle.getRoundNumber())
                    .chain(v -> battleService.getBattle(battleId));
            });
    }

    @Mutation
    public Uni<Battle> proposeBattleAction(@Name("battleId") String battleId, @Name("roundNumber") int roundNumber,
                                          @Name("description") String description) {
        String caller = graphQLAuthContext.requireAuth().getUserId().toString();
        return battleService.getBattle(battleId).chain(battle -> verifyAccess(battle.getConversationId().toString(), caller)
            .chain(allowed -> allowed ? battleService.proposeAction(battleId, roundNumber, description)
                : Uni.createFrom().failure(new SecurityException("Access denied"))));
    }

    @Mutation
    public Uni<Battle> endBattle(@Name("battleId") String battleId, @Name("status") String status) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        String userId = auth.getUserId().toString();

        // Only terminal statuses may be set, and only on an active battle —
        // previously the caller could set any string (e.g. resurrect a finished
        // battle to 'ACTIVE' or scribble arbitrary values).
        if (status == null || !(status.equals("VICTORY") || status.equals("DEFEAT") || status.equals("RETREAT"))) {
            return Uni.createFrom().failure(new RuntimeException("Invalid battle end status: " + status));
        }

        return battleService.getBattle(battleId)
            .chain(battle -> {
                if (!"ACTIVE".equals(battle.getStatus())) {
                    return Uni.createFrom().failure(new RuntimeException("Cannot end: battle is not active"));
                }
                return battleService.canManage(battle.getConversationId().toString(), userId)
                    .chain(hasAccess -> {
                        if (!hasAccess) {
                            return Uni.createFrom().failure(new RuntimeException("Access denied"));
                        }
                        return battleService.endBattle(battleId, status)
                            .chain(v -> battleService.getBattle(battleId))
;
                    });
            });
    }

    @Subscription
    public Multi<Battle> battleRound(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        return gatedBattleStream(conversationId, auth.getUserId().toString(),
                () -> createTypedEventBusMulti("rp.battle.round." + conversationId, Battle.class));
    }

    @Subscription
    public Multi<BattleVote> battleVote(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        return gatedBattleStream(conversationId, auth.getUserId().toString(),
                () -> createTypedEventBusMulti("rp.battle.vote." + conversationId, BattleVote.class));
    }

    @Subscription
    public Multi<Battle> battleStarted(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        return gatedBattleStream(conversationId, auth.getUserId().toString(),
                () -> createTypedEventBusMulti("rp.battle.started." + conversationId, Battle.class));
    }

    @Subscription
    public Multi<Battle> battleEnded(@Name("conversationId") String conversationId) {
        AuthContext auth = graphQLAuthContext.requireAuth();
        return gatedBattleStream(conversationId, auth.getUserId().toString(),
                () -> createTypedEventBusMulti("rp.battle.ended." + conversationId, Battle.class));
    }

    /**
     * Verify the caller owns or actively participates in a conversation
     * before a battle query/mutation touches its rows.
     */
    private Uni<Boolean> verifyAccess(String conversationId, String userId) {
        return serviceHelper.verifyConversationAccess(conversationId, userId);
    }

    /**
     * Gate a battle stream on conversation access (owner or active participant).
     */
    private <T> Multi<T> gatedBattleStream(String conversationId, String userId,
                                            java.util.function.Supplier<Multi<T>> streamSupplier) {
        return serviceHelper.verifyConversationAccess(conversationId, userId)
                .onItem().transformToMulti(hasAccess -> {
                    if (!hasAccess) {
                        log.warn("Battle subscription access denied: user {} -> conversation {}", userId, conversationId);
                        return Multi.createFrom().empty();
                    }
                    return streamSupplier.get();
                });
    }

    private <T> Multi<T> createTypedEventBusMulti(String address, Class<T> type) {
        return Multi.createFrom().<T>emitter(emitter -> {
            log.debug("Creating typed event bus subscription for address: {} -> {}", address, type.getSimpleName());

            MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(address);

            try {
                consumer.handler(message -> {
                    JsonObject data = message.body();
                    log.trace("Received event on address {}: {}", address, data.encode());
                    try {
                        T mapped = data.mapTo(type);
                        emitter.emit(mapped);
                    } catch (Exception e) {
                        log.warn("Failed to map event to {} on address '{}': {}. Payload keys: {}",
                            type.getSimpleName(), address, e.getMessage(), data.fieldNames());
                    }
                });

                consumer.completionHandler(ar -> {
                    if (ar.succeeded()) {
                        log.info("EventBus consumer registered for address: {}", address);
                    } else {
                        log.error("Failed to register EventBus consumer for address: {}", address, ar.cause());
                        consumer.unregister();
                        emitter.fail(ar.cause());
                    }
                });

                emitter.onTermination(() -> {
                    log.debug("Unregistering event bus consumer for address: {}", address);
                    consumer.unregister();
                });
            } catch (RuntimeException setupErr) {
                log.error("EventBus subscription setup failed for {}; unregistering consumer", address, setupErr);
                consumer.unregister();
                emitter.fail(setupErr);
            }
        });
    }


}
