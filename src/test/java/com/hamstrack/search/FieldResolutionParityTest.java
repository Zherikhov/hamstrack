package com.hamstrack.search;

import com.hamstrack.issue.ComponentTestBase;
import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.FieldSet;
import com.hamstrack.issue.entity.FieldSetItem;
import com.hamstrack.issue.entity.FieldType;
import com.hamstrack.issue.repository.FieldDefRepository;
import com.hamstrack.issue.repository.FieldSetItemRepository;
import com.hamstrack.issue.repository.FieldSetRepository;
import com.hamstrack.issue.service.ComponentService;
import com.hamstrack.issue.service.IssueService;
import com.hamstrack.issue.service.LabelService;
import com.hamstrack.issue.service.VersionService;
import com.hamstrack.search.dto.SearchSchemaResponse;
import com.hamstrack.search.parser.HqlParser;
import com.hamstrack.search.parser.ast.ComparisonOp;
import com.hamstrack.search.parser.ast.Query;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <strong>What a field name means is decided once, and every path that resolves one gets the
 * same answer</strong> (HD-114).
 *
 * <p>HQL field-name precedence — registry → visible custom field → retired-key alias →
 * "unknown field" — used to be written down in more than one place, and the copies drifted:
 * HD-107 taught the alias step to the semantic check while the sort path kept its own
 * sequence, so {@code ORDER BY story_points} was accepted by validation and then rejected as
 * an unknown field while compiling. {@code FieldResolver} exists so that class of failure
 * cannot recur; this suite pins the part of it that behaviour alone cannot show.
 *
 * <p>What is asserted here is the part of that behaviour no end-to-end search can reach:
 *
 * <ul>
 *   <li><strong>A registered-but-not-yet-queryable name is a system name, wherever it is
 *       resolved.</strong> The two pre-HD-114 copies disagreed on exactly this: one reported
 *       "not yet queryable", the other treated the same name as not-a-system-field and let it
 *       fall through to a tenant's custom field. Nothing in {@link FieldRegistry} is
 *       unavailable today, which is why the disagreement went unnoticed and why it is pinned
 *       against an injected stub rather than against live vocabulary.</li>
 *   <li><strong>An unknown field is refused in one wording, with its "did you mean …" hint,
 *       on every path.</strong> The hint used to depend on which check happened to reject the
 *       name first: the same typo produced a helpful message when validated and a bare one
 *       when compiled.</li>
 *   <li><strong>A surface that merely lists names reads a reserved name the same way.</strong>
 *       {@code /schema} and {@code /suggest} ask an ordinary vocabulary question ("what names
 *       exist"), not a resolution one, and are deliberately not guarded the way the alias table
 *       is — but the answer they give still has to be the answer a query gives, or a workspace
 *       is offered a key that every query against it refuses.</li>
 * </ul>
 *
 * <p>These are driven at the resolving components directly rather than over HTTP: validation
 * refuses such a query before anything compiles it, so a request can only ever show the first
 * answer, and the reserved state they all turn on is one the shipped vocabulary deliberately
 * has no member of. Real components, real workspace, real {@link ResolutionContext}; only the
 * vocabulary is a stub, and only where the subject is vocabulary that does not exist yet.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class FieldResolutionParityTest extends ComponentTestBase {

    @Autowired RetiredFieldAliases retiredAliases;
    @Autowired FieldResolver fieldResolver;
    @Autowired HqlValidator validator;
    @Autowired HqlCompiler compiler;
    @Autowired HqlValueResolver valueResolver;
    @Autowired HqlParentResolver parentResolver;
    @Autowired SearchScope searchScope;
    @Autowired ResolutionContextFactory resolutionContextFactory;
    @Autowired WorkspaceAccessService workspaceAccess;
    @Autowired IssueService issueService;
    @Autowired LabelService labelService;
    @Autowired ComponentService componentService;
    @Autowired VersionService versionService;
    @Autowired SearchService searchService;

    @Autowired FieldDefRepository fieldDefRepository;
    @Autowired FieldSetRepository fieldSetRepository;
    @Autowired FieldSetItemRepository fieldSetItemRepository;

    /** An HQL name the registry reserves but has not enabled — see {@link ReservingRegistry}. */
    private static final String RESERVED = "preview";

    /**
     * A reserved-but-unavailable name resolves as a <em>system</em> name and 422s with "not yet
     * queryable" — it never falls through to the tenant's own field of the same key — and it
     * does so identically for semantic validation, for predicate compilation and for the sort
     * path.
     *
     * <p>The workspace deliberately owns a custom field keyed {@code preview}, so a
     * fall-through has somewhere to land: if the strict reading is ever loosened, these queries
     * stop throwing and start silently searching the tenant's JSONB values instead. The control
     * assertion at the end proves the fixture really is visible, so "it threw" cannot be passing
     * for want of a custom field.
     *
     * <p>Why strict is the right reading: being registered is what reserves a name; availability
     * only says <em>when</em> it becomes queryable. A name that fell through would resolve to a
     * tenant's field right up until the day the field is enabled, and every query written
     * against it would then change meaning at once — the shadowing hazard {@link
     * RetiredFieldAliases} documents, pointed the other way.
     */
    @Test
    void aReservedButNotYetQueryableNameIsASystemNameWhereverItIsResolved() throws Exception {
        var ctx = newProject();
        bindCustomField(ctx, RESERVED, "Preview (tenant)");

        var ws = workspaceRepository.findById(ctx.wsId()).orElseThrow();
        var actor = userRepository.findById(ctx.owner().getId()).orElseThrow();
        var rc = resolutionContextFactory.build(actor, ws);

        // A registry that reserves the name, wired into fresh instances of the real components.
        var reserving = new FieldResolver(new ReservingRegistry(), retiredAliases);
        var strictValidator = new HqlValidator(reserving);
        var strictCompiler = new HqlCompiler(entityManager, reserving, valueResolver, parentResolver, searchScope);

        String notYet = "Field '" + RESERVED + "' is not yet queryable";

        Query filter = HqlParser.parse(RESERVED + " = 5");
        assertThatThrownBy(() -> strictValidator.validate(filter, rc))
                .as("validation must report a reserved name as reserved")
                .isInstanceOf(HqlSemanticException.class)
                .hasMessage(notYet);
        assertThatThrownBy(() -> strictCompiler.buildCountQuery(filter, actor, ws, rc))
                .as("a reserved name that fell through here would compile into the tenant's "
                    + "custom field — the divergence HD-114 removed")
                .isInstanceOf(HqlSemanticException.class)
                .hasMessage(notYet);

        Query sort = HqlParser.parse("ORDER BY " + RESERVED + " ASC");
        assertThatThrownBy(() -> strictValidator.validate(sort, rc))
                .isInstanceOf(HqlSemanticException.class)
                .hasMessage(notYet);
        assertThatThrownBy(() -> strictCompiler.buildPageQuery(sort, actor, ws, rc))
                .as("ORDER BY is the entry point HD-107 missed; it resolves like any other")
                .isInstanceOf(HqlSemanticException.class)
                .hasMessage(notYet);

        // Control: with the shipped vocabulary the very same name IS the tenant's custom field,
        // so the assertions above are about precedence and not about a name nobody defined.
        assertThat(fieldResolver.resolve(RESERVED, rc))
                .as("the fixture field must be visible, or this test proves nothing")
                .isInstanceOf(FieldResolver.Resolved.CustomField.class);
    }

    /**
     * A surface that only <em>lists</em> names answers about a reserved name the way a query
     * does: {@code /schema} does not advertise a workspace's own field keyed with a reserved
     * name, and {@code /suggest} refuses that name <em>as reserved</em> instead of quietly
     * serving the tenant field standing behind it.
     *
     * <p>These endpoints ask an ordinary vocabulary question, so they hold the registry rather
     * than being guarded like the alias table — but a vocabulary surface that reads availability
     * loosely publishes a key that every query against it rejects. That is HD-107's shape one
     * surface further out, and the reason the reading is uniform rather than merely centralised.
     *
     * <p>The workspace owns a <strong>user-valued</strong> custom field under the reserved key,
     * so the fall-through has somewhere to land and lands somewhere visible: read loosely,
     * {@code /schema} lists the key as queryable and {@code /suggest} answers with this
     * workspace's own members, while the identical name in a query is a 422. Nothing leaks —
     * the field and the members are the caller's own — the bug is that the surfaces disagree.
     */
    @Test
    void aReservedNameIsAbsentFromTheVocabularyThatCannotAnswerIt() throws Exception {
        var ctx = newProject();
        bindCustomField(ctx, RESERVED, "Preview (tenant)", FieldType.USER);

        var actor = userRepository.findById(ctx.owner().getId()).orElseThrow();

        // The same seam the test above uses, wired all the way up to the endpoints' service.
        var reserving = new ReservingRegistry();
        var resolver = new FieldResolver(reserving, retiredAliases);
        var reservingSearch = new SearchService(
                workspaceAccess, resolutionContextFactory,
                new HqlValidator(resolver),
                new HqlCompiler(entityManager, resolver, valueResolver, parentResolver, searchScope),
                reserving, resolver,
                issueService, labelService, componentService, versionService, entityManager);

        assertThat(reservingSearch.schema(actor, ctx.wsId()).fields())
                .extracting(SearchSchemaResponse.Field::name)
                .as("a key the registry reserves is the product's, so this workspace's field of "
                    + "that key is not queryable — advertising it offers a name every query "
                    + "against it refuses")
                .doesNotContain(RESERVED);

        assertThatThrownBy(() -> reservingSearch.suggest(actor, ctx.wsId(), RESERVED, ""))
                .as("suggesting values for a name a query refuses is the divergence; the refusal "
                    + "must also give the query's reason, not 'no suggestions' for a tenant "
                    + "field that never had the name")
                .isInstanceOf(HqlSemanticException.class)
                .hasMessage("Field '" + RESERVED + "' is not yet queryable");

        // Control: nothing in the shipped registry reserves this key, so the identical fixture
        // is both advertised and served. Without this the assertions above could pass on a
        // field the caller simply cannot see.
        assertThat(searchService.schema(actor, ctx.wsId()).fields())
                .extracting(SearchSchemaResponse.Field::name)
                .contains(RESERVED);
        assertThat(searchService.suggest(actor, ctx.wsId(), RESERVED, "").suggestions())
                .as("the fixture is a user-valued field, so unreserved it answers with members")
                .isNotEmpty();
    }

    /**
     * An unknown name is refused in the same words, with the same Levenshtein hint, whether the
     * refusal comes from semantic validation or from compilation — including from a sort key.
     *
     * <p>The hint is not decoration: it is the whole content of the error for a typo. Before
     * HD-114 the compilation path threw a bare "Unknown field 'assigne'", so the message a user
     * saw depended on which check reached the name first — and the useful one was not reachable
     * at all for a name that only compilation resolves.
     *
     * <p>Compilation is driven directly on purpose: no request can produce an unknown field
     * there, because validation refuses it first. That is the invariant, not a gap — but a
     * message nothing exercises is a message that rots, and this class of divergence is exactly
     * how HD-107 shipped.
     */
    @Test
    void anUnknownFieldIsRefusedInTheSameWordsWhereverItIsResolved() throws Exception {
        var ctx = newProject();
        var ws = workspaceRepository.findById(ctx.wsId()).orElseThrow();
        var actor = userRepository.findById(ctx.owner().getId()).orElseThrow();
        var rc = resolutionContextFactory.build(actor, ws);

        String expected = "Unknown field 'assigne'. Did you mean 'assignee'?";

        Query filter = HqlParser.parse("assigne = \"someone\"");
        assertThatThrownBy(() -> validator.validate(filter, rc))
                .isInstanceOf(HqlSemanticException.class)
                .hasMessage(expected);
        assertThatThrownBy(() -> compiler.buildCountQuery(filter, actor, ws, rc))
                .as("the compiled path must carry the hint too — a second wording is a second "
                    + "implementation of the same answer")
                .isInstanceOf(HqlSemanticException.class)
                .hasMessage(expected);

        Query sort = HqlParser.parse("ORDER BY assigne ASC");
        assertThatThrownBy(() -> validator.validate(sort, rc))
                .isInstanceOf(HqlSemanticException.class)
                .hasMessage(expected);
        assertThatThrownBy(() -> compiler.buildPageQuery(sort, actor, ws, rc))
                .isInstanceOf(HqlSemanticException.class)
                .hasMessage(expected);
    }

    /**
     * The shipped {@link FieldRegistry} plus one reserved-but-unavailable name.
     *
     * <p>A test double rather than a production entry: the whole point of the reserved state is
     * that nothing occupies it right now, and adding a real stub field to the catalog to make a
     * test possible would put a name into the product's vocabulary that the product does not
     * have. Everything else still resolves through the real registry, so the two tests differ in
     * exactly one name.
     */
    private static final class ReservingRegistry extends FieldRegistry {

        private static final FieldDescriptor STUB = new FieldDescriptor(
                RESERVED, FieldDataType.NUMBER,
                Set.of(ComparisonOp.EQ, ComparisonOp.NEQ, ComparisonOp.GT, ComparisonOp.LT),
                false, true, true, "storyPoints", null, List.of(), false);

        @Override
        public Optional<FieldDescriptor> find(String name) {
            if (RESERVED.equalsIgnoreCase(name)) return Optional.of(STUB);
            return super.find(name);
        }
    }

    /**
     * Bind a workspace-scoped NUMBER custom field with an explicit {@code key} to the ctx
     * project, through a field set — the same route the admin console takes and the one
     * {@link ResolutionContextFactory} reads.
     */
    private UUID bindCustomField(Ctx ctx, String key, String name) {
        return bindCustomField(ctx, key, name, FieldType.NUMBER);
    }

    /** As above, for a field of a chosen {@link FieldType}. */
    private UUID bindCustomField(Ctx ctx, String key, String name, FieldType type) {
        var def = new FieldDef();
        def.setScopeWorkspaceId(ctx.wsId());
        def.setKey(key);
        def.setName(name + " " + Math.abs(UUID.randomUUID().hashCode()));
        def.setType(type);
        def = fieldDefRepository.save(def);

        var set = new FieldSet();
        set.setName("Field resolution set " + Math.abs(UUID.randomUUID().hashCode()));
        set = fieldSetRepository.save(set);

        var item = new FieldSetItem();
        item.setSet(set);
        item.setField(def);
        item.setPosition((short) 0);
        item.setRequired(false);
        item.setShowOnCreate(true);
        fieldSetItemRepository.save(item);

        var project = projectRepository.findById(ctx.projectId()).orElseThrow();
        project.setFieldSet(set);
        projectRepository.save(project);
        return def.getId();
    }
}
