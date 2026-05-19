<!-- Generated with Stardoc: http://skydoc.bazel.build -->

Shared Maven label set for Jena-using java_binary targets.

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

