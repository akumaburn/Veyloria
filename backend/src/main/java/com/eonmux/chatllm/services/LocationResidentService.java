package com.eonmux.chatllm.services;

import com.eonmux.chatllm.agents.*;
import com.eonmux.chatllm.config.RoleplayEventAddresses;
import com.eonmux.chatllm.map.*;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.*;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** Stable local workers, anchored to their room; they are not traveling party members. */
@ApplicationScoped
public class LocationResidentService {
    @Inject Pool pool;
    @Inject Vertx vertx;
    @Inject MapService mapService;
    @Inject StoryMessageService storyMessages;
    @Inject com.eonmux.chatllm.config.AppConfig config;
    @Inject AgentModelConfigService models;
    private ResidentGreetingAgent greetingAgent;
    @jakarta.annotation.PostConstruct void initialize() { greetingAgent=new ResidentGreetingAgent(vertx,pool,config,models); }

    public static String roleFor(StageContext stage) {
        if (stage == null || !stage.isPresent()) return null;
        var meta=stage.getArea().getMetadata();
        String name=(stage.getArea().getName()+" "+stage.getArea().getAreaType()).toLowerCase(Locale.ROOT);
        String description=String.valueOf(stage.getArea().getDescription()).toLowerCase(Locale.ROOT);
        if (Boolean.TRUE.equals(stage.getArea().getIsEntry())) name += " "+stage.getNode().getName().toLowerCase(Locale.ROOT)+" "+String.valueOf(stage.getNode().getLocationType()).toLowerCase(Locale.ROOT);
        if (meta != null && (Boolean.FALSE.equals(meta.getBoolean("populated")) || Boolean.TRUE.equals(meta.getBoolean("abandoned")))) return null;
        if ((name+" "+description).matches("(?s).*\\b(abandoned|deserted|ruined|uninhabited)\\b.*")) return null;
        if (name.matches("(?s).*\\b(lane|road|path|trail|bridge)\\b.*")) return null;
        if (meta != null && meta.getString("residentRole") != null && !meta.getString("residentRole").isBlank()) return meta.getString("residentRole");
        if (name.matches("(?s).*\\b(inn|tavern|pub)\\b.*")) return "Innkeeper";
        if (name.matches("(?s).*\\b(smithy|blacksmith|forge)\\b.*")) return "Blacksmith";
        if (name.matches("(?s).*\\b(shop|store|market|bazaar)\\b.*")) return "Merchant";
        if (name.matches("(?s).*\\b(guardhouse|checkpoint)\\b.*")) return "Gate guard";
        if (meta != null && Boolean.TRUE.equals(meta.getBoolean("populated"))) return "Local resident";
        return null;
    }
    public static String residentId(String conversation,String area,String role) {
        return UUID.nameUUIDFromBytes((conversation+":resident:"+area+":"+role.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8)).toString();
    }
    public Uni<JsonObject> prepare(AgentContext context) {
        StageContext stage=context.getStage();
        if (stage == null || !stage.isPresent()) return Uni.createFrom().nullItem();
        String conversation=context.getConversationId(), area=stage.getArea().getLocalAreaId().toString();
        String scene=UUID.nameUUIDFromBytes((conversation+":local-scene:"+area).getBytes(StandardCharsets.UTF_8)).toString();
        String role=roleFor(stage);
        return AgentPipeline.requireCurrentRound(pool,context)
            .chain(() -> pool.withTransaction(db -> db.preparedQuery("SELECT conversationGUID FROM conversation WHERE conversationGUID = ? FOR UPDATE")
                .execute(Tuple.of(conversation))
                .call(() -> AgentPipeline.requireCurrentRound(pool,context))
                .chain(ignored -> db.preparedQuery("SELECT stateId FROM world_state WHERE conversationId = ? AND currentAreaId = ?")
                    .execute(Tuple.of(conversation,area)))
                .invoke(rows -> { if(rows.size()==0) throw new IllegalStateException("The party has left this area"); })
                .chain(ignored -> db.preparedQuery("UPDATE conversation_character cc JOIN `character` ch ON ch.characterUUID=cc.characterId SET cc.isActive=FALSE, cc.isIntroduced=FALSE WHERE cc.conversationId=? AND JSON_EXTRACT(ch.metadata,'$.residentOfAreaId') IS NOT NULL AND JSON_UNQUOTE(JSON_EXTRACT(ch.metadata,'$.residentOfAreaId')) <> ?")
                    .execute(Tuple.of(conversation,area)))
                .chain(ignored -> db.preparedQuery("UPDATE scene SET isActive=FALSE, endedAt=NOW() WHERE conversationId=? AND sceneId<>? AND isActive=TRUE").execute(Tuple.of(conversation,scene)))
                .chain(ignored -> db.preparedQuery("INSERT INTO scene (sceneId,conversationId,name,location,description,isActive) VALUES (?,?,?,?,?,TRUE) ON DUPLICATE KEY UPDATE isActive=TRUE, endedAt=NULL")
                    .execute(Tuple.of(scene,conversation,stage.getArea().getName(),stage.getNode().getName(),stage.getArea().getDescription())))
                .chain(ignored -> db.preparedQuery("UPDATE conversation SET activeSceneId=? WHERE conversationGUID=?").execute(Tuple.of(scene,conversation)))
                .chain(ignored -> role == null ? Uni.createFrom().<JsonObject>nullItem() : ensureResident(db,conversation,stage,role))
                .call(resident -> db.preparedQuery("INSERT INTO scene_character (id,sceneId,characterId) SELECT UUID(),?,cc.characterId FROM conversation_character cc WHERE cc.conversationId=? AND cc.isActive=TRUE AND cc.isIntroduced=TRUE ON DUPLICATE KEY UPDATE leftAt=NULL")
                    .execute(Tuple.of(scene,conversation)).replaceWithVoid())))
            .call(resident -> resident == null ? Uni.createFrom().voidItem() : mapService.updateCharacterPosition(stage.getMapId(),resident.getString("id"),stage.getNode().getLocationId().toString(),stage.getNode().getX(),stage.getNode().getY(),stage.getNode().getZ()))
            .invoke(resident -> {
                if(resident != null) {
                    String id=resident.getString("id");
                    vertx.eventBus().publish("group:"+conversation+":character-joined",new JsonObject()
                        .put("id",membershipId(conversation,id)).put("conversationId",conversation).put("characterId",id));
                }
                vertx.eventBus().publish(RoleplayEventAddresses.SCENE_CHANGED+conversation,
                    new JsonObject().put("sceneId",scene).put("name",stage.getArea().getName()).put("location",stage.getNode().getName()).put("isActive",true));
            });
    }
    private static String membershipId(String conversation,String character) {
        return UUID.nameUUIDFromBytes((conversation+":"+character).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private Uni<JsonObject> ensureResident(SqlClient db,String conversation,StageContext stage,String role) {
        String area=stage.getArea().getLocalAreaId().toString(), id=residentId(conversation,area,role);
        String place=stage.getArea().getName()+" in "+stage.getNode().getName();
        String personality="Welcoming, observant, practical and attentive to guests. Speaks clearly and naturally.";
        String prompt="You are the "+role.toLowerCase(Locale.ROOT)+" at "+place+". You live and work here, not with the traveling party. "
            +"Greet arrivals naturally and answer what you plausibly know. Do not invent personal history, species, equipment or abilities not established in the story. "
            +"Never decide another character's actions or perform a transaction without the player's choice.";
        String greeting="Welcome to "+stage.getArea().getName()+". How can I help you?";
        JsonObject metadata=new JsonObject().put("residentOfAreaId",area).put("residentOfLocationId",stage.getNode().getLocationId().toString()).put("residentRole",role);
        return db.preparedQuery("INSERT IGNORE INTO `character` (characterUUID,ownerId,name,description,systemPrompt,greeting,personality,isPublic,metadata) SELECT ?,ownerId,?,?,?,?,?,FALSE,? FROM conversation WHERE conversationGUID=?")
            .execute(Tuple.from(Arrays.asList(id,role,"The "+role.toLowerCase(Locale.ROOT)+" at "+place,prompt,greeting,personality,metadata.encode(),conversation)))
            .chain(ignored -> db.preparedQuery("INSERT INTO conversation_character (id,conversationId,characterId,turnOrder,isActive,isIntroduced,introducedAt) SELECT ?,?,?,COALESCE(MAX(cc.turnOrder),-1)+1,TRUE,TRUE,NOW() FROM conversation_character cc WHERE cc.conversationId=? ON DUPLICATE KEY UPDATE isActive=TRUE, isIntroduced=TRUE, introducedAt=COALESCE(introducedAt,NOW())")
                .execute(Tuple.of(membershipId(conversation,id),conversation,id,conversation)))
            .chain(ignored -> db.preparedQuery("SELECT characterUUID,name,description,systemPrompt,greeting,personality,gender,pronouns,ageYears FROM `character` WHERE characterUUID=?").execute(Tuple.of(id)))
            .map(rows -> {
                Row row=rows.iterator().next();
                return new JsonObject().put("id",id).put("name",row.getString("name")).put("description",row.getString("description"))
                    .put("systemPrompt",row.getString("systemPrompt")).put("greeting",row.getString("greeting")).put("personality",row.getString("personality"))
                    .put("gender",row.getString("gender")).put("pronouns",row.getString("pronouns")).put("ageYears",row.getInteger("ageYears")).put("areaId",area);
            });
    }
    public Uni<List<String>> onstage(String conversation) {
        return pool.preparedQuery("SELECT characterId FROM conversation_character WHERE conversationId=? AND isActive=TRUE AND isIntroduced=TRUE ORDER BY turnOrder")
            .execute(Tuple.of(conversation)).map(rows -> { List<String> ids=new ArrayList<>(); rows.forEach(row -> ids.add(row.getString("characterId"))); return ids; });
    }
    public Uni<List<String>> partyCharacters(String conversation,List<String> requested) {
        if(requested == null || requested.isEmpty()) return Uni.createFrom().item(List.of());
        return pool.preparedQuery("SELECT ch.characterUUID FROM conversation_character cc JOIN `character` ch ON ch.characterUUID=cc.characterId WHERE cc.conversationId=? AND JSON_EXTRACT(ch.metadata,'$.residentOfAreaId') IS NOT NULL")
            .execute(Tuple.of(conversation)).map(rows -> {
                Set<String> residents=new HashSet<>(); rows.forEach(row -> residents.add(row.getString("characterUUID")));
                return requested.stream().filter(id -> !residents.contains(id)).distinct().toList();
            });
    }
    public Uni<Void> leave(String conversation) {
        return pool.preparedQuery("UPDATE scene_character sc JOIN scene s ON s.sceneId=sc.sceneId JOIN `character` ch ON ch.characterUUID=sc.characterId SET sc.leftAt=NOW() WHERE s.conversationId=? AND JSON_EXTRACT(ch.metadata,'$.residentOfAreaId') IS NOT NULL AND sc.leftAt IS NULL")
            .execute(Tuple.of(conversation)).chain(ignored -> pool.preparedQuery("UPDATE conversation_character cc JOIN `character` ch ON ch.characterUUID=cc.characterId SET cc.isActive=FALSE,cc.isIntroduced=FALSE WHERE cc.conversationId=? AND JSON_EXTRACT(ch.metadata,'$.residentOfAreaId') IS NOT NULL")
                .execute(Tuple.of(conversation))).replaceWithVoid();
    }
    public Uni<Boolean> canWelcome(String conversation,String area) {
        return pool.preparedQuery("SELECT c.conversationGUID FROM conversation c JOIN world_state w ON w.conversationId=c.conversationGUID WHERE c.conversationGUID=? AND w.currentAreaId=? AND c.groupState='WAITING_FOR_USER' AND c.currentSpeakerId IS NULL")
            .execute(Tuple.of(conversation,area)).map(rows -> rows.size()>0);
    }
    public Uni<Void> greetArrival(String conversation,String eventKey,JsonObject resident,String arrival) {
        return greetInternal(conversation,null,null,eventKey,resident,arrival,() -> canWelcome(conversation,resident.getString("areaId")));
    }
    public Uni<Void> greet(String conversation,String trigger,String boundary,JsonObject resident,String arrival,Supplier<Uni<Boolean>> current) {
        return greetInternal(conversation,trigger,boundary,null,resident,arrival,current);
    }
    private Uni<Void> greetInternal(String conversation,String trigger,String boundary,String eventKey,JsonObject resident,String arrival,Supplier<Uni<Boolean>> current) {
        if(resident==null) return Uni.createFrom().voidItem();
        return current.get().chain(valid -> {
            if(!valid) return Uni.createFrom().voidItem();
            var context=AgentContext.builder().conversationId(conversation).arrivalResident(resident).narratorOutput(arrival).build();
            Uni<String> text=greetingAgent == null ? Uni.createFrom().item(resident.getString("greeting"))
                : greetingAgent.run(context).map(result -> result.isSuccess() && result.getContent()!=null && !result.getContent().isBlank()
                    ? result.getContent() : resident.getString("greeting"));
            return text.chain(words -> current.get().chain(stillCurrent -> !stillCurrent ? Uni.createFrom().voidItem()
                : eventKey == null ? storyMessages.residentGreeting(conversation,trigger,boundary,resident,words)
                    : storyMessages.residentGreetingAtArrival(conversation,eventKey,resident,words)));
        });
    }
}
