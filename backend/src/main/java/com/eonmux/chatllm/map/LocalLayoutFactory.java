package com.eonmux.chatllm.map;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds a sensible interior layout for one world-map node.
 *
 * <p>The layout is a small connected graph of rooms, streets, or clearings.
 * Each area carries a short description and a position for the local map
 * view. Gateways are the only areas from which the party can leave for
 * another world-map node.</p>
 *
 * <p>Generation is deterministic: the same node id and type always produce
 * the same layout. A node's name and description can select a more specific
 * template, so a city node renamed to an academy gets academy rooms rather
 * than market stalls.</p>
 */
public final class LocalLayoutFactory {

    /** One planned area before it receives a database id. */
    public record AreaDraft(String key, String name, String areaType, String description,
                            float x, float y, boolean gateway, boolean entry) {
    }

    /** One planned passable connection. */
    public record ConnectionDraft(String fromKey, String toKey, String label) {
    }

    /** A complete layout plan. */
    public record Layout(List<AreaDraft> areas, List<ConnectionDraft> connections) {
    }

    private LocalLayoutFactory() {
    }

    public static Layout generate(String locationId, String locationName,
                                  String locationType, String description) {
        String template = template(locationName, locationType, description);
        int variant = locationId == null ? 0 : Math.floorMod(locationId.hashCode(), 3);
        return switch (template) {
            case "academy" -> academy(variant);
            case "inn" -> inn(variant);
            case "temple" -> temple(variant);
            case "castle" -> castle(variant);
            case "dungeon" -> dungeon(variant);
            case "cave" -> cave(variant);
            case "mine" -> mine(variant);
            case "port" -> port(variant);
            case "village" -> village(variant);
            case "town" -> town(variant);
            case "forest" -> forest(variant);
            case "mountain" -> mountain(variant);
            case "camp" -> camp(variant);
            case "ruins" -> ruins(variant);
            case "tower" -> tower(variant);
            case "water" -> water(variant);
            case "swamp" -> swamp(variant);
            case "oasis" -> oasis(variant);
            default -> generic(variant);
        };
    }

    /**
     * The node's name selects a more specific template, so a place renamed to
     * an academy gets lecture halls. When the name says nothing structural,
     * the recorded type decides. The description is only a hint for a node
     * with no usable type: a dungeon's "thin mountain air" sentence must not
     * turn its interior into an outdoor trail.
     */
    private static String template(String locationName, String type, String description) {
        String specific = templateFromText(locationName == null
                ? "" : locationName.toLowerCase(Locale.ROOT));
        // "Weawick's Crossing" is a generated place name, not a request for a
        // river layout. A generic settlement or water word loses to the type.
        if (specific != null && !GENERIC_NAME_TEMPLATES.contains(specific)) return specific;
        String value = type == null ? "" : type.toLowerCase(Locale.ROOT);
        String byType = switch (value) {
            case "castle", "keep", "fortress" -> "castle";
            case "dungeon" -> "dungeon";
            case "cave" -> "cave";
            case "mine" -> "mine";
            case "city", "town" -> "town";
            case "village" -> "village";
            case "port" -> "port";
            case "forest", "grove" -> "forest";
            case "mountain" -> "mountain";
            case "temple", "shrine" -> "temple";
            case "ruins" -> "ruins";
            case "tower" -> "tower";
            case "camp" -> "camp";
            case "swamp" -> "swamp";
            case "oasis" -> "oasis";
            case "lake", "river_crossing" -> "water";
            default -> null;
        };
        if (byType != null) return byType;
        if (specific != null) return specific;
        String fromDescription = templateFromText(description == null
                ? "" : description.toLowerCase(Locale.ROOT));
        return fromDescription != null ? fromDescription : "generic";
    }

    private static final Set<String> GENERIC_NAME_TEMPLATES = Set.of("water", "town", "village");

    private static String templateFromText(String hint) {
        if (contains(hint, "academy", "school", "college", "university")) return "academy";
        if (contains(hint, "inn", "tavern", "roadhouse", "coaching house")) return "inn";
        if (contains(hint, "cathedral", "church", "monastery", "abbey", "temple", "shrine")) return "temple";
        if (contains(hint, "castle", "palace", "citadel", "keep", "fortress")) return "castle";
        if (contains(hint, "dungeon", "crypt", "tomb", "catacomb", "labyrinth", "maze")) return "dungeon";
        if (contains(hint, "cavern", "cave")) return "cave";
        if (contains(hint, "mine", "quarry", "shaft")) return "mine";
        if (contains(hint, "harbor", "harbour", "dock", "pier", "port")) return "port";
        if (contains(hint, "forest", "woods", "grove", "thicket")) return "forest";
        if (contains(hint, "mountain", "peak", "summit", "ridge")) return "mountain";
        if (contains(hint, "ruin", "ruined", "abandoned")) return "ruins";
        if (contains(hint, "tower", "spire", "lighthouse")) return "tower";
        if (contains(hint, "camp", "encampment", "caravan")) return "camp";
        if (contains(hint, "swamp", "marsh", "bog", "fen")) return "swamp";
        if (contains(hint, "oasis", "desert", "dune")) return "oasis";
        if (contains(hint, "lake", "river", "crossing", "ford")) return "water";
        if (contains(hint, "village", "hamlet")) return "village";
        if (contains(hint, "market", "town", "city", "street", "district")) return "town";
        return null;
    }

