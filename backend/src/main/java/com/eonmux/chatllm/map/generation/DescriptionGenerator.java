package com.eonmux.chatllm.map.generation;

import com.eonmux.chatllm.map.models.Biome;

import java.util.Map;
import java.util.Random;

/**
 * Composes 2-4 sentence location descriptions from context-indexed fragment pools.
 * Each description combines an opening (type-based), environment (biome-based),
 * cultural flavor, and contextual detail sentence for rich, unique output.
 */
public final class DescriptionGenerator {

    // ── Opening fragments by location type ──────────────────────────────────

    private static final Map<String, String[]> OPENINGS = Map.ofEntries(
        Map.entry("town", new String[]{
            "A bustling market town sits at a crossroads of well-traveled paths.",
            "Smoke rises from the chimneys of this thriving settlement.",
            "This trading hub has grown around a central square where merchants gather daily."
        }),
        Map.entry("city", new String[]{
            "A sprawling metropolis spreads across the landscape, its walls encircling thousands of souls.",
            "The sounds of commerce and culture fill the wide avenues of this grand city.",
            "Towering spires and domed halls mark the skyline of this seat of power."
        }),
        Map.entry("village", new String[]{
            "A small cluster of homes nestles in the landscape, quiet and unassuming.",
            "This modest hamlet consists of a handful of dwellings and a shared well.",
            "Thin trails of woodsmoke mark this tiny settlement from a distance."
        }),
        Map.entry("fortress", new String[]{
            "A formidable military stronghold commands the surrounding terrain.",
            "Thick walls and iron-banded gates guard this imposing fortress.",
            "Battlements bristle with watchtowers, each manned by vigilant sentries."
        }),
        Map.entry("castle", new String[]{
            "A noble castle rises above the surrounding lands, its banners catching the wind.",
            "This fortified seat of power has stood for generations.",
            "Arrow slits and crenellated walls ring this imposing stronghold."
        }),
        Map.entry("keep", new String[]{
            "A small but sturdy fortification guards a strategic vantage point.",
            "This squat tower and its surrounding curtain wall form a reliable redoubt.",
            "A modest garrison holds this lonely outpost against the wilds."
        }),
        Map.entry("dungeon", new String[]{
            "A dark and treacherous underground complex sprawls beneath the surface.",
            "The entrance to this subterranean labyrinth yawns like a wound in the earth.",
            "Few who enter these depths return unchanged, if they return at all."
        }),
        Map.entry("cave", new String[]{
            "A natural cave formation opens in the rock face, its depths unknown.",
            "Cool air drifts from the mouth of this cavern, carrying strange echoes.",
            "Stalactites frame the entrance to this ancient hollow in the stone."
        }),
        Map.entry("mine", new String[]{
            "Timber-framed tunnels descend into rich veins of ore and mineral.",
            "The rhythmic ring of pickaxes echoes from this working mine.",
            "Cart tracks and pulley systems mark this as an active excavation site."
        }),
        Map.entry("forest", new String[]{
            "A dense woodland stretches as far as the eye can see.",
            "Ancient trees tower overhead, their canopy filtering the light.",
            "This named stretch of forest carries a reputation among local travelers."
        }),
        Map.entry("grove", new String[]{
            "A ring of ancient trees forms a natural clearing of quiet beauty.",
            "This sacred grove holds trees older than any nearby settlement.",
            "Dappled light plays across the mossy floor of this sheltered stand of trees."
        }),
        Map.entry("mountain", new String[]{
            "A towering peak rises above the surrounding range.",
            "This named summit is visible for leagues in every direction.",
            "Snow and cloud cling to the upper reaches of this imposing mountain."
        }),
        Map.entry("lake", new String[]{
            "A broad lake stretches across the lowland, its surface mirroring the sky.",
            "Still waters fill this natural basin, fed by hidden springs.",
            "Reeds and waterbirds line the shores of this tranquil body of water."
        }),
        Map.entry("river_crossing", new String[]{
            "A bridge or ford marks this vital crossing point over running water.",
            "Travelers and merchants converge at this river crossing.",
            "Worn stones and timber mark where generations have forded the current."
        }),
        Map.entry("shrine", new String[]{
            "A small devotional site marks this spot as sacred to local belief.",
            "Offerings and carved symbols adorn this modest place of worship.",
            "Pilgrims occasionally visit this quiet shrine tucked into the landscape."
        }),
        Map.entry("temple", new String[]{
            "A grand religious edifice dominates the surrounding area.",
            "Carved pillars and vaulted ceilings speak to the devotion of the builders.",
            "Incense and chanting drift from the halls of this house of worship."
        }),
        Map.entry("ruins", new String[]{
            "Crumbling walls and toppled columns hint at past grandeur.",
            "Nature has begun reclaiming these ancient remnants of a forgotten age.",
            "Weathered stone and overgrown pathways mark what was once a place of importance."
        }),
        Map.entry("tower", new String[]{
            "A solitary tower rises from the landscape, its purpose unclear from a distance.",
            "This tall spire serves as both lookout and dwelling for its reclusive occupant.",
            "Weathered stone and narrow windows mark this tower against the sky."
        }),
        Map.entry("port", new String[]{
            "Docks and warehouses line the waterfront of this busy coastal settlement.",
            "The creaking of moored ships and the cry of gulls fill the salt air.",
            "This harbor town thrives on the trade that flows through its piers."
        }),
        Map.entry("camp", new String[]{
            "A cluster of tents and firepits marks this temporary resting place.",
            "Travelers have worn this spot into a recognized waypoint.",
            "This campsite offers shelter and a place to rest between longer journeys."
        }),
        Map.entry("oasis", new String[]{
            "A miraculous spring of fresh water sustains life amid the barren sands.",
            "Palm trees and green growth surround this desert lifeline.",
            "The shimmer of water draws travelers from across the parched wasteland."
        }),
        Map.entry("swamp", new String[]{
            "Murky waters and twisted vegetation define this treacherous marshland.",
            "The air hangs thick with moisture and the hum of unseen creatures.",
            "Gnarled roots and stagnant pools make passage through here perilous."
        })
    );

