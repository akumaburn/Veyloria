package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import net.time4j.PlainTimestamp;
import net.time4j.format.expert.PatternType;
import net.time4j.format.expert.ChronoFormatter;

import java.util.Locale;
import java.util.UUID;

import com.eonmux.chatllm.utils.Time4JHelper;

/**
 * Message edit history entity.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageEdit {
    private UUID id;
    private UUID messageId;
    private UUID editedBy;  // Aligned with database schema (was editorId)
    private String previousContent;
    private String newContent;
    private String editReason;
    private Integer editNumber; // Sequential edit number for this message
    private String editedAt;
    private String ipAddress;
    private String userAgent;
    
    private static final ChronoFormatter<PlainTimestamp> TS_FORMAT =
        ChronoFormatter.ofTimestampPattern("uuuu-MM-dd'T'HH:mm:ss", PatternType.CLDR, Locale.ROOT);

    // Relationships (internal use, not exposed to GraphQL)
    @org.eclipse.microprofile.graphql.Ignore
    private transient Message message;
    @org.eclipse.microprofile.graphql.Ignore
    private transient User editor;
    
    /**
     * Create an edit record for a message.
     */
    public static MessageEdit createEdit(Message message, String newContent, UUID editorId) {
        return MessageEdit.builder()
            .id(UUID.randomUUID())
            .messageId(message.getId())
            .editedBy(editorId)  // Using editedBy to match database
            .previousContent(message.getContent())
            .newContent(newContent)
            .editNumber(message.getEditCount() != null ? message.getEditCount() + 1 : 1)
            .editedAt(Time4JHelper.nowUTCString())
            .build();
    }
    
    // Compatibility methods for old field name
    public UUID getEditorId() {
        return editedBy;
    }
    
    public void setEditorId(UUID editorId) {
        this.editedBy = editorId;
    }
    
    /**
     * Get content diff length.
     */
    public int getDiffLength() {
        if (previousContent == null || newContent == null) return 0;
        return Math.abs(newContent.length() - previousContent.length());
    }
    
    /**
     * Check if this was a minor edit (small changes).
     */
    public boolean isMinorEdit() {
        if (previousContent == null || newContent == null) return false;
        
        // Consider it minor if less than 10% change or less than 10 characters
        int diffLength = getDiffLength();
        int avgLength = (previousContent.length() + newContent.length()) / 2;
        
        return diffLength < 10 || (avgLength > 0 && (double) diffLength / avgLength < 0.1);
    }
    
    /**
     * Get time since edit.
     */
    public String getTimeSinceEdit() {
        if (editedAt == null) return "Unknown";
        
        PlainTimestamp now = Time4JHelper.nowUTC();
        long minutes;
        try {
            minutes = java.time.Duration.between(
                java.time.LocalDateTime.from(TS_FORMAT.parse(editedAt)),
                java.time.LocalDateTime.from(now)
            ).toMinutes();
        } catch (Exception e) {
            return "Unknown";
        }
        
        if (minutes < 1) return "Just now";
        if (minutes < 60) return minutes + " minute" + (minutes != 1 ? "s" : "") + " ago";
        
        long hours = minutes / 60;
        if (hours < 24) return hours + " hour" + (hours != 1 ? "s" : "") + " ago";
        
        long days = hours / 24;
        if (days < 30) return days + " day" + (days != 1 ? "s" : "") + " ago";
        
        long months = days / 30;
        if (months < 12) return months + " month" + (months != 1 ? "s" : "") + " ago";
        
        long years = months / 12;
        return years + " year" + (years != 1 ? "s" : "") + " ago";
    }
    
    /**
     * Check if edit was recent (within last 5 minutes).
     */
    public boolean isRecentEdit() {
        if (editedAt == null) return false;
        return Time4JHelper.nowUTC().minus(5, net.time4j.ClockUnit.MINUTES).toString().compareTo(editedAt) < 0;
    }
    
    /**
     * Apply this edit to a message.
     */
    public void applyToMessage(Message message) {
        if (message != null && message.getId().equals(messageId)) {
            message.setContent(newContent);
            message.markAsEdited();
            message.setUpdatedAt(editedAt);
        }
    }
    
    /**
     * Revert this edit on a message.
     */
    public void revertOnMessage(Message message) {
        if (message != null && message.getId().equals(messageId)) {
            message.setContent(previousContent);
            message.setUpdatedAt(Time4JHelper.nowUTCString());
            // Note: We don't decrement edit count as that would lose history
        }
    }
}