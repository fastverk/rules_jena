"""Shared Maven label set for Jena-using java_binary targets.

Keeps the dep list in one place across rules_jena's own binaries AND
lets downstream consumers reuse the pinned Maven coordinates without
re-declaring them.

Every `java_binary` (or `java_library`) in this repo that touches
Apache Jena depends on the same five Maven labels: `jena-arq`,
`jena-core`, `jena-base`, `jena-iri`, plus `slf4j-simple` for runtime
logging. Hard-coding that list inside `jena/sparql/BUILD.bazel` works
fine for `jena_sparql` alone, but as v0.2 adds `jena_shacl`,
`jena_riot`, and `jena_reasoner` (see `docs/ROADMAP.md`) the list
needs to live somewhere shareable.

This file is also the public re-export point for downstream users:
the production `kg/java/BUILD.bazel` that originally inspired
rules_jena maintained its own `JENA_DEPS` constant by hand. With
rules_jena pinned via bzlmod, that consumer can now write:

    load("@rules_jena//jena:defs.bzl", "JENA_DEPS")

    java_binary(
        name = "my_jena_tool",
        srcs = ["MyJenaTool.java"],
        deps = JENA_DEPS + ["//some/local:lib"],
    )

…and pick up the exact pinned coordinates this repo tests against.
Bumping Jena across consumers becomes a single rules_jena version
bump in their MODULE.bazel rather than a cross-repo coordinate hunt.
"""

JENA_DEPS = [
    "@jena_maven//:org_apache_jena_jena_arq",
    "@jena_maven//:org_apache_jena_jena_core",
    "@jena_maven//:org_apache_jena_jena_base",
    "@jena_maven//:org_apache_jena_jena_iri",
    "@jena_maven//:org_slf4j_slf4j_simple",
]
"""Maven labels every Jena-using `java_binary` in this repo depends on.

Pinned via `rules_jvm_external` against the `@jena_maven` install
declared in `MODULE.bazel`. Five entries: Apache Jena 5.2.0
(`jena-arq` + `jena-core` + `jena-base` + `jena-iri`) plus
`slf4j-simple` 2.0.16 for runtime logging.

Spread into a `java_binary`'s `deps` attr — typically alongside any
target-specific extras:

    java_binary(
        name = "my_tool",
        srcs = ["MyTool.java"],
        main_class = "com.example.MyTool",
        deps = JENA_DEPS + [":my_helper_lib"],
    )

The list is intentionally minimal: it covers core Jena + ARQ query
execution + slf4j logging. SHACL (`jena-shacl`) is *not* included —
add it explicitly when you need it. That keeps non-SHACL binaries
from pulling the SHACL classpath transitively.
"""
