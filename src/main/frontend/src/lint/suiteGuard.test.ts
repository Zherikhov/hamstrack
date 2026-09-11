import { describe, it, expect } from 'vitest'
import PACKAGE_JSON_SOURCE from '../../package.json?raw'
import POM_SOURCE from '../../../../../pom.xml?raw'
import VITEST_CONFIG_SOURCE from '../../vitest.config.ts?raw'
import { code } from './source'

/**
 * **The wiring that makes the vitest suite answerable, sealed from the vitest side
 * (HD-301).**
 *
 * `SuiteCoverageGuard` refuses a `mvnw test` whose vitest run executed fewer test
 * files than `src/main/frontend` contains, and `src/test/suiteRecorder.ts` is what
 * tells it which ran. Both are useless if the suite is simply **unhooked from the
 * Maven path**, and that is a category rather than one accident. Its members, and
 * the count is deliberately not stated in this sentence — a number goes stale one
 * entry before the list does:
 *
 * 1. `package.json` `scripts.test` — rewritten with a `--reporter`, a `--config`,
 *    a directory argument or a filter;
 * 2. `pom.xml`'s `npm-test` execution — deleted, moved off the `test` phase, or
 *    given different arguments;
 * 3. `pom.xml`'s `npm-test` `<environmentVariables>` — the run identity the guard
 *    matches records by;
 * 4. `pom.xml`'s `test-tree-coverage-guard` execution — deleted, or stripped of
 *    the `<fail>`/`resultproperty` pair that turns a non-zero exit into a build
 *    failure;
 * 5. `vitest.config.ts` `reporters` — the recorder dropped from the array, which
 *    is also what a `--reporter=…` flag does to the whole list;
 * 6. `vitest.config.ts` — a `passWithNoTests` borrowed from someone's CI snippet,
 *    which turns "matched nothing" from an exit 1 into a green run;
 * 7. the antrun execution's own `<skip>`, which `-Dmaven.antrun.skip=true` reaches
 *    unless the POM sets it explicitly — the one member that lives one level ABOVE
 *    both suites.
 *
 * One member is sealed elsewhere rather than here: `test.include` is pinned as a
 * whole line by
 * `VacuousVerificationRulesTest#frontendPopulationStillEqualsTheVitestInclude`
 * from the JUnit side; a second copy here would be a drift waiting for the diff
 * that updates one of them.
 *
 * **Why this file is in the SPA and the refusal is not.** Everything above is
 * disarmed by an edit to a build file, and a guard living inside the artifact it
 * guards goes quiet in the same commit that needs it — so the *decision* runs as
 * a Maven-bound antrun step outside `frontend-maven-plugin`, where neither
 * `frontend.skip` nor a deleted npm execution can reach it, and this file reads
 * those build files as **text** from the other side. The two guard each other:
 * the antrun step keeps the vitest suite whole, and the vitest suite keeps the
 * antrun step wired.
 *
 * **What "neither can reach it" leaves out, because stating it as an absolute was
 * wrong once:** neither *suite* can, and a property of the guard's own host plugin
 * can. `-Dmaven.antrun.skip=true` ran both suites in full, skipped the guard and
 * exited BUILD SUCCESS (measured 2026-09-11) — the same shape that made a sibling
 * `frontend-maven-plugin` execution unacceptable, one level up. It is closed by
 * `<skip>false</skip>` in the execution rather than described, and member 7 above
 * is the assertion that keeps it closed.
 *
 * **The residual, stated rather than hidden:** deleting the antrun execution *and*
 * this file in one diff is refused by nothing. That is a two-file deliberate edit
 * visible in review, which is a different thing from a silent drop.
 */

/** The `frontend-maven-plugin` block, so "outside it" can be asserted rather than assumed. */
const FRONTEND_PLUGIN = (() => {
  const start = POM_SOURCE.indexOf('<artifactId>frontend-maven-plugin</artifactId>')
  const end = POM_SOURCE.indexOf('</plugin>', start)
  return start < 0 || end < 0 ? '' : POM_SOURCE.slice(start, end)
})()

function execution(id: string): string {
  return new RegExp(`<id>${id}</id>[\\s\\S]*?</execution>`).exec(code(POM_SOURCE))?.[0] ?? ''
}

