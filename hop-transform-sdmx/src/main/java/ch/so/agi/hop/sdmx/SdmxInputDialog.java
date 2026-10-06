package ch.so.agi.hop.sdmx;

import static ch.so.agi.hop.sdmx.core.SdmxModel.*;

import ch.so.agi.hop.sdmx.core.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.ui.core.PropsUi;
import org.apache.hop.ui.pipeline.transform.BaseTransformDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/** Edits a private metadata copy. Worker threads never access SWT widgets. */
public class SdmxInputDialog extends BaseTransformDialog {
  private final SdmxInputMeta input;
  private final IVariables vars;
  private final SdmxInputMeta draft;
  private final Map<String, Text> source = new LinkedHashMap<>();
  private final Map<String, Text> filters = new LinkedHashMap<>();
  private Composite body, filterBody;
  private ScrolledComposite filterScroll;
  private Text name;
  private Table fieldTable;
  private Button labels, ok;
  private Label status;
  private Schema loaded;
  private volatile SdmxClient active;
  private Future<?> pending;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "sdmx-dialog");
            t.setDaemon(true);
            return t;
          });

  public SdmxInputDialog(Shell parent, IVariables vars, SdmxInputMeta meta, PipelineMeta pipeline) {
    super(parent, vars, meta, pipeline);
    this.input = meta;
    this.vars = vars;
    this.draft = (SdmxInputMeta) meta.clone();
  }

  @Override
  public String open() {
    shell = new Shell(getParent(), SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MAX);
    shell.setText("SDMX Input");
    shell.setLayout(new FormLayout());
    shell.setMinimumSize(780, 650);
    PropsUi.setLook(shell);
    setShellImage(shell, input);
    Composite container = new Composite(shell, SWT.NONE);
    container.setLayout(new GridLayout(1, false));
    FormData bounds = new FormData();
    bounds.left = new FormAttachment(0, 8);
    bounds.right = new FormAttachment(100, -8);
    bounds.top = new FormAttachment(0, 8);
    bounds.bottom = new FormAttachment(100, -38);
    container.setLayoutData(bounds);
    body = new Composite(container, SWT.NONE);
    body.setLayout(new GridLayout(1, false));
    body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    Composite title = new Composite(body, SWT.NONE);
    title.setLayout(new GridLayout(2, false));
    title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    new Label(title, SWT.NONE).setText("Transform name");
    name = text(title, transformName);
    TabFolder tabs = new TabFolder(body, SWT.NONE);
    tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    Composite connection = tab(tabs, "Source", 2);
    addSource(connection, "Endpoint", draft.getEndpoint());
    addSource(connection, "Agency", draft.getAgency());
    addSource(connection, "Dataflow", draft.getDataflow());
    addSource(connection, "Version", draft.getVersion());
    addSource(connection, "Start period", draft.getStartPeriod());
    addSource(connection, "End period", draft.getEndPeriod());
    addSource(connection, "Language", draft.getLanguage());
    Label hint = new Label(connection, SWT.WRAP);
    hint.setText(
        "Values support ${PARAMETER}. Leave version empty only when loading a structure; a concrete"
            + " version will be saved.");
    hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    button(connection, "Find dataflow...", this::findFlows);
    button(connection, "Load / refresh structure", this::loadStructure);
    Composite filterTab = tab(tabs, "Dimensions", 1);
    new Label(filterTab, SWT.WRAP)
        .setText(
            "Comma-separated codes; empty or * means all. Parameters remain editable. Choices do"
                + " not guarantee available observations.");
    filterScroll = new ScrolledComposite(filterTab, SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
    filterScroll.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    filterScroll.setExpandHorizontal(true);
    filterScroll.setExpandVertical(true);
    filterBody = new Composite(filterScroll, SWT.NONE);
    filterBody.setLayout(new GridLayout(3, false));
    filterScroll.setContent(filterBody);
    Composite output = tab(tabs, "Output fields", 1);
    labels = new Button(output, SWT.CHECK);
    labels.setText("Include separate label fields");
    labels.setSelection(draft.isIncludeLabels());
    labels.addListener(
        SWT.Selection,
        e -> {
          if (loaded != null) {
            draft.setIncludeLabels(labels.getSelection());
            draft.setFieldsJson(SdmxModel.write(loaded.fields(labels.getSelection())));
            showFields();
          }
        });
    fieldTable = table(output, new String[] {"Output name", "Component", "Type", "Label"});
    fieldTable.addListener(SWT.DefaultSelection, e -> renameField());
    button(output, "Rename selected field...", this::renameField);
    new Label(output, SWT.WRAP)
        .setText(
            "Field types come from the saved structure. Refreshing the structure regenerates output"
                + " fields.");
    status = new Label(container, SWT.WRAP);
    status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    status.setText("Load a structure to configure dimensions and output fields.");
    Composite actions = new Composite(container, SWT.NONE);
    actions.setLayout(new GridLayout(4, false));
    actions.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
    button(actions, "Preview (50 rows)", this::preview);
    button(actions, "Cancel request", this::cancelRequest);
    ok = button(actions, "OK", this::accept);
    button(
        actions,
        "Cancel",
        () -> {
          transformName = null;
          shell.dispose();
        });
    shell.addListener(SWT.Close, e -> transformName = null);
    shell.addListener(
        SWT.Dispose,
        e -> {
          if (active != null) active.close();
          if (pending != null) pending.cancel(true);
          executor.shutdownNow();
        });
    if (!draft.getSchemaJson().isBlank()) {
      try {
        loaded = draft.schema();
        showFilters(SdmxModel.filters(draft.getFiltersJson()));
        showFields();
        status.setText(
            "Using saved structure " + loaded.flow().id() + " " + loaded.flow().version());
      } catch (Exception e) {
        status.setText(e.getMessage());
      }
    }
    shell.setSize(900, 720);
    shell.open();
    while (!shell.isDisposed())
      if (!shell.getDisplay().readAndDispatch()) shell.getDisplay().sleep();
    return transformName;
  }

  private Composite tab(TabFolder tabs, String title, int columns) {
    TabItem item = new TabItem(tabs, SWT.NONE);
    item.setText(title);
    Composite panel = new Composite(tabs, SWT.NONE);
    panel.setLayout(new GridLayout(columns, false));
    item.setControl(panel);
    return panel;
  }

  private Text text(Composite parent, String value) {
    Text t = new Text(parent, SWT.BORDER);
    t.setText(value == null ? "" : value);
    t.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    return t;
  }

  private void addSource(Composite parent, String label, String value) {
    new Label(parent, SWT.NONE).setText(label);
    source.put(label, text(parent, value));
  }

  private Button button(Composite parent, String label, Runnable action) {
    Button b = new Button(parent, SWT.PUSH);
    b.setText(label);
    b.addListener(
        SWT.Selection,
        e -> {
          try {
            action.run();
          } catch (Exception ex) {
            error(ex);
          }
        });
    return b;
  }

  private Table table(Composite parent, String[] headers) {
    Table t = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
    t.setHeaderVisible(true);
    t.setLinesVisible(true);
    t.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    for (String h : headers) {
      TableColumn c = new TableColumn(t, SWT.NONE);
      c.setText(h);
      c.setWidth(180);
    }
    return t;
  }

  private void capture() {
    draft.setEndpoint(source.get("Endpoint").getText().trim());
    draft.setAgency(source.get("Agency").getText().trim());
    draft.setDataflow(source.get("Dataflow").getText().trim());
    draft.setVersion(source.get("Version").getText().trim());
    draft.setStartPeriod(source.get("Start period").getText());
    draft.setEndPeriod(source.get("End period").getText());
    draft.setLanguage(source.get("Language").getText());
    draft.setIncludeLabels(labels.getSelection());
    Map<String, String> values = new LinkedHashMap<>();
    filters.forEach((id, t) -> values.put(id, t.getText()));
    draft.setFiltersJson(SdmxModel.write(values));
  }

  private void loadStructure() {
    capture();
    var q = draft.query(vars);
    Map<String, String> previous = SdmxModel.filters(draft.getFiltersJson());
    work(
        client -> client.structure(q),
        schema -> {
          loaded = schema;
          draft.saveSchema(schema);
          source.get("Version").setText(draft.getVersion());
          showFilters(previous);
          showFields();
          status.setText(
              "Loaded "
                  + schema.flow().id()
                  + " "
                  + schema.flow().version()
                  + "; output fields regenerated.");
        });
  }

  private void showFilters(Map<String, String> previous) {
    for (Control c : filterBody.getChildren()) c.dispose();
    filters.clear();
    String language = vars.resolve(source.get("Language").getText());
    for (Component c : loaded.dimensions()) {
      new Label(filterBody, SWT.NONE).setText(c.id() + " — " + c.label(language));
      Text value = text(filterBody, previous.getOrDefault(c.id(), ""));
      filters.put(c.id(), value);
      button(filterBody, "Choose...", () -> chooseCodes(c, value));
    }
    filterBody.layout(true, true);
    filterScroll.setMinSize(filterBody.computeSize(SWT.DEFAULT, SWT.DEFAULT));
  }

  private void showFields() {
    fieldTable.removeAll();
    for (Field f : draft.fields())
      new TableItem(fieldTable, SWT.NONE)
          .setText(new String[] {f.name(), f.component(), f.type().name(), f.label() ? "Yes" : ""});
  }

  private void renameField() {
    int index = fieldTable.getSelectionIndex();
    if (index < 0) return;
    List<Field> fields = new ArrayList<>(draft.fields());
    Field field = fields.get(index);
    Shell dialog = modal("Output field name");
    Text value = text(dialog, field.name());
    button(
        dialog,
        "Apply",
        () -> {
          fields.set(
              index,
              new Field(value.getText().trim(), field.component(), field.type(), field.label()));
          loaded.validateFields(fields);
          draft.setFieldsJson(SdmxModel.write(fields));
          showFields();
          dialog.dispose();
        });
    openModal(dialog, 440, 150);
  }

  private Shell modal(String title) {
    Shell s = new Shell(shell, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
    s.setText(title);
    s.setLayout(new GridLayout(1, false));
    return s;
  }

  private void openModal(Shell s, int w, int h) {
    s.setSize(w, h);
    s.open();
  }

  private void chooseCodes(Component component, Text target) {
    Shell dialog = modal("Select " + component.id());
    Text search = text(dialog, "");
    search.setMessage("Search code or label");
    Table choices = new Table(dialog, SWT.CHECK | SWT.BORDER | SWT.V_SCROLL);
    choices.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    Set<String> selected = new LinkedHashSet<>(Arrays.asList(target.getText().split(",")));
    selected.remove("");
    String language = vars.resolve(source.get("Language").getText());
    Runnable refresh =
        () -> {
          choices.removeAll();
          String term = search.getText().toLowerCase(Locale.ROOT);
          for (Code c : loaded.choices(component.id())) {
            String label = c.id() + " — " + c.label(language);
            if (!label.toLowerCase(Locale.ROOT).contains(term)) continue;
            TableItem i = new TableItem(choices, SWT.NONE);
            i.setText(label);
            i.setData(c.id());
            i.setChecked(selected.contains(c.id()));
          }
        };
    choices.addListener(
        SWT.Selection,
        e -> {
          if (e.detail == SWT.CHECK) {
            TableItem i = (TableItem) e.item;
            String id = (String) i.getData();
            if (i.getChecked()) selected.add(id);
            else selected.remove(id);
          }
        });
    search.addModifyListener(e -> refresh.run());
    refresh.run();
    button(
        dialog,
        "Use selected codes",
        () -> {
          target.setText(
              String.join(
                  ",",
                  selected.stream()
                      .filter(
                          id ->
                              loaded.choices(component.id()).stream()
                                  .anyMatch(c -> c.id().equals(id)))
                      .toList()));
          dialog.dispose();
        });
    button(
        dialog,
        "All values",
        () -> {
          target.setText("");
          dialog.dispose();
        });
    openModal(dialog, 580, 520);
  }

  private void findFlows() {
    String endpoint = vars.resolve(source.get("Endpoint").getText());
    String language = vars.resolve(source.get("Language").getText());
    work(
        client -> client.flows(endpoint, language),
        flows -> {
          Shell dialog = modal("Find dataflow");
          Text search = text(dialog, "");
          search.setMessage("Search agency, identifier or title");
          Table list = table(dialog, new String[] {"Agency", "Dataflow", "Version", "Title"});
          Runnable refresh =
              () -> {
                list.removeAll();
                String term = search.getText().toLowerCase(Locale.ROOT);
                for (Flow f : flows) {
                  String title = f.label(language);
                  if (!(f.agency() + " " + f.id() + " " + title)
                      .toLowerCase(Locale.ROOT)
                      .contains(term)) continue;
                  TableItem item = new TableItem(list, SWT.NONE);
                  item.setText(new String[] {f.agency(), f.id(), f.version(), title});
                  item.setData(f);
                }
              };
          search.addModifyListener(e -> refresh.run());
          refresh.run();
          button(
              dialog,
              "Select",
              () -> {
                if (list.getSelectionCount() == 0) return;
                Flow f = (Flow) list.getSelection()[0].getData();
                source.get("Agency").setText(f.agency());
                source.get("Dataflow").setText(f.id());
                source.get("Version").setText(f.version());
                dialog.dispose();
              });
          openModal(dialog, 840, 540);
          status.setText("Catalog loaded. Select a dataflow, then load its structure.");
        });
  }

  private void preview() {
    capture();
    draft.validate();
    var q = draft.query(vars);
    var saved = draft.schema();
    var fields = draft.fields();
    if (q.version().isBlank())
      throw new IllegalArgumentException("Preview requires a concrete dataflow version");
    work(
        client -> {
          var actual = client.structure(q);
          saved.requireCompatible(actual);
          List<Object[]> rows = new ArrayList<>();
          try (var reader = client.observations(q, actual, fields)) {
            Object[] row;
            while (rows.size() < 50 && (row = reader.next()) != null) rows.add(row);
          }
          return rows;
        },
        rows -> {
          Shell dialog = modal("Preview — first 50 observations");
          Table t = table(dialog, fields.stream().map(Field::name).toArray(String[]::new));
          for (Object[] row : rows)
            new TableItem(t, SWT.NONE)
                .setText(
                    Arrays.stream(row).map(v -> Objects.toString(v, "")).toArray(String[]::new));
          openModal(dialog, 1000, 500);
          status.setText("Preview: " + rows.size() + " observations.");
        });
  }

  @FunctionalInterface
  private interface Task<T> {
    T run(SdmxClient client) throws Exception;
  }

  private <T> void work(Task<T> task, Consumer<T> finish) {
    if (pending != null && !pending.isDone())
      throw new IllegalStateException("A request is already running");
    body.setEnabled(false);
    ok.setEnabled(false);
    status.setText("Loading…");
    Display display = shell.getDisplay();
    SdmxClient client = new SdmxClient();
    active = client;
    pending =
        executor.submit(
            () -> {
              try (client) {
                T result = task.run(client);
                display.asyncExec(
                    () -> {
                      if (!shell.isDisposed() && active == client) {
                        active = null;
                        body.setEnabled(true);
                        ok.setEnabled(true);
                        try {
                          finish.accept(result);
                        } catch (Exception e) {
                          error(e);
                        }
                      }
                    });
              } catch (Exception e) {
                display.asyncExec(
                    () -> {
                      if (!shell.isDisposed() && active == client) {
                        active = null;
                        body.setEnabled(true);
                        ok.setEnabled(true);
                        error(e);
                      }
                    });
              }
            });
  }

  private void cancelRequest() {
    if (active != null) active.close();
    active = null;
    if (pending != null) pending.cancel(true);
    body.setEnabled(true);
    ok.setEnabled(true);
    status.setText("Request cancelled; saved configuration unchanged.");
  }

  private void error(Exception e) {
    if (shell.isDisposed()) return;
    status.setText(Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
    shell.layout();
  }

  private void accept() {
    capture();
    draft.validate();
    if (name.getText().isBlank()) throw new IllegalArgumentException("Transform name is required");
    input.setEndpoint(draft.getEndpoint());
    input.setAgency(draft.getAgency());
    input.setDataflow(draft.getDataflow());
    input.setVersion(draft.getVersion());
    input.setStartPeriod(draft.getStartPeriod());
    input.setEndPeriod(draft.getEndPeriod());
    input.setLanguage(draft.getLanguage());
    input.setFiltersJson(draft.getFiltersJson());
    input.setSchemaJson(draft.getSchemaJson());
    input.setFieldsJson(draft.getFieldsJson());
    input.setIncludeLabels(draft.isIncludeLabels());
    input.setChanged();
    transformName = name.getText();
    shell.dispose();
  }
}
