package ch.so.agi.hop.sdmx.core;

import static ch.so.agi.hop.sdmx.core.SdmxModel.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public record SdmxQuery(
    String endpoint,
    String agency,
    String dataflow,
    String version,
    Map<String, String> filters,
    String start,
    String end,
    String language) {
  public SdmxQuery {
    for (String s : List.of(endpoint, agency, dataflow, version, start, end, language))
      if (s.contains("${")) throw new IllegalArgumentException("Unresolved Hop variable: " + s);
    URI base = URI.create(endpoint);
    if (!Set.of("http", "https").contains(base.getScheme())
        || base.getHost() == null
        || base.getQuery() != null
        || base.getFragment() != null
        || base.getUserInfo() != null)
      throw new IllegalArgumentException(
          "Endpoint must be an HTTP(S) REST base URL without query or credentials");
    identifier(agency);
    identifier(dataflow);
    if (!version.isEmpty()) identifier(version);
    if (language.isBlank() || !language.matches("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))
      throw new IllegalArgumentException("Use one language tag, for example de or en-GB");
    filters = Map.copyOf(filters);
  }

  public static void identifier(String s) {
    if (!s.matches("[A-Za-z0-9_@-][A-Za-z0-9_@.\\-]*"))
      throw new IllegalArgumentException("Invalid SDMX identifier: " + s);
  }

  private String base() {
    return endpoint.replaceAll("/+$", "") + "/";
  }

  public URI structureUri() {
    return URI.create(
        base()
            + "dataflow/"
            + encode(agency)
            + "/"
            + encode(dataflow)
            + (version.isEmpty() ? "" : "/" + encode(version))
            + "?references=all&detail=referencepartial");
  }

  public URI dataUri(Schema schema) {
    for (String id : filters.keySet()) {
      Component c = schema.component(id);
      if (c.kind() != Kind.DIMENSION)
        throw new IllegalArgumentException("Filter is not a key dimension: " + id);
    }
    List<String> key = new ArrayList<>();
    for (Component c : schema.dimensions()) {
      String filter = filters.getOrDefault(c.id(), "").trim();
      if (filter.isEmpty() || filter.equals("*")) {
        key.add("");
        continue;
      }
      List<String> values = new ArrayList<>();
      for (String code : filter.split(",", -1)) {
        code = code.trim();
        if (code.isEmpty()
            || code.contains(".")
            || code.contains("+")
            || code.contains("*")
            || code.contains("${"))
          throw new IllegalArgumentException("Invalid filter code for " + c.id() + ": " + code);
        if (!c.codes().isEmpty() && c.codes().stream().noneMatch(new CodeMatcher(code)))
          throw new IllegalArgumentException("Unknown code for " + c.id() + ": " + code);
        if (!schema.allowed(c.id(), code))
          throw new IllegalArgumentException(
              "Code excluded by dataflow constraints: " + c.id() + "=" + code);
        values.add(encode(code));
      }
      key.add(String.join("+", values));
    }
    String selector = key.stream().allMatch(String::isEmpty) ? "all" : String.join(".", key);
    String url =
        base()
            + "data/"
            + encode(agency)
            + ","
            + encode(dataflow)
            + ","
            + encode(schema.flow().version())
            + "/"
            + selector
            + "?dimensionAtObservation=AllDimensions";
    if (!start.isBlank()) url += "&startPeriod=" + encode(start.trim());
    if (!end.isBlank()) url += "&endPeriod=" + encode(end.trim());
    return URI.create(url);
  }

  private record CodeMatcher(String id) implements java.util.function.Predicate<Code> {
    @Override
    public boolean test(Code c) {
      return c.id().equals(id);
    }
  }

  public static String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
