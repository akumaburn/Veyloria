package com.eonmux.chatllm.services;

import com.eonmux.chatllm.config.RoleplayEventAddresses;
import com.eonmux.chatllm.models.CharacterInventory;
import com.eonmux.chatllm.utils.ServiceHelper;
import com.eonmux.chatllm.utils.Time4JHelper;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;
import java.util.function.BiFunction;

/** All writers lock the conversation first, then the item, in one transaction. */
@ApplicationScoped
public class InventoryService {
    @Inject Vertx vertx;
    @Inject Pool pool;

    public Uni<List<CharacterInventory>> getInventory(String conversationId, String characterId) {
        return list(pool, conversationId, "characterId", characterId);
    }

    public Uni<List<CharacterInventory>> getUserInventory(String conversationId, String userId) {
        return list(pool, conversationId, "userId", userId);
    }

    private Uni<List<CharacterInventory>> list(SqlClient db, String conversationId, String column, String owner) {
        return db.preparedQuery("SELECT * FROM character_inventory WHERE conversationId = ? AND " + column + " = ? ORDER BY category, itemName")
            .execute(Tuple.of(conversationId, owner)).map(rows -> {
                List<CharacterInventory> result = new ArrayList<>();
                rows.forEach(row -> result.add(mapRowToInventory(row)));
                return result;
            });
    }

    public Uni<CharacterInventory> getItem(String id) { return getItem(pool, id, false); }

    private Uni<CharacterInventory> getItem(SqlClient db, String id, boolean lock) {
        return db.preparedQuery("SELECT * FROM character_inventory WHERE inventoryId = ?" + (lock ? " FOR UPDATE" : ""))
            .execute(Tuple.of(id)).map(rows -> rows.size() == 0 ? null : mapRowToInventory(rows.iterator().next()));
    }

    public static void validateOwner(String characterId, String userId) {
        if ((characterId == null) == (userId == null) || (characterId != null && characterId.isBlank()) || (userId != null && userId.isBlank()))
            throw new IllegalArgumentException("Exactly one character or player must be selected");
    }

    private Uni<Boolean> manager(SqlClient db, String conversationId, String caller) {
        return db.preparedQuery("""
            SELECT c.ownerId, cp.role FROM conversation c
            LEFT JOIN conversation_participant cp ON cp.conversation_id = c.conversationGUID
                AND cp.user_id = ? AND cp.is_active = TRUE
            WHERE c.conversationGUID = ?
            """).execute(Tuple.of(caller, conversationId)).map(rows -> {
                if (rows.size() == 0) throw new SecurityException("Conversation not found");
                Row row = rows.iterator().next();
                boolean owner = caller.equals(row.getString("ownerId"));
                String role = row.getString("role");
                if (!owner && role == null) throw new SecurityException("Access denied");
                return owner || "admin".equalsIgnoreCase(role) || "owner".equalsIgnoreCase(role);
            });
    }

    private Uni<Boolean> canModify(SqlClient db, String conversationId, String characterId, String userId, String caller) {
        return manager(db, conversationId, caller).chain(isManager -> {
            if (isManager || caller.equals(userId)) return Uni.createFrom().item(true);
            if (characterId == null) return Uni.createFrom().item(false);
            return db.preparedQuery("SELECT characterUUID FROM `character` WHERE characterUUID = ? AND ownerId = ?")
                .execute(Tuple.of(characterId, caller)).map(rows -> rows.size() > 0);
        });
    }

