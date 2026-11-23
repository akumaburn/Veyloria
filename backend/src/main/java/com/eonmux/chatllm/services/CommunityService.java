package com.eonmux.chatllm.services;

import jakarta.enterprise.context.ApplicationScoped;
import com.eonmux.chatllm.utils.Time4JHelper;
import jakarta.inject.Inject;
import com.eonmux.chatllm.models.CommunityTemplate;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@Slf4j
public class CommunityService {
    @Inject
    Vertx vertx;
    @Inject
    Pool pool;

    public Uni<CommunityTemplate> submitTemplate(String templateId, String userId) {
        String id = UUID.randomUUID().toString();
        String insert = "INSERT INTO community_template (submissionId, templateId, userId) VALUES (?, ?, ?)";
        return pool.preparedQuery(insert)
            .execute(Tuple.of(id, templateId, userId))
            .map(v -> CommunityTemplate.builder()
                .submissionId(UUID.fromString(id))
                .templateId(templateId)
                .userId(UUID.fromString(userId))
                .status("pending")
                .build());
    }

    public Uni<List<CommunityTemplate>> getApprovedTemplates(Integer limit, Integer offset) {
        String query = "SELECT * FROM community_template WHERE status = 'approved' ORDER BY rating DESC, downloads DESC LIMIT ? OFFSET ?";
        return pool.preparedQuery(query)
            .execute(Tuple.of(limit != null ? limit : 20, offset != null ? offset : 0))
            .map(rows -> {
                List<CommunityTemplate> templates = new ArrayList<>();
                for (Row row : rows) templates.add(mapRowToCommunityTemplate(row));
                return templates;
            });
    }

    public Uni<CommunityTemplate> reviewSubmission(String submissionId, String reviewerId, String status) {
        String update = "UPDATE community_template SET status = ?, reviewedBy = ?, reviewedAt = NOW() WHERE submissionId = ?";
        return pool.preparedQuery(update)
            .execute(Tuple.of(status, reviewerId, submissionId))
            .chain(v -> pool.preparedQuery("SELECT * FROM community_template WHERE submissionId = ?")
                .execute(Tuple.of(submissionId))
                .map(rows -> rows.size() > 0 ? mapRowToCommunityTemplate(rows.iterator().next()) : null));
    }

    private CommunityTemplate mapRowToCommunityTemplate(Row row) {
        CommunityTemplate ct = new CommunityTemplate();
        ct.setSubmissionId(UUID.fromString(row.getString("submissionId")));
        ct.setTemplateId(row.getString("templateId"));
        ct.setUserId(UUID.fromString(row.getString("userId")));
        ct.setStatus(row.getString("status"));
        ct.setDownloads(row.getInteger("downloads"));
        ct.setRating(row.getDouble("rating"));
        ct.setRatingCount(row.getInteger("ratingCount"));
        ct.setSubmittedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("submittedAt")));
        ct.setReviewedAt(Time4JHelper.localDateTimeToString(row.getLocalDateTime("reviewedAt")));
        if (row.getString("reviewedBy") != null) ct.setReviewedBy(UUID.fromString(row.getString("reviewedBy")));
        return ct;
    }
}
