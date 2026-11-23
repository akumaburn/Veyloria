package com.eonmux.chatllm.utils;

import io.vertx.mutiny.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * DynamicUpdateBuilder - Fluent API for building dynamic UPDATE queries
 *
 * Eliminates 300+ lines of duplicate UPDATE query code across services.
 * Provides type-safe field setters with validation support.
 *
 * Usage Example:
 * <pre>
 * DynamicUpdateBuilder builder = new DynamicUpdateBuilder("user_settings");
 * builder.setString("theme", updates.getString("theme"), this::isValidTheme)
 *        .setFloat("llmTemperature", updates.getFloat("llmTemperature"), 0.0f, 2.0f)
 *        .setLocalDateTime("updatedAt", LocalDateTime.now(ZoneOffset.UTC));
 *
 * UpdateQuery query = builder.build("WHERE fk_userUUID = ?", userId);
 * if (!query.hasUpdates()) return Future.succeededFuture(current);
 * return mysqlPool.preparedQuery(query.sql()).execute(query.params());
 * </pre>
 */
public class DynamicUpdateBuilder {

    private final String tableName;
    private final List<String> setFields = new ArrayList<>();
    private final List<Object> params = new ArrayList<>();

    /**
     * UpdateQuery record containing the built SQL, parameters, and update flag
     */
    public record UpdateQuery(String sql, Tuple params, boolean hasUpdates) {}

    /**
     * Creates a new DynamicUpdateBuilder for the specified table
     *
     * @param tableName The name of the table to update
     */
    public DynamicUpdateBuilder(String tableName) {
        this.tableName = tableName;
    }

    /**
     * Sets a String field with optional validation
     *
     * @param fieldName The column name
     * @param value The value to set (null values are skipped)
     * @param validator Optional validator predicate (null skips validation)
     * @return this builder for chaining
     */
    public DynamicUpdateBuilder setString(String fieldName, String value, Predicate<String> validator) {
        if (value != null && (validator == null || validator.test(value))) {
            setFields.add(fieldName + " = ?");
            params.add(value);
        }
        return this;
    }

    /**
     * Sets a String field without validation
     *
     * @param fieldName The column name
     * @param value The value to set (null values are skipped)
     * @return this builder for chaining
     */
    public DynamicUpdateBuilder setString(String fieldName, String value) {
        return setString(fieldName, value, null);
    }

    /**
     * Sets a Boolean field
     *
     * @param fieldName The column name
     * @param value The value to set (null values are skipped)
     * @return this builder for chaining
     */
    public DynamicUpdateBuilder setBoolean(String fieldName, Boolean value) {
        if (value != null) {
            setFields.add(fieldName + " = ?");
            params.add(value);
        }
        return this;
    }

    /**
     * Sets an Integer field with optional range validation
     *
     * @param fieldName The column name
     * @param value The value to set (null values are skipped)
     * @param min Minimum value (inclusive, null skips check)
     * @param max Maximum value (inclusive, null skips check)
     * @return this builder for chaining
     */
    public DynamicUpdateBuilder setInteger(String fieldName, Integer value, Integer min, Integer max) {
        if (value != null) {
            if ((min == null || value >= min) && (max == null || value <= max)) {
                setFields.add(fieldName + " = ?");
                params.add(value);
            }
        }
        return this;
    }

    /**
     * Sets an Integer field without validation
     *
     * @param fieldName The column name
     * @param value The value to set (null values are skipped)
     * @return this builder for chaining
     */
    public DynamicUpdateBuilder setInteger(String fieldName, Integer value) {
        return setInteger(fieldName, value, null, null);
    }

    /**
     * Sets a Float field with optional range validation
     *
     * @param fieldName The column name
     * @param value The value to set (null values are skipped)
     * @param min Minimum value (inclusive, null skips check)
     * @param max Maximum value (inclusive, null skips check)
     * @return this builder for chaining
     */
    public DynamicUpdateBuilder setFloat(String fieldName, Float value, Float min, Float max) {
        if (value != null) {
            if ((min == null || value >= min) && (max == null || value <= max)) {
                setFields.add(fieldName + " = ?");
                params.add(value);
            }
        }
        return this;
    }