    // ==================== Layouts ====================

    private static Layout castle(int variant) {
        List<AreaDraft> areas = new ArrayList<>(List.of(
                area("hall", "Great Hall", "hall",
                        "A long hall under a high timber roof, with banners, a dais, and a hearth large enough to stand in.",
                        50, 52, false, true),
                area("courtyard", "Courtyard", "courtyard",
                        "A cobbled yard ringed by stables, storehouses, and the castle's inner walls.",
                        50, 76, false, false),
                gateway("gatehouse", "Gatehouse", "gate",
                        "A cold stone passage under a raised portcullis, with murder holes overhead and guards' benches along the walls.",
                        50, 92),
                area("kitchen", "Kitchen", "kitchen",
                        "Copper pots, hanging herbs, and a great brick oven fill this steamy kitchen.",
                        20, 54, false, false),
                area("barracks", "Barracks", "barracks",
                        "Rows of pallets, weapon racks, and a smell of oil and leather.",
                        80, 54, false, false),
                area("chapel", "Chapel", "chapel",
                        "A narrow chapel of pale stone, lit by candles and a single tall window.",
                        24, 30, false, false),
                area("solar", "Solar", "chamber",
                        "A private chamber with a writing desk, a curtained bed, and a fire that never quite warms the stone.",
                        76, 30, false, false),
                area("cellar", "Cellar", "cellar",
                        "Barrels, salted provisions, and a low vaulted ceiling lost in shadow.",
                        20, 72, false, false),
                area("stair", "Dungeon Stair", "stairs",
                        "A spiral stair descends into cold air and the distant sound of dripping water.",
                        8, 86, false, false),
                area("wall", "Wall Walk", "battlement",
                        "A windswept walk along the battlements, with the whole valley laid out below.",
                        82, 78, false, false)
        ));
        List<ConnectionDraft> links = new ArrayList<>(List.of(
                link("hall", "courtyard", "main doors"),
                link("courtyard", "gatehouse", "barbican gate"),
                link("hall", "kitchen", "serving passage"),
                link("hall", "barracks", "guards' corridor"),
                link("hall", "chapel", "arched gallery"),
                link("hall", "solar", "private stair"),
                link("courtyard", "wall", "stone stair"),
                link("kitchen", "cellar", "cellar stair"),
                link("cellar", "stair", "iron-bound door")
        ));
        if (variant == 1) {
            areas.add(area("library", "Library", "library",
                    "Shelves climb to the ceiling, and dust turns in the light from a shuttered window.",
                    90, 30, false, false));
            links.add(link("solar", "library", "library door"));
        } else if (variant == 2) {
            areas.add(area("guest", "Guest Wing", "chamber",
                    "A quiet corridor of guest rooms, each with a heavy door and a cold hearth.",
                    62, 40, false, false));
            links.add(link("hall", "guest", "guests' corridor"));
        }
        return layout(areas, links);
    }

    private static Layout academy(int variant) {
        List<AreaDraft> areas = new ArrayList<>(List.of(
                gateway("entrance", "Entrance Hall", "hall",
                        "A vaulted hall with the school's crest in mosaic on the floor and notice boards along the walls.",
                        50, 92),
                area("courtyard", "Courtyard", "courtyard",
                        "A wide courtyard where students gather between lessons, ringed by cloisters.",
                        50, 74, false, true),
                area("refectory", "Refectory", "hall",
                        "Long tables, benches, and the clatter of the school's shared meals.",
                        24, 58, false, false),
                area("lecture", "Lecture Hall", "hall",
                        "Tiered benches face a chalk-dusted board and a lecturer's lectern.",
                        76, 58, false, false),
                area("library", "Library", "library",
                        "Tall shelves, reading desks, and a silence enforced by the librarian's glare.",
                        24, 34, false, false),
                area("dormitory", "Dormitory", "chamber",
                        "Narrow beds, footlockers, and windows looking over the training yard.",
                        76, 34, false, false),
                area("yard", "Training Yard", "yard",
                        "A sanded yard with practice dummies, worn rings, and racks of blunted weapons.",
                        84, 76, false, false),
                area("office", "Headmaster's Office", "chamber",
                        "A severe room of dark wood, ledgers, and a window overlooking the gate.",
                        52, 18, false, false),
                area("kitchen", "Kitchens", "kitchen",
                        "A busy kitchen of steam, knives, and stacked trays for the refectory.",
                        16, 76, false, false)
        ));
        List<ConnectionDraft> links = new ArrayList<>(List.of(
                link("entrance", "courtyard", "inner doors"),
                link("courtyard", "refectory", "cloister walk"),
                link("courtyard", "lecture", "east arcade"),
                link("courtyard", "yard", "training yard gate"),
                link("courtyard", "kitchen", "service door"),
                link("refectory", "library", "west stair"),
                link("lecture", "dormitory", "dormitory stair"),
                link("library", "office", "headmaster's corridor"),
                link("dormitory", "office", "upper gallery")
        ));
        if (variant == 1) {
            areas.add(area("observatory", "Observatory", "tower",
                    "A round room of brass instruments and a cracked dome that opens to the sky.",
                    24, 12, false, false));
            links.add(link("library", "observatory", "spiral stair"));
        } else if (variant == 2) {
            areas.add(area("infirmary", "Infirmary", "chamber",
                    "Clean linen, herb jars, and beds screened by hanging cloth.",
                    88, 44, false, false));
            links.add(link("lecture", "infirmary", "covered walk"));
        }
        return layout(areas, links);
    }

