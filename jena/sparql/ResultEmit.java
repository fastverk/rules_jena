package fastverk.rules_jena.sparql;

import java.io.PrintStream;
import java.util.Set;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFactory;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;

/**
 * Shared SPARQL execution + result serialization.
 *
 * <p>Factored out of {@link JenaSparql} so the in-memory
 * {@code jena_sparql} binary (rules_rdf {@code sparql_engine}
 * toolchain) and the persistent TDB2-backed {@code jena_tdb2} binary
 * (runtime KG cache for the agentic-ide config-projection framework)
 * produce <em>byte-identical</em> output for the same query + data. The
 * build-time hermetic query targets and the runtime cached query path
 * must agree, so the formatter calls live in exactly one place.
 *
 * <p>Output contract (matches rules_rdf's plugin spec): SELECT →
 * tsv/csv/json/srx per {@code outFormat}; ASK → {@code "true"}/{@code
 * "false"}; CONSTRUCT/DESCRIBE → pretty Turtle. When
 * {@code failOnNonempty} is set, a non-empty result returns exit 1 with
 * a stderr diagnostic (the zero-row gate idiom).
 */
public final class ResultEmit {

    /** Accepted {@code --out-format} values. Tabular (tsv/csv/json/srx)
     *  apply to SELECT/ASK; RDF graph formats (turtle/ntriples/nquads/
     *  trig/jsonld/rdfxml) apply to CONSTRUCT/DESCRIBE. The dispatch
     *  picks the relevant subset by query type. */
    public static final Set<String> OUT_FORMATS = Set.of(
            "tsv", "csv", "json", "srx",
            "turtle", "ntriples", "nquads", "trig", "jsonld", "rdfxml");

    /** Execute {@code query} against an in-memory {@code model} and
     *  emit to {@code out}, with diagnostics on {@code err}. */
    public static int executeAndEmit(Model model, Query query, String outFormat,
            boolean failOnNonempty, PrintStream out, PrintStream err) {
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            return dispatch(qexec, query, outFormat, failOnNonempty, out, err);
        }
    }

    /** Execute {@code query} against a {@code dataset} (e.g. a TDB2
     *  Dataset) and emit. The caller owns the surrounding read
     *  transaction — TDB2 requires queries to run inside one. */
    public static int executeAndEmit(Dataset dataset, Query query, String outFormat,
            boolean failOnNonempty, PrintStream out, PrintStream err) {
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            return dispatch(qexec, query, outFormat, failOnNonempty, out, err);
        }
    }

    private static int dispatch(QueryExecution qexec, Query query, String outFormat,
            boolean failOnNonempty, PrintStream out, PrintStream err) {
        if (query.isSelectType()) {
            // Snapshot so we can count rows (for the gate) and still
            // emit them — Jena's formatter consumes the iterator.
            ResultSet snapshot = ResultSetFactory.copyResults(qexec.execSelect());
            int rowCount = countRows(snapshot);
            emitSelect(ResultSetFactory.copyResults(snapshot), outFormat, out);
            if (failOnNonempty && rowCount > 0) {
                err.println("result: " + rowCount + " row(s) violated --fail-on-nonempty gate");
                return 1;
            }
            return 0;
        }
        if (query.isAskType()) {
            boolean result = qexec.execAsk();
            out.println(result);
            if (failOnNonempty && result) {
                err.println("result: ASK returned true — gate failed");
                return 1;
            }
            return 0;
        }
        if (query.isConstructType()) {
            Model resultModel = qexec.execConstruct();
            RDFDataMgr.write(out, resultModel, graphFormat(outFormat));
            if (failOnNonempty && !resultModel.isEmpty()) {
                err.println("result: " + resultModel.size() + " triple(s) violated --fail-on-nonempty gate");
                return 1;
            }
            return 0;
        }
        if (query.isDescribeType()) {
            Model resultModel = qexec.execDescribe();
            RDFDataMgr.write(out, resultModel, graphFormat(outFormat));
            return 0;
        }
        err.println("result: unsupported SPARQL query type");
        return 1;
    }

    /** Map an {@code --out-format} value to a Jena graph serialization.
     *  CONSTRUCT/DESCRIBE results are graphs; anything that isn't a
     *  recognized graph format (e.g. a SELECT-oriented value) falls
     *  back to pretty Turtle. N-Triples is the canonical machine-
     *  readable form the projection serializer consumes. */
    static RDFFormat graphFormat(String outFormat) {
        switch (outFormat) {
            case "ntriples":
                return RDFFormat.NTRIPLES;
            case "nquads":
                return RDFFormat.NQUADS;
            case "trig":
                return RDFFormat.TRIG_PRETTY;
            case "jsonld":
                return RDFFormat.JSONLD;
            case "rdfxml":
                return RDFFormat.RDFXML_PRETTY;
            case "turtle":
            default:
                return RDFFormat.TURTLE_PRETTY;
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

    static void emitSelect(ResultSet rs, String outFormat, PrintStream out) {
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
                // Turtle for a SELECT is non-standard; fall back to JSON
                // (only CONSTRUCT/DESCRIBE produce meaningful Turtle).
                ResultSetFormatter.outputAsJSON(out, rs);
                break;
            default:
                throw new IllegalStateException("unhandled out-format: " + outFormat);
        }
        out.flush();
    }

    private ResultEmit() {}
}
