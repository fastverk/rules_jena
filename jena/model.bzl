"""`jena_model(name, srcs, in_format, base_iri)` — declare one RDF
graph as a Jena-aware data primitive.

Provider-only: no Bazel actions, no parsed-form artifacts. The
`srcs` files remain the source of truth; downstream rules either
read them directly or feed them to a Java tool that parses them
into an in-memory `Model`.

Every `jena_model` ALSO emits `RdfDatasetInfo` (the abstract
provider from rules_rdf) so it's a drop-in dataset for any
rules_rdf rule:

```python
load("@rules_jena//jena:defs.bzl", "jena_model")
load("@rules_rdf//sparql:defs.bzl", "sparql_query_test")

jena_model(
    name = "ontology",
    srcs = ["ontology.ttl"],
    in_format = "turtle",
)

sparql_query_test(  # works: resolves via RdfDatasetInfo
    name = "ontology_well_formed",
    dataset = ":ontology",
    query = "queries/check.rq",
)
```

For named-graph use cases see `jena_dataset`.
"""

load("@rules_rdf//rdf:dataset.bzl", "RDF_FORMATS")
load("@rules_rdf//rdf:providers.bzl", "RdfDatasetInfo")
load(":providers.bzl", "JenaModelInfo")

# Same extension set as rules_rdf's rdf_dataset — Jena reads them
# all natively via RDFDataMgr.
_RDF_FILE_EXTENSIONS = [".ttl", ".nt", ".nq", ".trig", ".jsonld", ".rdf", ".xml"]

def _jena_model_impl(ctx):
    files = depset(ctx.files.srcs)
    return [
        DefaultInfo(files = files),
        JenaModelInfo(
            files = files,
            in_format = ctx.attr.in_format,
            base_iri = ctx.attr.base_iri,
        ),
        # Drop-in compatibility with rules_rdf rules. Jena-aware
        # consumers prefer JenaModelInfo because it also carries
        # base_iri; the abstract path drops that.
        RdfDatasetInfo(
            files = files,
            in_format = ctx.attr.in_format,
        ),
    ]

jena_model = rule(
    implementation = _jena_model_impl,
    attrs = {
        "srcs": attr.label_list(
            allow_files = _RDF_FILE_EXTENSIONS,
            mandatory = True,
            doc = "Source RDF files for this single graph. " +
                  "Concatenated in lexicographic order by Jena tools.",
        ),
        "in_format": attr.string(
            default = "turtle",
            values = RDF_FORMATS,
            doc = "Serialization of every file in `srcs`. Mixed " +
                  "formats aren't supported — pipe through " +
                  "`rdf_transform` first if you need to combine.",
        ),
        "base_iri": attr.string(
            default = "",
            doc = "Optional base IRI for resolving relative " +
                  "references in `srcs`. Empty = none.",
        ),
    },
    provides = [JenaModelInfo, RdfDatasetInfo],
    doc = "A Jena Model (single RDF graph) declared as Bazel data.",
)
