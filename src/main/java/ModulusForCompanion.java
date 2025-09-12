import javafx.application.Application;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ModulusForCompanion extends Application {
  private static final String KEY_PAGES = "pages";
  private static final String KEY_CONTROLS = "controls";
  private static final String KEY_STEPS = "steps";
  private static final String KEY_ACTION_SETS = "action_sets";
  private static final String KEY_INSTANCES = "instances";
  private static final String KEY_TYPE = "type";
  private static final String KEY_CONNECTION_ID = "connectionId";
  private static final String KEY_OPTIONS = "options";
  private static final String KEY_INSTANCE_ID = "instance_id";

  private enum Theme { LIGHT, DARK }
  private static final String PREF_KEY_THEME = "theme";
  private Theme currentTheme = Theme.LIGHT;
  private Scene scene;
  private final Preferences prefs = Preferences.userNodeForPackage(ModulusForCompanion.class);

  public static class LineRow {
    final SimpleStringProperty page   = new SimpleStringProperty();
    final SimpleStringProperty button = new SimpleStringProperty();
    final SimpleStringProperty action = new SimpleStringProperty();
    LineRow(String page, String button, String action) { this.page.set(page); this.button.set(button); this.action.set(action); }
    public String getPage()   { return page.get(); }
    public String getButton() { return button.get(); }
    public String getAction() { return action.get(); }
  }

  private static class UsageRecord {
    final String moduleInstanceId;
    final int page, row, col, step;
    final String actionDefId;
    final String buttonIdentity; // used for grouping (e.g., "3.7" or "31")
    final String buttonDisplay;  // shown to the user (e.g., "3.7" for new, "31" for legacy)
    UsageRecord(String moduleInstanceId, int page, int row, int col, int step, String actionDefId,
                String buttonIdentity, String buttonDisplay) {
      this.moduleInstanceId = moduleInstanceId;
      this.page = page; this.row = row; this.col = col; this.step = step;
      this.actionDefId = actionDefId;
      this.buttonIdentity = buttonIdentity;
      this.buttonDisplay = buttonDisplay;
    }
  }

  private final ComboBox<String> moduleDropdown = new ComboBox<>();
  private final TableView<LineRow> table = new TableView<>();
  private final Label status = new Label("Drop a .companionconfig (YAML/JSON/GZ/ZIP) or use File > Open.");
  private final Button copyBtn = new Button("Copy Selected");
  private final Button exportBtn = new Button("Export list");
  private final Button fullExportBtn = new Button("Full export");

  private Map<String, Map<String, Object>> instances;
  private Map<String, String> instanceLabels;
  private List<UsageRecord> allUsage = new ArrayList<>();

  private String currentConfigName = null;
  private final Map<String, List<LineRow>> allModuleRows = new LinkedHashMap<>();

  @Override
  public void start(Stage stage) {
    stage.setTitle("Modulus for Companion");
    try {
      Image icon = new Image(Objects.requireNonNull(
          ModulusForCompanion.class.getResourceAsStream("/icons/app.png")));
      stage.getIcons().add(icon);
    } catch (Exception ignored) {}

    MenuBar menuBar = new MenuBar();
    Menu fileMenu = new Menu("File");
    MenuItem openItem = new MenuItem("Open Config…");
    openItem.setOnAction(e -> openConfigDialog(stage));
    MenuItem copyItem = new MenuItem("Copy Selected");
    copyItem.setOnAction(e -> copySelectedRows());
    fileMenu.getItems().addAll(openItem, new SeparatorMenuItem(), copyItem);
    menuBar.getMenus().add(fileMenu);

    ToggleButton lightBtn = new ToggleButton("☀︎");
    ToggleButton darkBtn  = new ToggleButton("☾");
    ToggleGroup tg = new ToggleGroup();
    lightBtn.setToggleGroup(tg);
    darkBtn.setToggleGroup(tg);
    String saved = prefs.get(PREF_KEY_THEME, "light");
    currentTheme = "dark".equalsIgnoreCase(saved) ? Theme.DARK : Theme.LIGHT;
    if (currentTheme == Theme.DARK) darkBtn.setSelected(true); else lightBtn.setSelected(true);
    lightBtn.setOnAction(e -> { currentTheme = Theme.LIGHT; applyTheme(); prefs.put(PREF_KEY_THEME, "light"); });
    darkBtn.setOnAction(e  -> { currentTheme = Theme.DARK;  applyTheme(); prefs.put(PREF_KEY_THEME, "dark");  });
    HBox themeBox = new HBox(lightBtn, darkBtn);
    themeBox.getStyleClass().add("theme-toggle-box");

    moduleDropdown.setPromptText("Select a module...");
    moduleDropdown.setDisable(true);
    moduleDropdown.valueProperty().addListener((obs, o, n) -> {
      refreshTableForModule(n);
      exportBtn.setDisable(table.getItems().isEmpty());
    });

    copyBtn.setVisible(false);
    copyBtn.setManaged(false);

    exportBtn.setDisable(true);
    fullExportBtn.setDisable(true);
    exportBtn.setOnAction(e -> exportCurrentTable(stage));
    fullExportBtn.setOnAction(e -> exportAllTables(stage));

    Region spacer = new Region();
    HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
    HBox top = new HBox(10, new Label("Module:"), moduleDropdown, exportBtn, fullExportBtn, spacer, themeBox);
    top.setPadding(new Insets(10));

    TableColumn<LineRow, String> colPage = new TableColumn<>("Page");
    colPage.setCellValueFactory(c -> c.getValue().page);
    colPage.setMinWidth(80);
    colPage.setMaxWidth(120);

    TableColumn<LineRow, String> colButton = new TableColumn<>("Button");
    colButton.setCellValueFactory(c -> c.getValue().button);

    TableColumn<LineRow, String> colAction = new TableColumn<>("Action");
    colAction.setCellValueFactory(c -> c.getValue().action);
    colAction.setMinWidth(160);

    table.getColumns().setAll(colPage, colButton, colAction);
    table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

    final KeyCombination COPY_KC = new KeyCodeCombination(
        KeyCode.C,
        System.getProperty("os.name").toLowerCase().contains("mac")
            ? KeyCombination.META_DOWN : KeyCombination.CONTROL_DOWN
    );
    table.setOnKeyPressed(e -> { if (COPY_KC.match(e)) copySelectedRows(); });

    BorderPane root = new BorderPane();
    root.setTop(new VBox(menuBar, top));
    root.setCenter(table);
    BorderPane.setMargin(table, new Insets(10));
    status.setPadding(new Insets(8));
    root.setBottom(status);

    scene = new Scene(root, 1000, 560);
    scene.setOnDragOver(event -> { Dragboard db = event.getDragboard(); if (db.hasFiles()) event.acceptTransferModes(TransferMode.COPY); event.consume(); });
    scene.setOnDragDropped(this::handleFileDrop);

    stage.setScene(scene);
    applyTheme();
    stage.show();
  }

  private void applyTheme() {
    if (scene == null) return;
    scene.getStylesheets().clear();
    try {
      String themeCss = (currentTheme == Theme.DARK)
          ? getClass().getResource("/theme_dark.css").toExternalForm()
          : getClass().getResource("/theme_light.css").toExternalForm();
      scene.getStylesheets().add(themeCss);
    } catch (Exception ignored) {}
  }

  private void copySelectedRows() {
    ObservableList<LineRow> sel = table.getSelectionModel().getSelectedItems();
    if (sel == null || sel.isEmpty()) return;
    String out = sel.stream()
        .map(r -> (r.getPage() == null || r.getPage().isBlank()) ? "  " + r.getButton() : r.getPage())
        .collect(Collectors.joining(System.lineSeparator()));
    ClipboardContent cc = new ClipboardContent();
    cc.putString(out);
    Clipboard.getSystemClipboard().setContent(cc);
  }

  private void openConfigDialog(Stage stage) {
    FileChooser fc = new FileChooser();
    fc.setTitle("Open Companion Config");
    fc.getExtensionFilters().addAll(
        new FileChooser.ExtensionFilter("Companion Config (*.companionconfig)", "*.companionconfig"),
        new FileChooser.ExtensionFilter("All Files", "*.*")
    );
    File f = fc.showOpenDialog(stage);
    if (f != null) loadConfig(f);
  }

  private void handleFileDrop(DragEvent event) {
    Dragboard db = event.getDragboard();
    boolean success = false;
    if (db.hasFiles()) { File f = db.getFiles().get(0); loadConfig(f); success = true; }
    event.setDropCompleted(success);
    event.consume();
  }

  @SuppressWarnings("unchecked")
  private void loadConfig(File f) {
    try {
      Map<String,Object> root = readConfigAuto(f);
      if (root == null) { setStatus("Unsupported or empty config: " + f.getName()); return; }

      Object instObj = root.get(KEY_INSTANCES);
      if (!(instObj instanceof Map)) { setStatus("No `instances` section found; is this a Companion export?"); return; }
      this.instances = (Map<String, Map<String, Object>>) (Map<?,?>) instObj;

      this.instanceLabels = new TreeMap<>();
      for (Map.Entry<String, Map<String, Object>> e : instances.entrySet()) {
        String id = e.getKey();
        Object labelObj = e.getValue() == null ? null : e.getValue().get("label");
        String label = (labelObj == null) ? id : String.valueOf(labelObj);
        if (id != null) instanceLabels.put(id, label);
      }

      this.allUsage = scanUsage(root, instanceLabels);

      Set<String> usedInstanceIds = new TreeSet<>();
      for (UsageRecord u : allUsage) usedInstanceIds.add(u.moduleInstanceId);

      List<String> dropdownLabels = usedInstanceIds.stream()
          .map(id -> instanceLabels.getOrDefault(id, id) + " (" + id + ")")
          .sorted(String.CASE_INSENSITIVE_ORDER)
          .collect(Collectors.toList());

      moduleDropdown.setItems(FXCollections.observableArrayList(dropdownLabels));
      moduleDropdown.setDisable(dropdownLabels.isEmpty());

      allModuleRows.clear();
      for (String dd : dropdownLabels) {
        String instanceId = extractInstanceId(dd);
        String labelOnly = dd.replaceAll("\\s*\\(.*\\)$", "");
        allModuleRows.put(labelOnly, new ArrayList<>(buildRowsForInstance(instanceId)));
      }

      this.currentConfigName = f.getName();
      fullExportBtn.setDisable(allModuleRows.isEmpty());

      table.getItems().clear();
      exportBtn.setDisable(true);

      if (!dropdownLabels.isEmpty()) {
        moduleDropdown.getSelectionModel().select(0);
        refreshTableForModule(dropdownLabels.get(0));
        setStatus("Parsed " + f.getName() + " (" + dropdownLabels.size() + " modules with usages). Selected: " + dropdownLabels.get(0));
      } else {
        setStatus("Parsed " + f.getName() + ". No module usages found.");
      }
    } catch (Exception ex) {
      ex.printStackTrace();
      setStatus("Failed to load (" + ex.getClass().getSimpleName() + "): " + (ex.getMessage() == null ? "(no message)" : ex.getMessage()));
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String,Object> readConfigAuto(File file) throws Exception {
    byte[] all = readAll(file);
    if (all.length >= 2 && (all[0] & 0xFF) == 0x1F && (all[1] & 0xFF) == 0x8B) {
      try (GZIPInputStream gin = new GZIPInputStream(new ByteArrayInputStream(all))) {
        byte[] unz = gin.readAllBytes();
        return parseBuffer(unz, "inner.gz");
      }
    }
    if (all.length >= 2 && all[0] == 0x50 && all[1] == 0x4B) {
      try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(all))) {
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
          if (e.isDirectory()) continue;
          byte[] bytes = zin.readAllBytes();
          Map<String,Object> parsed = tryParseBoth(bytes, e.getName().toLowerCase(Locale.ROOT));
          if (parsed != null) return parsed;
        }
      }
      return null;
    }
    return parseBuffer(all, file.getName().toLowerCase(Locale.ROOT));
  }

  @SuppressWarnings("unchecked")
  private Map<String,Object> parseBuffer(byte[] bytes, String nameLower) {
    String head = new String(bytes, 0, Math.min(bytes.length, 1024), StandardCharsets.UTF_8).trim();
    boolean looksJson = nameLower.endsWith(".json") || head.startsWith("{") || head.startsWith("[");
    try {
      if (looksJson) {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(bytes, Map.class);
      } else {
        LoaderOptions opts = new LoaderOptions();
        opts.setCodePointLimit(256 * 1024 * 1024);
        opts.setMaxAliasesForCollections(1_000_000);
        opts.setAllowDuplicateKeys(true);
        opts.setAllowRecursiveKeys(true);
        Yaml yaml = new Yaml(new Constructor(opts));
        Object rootObj = yaml.load(new ByteArrayInputStream(bytes));
        if (rootObj instanceof Map) return (Map<String,Object>) rootObj;
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(bytes, Map.class);
      }
    } catch (Exception primary) {
      try {
        if (looksJson) {
          LoaderOptions opts = new LoaderOptions();
          opts.setCodePointLimit(256 * 1024 * 1024);
          opts.setMaxAliasesForCollections(1_000_000);
          opts.setAllowDuplicateKeys(true);
          opts.setAllowRecursiveKeys(true);
          Yaml yaml = new Yaml(new Constructor(opts));
          Object rootObj = yaml.load(new ByteArrayInputStream(bytes));
          if (rootObj instanceof Map) return (Map<String,Object>) rootObj;
        } else {
          ObjectMapper mapper = new ObjectMapper();
          return mapper.readValue(bytes, Map.class);
        }
      } catch (Exception ignored) {}
      throw new RuntimeException("Unsupported or malformed config");
    }
  }

  private Map<String,Object> tryParseBoth(byte[] bytes, String nameLower) {
    try { return parseBuffer(bytes, nameLower); }
    catch (RuntimeException re) { return null; }
  }

  private static byte[] readAll(File f) throws Exception {
    try (InputStream in = new BufferedInputStream(new FileInputStream(f))) { return in.readAllBytes(); }
  }

  private void setStatus(String msg) { status.setText(msg); }

  private void refreshTableForModule(String dropdownValue) {
    table.getItems().clear();
    exportBtn.setDisable(true);
    if (dropdownValue == null || dropdownValue.isBlank()) return;
    String instanceId = extractInstanceId(dropdownValue);
    ObservableList<LineRow> rows = buildRowsForInstance(instanceId);
    table.setItems(rows);
    exportBtn.setDisable(rows.isEmpty());
    String labelOnly = dropdownValue.replaceAll("\\s*\\(.*\\)$", "");
    allModuleRows.put(labelOnly, new ArrayList<>(rows));
  }

  private ObservableList<LineRow> buildRowsForInstance(String instanceId) {
    class Agg {
      final Set<Integer> steps = new TreeSet<>();
      final LinkedHashSet<String> actions = new LinkedHashSet<>();
      String display; // button display label for this identity
    }
    Map<Integer, Map<String, Agg>> byPage = new TreeMap<>();

    for (UsageRecord u : allUsage) {
      if (!u.moduleInstanceId.equals(instanceId)) continue;
      Map<String, Agg> pageMap = byPage.computeIfAbsent(u.page, k -> new LinkedHashMap<>());
      Agg agg = pageMap.computeIfAbsent(u.buttonIdentity, k -> {
        Agg a = new Agg();
        a.display = u.buttonDisplay;
        return a;
      });
      agg.steps.add(u.step);
      if (u.actionDefId != null && !u.actionDefId.isBlank()) {
        agg.actions.add(u.actionDefId.trim());
      }
    }

    // order identities: numeric-aware if they look like "row.col", else numeric for plain numbers
    Comparator<String> identCmp = (a, b) -> {
      if (a.contains(".") && b.contains(".")) {
        int[] A = parsePair(a); int[] B = parsePair(b);
        if (A[0] != B[0]) return Integer.compare(A[0], B[0]);
        return Integer.compare(A[1], B[1]);
      } else if (!a.contains(".") && !b.contains(".")) {
        try {
          return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        } catch (Exception e) { return a.compareToIgnoreCase(b); }
      } else {
        // keep numbers before dotted forms
        return a.contains(".") ? 1 : -1;
      }
    };

    ObservableList<LineRow> rows = FXCollections.observableArrayList();
    for (Map.Entry<Integer, Map<String, Agg>> pe : byPage.entrySet()) {
      String pageStr = String.valueOf(pe.getKey());
      rows.add(new LineRow(pageStr, "", ""));
      List<Map.Entry<String, Agg>> entries = new ArrayList<>(pe.getValue().entrySet());
      entries.sort((e1, e2) -> identCmp.compare(e1.getKey(), e2.getKey()));
      for (Map.Entry<String, Agg> be : entries) {
        String btnDisplay = be.getValue().display;
        List<Integer> steps = new ArrayList<>(be.getValue().steps);
        String stepText = steps.size() == 1
            ? "(step " + steps.get(0) + ")"
            : "(steps " + steps.stream().map(Object::toString).collect(Collectors.joining(", ")) + ")";
        String actionText = be.getValue().actions.isEmpty() ? "" : String.join(", ", be.getValue().actions);
        rows.add(new LineRow("", btnDisplay + " " + stepText, actionText));
      }
    }
    return rows;
  }

  private static int[] parsePair(String s) {
    try {
      String[] p = s.split("\\.");
      return new int[]{ Integer.parseInt(p[0]), Integer.parseInt(p[1]) };
    } catch (Exception e) { return new int[]{ Integer.MAX_VALUE, Integer.MAX_VALUE }; }
  }

  private static String extractInstanceId(String dropdownValue) {
    return dropdownValue.replaceAll(".*\\((.*)\\)$", "$1");
  }

  private void exportCurrentTable(Stage stage) {
    String selected = moduleDropdown.getValue();
    if (selected == null || currentConfigName == null) return;
    String label = selected.replaceAll("\\s*\\(.*\\)$", "").replaceAll("\\s+", "_");
    String base = new File(currentConfigName).getName();
    String defaultName = label + "." + base + ".csv";
    FileChooser fc = new FileChooser();
    fc.setTitle("Save CSV for " + label);
    fc.setInitialFileName(defaultName);
    fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
    File dest = fc.showSaveDialog(stage);
    if (dest != null) {
      writeCsv(dest, table.getItems(), false, selected.replaceAll("\\s*\\(.*\\)$", ""));
      setStatus("Exported current module to " + dest.getName());
    }
  }

  private void exportAllTables(Stage stage) {
    if (currentConfigName == null || allModuleRows.isEmpty()) return;
    String base = new File(currentConfigName).getName();
    String defaultName = base + ".csv";
    FileChooser fc = new FileChooser();
    fc.setTitle("Save full CSV");
    fc.setInitialFileName(defaultName);
    fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
    File dest = fc.showSaveDialog(stage);
    if (dest != null) {
      try (PrintWriter out = new PrintWriter(new FileWriter(dest))) {
        out.println("Module,Page,Button,Action");
        for (Map.Entry<String,List<LineRow>> entry : allModuleRows.entrySet()) {
          String moduleLabel = entry.getKey();
          for (LineRow row : entry.getValue()) {
            boolean isPageHeader = row.getPage() != null && !row.getPage().isBlank();
            String moduleCell = isPageHeader ? csv(moduleLabel) : "";
            out.printf("%s,%s,%s,%s%n",
                moduleCell,
                csv(row.getPage()),
                csv(row.getButton()),
                csv(row.getAction()));
          }
        }
      } catch (Exception ex) {
        ex.printStackTrace();
        setStatus("Full export failed: " + ex.getMessage());
        return;
      }
      setStatus("Exported full module list to " + dest.getName());
    }
  }

  private void writeCsv(File dest, List<LineRow> rows, boolean includeModule, String moduleLabel) {
    try (PrintWriter out = new PrintWriter(new FileWriter(dest))) {
      if (includeModule) out.println("Module,Page,Button,Action");
      else out.println("Page,Button,Action");
      for (LineRow r : rows) {
        if (includeModule) {
          out.printf("%s,%s,%s,%s%n",
              csv(moduleLabel), csv(r.getPage()), csv(r.getButton()), csv(r.getAction()));
        } else {
          out.printf("%s,%s,%s%n",
              csv(r.getPage()), csv(r.getButton()), csv(r.getAction()));
        }
      }
    } catch (Exception ex) {
      ex.printStackTrace();
      setStatus("Export failed: " + ex.getMessage());
    }
  }

  private static String csv(String s) {
    if (s == null) s = "";
    return "\"" + s.replace("\"", "\"\"") + "\"";
  }

  @SuppressWarnings("unchecked")
  private static List<UsageRecord> scanUsage(Map<String, Object> root, Map<String, String> instanceLabels) {
    if (root.containsKey(KEY_PAGES) && root.get(KEY_PAGES) instanceof Map) {
      return scanUsageNew(root, instanceLabels);
    }
    if (root.containsKey("actions") && root.get("actions") instanceof Map) {
      return scanUsageLegacy(root, instanceLabels);
    }
    return Collections.emptyList();
  }

  @SuppressWarnings("unchecked")
  private static List<UsageRecord> scanUsageNew(Map<String, Object> root, Map<String, String> instanceLabels) {
    List<UsageRecord> out = new ArrayList<>();
    Map<String, Object> pages = (Map<String, Object>) root.get(KEY_PAGES);
    for (Map.Entry<String, Object> pageEntry : pages.entrySet()) {
      int pageNum = parseIntSafe(pageEntry.getKey(), -1);
      if (!(pageEntry.getValue() instanceof Map)) continue;
      Map<String, Object> pageMap = (Map<String, Object>) pageEntry.getValue();
      Object controlsObj = pageMap.get(KEY_CONTROLS);
      if (!(controlsObj instanceof Map)) continue;
      Map<String, Object> groups = (Map<String, Object>) controlsObj;
      for (Map.Entry<String, Object> groupEntry : groups.entrySet()) {
        int rowIndex = parseIntSafe(groupEntry.getKey(), -1);
        if (!(groupEntry.getValue() instanceof Map)) continue;
        Map<String, Object> controlMap = (Map<String, Object>) groupEntry.getValue();
        for (Map.Entry<String, Object> controlEntry : controlMap.entrySet()) {
          int colIndex = parseIntSafe(controlEntry.getKey(), -1);
          if (!(controlEntry.getValue() instanceof Map)) continue;
          Map<String, Object> control = (Map<String, Object>) controlEntry.getValue();
          Object stepsObj = control.get(KEY_STEPS);
          if (!(stepsObj instanceof Map)) continue;
          Map<String, Object> stepsMap = (Map<String, Object>) stepsObj;
          for (Map.Entry<String, Object> stepEntry : stepsMap.entrySet()) {
            int stepIndex = parseIntSafe(stepEntry.getKey(), -1);
            if (!(stepEntry.getValue() instanceof Map)) continue;
            Map<String, Object> stepMap = (Map<String, Object>) stepEntry.getValue();
            Object actionSetsObj = stepMap.get(KEY_ACTION_SETS);
            if (!(actionSetsObj instanceof Map)) continue;
            Map<String, Object> actionSets = (Map<String, Object>) actionSetsObj;
            for (Map.Entry<String, Object> asEntry : actionSets.entrySet()) {
              Object listObj = asEntry.getValue();
              if (!(listObj instanceof List)) continue;
              List<Object> actions = (List<Object>) listObj;
              for (Object actionObj : actions) {
                if (!(actionObj instanceof Map)) continue;
                Map<String, Object> action = (Map<String, Object>) actionObj;
                if (!"action".equals(action.get(KEY_TYPE))) continue;
                String connectionId = optString(action.get(KEY_CONNECTION_ID));
                Map<String, Object> opts = asMap(action.get(KEY_OPTIONS));
                String maybeInstance = (opts == null) ? null : optString(opts.get(KEY_INSTANCE_ID));
                boolean direct = connectionId != null && instanceLabels.containsKey(connectionId);
                boolean viaOpts = maybeInstance != null && instanceLabels.containsKey(maybeInstance);
                String targetInstanceId = direct ? connectionId : (viaOpts ? maybeInstance : null);
                if (targetInstanceId != null) {
                  String defId = firstNonBlank(
                      optString(action.get("definitionId")),
                      optString(action.get("definition_id")),
                      optString(action.get("actionId")),
                      optString(action.get("action_id")),
                      optString(action.get("id"))
                  );
                  String id = (rowIndex + 1) + "." + (colIndex + 1);
                  out.add(new UsageRecord(
                      targetInstanceId,
                      pageNum,
                      rowIndex + 1,
                      colIndex + 1,
                      stepIndex + 1,
                      defId,
                      id, // identity
                      id  // display (row.col)
                  ));
                }
              }
            }
          }
        }
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static List<UsageRecord> scanUsageLegacy(Map<String, Object> root, Map<String, String> instanceLabels) {
    List<UsageRecord> out = new ArrayList<>();
    List<String> buckets = Arrays.asList("actions", "release_actions", "rotate_left_actions", "rotate_right_actions");

    for (String bucket : buckets) {
      Object bucketObj = root.get(bucket);
      if (!(bucketObj instanceof Map)) continue;
      Map<String, Object> byPage = (Map<String, Object>) bucketObj;

      for (Map.Entry<String, Object> pe : byPage.entrySet()) {
        int pageNum = parseIntSafe(pe.getKey(), -1);
        Object posContainer = pe.getValue();
        if (!(posContainer instanceof Map)) continue;
        Map<String, Object> byPos = (Map<String, Object>) posContainer;

        for (Map.Entry<String, Object> be : byPos.entrySet()) {
          int posIndex = parseIntSafe(be.getKey(), -1);
          if (posIndex < 1) continue;

          // Value can be either a List of actions, or a Map with sublists (e.g., down/up)
          Object posVal = be.getValue();
          List<List<Object>> actionLists = new ArrayList<>();

          if (posVal instanceof List) {
            actionLists.add((List<Object>) posVal);
          } else if (posVal instanceof Map) {
            Map<String, Object> sub = (Map<String, Object>) posVal;
            for (Object v : sub.values()) {
              if (v instanceof List) actionLists.add((List<Object>) v);
            }
          } else {
            continue;
          }

          int stepBase = 0;
          for (List<Object> list : actionLists) {
            for (int i = 0; i < list.size(); i++) {
              Object obj = list.get(i);
              if (!(obj instanceof Map)) continue;
              Map<String, Object> act = (Map<String, Object>) obj;

              String instanceId = firstNonBlank(
                  optString(act.get("instance")),
                  optString(act.get("connectionId")),
                  optString(act.get("connection_id")),
                  optString(getNested(act, "options", "instance_id")),
                  optString(getNested(act, "options", "instanceId")),
                  optString(getNested(act, "options", "connectionId"))
              );
              if (instanceId == null || instanceId.isBlank()) continue;

              String defId = firstNonBlank(
                  optString(act.get("action")),
                  optString(act.get("definitionId")),
                  optString(act.get("actionId")),
                  optString(act.get("id"))
              );

              int step = stepBase + i + 1; // keep stable 1-based step order within all lists

              String idStr = String.valueOf(posIndex); // legacy shows raw button number
              out.add(new UsageRecord(
                  instanceId,
                  pageNum,
                  -1, -1,
                  step,
                  defId,
                  idStr,  // identity for grouping
                  idStr   // display as raw number
              ));
            }
            stepBase += Math.max(0, list.size());
          }
        }
      }
    }
    return out;
  }

  private static String firstNonBlank(String... vals) {
    if (vals == null) return null;
    for (String v : vals) if (v != null && !v.isBlank()) return v;
    return null;
  }
  private static int parseIntSafe(String s, int fallback) { try { return Integer.parseInt(s); } catch (Exception e) { return fallback; } }
  @SuppressWarnings("unchecked") private static Map<String, Object> asMap(Object o) { return (o instanceof Map) ? (Map<String, Object>) o : null; }
  private static String optString(Object o) { return (o == null) ? null : o.toString(); }

  @SuppressWarnings("unchecked")
  private static Object getNested(Map<String, Object> map, String key1, String key2) {
    if (map == null) return null;
    Object o = map.get(key1);
    if (!(o instanceof Map)) return null;
    return ((Map<String, Object>) o).get(key2);
  }

  public static void main(String[] args) { launch(args); }
}