    private static Layout inn(int variant) {
        List<AreaDraft> areas = new ArrayList<>(List.of(
                area("common", "Common Room", "hall",
                        "A low-beamed room of benches, a crowded bar, and a fire that smells of resin.",
                        42, 56, false, true),
                gateway("yard", "Yard", "yard",
                        "A muddy yard with a well, a mounting block, and the inn's sign creaking overhead.",
                        42, 88),
                area("kitchen", "Kitchen", "kitchen",
                        "A hot, cramped kitchen where the innkeeper works with two hired hands.",
                        14, 38, false, false),
                area("rooms", "Guest Rooms", "chamber",
                        "A narrow corridor of rented rooms, each with a straw bed and a locked chest.",
                        74, 66, false, false),
                area("stable", "Stable", "stable",
                        "Warm hay, restless horses, and tack hanging from the rafters.",
                        82, 88, false, false),
                area("cellar", "Cellar", "cellar",
                        "Ale casks, pickle jars, and a dirt floor that stays cold all year.",
                        18, 76, false, false),
                area("parlor", "Private Parlor", "chamber",
                        "A small room behind a curtain, with a round table and four chairs.",
                        76, 34, false, false)
        ));
        List<ConnectionDraft> links = new ArrayList<>(List.of(
                link("common", "yard", "front door"),
                link("common", "kitchen", "serving hatch"),
                link("common", "rooms", "stair"),
                link("common", "cellar", "cellar hatch"),
                link("common", "parlor", "parlor curtain"),
                link("yard", "stable", "stable door"),
                link("rooms", "parlor", "back stair")
        ));
        if (variant == 1) {
            areas.add(area("brewhouse", "Brewhouse", "workshop",
                    "Copper vats, a smell of yeast, and a floor slick with water.",
                    30, 20, false, false));
            links.add(link("cellar", "brewhouse", "low arch"));
        } else if (variant == 2) {
            areas.add(area("garden", "Kitchen Garden", "garden",
                    "Rows of herbs and vegetables behind the inn, with a bench against the wall.",
                    40, 18, false, false));
            links.add(link("kitchen", "garden", "back door"));
        }
        return layout(areas, links);
    }

    private static Layout temple(int variant) {
        List<AreaDraft> areas = new ArrayList<>(List.of(
                gateway("narthex", "Narthex", "hall",
                        "A shadowed entry hall with a stone basin and rows of worn pegs.",
                        50, 92),
                area("nave", "Nave", "hall",
                        "A long nave under painted arches, with benches and a cold stone floor.",
                        50, 68, false, true),
                area("altar", "Altar", "shrine",
                        "A raised altar beneath a hanging lamp, surrounded by offerings and faded banners.",
                        50, 44, false, false),
                area("cloister", "Cloister", "courtyard",
                        "A covered walk around a quiet garden, open to the weather.",
                        24, 54, false, false),
                area("cells", "Cells", "chamber",
                        "Small stone cells with narrow beds and a single window each.",
                        18, 30, false, false),
                area("crypt", "Crypt Stair", "stairs",
                        "Steps descend into a low crypt that smells of dust and old wax.",
                        82, 44, false, false),
                area("garden", "Garden", "garden",
                        "A walled garden of herbs and quiet paths.",
                        78, 68, false, false)
        ));
        List<ConnectionDraft> links = new ArrayList<>(List.of(
                link("narthex", "nave", "inner doors"),
                link("nave", "altar", "center aisle"),
                link("nave", "cloister", "side door"),
                link("nave", "garden", "garden door"),
                link("cloister", "cells", "cloister stair"),
                link("altar", "crypt", "crypt stair")
        ));
        if (variant == 1) {
            areas.add(area("library", "Chapter Library", "library",
                    "Shelves of ledgers and old books line a narrow room.",
                    82, 20, false, false));
            links.add(link("cells", "library", "chapter corridor"));
        } else if (variant == 2) {
            areas.add(area("belfry", "Belfry", "tower",
                    "A high room of ropes, bells, and pigeons.",
                    30, 12, false, false));
            links.add(link("nave", "belfry", "bell stair"));
        }
        return layout(areas, links);
    }

