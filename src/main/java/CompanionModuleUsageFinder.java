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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.prefs.Preferences;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class CompanionModuleUsageFinder extends Application {
  // YAML/JSON keys we use
  private static final String KEY_PAGES = "pages";
  private static final String KEY_CONTROLS = "controls";
  private static final String KEY_STEPS = "steps";
  private static final String KEY_ACTION_SETS = "action_sets";
  private static final String KEY_INSTANCES = "instances";
  private static final String KEY_TYPE = "type";
  private static final String KEY_CONNECTION_ID = "connectionId";
  private static final String KEY_OPTIONS = "options";
  private static final String KEY_INSTANCE_ID = "instance_id";

  // UI rows
  public static class LineRow {
    final SimpleStringProperty page   = new SimpleStringProperty();   // "9" on header row, "" otherwise
    final SimpleStringProperty button = new SimpleStringProperty();   // "" on header row, "1.2 (step 1)" on button rows
    final SimpleStringProperty action = new SimpleStringProperty();   // "" on header row, "defA, defB" on button rows
    LineRow(String page, String button, String action) { this.page.set(page); this.button.set(button); this.action.set(action); }
    public String getPage()   { return page.get(); }
    public String getButton() { return button.get(); }
    public String getAction() { return action.get(); }
  }

  private static class UsageRecord {
    final String moduleInstanceId;
    final int page, row, col, step; // 1-based
    final String actionDefId;
    UsageRecord(String moduleInstanceId, int page, int row, int col, int step, String actionDefId) {
      this.moduleInstanceId = moduleInstanceId; this.page = page; this.row = row; this.col = col; this.step = step; this.actionDefId = actionDefId;
    }
    String buttonKey() { return row + "." + col; }
  }

  // UI state
  private final ComboBox<String> moduleDropdown = new ComboBox<>();
  private final TableView<LineRow> table = new TableView<>();
  private final Label status = new Label("Drop a .companionconfig (YAML/JSON/GZ/ZIP) or use File > Open.");
  private final Button copyBtn = new Button("Copy Selected");

  // Theme (persist light/dark)
  private enum Theme { LIGHT, DARK }
  private static final String PREF_KEY_THEME = "theme";
  private final Preferences prefs = Preferences.userNodeForPackage(CompanionModuleUsageFinder.class);
  private Theme currentTheme = Theme.LIGHT;
  private Scene scene;

  // Parsed state
  private Map<String, Map<String, Object>> instances;
  private Map<String, String> instanceLabels;
  private List<UsageRecord> allUsage = new ArrayList<>();

  @Override
  public void start(Stage stage) {
    stage.setTitle("Modulus for Companion");

    try {
      Image icon = new Image(Objects.requireNonNull(
          CompanionModuleUsageFinder.class.getResourceAsStream("/icons/app.png")));
      stage.getIcons().add(icon);
    } catch (Exception ignored) {}

    // Menus
    MenuBar menuBar = new MenuBar();
    Menu fileMenu = new Menu("File");
    MenuItem openItem = new MenuItem("Open Config…");
    openItem.setOnAction(e -> openConfigDialog(stage));
    MenuItem copyItem = new MenuItem("Copy Selected");
    copyItem.setOnAction(e -> copySelectedRows());
    fileMenu.getItems().addAll(openItem, new SeparatorMenuItem(), copyItem);
    menuBar.getMenus().add(fileMenu);

    // Theme toggle ☀︎ / ☾
    ToggleButton lightBtn = new ToggleButton("☀︎");
    ToggleButton darkBtn  = new ToggleButton("☾");
    ToggleGroup tg = new ToggleGroup();
    lightBtn.setToggleGroup(tg); darkBtn.setToggleGroup(tg);
    String saved = prefs.get(PREF_KEY_THEME, "light");
    currentTheme = "dark".equalsIgnoreCase(saved) ? Theme.DARK : Theme.LIGHT;
    if (currentTheme == Theme.DARK) darkBtn.setSelected(true); else lightBtn.setSelected(true);
    lightBtn.setOnAction(e -> { currentTheme = Theme.LIGHT; applyTheme(); prefs.put(PREF_KEY_THEME, "light"); });
    darkBtn.setOnAction(e  -> { currentTheme = Theme.DARK;  applyTheme(); prefs.put(PREF_KEY_THEME, "dark");  });
    HBox themeBox = new HBox(lightBtn, darkBtn);

    // Top controls
    moduleDropdown.setPromptText("Select a module…");
    moduleDropdown.setDisable(true);
    moduleDropdown.valueProperty().addListener((obs, o, n) -> refreshTableForModule(n));

    copyBtn.setDisable(true);
    copyBtn.setOnAction(e -> copySelectedRows());

    Region spacer = new Region();
    HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
    HBox top = new HBox(10, new Label("Module:"), moduleDropdown, copyBtn, spacer, themeBox);
    top.setPadding(new Insets(10));

    // Table
    TableColumn<LineRow, String> colPage = new TableColumn<>("Page");
    colPage.setCellValueFactory(c -> c.getValue().page);
    colPage.setMinWidth(80); colPage.setMaxWidth(120);

    TableColumn<LineRow, String> colButton = new TableColumn<>("Button (row.col with steps)");
    colButton.setCellValueFactory(c -> c.getValue().button);

    TableColumn<LineRow, String> colAction = new TableColumn<>("Action");
    colAction.setCellValueFactory(c -> c.getValue().action);
    colAction.setMinWidth(160);

    table.getColumns().setAll(colPage, colButton, colAction);
    table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

    // Copy via Cmd/Ctrl+C
    final KeyCombination COPY_KC = new KeyCodeCombination(
        KeyCode.C,
        System.getProperty("os.name").toLowerCase().contains("mac")
            ? KeyCombination.META_DOWN : KeyCombination.CONTROL_DOWN
    );
    table.setOnKeyPressed(e -> { if (COPY_KC.match(e)) copySelectedRows(); });

    // Layout
    BorderPane root = new BorderPane();
    root.setTop(new VBox(menuBar, top));
    root.setCenter(table);
    BorderPane.setMargin(table, new Insets(10));
    status.setPadding(new Insets(8));
    root.setBottom(status);

    scene = new Scene(root, 1000, 560);

    // Drag & drop
    scene.setOnDragOver(e -> {
      Dragboard db = e.getDragboard();
      if (db.hasFiles()) e.acceptTransferModes(TransferMode.COPY);
      e.consume();
    });
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
        .map(r -> (r.getPage() == null || r.getPage().isBlank())
            ? "  " + r.getButton()
            : r.getPage())
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
    if (db.hasFiles()) {
      File f = db.getFiles().get(0);
      loadConfig(f);
      success = true;
    }
    event.setDropCompleted(success);
    event.consume();
  }

  // ========= Robust loader: handles YAML, JSON, GZIP(JSON/YAML), ZIP(JSON/YAML) irrespective of extension =========
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
        String label = (labelObj == null) ? id : labelObj.toString();
        if (id != null) instanceLabels.put(id, label);
      }

      this.allUsage = scanUsage(root, instanceLabels);

      // Populate dropdown with modules that are actually used
      Set<String> usedInstanceIds = new TreeSet<>();
      for (UsageRecord u : allUsage) usedInstanceIds.add(u.moduleInstanceId);

      List<String> dropdownLabels = usedInstanceIds.stream()
          .map(id -> instanceLabels.getOrDefault(id, id) + " (" + id + ")")
          .sorted(String.CASE_INSENSITIVE_ORDER)
          .collect(Collectors.toList());

      moduleDropdown.setItems(FXCollections.observableArrayList(dropdownLabels));
      moduleDropdown.setDisable(dropdownLabels.isEmpty());
      copyBtn.setDisable(true);
      table.getItems().clear();

      if (!dropdownLabels.isEmpty()) {
        // Auto-select first module and populate
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

  /** Read a config file as YAML or JSON, handling GZIP and ZIP by magic bytes (works even if name ends with .companionconfig). */
  @SuppressWarnings("unchecked")
  private Map<String,Object> readConfigAuto(File file) throws Exception {
    byte[] all = readAll(file);

    // GZIP magic 1F 8B
    if (all.length >= 2 && (all[0] & 0xFF) == 0x1F && (all[1] & 0xFF) == 0x8B) {
      try (GZIPInputStream gin = new GZIPInputStream(new ByteArrayInputStream(all))) {
        byte[] unz = gin.readAllBytes();
        return parseBuffer(unz, "inner.gz");
      }
    }

    // ZIP magic "PK"
    if (all.length >= 2 && all[0] == 0x50 && all[1] == 0x4B) {
      try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(all))) {
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
          if (e.isDirectory()) continue;
          byte[] bytes = zin.readAllBytes();
          Map<String,Object> parsed = tryParseBoth(bytes, e.getName().toLowerCase(Locale.ROOT));
          if (parsed != null) return parsed; // first decodable entry
        }
      }
      return null;
    }

    // Plain file; decide JSON vs YAML by content
    return parseBuffer(all, file.getName().toLowerCase(Locale.ROOT));
  }

  /** Decide JSON vs YAML by first non-space char (or extension), and parse. Falls back to the other on failure. */
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

        // Fallback to JSON if YAML root wasn't a map
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
    copyBtn.setDisable(true);
    if (dropdownValue == null || dropdownValue.isBlank()) return;

    // Extract instanceId from "Label (id)"
    String instanceId = dropdownValue.replaceAll(".*\\((.*)\\)$", "$1");

    // Aggregate: page -> button -> (steps, actions)
    class Agg {
      final Set<Integer> steps = new TreeSet<>();
      final LinkedHashSet<String> actions = new LinkedHashSet<>();
    }
    Map<Integer, Map<String, Agg>> byPage = new TreeMap<>();

    for (UsageRecord u : allUsage) {
      if (!u.moduleInstanceId.equals(instanceId)) continue;
      Map<String, Agg> pageMap = byPage.computeIfAbsent(u.page, k -> new TreeMap<>(new ButtonKeyComparator()));
      Agg agg = pageMap.computeIfAbsent(u.buttonKey(), k -> new Agg());
      agg.steps.add(u.step);
      if (u.actionDefId != null && !u.actionDefId.isBlank()) agg.actions.add(u.actionDefId.trim());
    }

    // Build rows (page header row, then button rows)
    ObservableList<LineRow> rows = FXCollections.observableArrayList();
    for (Map.Entry<Integer, Map<String, Agg>> pe : byPage.entrySet()) {
      rows.add(new LineRow(String.valueOf(pe.getKey()), "", ""));
      for (Map.Entry<String, Agg> be : pe.getValue().entrySet()) {
        String btn = be.getKey();
        List<Integer> steps = new ArrayList<>(be.getValue().steps);
        String stepText = steps.size() == 1
            ? "(step " + steps.get(0) + ")"
            : "(steps " + steps.stream().map(Object::toString).collect(Collectors.joining(", ")) + ")";
        String actionText = be.getValue().actions.isEmpty() ? "" : String.join(", ", be.getValue().actions);
        rows.add(new LineRow("", btn + " " + stepText, actionText));
      }
    }

    table.setItems(rows);
    copyBtn.setDisable(rows.isEmpty());
  }

  /** Sorts "row.col" numerically. */
  private static class ButtonKeyComparator implements Comparator<String> {
    @Override public int compare(String a, String b) {
      int[] A = parse(a), B = parse(b);
      if (A[0] != B[0]) return Integer.compare(A[0], B[0]);
      return Integer.compare(A[1], B[1]);
    }
    private int[] parse(String s) {
      try { String[] p = s.split("\\."); return new int[]{ Integer.parseInt(p[0]), Integer.parseInt(p[1]) }; }
      catch (Exception e) { return new int[]{ Integer.MAX_VALUE, Integer.MAX_VALUE }; }
    }
  }

  // ===== Scanning logic (works for both YAML & JSON dumps you provided) =====
  @SuppressWarnings("unchecked")
  private static List<UsageRecord> scanUsage(Map<String, Object> root, Map<String, String> instanceLabels) {
    List<UsageRecord> out = new ArrayList<>();

    Object pagesObj = root.get(KEY_PAGES);
    if (!(pagesObj instanceof Map)) return out;
    Map<String, Object> pages = (Map<String, Object>) pagesObj;

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
                  out.add(new UsageRecord(targetInstanceId, pageNum, rowIndex + 1, colIndex + 1, stepIndex + 1, defId));
                }
              }
            }
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

  public static void main(String[] args) { launch(args); }
}