package com.hamstrack.common.validation;

import com.hamstrack.common.testsupport.Doors;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>No request record carries a primitive component (HD-296; the HD-49 / HD-73 class).</strong>
 *
 * <p>Jackson 3 (Boot 4) enables {@code FAIL_ON_NULL_FOR_PRIMITIVES}, so any JSON body that omits a
 * primitive field — every partial PATCH, every client that sends only what changed — fails
 * deserialization before validation runs and answers {@code 400 "Failed to read request"} for the
 * whole body. {@code UpdateIssueRequest}'s {@code clear*} flags did that to every issue update; the
 * admin set-upsert items did it again ({@code AdminUpsertBooleanFlagOmittedTest} is the member test
 * this class generalises). The population is {@link Doors#requestRecords()}: every record reachable
 * from a {@code @RequestBody} plus every record named {@code *Request}. Response records may keep
 * primitives — this rule is about what is <em>read</em> from a body.
 *
 * <p>Green on the day it landed (0 offenders), which is why its negative control is recorded on the
 * ticket: {@code RolePermissionEntry.ownOnly} changed to {@code boolean} → red naming it.
 */
class RequestRecordBoxedFieldsTest {

    private static final String WHAT_TO_DO = """

            A REQUEST RECORD CARRIES A PRIMITIVE COMPONENT (the HD-49 / HD-73 class).

            %s

            Jackson 3 (Boot 4) enables FAIL_ON_NULL_FOR_PRIMITIVES, so any JSON body that omits this
            field — every partial PATCH, every client that sends only what changed — fails
            deserialization before validation runs and answers 400 "Failed to read request" for the
            whole body. UpdateIssueRequest's clear* flags did that to every issue update; the admin
            set-upsert items did it again (HD-73).

            Fix: box the type (Boolean / Integer / Long …) and coalesce in the compact constructor —
            `this.flag = flag != null && flag;` — so the absent case binds to the default the
            primitive would have had. Response records may keep primitives: this rule is about
            what is READ from a body, and the population is every record reachable from a
            @RequestBody plus every record named *Request.
            """;

    @Test
    void noRequestRecordCarriesAPrimitiveComponent() {
        var offenders = new ArrayList<String>();
        for (var record : Doors.requestRecords().floor(45)) {
            for (var component : record.type().getRecordComponents()) {
                if (component.getType().isPrimitive()) {
                    offenders.add("  " + record.type().getSimpleName() + "." + component.getName() + " : "
                                  + component.getType().getName() + "   (" + reached(record) + ")");
                }
            }
        }

        assertThat(offenders).as(WHAT_TO_DO, String.join("\n", offenders)).isEmpty();
    }

    private static String reached(Doors.RequestRecord record) {
        if (record.byNameOnly()) {
            return "by name";
        }
        var chain = new ArrayList<String>();
        record.via().forEach(c -> chain.add(c.getSimpleName()));
        var mounts = record.mountedBy().stream().map(Doors.Handler::id).sorted().toList();
        return "mounted by " + String.join(", ", mounts) + (chain.isEmpty() ? "" : "; via " + String.join(" > ", chain));
    }
}