    private static Layout dungeon(int variant) {
        List<AreaDraft> areas = new ArrayList<>(List.of(
                gateway("entrance", "Entrance", "gate",
                        "A broken arch opens into cold air and darkness.",
                        50, 94),
                area("hall", "Collapsed Hall", "hall",
                        "Fallen pillars and rubble choke a hall that once held a great assembly.",
                        50, 74, false, true),
                area("west", "West Corridor", "corridor",
                        "A low corridor of wet stone, with old torch brackets along the wall.",
                        24, 60, false, false),
                area("east", "East Corridor", "corridor",
                        "A narrow passage where the ceiling has begun to sag.",
                        76, 60, false, false),
                area("flood", "Flooded Chamber", "chamber",
                        "Black water covers the floor, and the walls glisten with damp.",
                        22, 38, false, false),
                area("shrine", "Shrine", "shrine",
                        "A small shrine stands untouched by the ruin around it, its candle still burning.",
                        78, 38, false, false),
                area("ossuary", "Ossuary", "chamber",
                        "Bones are stacked in careful rows along the walls.",
                        16, 18, false, false),
                area("vault", "Vault", "vault",
                        "A heavy door stands open onto a room of empty plinths and scattered coins.",
                        58, 18, false, false),
                area("deep", "Deep Stair", "stairs",
                        "A stair spirals down into a darkness that swallows torchlight.",
                        86, 18, false, false)
        ));
        List<ConnectionDraft> links = new ArrayList<>(List.of(
                link("entrance", "hall", "broken arch"),
                link("hall", "west", "west arch"),
                link("hall", "east", "east passage"),
                link("west", "flood", "flooded stair"),
                link("east", "shrine", "shrine door"),
                link("flood", "ossuary", "bone passage"),
                link("ossuary", "vault", "collapsed wall"),
                link("shrine", "deep", "deep stair")
        ));
        if (variant == 1) {
            areas.add(area("prison", "Prison", "chamber",
                    "Iron rings, straw, and scratched tally marks line the walls.",
                    10, 52, false, false));
            links.add(link("west", "prison", "iron gate"));
        } else if (variant == 2) {
            areas.add(area("crypt", "Crypt", "chamber",
                    "Stone coffins lie in tiers beneath a low vault.",
                    46, 8, false, false));
            links.add(link("vault", "crypt", "crypt stair"));
        }
        return layout(areas, links);
    }

    private static Layout cave(int variant) {
        List<AreaDraft> areas = new ArrayList<>(List.of(
                gateway("mouth", "Cave Mouth", "cave",
                        "A wide crack in the rock, cold with the breath of the earth.",
                        50, 94),
                area("tunnel", "Tunnel", "corridor",
                        "A winding tunnel where the walls narrow and the floor is slick.",
                        50, 74, false, true),
                area("cavern", "Great Cavern", "cavern",
                        "A vast cavern opens overhead, its ceiling lost beyond the torchlight.",
                        40, 50, false, false),
                area("pool", "Pool", "water",
                        "Still black water reflects the cavern roof.",
                        18, 34, false, false),
                area("crystal", "Crystal Chamber", "cavern",
                        "Pale crystals grow from the walls and floor, casting a faint blue light.",
                        72, 34, false, false),
                area("crawl", "Narrow Crawl", "corridor",
                        "A crawl space barely wide enough for a person in armor.",
                        82, 60, false, false),
                area("bones", "Bone Alcove", "chamber",
                        "A side alcove littered with the bones of animals and less fortunate travelers.",
                        14, 62, false, false)
        ));
        List<ConnectionDraft> links = new ArrayList<>(List.of(
                link("mouth", "tunnel", "narrow throat"),
                link("tunnel", "cavern", "cavern passage"),
                link("cavern", "pool", "water stair"),
                link("cavern", "crystal", "crystal passage"),
                link("tunnel", "crawl", "crawl space"),
                link("tunnel", "bones", "side alcove")
        ));
        if (variant == 1) {
            areas.add(area("river", "Underground River", "water",
                    "A fast black river cuts through the rock, loud in the enclosed space.",
                    50, 18, false, false));
            links.add(link("crystal", "river", "river ledge"));
        }
        return layout(areas, links);
    }

    private static Layout mine(int variant) {
        List<AreaDraft> areas = new ArrayList<>(List.of(
                gateway("entrance", "Mine Entrance", "gate",
                        "Timber braces frame a dark shaft in the hillside.",
                        50, 94),
                area("winch", "Winch Platform", "workshop",
                        "A wooden platform, a creaking winch, and a cart of broken ore.",
                        50, 74, false, true),
                area("gallery", "Main Gallery", "corridor",
                        "A broad gallery cut through the rock, with rails and abandoned tools.",
                        40, 50, false, false),
                area("vein", "Ore Vein", "chamber",
                        "A glittering seam of ore runs through the wall, lit by a single lantern.",
                        18, 34, false, false),
                area("lower", "Lower Tunnel", "corridor",
                        "A low tunnel slopes down into warm, stale air.",
                        72, 34, false, false),
                area("flood", "Flooded Shaft", "water",
                        "A vertical shaft disappears into black water.",
                        82, 60, false, false),
                area("office", "Foreman's Room", "chamber",
                        "A cramped office with a ledger, a cold brazier, and a narrow cot.",
                        14, 64, false, false)
        ));
        List<ConnectionDraft> links = new ArrayList<>(List.of(
                link("entrance", "winch", "shaft ladder"),
                link("winch", "gallery", "gallery arch"),
                link("gallery", "vein", "ore passage"),
                link("gallery", "lower", "lower decline"),
                link("lower", "flood", "flooded stair"),
                link("winch", "office", "office door")
        ));
        if (variant == 1) {
            areas.add(area("vent", "Old Ventilation Shaft", "corridor",
                    "A narrow shaft climbs toward a distant chink of daylight.",
                    50, 16, false, false));
            links.add(link("vein", "vent", "vent tunnel"));
        }
        return layout(areas, links);
    }

