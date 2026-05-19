package fastverk.rules_jena.sparql;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
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

    /** Output formats by argv value. Used for SELECT/ASK; CONSTRUCT
     *  and DESCRIBE always emit Turtle. */
    private static final Set<String> OUT_FORMATS = Set.of(
            "tsv", "csv", "json", "srx", "turtle");

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
            if (!OUT_FORMATS.contains(v)) {
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

    static int executeQuery(Model model, Query query, Args args) {
        if (query.isSelectType()) {
            return executeSelect(model, query, args);
        }
        if (query.isAskType()) {
            return executeAsk(model, query, args);
        }
        if (query.isConstructType()) {
            return executeConstruct(model, query, args);
        }
        if (query.isDescribeType()) {
            return executeDescribe(model, query);
        }
        err("unsupported SPARQL query type for " + args.ruleName);
        return 1;
    }

    static int executeSelect(Model model, Query query, Args args) {
        // Materialize the result set so we can both emit it AND count
        // it (the fail-on-nonempty path needs the count). Jena's
        // ResultSetFormatter consumes the iterator destructively, so
        // we rewind via ResultSetFactory after counting.
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet rs = qexec.execSelect();
            // Snapshot to count without consuming the underlying iterator twice.
            ResultSet snapshot = org.apache.jena.query.ResultSetFactory.copyResults(rs);
            int rowCount = countRows(snapshot);
            // Reset snapshot for emission.
            snapshot = org.apache.jena.query.ResultSetFactory.copyResults(snapshot);
            emitSelectResults(snapshot, args.outFormat);
            if (args.failOnNonempty && rowCount > 0) {
                err(rowCount + " row(s) violated --fail-on-nonempty gate");
                return 1;
            }
            return 0;
        }
    }

    static int countRows(ResultSet rs) {
        int n = 0;
        while (rs.hasNext()) {
            rs.next();
            n++;
        }
        return n;
    }

    static void emitSelectResults(ResultSet rs, String outFormat) {
        PrintStream out = System.out;
        switch (outFormat) {
            case "tsv":
                ResultSetFormatter.outputAsTSV(out, rs);
                break;
            case "csv":
                ResultSetFormatter.outputAsCSV(out, rs);
                break;
            case "json":
                ResultSetFormatter.outputAsJSON(out, rs);
                break;
            case "srx":
                ResultSetFormatter.outputAsXML(out, rs);
                break;
            case "turtle":
                // Turtle output for a SELECT is non-standard. Jena
                // emits it via outputAsRDF; we go with the JSON
                // fallback to keep the contract honest — Turtle is
                // only meaningful for CONSTRUCT/DESCRIBE.
                ResultSetFormatter.outputAsJSON(out, rs);
                break;
            default:
                throw new IllegalStateException("unhandled out-format: " + outFormat);
        }
        out.flush();
    }

    static int executeAsk(Model model, Query query, Args args) {
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            boolean result = qexec.execAsk();
            // For ASK, an empty result is `false` and a non-empty result is `true`.
            // --fail-on-nonempty maps naturally: fail iff the ASK returned true.
            System.out.println(result);
            if (args.failOnNonempty && result) {
                err("ASK query returned true — gate failed");
                return 1;
            }
            return 0;
        }
    }

    static int executeConstruct(Model model, Query query, Args args) {
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            Model resultModel = qexec.execConstruct();
            RDFDataMgr.write(System.out, resultModel, RDFFormat.TURTLE_PRETTY);
            if (args.failOnNonempty && !resultModel.isEmpty()) {
                err(resultModel.size() + " triple(s) violated --fail-on-nonempty gate");
                return 1;
            }
            return 0;
        }
    }

    static int executeDescribe(Model model, Query query) {
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            Model resultModel = qexec.execDescribe();
            RDFDataMgr.write(System.out, resultModel, RDFFormat.TURTLE_PRETTY);
            return 0;
        }
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
