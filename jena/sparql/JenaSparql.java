package fastverk.rules_jena.sparql;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RiotException;

/**
 * Jena-backed implementation of the rules_rdf {@code sparql_engine}
 * toolchain contract. Reads an RDF dataset from stdin, executes a
 * SPARQL query against it, and writes results to stdout.
 *
 * <p>See {@code rdf/plugin_contract.md} in rules_rdf for the
 * authoritative spec. Summary of the surface this binary
 * implements:
 *
 * <ul>
 *   <li>stdin: RDF document bytes (single serialization declared
 *       via {@code --in-format}).</li>
 *   <li>argv: {@code --rule-name=NAME}, {@code --in-format=FORMAT},
 *       {@code --query=PATH}, {@code --out-format=FORMAT},
 *       {@code --fail-on-nonempty}.</li>
 *   <li>stdout: SPARQL result set (TSV/JSON/SRX/CSV for SELECT;
 *       Turtle for CONSTRUCT/DESCRIBE).</li>
 *   <li>stderr: diagnostics.</li>
 *   <li>exit: 0 success; non-zero on error; non-zero with
 *       offending-row diagnostic on stderr if
 *       {@code --fail-on-nonempty} and result set is non-empty.</li>
 * </ul>
 *
 * <p>Unknown flags are rejected with exit 2 (per the contract's
 * "rejects unknown flags" requirement). Malformed input on stdin
 * exits 3 with the parser's diagnostic on stderr and no stdout.
 */
public final class JenaSparql {

    /** Recognized flag names. Unknown {@code --key=value} → exit 2. */
    private static final Set<String> KNOWN_FLAGS = Set.of(
            "--rule-name",
            "--in-format",
            "--query",
            "--out-format",
            "--fail-on-nonempty");

    /** {@code --in-format} → Jena {@link Lang}. Matches the rules_rdf
     *  vocabulary. */
    private static final Map<String, Lang> IN_FORMATS = Map.ofEntries(
            Map.entry("turtle", Lang.TURTLE),
            Map.entry("ntriples", Lang.NTRIPLES),
            Map.entry("nquads", Lang.NQUADS),
            Map.entry("trig", Lang.TRIG),
            Map.entry("jsonld", Lang.JSONLD),
            Map.entry("rdfxml", Lang.RDFXML),
            Map.entry("rdfthrift", Lang.RDFTHRIFT),
            Map.entry("rdfprotobuf", Lang.RDFPROTO));

    public static void main(String[] argv) {
        try {
            exitWith(run(argv));
        } catch (UsageError e) {
            err(e.getMessage());
            exitWith(2);
        } catch (InputError e) {
            err(e.getMessage());
            exitWith(3);
        }
    }

    static int run(String[] argv) throws UsageError, InputError {
        Args args = parseArgs(argv);
        Model model = loadModel(args.inFormat);
        Query query = loadQuery(args.queryPath);
        return executeQuery(model, query, args);
    }

    // ---------- argv parsing ---------------------------------------------

    static final class Args {
        String ruleName = "";
        Lang inFormat = Lang.TURTLE;
        Path queryPath = null;
        String outFormat = "tsv";
        boolean failOnNonempty = false;
    }

    static Args parseArgs(String[] argv) throws UsageError {
        Args args = new Args();
        Map<String, String> seenFlags = new LinkedHashMap<>();
        for (String raw : argv) {
            if (raw.equals("--fail-on-nonempty")) {
                args.failOnNonempty = true;
                continue;
            }
            int eq = raw.indexOf('=');
            if (!raw.startsWith("--") || eq < 0) {
                throw new UsageError("malformed flag: " + raw + " (expected --key=value)");
            }
            String key = raw.substring(0, eq);
            String value = raw.substring(eq + 1);
            if (!KNOWN_FLAGS.contains(key)) {
                throw new UsageError("unknown flag: " + key);
            }
            seenFlags.put(key, value);
        }
        if (seenFlags.containsKey("--rule-name")) {
            args.ruleName = seenFlags.get("--rule-name");
        }
        if (seenFlags.containsKey("--in-format")) {
            String v = seenFlags.get("--in-format");
            Lang lang = IN_FORMATS.get(v);
            if (lang == null) {
                throw new UsageError("unsupported --in-format: " + v);
            }
            args.inFormat = lang;
        }
        if (seenFlags.containsKey("--query")) {
            args.queryPath = Path.of(seenFlags.get("--query"));
        }
        if (seenFlags.containsKey("--out-format")) {
            String v = seenFlags.get("--out-format");
            if (!ResultEmit.OUT_FORMATS.contains(v)) {
                throw new UsageError("unsupported --out-format: " + v);
            }
            args.outFormat = v;
        }
        if (args.queryPath == null) {
            throw new UsageError("--query=PATH is required");
        }
        return args;
    }

    // ---------- input handling -------------------------------------------

    static Model loadModel(Lang inFormat) throws InputError {
        Model model = ModelFactory.createDefaultModel();
        try {
            RDFDataMgr.read(model, System.in, null, inFormat);
        } catch (RiotException e) {
            throw new InputError("failed to parse stdin as " + inFormat.getLabel() + ": " + e.getMessage());
        }
        return model;
    }

    static Query loadQuery(Path queryPath) throws InputError {
        String queryText;
        try {
            queryText = Files.readString(queryPath);
        } catch (IOException e) {
            throw new InputError("failed to read --query file " + queryPath + ": " + e.getMessage());
        }
        try {
            return QueryFactory.create(queryText);
        } catch (RuntimeException e) {
            throw new InputError("failed to parse SPARQL query in " + queryPath + ": " + e.getMessage());
        }
    }

    // ---------- execution + output ---------------------------------------

    // Execution + result serialization is shared with the TDB2-backed
    // jena_tdb2 binary via ResultEmit, so both emit byte-identical
    // output (build-time hermetic queries must agree with the runtime
    // cached query path).
    static int executeQuery(Model model, Query query, Args args) {
        return ResultEmit.executeAndEmit(
                model, query, args.outFormat, args.failOnNonempty, System.out, System.err);
    }

    // ---------- error plumbing -------------------------------------------

    static void err(String msg) {
        System.err.println("jena_sparql: " + msg);
    }

    static void exitWith(int code) {
        System.exit(code);
    }

    static final class UsageError extends Exception {
        UsageError(String msg) { super(msg); }
    }

    static final class InputError extends Exception {
        InputError(String msg) { super(msg); }
    }

    private JenaSparql() {}
}
