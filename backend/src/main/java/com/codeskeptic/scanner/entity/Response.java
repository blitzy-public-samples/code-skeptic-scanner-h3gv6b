package com.codeskeptic.scanner.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code responses} table and its five columns: {@code id} (the primary key),
 * {@code content}, {@code generated_at}, {@code is_approved} and {@code tweet_id}.
 *
 * <p>This entity holds the child side of the schema's single association and owns its foreign key:
 * many {@code responses} rows belong to one {@code tweets} row. {@link Tweet#getResponses()} is the
 * inverse side, mapped by this entity's {@code tweet} attribute and ordered by ascending identifier.
 * The table is created from these annotations by {@code spring.jpa.hibernate.ddl-auto} — see
 * docs/DECISION_LOG.md DL-026.
 *
 * <p>{@code id} is a generated surrogate key and is typed to match {@link Tweet#getId()} — see
 * docs/DECISION_LOG.md DL-025 and DL-049. The X post identifier is not carried in it and no
 * natural-key column is declared, so ingestion performs no de-duplication — see
 * docs/DECISION_LOG.md DL-049. {@code id} and {@code tweet_id} are both carried as {@link String} at
 * the wire boundary — see docs/DECISION_LOG.md DL-023.
 *
 * <p>{@code is_approved} carries the approval flag for a human to read. No code path in this
 * application publishes to X, so the flag is never a trigger.
 *
 * <p>No column declares {@code nullable = false}, {@code unique} or a length bound, reproducing the
 * unconstrained source declarations.
 */
// Ported from backend/app/db/models.py:L20-28 (faithful port) — see docs/DECISION_LOG.md
// Deviations from the literal source declaration, each recorded in the decision log: the type keeps
// the source name Response rather than being renamed — DL-025; id is Long with
// GenerationType.IDENTITY over Column(Integer, primary_key=True) — DL-049; the tweet_id column and
// the tweet relationship at backend/app/db/models.py:L27-28 are mapped by the single @ManyToOne
// association that owns the foreign key — see docs/DECISION_LOG.md
// equals(Object) and hashCode() are net-new Java persistence mechanics — DL-023 — see
// docs/DECISION_LOG.md
@Entity
@Table(name = "responses")
public class Response {

    // backend/app/db/models.py:L23
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // backend/app/db/models.py:L24
    @Column(name = "content")
    private String content;

    // backend/app/db/models.py:L25
    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    // backend/app/db/models.py:L26
    // Read by a human reviewer; never a trigger to publish.
    @Column(name = "is_approved")
    private Boolean isApproved;

    // Ported from backend/app/db/models.py:L27-28 (faithful port) — see docs/DECISION_LOG.md
    // This side owns the foreign key declared as ForeignKey('tweets.id').
    @ManyToOne
    @JoinColumn(name = "tweet_id")
    private Tweet tweet;

    /**
     * No-argument constructor required by JPA for entity instantiation. Field values are populated by
     * the persistence provider or by the accessors below.
     */
    public Response() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(LocalDateTime generatedAt) {
        this.generatedAt = generatedAt;
    }

    public Boolean getIsApproved() {
        return isApproved;
    }

    public void setIsApproved(Boolean isApproved) {
        this.isApproved = isApproved;
    }

    public Tweet getTweet() {
        return tweet;
    }

    public void setTweet(Tweet tweet) {
        this.tweet = tweet;
    }

    // Net-new Java persistence mechanics (no Python counterpart) — DL-023 — see
    // docs/DECISION_LOG.md
    /**
     * Compares two instances on the persistent identifier.
     *
     * <p>Yields {@code true} for the same reference, and for any instance of this type — a
     * persistence-provider proxy included — whose identifier is non-{@code null} and equal to this
     * identifier. Yields {@code false} whenever either identifier is {@code null} and the two
     * references differ; two instances that have not yet been persisted never compare equal. The
     * identifier is read through {@link #getId()} on both sides. The {@code tweet} association is not
     * read, so the comparison triggers no lazy load.
     *
     * @param other the object to compare with
     * @return {@code true} when both instances denote the same {@code responses} row
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Response that)) {
            return false;
        }
        Long thisId = this.getId();
        return thisId != null && thisId.equals(that.getId());
    }

    // Net-new Java persistence mechanics (no Python counterpart) — DL-023 — see
    // docs/DECISION_LOG.md
    /**
     * Returns a hash code derived from the entity type. The value is identical for every instance of
     * this type and for every proxy of it, and is unchanged by assignment of the identifier on
     * insert. The {@code tweet} association is not read, so the computation triggers no lazy load.
     *
     * @return the hash code of this entity's type
     */
    @Override
    public int hashCode() {
        return Response.class.hashCode();
    }
}
