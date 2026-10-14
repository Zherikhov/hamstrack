package com.hamstrack.ops;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The one reader of a YAML file this repository asserts about — a provisioned Grafana tree, a
 * compose file, a GitHub workflow — shared by every test that reads one so that no two of them can
 * disagree about what a file, a map or a scalar is. Parsed, not regex-matched: these trees are
 * mostly comments on purpose, and a {@code uid:}, a {@code mem_limit:} or an {@code if: always()}
 * mentioned in a comment must not satisfy a check for one.
 *
 * <p><strong>Public since HD-311</strong>, and for the reason the class exists at all: a seal
 * outside this package needed a workflow step read ({@code SuiteCoverageGuardTest}, over
 * {@code .github/workflows/build.yml}), and the alternative was a seventh hand-rolled reader whose
 * first bug would have been YAML comment-blindness — the exact defect this javadoc opens with, and
 * the one HD-343 measured in a build file. The parser is shared; what each caller asserts is not.
 *
 * <p>Named for the package rather than for Grafana since HD-299, when the compose contract joined
 * it: a helper named after its first caller is the shape that gets copied by its second.
 *
 * <p>Duplicate keys are an error here because they are an error in Grafana's reader too (Go's
 * yaml.v3) and in Compose's, and because a duplicate that IS tolerated is the worse failure: the
 * second value wins over the one whose comment explains it.
 */
public final class OpsYaml {

    private OpsYaml() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(Path file) {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try {
            Object root = new Yaml(options).load(Files.readString(file, StandardCharsets.UTF_8));
            return root instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    public static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    /** A present, non-blank scalar, rendered the way YAML handed it over. */
    public static String text(Object value) {
        if (value == null) {
            return null;
        }
        String rendered = String.valueOf(value).strip();
        return rendered.isEmpty() ? null : rendered;
    }
}
