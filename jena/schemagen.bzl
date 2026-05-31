"""`jena_schemagen(name, dataset, package, classname, namespace, …)` —
generate a Java vocabulary class from an RDF/OWL ontology.

Wraps Apache Jena's built-in `jena.schemagen` tool as a Bazel rule. For
each `https://example.org/foo#` resource in the ontology, the generated
class gains a `public static final Resource Foo = ResourceFactory.create…`
(or `Property`) constant — so Java code references the IRI by name + the
compiler catches typos.

```python
load("@rules_jena//jena:defs.bzl", "jena_model", "jena_schemagen")

jena_model(
    name = "schemaorg_ttl",
    srcs = ["schemaorg.ttl"],
    in_format = "turtle",
)

jena_schemagen(
    name = "schemaorg_vocab",
    dataset = ":schemaorg_ttl",
    namespace = "https://schema.org/",
    package = "dev.fastverk.agora.vocab",
    classname = "Schema",
)
# elsewhere:
java_library(
    name = "agora_lib",
    srcs = ["Foo.java"],   # uses Schema.SoftwareApplication, Schema.name, …
    deps = [":schemaorg_vocab"],
)
```

The rule resolves the input ontology via `JenaModelInfo` (preferred) OR
the abstract `RdfDatasetInfo` from rules_rdf — same drop-in pattern the
other rules_jena rules follow.

Macro-shaped: under the hood, a private `_jena_schemagen_src` rule runs
schemagen + emits `<classname>.java`; the public `jena_schemagen` wraps
that in a `java_library` so consumers `deps = [":vocab"]` and get both
the generated class and the Jena classpath it needs at compile time.

Schemagen options exposed today:
  * `namespace`     `-a <ns>`           — restrict output to one namespace.
  * `package`       `--package <pkg>`   — Java package for the output.
  * `classname`     `--classname <cls>` — class name (single token).
  * `use_inference` `--inference`       — apply RDFS reasoning before scan
                                          (default True; turn off only for
                                           pre-materialized closures).
  * `ontology_lang` `--owl|--rdfs|--daml`
                                        — interpretation of the ontology.

Things deliberately omitted from v0.3:
  * `--config <rdf>` (additional schemagen-config triples) — adds Jena
    config-language overhead with no current consumer; revisit when a
    real need shows up.
  * `--strict-individuals`, `--include`, `--exclude` — niche; users
    needing them can write their own `java_binary` against the
    `//jena/schemagen:jena_schemagen` tool directly.
"""

load("@rules_java//java:defs.bzl", "java_library")
load("@rules_rdf//rdf:providers.bzl", "RdfDatasetInfo")
load(":providers.bzl", "JenaModelInfo", "JenaSchemagenInfo")

_ONTOLOGY_LANGS = {
    "owl": "--owl",
    "rdfs": "--rdfs",
    "daml": "--daml",
}

# Map JenaModelInfo.in_format (and rules_rdf RDF_FORMATS) onto Jena's
# `-e <encoding>` values. Schemagen otherwise defaults to RDF/XML parsing
# and chokes on Turtle ("Content is not allowed in prolog"). Note Jena's
# legacy encoding names: "N3" covers Turtle (the parsers are compatible),
# "N-TRIPLE" is the canonical name for N-Triples, "RDF/XML" stays as-is.
_ENCODING_BY_FORMAT = {
    "turtle": "N3",
    "ntriples": "N-TRIPLE",
    "nquads": "N-QUADS",
    "trig": "TRIG",
    "jsonld": "JSON-LD",
    "rdfxml": "RDF/XML",
}

def _dataset_files_and_format(dataset_target):
    """Resolve a dataset target to its source RDF files + serialization.

    Prefers `JenaModelInfo` (carries `in_format` precisely); falls back to
    the abstract `RdfDatasetInfo` so any rules_rdf dataset (including
    `rdf_dataset`) works without a Jena wrapper. Returns a (files, format)
    tuple where `format` is the source's `in_format` string (e.g. "turtle").
    """
    if JenaModelInfo in dataset_target:
        info = dataset_target[JenaModelInfo]
        return info.files, info.in_format
    if RdfDatasetInfo in dataset_target:
        info = dataset_target[RdfDatasetInfo]
        return info.files, info.in_format
    fail("jena_schemagen: `dataset` must provide JenaModelInfo or " +
         "RdfDatasetInfo (got %s)." % str(dataset_target.label))

