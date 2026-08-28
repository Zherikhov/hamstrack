package com.hamstrack.issue.entity;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.entity.CreatedOnlyEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "issue_history")
@Getter
@Setter
public class IssueHistory extends CreatedOnlyEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issue_id", nullable = false)
    private Issue issue;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "changed_by", nullable = false)
    private User changedBy;

    /**
     * The width of {@code issue_history.field}, and the clip {@link #setField} applies. Referenced
     * by the {@code @Column} below rather than repeated as a literal there: nothing mechanical keeps
     * the two equal (see the field's javadoc), so every extra copy of the number is one more place
     * for a drift to hide. What is left is the single hand-kept pair this entity's reader is told to
     * watch — this constant and {@code V24}'s {@code VARCHAR(100)}.
     */
    public static final int MAX_FIELD_LENGTH = 100;

    /**
     * The name of the thing that changed — a core field's fixed label ({@code "title"},
     * {@code "status"}…) or a <strong>custom field's display name</strong>, copied out of
     * {@code field_defs.name VARCHAR(100)}.
     *
     * <p><strong>100 because that source is 100</strong> (HD-171 §4.2, V24). It was 50, i.e.
     * half its own widest source, so a custom field named 51–100 characters made every value
     * change to it 500 on {@code PATCH …/issues/{number}} while <em>create</em> succeeded —
     * create passes a no-op history listener.
     *
     * <p><strong>Entity/column parity here is convention, not enforcement.</strong> An earlier
     * draft of this javadoc (and of V24's header) said {@code ddl-auto=validate} fails if the two
     * widths disagree. <em>It does not.</em> Hibernate's schema validator compares JDBC
     * <em>type codes</em> ({@code ColumnDefinitions.hasMatchingType}); {@code hasMatchingLength}
     * exists but is referenced only from {@code StandardTableMigrator}, i.e. from
     * {@code ddl-auto=update}. So a {@code VARCHAR(50)} column against {@code length = 100} here
     * boots perfectly clean and 500s at INSERT — the exact bug V24 fixed. Nothing in the build
     * catches a drift today; what is <em>required</em> to (AC 6, still to be written) is a
     * behavioural assertion — a {@code PATCH} that changes the value of a custom field whose name
     * is 100 characters. Until it exists, keep the two numbers equal by hand.
     */
    @Column(nullable = false, length = MAX_FIELD_LENGTH)
    private String field;

    /**
     * <strong>The truncation belt, on the column rather than on one of its writers</strong>
     * (HD-171 §4.2). Lombok's {@code @Setter} yields to a hand-written setter, so every writer
     * <em>that goes through the setter</em> inherits the clip — which is the point, and which is
     * as far as the claim reaches: this entity is <strong>field-accessed</strong> ({@code @Id} sits
     * on a field in {@code CreatedOnlyEntity}, and no {@code @Access} overrides it), so Hibernate's
     * own hydration writes {@code field} directly and never calls this method, as would a
     * {@code @Modifying} JPQL update or a native INSERT. Hydration is harmless — the value it
     * assigns came out of the column and is already within its width — but a rule stated over
     * "every writer present and future" would be false the first time somebody wrote this column in
     * SQL. All four application writers do go through here. The clip lived in
     * {@code IssueService.makeHistory} for one round, and {@code issue_history.field} has
     * <em>four</em> writers: that one, {@code SprintService.writeSprintHistory} and a
     * near-identical private {@code SprintService.makeHistory} copy, and
     * {@code WorkspaceMemberService}. Three of them pass literals today, so there was no live
     * bug — but "only the update path writes a dynamic name" is a claim about today's call graph,
     * and the duplicated {@code makeHistory} is the divergence trap: the next person to write a
     * dynamic field name may well be editing sprints, and their copy carried no belt and no
     * comment saying why it needed one. Stated as a property of a class of values, the rule is
     * <em>a value derived from another column is either bounded by that column's width or
     * truncated at the write site</em> — so it belongs where the write happens for everyone.
     *
     * <p>It is a silent clip, accepted deliberately: this column is a label nothing keys on, and
     * the alternative on the custom-field path is the 500 V24 removed.
     *
     * <p><strong>Why the surrogate step-back, since the naive clip was already safe.</strong>
     * Java counts UTF-16 code units and PostgreSQL's {@code varchar(100)} counts code points, and
     * code points are never more numerous than units — so a clip at 100 units yields at most 100
     * code points and can never overshoot the column. A {@code 22001} from here is impossible
     * either way. What the naive {@code substring(0, 100)} leaves is a <em>lone high surrogate</em>
     * at the last index: pgjdbc's encoder replaces it with {@code ?}, so the last character is
     * silently mangled rather than rejected. Dropping the orphan is the exact clip. This is
     * spelled out because the naive version is correct for a non-obvious reason, and a reader who
     * reconstructs the reasoning from scratch is as likely to "fix" it into something wrong.
     */
    public void setField(String field) {
        if (field == null || field.length() <= MAX_FIELD_LENGTH) {
            this.field = field;
            return;
        }
        int end = MAX_FIELD_LENGTH;
        // charAt(end - 1) is a high surrogate ⇒ its low half is at index end, which is about to
        // be cut. Step back one unit rather than ship half a code point.
        if (Character.isHighSurrogate(field.charAt(end - 1))) {
            end--;
        }
        this.field = field.substring(0, end);
    }

    @Column(columnDefinition = "TEXT")
    private String oldValue;

    @Column(columnDefinition = "TEXT")
    private String newValue;
}