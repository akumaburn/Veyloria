package com.eonmux.chatllm.map.generation;

import com.eonmux.chatllm.map.models.Biome;

import java.util.Set;

/**
 * Cultural archetypes that govern naming conventions, architectural style,
 * and flavor text for procedurally generated map locations.
 * Each culture carries phoneme tables for syllable-based name generation,
 * root word pools for compound names, and biome affinity for placement.
 */
public enum Culture {

    NORTHERN(
        new String[]{"k", "th", "sk", "br", "gr", "hr", "st", "dr", "fr", "v", "j", "r"},
        new String[]{"a", "o", "u", "ei", "or", "al", "ar", "i"},
        new String[]{"r", "n", "nd", "rn", "ld", "th", "rk", "ng", "m"},
        new String[]{"Storm", "Iron", "Frost", "Wolf", "Eagle", "Stone", "Thunder", "Ash", "Blood", "Winter", "Raven", "Ice"},
        new String[]{"Hold", "Keep", "Watch", "Guard", "Forge", "Hall", "Helm", "Peak", "Gate", "Mark", "Spire", "Reach"},
        new String[]{"ancient", "frost-bitten", "wind-swept", "battle-scarred", "weathered", "iron-clad", "storm-lashed"},
        new String[]{"Longhouse", "Barrow", "Fjord", "Crag", "Throne", "Pyre", "Mead Hall"},
        new String[]{"Crossing", "Reach", "Rest", "Barrow", "Fall", "Haven", "Watch"},
        Set.of(Biome.SNOW_PEAK, Biome.MOUNTAIN, Biome.HILLS, Biome.TUNDRA)
    ),

    SYLVAN(
        new String[]{"l", "el", "th", "n", "s", "al", "f", "gl", "m", "v", "r"},
        new String[]{"ae", "i", "ea", "a", "o", "ie", "ia", "ei", "y"},
        new String[]{"n", "th", "l", "r", "nd", "s", "wen", "dor", "eth"},
        new String[]{"Silver", "Moon", "Star", "Dawn", "Whisper", "Shadow", "Dream", "Mist", "Fern", "Crystal"},
        new String[]{"Glen", "Dale", "Haven", "Brook", "Glade", "Wood", "Fall", "Dell", "Bower", "Spring"},
        new String[]{"enchanted", "moonlit", "verdant", "timeless", "ethereal", "luminous", "ancient"},
        new String[]{"Glade", "Bower", "Sanctuary", "Canopy", "Wellspring", "Hollow", "Grove"},
        new String[]{"Vigil", "Rest", "Crossing", "Glade", "Dell", "Watch", "Spring"},
        Set.of(Biome.FOREST, Biome.DENSE_FOREST)
    ),

    ARIDIAN(
        new String[]{"al", "sh", "kh", "z", "m", "j", "h", "q", "r", "s", "t"},
        new String[]{"a", "i", "u", "aa", "ai", "ar", "ah", "e"},
        new String[]{"m", "n", "r", "d", "th", "b", "sh", "k", "l"},
        new String[]{"Sun", "Sand", "Gold", "Flame", "Dust", "Wind", "Opal", "Amber", "Mirage", "Torch"},
        new String[]{"Gate", "Hall", "Well", "Throne", "Bazaar", "Spire", "Ward", "Port", "Dune", "Tower"},
        new String[]{"sun-baked", "golden", "ancient", "jeweled", "vast", "scorched", "gilded"},
        new String[]{"Oasis", "Citadel", "Bazaar", "Minaret", "Tomb", "Souk", "Caravansary"},
        new String[]{"Crossing", "Well", "Rest", "Claim", "Vigil", "Passage", "Folly"},
        Set.of(Biome.DESERT, Biome.BEACH)
    ),

    MIREBORN(
        new String[]{"b", "d", "c", "m", "f", "gl", "cr", "t", "br", "g"},
        new String[]{"a", "o", "u", "oi", "ea", "ou", "ai", "io"},
        new String[]{"gh", "n", "r", "ch", "nn", "ll", "th", "ck"},
        new String[]{"Moss", "Fog", "Peat", "Bramble", "Root", "Marsh", "Thorn", "Mire", "Hollow", "Glen"},
        new String[]{"Mound", "Fen", "Bog", "Cairn", "Dell", "Ford", "Rath", "Loch", "Moor", "Barrow"},
        new String[]{"moss-covered", "fog-shrouded", "ancient", "gnarled", "hidden", "damp", "overgrown"},
        new String[]{"Cairn", "Dolmen", "Rath", "Hollow", "Wyrd", "Bog", "Henge"},
        new String[]{"Ford", "Rest", "Crossing", "Hollow", "Mound", "Fen", "Cairn"},
        Set.of(Biome.SWAMP, Biome.GRASSLAND, Biome.LAKE)
    ),

