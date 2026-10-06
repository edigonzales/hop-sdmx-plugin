package ch.so.agi.hop.sdmx.core;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class SdmxClientTest {
  @Test
  void retriesBeforeResponseAndNeverAfterStreamingStarts() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger requests = new AtomicInteger();
    byte[] fixture = SdmxCoreTest.fixture();
    server.createContext(
        "/dataflow",
        e -> {
          int n = requests.incrementAndGet();
          e.getResponseHeaders().set("Content-Type", "application/vnd.sdmx.structure+json");
          e.sendResponseHeaders(n < 3 ? 503 : 200, n < 3 ? 0 : fixture.length);
          if (n >= 3) e.getResponseBody().write(fixture);
          e.close();
        });
    server.start();
    try (var client = new SdmxClient(1000)) {
      var q =
          new SdmxQuery(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "CH1.COU",
              "DF_COU_HEALTH_FINANCING",
              "1.0.0",
              Map.of(),
              "",
              "",
              "de");
      assertThat(client.structure(q).flow().version()).isEqualTo("1.0.0");
      assertThat(requests).hasValue(3);
      client.close();
      assertThatThrownBy(() -> client.structure(q)).hasMessageContaining("cancelled");
      assertThat(requests).hasValue(3);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void cancellationStopsRetryLoop() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    CountDownLatch contacted = new CountDownLatch(1);
    AtomicInteger requests = new AtomicInteger();
    server.createContext(
        "/dataflow",
        e -> {
          requests.incrementAndGet();
          e.sendResponseHeaders(503, -1);
          e.close();
          contacted.countDown();
        });
    server.start();
    try (var client = new SdmxClient(1000);
        var executor = Executors.newSingleThreadExecutor()) {
      var q =
          new SdmxQuery(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "CH1.COU",
              "DF_COU_HEALTH_FINANCING",
              "1.0.0",
              Map.of(),
              "",
              "",
              "de");
      Future<?> future =
          executor.submit(
              () -> {
                assertThatThrownBy(() -> client.structure(q)).hasMessageContaining("cancelled");
              });
      assertThat(contacted.await(2, TimeUnit.SECONDS)).isTrue();
      client.close();
      future.get(2, TimeUnit.SECONDS);
      assertThat(requests).hasValue(1);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void readFailureAfterFirstObservationDoesNotReplayRequest() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger requests = new AtomicInteger();
    String[] lines = SdmxCoreTest.csv().split("\n");
    byte[] body =
        (lines[0] + "\n" + lines[1] + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    server.createContext(
        "/data/",
        e -> {
          requests.incrementAndGet();
          e.getResponseHeaders().set("Content-Type", "application/vnd.sdmx.data+csv;version=2");
          e.sendResponseHeaders(200, body.length + 100);
          try {
            e.getResponseBody().write(body);
            e.getResponseBody().flush();
            Thread.sleep(500);
          } catch (Exception ignored) {
          } finally {
            try {
              e.close();
            } catch (Exception ignored) {
            }
          }
        });
    server.start();
    try (var client = new SdmxClient(100)) {
      var schema = SdmxCoreTest.schema();
      var query =
          new SdmxQuery(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "CH1.COU",
              "DF_COU_HEALTH_FINANCING",
              "1.0.0",
              Map.of(),
              "",
              "",
              "de");
      try (var reader = client.observations(query, schema, schema.fields(false))) {
        assertThat(reader.next()[0]).isEqualTo("Q_1");
        assertThatThrownBy(reader::next).isInstanceOf(java.io.IOException.class);
      }
      assertThat(requests).hasValue(1);
    } finally {
      server.stop(0);
    }
  }
}