    public Uni<com.eonmux.chatllm.models.InventoryAccess> access(String conversationId, String characterId, String userId, String caller) {
        validateOwner(characterId, userId);
        return manager(pool, conversationId, caller).chain(isManager ->
            canModify(pool, conversationId, characterId, userId, caller).chain(modify ->
                pool.preparedQuery("""
                    SELECT c.characterId AS recipientId, ch.name AS name, 'character' AS kind, ch.ownerId AS ownerId
                    FROM conversation_character c JOIN `character` ch ON ch.characterUUID = c.characterId
                    WHERE c.conversationId = ? AND c.isActive = TRUE
                    UNION SELECT c.characterId, ch.name, 'character', ch.ownerId FROM conversation c
                    JOIN `character` ch ON ch.characterUUID = c.characterId WHERE c.conversationGUID = ?
                    UNION SELECT u.userUUID, u.username, 'user', NULL FROM user u JOIN conversation c ON c.ownerId = u.userUUID
                    WHERE c.conversationGUID = ?
                    UNION SELECT u.userUUID, u.username, 'user', NULL FROM user u
                    JOIN conversation_participant cp ON cp.user_id = u.userUUID WHERE cp.conversation_id = ? AND cp.is_active = TRUE
                    """).execute(Tuple.of(conversationId, conversationId, conversationId, conversationId)).map(rows -> {
                        var recipients = new ArrayList<com.eonmux.chatllm.models.InventoryAccess.Recipient>();
                        rows.forEach(row -> {
                            String id = row.getString("recipientId");
                            String kind = row.getString("kind");
                            if (!("user".equals(kind) ? id.equals(userId) : id.equals(characterId)))
                                recipients.add(new com.eonmux.chatllm.models.InventoryAccess.Recipient(id, row.getString("name"), kind, "character".equals(kind) && (isManager || caller.equals(row.getString("ownerId")))));
                        });
                        return new com.eonmux.chatllm.models.InventoryAccess(modify, isManager, recipients);
                    })));
    }

    private Uni<Void> lockConversation(SqlClient db, String id) {
        return db.preparedQuery("SELECT conversationGUID FROM conversation WHERE conversationGUID = ? FOR UPDATE")
            .execute(Tuple.of(id)).invoke(rows -> {
                if (rows.size() == 0) throw new IllegalArgumentException("Conversation not found");
            }).replaceWithVoid();
    }

    private Uni<Void> recipient(SqlClient db, String conversationId, String characterId, String userId) {
        validateOwner(characterId, userId);
        String sql = characterId != null ? """
            SELECT c.conversationGUID FROM conversation c WHERE c.conversationGUID = ? AND
            (c.characterId = ? OR EXISTS (SELECT 1 FROM conversation_character cc
             WHERE cc.conversationId = c.conversationGUID AND cc.characterId = ? AND cc.isActive = TRUE))
            """ : """
            SELECT c.conversationGUID FROM conversation c WHERE c.conversationGUID = ? AND
            (c.ownerId = ? OR EXISTS (SELECT 1 FROM conversation_participant cp
             WHERE cp.conversation_id = c.conversationGUID AND cp.user_id = ? AND cp.is_active = TRUE))
            """;
        String id = characterId != null ? characterId : userId;
        return db.preparedQuery(sql).execute(Tuple.of(conversationId, id, id)).invoke(rows -> {
            if (rows.size() == 0) throw new IllegalArgumentException("Recipient is not active in this conversation");
        }).replaceWithVoid();
    }

    public Uni<CharacterInventory> addUserItem(String c, String u, String name, String description, Integer qty, String category, JsonObject props) {
        return add(c, null, u, name, description, qty, category, props, false, null);
    }
    public Uni<CharacterInventory> addItem(String c, String ch, String name, String description, Integer qty, String category, JsonObject props) {
        return add(c, ch, null, name, description, qty, category, props, false, null);
    }
    public Uni<CharacterInventory> add(String c, String ch, String u, String name, String description, Integer quantity, String category, JsonObject props, boolean equipped, String caller) {
        return Uni.createFrom().deferred(() -> addValidated(c, ch, u, name, description, quantity, category, props, equipped, caller));
    }
    private Uni<CharacterInventory> addValidated(String c, String ch, String u, String name, String description, Integer quantity, String category, JsonObject props, boolean equipped, String caller) {
        return pool.withTransaction(db -> addInTransaction(db, c, ch, u, name, description, quantity, category, props, equipped, caller)).invoke(this::publish);
    }