describe('the SPA suite cannot be unhooked from the Maven path in silence', () => {
  it('runs the whole suite through a bare `vitest run`, with nothing that narrows it', () => {
    const scripts = JSON.parse(PACKAGE_JSON_SOURCE).scripts as Record<string, string>
    expect(
      scripts.test,
      'the `test` script is no longer a bare `vitest run`. A `--reporter=…` flag REPLACES the ' +
      'reporters array rather than adding to it, so the run record is never written and the ' +
      'coverage guard reports "the suite did not run" for a suite that ran fine; a `--config`, ' +
      'a directory argument or a filter narrows what is collected while the tree the guard ' +
      'compares against does not move. Put configuration in vitest.config.ts, where the ' +
      'assertions below can read it.',
    ).toBe('vitest run')
  })

  it('is wired into the pom at the test phase, where -DskipTests reaches it', () => {
    const npmTest = execution('npm-test')
    expect(
      npmTest,
      'the npm-test execution is gone from pom.xml, so `mvnw verify` no longer runs the SPA ' +
      'suite at all. The coverage guard turns that into a refusal rather than into silence — ' +
      'which is the whole reason it is an antrun step and not a sibling execution of this one — ' +
      'but the remedy is here: restore it (goal `npm`, phase `test`, arguments `run test`).',
    ).not.toBe('')
    expect(
      npmTest,
      'the npm-test execution moved off the `test` phase. frontend-maven-plugin honours ' +
      '-DskipTests only for an execution bound to `test` or `integration-test`, so the SPA ' +
      'suite loses the shared off-switch and the Dockerfile`s `package -DskipTests` starts ' +
      'paying for a suite CI already ran.',
    ).toMatch(/<phase>test<\/phase>/)
    expect(
      npmTest,
      'the npm-test execution no longer runs `npm run test`, so the Maven path and the local ' +
      'loop are running different things and only one of them is the gated suite.',
    ).toMatch(/<arguments>run test<\/arguments>/)
  })

  it('hands the run identity to the recorder, which is what makes a record fresh', () => {
    const npmTest = execution('npm-test')
    for (const name of ['HAMSTRACK_TEST_RUN_ID', 'HAMSTRACK_TEST_RUN_DIR']) {
      expect(
        npmTest,
        `the npm-test execution no longer passes ${name} to the SPA suite. Freshness is an ` +
        'IDENTITY, never a file timestamp: the guard accepts only records carrying THIS Maven ' +
        "session's id, so without this line every run writes a record the guard ignores and " +
        'every build is refused with "no execution record for this run". Restore ' +
        '<environmentVariables> on the npm-test execution.',
      ).toContain(name)
    }
  })

  it('keeps the recorder in the reporters array, which no floor can check', () => {
    const declared = /reporters:\s*\[([^\]]*)]/.exec(code(VITEST_CONFIG_SOURCE))?.[1] ?? ''
    const entries = [...declared.matchAll(/'([^']+)'/g)].map((m) => m[1])
    expect(
      entries,
      'vitest.config.ts `reporters` no longer equals its declared list. Dropping ' +
      './src/test/suiteRecorder.ts does not make anything green — the guard finds no record ' +
      'and refuses, naming this array among its causes — but it costs a reader the ' +
      'wrong diagnosis for a while. Dropping ./src/test/marginReporter.ts silently ends the ' +
      'HD-240 margin survey instead, which nothing else would say.',
    ).toEqual(['default', './src/test/marginReporter.ts', './src/test/suiteRecorder.ts'])
    expect(
      (code(VITEST_CONFIG_SOURCE).match(/\breporters:/g) ?? []).length,
      'vitest.config.ts has more than one `reporters` key, so the list asserted above is no ' +
      'longer the whole set. Keep one.',
    ).toBe(1)
  })

  it('never lets an empty match become a green run', () => {
    // `vitest run` exits 1 on `No test files found` and this repo has nothing that
    // turns that off — verified in HD-242 by pointing `include` at a non-existent
    // directory. It is the one property that holds when the guard itself is
    // disarmed, so it is asserted on every surface that could grant it.
    for (const [what, source] of [
      ['vitest.config.ts', code(VITEST_CONFIG_SOURCE)],
      ['package.json', PACKAGE_JSON_SOURCE],
      ['the npm-test execution in pom.xml', execution('npm-test')],
    ] as const) {
      expect(
        source,
        `passWithNoTests appears in ${what}. It turns "the include glob matched nothing" from ` +
        'an exit 1 into a confident green run, which is the exact vacuous-green shape this ' +
        'whole mechanism exists to refuse. Delete it; if the SPA genuinely has no tests, say ' +
        'so in a diff nobody can miss.',
      ).not.toMatch(/passWithNoTests/)
    }
  })
})

