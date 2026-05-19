# rules_jena

Apache Jena toolchain implementations for [`rules_rdf`](https://github.com/fastverk/rules_rdf) —
SPARQL engine (ARQ), SHACL validator, Turtle/N-Triples serializers, OWL
reasoner. Java tools built via `rules_java` + Maven (pinned through
`rules_jvm_external`).

`rules_jena` is the analog for `rules_rdf` that
[`rules_openapi`](https://github.com/fastverk/rules_openapi) is for
`rules_jsonschema`: a concrete, language-specific implementation
satisfying an abstract toolchain contract. The contract lives in
`rules_rdf`; the binaries that fulfil it live here.

## Status: v0.1.1

What ships:

- **`@jena_maven`** — Apache Jena 5.2.0 + slf4j-simple 2.0.16,
  pinned via `rules_jvm_external` with a committed
  `maven_install.json`. Same artifact set as the production
  `kg/java/` `JENA_DEPS` constant.
- **`JENA_DEPS`** (from `@rules_jena//jena:defs.bzl`) — the five
  Maven labels every Jena-using `java_binary` needs, exposed as a
  publicly re-exportable Starlark constant. Downstream consumers
  `load("@rules_jena//jena:defs.bzl", "JENA_DEPS")` and spread it
  into their `deps` attr instead of hand-rolling their own list.
  Stardoc reference in [`docs/defs.md`](docs/defs.md).
- **`//jena/sparql:jena_sparql`** — `java_binary` implementing
  `rules_rdf`'s `sparql_engine_toolchain_type` plugin contract.
  Reads RDF from stdin, executes a SPARQL query from
  `--query=<path>`, emits results (TSV / CSV / JSON / SRX for
  SELECT, Turtle for CONSTRUCT/DESCRIBE) to stdout. SELECT + ASK
  + CONSTRUCT + DESCRIBE all supported. `--fail-on-nonempty`
  drives the zero-row gate idiom.
- **`//jena:jena_sparql_toolchain_def`** — toolchain registration.
  Auto-registered in `MODULE.bazel` so consumers don't have to.
- **Conformance test** (`//jena:jena_sparql_conforms`) — runs
  `rules_rdf`'s `rdf_plugin_contract_test` driver against the
  binary. All four scenarios pass: `valid_minimal`,
  `malformed_input`, `unknown_flag`, `determinism`.
- **End-to-end smoke** (`examples/smoke/`) — a FOAF dataset + a
  SPARQL zero-row gate, resolved through the registered
  toolchain. `bazel test //examples/smoke:all` passes.

Deferred to v0.2 (see [docs/ROADMAP.md](docs/ROADMAP.md)):

- SHACL validator (`jena_shacl`) → `rdf_validator_toolchain_type`.
- RIOT format converter (`jena_riot`) →
  `rdf_serializer_toolchain_type`.
- OWL reasoner (`jena_reasoner`) → `rdf_reasoner_toolchain_type`.
- Port `Loader.java` + `Writer.java` from `~/Documents/rfcs/kg/java/`
  as public `//jena:loader` + `//jena:writer` `java_library`
  targets.

## Planned implementations

Each row binds one `rules_rdf` toolchain type to a Jena-backed
`java_binary`. All binaries take their input on stdin / argv and emit
results on stdout, matching the `rules_rdf` plugin contract.

| `rules_rdf` toolchain type | Jena backing | Pattern source |
|---|---|---|
| `sparql_engine_toolchain_type` | ARQ (`org.apache.jena.query.QueryExecutionFactory`) — read `.rq` from argv, `.ttl` from stdin, results to stdout | `gate_query_smoke` in `kg/java/BUILD.bazel` |
| `shacl_validator_toolchain_type` | `org.apache.jena.shacl.ShaclValidator` — shapes + data graph in, conformance report out | `gate_shacl` / `GateShacl.java` |
| `rdf_serializer_toolchain_type` | Jena's `RDFDataMgr` + the deterministic Turtle writer patterns from `Writer.java` (byte-stable round-trip + parse-equivalence) | `Writer.java` + `WriterTest.java` |
| `owl_reasoner_toolchain_type` | `ReasonerRegistry.getOWLMicroReasoner()` — matches what `kg_reasoner` runs in production today | `kg/java/reasoner:kg_reasoner` |

The shared corpus-loading library (`kg.Loader`) ports to a public
`//:rdf_io` `java_library` that every toolchain binary depends on.

## Sources of inspiration

The patterns this repo will package as toolchains all already exist in
production today, in the Aion RFC knowledge-graph Java tree
(`~/Documents/rfcs/kg/java/`). See [`docs/SOURCES.md`](docs/SOURCES.md)
for the per-file catalog of what gets ported and what each source
teaches:

- **`Loader.java`** — single-source-of-truth for "load every TTL under
  a corpus root into one in-memory `Dataset`". The shared dependency
  for every binary that reads RDF.
- **`Writer.java`** — deterministic Turtle serializer with byte-stable
  round-trip (the property the corresponding test locks down).
- **`GateHarness.java`** + **`Gates.java`** — orchestrates SPARQL
  zero-row gates and SHACL conformance against a `Dataset`.
- **`GateZeroRows.java`**, **`GateQuerySmoke.java`**,
  **`GateShacl.java`** — the three reusable PR-gate shapes.
- **`KgReasoner.java`** (under `kg/java/reasoner/`) — OWL-MICRO
  inference over a corpus with Jena rule files.

`rules_jena` will extract these into reusable, contract-conforming
toolchain binaries plus a small public `:rdf_io` library, deleting the
hand-rolled `JENA_DEPS` lists from downstream consumers.

## Install

`.bazelrc`:

```
common --registry=https://raw.githubusercontent.com/fastverk/bazel-registry/main/
common --registry=https://bcr.bazel.build/
```

`MODULE.bazel`:

```python
bazel_dep(name = "rules_jena", version = "0.1.1")
```

`rules_rdf`, `rules_java`, and `rules_jvm_external` (the Maven
artifact pinner) are pulled in transitively once toolchains land in
v0.1. The default Jena-backed toolchains will be registered
automatically.

## Compatibility

- **Bazel**: 7.4+, bzlmod required.
- **Java**: 17+.
- **Jena**: the `JENA_DEPS` set used by the source corpus today —
  `jena-arq`, `jena-core`, `jena-base`, `jena-iri`, plus
  `slf4j-simple` for runtime logging. Maven coordinates pinned in
  v0.2.

## License

MIT.