    private static Layout town(int variant) {
        List<AreaDraft> areas = new ArrayList<>(List.of(
                area("market", "Market Square", "square",
                        "A wide square of cobbles, stalls, and the noise of trade.",
                        50, 66, false, true),
                gateway("gate", "Main Gate", "gate",
                        "A gatehouse of timber and stone controlling the road into town.",
                        50, 92),
                area("tavern", "Tavern", "hall",
                        "A warm, crowded tavern of smoke, ale, and rumor.",
                        22, 54, false, false),
                area("smithy", "Smithy", "workshop",
                        "A hot forge, ringing anvil, and racks of unfinished iron.",
                        78, 54, false, false),
                area("temple", "Temple", "shrine",
                        "A modest temple with an open door and a worn stone step.",
                        22, 30, false, false),
                area("alley", "Alley", "street",
                        "A narrow alley of laundry lines, puddles, and back doors.",
                        50, 42, false, false),
                area("manor", "Manor", "hall",
                        "A solid house behind a wall, with shuttered windows and a guarded door.",
                        78, 30, false, false),
                area("stable", "Stable", "stable",
                        "A long stable of stalls, hay, and the smell of horses.",
                        82, 76, false, false)
        ));
        List<ConnectionDraft> links = new ArrayList<>(List.of(
                link("market", "gate", "cobbled street"),
                link("market", "tavern", "tavern lane"),
                link("market", "smithy", "smith's row"),
                link("market", "temple", "temple steps"),
                link("market", "alley", "alley mouth"),
                link("alley", "manor", "back lane"),
                link("market", "stable", "stable yard")
        ));
        if (variant == 1) {
            areas.add(area("guild", "Guildhall", "hall",
                    "A self-important hall of banners, ledgers, and polished wood.",
                    90, 54, false, false));
            links.add(link("smithy", "guild", "guild steps"));
        } else if (variant == 2) {
            areas.add(area("garden", "Walled Garden", "garden",
                    "A walled garden with clipped hedges and a bench out of the wind.",
                    62, 24, false, false));
            links.add(link("temple", "garden", "garden gate"));
        }
        return layout(areas, links);
    }

    private static Layout village(int variant) {
        List<AreaDraft> areas = new ArrayList<>(List.of(
                area("green", "Village Green", "square",
                        "A broad green with a well, a notice board, and ruts from cart wheels.",
                        50, 66, false, true),
                gateway("road", "Mill Road", "road",
                        "A dirt road that leads past the mill and out toward the fields.",
                        50, 92),
                area("inn", "Inn", "hall",
                        "A low inn of timber and thatch, warm inside and smelling of stew.",
                        22, 52, false, false),
                area("smithy", "Smithy", "workshop",
                        "A village smithy with a stone forge and a pile of scrap iron.",
                        78, 52, false, false),
                area("shrine", "Shrine", "shrine",
                        "A small roadside shrine with a weathered figure and fresh flowers.",
                        22, 30, false, false),
                area("farm", "Farmstead", "yard",
                        "A fenced farmstead of a low house, a barn, and a muddy yard.",
                        78, 30, false, false),
                area("well", "Common Well", "square",
                        "A stone well with a creaking bucket and a worn lip.",
                        50, 42, false, false)
        ));
        List<ConnectionDraft> links = new ArrayList<>(List.of(
                link("green", "road", "mill road"),
                link("green", "inn", "inn lane"),
                link("green", "smithy", "forge lane"),
                link("green", "shrine", "shrine path"),
                link("green", "farm", "farm track"),
                link("green", "well", "village well")
        ));
        if (variant == 1) {
            areas.add(area("mill", "Mill", "workshop",
                    "A water mill turns beside the stream, its floor dusted with flour.",
                    28, 18, false, false));
            links.add(link("road", "mill", "mill path"));
        }
        return layout(areas, links);
    }

    private static Layout port(int variant) {
        List<AreaDraft> areas = new ArrayList<>(List.of(
                gateway("docks", "Docks", "dock",
                        "A crowded quay of rope, salt, and shouting dockhands.",
                        50, 88),
                area("market", "Fish Market", "square",
                        "Wet stone tables, barrels of fish, and aggressive gulls.",
                        26, 64, false, true),
                area("tavern", "Tavern", "hall",
                        "A dockside tavern of low ceilings and cheaper ale.",
                        74, 64, false, false),
                area("warehouse", "Warehouse", "warehouse",
                        "A long warehouse of stacked crates, nets, and shadowed aisles.",
                        18, 38, false, false),
                area("harbor", "Harbormaster's Office", "chamber",
                        "A cramped office of charts, ledgers, and a bell rope.",
                        78, 38, false, false),
                area("ship", "Moored Ship", "ship",
                        "A merchant ship lies alongside, its deck busy with cargo.",
                        50, 30, false, false),
                area("alley", "Alley", "street",
                        "A narrow passage between warehouses, smelling of tar and rot.",
                        50, 52, false, false)
        ));
        List<ConnectionDraft> links = new ArrayList<>(List.of(
                link("docks", "market", "quay steps"),
                link("docks", "tavern", "tavern quay"),
                link("docks", "ship", "gangplank"),
                link("market", "warehouse", "warehouse lane"),
                link("market", "alley", "fish alley"),
                link("tavern", "harbor", "harbor street"),
                link("alley", "harbor", "back alley")
        ));
        if (variant == 1) {
            areas.add(area("customs", "Customs House", "hall",
                    "A cold room of counters, sealed ledgers, and a waiting bench.",
                    80, 20, false, false));
            links.add(link("harbor", "customs", "customs stair"));
        }
        return layout(areas, links);
    }

