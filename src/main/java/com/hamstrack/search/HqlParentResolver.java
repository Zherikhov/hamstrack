package com.hamstrack.search;

import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.search.parser.ast.Value;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves an ISSUE_REF ({@code parent}) operand — a quoted issue key like
 * {@code "DEMO-12"} — to its issue id, scoped to the actor's visible projects
 * (Advanced Search proposal §5, §6). Split out of {@link HqlValueResolver} because
 * it needs an issue lookup; the workspace + visible-project ids come from the
 * {@link ResolutionContext}, so a parent outside the tenant boundary is never
 * resolvable (a foreign/unknown key → 422, never an existence leak).
 *
 * <h2>Cleaning the operand: canonical, not folded, and never a bare {@code trim()}</h2>
 * The operand is a paste target — chat clients, wikis and rendered pages routinely emit
 * U+00A0 around whatever they wrap — so it goes through
 * {@link SearchNames#canonical(String)} (HD-121). The two nearby-looking choices are both
 * wrong, in opposite directions:
 *
 * <ul>
 *   <li><strong>{@code String.strip()} does not fix the reported bug.</strong> It is
 *       Unicode-aware about {@link Character#isWhitespace}, and the non-breaking
 *       separators are precisely the ones that predicate excludes: U+00A0, U+2007 and
 *       U+202F are {@code \p{Z}} and are NOT whitespace, so {@code strip()} removes
 *       U+3000 and leaves the three a paste actually produces. It also leaves U+200B and
 *       U+FEFF, which are neither whitespace nor separators. {@code trim()}, which only
 *       cuts at or below U+0020, leaves all five.</li>
 *   <li><strong>{@link SearchNames#key(String)} does too much.</strong> It adds
 *       {@code toLowerCase(Locale.ROOT)}, and case-insensitivity here is already settled
 *       in SQL: {@code IssueRepository.findIdByWorkspaceAndKey} matches
 *       {@code upper(i.project.key) = upper(:projectKey)}. Folding the operand cannot
 *       change which issue resolves, because there is no folded copy on the other side of
 *       that predicate for it to meet; the only thing it changes is the spelling the 422
 *       echoes, showing a caller their own key in a case they did not type.</li>
 * </ul>
 *
 * <p><strong>Errors quote the CLEANED key</strong> — the deliberate opposite of
 * {@code HqlValueResolver.requireName}, which quotes the raw input, and for the reason
 * that rule is made of: a caller should see what they typed. Here they do.
 * {@code canonical} preserves case and removes nothing that renders, so every character
 * the caller can see survives it. What it drops is exactly the set that does not render
 * and would instead corrupt the message it lands in — the bidi overrides U+202A–202E and
 * the zero-width characters — and an error banner is a poor place to re-emit those. The
 * choice follows the operand's kind, not this field's name: quote the raw form wherever
 * cleaning can change something visible, and the cleaned form wherever it cannot.
 *
 * <h2>Blank and interior-separator operands are refused before the lookup</h2>
 * Blank-after-cleaning ({@code parent = ""}, or an operand that is nothing but a
 * U+00A0) gets a field-anchored 422 of its own, mirroring {@code requireName}: it is
 * not a key anyone could hold, and saying so beats an "invalid key" message quoting
 * nothing at all.
 *
 * <p>An interior separator is refused for a stronger reason than tidiness. Cleaning has
 * already folded every separator class to U+0020, so a space still present is
 * <em>inside</em> the operand, and neither half of an issue key can hold one. Before
 * HD-121 the two spellings of that mistake disagreed: {@code parent = "ABC - 12"} was a
 * format error (the number half fails to parse), while the same key written with a
 * U+00A0 in place of that plain space reached the database with a project key of
 * {@code "ABC "} and came back as "no issue matching" — wrong twice, since it implies
 * such a key could exist and spends a query establishing what the shape of the string
 * had already decided.
 */
@Component
@RequiredArgsConstructor
public class HqlParentResolver {

    private final IssueRepository issueRepository;

    public ResolvedValue.Ids resolve(FieldDescriptor field, Value value, ResolutionContext ctx) {
        if (!(value instanceof Value.StringLiteral s)) {
            throw new HqlSemanticException(
                    "Field '" + field.name() + "' expects a quoted issue key like \"DEMO-12\"",
                    field.name());
        }
        String key = SearchNames.canonical(s.value());
        if (key.isEmpty()) {
            throw new HqlSemanticException(
                    "Field '" + field.name() + "' expects a non-empty issue key", field.name());
        }
        int dash = key.lastIndexOf('-');
        // indexOf(' ') covers every separator class at once: cleaning has already folded
        // \s and \p{Z} runs to a single U+0020, so one test rejects them all.
        if (dash <= 0 || dash == key.length() - 1 || key.indexOf(' ') >= 0) {
            throw new HqlSemanticException(
                    "Invalid issue key '" + key + "'; expected PROJECT-NUMBER", field.name());
        }
        String projectKey = key.substring(0, dash);
        long number;
        try {
            number = Long.parseLong(key.substring(dash + 1));
        } catch (NumberFormatException e) {
            throw new HqlSemanticException(
                    "Invalid issue key '" + key + "'; expected PROJECT-NUMBER", field.name());
        }
        if (ctx.visibleProjectIds().isEmpty()) {
            throw new HqlSemanticException("No issue matching '" + key + "' in this workspace", field.name());
        }
        var id = issueRepository.findIdByWorkspaceAndKey(
                ctx.workspace().getId(), ctx.visibleProjectIds(), projectKey, number);
        return new ResolvedValue.Ids(List.of(id.orElseThrow(() -> new HqlSemanticException(
                "No issue matching '" + key + "' in this workspace", field.name()))));
    }
}