    Uni<CharacterInventory> addInTransaction(SqlClient db, String c, String ch, String u, String name, String description,
            Integer quantity, String category, JsonObject props, boolean equipped, String caller) {
        validateOwner(ch, u);
        int qty = quantity == null ? 1 : quantity;
        if (name == null || name.isBlank() || name.trim().length() > 255 || qty < 1 || (category != null && category.length() > 100))
            throw new IllegalArgumentException("Provide an item name and positive quantity within the supported limits");
        final String itemName = name.trim();
        return lockConversation(db, c)
            .chain(() -> caller == null ? Uni.createFrom().item(true) : manager(db, c, caller))
            .invoke(allowed -> { if (!allowed) throw new SecurityException("Only the host or a conversation administrator may grant items"); })
            .chain(() -> recipient(db, c, ch, u))
            .chain(() -> list(db, c, ch != null ? "characterId" : "userId", ch != null ? ch : u))
            .chain(items -> {
                // Same name alone is insufficient: enchanted and mundane swords must never merge.
                var match = items.stream().filter(i -> i.getItemName().equals(itemName)
                    && Objects.equals(i.getItemDescription(), description) && Objects.equals(i.getCategory(), category)
                    && Objects.equals(properties(i), props == null ? new JsonObject() : props)
                    && !Boolean.TRUE.equals(i.getIsEquipped()) && !equipped
                    && (props == null || props.getBoolean("stackable", true))).findFirst();
                if (match.isPresent()) {
                    var item = match.get();
                    Math.addExact(item.getQuantity(), qty);
                    return db.preparedQuery("UPDATE character_inventory SET quantity = quantity + ? WHERE inventoryId = ?")
                        .execute(Tuple.of(qty, item.getInventoryId().toString())).chain(() -> getItem(db, item.getInventoryId().toString(), false));
                }
                var item = CharacterInventory.builder().inventoryId(UUID.randomUUID()).conversationId(UUID.fromString(c))
                    .characterId(ch == null ? null : UUID.fromString(ch)).userId(u == null ? null : UUID.fromString(u))
                    .itemName(itemName).itemDescription(description).quantity(qty).category(category)
                    .properties(props == null ? null : props.encode()).isEquipped(equipped).build();
                if (equipped) requireEquippable(item);
                return db.preparedQuery("""
                    INSERT INTO character_inventory (inventoryId, conversationId, characterId, userId, itemName, itemDescription, quantity, category, properties, isEquipped)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).execute(Tuple.tuple().addString(item.getInventoryId().toString()).addString(c).addString(ch).addString(u)
                        .addString(itemName).addString(description).addInteger(qty).addString(category)
                        .addString(item.getProperties()).addBoolean(equipped)).chain(() -> getItem(db, item.getInventoryId().toString(), false));
            }).call(item -> equipped ? clearSlot(db, item) : Uni.createFrom().voidItem()).call(item -> record(db, item, caller, "add"));
    }

    /** Re-read the item after acquiring the conversation lock: ownership may have changed. */
    private Uni<CharacterInventory> change(String id, String caller, boolean manageOnly, String action, BiFunction<SqlClient, CharacterInventory, Uni<CharacterInventory>> operation) {
        return change(id, caller, manageOnly, action, null, operation);
    }
    private Uni<CharacterInventory> change(String id, String caller, boolean manageOnly, String action, String targetCharacterId, BiFunction<SqlClient, CharacterInventory, Uni<CharacterInventory>> operation) {
        return getItem(id).chain(snapshot -> {
            if (snapshot == null) return Uni.createFrom().failure(new IllegalArgumentException("Item not found"));
            return pool.withTransaction(db -> lockConversation(db, snapshot.getConversationId().toString())
                .chain(() -> getItem(db, id, true)).chain(item -> {
                    if (item == null || item.getQuantity() < 1) return Uni.createFrom().failure(new IllegalArgumentException("Item no longer available"));
                    Uni<Boolean> permission = caller == null ? Uni.createFrom().item(true) : manageOnly
                        ? manager(db, item.getConversationId().toString(), caller)
                        : canModify(db, item.getConversationId().toString(), string(item.getCharacterId()), string(item.getUserId()), caller);
                    return permission.invoke(allowed -> { if (!allowed) throw new SecurityException("You cannot modify this inventory"); })
                        .chain(() -> operation.apply(db, item)).call(updated -> record(db, updated, caller, action, item, targetCharacterId));
                }));
        }).invoke(this::publish);
    }

    private static String string(UUID id) { return id == null ? null : id.toString(); }
    public static JsonObject properties(CharacterInventory item) {
        return item.getProperties() == null ? new JsonObject() : new JsonObject(item.getProperties());
    }
    public static boolean consumable(CharacterInventory item) {
        return properties(item).getBoolean("consumable", "consumable".equalsIgnoreCase(item.getCategory()));
    }
    public static void requireEquippable(CharacterInventory item) {
        boolean allowed = properties(item).getBoolean("equippable", Set.of("weapon", "armor", "equipment", "accessory", "shield").contains(
            item.getCategory() == null ? "" : item.getCategory().toLowerCase(Locale.ROOT)));
        if (!allowed || consumable(item)) throw new IllegalArgumentException("This item cannot be equipped");
    }

    private Uni<Void> clearSlot(SqlClient db, CharacterInventory item) {
        String slot = properties(item).getString("slot");
        if (slot == null || slot.isBlank()) return Uni.createFrom().voidItem();
        return db.preparedQuery("""
            UPDATE character_inventory SET isEquipped = FALSE WHERE conversationId = ?
            AND characterId <=> ? AND userId <=> ? AND inventoryId <> ?
            AND JSON_UNQUOTE(JSON_EXTRACT(properties, '$.slot')) = ? AND isEquipped = TRUE
            """).execute(Tuple.of(item.getConversationId().toString(), string(item.getCharacterId()), string(item.getUserId()), item.getInventoryId().toString(), slot)).replaceWithVoid();
    }

    public Uni<CharacterInventory> equipItem(String id, boolean equipped) { return equipItem(id, equipped, null); }
    public Uni<CharacterInventory> equipItem(String id, boolean equipped, String caller) {
        return change(id, caller, false, equipped ? "equip" : "unequip", (db, item) -> {
            if (equipped) requireEquippable(item);
            Uni<Void> clearSlot = equipped ? clearSlot(db, item) : Uni.createFrom().voidItem();
            return clearSlot.chain(() -> db.preparedQuery("UPDATE character_inventory SET isEquipped = ? WHERE inventoryId = ?")
                .execute(Tuple.of(equipped, id)).chain(() -> getItem(db, id, false)));
        });
    }

    public Uni<Boolean> removeItem(String id) { return removeItem(id, null); }
    public Uni<Boolean> removeItem(String id, String caller) {
        return change(id, caller, true, "remove", (db, item) -> db.preparedQuery("DELETE FROM character_inventory WHERE inventoryId = ?")
            .execute(Tuple.of(id)).map(rows -> { item.setQuantity(0); item.setIsEquipped(false); return item; })).replaceWith(true);
    }

    public Uni<CharacterInventory> useItem(String id) { return useItem(id, null); }
    public Uni<CharacterInventory> useItem(String id, String caller) { return useItem(id, caller, null); }
    public Uni<CharacterInventory> useItem(String id, String caller, String targetCharacterId) {
        return change(id, caller, false, "use", targetCharacterId, (db, item) -> {
            JsonObject props = properties(item);
            boolean consume = consumable(item);
            if (!consume && !props.getBoolean("usable", false))
                throw new IllegalArgumentException("This item is not usable; equip equipment instead");
            if (Boolean.TRUE.equals(item.getIsEquipped()) && consume) throw new IllegalArgumentException("Unequip before consuming");
            String target = targetCharacterId == null ? string(item.getCharacterId()) : targetCharacterId;
            Uni<Void> targetCheck = target == null ? Uni.createFrom().voidItem()
                : recipient(db, item.getConversationId().toString(), target, null)
                    .chain(() -> caller == null ? Uni.createFrom().item(true)
                        : canModify(db, item.getConversationId().toString(), target, null, caller))
                    .invoke(allowed -> { if (!allowed) throw new SecurityException("You cannot apply item effects to that character"); }).replaceWithVoid();
            return targetCheck.chain(() -> applyEffects(db, item, target)).chain(() -> {
                if (!consume) return Uni.createFrom().item(item);
                int left = item.getQuantity() - 1;
                String sql = left == 0 ? "DELETE FROM character_inventory WHERE inventoryId = ?"
                    : "UPDATE character_inventory SET quantity = quantity - 1 WHERE inventoryId = ?";
                return db.preparedQuery(sql).execute(Tuple.of(id)).map(rows -> {
                    item.setQuantity(left); if (left == 0) item.setIsEquipped(false); return item;
                });
            });
        });
    }

    /** Explicit, deterministic self effects only; descriptive prose never invents stat changes. */
    private Uni<Void> applyEffects(SqlClient db, CharacterInventory item, String targetCharacterId) {
        var effects = properties(item).getJsonArray("effects", new io.vertx.core.json.JsonArray());
        if (!effects.isEmpty() && targetCharacterId == null)
            throw new IllegalArgumentException("Choose a character to receive the stat effect");
        Uni<Void> result = Uni.createFrom().voidItem();
        for (int index = 0; index < effects.size(); index++) {
            JsonObject effect = effects.getJsonObject(index);
            String stat = effect.getString("statName");
            Double delta = effect.getDouble("delta");
            if (stat == null || stat.isBlank() || delta == null || !Double.isFinite(delta))
                throw new IllegalArgumentException("Invalid item stat effect");
            result = result.chain(() -> db.preparedQuery("SELECT statId FROM character_stat WHERE conversationId = ? AND characterId = ? AND statName = ? AND statType = 'numeric' FOR UPDATE")
                .execute(Tuple.of(item.getConversationId().toString(), targetCharacterId, stat))
                .invoke(rows -> { if (rows.size() == 0) throw new IllegalArgumentException("Missing numeric stat: " + stat); })
                .chain(() -> db.preparedQuery("""
                    UPDATE character_stat SET statValue = GREATEST(0, LEAST(COALESCE(statMax, 1.7976931348623157e308), statValue + ?))
                    WHERE conversationId = ? AND characterId = ? AND statName = ?
                    """).execute(Tuple.of(delta, item.getConversationId().toString(), targetCharacterId, stat))).replaceWithVoid());
        }
        return result;
    }

    public Uni<CharacterInventory> transferItem(String id, String ch, String u) { return transferItem(id, ch, u, null, null); }
    public Uni<CharacterInventory> transferItem(String id, String ch, String u, String caller) { return transferItem(id, ch, u, null, caller); }
    public Uni<CharacterInventory> transferItem(String id, String ch, String u, Integer quantity, String caller) {
        validateOwner(ch, u);
        return change(id, caller, false, "transfer", (db, item) -> {
            if (Objects.equals(ch, string(item.getCharacterId())) && Objects.equals(u, string(item.getUserId())))
                throw new IllegalArgumentException("Item already belongs to that recipient");
            if (!properties(item).getBoolean("transferable", true)) throw new IllegalArgumentException("This item cannot be transferred");
            int amount = quantity == null ? item.getQuantity() : quantity;
            if (amount < 1 || amount > item.getQuantity()) throw new IllegalArgumentException("Transfer quantity exceeds the available stack");
            return recipient(db, item.getConversationId().toString(), ch, u).chain(() -> {
                if (amount == item.getQuantity()) return db.preparedQuery("UPDATE character_inventory SET characterId = ?, userId = ?, isEquipped = FALSE WHERE inventoryId = ?")
                    .execute(Tuple.of(ch, u, id)).chain(() -> getItem(db, id, false));
                String newId = UUID.randomUUID().toString();
                return db.preparedQuery("UPDATE character_inventory SET quantity = quantity - ? WHERE inventoryId = ?")
                    .execute(Tuple.of(amount, id)).chain(() -> db.preparedQuery("""
                        INSERT INTO character_inventory (inventoryId, conversationId, characterId, userId, itemName, itemDescription, quantity, category, properties, isEquipped)
                        SELECT ?, conversationId, ?, ?, itemName, itemDescription, ?, category, properties, FALSE
                        FROM character_inventory WHERE inventoryId = ?
                        """).execute(Tuple.of(newId, ch, u, amount, id))).chain(() -> getItem(db, newId, false));
            });
        });
    }

    public Uni<Boolean> removeUserItemByName(String c, String u, String name) { return removeByName(c, null, u, name, 1); }
    public Uni<Boolean> removeItemByName(String c, String ch, String name) { return removeByName(c, ch, null, name, 1); }
    public Uni<Boolean> removeByName(String c, String ch, String u, String name, int quantity) {
        validateOwner(ch, u);
        if (quantity < 1) throw new IllegalArgumentException("Quantity must be positive");
        return pool.withTransaction(db -> lockConversation(db, c).chain(() -> list(db, c, ch != null ? "characterId" : "userId", ch != null ? ch : u))
            .chain(items -> {
                var matches = items.stream().filter(i -> i.getItemName().equalsIgnoreCase(name)).toList();
                if (matches.isEmpty()) return Uni.createFrom().item(List.<CharacterInventory>of());
                if (matches.size() != 1) throw new IllegalArgumentException("Ambiguous item name; select a specific inventory item");
                var item = matches.getFirst();
                if (item.getQuantity() < quantity) throw new IllegalArgumentException("Insufficient item quantity");
                int left = item.getQuantity() - quantity;
                return db.preparedQuery(left == 0 ? "DELETE FROM character_inventory WHERE inventoryId = ?" : "UPDATE character_inventory SET quantity = ? WHERE inventoryId = ?")
                    .execute(left == 0 ? Tuple.of(item.getInventoryId().toString()) : Tuple.of(left, item.getInventoryId().toString()))
                    .map(rows -> { item.setQuantity(left); if (left == 0) item.setIsEquipped(false); return List.of(item); });
            }).call(items -> items.isEmpty() ? Uni.createFrom().voidItem() : record(db, items.getFirst(), null, "remove"))).invoke(items -> items.forEach(this::publish)).map(items -> !items.isEmpty());
    }

    /** Caller holds the conversation lock; round, inventory, effects and history commit together. */
    Uni<Void> applyBattleChanges(SqlClient db, String conversationId, io.vertx.core.json.JsonArray changes) {
        Uni<Void> sequence = Uni.createFrom().voidItem();
        for (int index = 0; index < changes.size(); index++) {
            JsonObject change = changes.getJsonObject(index);
            sequence = sequence.chain(() -> Uni.createFrom().deferred(() -> {
                String ch = change.getString("characterId"), user = change.getString("userId");
                validateOwner(ch, user);
                String action = change.getString("action"), name = change.getString("itemName");
                if ("add".equals(action)) return addInTransaction(db, conversationId, ch, user, name,
                    change.getString("itemDescription"), change.getInteger("quantity", 1), change.getString("category"),
                    change.getJsonObject("properties"), false, null).replaceWithVoid();
                if (!"remove".equals(action) && !"use".equals(action))
                    throw new IllegalArgumentException("Unsupported battle inventory action");
                return recipient(db, conversationId, ch, user)
                    .chain(() -> list(db, conversationId, ch != null ? "characterId" : "userId", ch != null ? ch : user))
                    .chain(items -> {
                        String id = change.getString("inventoryId");
                        var matches = items.stream().filter(i -> id != null ? id.equals(i.getInventoryId().toString())
                            : i.getItemName().equalsIgnoreCase(name)).toList();
                        if (matches.size() != 1) throw new IllegalArgumentException("Battle item is missing or ambiguous");
                        var item = matches.getFirst();
                        int quantity = "use".equals(action) ? 1 : change.getInteger("quantity", 1);
                        if (quantity < 1 || quantity > item.getQuantity()) throw new IllegalArgumentException("Insufficient battle item quantity");
                        if ("use".equals(action) && !consumable(item) && !properties(item).getBoolean("usable", false))
                            throw new IllegalArgumentException("Battle item cannot be used");
                        String target = change.getString("targetCharacterId", ch);
                        Uni<Void> effects = "use".equals(action)
                            ? (target == null ? Uni.createFrom().voidItem() : recipient(db, conversationId, target, null))
                                .chain(() -> applyEffects(db, item, target)) : Uni.createFrom().voidItem();
                        int left = "use".equals(action) && !consumable(item) ? item.getQuantity() : item.getQuantity() - quantity;
                        return effects.chain(() -> db.preparedQuery(left == 0
                            ? "DELETE FROM character_inventory WHERE inventoryId = ?"
                            : "UPDATE character_inventory SET quantity = ? WHERE inventoryId = ?")
                            .execute(left == 0 ? Tuple.of(item.getInventoryId().toString()) : Tuple.of(left, item.getInventoryId().toString())))
                            .invoke(rows -> { item.setQuantity(left); if (left == 0) item.setIsEquipped(false); })
                            .chain(() -> record(db, item, null, action, item, target));
                    });
            }));
        }
        return sequence;
    }

    private Uni<Void> record(SqlClient db, CharacterInventory item, String caller, String action) {
        return record(db, item, caller, action, item, null);
    }
    private Uni<Void> record(SqlClient db, CharacterInventory item, String caller, String action, CharacterInventory source, String targetCharacterId) {
        JsonObject details = JsonObject.mapFrom(item).put("sourceInventoryId", source.getInventoryId().toString())
            .put("sourceCharacterId", string(source.getCharacterId())).put("sourceUserId", string(source.getUserId()))
            .put("effectTargetCharacterId", targetCharacterId != null ? targetCharacterId : string(source.getCharacterId()));
        return db.preparedQuery("INSERT INTO inventory_activity (actionId, conversationId, inventoryId, actorId, action, details) VALUES (?, ?, ?, ?, ?, ?)")
            .execute(Tuple.of(UUID.randomUUID().toString(), item.getConversationId().toString(), item.getInventoryId().toString(),
                caller, action, details.encode())).replaceWithVoid();
    }

    public Uni<io.vertx.core.json.JsonArray> recentActions(String conversationId) {
        return pool.preparedQuery("SELECT actionId, action, details, createdAt FROM inventory_activity WHERE conversationId = ? ORDER BY createdAt DESC, actionId DESC LIMIT 20")
            .execute(Tuple.of(conversationId)).map(rows -> {
                var actions = new io.vertx.core.json.JsonArray();
                rows.forEach(row -> actions.add(new JsonObject().put("actionId", row.getString("actionId"))
                    .put("action", row.getString("action")).put("details", new JsonObject(ServiceHelper.safeGetJsonText(row, "details")))
                    .put("createdAt", Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")))));
                return actions;
            });
    }

    private void publish(CharacterInventory item) {
        // Inventory events are invalidations, not private item payloads. Readers re-query through authorization.
        if (vertx != null) vertx.eventBus().publish(RoleplayEventAddresses.INVENTORY_CHANGED + item.getConversationId(),
            new JsonObject().put("conversationId", item.getConversationId().toString()).put("inventoryId", item.getInventoryId().toString()));
    }

    private CharacterInventory mapRowToInventory(Row row) {
        CharacterInventory i = new CharacterInventory();
        i.setInventoryId(UUID.fromString(row.getString("inventoryId")));
        i.setConversationId(UUID.fromString(row.getString("conversationId")));
        String charId = row.getString("characterId");
        if (charId != null) i.setCharacterId(UUID.fromString(charId));
        String uid = row.getString("userId");
        if (uid != null) i.setUserId(UUID.fromString(uid));
        i.setItemName(row.getString("itemName"));
        i.setItemDescription(row.getString("itemDescription"));
        i.setQuantity(row.getInteger("quantity"));
        i.setCategory(row.getString("category"));
        i.setProperties(ServiceHelper.safeGetJsonText(row, "properties"));
        i.setIsEquipped(row.getBoolean("isEquipped"));
        i.setCreatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("createdAt")));
        i.setUpdatedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("updatedAt")));
        return i;
    }
}