    // ── Environment fragments by biome ──────────────────────────────────────

    private static final Map<Biome, String[]> ENVIRONMENTS = Map.ofEntries(
        Map.entry(Biome.GRASSLAND, new String[]{
            "Rolling green fields stretch in every direction.",
            "Wildflowers sway in the gentle breeze across open meadows.",
            "The land here is flat and fertile, ideal for farming."
        }),
        Map.entry(Biome.FOREST, new String[]{
            "Ancient oaks form a dense canopy overhead.",
            "Birdsong echoes through the moss-covered trees.",
            "Shafts of golden light pierce the leafy canopy above."
        }),
        Map.entry(Biome.DENSE_FOREST, new String[]{
            "The forest here is nearly impenetrable, thick with undergrowth.",
            "Little light reaches the forest floor through the tangled branches.",
            "Old-growth trees crowd together, their roots forming a knotted maze."
        }),
        Map.entry(Biome.HILLS, new String[]{
            "Gentle hills roll across the landscape in every direction.",
            "Scrubby grass and heather cover the rounded hilltops.",
            "The undulating terrain offers commanding views from every rise."
        }),
        Map.entry(Biome.MOUNTAIN, new String[]{
            "Jagged peaks surround the area, cutting stark lines against the sky.",
            "Rocky terrain and thin mountain air define this harsh landscape.",
            "The wind howls through narrow passes between towering stone walls."
        }),
        Map.entry(Biome.SNOW_PEAK, new String[]{
            "Perpetual snow blankets the ground, glittering in the cold light.",
            "Ice and frost cling to every surface at this extreme altitude.",
            "The air is thin and bitterly cold, and the wind never rests."
        }),
        Map.entry(Biome.DESERT, new String[]{
            "Endless dunes of golden sand ripple toward the horizon.",
            "The sun beats down mercilessly on the cracked, dry earth.",
            "Heat shimmers distort the distant landscape in every direction."
        }),
        Map.entry(Biome.SWAMP, new String[]{
            "Standing water and tangled roots cover the boggy ground.",
            "A thick mist clings to the surface of the stagnant waters.",
            "The air is heavy with the scent of decay and damp earth."
        }),
        Map.entry(Biome.BEACH, new String[]{
            "Sandy shores give way to the rhythmic lap of gentle waves.",
            "Sea salt and warm sand define this coastal stretch.",
            "Driftwood and shells litter the tideline of this open shore."
        }),
        Map.entry(Biome.SHALLOW_WATER, new String[]{
            "Clear, shallow waters reveal the sandy bottom below.",
            "Gentle currents lap against the nearby shore.",
            "Sunlight dances across the surface of the calm waters."
        }),
        Map.entry(Biome.DEEP_WATER, new String[]{
            "Dark, deep waters stretch endlessly outward.",
            "The sea here is deep and unpredictable.",
            "Waves roll steadily across the open water."
        }),
        Map.entry(Biome.RIVER, new String[]{
            "A swift river cuts through the landscape, its waters clear and cold.",
            "The sound of rushing water fills the air as the river winds past.",
            "River banks lined with reeds and willows frame the flowing waters."
        }),
        Map.entry(Biome.LAKE, new String[]{
            "A serene lake mirrors the sky, its still surface broken only by ripples.",
            "The calm waters of this inland lake stretch toward distant shores.",
            "Gentle waves lap at the reedy shores of this tranquil lake."
        }),
        Map.entry(Biome.TUNDRA, new String[]{
            "Barren, wind-swept plains stretch endlessly under a leaden sky.",
            "Hardy lichens and dwarf shrubs cling to the frozen ground.",
            "The tundra is silent save for the constant low moan of the wind."
        })
    );

