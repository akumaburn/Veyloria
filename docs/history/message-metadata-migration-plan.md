> **LEGACY DESIGN DOCUMENT** — This is a migration plan from an earlier schema cleanup effort. The `message` table now carries all generation metadata (promptTokens, completionTokens, model, timing) directly. Retained for historical reference only.

# Message Metadata Consolidation - Migration Plan

## Problem Statement

The Veyloria database schema has data duplication between `message` and `message_metadata` tables. This creates several issues:

1. **Data Inconsistency Risk**: Updates must be synchronized across two tables
2. **Complex UPDATE Logic**: Every metadata update requires two queries
3. **Database Bloat**: Same data stored twice
4. **Migration Burden**: Schema changes require updating both tables

## Duplicated Fields

| Field | message table | message_metadata table |
|-------|---------------|------------------------|
| tokens | ✅ (INT) | ✅ (token_count INT) |
| promptTokens | ✅ (INT) | ✅ (prompt_tokens INT) |
| completionTokens | ✅ (INT) | ✅ (completion_tokens INT) |
| promptEvalMs | ✅ (INT) | N/A |
| generationMs | ✅ (INT) | N/A |
| totalTimeMs | ✅ (INT) | N/A |
| tokensPerSecond | ✅ (DOUBLE) | N/A |
| processingTime | ✅ (INT) | ✅ (processing_time_ms INT) |
| model | ✅ (VARCHAR 100) | ✅ (model_used VARCHAR 255) |

Additionally, `message_metadata` has edit tracking fields that should belong in message_edit table.

## Recommended Solution: Option 1 - Keep Both Tables with Clear Separation

### Rationale
- **message** table: Core message data needed for display and retrieval (frequently accessed)
- **message_metadata** table: Extended metadata for analytics and advanced queries (infrequently accessed)

### Migration Strategy

#### Phase 1: Define Clear Ownership (No Schema Changes Yet)

**message** table owns:
- tokens, completionTokens, promptTokens (for quick display in UI)
- promptEvalMs, generationMs, totalTimeMs, tokensPerSecond (performance metrics)
- model (which LLM was used)
- All message content and flags

**message_metadata** table owns:
- LLM parameters: temperature, top_p, top_k, max_tokens, stop_sequences
- Cost tracking: total_cost
- Penalties: presence_penalty, frequency_penalty
- Advanced metadata: metadata JSON blob

#### Phase 2: Remove Duplicates from message_metadata

```sql
-- Backup table first!
CREATE TABLE message_metadata_backup AS SELECT * FROM message_metadata;

-- Remove duplicate columns from message_metadata
ALTER TABLE message_metadata
  DROP COLUMN token_count,
  DROP COLUMN processing_time_ms,
  DROP COLUMN model_used,
  DROP COLUMN prompt_tokens,
  DROP COLUMN completion_tokens;

-- Update message_metadata to reference message for these fields
-- (Application layer will join when needed)
```

#### Phase 3: Update Application Code

**Before:**
```java
// Update both tables (inconsistency risk!)
mysqlPool.preparedQuery("UPDATE message SET tokens = ? WHERE messageUUID = ?")
  .execute(Tuple.of(tokens, messageId));
mysqlPool.preparedQuery("UPDATE message_metadata SET token_count = ? WHERE message_id = ?")
  .execute(Tuple.of(tokens, messageId));
```

**After:**
```java
// Single update to message table
mysqlPool.preparedQuery("UPDATE message SET tokens = ? WHERE messageUUID = ?")
  .execute(Tuple.of(tokens, messageId));
// message_metadata doesn't need update - it references message
```

#### Phase 4: Update Queries to Join When Needed

**Before:**
```java
SELECT * FROM message_metadata WHERE message_id = ?
```

**After:**
```java
SELECT
  mm.*,
  m.tokens,
  m.promptTokens,
  m.completionTokens,
  m.model,
  m.processingTime
FROM message_metadata mm
JOIN message m ON mm.message_id = m.messageUUID
WHERE mm.message_id = ?
```

