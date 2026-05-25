import javafx.application.Application;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Year;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class MainApp extends Application {
    private static final Logger LOG = LogManager.getLogger(MainApp.class);
    private static final String APP_NAME = "UKSH Stundenzettel Generator";
    private static final String APP_VERSION = loadAppVersion();

    @Override
    public void start(Stage stage) {
        TextField xlsxFilePathField = new TextField();
        xlsxFilePathField.setEditable(false);
        xlsxFilePathField.setFocusTraversable(false);
        xlsxFilePathField.setStyle("-fx-background-radius: 3 0 0 3;");
        HBox.setHgrow(xlsxFilePathField, Priority.ALWAYS);

        Button chooseXlsxFileButton = new Button("Datei auswählen");
        chooseXlsxFileButton.setStyle("-fx-background-radius: 0 3 3 0;");
        chooseXlsxFileButton.setOnAction(_ -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel-Dateien", "*.xlsx"));
            File xlsxFile = fileChooser.showOpenDialog(stage);
            if (xlsxFile != null) {
                xlsxFilePathField.setText(xlsxFile.getAbsolutePath());
            }
        });

        HBox fileBox = new HBox(xlsxFilePathField, chooseXlsxFileButton);
        fileBox.setSpacing(0);
        fileBox.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> monthComboBox = new ComboBox<>();
        monthComboBox.getItems().addAll("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember");
        monthComboBox.getSelectionModel().select(LocalDate.now().getMonthValue() - 1);
        monthComboBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(monthComboBox, Priority.ALWAYS);

        Spinner<Integer> yearSpinner = new Spinner<>();
        yearSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, Integer.MAX_VALUE, Year.now().getValue()));
        yearSpinner.setEditable(true);
        yearSpinner.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(yearSpinner, Priority.ALWAYS);

        Label yearLabel = new Label("Jahr");
        yearLabel.setMinWidth(Label.USE_PREF_SIZE);

        Label monthLabel = new Label("Monat");
        monthLabel.setMinWidth(Label.USE_PREF_SIZE);

        VBox yearBox = new VBox(yearLabel, yearSpinner);
        yearBox.setSpacing(4);
        yearBox.setMaxWidth(Double.MAX_VALUE);

        VBox monthBox = new VBox(monthLabel, monthComboBox);
        monthBox.setSpacing(4);
        monthBox.setMaxWidth(Double.MAX_VALUE);

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);

        GridPane dateBox = new GridPane();
        dateBox.getColumnConstraints().addAll(col1, col2);
        dateBox.setHgap(32);
        dateBox.setMaxWidth(Double.MAX_VALUE);
        dateBox.add(yearBox, 0, 0);
        dateBox.add(monthBox, 1, 0);
        GridPane.setFillWidth(yearBox, true);
        GridPane.setFillWidth(monthBox, true);

        BooleanProperty taskRunning = new SimpleBooleanProperty(false);

        ProgressIndicator progressIndicator = new ProgressIndicator();
        progressIndicator.setPrefSize(24, 24);
        progressIndicator.visibleProperty().bind(taskRunning);
        progressIndicator.managedProperty().bind(taskRunning);

        Button genPdfButton = new Button("PDF erzeugen");
        genPdfButton.disableProperty().bind(
                xlsxFilePathField.textProperty().isEmpty()
                        .or(monthComboBox.valueProperty().isNull())
                        .or(yearSpinner.valueProperty().isNull())
                        .or(taskRunning)
        );
        genPdfButton.setOnAction(_ -> {
            YearMonth yearMonth = YearMonth.of(yearSpinner.getValue(), monthComboBox.getSelectionModel().getSelectedIndex() + 1);

            FileChooser saveDialog = new FileChooser();
            saveDialog.setInitialFileName("stundenzettel_" + yearMonth + ".pdf");
            saveDialog.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF-Dateien", "*.pdf"));
            File outputFile = saveDialog.showSaveDialog(stage);
            if (outputFile == null) return;

            File xlsxFile = new File(xlsxFilePathField.getText());
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    generatePdf(xlsxFile, yearMonth, outputFile);
                    return null;
                }
            };
            task.setOnSucceeded(_ -> taskRunning.set(false));
            task.setOnFailed(_ -> {
                taskRunning.set(false);
                Throwable ex = task.getException();
                new Alert(Alert.AlertType.ERROR, "Fehler bei der PDF-Erzeugung: " + ex.getMessage()).showAndWait();
            });
            taskRunning.set(true);
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            thread.start();
        });

        String iconButtonStyle =
                "-fx-background-color: transparent;"
                        + " -fx-padding: 4;"
                        + " -fx-cursor: hand;";

        Button aboutButton = new Button();
        aboutButton.setGraphic(SvgIconLoader.load("/icons/info.svg"));
        aboutButton.setStyle(iconButtonStyle);
        aboutButton.setFocusTraversable(false);
        aboutButton.setTooltip(new Tooltip("Informationen über dieses Programm anzeigen"));
        aboutButton.setOnAction(_ -> showAboutWindow(stage));

        Button logButton = new Button();
        logButton.setGraphic(SvgIconLoader.load("/icons/scroll-text.svg"));
        logButton.setStyle(iconButtonStyle);
        logButton.setFocusTraversable(false);
        logButton.setTooltip(new Tooltip("Log anzeigen"));
        logButton.setOnAction(_ -> showLogWindow(stage));

        Region actionSpacer = new Region();
        HBox.setHgrow(actionSpacer, Priority.ALWAYS);

        HBox actionBox = new HBox(8, aboutButton, logButton, actionSpacer, progressIndicator, genPdfButton);
        actionBox.setAlignment(Pos.CENTER);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        VBox root = new VBox(fileBox, dateBox, spacer, actionBox);
        root.setSpacing(16);
        root.setPadding(new Insets(16));
        root.setAlignment(Pos.TOP_RIGHT);

        Scene scene = new Scene(root, 500, 300);
        stage.setTitle(APP_NAME);
        stage.setScene(scene);
        stage.show();

        LOG.info("{} {} gestartet", APP_NAME, APP_VERSION);
    }

    private static void showLogWindow(Stage owner) {
        ObservableList<String> entries = FxLogAppender.entries();
        ListView<String> listView = new ListView<>(entries);
        listView.setStyle("-fx-font-family: monospaced;");

        ListChangeListener<String> listener = c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    listView.scrollTo(entries.size() - 1);
                }
            }
        };
        entries.addListener(listener);

        Stage logStage = new Stage();
        logStage.initOwner(owner);
        logStage.setTitle("Log");
        logStage.setScene(new Scene(listView, 700, 400));
        logStage.setOnHidden(_ -> entries.removeListener(listener));
        logStage.show();
        if (!entries.isEmpty()) listView.scrollTo(entries.size() - 1);
    }

    private static void showAboutWindow(Stage owner) {
        Label nameLabel = new Label(APP_NAME);
        nameLabel.setStyle("-fx-font-size: 16; -fx-font-weight: bold;");

        Label versionLabel = new Label("Version " + APP_VERSION);

        VBox content = new VBox(8, nameLabel, versionLabel);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(24));

        Stage aboutStage = new Stage();
        aboutStage.initOwner(owner);
        aboutStage.initModality(Modality.WINDOW_MODAL);
        aboutStage.setTitle("Über");
        aboutStage.setResizable(false);
        aboutStage.setScene(new Scene(content));
        aboutStage.showAndWait();
    }

    private static String loadAppVersion() {
        Properties props = new Properties();
        try (InputStream in = MainApp.class.getResourceAsStream("/version.properties")) {
            if (in != null) props.load(in);
        } catch (IOException e) {
            LOG.warn("version.properties konnte nicht gelesen werden", e);
        }
        return props.getProperty("version", "unbekannt");
    }

    private static void generatePdf(File xlsxFile, YearMonth yearMonth, File outputFile) throws Exception {
        Employee employee;
        List<TimeEntry> segments;
        try (ExcelReader reader = new ExcelReader(xlsxFile)) {
            employee = reader.readEmployeeDetails();
            segments = reader.readEntries(yearMonth);
        }
        List<TimeEntry> days = mergeByDay(segments);
        LOG.debug("{} Tageseinträge aus {} Einzeleinträgen für {}", days.size(), segments.size(), yearMonth);
        Duration totalSum = days.stream().map(TimeEntry::total).reduce(Duration.ZERO, Duration::plus);
        LOG.debug("Summe: {} h", PdfGenerator.formatDecimalHours(totalSum));
        PdfGenerator.writePdf(outputFile, yearMonth, employee, days);
        LOG.info("PDF erzeugt: {}", outputFile);
    }

    private static List<TimeEntry> mergeByDay(List<TimeEntry> segments) {
        Map<LocalDate, List<TimeEntry>> byDate = segments.stream()
                .collect(Collectors.groupingBy(TimeEntry::date, TreeMap::new, Collectors.toList()));
        List<TimeEntry> days = new ArrayList<>(byDate.size());
        for (var group : byDate.values()) {
            LocalTime dayStart = group.stream().map(TimeEntry::start).min(Comparator.naturalOrder()).orElseThrow();
            LocalTime dayEnd = group.stream().map(TimeEntry::end).max(Comparator.naturalOrder()).orElseThrow();
            Duration worked = group.stream().map(TimeEntry::total).reduce(Duration.ZERO, Duration::plus);
            Duration breakDur = Duration.between(dayStart, dayEnd).minus(worked);
            if (breakDur.isNegative()) {
                throw new IllegalStateException("Überlappende Einträge am " + group.getFirst().date());
            }
            String remark = group.stream()
                    .map(TimeEntry::remark)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.joining(", "));
            days.add(new TimeEntry(group.getFirst().date(), dayStart, dayEnd, breakDur, worked, remark));
        }
        return days;
    }

    @SuppressWarnings("unused")
    void main(String[] args) {
        launch(args);
    }
}
