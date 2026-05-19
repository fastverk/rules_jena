"""`jena_reasoner(name, profile|rule_set)` — declare a reasoner
configuration.

Provider-only. Either a built-in profile or a custom rule set;
the rule rejects both-or-neither.

Built-in profiles map onto Jena's
[`ReasonerRegistry`](https://jena.apache.org/documentation/inference/):

| `profile` | Jena equivalent |
|---|---|
| `rdfs` | `ReasonerRegistry.getRDFSReasoner()` |
| `owl-rl` | `ReasonerRegistry.getOWLReasoner()` |
| `owl-mini` | `ReasonerRegistry.getOWLMiniReasoner()` |
| `owl-micro` | `ReasonerRegistry.getOWLMicroReasoner()` |
| `custom` | `GenericRuleReasoner` with the given `rule_set`. |

The Aion production `kg_reasoner` uses `owl-micro` plus
purpose-written Jena rule files; both shapes have first-class
support here.

```python
load("@rules_jena//jena:defs.bzl", "jena_rule_set", "jena_reasoner")

jena_rule_set(name = "kg_rules", rules = glob(["rules/*.rule"]))

jena_reasoner(name = "owl_micro_plus_kg", profile = "custom", rule_set = ":kg_rules")
```

To actually apply the reasoner to a base model, use `jena_reason`
(which runs a build action) or the abstract `rdf_reason` rule
from rules_rdf (which resolves the reasoner toolchain).
"""

load(":providers.bzl", "JenaReasonerInfo", "JenaRuleSetInfo")

# Mirror Jena's ReasonerRegistry. Adding new profiles is a v0.3
# conversation — the reasoner contract leaves room via the
# rdf_reasoner toolchain's `--profile` flag.
_BUILTIN_PROFILES = ["rdfs", "owl-rl", "owl-mini", "owl-micro"]
_ALL_PROFILES = _BUILTIN_PROFILES + ["custom"]

def _jena_reasoner_impl(ctx):
    if ctx.attr.profile == "custom":
        if ctx.attr.rule_set == None:
            fail(
                "jena_reasoner: profile = 'custom' requires `rule_set`. " +
                "Use a built-in profile ({}) if you don't have rules.".format(
                    ", ".join(_BUILTIN_PROFILES),
                ),
            )
        rule_set_info = ctx.attr.rule_set[JenaRuleSetInfo]
    elif ctx.attr.rule_set != None:
        fail(
            "jena_reasoner: `rule_set` is only meaningful with " +
            "profile = 'custom' (got profile = '{}').".format(ctx.attr.profile),
        )
    else:
        rule_set_info = None

    return [JenaReasonerInfo(
        profile = ctx.attr.profile,
        rule_set = rule_set_info,
    )]

jena_reasoner = rule(
    implementation = _jena_reasoner_impl,
    attrs = {
        "profile": attr.string(
            default = "rdfs",
            values = _ALL_PROFILES,
            doc = "Built-in profile name or `custom`. `custom` " +
                  "requires `rule_set`.",
        ),
        "rule_set": attr.label(
            providers = [JenaRuleSetInfo],
            doc = "A `jena_rule_set` label. Required iff " +
                  "profile = 'custom'.",
        ),
    },
    provides = [JenaReasonerInfo],
    doc = "A Jena reasoner configuration (provider-only).",
)