    // ── Cultural flavor fragments ───────────────────────────────────────────

    private static final Map<Culture, String[]> CULTURAL_FLAVORS = Map.ofEntries(
        Map.entry(Culture.NORTHERN, new String[]{
            "Carved runestones line the main path.",
            "The architecture favors heavy timber and stone, built to endure harsh winters.",
            "Furs and iron are the currency of daily life here."
        }),
        Map.entry(Culture.SYLVAN, new String[]{
            "Graceful arches and living wood blend seamlessly with the natural surroundings.",
            "Soft lantern light and the scent of herbs pervade the air.",
            "The inhabitants here live in close harmony with the forest."
        }),
        Map.entry(Culture.ARIDIAN, new String[]{
            "Colorful awnings and intricate tilework adorn the sun-bleached walls.",
            "The spice-scented air carries the sound of distant music.",
            "Geometric patterns and flowing calligraphy decorate every surface."
        }),
        Map.entry(Culture.MIREBORN, new String[]{
            "Woven reed structures and raised walkways keep the settlement above the damp ground.",
            "Strange carved totems stand at the boundaries, warding off ill fortune.",
            "The people here are hardy and resourceful, shaped by the unforgiving land."
        }),
        Map.entry(Culture.THALASSIAN, new String[]{
            "White-columned buildings and open courtyards face the sea breeze.",
            "Nautical symbols and trident motifs mark the architecture.",
            "The rhythm of life here follows the tides and the trade winds."
        }),
        Map.entry(Culture.HEARTHFOLK, new String[]{
            "Thatched roofs and flower boxes give the settlement a welcoming appearance.",
            "The aroma of fresh bread and wood smoke drifts from the buildings.",
            "Stone walls and hedgerows mark the boundaries of well-kept homesteads."
        }),
        Map.entry(Culture.FROSTBOUND, new String[]{
            "Dark stone and iron fixtures give the structures a somber, imposing air.",
            "The inhabitants dress in heavy furs and speak in clipped, deliberate tones.",
            "Every building is fortified against both the cold and potential invaders."
        }),
        Map.entry(Culture.DEEPSTONE, new String[]{
            "Masterful stonework and geometric precision define every structure.",
            "The ring of hammer on anvil echoes from somewhere below the surface.",
            "Tunnels and shafts connect the surface structures to extensive underground halls."
        })
    );

