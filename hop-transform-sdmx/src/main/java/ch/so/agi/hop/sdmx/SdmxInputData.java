package ch.so.agi.hop.sdmx;

import ch.so.agi.hop.sdmx.core.*;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.pipeline.transform.BaseTransformData;

public class SdmxInputData extends BaseTransformData {
  volatile SdmxClient client;
  ObservationReader reader;
  IRowMeta output;
}
