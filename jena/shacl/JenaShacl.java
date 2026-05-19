package fastverk.rules_jena.shacl;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.jena.graph.Graph;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RiotException;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;

/**
 * Jena-backed implementation of the rules_rdf
 * {@code rdf_validator_toolchain_type} plugin contract. Reads an
 * RDF dataset from stdin, loads a SHACL shapes graph from
 * {@code --shapes=PATH}, runs validation, and writes the
 * resulting {@code sh:ValidationReport} (Turtle) to stdout.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0: dataset conforms (or all violations are below the
 *          configured {@code --severity}).</li>
 *   <li>1: ≥ 1 violation at or above {@code --severity}.</li>
 *   <li>2: argv error (unknown flag, missing required flag).</li>
 *   <li>3: malformed input — stdin or shapes file isn't valid RDF.</li>
 * </ul>
 */
public final class JenaShacl {

    private static final Set<String> KNOWN_FLAGS = Set.of(
            "--rule-name", "--in-format", "--shapes", "--severity");

    private static final Map<String, Lang> IN_FORMATS = Map.of(
            "turtle", Lang.TURTLE,
            "ntriples", Lang.NTRIPLES,
            "nquads", Lang.NQUADS,
            "trig", Lang.TRIG,
            "jsonld", Lang.JSONLD,
            "rdfxml", Lang.RDFXML);

    /** SHACL severity rank: higher = more severe. */
    private static final Map<String, Integer> SEVERITY_RANK = Map.of(
            "info", 0,
            "warning", 1,
            "violation", 2);

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
        Model dataGraph = loadModel(args.inFormat);
        Shapes shapes = loadShapes(args.shapesPath);
        ValidationReport report = ShaclValidator.get().validate(shapes, dataGraph.getGraph());
        RDFDataMgr.write(System.out, report.getModel(), RDFFormat.TURTLE_PRETTY);
        return failingResults(report, args.severityRank) > 0 ? 1 : 0;
    }

    // ---- argv ----

    static final class Args {
        String ruleName = "";
        Lang inFormat = Lang.TURTLE;
        Path shapesPath = null;
        int severityRank = 2; // violation
    }

    static Args parseArgs(String[] argv) throws UsageError {
        Args args = new Args();
        Map<String, String> seen = new LinkedHashMap<>();
        for (String raw : argv) {
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
        if (seen.containsKey("--shapes")) {
            args.shapesPath = Path.of(seen.get("--shapes"));
        } else {
            throw new UsageError("--shapes=PATH is required");
        }
        if (seen.containsKey("--severity")) {
            Integer rank = SEVERITY_RANK.get(seen.get("--severity"));
            if (rank == null) {
                throw new UsageError(
                        "unsupported --severity: " + seen.get("--severity") +
                        " (expected info | warning | violation)");
            }
            args.severityRank = rank;
        }
        return args;
    }

    // ---- input ----

    static Model loadModel(Lang inFormat) throws InputError {
        Model model = ModelFactory.createDefaultModel();
        try {
            RDFDataMgr.read(model, System.in, null, inFormat);
        } catch (RiotException e) {
            throw new InputError("failed to parse stdin as " + inFormat.getLabel() + ": " + e.getMessage());
        }
        return model;
    }

    static Shapes loadShapes(Path path) throws InputError {
        try {
            // The contract specifies shapes are Turtle. Pass the lang
            // explicitly so the loader works even when the file path
            // has no extension (the contract driver writes to a temp
            // file named `shapes` with no suffix).
            Model shapesModel = ModelFactory.createDefaultModel();
            RDFDataMgr.read(shapesModel, path.toUri().toString(), Lang.TURTLE);
            return Shapes.parse(shapesModel.getGraph());
        } catch (RiotException e) {
            throw new InputError("failed to parse --shapes file " + path + ": " + e.getMessage());
        } catch (RuntimeException e) {
            throw new InputError("failed to load shapes from " + path + ": " + e.getMessage());
        }
    }

    /**
     * Number of failing results at or above the configured severity.
     *
     * <p>v0.2 ships a coarse implementation: any non-conformance
     * counts as ≥ violation. Filtering down to warning / info
     * requires walking the report's {@code sh:resultSeverity}
     * predicate per entry — Jena exposes that via the report's
     * RDF model, and the lookup is a v0.3 refinement
     * (tracked in docs/ROADMAP.md). For now the {@code --severity}
     * flag is accepted (so consumers can already write
     * {@code --severity=warning} in their BUILD files) but the
     * coarse semantics apply across all three levels.
     */
    static int failingResults(ValidationReport report, int minRank) {
        return report.conforms() ? 0 : 1;
    }

    static void err(String msg) {
        System.err.println("jena_shacl: " + msg);
    }

    static final class UsageError extends Exception {
        UsageError(String m) { super(m); }
    }
    static final class InputError extends Exception {
        InputError(String m) { super(m); }
    }

    private JenaShacl() {}
}
