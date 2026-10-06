package ch.so.agi.hop.sdmx;

import static org.assertj.core.api.Assertions.*;

import ch.so.agi.hop.sdmx.core.*;
import java.nio.file.*;
import java.util.*;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.core.xml.XmlHandler;
import org.junit.jupiter.api.*;

class SdmxInputMetaTest {
  @BeforeAll
  static void init() throws Exception {
    HopEnvironment.init();
  }

  static SdmxInputMeta configured() throws Exception {
    var m = new SdmxInputMeta();
    m.setAgency("CH1.COU");
    m.setDataflow("DF_COU_HEALTH_FINANCING");
    m.saveSchema(
        StructureParser.parse(
            Files.readAllBytes(Path.of("../e2e/fixtures/structure.json")),
            m.getAgency(),
            m.getDataflow(),
            "1.0.0"));
    return m;
  }

  @Test
  void roundTripPreservesExpressionsAndSavedSchema() throws Exception {
    var m = configured();
    m.setStartPeriod("${START_PERIOD}");
    m.setFiltersJson("{\"Q\":\"${SOURCES}\"}");
    var copy = new SdmxInputMeta();
    copy.loadXml(
        XmlHandler.loadXmlString("<transform>" + m.getXml() + "</transform>", "transform"), null);
    assertThat(copy.getSchemaJson()).isEqualTo(m.getSchemaJson());
    assertThat(copy.getFieldsJson()).isEqualTo(m.getFieldsJson());
    assertThat(copy.getStartPeriod()).isEqualTo("${START_PERIOD}");
    assertThat(copy.getFiltersJson()).isEqualTo(m.getFiltersJson());
  }

  @Test
  void fieldsAreOfflineAndParametersNeverMutateSavedConfiguration() throws Exception {
    var m = configured();
    m.setEndpoint("http://127.0.0.1:1/rest");
    m.setStartPeriod("${START_PERIOD}");
    m.setFiltersJson("{\"Q\":\"${SOURCES}\"}");
    var vars = new Variables();
    vars.setVariable("START_PERIOD", "2023");
    vars.setVariable("SOURCES", "Q_1,Q_2");
    var before = new RowMeta();
    m.getFields(before, "source", null, null, vars, null);
    assertThat(m.query(vars).start()).isEqualTo("2023");
    assertThat(m.query(vars).filters().get("Q")).isEqualTo("Q_1,Q_2");
    vars.setVariable("START_PERIOD", "2024");
    var after = new RowMeta();
    m.getFields(after, "source", null, null, vars, null);
    assertThat(after.getFieldNames()).containsExactly(before.getFieldNames());
    assertThat(m.getStartPeriod()).isEqualTo("${START_PERIOD}");
    assertThat(m.getVersion()).isEqualTo("1.0.0");
  }

  @Test
  void clonedMetadataCanBeEditedWithoutMutatingOriginal() throws Exception {
    var m = configured();
    String xml = m.getXml();
    var clone = (SdmxInputMeta) m.clone();
    clone.setFiltersJson("{\"Q\":\"Q_2\"}");
    clone.setFieldsJson("[]");
    assertThat(m.getXml()).isEqualTo(xml);
  }
}