    THALASSIAN(
        new String[]{"th", "p", "k", "s", "ph", "n", "l", "m", "d", "ae"},
        new String[]{"a", "e", "o", "i", "ae", "ei", "os", "y"},
        new String[]{"s", "n", "os", "us", "on", "is", "th", "x"},
        new String[]{"Wave", "Tide", "Pearl", "Coral", "Sail", "Harbor", "Foam", "Anchor", "Shell", "Trident"},
        new String[]{"Bay", "Cove", "Isle", "Port", "Reach", "Shore", "Deep", "Strait", "Sound", "Cape"},
        new String[]{"salt-kissed", "weathered", "gleaming", "maritime", "sun-bleached", "briny", "azure"},
        new String[]{"Harbor", "Lighthouse", "Agora", "Temple", "Oracle", "Wharf", "Amphitheater"},
        new String[]{"Landing", "Anchorage", "Rest", "Folly", "Watch", "Light", "Quay"},
        Set.of(Biome.SHALLOW_WATER, Biome.BEACH, Biome.LAKE, Biome.RIVER)
    ),

    HEARTHFOLK(
        new String[]{"b", "w", "h", "m", "st", "br", "cr", "gr", "gl", "t", "r"},
        new String[]{"a", "e", "i", "o", "u", "ea", "ow", "oo"},
        new String[]{"n", "d", "ley", "ton", "wick", "ford", "bury", "ham", "well"},
        new String[]{"Oak", "Mill", "Wheat", "Bell", "Meadow", "Clover", "Honey", "Hearth", "Copper", "Lark"},
        new String[]{"Bridge", "Lane", "Field", "Hill", "Dale", "Shire", "Gate", "Green", "Cross", "Manor"},
        new String[]{"quaint", "well-tended", "bustling", "humble", "peaceful", "homely", "charming"},
        new String[]{"Market", "Chapel", "Tavern", "Barn", "Mill", "Green", "Common"},
        new String[]{"Bridge", "Ford", "Rest", "Hollow", "Crossing", "End", "Lane"},
        Set.of(Biome.GRASSLAND, Biome.FOREST, Biome.HILLS, Biome.RIVER)
    ),

    FROSTBOUND(
        new String[]{"z", "v", "kr", "sl", "st", "pr", "gr", "ch", "d", "b"},
        new String[]{"o", "a", "e", "i", "u", "ov", "ey", "av"},
        new String[]{"sk", "v", "grad", "mir", "nov", "kov", "ek", "ich"},
        new String[]{"Ice", "Night", "Shadow", "Steel", "Bone", "Raven", "Bitter", "Dark", "Pale", "White"},
        new String[]{"Reach", "Spire", "Fall", "March", "Hold", "Crest", "Peak", "Deep", "Grip", "Ward"},
        new String[]{"frozen", "desolate", "stark", "bitter", "unyielding", "bleak", "crystalline"},
        new String[]{"Citadel", "Tomb", "Spire", "Gate", "Bastion", "Monolith", "Vault"},
        new String[]{"Grasp", "March", "Rest", "Fall", "Vigil", "Reach", "End"},
        Set.of(Biome.SNOW_PEAK, Biome.MOUNTAIN, Biome.TUNDRA)
    ),

    DEEPSTONE(
        new String[]{"d", "b", "g", "kh", "th", "gr", "dr", "z", "n", "m"},
        new String[]{"u", "o", "a", "or", "ur", "az", "un", "im"},
        new String[]{"k", "m", "n", "r", "grim", "dum", "rak", "rim", "gar"},
        new String[]{"Anvil", "Ore", "Coal", "Mithril", "Hammer", "Forge", "Iron", "Granite", "Obsidian", "Ruby"},
        new String[]{"Delve", "Deep", "Mine", "Hall", "Vault", "Forge", "Pit", "Gorge", "Hold", "Vein"},
        new String[]{"massive", "hewn", "echoing", "cavernous", "fortified", "grim", "unyielding"},
        new String[]{"Vault", "Forge", "Throne", "Delve", "Citadel", "Foundry", "Underhall"},
        new String[]{"Claim", "Delve", "Rest", "Deep", "Hold", "Strike", "Vein"},
        Set.of(Biome.MOUNTAIN, Biome.HILLS)
    );

    private final String[] onsets;
    private final String[] nuclei;
    private final String[] codas;
    private final String[] prefixRoots;
    private final String[] suffixRoots;
    private final String[] adjectives;
    private final String[] nouns;
    private final String[] landmarks;
    private final Set<Biome> biomeAffinity;

    Culture(String[] onsets, String[] nuclei, String[] codas,
            String[] prefixRoots, String[] suffixRoots,
            String[] adjectives, String[] nouns, String[] landmarks,
            Set<Biome> biomeAffinity) {
        this.onsets = onsets;
        this.nuclei = nuclei;
        this.codas = codas;
        this.prefixRoots = prefixRoots;
        this.suffixRoots = suffixRoots;
        this.adjectives = adjectives;
        this.nouns = nouns;
        this.landmarks = landmarks;
        this.biomeAffinity = biomeAffinity;
    }

    public String[] getOnsets() { return onsets.clone(); }
    public String[] getNuclei() { return nuclei.clone(); }
    public String[] getCodas() { return codas.clone(); }
    public String[] getPrefixRoots() { return prefixRoots.clone(); }
    public String[] getSuffixRoots() { return suffixRoots.clone(); }
    public String[] getAdjectives() { return adjectives.clone(); }
    public String[] getNouns() { return nouns.clone(); }
    public String[] getLandmarks() { return landmarks.clone(); }
    public Set<Biome> getBiomeAffinity() { return biomeAffinity; }
}
