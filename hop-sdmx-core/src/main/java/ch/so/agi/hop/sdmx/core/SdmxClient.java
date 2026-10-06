package ch.so.agi.hop.sdmx.core;

import static ch.so.agi.hop.sdmx.core.SdmxModel.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/** One operation per client. Closing cancels the current connection and forbids retries. */
public final class SdmxClient implements AutoCloseable {
  private final int timeoutMillis;
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile HttpURLConnection connection;

  public SdmxClient() {
    this(30_000);
  }

  public SdmxClient(int timeoutMillis) {
    this.timeoutMillis = timeoutMillis;
  }

  public Schema structure(SdmxQuery query) throws IOException {
    return StructureParser.parse(
        bytes(query.structureUri(), query.language()),
        query.agency(),
        query.dataflow(),
        query.version());
  }

  public List<Flow> flows(String endpoint, String language) throws IOException {
    // Reuse endpoint validation before constructing catalog requests.
    new SdmxQuery(endpoint, "all", "all", "", Map.of(), "", "", language);
    return StructureParser.flows(
        bytes(
            URI.create(endpoint.replaceAll("/+$", "") + "/dataflow/all/all/latest?detail=allstubs"),
            language));
  }

  private byte[] bytes(URI uri, String language) throws IOException {
    try (InputStream in = open(uri, "application/vnd.sdmx.structure+json;version=1.0", language)) {
      byte[] bytes = in.readNBytes(16 * 1024 * 1024 + 1);
      if (bytes.length > 16 * 1024 * 1024)
        throw new IOException("Structure response exceeds 16 MiB; narrow the catalog request");
      return bytes;
    } finally {
      disconnect();
    }
  }

  public ObservationReader observations(SdmxQuery query, Schema schema, List<Field> fields)
      throws IOException {
    schema.validateFields(fields);
    InputStream in =
        open(
            query.dataUri(schema),
            "application/vnd.sdmx.data+csv;version=2;labels=id",
            query.language());
    try {
      return new ObservationReader(in, schema, fields, query.language());
    } catch (Exception e) {
      in.close();
      disconnect();
      throw e;
    }
  }

  private InputStream open(URI uri, String accept, String language) throws IOException {
    for (int attempt = 0; attempt < 3; attempt++) {
      checkCancelled();
      HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
      connection = c;
      c.setConnectTimeout(timeoutMillis);
      c.setReadTimeout(timeoutMillis);
      c.setInstanceFollowRedirects(false);
      c.setRequestProperty("Accept", accept);
      c.setRequestProperty("Accept-Language", language);
      c.setRequestProperty("Accept-Encoding", "gzip");
      checkCancelled();
      try {
        int status = c.getResponseCode();
        if ((status == 429 || status == 502 || status == 503 || status == 504) && attempt < 2) {
          disconnect();
          pause(250L * (attempt + 1));
          continue;
        }
        if (status == 204)
          throw new IOException("No data returned (HTTP 204). Narrow or revise the selection.");
        if (status != 200)
          throw new IOException("SDMX request failed: HTTP " + status + " at " + uri.getPath());
        String content = Objects.toString(c.getContentType(), "").toLowerCase(Locale.ROOT);
        if (!content.contains(accept.contains("+json") ? "json" : "csv"))
          throw new IOException("Unexpected SDMX response Content-Type: " + content);
        InputStream stream = c.getInputStream();
        return "gzip".equalsIgnoreCase(c.getContentEncoding())
            ? new GZIPInputStream(stream)
            : stream;
      } catch (RuntimeException e) {
        checkCancelled();
        disconnect();
        throw e;
      } catch (IOException e) {
        checkCancelled();
        disconnect();
        // Retry only transient transport failures before handing out the response body.
        if (!closed.get()
            && attempt < 2
            && (e instanceof SocketTimeoutException || e instanceof ConnectException)) {
          pause(250L * (attempt + 1));
          continue;
        }
        throw e;
      }
    }
    throw new IOException("SDMX request retries exhausted");
  }

  private void pause(long millis) throws IOException {
    for (long elapsed = 0; elapsed < millis; elapsed += 50) {
      checkCancelled();
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("SDMX request cancelled");
      }
    }
  }

  private void checkCancelled() throws IOException {
    if (closed.get() || Thread.currentThread().isInterrupted()) {
      disconnect();
      throw new InterruptedIOException("SDMX request cancelled");
    }
  }

  private void disconnect() {
    HttpURLConnection c = connection;
    connection = null;
    if (c != null) c.disconnect();
  }

  @Override
  public void close() {
    closed.set(true);
    disconnect();
  }
}