    // ── Contextual detail fragments ─────────────────────────────────────────

    private static final String[] NEAR_WATER_DETAILS = {
        "A clear stream runs along the settlement's edge.",
        "The sound of running water is ever-present.",
        "Fresh water is plentiful, drawn from a nearby source."
    };

    private static final String[] HIGH_ELEVATION_DETAILS = {
        "The thin mountain air carries the scent of pine and ice.",
        "From this height, the lands below spread out like a painted map.",
        "The wind at this altitude is constant and biting."
    };

    private static final String[] LOW_ELEVATION_DETAILS = {
        "The lowland setting keeps the area sheltered from harsh winds.",
        "Rich soil and ample moisture make the surrounding land productive.",
        "Morning mist frequently settles in this low-lying area."
    };

    private static final String[] NEAR_ROAD_DETAILS = {
        "Well-worn cart tracks lead to the main entrance.",
        "The nearby road brings a steady flow of travelers and news.",
        "Trade caravans pass through regularly, keeping the area supplied."
    };

    private static final String[] ISOLATED_DETAILS = {
        "Few paths lead here, and fewer travelers know of its existence.",
        "The remote location grants a measure of peace and privacy.",
        "Only the most determined travelers find their way to this place."
    };

    private static final String[] CROWDED_AREA_DETAILS = {
        "Several other settlements are visible in the surrounding landscape.",
        "The proximity to neighbors fosters both trade and rivalry.",
        "This area has become a hub of activity in the region."
    };

    private static final String[] DEFAULT_OPENING = {
        "A notable landmark stands at this location.",
        "This place has earned a name among those who travel the region."
    };

    private static final String[] DEFAULT_ENVIRONMENT = {
        "The surrounding landscape stretches outward in every direction.",
        "The terrain here is unremarkable but serviceable."
    };

    private DescriptionGenerator() {}

    /**
     * Generates a 2-4 sentence description for a location based on its placement context.
     */
    public static String generate(PlacementContext ctx, Random rng) {
        StringBuilder desc = new StringBuilder();

        // 1. Opening (always present)
        String[] openings = OPENINGS.getOrDefault(ctx.type().id(), DEFAULT_OPENING);
        desc.append(pick(openings, rng));

        // 2. Environment (always present)
        String[] envFragments = ENVIRONMENTS.getOrDefault(ctx.biome(), DEFAULT_ENVIRONMENT);
        desc.append(" ").append(pick(envFragments, rng));

        // 3. Cultural flavor (80% chance)
        if (rng.nextFloat() < 0.80f) {
            String[] culturalFragments = CULTURAL_FLAVORS.get(ctx.culture());
            if (culturalFragments != null) {
                desc.append(" ").append(pick(culturalFragments, rng));
            }
        }

        // 4. Contextual detail (70% chance)
        if (rng.nextFloat() < 0.70f) {
            String detail = pickContextualDetail(ctx, rng);
            if (detail != null) {
                desc.append(" ").append(detail);
            }
        }

        return desc.toString();
    }

    private static String pickContextualDetail(PlacementContext ctx, Random rng) {
        // Prioritize the most distinctive context
        if (ctx.nearestWaterDist() < 5.0f) {
            return pick(NEAR_WATER_DETAILS, rng);
        }
        if (ctx.elevation() > 0.75f) {
            return pick(HIGH_ELEVATION_DETAILS, rng);
        }
        if (ctx.elevation() < 0.40f) {
            return pick(LOW_ELEVATION_DETAILS, rng);
        }
        if (ctx.nearRoad()) {
            return pick(NEAR_ROAD_DETAILS, rng);
        }
        if (ctx.nearbyLocationCount() == 0) {
            return pick(ISOLATED_DETAILS, rng);
        }
        if (ctx.nearbyLocationCount() >= 3) {
            return pick(CROWDED_AREA_DETAILS, rng);
        }
        return null;
    }

    private static String pick(String[] array, Random rng) {
        return array[rng.nextInt(array.length)];
    }
}