def _jena_schemagen_src_impl(ctx):
    files_depset, in_format = _dataset_files_and_format(ctx.attr.dataset)
    files = files_depset.to_list()
    out_java = ctx.actions.declare_file(ctx.attr.classname + ".java")

    encoding = _ENCODING_BY_FORMAT.get(in_format)
    if encoding == None:
        fail("jena_schemagen: dataset in_format %r has no Jena encoding " +
             "mapping (supported: %s)." % (
                 in_format,
                 sorted(_ENCODING_BY_FORMAT.keys()),
             ))

    # Schemagen uses the SHORT flags for most options; long forms either
    # don't exist or are silently ignored (verified vs jena-cmds 5.2.0):
    #   -e <encoding>   input format hint (`N3` covers Turtle too)
    #   -n <classname>  Java class name (--classname is silently dropped)
    #   --package <pkg> Java package (one of the few long flags that work)
    #   -a <uri>        ontology namespace to restrict output to
    #
    # We capture stdout instead of using `-o <path>`: with `-o`, schemagen
    # interprets a not-yet-existing path as a directory name and writes
    # `<path>/<classname>.java` inside it, which Bazel rejects as "output
    # declared as a file but produced as a directory". stdout dodges that.
    cmd_parts = [
        "$1",
        _ONTOLOGY_LANGS[ctx.attr.ontology_lang],
    ]
    if ctx.attr.use_inference:
        cmd_parts.append("--inference")
    cmd_parts += [
        "-e",
        encoding,
        "-a",
        "'" + ctx.attr.namespace + "'",
        "--package",
        ctx.attr.package,
        "-n",
        ctx.attr.classname,
    ]
    for f in files:
        cmd_parts += ["-i", f.path]
    cmd_parts.append("> $2")
    cmd = " ".join(cmd_parts)

    ctx.actions.run_shell(
        command = cmd,
        arguments = [ctx.executable._tool.path, out_java.path],
        tools = [ctx.executable._tool],
        inputs = files,
        outputs = [out_java],
        mnemonic = "JenaSchemagen",
        progress_message = "jena schemagen %s (%s.%s)" % (
            ctx.label,
            ctx.attr.package,
            ctx.attr.classname,
        ),
    )
    return [
        DefaultInfo(files = depset([out_java])),
        JenaSchemagenInfo(
            java_src = out_java,
            package = ctx.attr.package,
            classname = ctx.attr.classname,
            namespace = ctx.attr.namespace,
        ),
    ]

_jena_schemagen_src = rule(
    implementation = _jena_schemagen_src_impl,
    attrs = {
        "dataset": attr.label(
            mandatory = True,
            doc = "Source ontology — a `jena_model` (preferred) or any " +
                  "rules_rdf `rdf_dataset`.",
        ),
        "namespace": attr.string(
            mandatory = True,
            doc = "Ontology namespace IRI (e.g. `https://schema.org/`); " +
                  "passed to schemagen's `-a` to scope output.",
        ),
        "package": attr.string(
            mandatory = True,
            doc = "Java package for the generated class.",
        ),
        "classname": attr.string(
            mandatory = True,
            doc = "Java class name (single token; conventionally the " +
                  "vocabulary short name, e.g. `Schema`, `Prov`).",
        ),
        "use_inference": attr.bool(
            default = True,
            doc = "Apply RDFS reasoning before scanning (default True). " +
                  "Turn off only for pre-materialized closures where the " +
                  "extra reasoning adds nothing.",
        ),
        "ontology_lang": attr.string(
            default = "owl",
            values = list(_ONTOLOGY_LANGS.keys()),
            doc = "Schemagen ontology interpretation. `owl` is the right " +
                  "default for most modern vocabularies (schema.org, prov, " +
                  "skos). `rdfs` for plain-RDFS sources; `daml` for legacy.",
        ),
        "_tool": attr.label(
            default = Label("//jena/schemagen:jena_schemagen"),
            executable = True,
            cfg = "exec",
        ),
    },
    provides = [JenaSchemagenInfo],
    doc = "Internal: runs schemagen and emits the .java source. Most " +
          "consumers want the `jena_schemagen` macro, which wraps this " +
          "in a `java_library` with the Jena classpath attached.",
)

def jena_schemagen(
        name,
        dataset,
        package,
        classname,
        namespace,
        use_inference = True,
        ontology_lang = "owl",
        visibility = None):
    """A `java_library` whose source is generated from `dataset` via Jena schemagen.

    Args:
      name: target name.
      dataset: a `jena_model` (or any rules_rdf `rdf_dataset`).
      package: Java package for the generated vocabulary class.
      classname: Java class name (single token).
      namespace: ontology namespace IRI (e.g. `https://schema.org/`).
      use_inference: apply RDFS reasoning before scanning (default True).
      ontology_lang: `owl` (default), `rdfs`, or `daml`.
      visibility: forwarded to the generated `java_library`.
    """
    src_name = "_{}_src".format(name)
    _jena_schemagen_src(
        name = src_name,
        dataset = dataset,
        package = package,
        classname = classname,
        namespace = namespace,
        use_inference = use_inference,
        ontology_lang = ontology_lang,
    )
    java_library(
        name = name,
        srcs = [":" + src_name],
        # The generated class references Jena's Resource / Property /
        # ResourceFactory. Depend on the public `:jena_runtime` re-export
        # rather than `@jena_maven//...` directly so downstream consumers
        # (who can't see jena_maven across Bazel-module boundaries) build
        # without redeclaring the Jena artifacts.
        deps = ["@rules_jena//jena:jena_runtime"],
        visibility = visibility,
    )
