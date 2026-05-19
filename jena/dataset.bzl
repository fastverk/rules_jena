"""`jena_dataset(name, default_graph, named_graphs)` — a Jena
Dataset: a default graph plus a set of named graphs addressable
by IRI.

Provider-only. Composes `jena_model` labels. Like `jena_model`,
also emits `RdfDatasetInfo` (the union of all triples across the
default + named graphs) so rules_rdf rules consume it transparently.

```python
load("@rules_jena//jena:defs.bzl", "jena_model", "jena_dataset")

jena_model(name = "core", srcs = ["core.ttl"], in_format = "turtle")
jena_model(name = "facts", srcs = ["facts.ttl"], in_format = "turtle")
jena_model(name = "claims", srcs = ["claims.ttl"], in_format = "turtle")

jena_dataset(
    name = "corpus",
    default_graph = ":core",
    named_graphs = {
        "http://example.org/g/facts": ":facts",
        "http://example.org/g/claims": ":claims",
    },
)
```

Datasets without named graphs are a degenerate case — for those,
use `jena_model` directly.
"""

load("@rules_rdf//rdf:providers.bzl", "RdfDatasetInfo")
load(":providers.bzl", "JenaDatasetInfo", "JenaModelInfo")

def _jena_dataset_impl(ctx):
    default_info = None
    if ctx.attr.default_graph != None:
        default_info = ctx.attr.default_graph[JenaModelInfo]

    named = {}
    flat_files = []
    flat_format = None
    for graph_iri, label in ctx.attr.named_graphs.items():
        info = label[JenaModelInfo]
        named[graph_iri] = info
        for f in info.files.to_list():
            flat_files.append(f)
        if flat_format == None:
            flat_format = info.in_format
        elif flat_format != info.in_format:
            # Mixed-format datasets are out-of-scope; Jena consumers
            # expect to either tag each graph with its own format
            # (we don't, today) or pass the whole thing as turtle.
            fail(
                "jena_dataset: mixed in_format across named_graphs " +
                "(saw both {} and {}). All graphs in a dataset must " +
                "share a serialization in v0.2 — pipe through " +
                "rdf_transform if you need to combine.".format(
                    flat_format,
                    info.in_format,
                ),
            )

    if default_info != None:
        for f in default_info.files.to_list():
            flat_files.append(f)
        if flat_format == None:
            flat_format = default_info.in_format
        elif flat_format != default_info.in_format:
            fail(
                "jena_dataset: default_graph format {} doesn't match " +
                "named_graphs format {}.".format(
                    default_info.in_format,
                    flat_format,
                ),
            )

    # Sort + dedupe by short_path so the flattened set order is
    # deterministic across consumers.
    flat_files = sorted({f.short_path: f for f in flat_files}.values(),
                        key = lambda f: f.short_path)
    rdf_flat = depset(flat_files)

    return [
        DefaultInfo(files = rdf_flat),
        JenaDatasetInfo(default_graph = default_info, named_graphs = named),
        RdfDatasetInfo(files = rdf_flat, in_format = flat_format or "turtle"),
    ]

jena_dataset = rule(
    implementation = _jena_dataset_impl,
    attrs = {
        "default_graph": attr.label(
            providers = [JenaModelInfo],
            doc = "A `jena_model` whose triples form the dataset's " +
                  "default graph (unnamed). Optional.",
        ),
        "named_graphs": attr.string_keyed_label_dict(
            allow_files = False,
            doc = "Map of graph IRI → `jena_model` label. Each entry " +
                  "becomes a named graph in the resulting Dataset.",
        ),
    },
    provides = [JenaDatasetInfo, RdfDatasetInfo],
    doc = "A Jena Dataset composed of named-graph `jena_model`s + " +
          "an optional default graph.",
)
