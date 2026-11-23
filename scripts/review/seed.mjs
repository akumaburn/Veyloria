import { randomUUID } from "node:crypto";
export async function seed({ graphql, sql, demo = false }) {
  const theme = process.env.REVIEW_THEME || "dark";
  if (!["dark", "light"].includes(theme))
    throw Error("REVIEW_THEME must be dark or light");
  const password = "Review-Only!2026";
  const register = async (username) =>
    (
      await graphql(
        "mutation($u:String!,$e:String!,$p:String!){register(username:$u,email:$e,password:$p){accessToken refreshToken user{id username}}}",
        { u: username, e: `${username}@example.test`, p: password },
      )
    ).register;
  const owner = await register("review_explorer");
  const member = await register("review_companion");
  const outsider = await register("review_outsider");
  const id = Object.fromEntries(
    [
      "conversation",
      "mira",
      "torren",
      "world",
      "scene",
      "square",
      "inn",
      "gate",
      "battle",
      "potion",
      "sword",
    ].map((k) => [k, randomUUID()]),
  );
  // All SQL goes exclusively through the runner's disposable Compose MySQL container.
  const q = (value) => "'" + String(value).replaceAll("'", "''") + "'";
  const json = (value) => q(JSON.stringify(value));
  const uuid = () => q(randomUUID());
  await sql(`
INSERT INTO \`character\`(characterUUID,name,description,personality,ownerId,metadata) VALUES
(${q(id.mira)},'Mira Solstice','A young adult woman, scholar and adventurer.','Impulsive, fiercely loyal, and cynical about institutional power. She speaks with a mix of academic slang and street profanity.',${q(owner.user.id)},${json({ gender: "female", pronouns: "she/her", ageYears: 26 })}),
(${q(id.torren)},'Torren Vale','A seasoned ranger.','Patient, observant, and quietly protective.',${q(owner.user.id)},${json({ gender: "male", pronouns: "he/him", ageYears: 34 })});
INSERT INTO conversation(conversationGUID,conversationId,ownerId,title,description,isGroupChat,isRoleplay,rpMode,agentPipelineEnabled,turnMode,maxConsecutiveResponses,groupState,messageCount) VALUES
(${q(id.conversation)},${q(id.conversation)},${q(owner.user.id)},'Lanterns at the Crossing','A party reaches a coastal town in search of the lost observatory. A deterministic review fixture.',true,true,'guided',true,'ROUND_ROBIN',1,'WAITING_FOR_USER',3);
INSERT INTO conversation_participant(id,conversation_id,user_id,role) VALUES (${uuid()},${q(id.conversation)},${q(owner.user.id)},'owner'),(${uuid()},${q(id.conversation)},${q(member.user.id)},'member');
INSERT INTO conversation_character(id,conversationId,characterId,turnOrder) VALUES (${uuid()},${q(id.conversation)},${q(id.mira)},0),(${uuid()},${q(id.conversation)},${q(id.torren)},1);
INSERT INTO scene(sceneId,conversationId,name,location,description) VALUES (${q(id.scene)},${q(id.conversation)},'Lantern Quay','Market Square','Lanterns sway above the stalls as the tide turns.');
UPDATE conversation SET activeSceneId=${q(id.scene)} WHERE conversationGUID=${q(id.conversation)};
INSERT INTO scene_character(id,sceneId,characterId) VALUES (${uuid()},${q(id.scene)},${q(id.mira)}),(${uuid()},${q(id.scene)},${q(id.torren)});
INSERT INTO character_stat(statId,conversationId,characterId,statName,statValue,statMax,statCategory) VALUES (${uuid()},${q(id.conversation)},${q(id.mira)},'HP',12,20,'vitals'),(${uuid()},${q(id.conversation)},${q(id.torren)},'HP',18,20,'vitals');
INSERT INTO character_inventory(inventoryId,conversationId,characterId,itemName,itemDescription,quantity,category,properties) VALUES
(${q(id.potion)},${q(id.conversation)},${q(id.mira)},'Healing draught','Restores five health to an injured companion.',2,'consumable',${json({ consumable: true, effects: [{ statName: "HP", delta: 5 }] })}),
(${q(id.sword)},${q(id.conversation)},${q(id.mira)},'Observatory blade','A balanced short sword etched with constellations.',1,'weapon',${json({ slot: "main_hand" })});
INSERT INTO world_state(stateId,conversationId,currentScene,currentLocation,currentArea,timeOfDay,dayNumber,weather,mood) VALUES (${q(id.world)},${q(id.conversation)},'Lantern Quay','Lantern Quay','Market Square','Evening',1,'Clear skies','Expectant');
INSERT INTO message(messageUUID,messageId,conversationId,ownerId,characterId,role,displayName,content,messageType,sceneId) VALUES
(${uuid()},${uuid()},${q(id.conversation)},${q(owner.user.id)},NULL,'assistant','Narrator','Evening settled over Lantern Quay. Beyond the market stalls, the road wound toward the observatory; the Lantern Inn offered a moment of warmth before the climb.','NARRATION',${q(id.scene)}),
(${uuid()},${uuid()},${q(id.conversation)},${q(owner.user.id)},${q(id.mira)},'assistant','Mira Solstice','*I tuck a stray strand behind my ear.* "The old observatory is still standing. We should ask the innkeeper what happened there."','DIALOGUE',${q(id.scene)}),
(${uuid()},${uuid()},${q(id.conversation)},${q(member.user.id)},NULL,'user','Companion','I will keep watch by the market while you speak to the innkeeper.','DIALOGUE',${q(id.scene)});
UPDATE user SET displayName=CASE WHEN userUUID=${q(owner.user.id)} THEN 'Rowan' ELSE 'Companion' END,theme=${q(theme)},sounds=false WHERE userUUID IN (${q(owner.user.id)},${q(member.user.id)});
INSERT INTO settings(settingsId,userId,uiSettings) VALUES (${uuid()},${q(owner.user.id)},JSON_OBJECT('theme',${q(theme)},'fontSize','medium','soundEnabled',false)),(${uuid()},${q(member.user.id)},JSON_OBJECT('theme',${q(theme)},'fontSize','medium','soundEnabled',false)) ON DUPLICATE KEY UPDATE uiSettings=VALUES(uiSettings);
UPDATE user_settings SET theme=${q(theme)},soundEnabled=false WHERE fk_userUUID IN (${q(owner.user.id)},${q(member.user.id)});
`);
  const generated = await graphql(
    "mutation($input:GenerateMapInput!){generateProceduralMap(input:$input){mapId name locations{locationId name}}}",
    {
      input: {
        conversationId: id.conversation,
        name: "The Lantern Coast",
        mapScale: "region",
        seed: "review-lantern-coast",
        locationCount: 12,
        theme: "fantasy",
      },
    },
    owner.accessToken,
  );
  id.map = generated.generateProceduralMap.mapId;
  id.location = generated.generateProceduralMap.locations[0].locationId;
  await sql(`
UPDATE map_location SET name='Lantern Quay',locationType='town',isDiscovered=true WHERE locationId=${q(id.location)};
DELETE FROM local_area WHERE locationId=${q(id.location)};
INSERT INTO local_area(localAreaId,mapId,locationId,name,description,areaType,x,y,isGateway,isEntry,sortOrder) VALUES
(${q(id.square)},${q(id.map)},${q(id.location)},'Market Square','Lantern-lit stalls overlook the harbor.','market',50,50,false,true,0),
(${q(id.inn)},${q(id.map)},${q(id.location)},'Lantern Inn','A welcoming inn, with an innkeeper tending the counter.','inn',80,50,false,false,1),
(${q(id.gate)},${q(id.map)},${q(id.location)},'Harbor Gate','The road continues toward the observatory.','gate',50,80,true,false,2);
INSERT INTO local_area_connection(connectionId,mapId,locationId,fromAreaId,toAreaId,label) VALUES
(${uuid()},${q(id.map)},${q(id.location)},${q(id.square)},${q(id.inn)},'Follow the lanterns'),(${uuid()},${q(id.map)},${q(id.location)},${q(id.square)},${q(id.gate)},'Toward the harbor');
UPDATE world_state SET currentLocationId=${q(id.location)},currentLocation='Lantern Quay',currentAreaId=${q(id.square)},currentArea='Market Square' WHERE conversationId=${q(id.conversation)};
INSERT INTO local_area_visit(conversationId,locationId,localAreaId) VALUES (${q(id.conversation)},${q(id.location)},${q(id.square)});
`);
  if (demo) {
    const data = await graphql(
      "query($m:String!){mapRoads(mapId:$m){fromLocationId toLocationId} mapLocations(mapId:$m){locationId name x y z}}",
      { m: id.map },
      owner.accessToken,
    );
    const roads = data.mapRoads;
    const locations = new Map(data.mapLocations.map((l) => [l.locationId, l]));
    const distance = (r) => {
      const a = locations.get(r.fromLocationId),
        b = locations.get(r.toLocationId);
      return a && b ? Math.hypot(a.x - b.x, a.y - b.y) : 0;
    };
    const edge = [...roads].sort((a, b) => distance(b) - distance(a))[0];
    if (!edge)
      throw Error("Demo needs a connected road to demonstrate travel.");
    id.location = edge.fromLocationId;
    id.destination = edge.toLocationId;
    const origin = data.mapLocations.find((l) => l.locationId === id.location);
    const destination = data.mapLocations.find(
      (l) => l.locationId === id.destination,
    );
    if (!origin || !destination) throw Error("Missing road endpoints.");
    await sql(`
UPDATE map_location SET isDiscovered=TRUE WHERE mapId=${q(id.map)};
UPDATE map_location SET name='Quayside Ruins' WHERE mapId=${q(id.map)} AND name='Lantern Quay' AND locationId<>${q(id.location)};
UPDATE map_location SET name='Lantern Quay',locationType='town' WHERE locationId=${q(id.location)};
UPDATE map_location SET name='Old Observatory',description='An ancient observatory beyond the harbor road; a bridge sentinel guards the approach.' WHERE locationId=${q(id.destination)};
UPDATE local_area SET locationId=${q(id.location)} WHERE localAreaId IN (${q(id.square)},${q(id.inn)},${q(id.gate)});
UPDATE local_area_connection SET locationId=${q(id.location)} WHERE mapId=${q(id.map)} AND fromAreaId IN (${q(id.square)},${q(id.inn)},${q(id.gate)});
UPDATE world_state SET currentLocationId=${q(id.location)},currentLocation='Lantern Quay' WHERE conversationId=${q(id.conversation)};
DELETE FROM local_area_visit WHERE conversationId=${q(id.conversation)};
INSERT INTO local_area_visit(conversationId,locationId,localAreaId) VALUES (${q(id.conversation)},${q(id.location)},${q(id.square)}),(${q(id.conversation)},${q(id.location)},${q(id.inn)}),(${q(id.conversation)},${q(id.location)},${q(id.gate)});
UPDATE \`character\` SET gender='female',pronouns='she/her',ageYears=26 WHERE characterUUID=${q(id.mira)};
UPDATE message SET content='Evening settled over Lantern Quay. The party paused in the market before visiting the inn.' WHERE conversationId=${q(id.conversation)} AND messageType='NARRATION';
UPDATE message SET content='"The innkeeper might know a safe route to the observatory. Let us ask before the tide turns."' WHERE conversationId=${q(id.conversation)} AND characterId=${q(id.mira)};
DELETE FROM map_character_position WHERE mapId=${q(id.map)};
INSERT INTO map_character_position(id,mapId,userId,locationId,x,y,z) VALUES (${uuid()},${q(id.map)},${q(owner.user.id)},${q(id.location)},${origin.x},${origin.y},${origin.z});
INSERT INTO map_character_position(id,mapId,characterId,locationId,x,y,z) VALUES (${uuid()},${q(id.map)},${q(id.mira)},${q(id.location)},${origin.x},${origin.y},${origin.z}),(${uuid()},${q(id.map)},${q(id.torren)},${q(id.location)},${origin.x},${origin.y},${origin.z});
`);
    const extra = [
      ["Apothecary", 20, 20],
      ["Archive", 50, 20],
      ["Watchtower", 80, 20],
      ["Guild Hall", 20, 50],
      ["Docks", 20, 80],
      ["Garden", 80, 80],
    ].map(([name, x, y]) => ({ id: randomUUID(), name, x, y }));
    const byName = Object.fromEntries(
      extra.map((room) => [room.name, room.id]),
    );
    const links = [
      [id.square, byName["Archive"]],
      [id.square, byName["Guild Hall"]],
      [byName["Archive"], byName["Apothecary"]],
      [byName["Archive"], byName["Watchtower"]],
      [byName["Guild Hall"], byName["Apothecary"]],
      [byName["Guild Hall"], byName["Docks"]],
      [id.gate, byName["Docks"]],
      [id.gate, byName["Garden"]],
      [id.inn, byName["Garden"]],
      [id.inn, byName["Watchtower"]],
    ];
    await sql(`INSERT INTO local_area(localAreaId,mapId,locationId,name,description,areaType,x,y,sortOrder) VALUES ${extra.map((room, index) => `(${q(room.id)},${q(id.map)},${q(id.location)},${q(room.name)},${q("An established, previously explored part of Lantern Quay.")},'district',${room.x},${room.y},${index + 3})`).join(",")};
INSERT INTO local_area_connection(connectionId,mapId,locationId,fromAreaId,toAreaId,label) VALUES ${links.map(([from, to]) => `(${uuid()},${q(id.map)},${q(id.location)},${q(from)},${q(to)},'Town passage')`).join(",")};
INSERT INTO local_area_visit(conversationId,locationId,localAreaId) VALUES ${extra.map((room) => `(${q(id.conversation)},${q(id.location)},${q(room.id)})`).join(",")};`);
    await sql(`
INSERT INTO story_bookmark(bookmarkId,conversationId,userId,messageId,label,description) SELECT ${uuid()},${q(id.conversation)},${q(owner.user.id)},messageUUID,'The road to the inn','An earlier moment in this fictional adventure.' FROM message WHERE conversationId=${q(id.conversation)} ORDER BY seq LIMIT 1;
INSERT INTO knowledge_entry(entryId,conversationId,summary,visibility,importance,tags) VALUES (${uuid()},${q(id.conversation)},'The observatory lens reveals a hidden star route when the tide is low. The innkeeper keeps the old harbor charts.','[]',8,'observatory,harbor,history');
INSERT INTO user_progression(userId,totalXP,level,title,storiesCompleted,totalMessages,totalDiceRolls) VALUES (${q(owner.user.id)},1400,3,'Wandering Storyteller',2,64,12) ON DUPLICATE KEY UPDATE totalXP=1400,level=3,title='Wandering Storyteller',storiesCompleted=2;
INSERT INTO user_achievement(id,userId,achievementId,progress,isComplete,completedAt,conversationId) SELECT UUID(),${q(owner.user.id)},achievementId,100,TRUE,NOW(),${q(id.conversation)} FROM achievement LIMIT 2;
`);
    return {
      id,
      owner,
      member,
      outsider,
      password,
      origin: { ...origin, name: "Lantern Quay" },
      destination: { ...destination, name: "Old Observatory" },
    };
  }
  return { id, owner, member, outsider, password };
}