    private static Layout forest(int variant) {
        List<AreaDraft> areas = new ArrayList<>(List.of(
                gateway("trailhead", "Trailhead", "road",
                        "A path enters the trees under a canopy that closes out the sky.",
                        50, 92),
                area("clearing", "Clearing", "clearing",
                        "A wide clearing ringed by old trees, with a fire pit at its center.",
                        50, 68, false, true),
                area("trail", "Game Trail", "trail",
                        "A narrow trail of broken branches and hoofprints.",
                        24, 54, false, false),
                area("stream", "Stream", "water",
                        "A shallow stream runs over stones, loud enough to cover quieter sounds.",
                        76, 54, false, false),
                area("thicket", "Thicket", "thicket",
                        "A dense thicket of briars and young trees.",
                        20, 32, false, false),
                area("oak", "Old Oak", "landmark",
                        "A vast oak stands alone, its roots spreading like walls.",
                        74, 32, false, false),
                area("hollow", "Hollow", "cave",
                        "A hollow beneath the roots, dry and hidden from the trail.",
                        50, 18, false, false)
        ));
        List<ConnectionDraft> links = new ArrayList<>(List.of(
                link("trailhead", "clearing", "worn path"),
                link("clearing", "trail", "game trail"),
                link("clearing", "stream", "stream path"),
                link("trail", "thicket", "deer run"),
                link("stream", "oak", "fallen log"),
                link("thicket", "hollow", "root tunnel"),
                link("oak", "hollow", "root hollow")
        ));
        if (variant == 1) {
            areas.add(area("pool", "Still Pool", "water",
                    "A dark pool under the trees, its surface unbroken.",
                    32, 16, false, false));
            links.add(link("stream", "pool", "stream bend"));
        }
        return layout(areas, links);
    }

    private static Layout mountain(int variant) {
        List<AreaDraft> areas = new ArrayList<>(List.of(
                gateway("trailhead", "Trailhead", "road",
                        "A switchback trail begins at the foot of the mountain.",
                        50, 92),
                area("switchback", "Switchback", "trail",
                        "A narrow path turns back on itself above a long drop.",
                        38, 72, false, true),
                area("ledge", "Ledge", "ledge",
                        "A flat ledge juts from the rock, exposed to the wind.",
                        66, 58, false, false),
                area("cave", "Cave Mouth", "cave",
                        "A cave opens in the cliff face, cold and dark.",
                        26, 44, false, false),
                area("summit", "Summit Path", "trail",
                        "A steep path of loose stone climbs toward the peak.",
                        72, 36, false, false),
                area("shelter", "Shelter", "shelter",
                        "A stone shelter of stacked slabs, barely large enough to break the wind.",
                        50, 22, false, false)
        ));
        List<ConnectionDraft> links = new ArrayList<>(List.of(
                link("trailhead", "switchback", "first bend"),
                link("switchback", "ledge", "exposed traverse"),
                link("switchback", "cave", "cliff path"),
                link("ledge", "summit", "upper traverse"),
                link("summit", "shelter", "final approach")
        ));
        if (variant == 1) {
            areas.add(area("spring", "Mountain Spring", "water",
                    "A cold spring seeps from the rock into a stone basin.",
                    54, 42, false, false));
            links.add(link("ledge", "spring", "spring path"));
        }
        return layout(areas, links);
    }

    private static Layout camp(int variant) {
        List<AreaDraft> areas = new ArrayList<>(List.of(
                area("fire", "Campfire", "camp",
                        "A ring of stones, a low fire, and bedrolls drawn close against the night.",
                        50, 58, false, true),
                gateway("gate", "Palisade Gate", "gate",
                        "A gap in the wooden palisade, watched by a bored sentry.",
                        50, 88),
                area("tents", "Tents", "camp",
                        "A cluster of canvas tents, guy ropes, and muddy paths.",
                        24, 50, false, false),
                area("wagon", "Supply Wagon", "camp",
                        "A laden wagon under oiled canvas, with a guard's stool beside it.",
                        76, 50, false, false),
                area("command", "Command Tent", "camp",
                        "A larger tent with a map table and a lantern that burns late.",
                        30, 28, false, false),
                area("picket", "Picket Line", "camp",
                        "Horses and mules stand tethered along a rope, stamping at flies.",
                        74, 28, false, false)
        ));
        List<ConnectionDraft> links = new ArrayList<>(List.of(
                link("fire", "gate", "palisade path"),
                link("fire", "tents", "tent row"),
                link("fire", "wagon", "wagon track"),
                link("tents", "command", "command path"),
                link("wagon", "picket", "picket path")
        ));
        if (variant == 1) {
            areas.add(area("cook", "Cook Fire", "camp",
                    "A second fire, a hanging pot, and a stack of firewood.",
                    66, 70, false, false));
            links.add(link("fire", "cook", "short path"));
        }
        return layout(areas, links);
    }

