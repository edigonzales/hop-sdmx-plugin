package ch.so.agi.hop.sdmx.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Portable metadata shared by the editor and runtime. No Hop or SWT dependencies. */
public final class SdmxModel {
  public static final ObjectMapper JSON = new ObjectMapper();

  private SdmxModel() {}

  public enum Kind {
    DIMENSION,
    TIME,
    MEASURE,
    ATTRIBUTE
  }

  public enum ValueType {
    STRING,
    INTEGER,
    DECIMAL,
    NUMBER
  }

  public record Code(String id, Map<String, String> names) {
    public String label(String language) {
      return localized(names, language, id);
    }
  }

  public record Component(
      String id,
      Kind kind,
      int position,
      String sourceType,
      ValueType type,
      Map<String, String> names,
      List<Code> codes) {
    public String label(String language) {
      return localized(names, language, id);
    }

    public String codeLabel(String code, String language) {
      return codes.stream()
          .filter(c -> c.id().equals(code))
          .findFirst()
          .map(c -> c.label(language))
          .orElse(code);
    }
  }

  public record Region(String constraint, boolean included, Map<String, List<String>> values) {}

  public record Flow(String agency, String id, String version, Map<String, String> names) {
    public String label(String language) {
      return localized(names, language, id);
    }
  }

  public record Field(String name, String component, ValueType type, boolean label) {}

  public record Schema(Flow flow, List<Component> components, List<Region> regions) {
    public Component component(String id) {
      return components.stream()
          .filter(c -> c.id().equals(id))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("Unknown SDMX component: " + id));
    }

    public List<Component> dimensions() {
      return components.stream()
          .filter(c -> c.kind() == Kind.DIMENSION)
          .sorted(Comparator.comparingInt(Component::position))
          .toList();
    }

    public List<Code> choices(String id) {
      return component(id).codes().stream().filter(c -> allowed(id, c.id())).toList();
    }

    public boolean allowed(String id, String code) {
      // Projection of available cube regions, not a claim about cross-dimensional availability.
      Map<String, List<Region>> groups =
          regions.stream().collect(java.util.stream.Collectors.groupingBy(Region::constraint));
      for (List<Region> group : groups.values()) {
        List<Region> includes = group.stream().filter(Region::included).toList();
        boolean included =
            includes.isEmpty()
                || includes.stream()
                    .anyMatch(
                        r -> !r.values().containsKey(id) || r.values().get(id).contains(code));
        boolean excluded =
            group.stream()
                .filter(r -> !r.included() && r.values().size() == 1)
                .anyMatch(r -> r.values().getOrDefault(id, List.of()).contains(code));
        if (!included || excluded) return false;
      }
      return true;
    }

    public List<Field> fields(boolean labels) {
      List<Field> result = new ArrayList<>();
      for (Component c : components) {
        result.add(new Field(c.id(), c.id(), c.type(), false));
        if (labels && !c.codes().isEmpty())
          result.add(new Field(c.id() + "_LABEL", c.id(), ValueType.STRING, true));
      }
      validateFields(result);
      return result;
    }

    public void validateFields(List<Field> fields) {
      if (fields.isEmpty()) throw new IllegalArgumentException("Load an output schema first");
      Set<String> names = new HashSet<>();
      for (Field f : fields) {
        Component c = component(f.component());
        if (f.name() == null || f.name().isBlank() || !names.add(f.name().toLowerCase(Locale.ROOT)))
          throw new IllegalArgumentException("Empty or duplicate output field: " + f.name());
        if (f.type() != (f.label() ? ValueType.STRING : c.type()))
          throw new IllegalArgumentException("Output type differs from structure: " + f.name());
      }
    }

    public void requireCompatible(Schema actual) {
      if (components.size() != actual.components.size())
        throw new IllegalArgumentException(
            "SDMX schema changed: component count differs. Reload structure explicitly.");
      for (Component c : components) {
        Component a = actual.component(c.id());
        if (c.kind() != a.kind()
            || c.type() != a.type()
            || !c.sourceType().equals(a.sourceType())
            || (c.kind() == Kind.DIMENSION && c.position() != a.position()))
          throw new IllegalArgumentException(
              "SDMX schema changed for " + c.id() + ". Reload structure explicitly.");
      }
    }
  }

  public static String localized(Map<String, String> names, String language, String fallback) {
    String lang = language.toLowerCase(Locale.ROOT);
    String base = lang.split("-")[0];
    return names.getOrDefault(lang, names.getOrDefault(base, names.getOrDefault("en", fallback)));
  }

  public static String write(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot serialize SDMX configuration", e);
    }
  }

  public static Schema schema(String json) {
    try {
      return JSON.readValue(json, Schema.class);
    } catch (Exception e) {
      throw new IllegalArgumentException("Load a valid SDMX structure first", e);
    }
  }

  public static List<Field> fields(String json) {
    try {
      return JSON.readValue(json, new TypeReference<List<Field>>() {});
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid saved SDMX output fields", e);
    }
  }

  public static Map<String, String> filters(String json) {
    try {
      return JSON.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid SDMX filter configuration", e);
    }
  }
}
