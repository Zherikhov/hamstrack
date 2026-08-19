package com.hamstrack.common.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/**
 * <strong>The unit invariants of the one authorization primitive</strong>
 * (roles-permissions-proposal §5.1, §6.4).
 *
 * <p>Every other test in this epic reaches {@link PermissionSet} through Spring, a
 * database and a seeded role, so a defect in the primitive itself shows up there as a
 * confusing parity cell rather than as itself. These are the properties the whole model
 * rests on, asserted directly and with no context to start:
 *
 * <ul>
 *   <li><strong>own-only never satisfies an unrestricted check.</strong> That asymmetry IS
 *       the ownership modifier. If it ever softened, {@code LabelService.requireCurator},
 *       {@code IssueService.delete} and unrestricted {@code attachment.delete} would all
 *       start accepting a Contributor, and nothing else in the suite asks the question in
 *       one line;</li>
 *   <li><strong>{@code union} takes the wider grant</strong>, in both argument orders. The
 *       resolver unions a project role with the workspace-level "in every project" grants
 *       (§17.2); an implementation that let an own-only grant on one side <em>narrow</em>
 *       an unrestricted grant on the other would silently revoke abilities from exactly
 *       the people who hold two roles;</li>
 *   <li><strong>the memoised {@link PermissionSet#allOf(RoleScope)} cannot be mutated by a
 *       caller.</strong> It is a single shared instance handed to every resolution of
 *       {@code project.administer.all}, so one escaped mutable reference would be a
 *       process-wide privilege change, and the resolver that caused it would be long
 *       gone from the stack by the time anyone noticed.</li>
 * </ul>
 *
 * <p>Plain JUnit, no Spring: these are value-object properties, and a test that needs a
 * database to state them is a test nobody runs while changing the class.
 */
class PermissionSetTest {

    // ============================================================ the ownership modifier

    @Test
    void anOwnOnlyGrantNeverSatisfiesAnUnrestrictedCheck() {
        var set = PermissionSet.of(List.of(new PermissionSet.Grant(Permission.ISSUE_DELETE, true)));

        assert !set.has(Permission.ISSUE_DELETE)
                : "has(p) asks 'may you do this to ANYONE's object?' and an own-only grant must "
                  + "answer no. This is the whole ownership modifier (§6.4): the curator call "
                  + "sites (label archive/merge/delete, issue delete, attachment moderation) pass "
                  + "NO ownership argument precisely so an own-only grant cannot reach them.";
        assert !set.has(Permission.ISSUE_DELETE, false)
                : "has(p, false) is the same question with the answer spelled out";
        assert set.has(Permission.ISSUE_DELETE, true)
                : "the actor's OWN object is what an own-only grant is for";
        assert set.hasAtAll(Permission.ISSUE_DELETE)
                : "hasAtAll is the role-editor question ('is this granted in any form?') and must "
                  + "see the own-only grant — it is deliberately NOT an authorization check";

        boolean refusedUnrestricted = false;
        try {
            set.require(Permission.ISSUE_DELETE);
        } catch (MissingPermissionException e) {
            refusedUnrestricted = true;
            assert e.getMessage().contains(Permission.ISSUE_DELETE.key())
                    : "a 403 must name the permission — it is the only thing that tells an "
                      + "operator which grant to add. Got: " + e.getMessage();
        }
        assert refusedUnrestricted : "require(p) accepted an own-only grant";

        boolean refusedForeign = false;
        try {
            set.require(Permission.ISSUE_DELETE, false);
        } catch (MissingPermissionException e) {
            refusedForeign = true;
        }
        assert refusedForeign : "require(p, false) accepted an own-only grant";

        set.require(Permission.ISSUE_DELETE, true); // must not throw
    }

    @Test
    void anUnrestrictedGrantAnswersEveryObject() {
        var set = PermissionSet.of(List.of(new PermissionSet.Grant(Permission.ISSUE_DELETE, false)));
        assert set.has(Permission.ISSUE_DELETE);
        assert set.has(Permission.ISSUE_DELETE, false);
        assert set.has(Permission.ISSUE_DELETE, true);
        assert set.hasAtAll(Permission.ISSUE_DELETE);
    }