    private static Layout ruins(int variant) {
        List<AreaDraft> areas = new ArrayList<>(List.of(
                gateway("court", "Outer Court", "courtyard",
                        "A cracked court of flagstones and toppled columns.",
                        50, 90),
                area("hall", "Broken Hall", "hall",
                        "A roofless hall open to the sky, its walls furred with moss.",
                        50, 66, false, true),
                area("cloister", "Cloister", "courtyard",
                        "A broken arcade around a choked garden.",
                        24, 50, false, false),
                area("vault", "Vault", "vault",
                        "A low vault whose door has long since fallen from its hinges.",
                        76, 50, false, false),
                area("tower", "Collapsed Tower", "tower",
                        "A stub of tower stands among its own fallen stones.",
                        30, 30, false, false),
                area("garden", "Overgrown Garden", "garden",
                        "A garden gone wild behind a broken wall.",
                        70, 30, false, false)
        ));
        List<ConnectionDraft> links = new ArrayList<>(List.of(
                link("court", "hall", "broken steps"),
                link("hall", "cloister", "cloister arch"),
                link("hall", "vault", "vault passage"),
                link("cloister", "tower", "rubble path"),
                link("vault", "garden", "fallen wall")
        ));
        if (variant == 1) {
            areas.add(area("crypt", "Sunken Crypt", "chamber",
                    "Steps lead down to a flooded crypt and a row of stone coffins.",
                    50, 16, false, false));
            links.add(link("vault", "crypt", "crypt stair"));
        }
        return layout(areas, links);
    }

    private static Layout tower(int variant) {
        List<AreaDraft> areas = new ArrayList<>(List.of(
                gateway("door", "Tower Door", "gate",
                        "A heavy door at the foot of a round tower.",
                        50, 92),
                area("ground", "Ground Floor", "room",
                        "A bare stone room with a ladder, a table, and a cold brazier.",
                        50, 72, false, true),
                area("stair", "Spiral Stair", "stairs",
                        "A tight spiral stair climbs through the tower's heart.",
                        50, 54, false, false),
                area("upper", "Upper Chamber", "chamber",
                        "A round room with narrow windows and a bed against the wall.",
                        50, 34, false, false),
                area("roof", "Roof", "battlement",
                        "A wind-scoured roof with a low parapet and a wide view.",
                        50, 14, false, false),
                area("cellar", "Cellar", "cellar",
                        "A dark cellar beneath the tower, reached by a trapdoor.",
                        24, 72, false, false)
        ));
        List<ConnectionDraft> links = new ArrayList<>(List.of(
                link("door", "ground", "tower door"),
                link("ground", "stair", "stair foot"),
                link("stair", "upper", "upper landing"),
                link("upper", "roof", "roof ladder"),
                link("ground", "cellar", "trapdoor")
        ));
        if (variant == 1) {
            areas.add(area("study", "Study", "chamber",
                    "A small study of books, charts, and a cold cup of tea.",
                    76, 34, false, false));
            links.add(link("upper", "study", "study door"));
        }
        return layout(areas, links);
    }

    private static Layout water(int variant) {
        List<AreaDraft> areas = new ArrayList<>(List.of(
                gateway("shore", "Shore", "shore",
                        "A gravel shore where the water laps at the stones.",
                        50, 90),
                area("pier", "Pier", "dock",
                        "A wooden pier reaches into the water, its planks slick with weed.",
                        50, 70, false, true),
                area("crossing", "Crossing", "water",
                        "A shallow crossing of stones and sand.",
                        28, 50, false, false),
                area("reeds", "Reeds", "water",
                        "Tall reeds line the water, rustling in the wind.",
                        72, 50, false, false),
                area("island", "Island", "landmark",
                        "A small island of trees and stone, cut off by deep water.",
                        50, 28, false, false)
        ));
        List<ConnectionDraft> links = new ArrayList<>(List.of(
                link("shore", "pier", "pier steps"),
                link("shore", "crossing", "shore path"),
                link("shore", "reeds", "reed bank"),
                link("pier", "island", "boat"),
                link("crossing", "island", "shallow ford")
        ));
        if (variant == 1) {
            areas.add(area("hut", "Fisher's Hut", "room",
                    "A low hut of driftwood and tar, with nets hung outside.",
                    20, 74, false, false));
            links.add(link("shore", "hut", "hut path"));
        }
        return layout(areas, links);
    }