describe('the refusal is reachable from the build, and not from the suite it judges', () => {
  it('keeps the antrun guard wired with the fail plumbing that makes its exit code matter', () => {
    const guard = execution('test-tree-coverage-guard')
    expect(
      guard,
      'the test-tree-coverage-guard execution is gone from pom.xml. Nothing then compares ' +
      'either suite against its tree, and both halves of both recorders go on writing records ' +
      'nobody reads — a silence that looks exactly like a clean build. Restore it (HD-265, ' +
      'HD-301): maven-antrun-plugin, phase `test`, forking ' +
      'com.hamstrack.common.testsupport.SuiteCoverageGuard.',
    ).not.toBe('')
    expect(
      guard,
      'the antrun guard moved off the `test` phase, so it no longer runs after surefire and ' +
      'the npm-test execution and can no longer see either run record.',
    ).toMatch(/<phase>test<\/phase>/)
    expect(
      guard,
      'the antrun guard no longer captures the forked JVM`s exit code into a resultproperty. ' +
      'With failonerror="false" and no resultproperty, a refusal is PRINTED and the build ' +
      'stays green — which is the same build as not checking at all.',
    ).toMatch(/resultproperty="hamstrack\.test-tree\.exit"/)
    expect(
      guard,
      'the antrun guard no longer turns that exit code into a <fail>. See above: printing a ' +
      'refusal and exiting 0 is not a gate.',
    ).toMatch(/<fail message=[\s\S]*hamstrack\.test-tree\.exit/)
    // The off-switch one level above both suites. maven-antrun-plugin's `run` goal
    // declares `skip` as an EDITABLE parameter defaulting to ${maven.antrun.skip},
    // so without this line `-Dmaven.antrun.skip=true` runs both suites in full,
    // skips the guard and exits BUILD SUCCESS — measured 2026-09-11, on an
    // invocation that refuses without the flag. An explicit value in the execution
    // wins over the property expression.
    //
    // What this assertion cannot see, stated because it is a mechanism present and
    // possibly not in effect: it reads the line as TEXT, and Maven answers an
    // unknown plugin parameter with `[WARNING] Parameter 'skipp' is unknown for
    // plugin …` rather than an error (measured 2026-09-11). A BOM bump that renamed
    // or dropped antrun's `skip` would leave this line present, inert, and green.
    // The drill that does see it is one run — `mvnw -B verify
    // -Dmaven.antrun.skip=true` must REFUSE — on every Boot BOM bump. Pinning the
    // antrun version is the alternative and contradicts the pom's own "do NOT pin".
    expect(
      guard,
      'the antrun guard no longer pins <skip>false</skip>, so `-Dmaven.antrun.skip=true` ' +
      'switches the whole check off IN PLACE: both suites run, the comparison does not, and ' +
      'the build is green. That is the same disarm-by-one-flag shape that kept this execution ' +
      'out of frontend-maven-plugin, one level up. Restore <skip>false</skip> in the ' +
      "execution's <configuration>; a run that genuinely wants no antrun has -DskipTests.",
    ).toMatch(/<skip>false<\/skip>/)
    for (const argument of ['frontendRoot=', 'frontend.skip=', 'runId=']) {
      expect(
        guard,
        `the antrun guard is no longer passed ${argument}. Without frontendRoot the SPA arm ` +
        'refuses every build (an absent input is never a stand-down); without frontend.skip a ' +
        '-Dfrontend.skip=true run refuses instead of standing down; without runId the guard ' +
        'matches records by an identity nothing wrote.',
      ).toContain(argument)
    }
  })

  it('keeps that refusal outside frontend-maven-plugin, whose skip switches the suite off', () => {
    // The load-bearing placement. `<skip>${frontend.skip}</skip>` is bound at PLUGIN level
    // precisely so every execution switches off together, so a guard living there would be
    // disarmed by the same flag — and deleted in the same block — as the thing it guards.
    expect(FRONTEND_PLUGIN, 'the frontend-maven-plugin block could not be found in pom.xml')
      .not.toBe('')
    expect(
      FRONTEND_PLUGIN,
      'the test-tree-coverage-guard execution has moved INSIDE frontend-maven-plugin. That ' +
      "plugin's <skip>${frontend.skip}</skip> is bound at plugin level so that every execution " +
      'switches off together: the guard would then be disarmed by the same flag that skips the ' +
      'suite, and deleted in the same block. Move it back to maven-antrun-plugin.',
    ).not.toContain('test-tree-coverage-guard')
    expect(
      FRONTEND_PLUGIN,
      'frontend-maven-plugin no longer binds its plugin-level <skip> to ${frontend.skip}. That ' +
      'one binding is what makes -Dfrontend.skip=true mean the same thing for every execution, ' +
      'including ones added later — and it is what the guard`s vitest arm stands down on.',
    ).toMatch(/<skip>\$\{frontend\.skip\}<\/skip>/)
  })

  it('declares the guard after both npm executions, which is the only thing that orders them', () => {
    // All three are bound to `test`, so Maven runs them in DECLARATION order and there is no
    // other handle. The pom claims this in prose; without this line a reorder is silent and
    // the guard reads a record that has not been written yet — reported as "the suite did not
    // run", which sends the reader to the wrong question entirely.
    const at = (id: string) => POM_SOURCE.indexOf(`<id>${id}</id>`)
    const order = ['npm-lint', 'npm-test', 'test-tree-coverage-guard']
    for (const id of order) expect(at(id), `${id} is not in pom.xml at all`).toBeGreaterThan(-1)
    for (let i = 1; i < order.length; i += 1) {
      expect(
        at(order[i - 1]),
        `${order[i]} is now declared before ${order[i - 1]}. Both are bound to the \`test\` ` +
        'phase and Maven runs executions within a phase in declaration order, so this reorder ' +
        'changes what has happened by the time each one runs. Put them back in the order ' +
        `${order.join(' -> ')}.`,
      ).toBeLessThan(at(order[i]))
    }
  })
})
