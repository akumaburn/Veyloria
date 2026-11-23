package com.eonmux.chatllm.services;

import com.eonmux.chatllm.models.Character;
import com.eonmux.chatllm.models.Conversation;
import com.eonmux.chatllm.models.ExportData;
import com.eonmux.chatllm.models.Message;
import com.eonmux.chatllm.models.Settings;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class DataExportService {

    public String format(ExportData data, String format) {
        return switch (format.toLowerCase()) {
            case "csv" -> toCsv(data);
            case "markdown" -> toMarkdown(data);
            case "txt" -> toTxt(data);
            default -> toJson(data);
        };
    }

    private String toJson(ExportData data) {
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(data);
    }

    public String toCsv(ExportData data) {
        StringBuilder sb = new StringBuilder();

        sb.append("=== CONVERSATIONS ===\n");
        sb.append("id,title,createdAt,messageCount,isGroupChat,isRoleplay\n");
        if (data.getConversations() != null) {
            for (Conversation c : data.getConversations()) {
                sb.append(escapeCsv(c.getId())).append(",");
                sb.append(escapeCsv(c.getTitle())).append(",");
                sb.append(escapeCsv(c.getCreatedAt() != null ? c.getCreatedAt() : "")).append(",");
                sb.append(c.getMessageCount() != null ? c.getMessageCount() : 0).append(",");
                sb.append(c.getIsGroupChat() != null && c.getIsGroupChat()).append(",");
                sb.append(c.getIsRoleplay() != null && c.getIsRoleplay()).append("\n");
            }
        }

        sb.append("\n=== MESSAGES ===\n");
        sb.append("id,conversationId,role,displayName,content,createdAt\n");
        if (data.getMessages() != null) {
            for (Message m : data.getMessages()) {
                sb.append(escapeCsv(m.getId())).append(",");
                sb.append(escapeCsv(m.getConversationId())).append(",");
                sb.append(escapeCsv(m.getRoleString())).append(",");
                sb.append(escapeCsv(m.getEffectiveDisplayName())).append(",");
                sb.append(escapeCsv(m.getDecodedContent())).append(",");
                sb.append(escapeCsv(m.getCreatedAt() != null ? m.getCreatedAt() : "")).append("\n");
            }
        }

        sb.append("\n=== CHARACTERS ===\n");
        sb.append("id,name,description,personality,category\n");
        if (data.getCharacters() != null) {
            for (Character ch : data.getCharacters()) {
                sb.append(escapeCsv(ch.getId())).append(",");
                sb.append(escapeCsv(ch.getName())).append(",");
                sb.append(escapeCsv(ch.getDescription())).append(",");
                sb.append(escapeCsv(ch.getPersonality())).append(",");
                sb.append(escapeCsv(ch.getCategory())).append("\n");
            }
        }

        sb.append("\n=== SETTINGS ===\n");
        if (data.getSettings() != null) {
            sb.append("exportFormat,").append(escapeCsv(data.getSettings().getPrivacy() != null
                ? data.getSettings().getPrivacy().getExportFormat() : "")).append("\n");
        }

        return sb.toString();
    }

    public String toMarkdown(ExportData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Veyloria Data Export\n\n");
        sb.append("**Export Date:** ").append(data.getExportDate() != null ? data.getExportDate() : "N/A").append("\n");
        sb.append("**Version:** ").append(data.getVersion() != null ? data.getVersion() : "N/A").append("\n\n");
        sb.append("---\n\n");

        if (data.getConversations() != null && !data.getConversations().isEmpty()) {
            sb.append("## Conversations (").append(data.getConversations().size()).append(")\n\n");
            for (Conversation c : data.getConversations()) {
                sb.append("### ").append(escapeMd(c.getTitle())).append("\n\n");
                sb.append("- **ID:** `").append(c.getId()).append("`\n");
                sb.append("- **Created:** ").append(c.getCreatedAt() != null ? c.getCreatedAt() : "N/A").append("\n");
                sb.append("- **Messages:** ").append(c.getMessageCount() != null ? c.getMessageCount() : 0).append("\n");
                sb.append("- **Type:** ").append(Boolean.TRUE.equals(c.getIsGroupChat()) ? "Group" : "Direct").append("\n");
                if (Boolean.TRUE.equals(c.getIsRoleplay())) {
                    sb.append("- **Roleplay:** Yes\n");
                }
                sb.append("\n");
            }
        }

        if (data.getMessages() != null && !data.getMessages().isEmpty()) {
            sb.append("## Messages (").append(data.getMessages().size()).append(")\n\n");
            for (Message m : data.getMessages()) {
                sb.append("> **").append(escapeMd(m.getEffectiveDisplayName())).append("** ");
                sb.append("(").append(m.getRoleString()).append(") ");
                sb.append("*").append(m.getCreatedAt() != null ? m.getCreatedAt() : "").append("*\n\n");
                sb.append(escapeMd(m.getDecodedContent())).append("\n\n");
            }
        }

        if (data.getCharacters() != null && !data.getCharacters().isEmpty()) {
            sb.append("## Characters (").append(data.getCharacters().size()).append(")\n\n");
            for (Character ch : data.getCharacters()) {
                sb.append("### ").append(escapeMd(ch.getName())).append("\n\n");
                if (ch.getDescription() != null && !ch.getDescription().isEmpty()) {
                    sb.append("**Description:** ").append(escapeMd(ch.getDescription())).append("\n\n");
                }
                if (ch.getPersonality() != null && !ch.getPersonality().isEmpty()) {
                    sb.append("**Personality:** ").append(escapeMd(ch.getPersonality())).append("\n\n");
                }
                if (ch.getBackstory() != null && !ch.getBackstory().isEmpty()) {
                    sb.append("**Backstory:** ").append(escapeMd(ch.getBackstory())).append("\n\n");
                }
                sb.append("- **Category:** ").append(ch.getCategory() != null ? ch.getCategory() : "General").append("\n");
                sb.append("- **Public:** ").append(Boolean.TRUE.equals(ch.getIsPublic()) ? "Yes" : "No").append("\n");
                sb.append("\n");
            }
        }

        if (data.getSettings() != null) {
            sb.append("## Settings\n\n");
            sb.append("```json\n");
            sb.append(new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(data.getSettings()));
            sb.append("\n```\n\n");
        }

        return sb.toString();
    }

    public String toTxt(ExportData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("Veyloria Data Export\n");
        sb.append("===================\n");
        sb.append("Export Date: ").append(data.getExportDate() != null ? data.getExportDate() : "N/A").append("\n");
        sb.append("Version: ").append(data.getVersion() != null ? data.getVersion() : "N/A").append("\n\n");

        if (data.getConversations() != null) {
            sb.append("Conversations (").append(data.getConversations().size()).append(")\n");
            sb.append("------------------------------------------------\n\n");
            for (Conversation c : data.getConversations()) {
                sb.append("Title: ").append(c.getTitle()).append("\n");
                sb.append("ID: ").append(c.getId()).append("\n");
                sb.append("Created: ").append(c.getCreatedAt() != null ? c.getCreatedAt() : "N/A").append("\n");
                sb.append("Messages: ").append(c.getMessageCount() != null ? c.getMessageCount() : 0).append("\n");
                sb.append("Type: ").append(Boolean.TRUE.equals(c.getIsGroupChat()) ? "Group" : "Direct").append("\n");
                sb.append("\n");
            }
        }

        if (data.getCharacters() != null) {
            sb.append("Characters (").append(data.getCharacters().size()).append(")\n");
            sb.append("------------------------------------------------\n\n");
            for (Character ch : data.getCharacters()) {
                sb.append("Name: ").append(ch.getName()).append("\n");
                sb.append("Description: ").append(nullToEmpty(ch.getDescription())).append("\n");
                sb.append("Personality: ").append(nullToEmpty(ch.getPersonality())).append("\n");
                sb.append("Category: ").append(ch.getCategory() != null ? ch.getCategory() : "General").append("\n");
                sb.append("\n");
            }
        }

        if (data.getMessages() != null) {
            sb.append("Messages (").append(data.getMessages().size()).append(")\n");
            sb.append("------------------------------------------------\n\n");
            for (Message m : data.getMessages()) {
                sb.append("[").append(m.getCreatedAt() != null ? m.getCreatedAt() : "").append("] ");
                sb.append(m.getEffectiveDisplayName()).append(" (").append(m.getRoleString()).append("):\n");
                sb.append(m.getDecodedContent()).append("\n\n");
            }
        }

        if (data.getSettings() != null) {
            sb.append("Settings\n");
            sb.append("------------------------------------------------\n\n");
            sb.append(new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(data.getSettings()));
            sb.append("\n");
        }

        return sb.toString();
    }

    private static String escapeCsv(Object value) {
        if (value == null) return "";
        String s = value.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String escapeMd(String value) {
        if (value == null) return "";
        return value.replaceAll("([*_~`>#\\-\\[\\]()])", "\\\\$1");
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
