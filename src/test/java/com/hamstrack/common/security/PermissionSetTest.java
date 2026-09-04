package com.hamstrack.common.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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

        assertThat(set.has(Permission.ISSUE_DELETE))
                .withFailMessage("has(p) asks 'may you do this to ANYONE's object?' and an own-only grant must "
                  + "answer no. This is the whole ownership modifier (§6.4): the curator call "
                  + "sites (label archive/merge/delete, issue delete, attachment moderation) pass "
                  + "NO ownership argument precisely so an own-only grant cannot reach them.")
                .isFalse();
        assertThat(set.has(Permission.ISSUE_DELETE, false))
                .withFailMessage("has(p, false) is the same question with the answer spelled out")
                .isFalse();
        assertThat(set.has(Permission.ISSUE_DELETE, true))
                .withFailMessage("the actor's OWN object is what an own-only grant is for")
                .isTrue();
        assertThat(set.hasAtAll(Permission.ISSUE_DELETE))
                .withFailMessage("hasAtAll is the role-editor question ('is this granted in any form?') and must "
                  + "see the own-only grant — it is deliberately NOT an authorization check")
                .isTrue();

        boolean refusedUnrestricted = false;
        try {
            set.require(Permission.ISSUE_DELETE);
        } catch (MissingPermissionException e) {
            refusedUnrestricted = true;
            assertThat(e.getMessage())
                    .as(() -> "a 403 must name the permission — it is the only thing that tells an "
                      + "operator which grant to add. Got: " + e.getMessage())
                    .contains(Permission.ISSUE_DELETE.key());
        }
        assertThat(refusedUnrestricted).withFailMessage("require(p) accepted an own-only grant").isTrue();

        boolean refusedForeign = false;
        try {
            set.require(Permission.ISSUE_DELETE, false);
        } catch (MissingPermissionException e) {
            refusedForeign = true;
        }
        assertThat(refusedForeign).withFailMessage("require(p, false) accepted an own-only grant").isTrue();

        set.require(Permission.ISSUE_DELETE, true); // must not throw
    }

    @Test
    void anUnrestrictedGrantAnswersEveryObject() {
        var set = PermissionSet.of(List.of(new PermissionSet.Grant(Permission.ISSUE_DELETE, false)));
        assertThat(set.has(Permission.ISSUE_DELETE))
                .withFailMessage("an unrestricted grant answers the plain question")
                .isTrue();
        assertThat(set.has(Permission.ISSUE_DELETE, false)).withFailMessage("…and the any-object question").isTrue();
        assertThat(set.has(Permission.ISSUE_DELETE, true))
                .withFailMessage("…and the own-object one: unrestricted covers own, the subset rule's whole point")
                .isTrue();
        assertThat(set.hasAtAll(Permission.ISSUE_DELETE))
                .withFailMessage("…and hasAtAll, which asks only whether the permission is held at any width")
                .isTrue();
    }

    @Test
    void anOwnRequiredPermissionIsForcedOwnOnlyHoweverItIsBuilt() {
        // §17.3: comment.edit unrestricted is not expressible AT ANY ROLE. Both construction
        // paths must enforce it, because S4's role editor will feed one and the implied
        // project.administer.all grant feeds the other.
        var stored = PermissionSet.of(
                List.of(new PermissionSet.Grant(Permission.COMMENT_EDIT, false)));
        assertThat(stored.has(Permission.COMMENT_EDIT))
                .withFailMessage("a stored row claiming unrestricted comment.edit was honoured. A bad row (or a "
                  + "pre-§17.3 database) must be narrowed on read, not trusted: " + stored)
                .isFalse();
        assertThat(stored.has(Permission.COMMENT_EDIT, true))
                .withFailMessage("a stored row claiming unrestricted comment.edit was honoured. A bad row (or a "
                  + "pre-§17.3 database) must be narrowed on read, not trusted: " + stored)
                .isTrue();

        var implied = PermissionSet.granting(Set.of(Permission.COMMENT_EDIT));
        assertThat(implied.has(Permission.COMMENT_EDIT))
                .withFailMessage("granting() handed out unrestricted comment.edit — so project.administer.all "
                  + "(which is granting(allOf(PROJECT))) would let a 'Program manager' custom role "
                  + "edit other people's words. §17.3 says no role ships that: " + implied)
                .isFalse();
        assertThat(implied.has(Permission.COMMENT_EDIT, true))
                .withFailMessage("granting() handed out unrestricted comment.edit — so project.administer.all "
                  + "(which is granting(allOf(PROJECT))) would let a 'Program manager' custom role "
                  + "edit other people's words. §17.3 says no role ships that: " + implied)
                .isTrue();

        assertThat(PermissionSet.allOf(RoleScope.PROJECT).has(Permission.COMMENT_EDIT, true))
                .withFailMessage("allOf(PROJECT) is what project.administer.all unions in — same rule applies")
                .isTrue();
        assertThat(PermissionSet.allOf(RoleScope.PROJECT).has(Permission.COMMENT_EDIT))
                .withFailMessage("allOf(PROJECT) is what project.administer.all unions in — same rule applies")
                .isFalse();
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
            assertThat(merged.has(Permission.ATTACHMENT_DELETE))
                    .withFailMessage("union narrowed an unrestricted grant to own-only. The resolver unions the "
                      + "project role with the workspace-level 'in every project' grants (§17.2), "
                      + "so this would silently REVOKE moderation from someone who holds both. "
                      + "Got " + merged)
                    .isTrue();
            assertThat(merged.has(Permission.ISSUE_EDIT, true))
                    .withFailMessage("a permission only one side holds must survive at ITS width, not the "
                      + "other's. Got " + merged)
                    .isTrue();
            assertThat(merged.has(Permission.ISSUE_EDIT))
                    .withFailMessage("a permission only one side holds must survive at ITS width, not the "
                      + "other's. Got " + merged)
                    .isFalse();
            assertThat(merged.asWireStrings().stream().noneMatch(s -> s.equals("attachment.delete:own")))
                    .withFailMessage(() -> "the wire form carries both widths of one key — a client would gate on "
                      + "whichever it found first. Got " + merged.asWireStrings())
                    .isTrue();
        }
    }

    @Test
    void unionWithEmptyIsIdentityAndNeverWidens() {
        var set = PermissionSet.of(List.of(
                new PermissionSet.Grant(Permission.ISSUE_CREATE, false),
                new PermissionSet.Grant(Permission.COMMENT_DELETE, true)));

        for (var merged : List.of(set.union(PermissionSet.empty()), PermissionSet.empty().union(set))) {
            assertThat(merged.asWireStrings())
                    .as("unioning with the empty set changed the grants: " + merged)
                    .isEqualTo(set.asWireStrings());
        }
        assertThat(PermissionSet.empty().union(PermissionSet.empty()).isEmpty())
                .withFailMessage("the empty set unioned with itself is still empty — union never conjures a grant")
                .isTrue();
        assertThat(PermissionSet.empty().isEmpty() && PermissionSet.empty().asWireStrings().isEmpty())
                .withFailMessage("empty() is a real answer, not an error state (§12) — a Viewer, and a member "
                  + "with no project role in a STRICT workspace, both hold exactly this")
                .isTrue();
    }

    @Test
    void ofCollapsesBothWidthsOfOneKeyToTheWiderOne() {
        // The database's composite PK forbids this pair, but PermissionSet.of also backs
        // the S4 editor's preview, where a request body can carry anything.
        var set = PermissionSet.of(List.of(
                new PermissionSet.Grant(Permission.ISSUE_EDIT, true),
                new PermissionSet.Grant(Permission.ISSUE_EDIT, false)));
        assertThat(set.has(Permission.ISSUE_EDIT)).withFailMessage("the wider grant must win: " + set).isTrue();
        assertThat(set.asWireStrings())
                .as(() -> "one key must appear exactly once on the wire, at one width. Got "
                  + set.asWireStrings())
                .isEqualTo(List.of("issue.edit"));
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
        assertThat(!derived.isEmpty())
                .withFailMessage("the fixture is non-empty, so the memoised set being unchanged is a real claim and not a vacuous one")
                .isTrue();

        boolean wireFormIsImmutable = false;
        try {
            first.asWireStrings().add("workspace.edit");
        } catch (UnsupportedOperationException expected) {
            wireFormIsImmutable = true;
        }
        assertThat(wireFormIsImmutable)
                .withFailMessage("asWireStrings() handed out a mutable list. It is rendered straight into a "
                  + "response body and, for the memoised set, is derived from a process-wide "
                  + "shared instance; a caller that appended to it would be editing what every "
                  + "later request sees.")
                .isTrue();

        assertThat(PermissionSet.allOf(RoleScope.PROJECT))
                .as("allOf must return the SAME instance every call — §9.2's constant cost depends "
                  + "on it not being rebuilt on the request path")
                .isSameAs(first);
        assertThat(first.asWireStrings())
                .as(() -> "the memoised set changed after being used. It is shared by every resolution "
                  + "of project.administer.all in the process, so a mutation here is an instance-"
                  + "wide privilege change with no audit trail. Was " + before + ", now "
                  + first.asWireStrings())
                .isEqualTo(before);
    }

    @Test
    void allOfIsExactlyOneScopeAndNothingFromTheOther() {
        var project = PermissionSet.allOf(RoleScope.PROJECT);
        var workspace = PermissionSet.allOf(RoleScope.WORKSPACE);

        for (var p : Permission.values()) {
            var owning = p.scope() == RoleScope.PROJECT ? project : workspace;
            var other = p.scope() == RoleScope.PROJECT ? workspace : project;
            assertThat(owning.hasAtAll(p)).withFailMessage(() -> "allOf(" + p.scope() + ") is missing " + p.key()).isTrue();
            assertThat(other.hasAtAll(p))
                    .withFailMessage(() -> "allOf leaked " + p.key() + " into the " + (p.scope() == RoleScope.PROJECT
                      ? "WORKSPACE" : "PROJECT") + " scope. project.administer.all unions "
                      + "allOf(PROJECT) into a ProjectContext, so a workspace permission in there "
                      + "would satisfy workspace-scoped require(...) calls for a project role.")
                    .isFalse();
        }
    }

    // ============================================================ the wire form

    @Test
    void theWireFormIsCatalogOrderedAndOwnQualified() {
        var set = PermissionSet.of(List.of(
                new PermissionSet.Grant(Permission.COMMENT_DELETE, true),
                new PermissionSet.Grant(Permission.WORKSPACE_EDIT, false),
                new PermissionSet.Grant(Permission.ISSUE_CREATE, false)));

        assertThat(set.asWireStrings())
                .as(() -> "the wire form must be in catalog (declaration) order so a response body is "
                  + "stable and diffable across requests and installs, and an own-only grant must "
                  + "carry the ':own' suffix. Got " + set.asWireStrings())
                .isEqualTo(List.of("workspace.edit", "issue.create", "comment.delete" + PermissionSet.OWN_SUFFIX));
    }
}
