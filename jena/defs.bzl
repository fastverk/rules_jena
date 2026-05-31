"""Public API surface for rules_jena.

Re-exports the v0.2 user-facing rules (Bazel-idiomatic Jena data
primitives) + the `JENA_DEPS` Maven label set shared with anyone
writing their own Jena `java_binary`.

```python
load("@rules_jena//jena:defs.bzl",
     "JENA_DEPS",
     "jena_model", "jena_dataset", "jena_rule_set", "jena_reasoner",
     "JenaModelInfo", "JenaDatasetInfo", "JenaRuleSetInfo", "JenaReasonerInfo")
```

Pair with the rules_rdf user-facing test rules (`sparql_query_test`,
`rdf_validate_test`) — `jena_model` / `jena_dataset` emit both
`JenaModelInfo` / `JenaDatasetInfo` AND `RdfDatasetInfo`, so they're
drop-in replacements for `rdf_dataset` in any rules_rdf rule.

rules_jena's `MODULE.bazel` auto-registers four toolchains
satisfying every rules_rdf toolchain type — pulling in
`rules_jena` is enough to run any of `sparql_query_test`,
`rdf_validate_test`, `rdf_transform`, `rdf_reason`. v0.2's
`jena_reason` build action is the consumer-facing alternative
when a downstream rule wants a concrete file artifact instead of
the test-shaped `rdf_reason`.
"""

load(":dataset.bzl", _jena_dataset = "jena_dataset")
load(":model.bzl", _jena_model = "jena_model")
load(
    ":providers.bzl",
    _JenaDatasetInfo = "JenaDatasetInfo",
    _JenaModelInfo = "JenaModelInfo",
    _JenaReasonerInfo = "JenaReasonerInfo",
    _JenaRuleSetInfo = "JenaRuleSetInfo",
)
load(":reasoner.bzl", _jena_reasoner = "jena_reasoner")
load(":rules.bzl", _jena_rule_set = "jena_rule_set")
load(":dataset_library.bzl", _jena_dataset_library = "jena_dataset_library")
load(":schemagen.bzl", _jena_schemagen = "jena_schemagen")
load(
    ":providers.bzl",
    _JenaSchemagenInfo = "JenaSchemagenInfo",
)

# Maven labels every Jena-using java_binary depends on. Five
# entries — `jena-arq`, `-core`, `-base`, `-iri`, plus
# `slf4j-simple`. SHACL is not in the list; depend on
# `@jena_maven//:org_apache_jena_jena_shacl` explicitly when you
# need it (the `jena_shacl` java_binary does).
JENA_DEPS = [
    "@jena_maven//:org_apache_jena_jena_arq",
    "@jena_maven//:org_apache_jena_jena_core",
    "@jena_maven//:org_apache_jena_jena_base",
    "@jena_maven//:org_apache_jena_jena_iri",
    "@jena_maven//:org_slf4j_slf4j_simple",
]

# Re-exported rules.
jena_model = _jena_model
jena_dataset = _jena_dataset

jena_rule_set = _jena_rule_set
jena_reasoner = _jena_reasoner
jena_dataset_library = _jena_dataset_library
jena_schemagen = _jena_schemagen

# Re-exported providers.
JenaModelInfo = _JenaModelInfo
JenaDatasetInfo = _JenaDatasetInfo
JenaRuleSetInfo = _JenaRuleSetInfo
JenaReasonerInfo = _JenaReasonerInfo
JenaSchemagenInfo = _JenaSchemagenInfo
