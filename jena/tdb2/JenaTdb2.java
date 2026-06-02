package fastverk.rules_jena.tdb2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RiotException;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.TDB2Factory;

import fastverk.rules_jena.sparql.ResultEmit;

/**
 * Persistent TDB2-backed companion to {@code jena_sparql}. Loads RDF
 * into and queries an on-disk TDB2 dataset — the substrate for the
 * agentic-ide runtime knowledge-graph cache under
 * {@code ~/.cache/agentic-ide/<repo-hash>/tdb2}. Query output is
 * produced by the shared {@link ResultEmit}, so a query run here is
 * byte-identical to the same query run in-memory by {@code jena_sparql}
 * at build time (build-time hermetic queries must agree with the
 * runtime cached path).
 *
 * <p>Subcommands (argv[0]):
 * <ul>
 *   <li>{@code load --location=DIR [--in-format=FMT] [--graph=IRI]} —
 *       read RDF from stdin and bulk-load it into the TDB2 dataset at
 *       DIR, into the named graph IRI if given else the default graph,
 *       inside a write transaction.</li>
 *   <li>{@code query --location=DIR --query=PATH [--out-format=FMT]
 *       [--fail-on-nonempty]} — open the dataset and run the SPARQL
 *       query inside a read transaction.</li>
 * </ul>
 *
 * <p>Exit codes match jena_sparql: 0 success, 2 usage error, 3 input
 * error.
 */
public final class JenaTdb2 {

    private static final Map<String, Lang> IN_FORMATS = Map.ofEntries(
            Map.entry("turtle", Lang.TURTLE),
            Map.entry("ntriples", Lang.NTRIPLES),
            Map.entry("nquads", Lang.NQUADS),
            Map.entry("trig", Lang.TRIG),
            Map.entry("jsonld", Lang.JSONLD),
            Map.entry("rdfxml", Lang.RDFXML),
            Map.entry("rdfthrift", Lang.RDFTHRIFT),
            Map.entry("rdfprotobuf", Lang.RDFPROTO));

    private static final Set<String> LOAD_FLAGS =
            Set.of("--location", "--in-format", "--graph");
    private static final Set<String> QUERY_FLAGS =
            Set.of("--location", "--query", "--out-format");

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
        if (argv.length == 0) {
            throw new UsageError("expected a subcommand: load | query");
        }
        String sub = argv[0];
        String[] rest = new String[argv.length - 1];
        System.arraycopy(argv, 1, rest, 0, rest.length);
        switch (sub) {
            case "load":
                return runLoad(rest);
            case "query":
                return runQuery(rest);
            default:
                throw new UsageError("unknown subcommand: " + sub + " (expected load | query)");
        }
    }

    // ---------- load -----------------------------------------------------

    static int runLoad(String[] argv) throws UsageError, InputError {
        Map<String, String> flags = parseFlags(argv, LOAD_FLAGS);
        String location = require(flags, "--location");
        Lang inFormat = resolveInFormat(flags.getOrDefault("--in-format", "turtle"));
        String graph = flags.get("--graph");

        Dataset ds = TDB2Factory.connectDataset(location);
        try {
            Txn.executeWrite(ds, () -> {
                Model target = (graph == null) ? ds.getDefaultModel() : ds.getNamedModel(graph);
                try {
                    RDFDataMgr.read(target, System.in, null, inFormat);
                } catch (RiotException e) {
                    throw new RuntimeException(
                            "failed to parse stdin as " + inFormat.getLabel() + ": " + e.getMessage(), e);
                }
            });
            return 0;
        } catch (RuntimeException e) {
            throw new InputError(e.getMessage());
        } finally {
            ds.close();
        }
    }

    // ---------- query ----------------------------------------------------

    static int runQuery(String[] argv) throws UsageError, InputError {
        Map<String, String> flags = parseFlags(argv, QUERY_FLAGS);
        boolean failOnNonempty = flagPresent(argv, "--fail-on-nonempty");
        String location = require(flags, "--location");
        Path queryPath = Path.of(require(flags, "--query"));
        String outFormat = flags.getOrDefault("--out-format", "tsv");
        if (!ResultEmit.OUT_FORMATS.contains(outFormat)) {
            throw new UsageError("unsupported --out-format: " + outFormat);
        }
        Query query = loadQuery(queryPath);

        Dataset ds = TDB2Factory.connectDataset(location);
        try {
            return Txn.calculateRead(ds, () ->
                    ResultEmit.executeAndEmit(ds, query, outFormat, failOnNonempty, System.out, System.err));
        } finally {
            ds.close();
        }
    }

    // ---------- helpers --------------------------------------------------

    static Map<String, String> parseFlags(String[] argv, Set<String> known) throws UsageError {
        Map<String, String> flags = new LinkedHashMap<>();
        for (String raw : argv) {
            if (raw.equals("--fail-on-nonempty")) {
                continue; // handled separately as a boolean
            }
            int eq = raw.indexOf('=');
            if (!raw.startsWith("--") || eq < 0) {
                throw new UsageError("malformed flag: " + raw + " (expected --key=value)");
            }
            String key = raw.substring(0, eq);
            if (!known.contains(key)) {
                throw new UsageError("unknown flag: " + key);
            }
            flags.put(key, raw.substring(eq + 1));
        }
        return flags;
    }

    static boolean flagPresent(String[] argv, String flag) {
        for (String raw : argv) {
            if (raw.equals(flag)) {
                return true;
            }
        }
        return false;
    }

    static String require(Map<String, String> flags, String key) throws UsageError {
        String v = flags.get(key);
        if (v == null || v.isEmpty()) {
            throw new UsageError(key + "=VALUE is required");
        }
        return v;
    }

    static Lang resolveInFormat(String v) throws UsageError {
        Lang lang = IN_FORMATS.get(v);
        if (lang == null) {
            throw new UsageError("unsupported --in-format: " + v);
        }
        return lang;
    }

    static Query loadQuery(Path queryPath) throws InputError {
        String text;
        try {
            text = Files.readString(queryPath);
        } catch (IOException e) {
            throw new InputError("failed to read --query file " + queryPath + ": " + e.getMessage());
        }
        try {
            return QueryFactory.create(text);
        } catch (RuntimeException e) {
            throw new InputError("failed to parse SPARQL query in " + queryPath + ": " + e.getMessage());
        }
    }

    static void err(String msg) {
        System.err.println("jena_tdb2: " + msg);
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

    private JenaTdb2() {}
}
