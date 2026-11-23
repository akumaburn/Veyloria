package com.eonmux.chatllm.models;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.UUID;

import com.eonmux.chatllm.utils.Time4JHelper;

/**
 * Attachment entity for files attached to messages.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Attachment {
    private UUID id;
    private UUID messageId;
    private String filename;  // Aligned with database schema
    private String filePath;
    private String mimeType;
    @org.eclipse.microprofile.graphql.Name("size")
    private Long fileSize;
    private String url;
    private String thumbnailUrl;
    private AttachmentType type;
    @Builder.Default
    private AttachmentStatus status = AttachmentStatus.PENDING;
    private String checksum;
    private Integer width; // For images
    private Integer height; // For images
    private Integer duration; // For audio/video in seconds
    private String metadata; // JSON string for additional metadata
    private String createdAt;
    private String uploadedAt;
    private String expiresAt;
    
    /**
     * Attachment types.
     */
    public enum AttachmentType {
        IMAGE,
        VIDEO,
        AUDIO,
        DOCUMENT,
        CODE,
        ARCHIVE,
        OTHER;
        
        /**
         * Determine type from MIME type.
         */
        public static AttachmentType fromMimeType(String mimeType) {
            if (mimeType == null) return OTHER;
            
            String lowerMime = mimeType.toLowerCase();
            if (lowerMime.startsWith("image/")) return IMAGE;
            if (lowerMime.startsWith("video/")) return VIDEO;
            if (lowerMime.startsWith("audio/")) return AUDIO;
            if (lowerMime.contains("pdf") || lowerMime.contains("document") || 
                lowerMime.contains("msword") || lowerMime.contains("officedocument")) {
                return DOCUMENT;
            }
            if (lowerMime.contains("text/") || lowerMime.contains("application/json") ||
                lowerMime.contains("application/xml") || lowerMime.contains("javascript")) {
                return CODE;
            }
            if (lowerMime.contains("zip") || lowerMime.contains("tar") || 
                lowerMime.contains("rar") || lowerMime.contains("7z")) {
                return ARCHIVE;
            }
            return OTHER;
        }
    }
    
    /**
     * Attachment status.
     */
    public enum AttachmentStatus {
        PENDING,
        UPLOADING,
        PROCESSING,
        READY,
        FAILED,
        DELETED
    }
    
    /**
     * Check if attachment is an image.
     */
    public boolean isImage() {
        return type == AttachmentType.IMAGE;
    }
    
    /**
     * Check if attachment is ready for use.
     */
    public boolean isReady() {
        return status == AttachmentStatus.READY;
    }
    
    /**
     * Check if attachment has expired.
     */
    public boolean isExpired() {
        return expiresAt != null && Time4JHelper.nowUTCString().compareTo(expiresAt) > 0;
    }
    
    /**
     * Get human-readable file size.
     */
    public String getHumanReadableSize() {
        if (fileSize == null) return "Unknown";
        
        long size = fileSize;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        
        return size + " " + units[unitIndex];
    }
    
    /**
     * Get file extension.
     */
    public String getFileExtension() {
        if (filename == null) return null;
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot + 1).toLowerCase();
        }
        return null;
    }
    
    /**
     * Check if attachment is viewable inline.
     */
    public boolean isInlineViewable() {
        return type == AttachmentType.IMAGE || 
               type == AttachmentType.VIDEO ||
               type == AttachmentType.AUDIO ||
               (type == AttachmentType.DOCUMENT && "pdf".equals(getFileExtension())) ||
               type == AttachmentType.CODE;
    }
    
    /**
     * Mark as uploaded.
     */
    public void markAsUploaded() {
        this.status = AttachmentStatus.READY;
        this.uploadedAt = Time4JHelper.nowUTCString();
    }
    
    /**
     * Mark as failed.
     */
    public void markAsFailed() {
        this.status = AttachmentStatus.FAILED;
    }
    
    /**
     * Set type from MIME type.
     */
    public void setTypeFromMimeType(String mimeType) {
        this.mimeType = mimeType;
        this.type = AttachmentType.fromMimeType(mimeType);
    }
}