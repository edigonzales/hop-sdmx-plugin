package ch.so.agi.hop.sdmx;

import ch.so.agi.hop.sdmx.core.*;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.pipeline.*;
import org.apache.hop.pipeline.transform.*;

public class SdmxInput extends BaseTransform<SdmxInputMeta, SdmxInputData> {
  public SdmxInput(
      TransformMeta tm,
      SdmxInputMeta meta,
      SdmxInputData data,
      int copy,
      PipelineMeta pm,
      Pipeline pipeline) {
    super(tm, meta, data, copy, pm, pipeline);
  }

  @Override
  public boolean processRow() throws HopException {
    if (isStopped()) {
      close();
      setOutputDone();
      return false;
    }
    try {
      if (first) {
        first = false;
        if (getCopy() != 0) throw new HopException("SDMX Input requires a single transform copy");
        if (!getInputRowSets().isEmpty())
          throw new HopException("SDMX Input accepts no incoming rows");
        meta.validate();
        data.output = new RowMeta();
        meta.getFields(data.output, getTransformName(), null, null, this, metadataProvider);
        data.client = new SdmxClient();
        if (isStopped()) {
          close();
          setOutputDone();
          return false;
        }
        var query = meta.query(this);
        if (query.version().isBlank())
          throw new HopException("Runtime dataflow version must resolve to a concrete version");
        var actual = data.client.structure(query);
        meta.schema().requireCompatible(actual);
        data.reader = data.client.observations(query, actual, meta.fields());
      }
      Object[] row = data.reader.next();
      if (row == null || isStopped()) {
        close();
        setOutputDone();
        return false;
      }
      putRow(data.output, row);
      incrementLinesInput();
      return true;
    } catch (Exception e) {
      close();
      if (isStopped()) {
        setOutputDone();
        return false;
      }
      throw new HopException("SDMX Input: " + e.getMessage(), e);
    }
  }

  private void close() {
    if (data.client != null) data.client.close();
    if (data.reader != null) {
      try {
        data.reader.close();
      } catch (Exception ignored) {
        /* already failed or cancelled */
      }
      data.reader = null;
    }
  }

  @Override
  public void stopRunning() throws HopException {
    if (data.client != null) data.client.close();
    super.stopRunning();
  }

  @Override
  public void dispose() {
    close();
    super.dispose();
  }
}
