package fastverk.rules_jena.riot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RiotException;

/**
 * Jena-backed implementation of the rules_rdf
 * {@code rdf_serializer_toolchain_type} plugin contract. Reads RDF
 * from stdin in {@code --in-format=FORMAT}, re-emits in
 * {@code --out-format=FORMAT} to stdout. Backed by Jena's RIOT
 * (RDF I/O technology).
 *
 * <p>Determinism: Jena's TURTLE_PRETTY output is content-stable for
 * a given input graph (sorts triples; assigns blank-node labels by
 * graph rank). NTRIPLES is canonically line-sorted. Output for
 * formats with implementation-defined order (RDFXML) is
 * best-effort but not guaranteed byte-stable across runs — use
 * Turtle / N-Triples when canonical output matters.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0: success.</li>
 *   <li>2: argv error.</li>
 *   <li>3: malformed input on stdin.</li>
 * </ul>
 */
public final class JenaRiot {

    private static final Set<String> KNOWN_FLAGS = Set.of(
            "--rule-name", "--in-format", "--out-format");

    private static final Map<String, Lang> IN_FORMATS = Map.of(
            "turtle", Lang.TURTLE,
            "ntriples", Lang.NTRIPLES,
            "nquads", Lang.NQUADS,
            "trig", Lang.TRIG,
            "jsonld", Lang.JSONLD,
            "rdfxml", Lang.RDFXML);

    private static final Map<String, RDFFormat> OUT_FORMATS = Map.of(
            "turtle", RDFFormat.TURTLE_PRETTY,
            "ntriples", RDFFormat.NTRIPLES,
            "nquads", RDFFormat.NQUADS,
            "trig", RDFFormat.TRIG_PRETTY,
            "jsonld", RDFFormat.JSONLD_PRETTY,
            "rdfxml", RDFFormat.RDFXML_PRETTY);

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
        Model model = ModelFactory.createDefaultModel();
        try {
            RDFDataMgr.read(model, System.in, null, args.inFormat);
        } catch (RiotException e) {
            throw new InputError("failed to parse stdin as " + args.inFormat.getLabel() + ": " + e.getMessage());
        }
        RDFDataMgr.write(System.out, model, args.outFormat);
        return 0;
    }

    static final class Args {
        String ruleName = "";
        Lang inFormat = Lang.TURTLE;
        RDFFormat outFormat = RDFFormat.TURTLE_PRETTY;
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
        if (seen.containsKey("--out-format")) {
            RDFFormat fmt = OUT_FORMATS.get(seen.get("--out-format"));
            if (fmt == null) throw new UsageError("unsupported --out-format: " + seen.get("--out-format"));
            args.outFormat = fmt;
        } else {
            throw new UsageError("--out-format=FORMAT is required");
        }
        return args;
    }

    static void err(String msg) {
        System.err.println("jena_riot: " + msg);
    }

    static final class UsageError extends Exception {
        UsageError(String m) { super(m); }
    }
    static final class InputError extends Exception {
        InputError(String m) { super(m); }
    }

    private JenaRiot() {}
}
