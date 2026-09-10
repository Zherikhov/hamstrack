package com.hamstrack.common.testsupport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>The attribution of {@link SourceCallSites} on the exact shapes that fooled its two
 * predecessors</strong> — a seal is only as good as the parser under it, and both copies this
 * class replaced were wrong in a way no consumer test could see until a line happened to be
 * rewrapped.
 *
 * <p>Each planted snippet is written the way this codebase writes it (four-space method level,
 * eight-space body, twelve-space nested), because the parser recognises that shape and nothing
 * else; a test written in a different style would pass for the wrong reason.
 */
class SourceCallSitesTest {

    /**
     * The HD-298 regression: a call rewrapped over two lines has no semicolon on its first line
     * and a real method name the keyword deny-list cannot refuse. Without the indentation
     * lookahead it parsed as a declaration and everything below it — including the sealed door —
     * was attributed to {@code rejectUnencodablePassword}, a method the door is never called from.
     */
    @Test
    void aTwoLineCallInABodyIsNotADeclaration() {
        var lines = List.of(
                "    public void register(RegisterRequest req) {",
                "        rejectPublishedPassword(req.password(),",
                "                () -> metrics.signupRefused(SignupRefusal.PUBLISHED_PASSWORD));",
                "        rejectUnencodablePassword(req.password(),",
                "                () -> metrics.signupRefused(SignupRefusal.UNENCODABLE_PASSWORD));",
                "        mailThrottle.requireAndRecordWhereEndpointDiscloses(",
                "                EmailType.REGISTRATION_VERIFICATION, email);",
                "    }");

        assertThat(SourceCallSites.enclosingMethods(lines))
                .as("every body line of register, including the rewrapped calls, belongs to register")
                .containsOnly("register");
    }

    /**
     * The deficiency {@code AttachmentDoorsTest} documented in its own copy: a {@code return new
     * Type(} twelve spaces in captures a real type name, which the deny-list cannot refuse either.
     */
    @Test
    void aNestedConstructorCallIsNotADeclaration() {
        var lines = List.of(
                "    private ReservedAttachment upload(MultipartFile file) {",
                "        return txTemplate.execute(status -> {",
                "            workspaceStorage.reserve(ws, file.getSize());",
                "            return new",
                "            ReservedAttachment(",
                "                    file.getSize());",
                "        });",
                "        fileStorage.store(key, file);",
                "    }");

        assertThat(SourceCallSites.enclosingMethods(lines)).containsOnly("upload");
    }

    /** A control statement in a body was the original false positive; the deny-list still holds it. */
    @Test
    void aControlStatementIsNotADeclaration() {
        var lines = List.of(
                "    public void register(RegisterRequest req) {",
                "        if (userRepository.existsByFoldedEmail(email)) {",
                "            throw new EmailAlreadyExistsException();",
                "        }",
                "    }");

        assertThat(SourceCallSites.enclosingMethods(lines)).containsOnly("register");
    }

    /** And the thing it must still see: a real declaration re-attributes what follows it. */
    @Test
    void aRealDeclarationAtMethodLevelReattributes() {
        var lines = List.of(
                "    public void register(RegisterRequest req) {",
                "        mailThrottle.requireAndRecordWhereEndpointDiscloses(type, email);",
                "    }",
                "",
                "    /**",
                "     * Javadoc that mentions requireAndRecordWhereEndpointDiscloses( is not a call.",
                "     */",
                "    @Transactional",
                "    public void forgotPassword(ForgotPasswordRequest req) {",
                "        mailThrottle.requireAndRecordWhereEndpointDiscloses(type, email);",
                "    }",
                "",
                "    private static final Runnable NOT_A_SIGNUP = () -> { };",
                "",
                "    private void rejectUnencodablePassword(String password, Runnable refused) {",
                "        refused.run();",
                "    }");

        var enclosing = SourceCallSites.enclosingMethods(lines);

        assertThat(enclosing.get(1)).isEqualTo("register");
        assertThat(enclosing.get(9)).isEqualTo("forgotPassword");
        assertThat(enclosing.get(12))
                .as("a field with an initialiser is neither a declaration nor a re-attribution")
                .isEqualTo("forgotPassword");
        assertThat(enclosing.get(15)).isEqualTo("rejectUnencodablePassword");

        var bodies = SourceCallSites.bodiesByMethod("AuthService", lines);
        assertThat(bodies.keySet()).containsExactly("AuthService.register",
                "AuthService.forgotPassword", "AuthService.rejectUnencodablePassword");
        assertThat(bodies.get("AuthService.forgotPassword"))
                .as("the javadoc mention above forgotPassword is dropped from the body")
                .doesNotContain("Javadoc");
        assertThat(bodies.get("AuthService.forgotPassword"))
                .as("the field line between two methods is attributed to the one above it, which is "
                    + "harmless — it carries no call — but the attribution must be stable")
                .contains("NOT_A_SIGNUP");
    }

    /**
     * The two public shapes agree with each other on the real tree: every method named by
     * {@code callerBodies} is named by {@code callers}, and vice versa. Which methods those are
     * is the seals' business ({@code AuthMailDoorsTest}, {@code AttachmentDoorsTest}), not this
     * test's — a second copy of a seal is the drift this class replaced.
     */
    @Test
    void bothShapesNameTheSameMethodsOnTheRealTree() throws IOException {
        var needle = "requireAndRecordWhereEndpointDiscloses(";
        var perLine = SourceCallSites.callers(needle, "RecipientMailThrottle.java");
        var perMethod = SourceCallSites.callerBodies(needle, "RecipientMailThrottle.java");

        assertThat(perLine).as("the parser found nothing to attribute on the real tree").isNotEmpty();
        assertThat(perMethod.keySet()).containsExactlyInAnyOrderElementsOf(
                perLine.stream().distinct().toList());
        assertThat(perLine).noneMatch(caller -> caller.endsWith("." + SourceCallSites.FILE_SCOPE));
    }
}