    @Test
    void anOwnRequiredPermissionIsForcedOwnOnlyHoweverItIsBuilt() {
        // §17.3: comment.edit unrestricted is not expressible AT ANY ROLE. Both construction
        // paths must enforce it, because S4's role editor will feed one and the implied
        // project.administer.all grant feeds the other.
        var stored = PermissionSet.of(
                List.of(new PermissionSet.Grant(Permission.COMMENT_EDIT, false)));
        assert !stored.has(Permission.COMMENT_EDIT) && stored.has(Permission.COMMENT_EDIT, true)
                : "a stored row claiming unrestricted comment.edit was honoured. A bad row (or a "
                  + "pre-§17.3 database) must be narrowed on read, not trusted: " + stored;

        var implied = PermissionSet.granting(Set.of(Permission.COMMENT_EDIT));
        assert !implied.has(Permission.COMMENT_EDIT) && implied.has(Permission.COMMENT_EDIT, true)
                : "granting() handed out unrestricted comment.edit — so project.administer.all "
                  + "(which is granting(allOf(PROJECT))) would let a 'Program manager' custom role "
                  + "edit other people's words. §17.3 says no role ships that: " + implied;

        assert PermissionSet.allOf(RoleScope.PROJECT).has(Permission.COMMENT_EDIT, true)
                && !PermissionSet.allOf(RoleScope.PROJECT).has(Permission.COMMENT_EDIT)
                : "allOf(PROJECT) is what project.administer.all unions in — same rule applies";
    }

    // ==================================================================== union

    @Test
    void unionTakesTheWiderGrantInEitherOrder() {
        var own = PermissionSet.of(List.of(
                new PermissionSet.Grant(Permission.ATTACHMENT_DELETE, true),
                new PermissionSet.Grant(Permission.ISSUE_EDIT, true)));
        var unrestricted = PermissionSet.of(List.of(
                new PermissionSet.Grant(Permission.ATTACHMENT_DELETE, false)));

        for (var merged : List.of(own.union(unrestricted), unrestricted.union(own))) {
            assert merged.has(Permission.ATTACHMENT_DELETE)
                    : "union narrowed an unrestricted grant to own-only. The resolver unions the "
                      + "project role with the workspace-level 'in every project' grants (§17.2), "
                      + "so this would silently REVOKE moderation from someone who holds both. "
                      + "Got " + merged;
            assert merged.has(Permission.ISSUE_EDIT, true) && !merged.has(Permission.ISSUE_EDIT)
                    : "a permission only one side holds must survive at ITS width, not the "
                      + "other's. Got " + merged;
            assert merged.asWireStrings().stream().noneMatch(s -> s.equals("attachment.delete:own"))
                    : "the wire form carries both widths of one key — a client would gate on "
                      + "whichever it found first. Got " + merged.asWireStrings();
        }
    }

    @Test
    void unionWithEmptyIsIdentityAndNeverWidens() {
        var set = PermissionSet.of(List.of(
                new PermissionSet.Grant(Permission.ISSUE_CREATE, false),
                new PermissionSet.Grant(Permission.COMMENT_DELETE, true)));

        for (var merged : List.of(set.union(PermissionSet.empty()), PermissionSet.empty().union(set))) {
            assert merged.asWireStrings().equals(set.asWireStrings())
                    : "unioning with the empty set changed the grants: " + merged;
        }
        assert PermissionSet.empty().union(PermissionSet.empty()).isEmpty();
        assert PermissionSet.empty().isEmpty() && PermissionSet.empty().asWireStrings().isEmpty()
                : "empty() is a real answer, not an error state (§12) — a Viewer, and a member "
                  + "with no project role in a STRICT workspace, both hold exactly this";
    }

