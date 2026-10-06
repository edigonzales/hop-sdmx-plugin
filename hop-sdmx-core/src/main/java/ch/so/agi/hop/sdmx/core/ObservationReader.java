package ch.so.agi.hop.sdmx.core;

import static ch.so.agi.hop.sdmx.core.SdmxModel.*;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.commons.csv.*;

/** Streaming SDMX-CSV 2 full-snapshot reader; never interprets deletion regions as observations. */
public final class ObservationReader implements AutoCloseable {
  private final CSVParser parser;
  private final Iterator<CSVRecord> rows;
  private final Schema schema;
  private final List<Field> fields;
  private final String language;

  public ObservationReader(InputStream stream, Schema schema, List<Field> fields, String language)
      throws IOException {
    this.schema = schema;
    this.fields = fields;
    this.language = language;
    PushbackReader reader =
        new PushbackReader(new InputStreamReader(stream, StandardCharsets.UTF_8), 32);
    StringBuilder prefix = new StringBuilder();
    int c = reader.read();
    if (c == 0xfeff) c = reader.read();
    while (c >= 0 && c != ',' && c != ';' && c != '\t' && prefix.length() < 24) {
      prefix.append((char) c);
      c = reader.read();
    }
    if (!prefix.toString().equals("STRUCTURE") || (c != ',' && c != ';' && c != '\t'))
      throw new IOException("Expected SDMX-CSV v2 STRUCTURE header");
    reader.unread((prefix.toString() + (char) c).toCharArray());
    parser =
        CSVFormat.RFC4180
            .builder()
            .setDelimiter((char) c)
            .setHeader()
            .setSkipHeaderRecord(true)
            .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
            .get()
            .parse(reader);
    try {
      Set<String> headers = parser.getHeaderMap().keySet();
      for (String required : List.of("STRUCTURE", "STRUCTURE_ID", "ACTION"))
        if (!headers.contains(required))
          throw new IOException("Missing SDMX-CSV header: " + required);
      for (Component component : schema.components())
        if (component.kind() != Kind.ATTRIBUTE && !headers.contains(component.id()))
          throw new IOException("Missing SDMX-CSV component: " + component.id());
      for (String header : headers)
        if (!Set.of("STRUCTURE", "STRUCTURE_ID", "ACTION").contains(header))
          schema.component(header);
      rows = parser.iterator();
    } catch (Exception e) {
      parser.close();
      throw e;
    }
  }

  public Object[] next() throws IOException {
    try {
      if (!rows.hasNext()) return null;
      CSVRecord row = rows.next();
      if (!row.isConsistent())
        throw new IOException("Inconsistent CSV record " + row.getRecordNumber());
      if (!row.get("STRUCTURE").equals("DATAFLOW"))
        throw new IOException("Expected DATAFLOW observations");
      Flow f = schema.flow();
      String identity = f.agency() + ":" + f.id() + "(" + f.version() + ")";
      if (!row.get("STRUCTURE_ID").equals(identity))
        throw new IOException("Unexpected CSV dataflow: " + row.get("STRUCTURE_ID"));
      String action = row.get("ACTION");
      if (!Set.of("I", "R").contains(action))
        throw new IOException(
            "Unsupported SDMX action "
                + action
                + "; full observations required, no deletion regions");
      for (Component component : schema.components())
        if ((component.kind() == Kind.DIMENSION || component.kind() == Kind.TIME)
            && row.get(component.id()).isEmpty())
          throw new IOException(
              "Attribute-only or incomplete observation at record " + row.getRecordNumber());
      Object[] result = new Object[fields.size()];
      for (int i = 0; i < fields.size(); i++) {
        Field field = fields.get(i);
        String value = row.isMapped(field.component()) ? row.get(field.component()) : "";
        if (value.isEmpty()) continue;
        if (field.label())
          result[i] = schema.component(field.component()).codeLabel(value, language);
        else
          result[i] =
              switch (field.type()) {
                case STRING -> value;
                case INTEGER -> Long.valueOf(value);
                case DECIMAL -> new BigDecimal(value);
                case NUMBER -> number(value);
              };
      }
      return result;
    } catch (UncheckedIOException e) {
      throw e.getCause();
    } catch (IllegalArgumentException e) {
      throw new IOException("Invalid SDMX observation: " + e.getMessage(), e);
    }
  }

  private static double number(String value) {
    double d = Double.parseDouble(value);
    if (!Double.isFinite(d))
      throw new IllegalArgumentException("Non-finite numeric observation: " + value);
    return d;
  }

  @Override
  public void close() throws IOException {
    parser.close();
  }
}
