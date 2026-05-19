# Changelog

All notable changes to rules_jena. The format is loosely
[Keep a Changelog](https://keepachangelog.com/) — version headers
mirror the published bazel-registry entries.

## 0.1.0 — first concrete implementation: `jena_sparql`

- Maven dep pinning via `rules_jvm_external`: Apache Jena 5.2.0
  (`jena-arq`, `jena-core`, `jena-base`, `jena-iri`, `jena-shacl`)
  + `slf4j-simple` 2.0.16. Committed `maven_install.json`.
- `jena_sparql` — `java_binary` satisfying rules_rdf's
  `sparql_engine_toolchain_type` contract. ARQ-backed; supports
  SELECT / ASK / CONSTRUCT / DESCRIBE; emits TSV / CSV / JSON /
  SRX / Turtle; honors `--fail-on-nonempty` for zero-row gates.
- `//jena:jena_sparql_toolchain_def` auto-registered by
  `MODULE.bazel` — consumers get the toolchain for free.
- Conformance gate: `//jena:jena_sparql_conforms` runs the
  rules_rdf contract driver. All four scenarios pass against the
  real Jena binary.
- End-to-end smoke (`examples/smoke/`) — FOAF dataset +
  zero-row SPARQL gate through the registered toolchain.
- `.bazelrc` pins `--java_runtime_version=remotejdk_21` so builds
  don't depend on host `JAVA_HOME`.

## 0.0.1 — scaffold

- Initial scaffold via `rels scaffold`. No public API yet.