    private static Layout swamp(int variant) {
        List<AreaDraft> areas = new ArrayList<>(List.of(
                gateway("hummock", "Hummock", "landmark",
                        "A low hummock of firm ground surrounded by black water.",
                        50, 90),
                area("grove", "Drowned Grove", "forest",
                        "Dead trees stand in the water, their branches hung with moss.",
                        50, 68, false, true),
                area("channel", "Black Channel", "water",
                        "A slow channel of dark water winds between the roots.",
                        26, 50, false, false),
                area("hut", "Stilted Hut", "room",
                        "A stilted hut leans over the water, its door shut.",
                        74, 50, false, false),
                area("bank", "Reed Bank", "water",
                        "A wide bank of reeds and mud, loud with insects.",
                        50, 30, false, false)
        ));
        List<ConnectionDraft> links = new ArrayList<>(List.of(
                link("hummock", "grove", "mossy path"),
                link("grove", "channel", "sunken path"),
                link("grove", "hut", "boardwalk"),
                link("channel", "bank", "reed path"),
                link("hut", "bank", "stilt walk")
        ));
        if (variant == 1) {
            areas.add(area("mound", "Burial Mound", "shrine",
                    "A low mound of stones rises from the marsh.",
                    50, 14, false, false));
            links.add(link("bank", "mound", "stone causeway"));
        }
        return layout(areas, links);
    }

    private static Layout oasis(int variant) {
        List<AreaDraft> areas = new ArrayList<>(List.of(
                area("pool", "Pool", "water",
                        "A clear pool ringed by palms and flat stones.",
                        50, 58, false, true),
                gateway("dunes", "Dune Edge", "desert",
                        "The dunes begin at the edge of the oasis, bright and empty.",
                        50, 90),
                area("palms", "Palms", "garden",
                        "A stand of date palms, with shade and fallen fruit.",
                        50, 78, false, false),
                area("camp", "Camp", "camp",
                        "A small camp of rugs and a cold fire pit.",
                        24, 48, false, false),
                area("shrine", "Ruined Shrine", "shrine",
                        "A half-buried shrine of carved stone stands at the water's edge.",
                        76, 48, false, false)
        ));
        List<ConnectionDraft> links = new ArrayList<>(List.of(
                link("dunes", "palms", "dune path"),
                link("palms", "pool", "palm shade"),
                link("pool", "camp", "camp path"),
                link("pool", "shrine", "shrine path")
        ));
        if (variant == 1) {
            areas.add(area("well", "Old Well", "water",
                    "A stone well stands among the palms, its water cold and sweet.",
                    78, 20, false, false));
            links.add(link("shrine", "well", "stone path"));
        }
        return layout(areas, links);
    }

    private static Layout generic(int variant) {
        List<AreaDraft> areas = new ArrayList<>(List.of(
                gateway("entrance", "Entrance", "gate",
                        "The way into this place.",
                        50, 90),
                area("main", "Main Room", "room",
                        "The main room, with a table, a hearth, and a few chairs.",
                        50, 64, false, true),
                area("side", "Side Room", "room",
                        "A smaller room off the main one.",
                        24, 48, false, false),
                area("passage", "Passage", "corridor",
                        "A narrow passage connecting the rooms.",
                        76, 48, false, false),
                gateway("rear", "Rear Exit", "gate",
                        "A rear door leading out.",
                        50, 28)
        ));
        List<ConnectionDraft> links = new ArrayList<>(List.of(
                link("entrance", "main", "front door"),
                link("main", "side", "side door"),
                link("main", "passage", "passage door"),
                link("passage", "rear", "rear door")
        ));
        if (variant == 1) {
            areas.add(area("store", "Store Room", "workshop",
                    "A cramped store room of shelves and crates.",
                    24, 72, false, false));
            links.add(link("entrance", "store", "store door"));
        }
        return layout(areas, links);
    }

    // ==================== Helpers ====================

    private static Layout layout(List<AreaDraft> areas, List<ConnectionDraft> links) {
        Set<String> keys = new HashSet<>();
        for (AreaDraft area : areas) {
            if (!keys.add(area.key())) {
                throw new IllegalStateException("Duplicate area key: " + area.key());
            }
        }
        for (ConnectionDraft link : links) {
            if (!keys.contains(link.fromKey()) || !keys.contains(link.toKey())) {
                throw new IllegalStateException("Connection references unknown area: " + link);
            }
        }
        return new Layout(List.copyOf(areas), List.copyOf(links));
    }

    private static AreaDraft area(String key, String name, String type, String description,
                                  float x, float y, boolean gateway, boolean entry) {
        return new AreaDraft(key, name, type, description, x, y, gateway, entry);
    }

    private static AreaDraft gateway(String key, String name, String type, String description,
                                     float x, float y) {
        return new AreaDraft(key, name, type, description, x, y, true, false);
    }

    private static ConnectionDraft link(String from, String to, String label) {
        return new ConnectionDraft(from, to, label);
    }

    /**
     * Matches whole words only.
     *
     * A plain substring test turned "the lowland setting keeps..." into a
     * castle ("keep") and would turn "important" into a port ("port") or
     * "determine" into a mine ("mine"). The node's own type is the fallback,
     * so a false positive here replaces the right layout with the wrong one.
     */
    private static boolean contains(String text, String... words) {
        for (String word : words) {
            Pattern whole = Pattern.compile("\\b" + Pattern.quote(word) + "\\b");
            if (whole.matcher(text).find()) return true;
        }
        return false;
    }
}
