package com.eonmux.chatllm.llm;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * GGUF (GGML Universal File) parser for reading model metadata.
 * Implements reactive parsing for Vert.x compatibility.
 */
@Slf4j
public class GGUFParser {
    
    private static final int GGUF_MAGIC = 0x46554747; // "GGUF" in little-endian
    private static final int GGUF_VERSION = 3;
    
    // GGUF value types
    private static final int GGUF_TYPE_UINT8 = 0;
    private static final int GGUF_TYPE_INT8 = 1;
    private static final int GGUF_TYPE_UINT16 = 2;
    private static final int GGUF_TYPE_INT16 = 3;
    private static final int GGUF_TYPE_UINT32 = 4;
    private static final int GGUF_TYPE_INT32 = 5;
    private static final int GGUF_TYPE_FLOAT32 = 6;
    private static final int GGUF_TYPE_BOOL = 7;
    private static final int GGUF_TYPE_STRING = 8;
    private static final int GGUF_TYPE_ARRAY = 9;
    private static final int GGUF_TYPE_UINT64 = 10;
    private static final int GGUF_TYPE_INT64 = 11;
    private static final int GGUF_TYPE_FLOAT64 = 12;
    
    private final Vertx vertx;
    
    public GGUFParser(Vertx vertx) {
        this.vertx = vertx;
    }
    
    // Increase buffer to 4MB to handle large tokenizer metadata
    private static final int METADATA_BUFFER_SIZE = 4 * 1024 * 1024;

