package ch.so.agi.hop.sdmx;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.widgets.*;
import org.junit.jupiter.api.*;

class SdmxInputDialogTest {
  @Test
  void actualDialogCancelPreservesMetadataAndRendersSavedSchema() throws Exception {
    HopEnvironment.init();
    Display display = Display.getDefault();
    Shell parent = new Shell(display);
    var meta = SdmxInputMetaTest.configured();
    meta.setStartPeriod("${START_PERIOD}");
    String xml = meta.getXml();
    boolean changed = meta.hasChanged();
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.addTransform(new TransformMeta("SDMX Input", meta));
    var dialog = new SdmxInputDialog(parent, new Variables(), meta, pipeline);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    display.timerExec(
        300,
        () -> {
          for (Shell shell : display.getShells())
            if (shell.getText().equals("SDMX Input")) {
              try {
                assertThat(find(shell, Text.class).stream().map(Text::getText))
                    .contains("${START_PERIOD}", "1.0.0");
                Text first = find(shell, Text.class).getFirst();
                first.setText("discard this name");
                Image image = new Image(display, shell.getSize().x, shell.getSize().y);
                GC gc = new GC(shell);
                gc.copyArea(image, 0, 0);
                gc.dispose();
                ImageLoader loader = new ImageLoader();
                loader.data = new ImageData[] {image.getImageData()};
                new java.io.File("target").mkdirs();
                loader.save("target/sdmx-dialog.png", SWT.IMAGE_PNG);
                image.dispose();
              } catch (Throwable e) {
                failure.set(e);
              } finally {
                shell.close();
              }
            }
        });
    try {
      assertThat(dialog.open()).isNull();
      if (failure.get() != null) throw new AssertionError(failure.get());
      assertThat(meta.getXml()).isEqualTo(xml);
      assertThat(meta.hasChanged()).isEqualTo(changed);
    } finally {
      parent.dispose();
    }
  }

  @Test
  void structureRequestRunsOffUiThreadAndCancelDoesNotCommit() throws Exception {
    HopEnvironment.init();
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var inFlight = new java.util.concurrent.atomic.AtomicBoolean();
    var gate = new java.util.concurrent.CountDownLatch(1);
    byte[] fixture =
        java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../e2e/fixtures/structure.json"));
    server.createContext(
        "/dataflow",
        exchange -> {
          inFlight.set(true);
          try {
            gate.await(2, java.util.concurrent.TimeUnit.SECONDS);
            exchange
                .getResponseHeaders()
                .set("Content-Type", "application/vnd.sdmx.structure+json");
            exchange.sendResponseHeaders(200, fixture.length);
            exchange.getResponseBody().write(fixture);
          } catch (Exception ignored) {
          } finally {
            inFlight.set(false);
            exchange.close();
          }
        });
    server.start();
    Display display = Display.getDefault();
    Shell parent = new Shell(display);
    var meta = SdmxInputMetaTest.configured();
    meta.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
    String before = meta.getXml();
    PipelineMeta pm = new PipelineMeta();
    pm.addTransform(new TransformMeta("SDMX Input", meta));
    var dialog = new SdmxInputDialog(parent, new Variables(), meta, pm);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    display.timerExec(
        150,
        () -> {
          for (Shell shell : display.getShells())
            if (shell.getText().equals("SDMX Input")) {
              find(shell, Button.class).stream()
                  .filter(b -> b.getText().equals("Load / refresh structure"))
                  .findFirst()
                  .orElseThrow()
                  .notifyListeners(SWT.Selection, new Event());
              display.timerExec(
                  150,
                  () -> {
                    try {
                      assertThat(inFlight.get())
                          .as("UI remains responsive during HTTP request")
                          .isTrue();
                    } catch (Throwable e) {
                      failure.set(e);
                    } finally {
                      gate.countDown();
                      shell.close();
                    }
                  });
            }
        });
    try {
      assertThat(dialog.open()).isNull();
      if (failure.get() != null) throw new AssertionError(failure.get());
      assertThat(meta.getXml()).isEqualTo(before);
    } finally {
      gate.countDown();
      server.stop(0);
      parent.dispose();
    }
  }

  private static <T extends Control> java.util.List<T> find(Composite parent, Class<T> type) {
    java.util.List<T> result = new java.util.ArrayList<>();
    for (Control c : parent.getChildren()) {
      if (type.isInstance(c)) result.add(type.cast(c));
      if (c instanceof Composite child) result.addAll(find(child, type));
    }
    return result;
  }
}
