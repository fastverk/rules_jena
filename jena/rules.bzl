"""`jena_rule_set(name, rules)` — a collection of Jena rule files
for the rule-engine reasoner.

Provider-only. Consumed by `jena_reasoner(profile = "custom")` and
by the `rdf_reasoner_toolchain_type` plugin contract when the
plugin's `--rules` flag points at a file from this set.

Jena rule syntax (the RETE forward-chainer's input — distinct from
SPARQL):

```text
@prefix ex: <http://example.org/> .

[transitiveSubOrg:
    (?a ex:partOf ?b),
    (?b ex:partOf ?c)
    -> (?a ex:partOf ?c)
]
```

See https://jena.apache.org/documentation/inference/#rules. The
file extension is `.rule` by convention; .txt is tolerated.
"""

load(":providers.bzl", "JenaRuleSetInfo")

def _jena_rule_set_impl(ctx):
    files = depset(ctx.files.rules)
    return [
        DefaultInfo(files = files),
        JenaRuleSetInfo(files = files),
    ]

jena_rule_set = rule(
    implementation = _jena_rule_set_impl,
    attrs = {
        "rules": attr.label_list(
            allow_files = [".rule", ".txt"],
            mandatory = True,
            doc = "Jena rule files. Each must follow the rule-engine " +
                  "syntax at https://jena.apache.org/documentation/inference/#rules.",
        ),
    },
    provides = [JenaRuleSetInfo],
    doc = "A set of Jena rule files for the rule-engine reasoner.",
)