### Testing Plan

1. **Data Validation**: Ensure all duplicated values match between tables before migration
2. **Application Testing**: Run full test suite with joined queries
3. **Performance Testing**: Measure query performance impact (should be minimal with proper indexes)
4. **Rollback Plan**: Keep backup table for 30 days

### Timeline Estimate

- Phase 1 (Documentation): 1 day
- Phase 2 (Schema changes): 2 days (including testing)
- Phase 3 (Code updates): 3-5 days
- Phase 4 (Query optimization): 2 days
- **Total**: 8-10 days

---

## Alternative Solution: Option 2 - Merge into Single Table

### Rationale
Simplify by keeping all data in `message` table only.

### Migration Strategy

```sql
-- Add missing columns to message table
ALTER TABLE message
  ADD COLUMN temperature FLOAT,
  ADD COLUMN top_p FLOAT,
  ADD COLUMN top_k INT,
  ADD COLUMN max_tokens INT,
  ADD COLUMN stop_sequences TEXT,
  ADD COLUMN presence_penalty FLOAT,
  ADD COLUMN frequency_penalty FLOAT,
  ADD COLUMN total_cost DECIMAL(10, 6),
  ADD COLUMN metadata JSON;

-- Migrate data from message_metadata to message
UPDATE message m
JOIN message_metadata mm ON m.messageUUID = mm.message_id
SET
  m.temperature = mm.temperature,
  m.top_p = mm.top_p,
  m.top_k = mm.top_k,
  m.max_tokens = mm.max_tokens,
  m.stop_sequences = mm.stop_sequences,
  m.presence_penalty = mm.presence_penalty,
  m.frequency_penalty = mm.frequency_penalty,
  m.total_cost = mm.total_cost,
  m.metadata = mm.metadata;

-- Verify migration (should return 0)
SELECT COUNT(*) FROM message_metadata mm
LEFT JOIN message m ON mm.message_id = m.messageUUID
WHERE m.messageUUID IS NULL;

-- Drop message_metadata table (after verification)
DROP TABLE message_metadata;
```

### Pros
- Single source of truth
- No joins required
- Simpler queries

### Cons
- Larger `message` table (more storage per row)
- Less separation of concerns
- All fields loaded even when not needed

---

## Recommendation

**Use Option 1** (Keep Both Tables with Clear Separation) because:

1. ✅ Maintains performance for common queries (no joins for basic message display)
2. ✅ Separates frequently-accessed data from analytics data
3. ✅ Easier to scale (can shard metadata to separate database later)
4. ✅ Lower migration risk (can be done incrementally)

## Post-Migration Validation

```sql
-- Verify no data loss
SELECT COUNT(*) FROM message_metadata_backup
WHERE message_id NOT IN (SELECT messageUUID FROM message);
-- Should return 0

-- Verify metadata integrity
SELECT COUNT(*) FROM message
WHERE messageUUID NOT IN (SELECT message_id FROM message_metadata);
-- Should return count of messages without metadata (expected)

-- Performance check
EXPLAIN SELECT
  m.*,
  mm.temperature,
  mm.top_p
FROM message m
LEFT JOIN message_metadata mm ON m.messageUUID = mm.message_id
WHERE m.conversationId = 'test-uuid';
-- Should use indexes
```

## Rollback Plan

If issues arise post-migration:

```sql
-- Restore from backup
DROP TABLE message_metadata;
RENAME TABLE message_metadata_backup TO message_metadata;

-- Revert application code to previous version
git revert [migration-commit]
```

---

## Status

**Current State**: ⚠️ Data duplication exists
**Target State**: ✅ Clean separation with single source of truth per field
**Migration Status**: 📝 Planned (not yet executed)
**Estimated Risk**: 🟡 Medium (requires careful testing but rollback available)
