package example.dataset;

import org.apache.jena.rdf.model.Model;

/** Exercises jena_dataset_library: the TTL is bundled in the jar and loaded
 *  from the classpath by the generated Fixture.load() — no file path. */
public final class LoadTest {
    public static void main(String[] args) {
        Model m = Fixture.load();
        long n = m.size();
        if (n != 2) {
            System.err.println("jena_dataset_library: expected 2 triples, got " + n);
            System.exit(1);
        }
        System.out.println("jena_dataset_library: loaded " + n + " triples from classpath. OK");
    }
}
