package example.schemagen;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;

// Compile-time check: the generated Spec class exists, lives in the
// declared package, and exposes Resource + Property constants for the
// fixture ontology's terms. If schemagen failed to emit any of these,
// this file would fail to compile — the build IS the test.
public final class UseGeneratedVocabTest {

  private UseGeneratedVocabTest() {}

  public static void main(String[] args) {
    Resource widget = Spec.Widget;
    Resource gizmo = Spec.Gizmo;
    Property name = Spec.name;
    Property partOf = Spec.partOf;

    if (!widget.getURI().equals("https://example.org/spec#Widget")) {
      throw new AssertionError("Widget URI: " + widget.getURI());
    }
    if (!gizmo.getURI().equals("https://example.org/spec#Gizmo")) {
      throw new AssertionError("Gizmo URI: " + gizmo.getURI());
    }
    if (!name.getURI().equals("https://example.org/spec#name")) {
      throw new AssertionError("name URI: " + name.getURI());
    }
    if (!partOf.getURI().equals("https://example.org/spec#partOf")) {
      throw new AssertionError("partOf URI: " + partOf.getURI());
    }
    System.out.println("ok");
  }
}
