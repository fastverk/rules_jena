"""Provider types for the rules_jena public API.

The data primitives (`jena_model`, `jena_dataset`, `jena_rule_set`,
`jena_reasoner`) are provider-only — they carry references to
files + small Jena-shaped config, no build actions. Build-action
rules (`jena_reason`, the rules_rdf-driven `rdf_validate_test`,
etc.) consume them.

Every data-providing rule also emits the abstract `RdfDatasetInfo`
from rules_rdf, so jena_model / jena_dataset are drop-in
replacements for `rdf_dataset` in any rules_rdf rule. Consumers
who want Jena-aware features (named graphs, rule sets, OWL
profiles) reach for the Jena providers; everyone else stays on
the abstract interface.

The names use the package-prefixed convention (`JenaXInfo`) so
that an unwrapped `JenaModelInfo` import is unambiguous next to
the rules_rdf `RdfDatasetInfo`.
"""

JenaModelInfo = provider(
    doc = "A single Jena Model (RDF graph). Provider-only — the " +
          "files declared on the rule remain the source of truth.",
    fields = {
        "files": "depset[File]: source files concatenated to form the model.",
        "in_format": "str: serialization (turtle, ntriples, nquads, " +
                     "trig, jsonld, rdfxml). Matches the rules_rdf " +
                     "RDF_FORMATS vocabulary.",
        "base_iri": "str: optional base IRI for relative references " +
                    "in the source files. Empty string = no base.",
    },
)

JenaDatasetInfo = provider(
    doc = "A Jena Dataset (collection of named graphs + an optional " +
          "default graph). Used by rules that need named-graph " +
          "addressability (Fuseki, multi-graph SPARQL).",
    fields = {
        "default_graph": "JenaModelInfo | None: triples that live " +
                         "outside any named graph.",
        "named_graphs": "dict[str, JenaModelInfo]: graph IRI → " +
                        "model. Order-preserving.",
    },
)

JenaRuleSetInfo = provider(
    doc = "A set of Jena rule files consumed by the rule-engine " +
          "reasoner (Jena's RETE-based forward/backward inference). " +
          "See https://jena.apache.org/documentation/inference/ for " +
          "the rule syntax. Distinct from SPARQL .rq files.",
    fields = {
        "files": "depset[File]: .rule files in the set.",
    },
)

JenaReasonerInfo = provider(
    doc = "A Jena reasoner configuration. Either a built-in profile " +
          "(`rdfs`, `owl-rl`, `owl-mini`, `owl-micro`) or a custom " +
          "rule set; never both. Consumed by `jena_reason` and by " +
          "the `rdf_reasoner_toolchain_type` plugin contract.",
    fields = {
        "profile": "str: built-in profile name, or empty if custom.",
        "rule_set": "JenaRuleSetInfo | None: rule set for the " +
                    "`custom` profile.",
    },
)

JenaSchemagenInfo = provider(
    doc = "A Java vocabulary class generated from an RDF/OWL ontology " +
          "by `jena.schemagen`. Carries the generated source file plus " +
          "the package + classname so downstream codegen-of-codegen " +
          "rules can name-import without re-deriving them.",
    fields = {
        "java_src": "File: the generated `<classname>.java`.",
        "package": "str: Java package (e.g. `dev.fastverk.agora.vocab`).",
        "classname": "str: simple class name (e.g. `Schema`, `Prov`).",
        "namespace": "str: ontology namespace IRI the class enumerates.",
    },
)
