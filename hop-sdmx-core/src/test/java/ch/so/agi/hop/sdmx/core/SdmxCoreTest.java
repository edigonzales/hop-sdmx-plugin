package ch.so.agi.hop.sdmx.core;

import static ch.so.agi.hop.sdmx.core.SdmxModel.*;
import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SdmxCoreTest {
  static byte[] fixture() throws Exception {
    return Files.readAllBytes(Path.of("../e2e/fixtures/structure.json"));
  }

  static Schema schema() throws Exception {
    return StructureParser.parse(fixture(), "CH1.COU", "DF_COU_HEALTH_FINANCING", "1.0.0");
  }

  static String csv() throws Exception {
    return Files.readString(Path.of("../e2e/fixtures/observations.csv"));
  }

  static SdmxQuery query(Map<String, String> filters) {
    return new SdmxQuery(
        "https://example.org/rest/",
        "CH1.COU",
        "DF_COU_HEALTH_FINANCING",
        "1.0.0",
        filters,
        "2023-01",
        "2024-12",
        "de");
  }

  @Test
  void resolvesReferencesAndOrdersDimensions() throws Exception {
    Schema s = schema();
    assertThat(s.dimensions()).extracting(Component::id).containsExactly("Q", "F", "UNIT", "FREQ");
    assertThat(s.component("Q").codeLabel("Q_1", "de-CH")).isEqualTo("Bund");
    assertThat(s.component("Q").codeLabel("Q_2", "de")).isEqualTo("Cantons");
    assertThat(s.component("Q").codeLabel("NEW", "de")).isEqualTo("NEW");
    assertThat(s.choices("FREQ")).extracting(Code::id).containsExactly("A");
  }

  @Test
  void resolvesConcreteVersionAndRejectsBrokenReferences() throws Exception {
    assertThat(
            StructureParser.parse(fixture(), "CH1.COU", "DF_COU_HEALTH_FINANCING", "")
                .flow()
                .version())
        .isEqualTo("1.0.0");
    byte[] broken =
        new String(fixture(), StandardCharsets.UTF_8)
            .replace("DataStructure=CH1.COU", "DataStructure=UNKNOWN")
            .getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () -> StructureParser.parse(broken, "CH1.COU", "DF_COU_HEALTH_FINANCING", "1.0.0"))
        .hasMessageContaining("Unresolved");
  }

  @Test
  void buildsV1KeyByDimensionIdWithMultipleCodes() throws Exception {
    String uri =
        query(Map.of("FREQ", "A", "Q", "Q_1,Q_2", "UNIT", "CHF")).dataUri(schema()).toASCIIString();
    assertThat(uri)
        .contains("/Q_1+Q_2..CHF.A?")
        .contains("startPeriod=2023-01")
        .contains("endPeriod=2024-12");
    assertThat(query(Map.of()).dataUri(schema()).getPath()).endsWith("/all");
    assertThat(SdmxQuery.encode("2023-01-01T00:00:00+02:00")).contains("%2B02%3A00");
  }

  @Test
  void rejectsUnknownFiltersConstraintsAndUnresolvedVariables() throws Exception {
    Schema s = schema();
    assertThatThrownBy(() -> query(Map.of("UNKNOWN", "X")).dataUri(s))
        .hasMessageContaining("Unknown SDMX");
    assertThatThrownBy(() -> query(Map.of("FREQ", "M")).dataUri(s))
        .hasMessageContaining("excluded");
    assertThatThrownBy(() -> query(Map.of("Q", "bad")).dataUri(s))
        .hasMessageContaining("Unknown code");
    assertThatThrownBy(() -> query(Map.of("Q", "${Q}")).dataUri(s))
        .hasMessageContaining("Invalid filter");
    assertThatThrownBy(() -> new SdmxQuery("${HOST}", "A", "B", "1", Map.of(), "", "", "de"))
        .hasMessageContaining("Unresolved");
  }

  @Test
  void parsesNumbersNullsAttributesAndSeparateLabels() throws Exception {
    Schema s = schema();
    List<Field> f = s.fields(true);
    try (var reader =
        new ObservationReader(
            new ByteArrayInputStream(csv().getBytes(StandardCharsets.UTF_8)), s, f, "de")) {
      Object[] row = reader.next();
      assertThat(row[index(f, "Q")]).isEqualTo("Q_1");
      assertThat(row[index(f, "Q_LABEL")]).isEqualTo("Bund");
      assertThat(row[index(f, "OBS_VALUE")]).isEqualTo(397.708);
      assertThat(row[index(f, "MULT")]).isEqualTo("6");
      assertThat(reader.next()[index(f, "OBS_VALUE")]).isNull();
      assertThat(reader.next()).isNull();
    }
  }

  static int index(List<Field> fields, String name) {
    for (int i = 0; i < fields.size(); i++) if (fields.get(i).name().equals(name)) return i;
    throw new AssertionError(name);
  }

  @Test
  void acceptsSemicolonBomAndQuotes() throws Exception {
    String csv = "\ufeff" + csv().replace(',', ';').replace("Q_1", "\"Q_1\"");
    Schema s = schema();
    try (var r =
        new ObservationReader(
            new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
            s,
            s.fields(false),
            "de")) {
      assertThat(r.next()[0]).isEqualTo("Q_1");
    }
  }

  @Test
  void rejectsDeletionAndWrongIdentityAndNonFiniteValue() throws Exception {
    for (String text :
        List.of(
            csv().replace(",I,", ",D,"),
            csv().replace("(1.0.0)", "(2.0.0)"),
            csv().replace("397.708", "NaN"),
            csv().replace(",2023,", ",,"))) {
      Schema s = schema();
      try (var r =
          new ObservationReader(
              new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
              s,
              s.fields(false),
              "de")) {
        assertThatThrownBy(r::next).isInstanceOf(IOException.class);
      }
    }
  }

  @Test
  void optionalAttributeColumnsMayBeAbsent() throws Exception {
    String csv = csv().replace(",DECIMALS", "").replace(",A,3", ",A");
    Schema s = schema();
    List<Field> f = s.fields(false);
    try (var r =
        new ObservationReader(
            new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), s, f, "de")) {
      assertThat(r.next()[index(f, "DECIMALS")]).isNull();
    }
  }

  @Test
  void rejectsDuplicateOrMissingHeaders() throws Exception {
    Schema s = schema();
    for (String csv :
        List.of(csv().replace("OBS_VALUE", "OTHER"), csv().replace("DECIMALS", "MULT")))
      assertThatThrownBy(
              () ->
                  new ObservationReader(
                      new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                      s,
                      s.fields(false),
                      "de"))
          .isInstanceOf(Exception.class);
  }

  @Test
  void schemaIgnoresNewCodesAndLabelsButRejectsChangedTypes() throws Exception {
    Schema s = schema();
    String json = new String(fixture(), StandardCharsets.UTF_8);
    Schema relabeled =
        StructureParser.parse(
            json.replace("Bund", "New label").getBytes(StandardCharsets.UTF_8),
            "CH1.COU",
            "DF_COU_HEALTH_FINANCING",
            "1.0.0");
    assertThatCode(() -> s.requireCompatible(relabeled)).doesNotThrowAnyException();
    Schema retyped =
        StructureParser.parse(
            json.replace("Float", "Decimal").getBytes(StandardCharsets.UTF_8),
            "CH1.COU",
            "DF_COU_HEALTH_FINANCING",
            "1.0.0");
    assertThatThrownBy(() -> s.requireCompatible(retyped)).hasMessageContaining("OBS_VALUE");
    assertThat(SdmxModel.schema(SdmxModel.write(s))).isEqualTo(s);
  }

  @Test
  void intersectsSeparateConstraintsAndIgnoresUnrelatedAttachments() throws Exception {
    Schema s = schema();
    var narrowed =
        new Schema(
            s.flow(),
            s.components(),
            List.of(
                new Region("allowed", true, Map.of("Q", List.of("Q_1", "Q_2"))),
                new Region("actual", true, Map.of("Q", List.of("Q_1")))));
    assertThat(narrowed.choices("Q")).extracting(Code::id).containsExactly("Q_1");
    var json = JSON.readTree(fixture());
    var constraint =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            json.path("data").path("contentConstraints").get(0);
    constraint
        .putObject("constraintAttachment")
        .putArray("dataflows")
        .add("urn:sdmx:org.sdmx.infomodel.datastructure.Dataflow=OTHER:FLOW(1.0.0)");
    Schema unconstrained =
        StructureParser.parse(
            JSON.writeValueAsBytes(json), "CH1.COU", "DF_COU_HEALTH_FINANCING", "1.0.0");
    assertThat(unconstrained.choices("FREQ")).extracting(Code::id).containsExactly("A", "M");
  }
}
