package ch.so.agi.hop.sdmx.core;

import static ch.so.agi.hop.sdmx.core.SdmxModel.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.*;

/** SDMX-JSON structure 1.0 resolver. References are matched by agency, id and version. */
public final class StructureParser {
  private StructureParser() {}

  public static List<Flow> flows(byte[] body) throws IOException {
    List<Flow> result = new ArrayList<>();
    for (JsonNode f : JSON.readTree(body).path("data").path("dataflows")) result.add(flow(f));
    return result;
  }

  private static Flow flow(JsonNode f) {
    return new Flow(
        f.path("agencyID").asText(), f.path("id").asText(), f.path("version").asText(), names(f));
  }

  public static Schema parse(byte[] body, String agency, String id, String version)
      throws IOException {
    JsonNode data = JSON.readTree(body).path("data");
    List<JsonNode> matching = new ArrayList<>();
    for (JsonNode f : data.path("dataflows"))
      if (f.path("id").asText().equals(id)
          && f.path("agencyID").asText().equals(agency)
          && (version.isEmpty() || f.path("version").asText().equals(version))) matching.add(f);
    if (matching.size() != 1)
      throw new IllegalArgumentException(
          "Expected exactly one dataflow; choose an explicit version: " + agency + ":" + id);
    JsonNode f = matching.getFirst();
    JsonNode dsd = referenced(data.path("dataStructures"), f.path("structure").asText());
    JsonNode parts = dsd.path("dataStructureComponents");
    List<Component> components = new ArrayList<>();
    add(components, parts.path("dimensionList").path("dimensions"), Kind.DIMENSION, data);
    components.sort(Comparator.comparingInt(Component::position));
    add(components, parts.path("dimensionList").path("timeDimensions"), Kind.TIME, data);
    JsonNode primary = parts.path("measureList").path("primaryMeasure");
    if (!primary.isMissingNode()) components.add(component(primary, Kind.MEASURE, data));
    add(components, parts.path("measureList").path("measures"), Kind.MEASURE, data);
    add(components, parts.path("attributeList").path("attributes"), Kind.ATTRIBUTE, data);
    if (components.stream().noneMatch(c -> c.kind() == Kind.MEASURE))
      throw new IllegalArgumentException("No measure in DSD");
    if (components.stream().map(Component::id).distinct().count() != components.size())
      throw new IllegalArgumentException("Duplicate DSD component identifiers");
    List<Region> regions = new ArrayList<>();
    String identity = identity(f);
    for (JsonNode constraint : data.path("contentConstraints")) {
      // Do not accidentally apply constraints attached to a different flow or DSD.
      String attachments = constraint.path("constraintAttachment").toString();
      if (!attachments.contains("=" + identity + "\"")
          && !attachments.contains("=" + identity(dsd) + "\"")) continue;
      for (JsonNode region : constraint.path("cubeRegions")) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (JsonNode key : region.path("keyValues")) {
          List<String> codes = new ArrayList<>();
          for (JsonNode v : key.path("values"))
            codes.add(v.isTextual() ? v.asText() : v.path("value").asText());
          if (!codes.isEmpty()) values.put(key.path("id").asText(), codes);
        }
        regions.add(
            new Region(identity(constraint), region.path("isIncluded").asBoolean(true), values));
      }
    }
    return new Schema(flow(f), List.copyOf(components), List.copyOf(regions));
  }

  private static void add(List<Component> out, JsonNode nodes, Kind kind, JsonNode data) {
    for (JsonNode n : nodes) out.add(component(n, kind, data));
  }

  private static Component component(JsonNode n, Kind kind, JsonNode data) {
    JsonNode concept = concept(data, n.path("conceptIdentity").asText());
    JsonNode representation = n.path("localRepresentation");
    if (representation.isMissingNode()) representation = concept.path("coreRepresentation");
    List<Code> codes = new ArrayList<>();
    if (representation.has("enumeration")) {
      JsonNode list =
          referenced(data.path("codelists"), representation.path("enumeration").asText());
      for (JsonNode c : list.path("codes")) codes.add(new Code(c.path("id").asText(), names(c)));
    }
    String sourceType = representation.path("textFormat").path("textType").asText("String");
    ValueType type = ValueType.STRING;
    if (codes.isEmpty() && kind != Kind.DIMENSION && kind != Kind.TIME) {
      type =
          switch (sourceType.toLowerCase(Locale.ROOT)) {
            case "integer", "long", "short", "int", "count", "biginteger", "decimal" ->
                ValueType.DECIMAL;
            case "float", "double" -> ValueType.NUMBER;
            default -> ValueType.STRING;
          };
    }
    Map<String, String> labels = names(concept);
    if (labels.isEmpty()) labels = names(n);
    return new Component(
        n.path("id").asText(),
        kind,
        n.path("position").asInt(-1),
        sourceType,
        type,
        labels,
        List.copyOf(codes));
  }

  private static JsonNode concept(JsonNode data, String ref) {
    for (JsonNode scheme : data.path("conceptSchemes"))
      for (JsonNode c : scheme.path("concepts"))
        if (ref.endsWith("=" + identity(scheme) + "." + c.path("id").asText())) return c;
    return JSON.createObjectNode();
  }

  private static JsonNode referenced(JsonNode nodes, String ref) {
    for (JsonNode node : nodes) if (ref.endsWith("=" + identity(node))) return node;
    throw new IllegalArgumentException("Unresolved SDMX structure reference: " + ref);
  }

  private static String identity(JsonNode n) {
    return n.path("agencyID").asText()
        + ":"
        + n.path("id").asText()
        + "("
        + n.path("version").asText()
        + ")";
  }

  private static Map<String, String> names(JsonNode n) {
    Map<String, String> result = new LinkedHashMap<>();
    n.path("names")
        .fields()
        .forEachRemaining(
            e -> result.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().asText()));
    if (result.isEmpty() && n.has("name")) result.put("en", n.path("name").asText());
    return result;
  }
}
