package fastverk.rules_jena.reasoner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.Reasoner;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.Rule;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RiotException;

/**
 * Jena-backed implementation of the rules_rdf
 * {@code rdf_reasoner_toolchain_type} plugin contract.
 *
 * <p>Reads a base graph from stdin, applies the configured
 * reasoner, and emits triples (Turtle) to stdout.
 * {@code --include-base} switches between "derived only"
 * (default) and "base + derived" output.
 *
 * <p>Profiles map onto Jena's
 * {@link ReasonerRegistry}:
 * <ul>
 *   <li>{@code rdfs}      → {@code getRDFSReasoner()}</li>
 *   <li>{@code owl-rl}    → {@code getOWLReasoner()} (full OWL)</li>
 *   <li>{@code owl-mini}  → {@code getOWLMiniReasoner()}</li>
 *   <li>{@code owl-micro} → {@code getOWLMicroReasoner()}</li>
 *   <li>{@code custom}    → {@link GenericRuleReasoner} with
 *                          rules from {@code --rules=PATH}</li>
 * </ul>
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0: success.</li>
 *   <li>2: argv error.</li>
 *   <li>3: malformed input on stdin / rules file.</li>
 * </ul>
 */
public final class JenaReasonerBin {

    private static final Set<String> KNOWN_FLAGS = Set.of(
            "--rule-name", "--in-format", "--profile", "--rules", "--include-base");

    private static final Map<String, Lang> IN_FORMATS = Map.ofEntries(
            Map.entry("turtle", Lang.TURTLE),
            Map.entry("ntriples", Lang.NTRIPLES),
            Map.entry("nquads", Lang.NQUADS),
            Map.entry("trig", Lang.TRIG),
            Map.entry("jsonld", Lang.JSONLD),
            Map.entry("rdfxml", Lang.RDFXML),
            Map.entry("rdfthrift", Lang.RDFTHRIFT),
            Map.entry("rdfprotobuf", Lang.RDFPROTO));

    private static final Set<String> BUILTIN_PROFILES = Set.of(
            "rdfs", "owl-rl", "owl-mini", "owl-micro");

    public static void main(String[] argv) {
        try {
            System.exit(run(argv));
        } catch (UsageError e) {
            err(e.getMessage());
            System.exit(2);
        } catch (InputError e) {
            err(e.getMessage());
            System.exit(3);
        }
    }

    static int run(String[] argv) throws UsageError, InputError {
        Args args = parseArgs(argv);
        Model baseGraph = ModelFactory.createDefaultModel();
        try {
            RDFDataMgr.read(baseGraph, System.in, null, args.inFormat);
        } catch (RiotException e) {
            throw new InputError("failed to parse stdin as " + args.inFormat.getLabel() + ": " + e.getMessage());
        }

        Reasoner reasoner = buildReasoner(args.profile, args.rulesPath);
        InfModel inf = ModelFactory.createInfModel(reasoner, baseGraph);

        // The InfModel exposes both base + derived; derived-only
        // is the base-subtracted view.
        Model output;
        if (args.includeBase) {
            output = inf;
        } else {
            output = ModelFactory.createDefaultModel();
            output.add(inf.getDeductionsModel());
        }
        RDFDataMgr.write(System.out, output, RDFFormat.TURTLE_PRETTY);
        return 0;
    }

    static Reasoner buildReasoner(String profile, Path rulesPath) throws UsageError, InputError {
        switch (profile) {
            case "rdfs":
                return ReasonerRegistry.getRDFSReasoner();
            case "owl-rl":
                return ReasonerRegistry.getOWLReasoner();
            case "owl-mini":
                return ReasonerRegistry.getOWLMiniReasoner();
            case "owl-micro":
                return ReasonerRegistry.getOWLMicroReasoner();
            case "custom":
                if (rulesPath == null) {
                    throw new UsageError("--profile=custom requires --rules=PATH");
                }
                try {
                    String body = Files.readString(rulesPath);
                    List<Rule> rules = Rule.parseRules(body);
                    GenericRuleReasoner r = new GenericRuleReasoner(rules);
                    r.setOWLTranslation(true);
                    r.setTransitiveClosureCaching(true);
                    return r;
                } catch (IOException e) {
                    throw new InputError("failed to read --rules file " + rulesPath + ": " + e.getMessage());
                } catch (RuntimeException e) {
                    throw new InputError("failed to parse Jena rules in " + rulesPath + ": " + e.getMessage());
                }
            default:
                throw new UsageError("unsupported --profile: " + profile);
        }
    }

    static final class Args {
        String ruleName = "";
        Lang inFormat = Lang.TURTLE;
        String profile = "rdfs";
        Path rulesPath = null;
        boolean includeBase = false;
    }

    static Args parseArgs(String[] argv) throws UsageError {
        Args args = new Args();
        Map<String, String> seen = new LinkedHashMap<>();
        for (String raw : argv) {
            if (raw.equals("--include-base")) {
                args.includeBase = true;
                continue;
            }
            int eq = raw.indexOf('=');
            if (!raw.startsWith("--") || eq < 0) {
                throw new UsageError("malformed flag: " + raw + " (expected --key=value)");
            }
            String key = raw.substring(0, eq);
            if (!KNOWN_FLAGS.contains(key)) {
                throw new UsageError("unknown flag: " + key);
            }
            seen.put(key, raw.substring(eq + 1));
        }
        if (seen.containsKey("--rule-name")) args.ruleName = seen.get("--rule-name");
        if (seen.containsKey("--in-format")) {
            Lang lang = IN_FORMATS.get(seen.get("--in-format"));
            if (lang == null) throw new UsageError("unsupported --in-format: " + seen.get("--in-format"));
            args.inFormat = lang;
        }
        if (seen.containsKey("--profile")) {
            String p = seen.get("--profile");
            if (!BUILTIN_PROFILES.contains(p) && !p.equals("custom")) {
                throw new UsageError("unsupported --profile: " + p);
            }
            args.profile = p;
        }
        if (seen.containsKey("--rules")) {
            args.rulesPath = Path.of(seen.get("--rules"));
        }
        return args;
    }

    static void err(String msg) {
        System.err.println("jena_reasoner: " + msg);
    }

    static final class UsageError extends Exception {
        UsageError(String m) { super(m); }
    }
    static final class InputError extends Exception {
        InputError(String m) { super(m); }
    }

    private JenaReasonerBin() {}
}