    /**
     * Parse a GGUF file and extract metadata.
     *
     * @param filePath Path to the GGUF file
     * @return Uni containing the parsed metadata
     */
    public Uni<JsonObject> parseFile(String filePath) {
        return Uni.createFrom().item(() -> {
            try {
                // Read first 4MB for metadata
                java.io.File file = new java.io.File(filePath);
                if (!file.exists() || !file.canRead()) {
                    throw new RuntimeException("Failed to open file: " + filePath);
                }

                int readSize = (int) Math.min(file.length(), METADATA_BUFFER_SIZE);
                byte[] bytes = new byte[readSize];
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                    raf.readFully(bytes);
                }

                io.vertx.core.buffer.Buffer buffer = io.vertx.core.buffer.Buffer.buffer(bytes);
                return parseGGUFHeader(buffer);
            } catch (Exception e) {
                log.debug("Failed to parse GGUF header completely: {}", e.getMessage());
                throw new RuntimeException("Failed to parse GGUF header: " + e.getMessage(), e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
    
    /**
     * Parse GGUF header from buffer.
     * Handles buffer exhaustion gracefully by returning partial data.
     */
    private JsonObject parseGGUFHeader(io.vertx.core.buffer.Buffer buffer) {
        ByteBuffer bb = ByteBuffer.wrap(buffer.getBytes());
        bb.order(ByteOrder.LITTLE_ENDIAN);

        JsonObject result = new JsonObject();

        // Read magic number
        if (bb.remaining() < 4) {
            throw new IllegalArgumentException("Buffer too small for GGUF magic");
        }
        int magic = bb.getInt();
        if (magic != GGUF_MAGIC) {
            throw new IllegalArgumentException("Invalid GGUF magic number: " + Integer.toHexString(magic));
        }

        // Read version
        if (bb.remaining() < 4) {
            throw new IllegalArgumentException("Buffer too small for GGUF version");
        }
        int version = bb.getInt();
        result.put("version", version);

        if (version < 2 || version > 3) {
            throw new IllegalArgumentException("Unsupported GGUF version: " + version);
        }

        // Read tensor count
        if (bb.remaining() < 8) {
            return result; // Return what we have
        }
        long tensorCount = bb.getLong();
        result.put("tensorCount", tensorCount);

        // Read metadata KV count
        if (bb.remaining() < 8) {
            return result; // Return what we have
        }
        long metadataKVCount = bb.getLong();
        result.put("metadataCount", metadataKVCount);

        // Parse metadata key-value pairs
        JsonObject metadata = new JsonObject();
        Map<String, Object> kvPairs = new HashMap<>();

        for (int i = 0; i < metadataKVCount; i++) {
            // Check if we have enough data to continue
            if (bb.remaining() < 12) { // Minimum: 8 (string length) + 4 (value type)
                log.debug("Buffer exhausted after reading {} of {} metadata entries", i, metadataKVCount);
                break;
            }

            try {
                // Read key
                String key = readStringSafe(bb);
                if (key == null) {
                    log.debug("Buffer exhausted while reading key at entry {}", i);
                    break;
                }

                // Read value type
                if (bb.remaining() < 4) {
                    log.debug("Buffer exhausted while reading value type at entry {}", i);
                    break;
                }
                int valueType = bb.getInt();

                // Read value based on type
                Object value = readValueSafe(bb, valueType);
                if (value != null) {
                    kvPairs.put(key, value);
                    metadata.put(key, value);
                }
            } catch (Exception e) {
                log.debug("Error parsing metadata entry {}: {}", i, e.getMessage());
                break; // Stop parsing but return what we have
            }
        }

        result.put("metadata", metadata);

        // Extract important model parameters
        JsonObject modelInfo = extractModelInfo(kvPairs);
        result.put("modelInfo", modelInfo);

        return result;
    }

    /**
     * Read a string safely, returning null if buffer is exhausted.
     */
    private String readStringSafe(ByteBuffer bb) {
        if (bb.remaining() < 8) {
            return null;
        }
        long length = bb.getLong();
        if (length > Integer.MAX_VALUE || length < 0 || length > bb.remaining()) {
            return null;
        }

        byte[] bytes = new byte[(int) length];
        bb.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Read a value safely, returning null if buffer is exhausted.
     */
    private Object readValueSafe(ByteBuffer bb, int valueType) {
        try {
            return readValue(bb, valueType);
        } catch (Exception e) {
            log.debug("Error reading value of type {}: {}", valueType, e.getMessage());
            return null;
        }
    }
    
    /**
     * Read a string from the buffer.
     */
    private String readString(ByteBuffer bb) {
        long length = bb.getLong();
        if (length > Integer.MAX_VALUE || length < 0) {
            throw new IllegalArgumentException("Invalid string length: " + length);
        }
        
        byte[] bytes = new byte[(int) length];
        bb.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    
    /**
     * Read a value based on its type.
     */
    private Object readValue(ByteBuffer bb, int valueType) {
        switch (valueType) {
            case GGUF_TYPE_UINT8:
                return bb.get() & 0xFF;
            
            case GGUF_TYPE_INT8:
                return bb.get();
            
            case GGUF_TYPE_UINT16:
                return bb.getShort() & 0xFFFF;
            
            case GGUF_TYPE_INT16:
                return bb.getShort();
            
            case GGUF_TYPE_UINT32:
                return bb.getInt() & 0xFFFFFFFFL;
            
            case GGUF_TYPE_INT32:
                return bb.getInt();
            
            case GGUF_TYPE_FLOAT32:
                return bb.getFloat();
            
            case GGUF_TYPE_BOOL:
                return bb.get() != 0;
            
            case GGUF_TYPE_STRING:
                return readString(bb);
            
            case GGUF_TYPE_ARRAY:
                return readArray(bb);
            
            case GGUF_TYPE_UINT64:
            case GGUF_TYPE_INT64:
                return bb.getLong();
            
            case GGUF_TYPE_FLOAT64:
                return bb.getDouble();
            
            default:
                log.warn("Unknown GGUF value type: {}", valueType);
                return null;
        }
    }
    
    /**
     * Read an array value.
     * For very large arrays (like tokenizer tokens/merges), we skip over them
     * since we only need a few elements and parsing 150k+ items is wasteful.
     */
    private JsonArray readArray(ByteBuffer bb) {
        if (bb.remaining() < 12) {
            log.debug("Not enough bytes remaining for array header");
            return new JsonArray();
        }

        int arrayType = bb.getInt();
        long arrayLength = bb.getLong();

        if (arrayLength < 0 || arrayLength > Integer.MAX_VALUE) {
            log.warn("Invalid array length: {}", arrayLength);
            return new JsonArray();
        }

        // For very large arrays (like tokenizer tokens with 150k+ elements),
        // skip them entirely to avoid memory issues and parsing delays
        if (arrayLength > 1000) {
            log.debug("Skipping large array with {} elements of type {}", arrayLength, arrayType);
            if (!skipArrayElementsSafe(bb, arrayType, (int) arrayLength)) {
                log.debug("Could not skip all array elements, buffer exhausted");
            }
            return new JsonArray(); // Return empty array
        }

        JsonArray array = new JsonArray();

        for (int i = 0; i < arrayLength; i++) {
            if (bb.remaining() < 1) {
                log.debug("Buffer exhausted while reading array");
                break;
            }
            Object value = readValue(bb, arrayType);
            if (value != null) {
                array.add(value);
            }
        }

        return array;
    }

    /**
     * Skip over a value of the given type without parsing it.
     * Returns true if skip was successful, false if buffer underflow would occur.
     */
    private boolean skipValue(ByteBuffer bb, int valueType) {
        try {
            switch (valueType) {
                case GGUF_TYPE_UINT8:
                case GGUF_TYPE_INT8:
                case GGUF_TYPE_BOOL:
                    if (bb.remaining() < 1) return false;
                    bb.get(); // 1 byte
                    break;

                case GGUF_TYPE_UINT16:
                case GGUF_TYPE_INT16:
                    if (bb.remaining() < 2) return false;
                    bb.getShort(); // 2 bytes
                    break;

                case GGUF_TYPE_UINT32:
                case GGUF_TYPE_INT32:
                case GGUF_TYPE_FLOAT32:
                    if (bb.remaining() < 4) return false;
                    bb.getInt(); // 4 bytes
                    break;

                case GGUF_TYPE_UINT64:
                case GGUF_TYPE_INT64:
                case GGUF_TYPE_FLOAT64:
                    if (bb.remaining() < 8) return false;
                    bb.getLong(); // 8 bytes
                    break;

                case GGUF_TYPE_STRING:
                    if (bb.remaining() < 8) return false;
                    long strLen = bb.getLong();
                    if (strLen > 0 && strLen <= bb.remaining()) {
                        bb.position(bb.position() + (int) strLen);
                    } else if (strLen > bb.remaining()) {
                        return false; // Not enough data
                    }
                    break;

                case GGUF_TYPE_ARRAY:
                    if (bb.remaining() < 12) return false;
                    int nestedType = bb.getInt();
                    long nestedLen = bb.getLong();
                    if (nestedLen > 0 && nestedLen < Integer.MAX_VALUE) {
                        if (!skipArrayElementsSafe(bb, nestedType, (int) nestedLen)) {
                            return false;
                        }
                    }
                    break;

                default:
                    log.warn("Unknown GGUF value type for skipping: {}", valueType);
                    return false;
            }
            return true;
        } catch (Exception e) {
            log.debug("Buffer underflow while skipping value type {}", valueType);
            return false;
        }
    }

    /**
     * Skip array elements safely, returning false if buffer runs out.
     */
    private boolean skipArrayElementsSafe(ByteBuffer bb, int elementType, int count) {
        for (int i = 0; i < count; i++) {
            if (!skipValue(bb, elementType)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Extract important model information from metadata.
     */
    private JsonObject extractModelInfo(Map<String, Object> metadata) {
        JsonObject info = new JsonObject();
        
        // Model architecture
        info.put("architecture", getMetadataString(metadata, "general.architecture", "unknown"));
        
        // Model name
        info.put("name", getMetadataString(metadata, "general.name", "Unknown Model"));
        
        // Model type
        info.put("type", getMetadataString(metadata, "general.type", "unknown"));
        
        // Context size
        info.put("contextSize", getMetadataLong(metadata, "llama.context_length", 2048L));
        
        // Embedding size
        info.put("embeddingSize", getMetadataLong(metadata, "llama.embedding_length", 4096L));
        
        // Block count (layers)
        info.put("blockCount", getMetadataLong(metadata, "llama.block_count", 32L));
        
        // Head count
        info.put("headCount", getMetadataLong(metadata, "llama.attention.head_count", 32L));
        
        // Head count KV
        info.put("headCountKV", getMetadataLong(metadata, "llama.attention.head_count_kv", 32L));
        
        // Vocabulary size
        info.put("vocabSize", getMetadataLong(metadata, "tokenizer.ggml.model", 32000L));
        
        // RoPE dimensions
        info.put("ropeDimCount", getMetadataLong(metadata, "llama.rope.dimension_count", 128L));
        
        // Feed forward length
        info.put("feedForwardLength", getMetadataLong(metadata, "llama.feed_forward_length", 11008L));
        
        // Quantization
        info.put("quantization", getMetadataString(metadata, "general.quantization_version", "unknown"));
        
        // File type
        info.put("fileType", getMetadataString(metadata, "general.file_type", "unknown"));
        
        // Template
        String template = getMetadataString(metadata, "tokenizer.chat_template", null);
        if (template != null) {
            info.put("chatTemplate", template);
            info.put("templateType", detectTemplateType(template));
        }
        
        // BOS/EOS tokens
        info.put("bosToken", getMetadataString(metadata, "tokenizer.ggml.bos_token", "<s>"));
        info.put("eosToken", getMetadataString(metadata, "tokenizer.ggml.eos_token", "</s>"));
        
        // Calculate memory requirements
        long parameters = calculateParameters(info);
        info.put("parameters", parameters);
        info.put("estimatedMemoryMB", estimateMemoryUsage(parameters, info.getString("quantization")));
        
        return info;
    }
    
    /**
     * Get string value from metadata.
     */
    private String getMetadataString(Map<String, Object> metadata, String key, String defaultValue) {
        Object value = metadata.get(key);
        return value instanceof String ? (String) value : defaultValue;
    }
    
    /**
     * Get long value from metadata.
     */
    private Long getMetadataLong(Map<String, Object> metadata, String key, Long defaultValue) {
        Object value = metadata.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return defaultValue;
    }
    
    /**
     * Detect template type from chat template string.
     */
    private String detectTemplateType(String template) {
        if (template == null) return "unknown";
        
        template = template.toLowerCase();
        
        if (template.contains("[inst]") && template.contains("[/inst]")) {
            return "llama2";
        } else if (template.contains("<|im_start|>") && template.contains("<|im_end|>")) {
            return "chatml";
        } else if (template.contains("### instruction:")) {
            return "alpaca";
        } else if (template.contains("<|user|>") && template.contains("<|assistant|>")) {
            return "zephyr";
        } else if (template.contains("<|system|>")) {
            return "llama3";
        } else if (template.contains("user:") && template.contains("assistant:")) {
            return "vicuna";
        } else if (template.contains("[gpt]")) {
            return "gpt4all";
        } else if (template.contains("<|prompter|>") && template.contains("<|assistant|>")) {
            return "openassistant";
        }
        
        return "generic";
    }
    
    /**
     * Calculate approximate number of parameters.
     */
    private long calculateParameters(JsonObject info) {
        long embeddingSize = info.getLong("embeddingSize", 4096L);
        long blockCount = info.getLong("blockCount", 32L);
        long ffLength = info.getLong("feedForwardLength", 11008L);
        long vocabSize = info.getLong("vocabSize", 32000L);
        
        // Approximate calculation
        long embeddingParams = vocabSize * embeddingSize;
        long attentionParams = blockCount * (4 * embeddingSize * embeddingSize);
        long ffParams = blockCount * (2 * embeddingSize * ffLength);
        long normParams = blockCount * (2 * embeddingSize);
        
        return embeddingParams + attentionParams + ffParams + normParams;
    }
    
    /**
     * Estimate memory usage based on parameters and quantization.
     */
    private long estimateMemoryUsage(long parameters, String quantization) {
        float bytesPerParam;
        
        if (quantization == null) quantization = "";
        quantization = quantization.toLowerCase();
        
        if (quantization.contains("q4_0") || quantization.contains("q4_k")) {
            bytesPerParam = 0.5f; // 4-bit quantization
        } else if (quantization.contains("q5")) {
            bytesPerParam = 0.625f; // 5-bit quantization
        } else if (quantization.contains("q6")) {
            bytesPerParam = 0.75f; // 6-bit quantization
        } else if (quantization.contains("q8")) {
            bytesPerParam = 1.0f; // 8-bit quantization
        } else if (quantization.contains("f16")) {
            bytesPerParam = 2.0f; // 16-bit float
        } else if (quantization.contains("f32")) {
            bytesPerParam = 4.0f; // 32-bit float
        } else {
            bytesPerParam = 1.0f; // Default to 8-bit
        }
        
        // Convert to MB and add overhead for context
        long memoryMB = (long) ((parameters * bytesPerParam) / (1024 * 1024));
        
        // Add overhead for context and runtime
        memoryMB += 512; // Base overhead
        
        return memoryMB;
    }
    
    /**
     * Calculate optimal GPU layers based on available VRAM.
     */
    public static int calculateGPULayers(JsonObject modelInfo, long availableVRAM) {
        long totalMemory = modelInfo.getLong("estimatedMemoryMB", 4096L);
        long blockCount = modelInfo.getLong("blockCount", 32L);
        
        if (availableVRAM <= 0 || totalMemory <= 0 || blockCount <= 0) {
            return 0;
        }
        
        // Calculate memory per layer
        long memoryPerLayer = totalMemory / blockCount;
        
        // Leave some VRAM for context and overhead (20% buffer)
        long usableVRAM = (long) (availableVRAM * 0.8);
        
        // Calculate how many layers can fit
        int layers = (int) (usableVRAM / memoryPerLayer);
        
        // Cap at total block count
        layers = Math.min(layers, (int) blockCount);
        
        // Ensure at least 0
        layers = Math.max(0, layers);
        
        log.info("Calculated {} GPU layers for {} MB model with {} MB VRAM available",
            layers, totalMemory, availableVRAM);
        
        return layers;
    }
    
    /**
     * Validate if a file is a valid GGUF model.
     */
    public Uni<Boolean> isValidGGUF(String filePath) {
        return Uni.createFrom().item(() -> {
            try {
                java.io.File file = new java.io.File(filePath);
                if (!file.exists() || !file.canRead() || file.length() < 4) {
                    return false;
                }

                byte[] magicBytes = new byte[4];
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                    raf.readFully(magicBytes);
                }

                ByteBuffer bb = ByteBuffer.wrap(magicBytes);
                bb.order(ByteOrder.LITTLE_ENDIAN);
                int magic = bb.getInt();

                return magic == GGUF_MAGIC;
            } catch (Exception e) {
                return false;
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}