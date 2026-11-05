package com.disputepilot.drafts;

import com.disputepilot.domain.Citation;
import com.disputepilot.domain.Draft;
import com.disputepilot.domain.DraftStatus;
import com.disputepilot.domain.EvidenceItem;
import com.disputepilot.domain.LlmMode;
import com.disputepilot.domain.Offer;
import com.disputepilot.domain.Recommendation;
import com.disputepilot.domain.ValidationResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Draft rows. Versioned per dispute: a new draft supersedes the previous one. */
@Repository
public class DraftRepository {

    private static final String SELECT = """
            select id, dispute_id, version, status, recommendation, confidence, rationale,
                   evidence::text as evidence, notes, citations::text as citations,
                   missing_info::text as missing_info, offer::text as offer, validation::text as validation, llm_mode, provider, model,
                   prompt_version, input_tokens, output_tokens, latency_ms, edited_by_human, repair_attempted,
                   created_at
            from drafts
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public DraftRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Marks every live draft for this dispute SUPERSEDED. Returns how many were superseded. */
    public int supersedeAll(UUID disputeId) {
        return jdbc.sql("""
                update drafts set status = 'SUPERSEDED'
                where dispute_id = :disputeId and status in ('DRAFT', 'NEEDS_REVIEW')
                """).param("disputeId", disputeId).update();
    }

    public int nextVersion(UUID disputeId) {
        return jdbc.sql("select coalesce(max(version), 0) + 1 from drafts where dispute_id = :disputeId")
                .param("disputeId", disputeId).query(Integer.class).single();
    }

    public Draft insert(Draft draft) {
        jdbc.sql("""
                insert into drafts (id, dispute_id, version, status, recommendation, confidence, rationale,
                    evidence, notes, citations, missing_info, offer, validation, llm_mode, provider, model,
                    prompt_version, input_tokens, output_tokens, latency_ms, edited_by_human, repair_attempted)
                values (:id, :disputeId, :version, :status, :recommendation, :confidence, :rationale,
                    cast(:evidence as jsonb), :notes, cast(:citations as jsonb), cast(:missingInfo as jsonb),
                    cast(:offer as jsonb), cast(:validation as jsonb), :llmMode, :provider, :model, :promptVersion, :inputTokens,
                    :outputTokens, :latencyMs, :editedByHuman, :repairAttempted)
                """)
                .param("id", draft.id())
                .param("disputeId", draft.disputeId())
                .param("version", draft.version())
                .param("status", draft.status().name())
                .param("recommendation", draft.recommendation() == null ? "NEEDS_INFO"
                        : draft.recommendation().name())
                .param("confidence", draft.confidence())
                .param("rationale", draft.rationale() == null ? "" : draft.rationale())
                .param("evidence", json.writeValueAsString(draft.evidence()))
                .param("notes", draft.notes() == null ? "" : draft.notes())
                .param("citations", json.writeValueAsString(draft.citations()))
                .param("missingInfo", json.writeValueAsString(draft.missingInfo()))
                .param("offer", draft.offer() == null ? null : json.writeValueAsString(draft.offer()))
                .param("validation", json.writeValueAsString(draft.validation()))
                .param("llmMode", draft.llmMode() == null ? LlmMode.RECORDED.dbValue() : draft.llmMode().dbValue())
                .param("provider", draft.provider())
                .param("model", draft.model())
                .param("promptVersion", draft.promptVersion())
                .param("inputTokens", draft.inputTokens())
                .param("outputTokens", draft.outputTokens())
                .param("latencyMs", draft.latencyMs())
                .param("editedByHuman", draft.editedByHuman())
                .param("repairAttempted", draft.repairAttempted())
                .update();
        return find(draft.id()).orElseThrow();
    }

    public Optional<Draft> find(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(this::map).optional();
    }

    /** The newest draft for a dispute that has not been superseded. */
    public Optional<Draft> findLatest(UUID disputeId) {
        return jdbc.sql(SELECT + """
                 where dispute_id = :disputeId and status <> 'SUPERSEDED'
                 order by version desc limit 1
                """).param("disputeId", disputeId).query(this::map).optional();
    }

    /** True when a draft exists for this dispute that a merchant could still act on. */
    public boolean hasLiveDraft(UUID disputeId) {
        return jdbc.sql("""
                select count(*) from drafts
                where dispute_id = :disputeId and status in ('DRAFT', 'NEEDS_REVIEW', 'APPROVED', 'SUBMITTED')
                """).param("disputeId", disputeId).query(Integer.class).single() > 0;
    }

    /** Saves a human edit: notes, evidence, offer and the re-run validation. */
    public Draft updateEdited(UUID id, String notes, List<EvidenceItem> evidence, Offer offer,
                              ValidationResult validation, DraftStatus status) {
        jdbc.sql("""
                update drafts set notes = :notes, evidence = cast(:evidence as jsonb),
                    offer = cast(:offer as jsonb), validation = cast(:validation as jsonb), status = :status, edited_by_human = true
                where id = :id
                """)
                .param("id", id)
                .param("notes", notes)
                .param("evidence", json.writeValueAsString(evidence))
                .param("offer", offer == null ? null : json.writeValueAsString(offer))
                .param("validation", json.writeValueAsString(validation))
                .param("status", status.name())
                .update();
        return find(id).orElseThrow();
    }

    public Draft updateStatus(UUID id, DraftStatus status) {
        jdbc.sql("update drafts set status = :status where id = :id")
                .param("id", id).param("status", status.name()).update();
        return find(id).orElseThrow();
    }

    private Draft map(ResultSet rs, int rowNum) throws SQLException {
        return new Draft(
                rs.getObject("id", UUID.class),
                rs.getObject("dispute_id", UUID.class),
                rs.getInt("version"),
                DraftStatus.valueOf(rs.getString("status")),
                Recommendation.valueOf(rs.getString("recommendation")),
                rs.getBigDecimal("confidence"),
                rs.getString("rationale"),
                json.readValue(rs.getString("evidence"), new TypeReference<List<EvidenceItem>>() { }),
                rs.getString("notes"),
                json.readValue(rs.getString("citations"), new TypeReference<List<Citation>>() { }),
                strings(rs.getString("missing_info")),
                rs.getString("offer") == null ? null : json.readValue(rs.getString("offer"), Offer.class),
                json.readValue(rs.getString("validation"), ValidationResult.class),
                LlmMode.valueOf(rs.getString("llm_mode").toUpperCase()),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("prompt_version"),
                integer(rs, "input_tokens"),
                integer(rs, "output_tokens"),
                integer(rs, "latency_ms"),
                rs.getBoolean("edited_by_human"),
                rs.getBoolean("repair_attempted"),
                toInstant(rs, "created_at"));
    }

    private List<String> strings(String jsonArray) {
        List<String> out = new ArrayList<>();
        json.readTree(jsonArray).forEach(n -> out.add(n.asString()));
        return out;
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : Instant.ofEpochMilli(ts.getTime());
    }
}
