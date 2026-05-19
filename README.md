# rules_jena

Apache Jena toolchain implementations for rules_rdf — SPARQL engine (ARQ), SHACL validator, Turtle/N-Triples serializers, OWL reasoner. Java tools built via rules_java + Maven.

## Status: v0.0.1 — scaffold

No public surface yet. See `CHANGELOG.md` for what has shipped.

## Install

`.bazelrc`:

```
common --registry=https://raw.githubusercontent.com/fastverk/bazel-registry/main/
common --registry=https://bcr.bazel.build/
```

`MODULE.bazel`:

```python
bazel_dep(name = "rules_jena", version = "0.0.1")
```
