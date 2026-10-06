package ch.so.agi.hop.sdmx;

import static ch.so.agi.hop.sdmx.core.SdmxModel.*;

import ch.so.agi.hop.sdmx.core.*;
import java.util.*;
import org.apache.hop.core.*;
import org.apache.hop.core.annotations.Transform;
import org.apache.hop.core.exception.HopTransformException;
import org.apache.hop.core.row.*;
import org.apache.hop.core.row.value.*;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.*;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.*;

@Transform(
    id = "SdmxInput",
    name = "SDMX Input",
    description = "Read statistical observations using a saved SDMX schema",
    image = "ch/so/agi/hop/sdmx/sdmx.svg",
    categoryDescription = "Input",
    keywords = {"sdmx", "statistics", "bfs", "input"},
    documentationUrl =
        "https://edigonzales.github.io/hop-sdmx-plugin/sdmx/main/index.html#sdmx-input")
public class SdmxInputMeta extends BaseTransformMeta<SdmxInput, SdmxInputData> {
  @HopMetadataProperty private String endpoint;
  @HopMetadataProperty private String agency;
  @HopMetadataProperty private String dataflow;
  @HopMetadataProperty private String version;
  @HopMetadataProperty private String startPeriod;
  @HopMetadataProperty private String endPeriod;
  @HopMetadataProperty private String language;
  @HopMetadataProperty private String filtersJson;
  @HopMetadataProperty private String schemaJson;
  @HopMetadataProperty private String fieldsJson;
  @HopMetadataProperty private boolean includeLabels;

  public SdmxInputMeta() {
    setDefault();
  }

  @Override
  public void setDefault() {
    endpoint = "https://disseminate.stats.swiss/rest";
    agency = "";
    dataflow = "";
    version = "";
    startPeriod = "";
    endPeriod = "";
    language = "de";
    filtersJson = "{}";
    schemaJson = "";
    fieldsJson = "[]";
    includeLabels = true;
  }

  public SdmxQuery query(IVariables vars) {
    Map<String, String> filters = new LinkedHashMap<>();
    SdmxModel.filters(filtersJson).forEach((key, value) -> filters.put(key, resolve(vars, value)));
    return new SdmxQuery(
        resolve(vars, endpoint),
        resolve(vars, agency),
        resolve(vars, dataflow),
        resolve(vars, version),
        filters,
        resolve(vars, startPeriod),
        resolve(vars, endPeriod),
        resolve(vars, language));
  }

  private static String resolve(IVariables vars, String value) {
    return vars == null ? value : vars.resolve(value);
  }

  public Schema schema() {
    return SdmxModel.schema(schemaJson);
  }

  public List<Field> fields() {
    return SdmxModel.fields(fieldsJson);
  }

  public void saveSchema(Schema schema) {
    schemaJson = SdmxModel.write(schema);
    fieldsJson = SdmxModel.write(schema.fields(includeLabels));
    // Keep parameter expressions; otherwise persist the server's concrete version.
    if (!version.contains("${")) version = schema.flow().version();
  }

  public void validate() {
    schema().validateFields(fields());
    if (version.isBlank()) throw new IllegalArgumentException("Choose a concrete dataflow version");
  }

  @Override
  public void getFields(
      IRowMeta row,
      String origin,
      IRowMeta[] info,
      TransformMeta next,
      IVariables vars,
      IHopMetadataProvider provider)
      throws HopTransformException {
    try {
      validate();
      row.clear();
      for (Field f : fields()) {
        IValueMeta value =
            switch (f.type()) {
              case STRING -> new ValueMetaString(f.name());
              case INTEGER -> new ValueMetaInteger(f.name());
              case DECIMAL -> new ValueMetaBigNumber(f.name());
              case NUMBER -> new ValueMetaNumber(f.name());
            };
        value.setOrigin(origin);
        row.addValueMeta(value);
      }
    } catch (Exception e) {
      throw new HopTransformException(e.getMessage(), e);
    }
  }

  @Override
  public void check(
      List<ICheckResult> remarks,
      PipelineMeta pm,
      TransformMeta tm,
      IRowMeta prev,
      String[] input,
      String[] output,
      IRowMeta info,
      IVariables vars,
      IHopMetadataProvider provider) {
    try {
      validate();
      if (input != null && input.length > 0)
        throw new IllegalArgumentException("SDMX Input is a source and accepts no incoming rows");
      remarks.add(new CheckResult(ICheckResult.TYPE_RESULT_OK, "Saved SDMX schema is valid", tm));
    } catch (Exception e) {
      remarks.add(new CheckResult(ICheckResult.TYPE_RESULT_ERROR, e.getMessage(), tm));
    }
  }

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String value) {
    endpoint = value;
  }

  public String getAgency() {
    return agency;
  }

  public void setAgency(String value) {
    agency = value;
  }

  public String getDataflow() {
    return dataflow;
  }

  public void setDataflow(String value) {
    dataflow = value;
  }

  public String getVersion() {
    return version;
  }

  public void setVersion(String value) {
    version = value;
  }

  public String getStartPeriod() {
    return startPeriod;
  }

  public void setStartPeriod(String value) {
    startPeriod = value;
  }

  public String getEndPeriod() {
    return endPeriod;
  }

  public void setEndPeriod(String value) {
    endPeriod = value;
  }

  public String getLanguage() {
    return language;
  }

  public void setLanguage(String value) {
    language = value;
  }

  public String getFiltersJson() {
    return filtersJson;
  }

  public void setFiltersJson(String value) {
    filtersJson = value;
  }

  public String getSchemaJson() {
    return schemaJson;
  }

  public void setSchemaJson(String value) {
    schemaJson = value;
  }

  public String getFieldsJson() {
    return fieldsJson;
  }

  public void setFieldsJson(String value) {
    fieldsJson = value;
  }

  public boolean isIncludeLabels() {
    return includeLabels;
  }

  public void setIncludeLabels(boolean value) {
    includeLabels = value;
  }
}