    /**
     * Sets a Float field without validation
     *
     * @param fieldName The column name
     * @param value The value to set (null values are skipped)
     * @return this builder for chaining
     */
    public DynamicUpdateBuilder setFloat(String fieldName, Float value) {
        return setFloat(fieldName, value, null, null);
    }

    /**
     * Sets a Double field with optional range validation
     *
     * @param fieldName The column name
     * @param value The value to set (null values are skipped)
     * @param min Minimum value (inclusive, null skips check)
     * @param max Maximum value (inclusive, null skips check)
     * @return this builder for chaining
     */
    public DynamicUpdateBuilder setDouble(String fieldName, Double value, Double min, Double max) {
        if (value != null) {
            if ((min == null || value >= min) && (max == null || value <= max)) {
                setFields.add(fieldName + " = ?");
                params.add(value);
            }
        }
        return this;
    }

    /**
     * Sets a Double field without validation
     *
     * @param fieldName The column name
     * @param value The value to set (null values are skipped)
     * @return this builder for chaining
     */
    public DynamicUpdateBuilder setDouble(String fieldName, Double value) {
        return setDouble(fieldName, value, null, null);
    }

    /**
     * Sets a LocalDateTime field (commonly used for updatedAt)
     *
     * @param fieldName The column name
     * @param value The value to set (null values are skipped)
     * @return this builder for chaining
     */
    public DynamicUpdateBuilder setLocalDateTime(String fieldName, LocalDateTime value) {
        if (value != null) {
            setFields.add(fieldName + " = ?");
            params.add(value);
        }
        return this;
    }

    /**
     * Sets a JSON field (stored as String)
     *
     * @param fieldName The column name
     * @param value The JSON string value (null values are skipped)
     * @return this builder for chaining
     */
    public DynamicUpdateBuilder setJson(String fieldName, String value) {
        if (value != null) {
            setFields.add(fieldName + " = ?");
            params.add(value);
        }
        return this;
    }

    /**
     * Sets a field using a custom validator predicate
     *
     * @param fieldName The column name
     * @param value The value to set (null values are skipped)
     * @param validator Validator predicate (value is added only if predicate returns true)
     * @return this builder for chaining
     */
    public <T> DynamicUpdateBuilder setCustom(String fieldName, T value, Predicate<T> validator) {
        if (value != null && validator.test(value)) {
            setFields.add(fieldName + " = ?");
            params.add(value);
        }
        return this;
    }

    /**
     * Builds the final UPDATE query with WHERE clause and additional parameters
     *
     * @param whereClause The WHERE clause (e.g., "WHERE id = ?")
     * @param whereParams Parameters for the WHERE clause
     * @return UpdateQuery containing SQL, parameters, and hasUpdates flag
     */
    public UpdateQuery build(String whereClause, Object... whereParams) {
        if (setFields.isEmpty()) {
            return new UpdateQuery("", Tuple.tuple(), false);
        }

        StringBuilder sql = new StringBuilder("UPDATE ")
            .append(tableName)
            .append(" SET ")
            .append(String.join(", ", setFields));

        if (whereClause != null && !whereClause.isBlank()) {
            sql.append(" ").append(whereClause);
        }

        // Add WHERE parameters to the tuple
        List<Object> allParams = new ArrayList<>(params);
        if (whereParams != null) {
            for (Object param : whereParams) {
                allParams.add(param);
            }
        }

        return new UpdateQuery(sql.toString(), Tuple.from(allParams), true);
    }

    /**
     * Builds the final UPDATE query without a WHERE clause
     * Useful for global updates (use with caution)
     *
     * @return UpdateQuery containing SQL, parameters, and hasUpdates flag
     */
    public UpdateQuery build() {
        return build(null);
    }

    /**
     * Checks if any fields have been set for update
     *
     * @return true if at least one field has been set
     */
    public boolean hasUpdates() {
        return !setFields.isEmpty();
    }

    /**
     * Gets the count of fields that will be updated
     *
     * @return number of fields set for update
     */
    public int getUpdateCount() {
        return setFields.size();
    }
}