    @Test
    void ofCollapsesBothWidthsOfOneKeyToTheWiderOne() {
        // The database's composite PK forbids this pair, but PermissionSet.of also backs
        // the S4 editor's preview, where a request body can carry anything.
        var set = PermissionSet.of(List.of(
                new PermissionSet.Grant(Permission.ISSUE_EDIT, true),
                new PermissionSet.Grant(Permission.ISSUE_EDIT, false)));
        assert set.has(Permission.ISSUE_EDIT) : "the wider grant must win: " + set;
        assert set.asWireStrings().equals(List.of("issue.edit"))
                : "one key must appear exactly once on the wire, at one width. Got "
                  + set.asWireStrings();
    }

    // ============================================================ the memoised allOf

    @Test
    void theMemoisedAllOfIsSharedAndUnreachableForMutation() {
        var first = PermissionSet.allOf(RoleScope.PROJECT);
        var before = first.asWireStrings();

        // Everything a caller can legally do with it, including the thing the resolver does
        // on the request path for a holder of project.administer.all.
        var derived = PermissionSet.of(List.of(new PermissionSet.Grant(Permission.ISSUE_CREATE, true)))
                .union(first);
        first.union(PermissionSet.empty());
        first.has(Permission.PROJECT_ARCHIVE);
        assert !derived.isEmpty();

        boolean wireFormIsImmutable = false;
        try {
            first.asWireStrings().add("workspace.edit");
        } catch (UnsupportedOperationException expected) {
            wireFormIsImmutable = true;
        }
        assert wireFormIsImmutable
                : "asWireStrings() handed out a mutable list. It is rendered straight into a "
                  + "response body and, for the memoised set, is derived from a process-wide "
                  + "shared instance; a caller that appended to it would be editing what every "
                  + "later request sees.";

        assert PermissionSet.allOf(RoleScope.PROJECT) == first
                : "allOf must return the SAME instance every call — §9.2's constant cost depends "
                  + "on it not being rebuilt on the request path";
        assert first.asWireStrings().equals(before)
                : "the memoised set changed after being used. It is shared by every resolution "
                  + "of project.administer.all in the process, so a mutation here is an instance-"
                  + "wide privilege change with no audit trail. Was " + before + ", now "
                  + first.asWireStrings();
    }

    @Test
    void allOfIsExactlyOneScopeAndNothingFromTheOther() {
        var project = PermissionSet.allOf(RoleScope.PROJECT);
        var workspace = PermissionSet.allOf(RoleScope.WORKSPACE);

        for (var p : Permission.values()) {
            var owning = p.scope() == RoleScope.PROJECT ? project : workspace;
            var other = p.scope() == RoleScope.PROJECT ? workspace : project;
            assert owning.hasAtAll(p) : "allOf(" + p.scope() + ") is missing " + p.key();
            assert !other.hasAtAll(p)
                    : "allOf leaked " + p.key() + " into the " + (p.scope() == RoleScope.PROJECT
                      ? "WORKSPACE" : "PROJECT") + " scope. project.administer.all unions "
                      + "allOf(PROJECT) into a ProjectContext, so a workspace permission in there "
                      + "would satisfy workspace-scoped require(...) calls for a project role.";
        }
    }

    // ============================================================ the wire form

    @Test
    void theWireFormIsCatalogOrderedAndOwnQualified() {
        var set = PermissionSet.of(List.of(
                new PermissionSet.Grant(Permission.COMMENT_DELETE, true),
                new PermissionSet.Grant(Permission.WORKSPACE_EDIT, false),
                new PermissionSet.Grant(Permission.ISSUE_CREATE, false)));

        assert set.asWireStrings().equals(
                List.of("workspace.edit", "issue.create", "comment.delete" + PermissionSet.OWN_SUFFIX))
                : "the wire form must be in catalog (declaration) order so a response body is "
                  + "stable and diffable across requests and installs, and an own-only grant must "
                  + "carry the ':own' suffix. Got " + set.asWireStrings();
    }
}
