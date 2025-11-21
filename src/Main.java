import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.text.DateFormatter;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 本地日程提醒工具。
 */
public class Main {
    public static void main(String[] args) {
        boolean showWindow = false;
        for (String arg : args) {
            if ("--gui".equalsIgnoreCase(arg) || "--window".equalsIgnoreCase(arg) || "--swing".equalsIgnoreCase(arg)) {
                showWindow = true;
                break;
            }
        }
        if (Boolean.parseBoolean(System.getenv("SCHEDULER_GUI"))) {
            showWindow = true;
        }
        boolean finalShowWindow = showWindow;
        SwingUtilities.invokeLater(() -> new SchedulerApp(finalShowWindow).maybeShowWindow());
    }

    /**
     * 重复规则。
     */
    enum RepeatRule {
        NONE("不重复"),
        DAILY("每天"),
        WEEKLY("每周");

        private final String label;

        RepeatRule(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * 日程条目。
     */
    static final class ScheduleEntry {
        private final String id;
        private final String title;
        private LocalDate date;
        private LocalTime time;
        private final RepeatRule repeatRule;
        private boolean notified;
        private String musicTitle;
        private String musicUrl;
        private String musicFile;

        ScheduleEntry(String title, LocalDate date, LocalTime time, RepeatRule repeatRule) {
            this(UUID.randomUUID().toString(), title, date, time, repeatRule, "", "", "");
        }

        ScheduleEntry(String id, String title, LocalDate date, LocalTime time, RepeatRule repeatRule) {
            this(id, title, date, time, repeatRule, "", "", "");
        }

        ScheduleEntry(
            String title,
            LocalDate date,
            LocalTime time,
            RepeatRule repeatRule,
            String musicTitle,
            String musicUrl,
            String musicFile
        ) {
            this(UUID.randomUUID().toString(), title, date, time, repeatRule, musicTitle, musicUrl, musicFile);
        }

        ScheduleEntry(
            String id,
            String title,
            LocalDate date,
            LocalTime time,
            RepeatRule repeatRule,
            String musicTitle,
            String musicUrl,
            String musicFile
        ) {
            this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
            this.title = title;
            this.date = date;
            this.time = time;
            this.repeatRule = repeatRule == null ? RepeatRule.NONE : repeatRule;
            this.musicTitle = musicTitle == null ? "" : musicTitle;
            this.musicUrl = musicUrl == null ? "" : musicUrl;
            this.musicFile = musicFile == null ? "" : musicFile;
            this.notified = false;
        }

        String getId() {
            return id;
        }

        String getTitle() {
            return title;
        }

        RepeatRule getRepeatRule() {
            return repeatRule;
        }

        String getMusicTitle() {
            return musicTitle;
        }

        String getMusicUrl() {
            return musicUrl;
        }

        String getMusicFile() {
            return musicFile;
        }

        void setMusicFile(String musicFile) {
            this.musicFile = musicFile == null ? "" : musicFile;
        }

        java.nio.file.Path getMusicFilePath() {
            if (musicFile == null || musicFile.isBlank()) {
                return null;
            }
            return Paths.get(musicFile);
        }

        LocalDateTime getDateTime() {
            return LocalDateTime.of(date, time);
        }

        void alignToFuture(LocalDateTime base) {
            if (repeatRule == RepeatRule.NONE) {
                return;
            }
            while (getDateTime().isBefore(base)) {
                moveToNext();
            }
        }

        void moveToNext() {
            if (repeatRule == RepeatRule.DAILY) {
                date = date.plusDays(1);
            } else if (repeatRule == RepeatRule.WEEKLY) {
                date = date.plusWeeks(1);
            }
            notified = false;
        }

        boolean isNotified() {
            return notified;
        }

        void markNotified() {
            this.notified = true;
        }

        String toJson() {
            return "{\"id\":\"" + id + "\",\"title\":\"" + escape(title) + "\",\"date\":\""
                + date.format(SchedulerApp.DATE_FORMAT) + "\",\"time\":\""
                + time.format(SchedulerApp.TIME_FORMAT) + "\",\"repeat\":\"" + repeatRule.name() + "\","
                + "\"musicTitle\":\"" + escape(musicTitle) + "\",\"musicUrl\":\"" + escape(musicUrl) + "\","
                + "\"musicFile\":\"" + escape(musicFile) + "\"}";
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        @Override
        public String toString() {
            return title + "  " + date.format(SchedulerApp.DATE_FORMAT) + " " + time.format(SchedulerApp.TIME_FORMAT)
                + "  [" + repeatRule + "]";
        }
    }

    /**
     * 主应用。
     */
    static final class SchedulerApp {
        static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
        private static final Path STORAGE = Paths.get("schedule.xml");
        private static final int HTTP_PORT = 18080;
        private static final Path DASHBOARD_HTML = Paths.get("neo_brutalism_dashboard.html");
        private static final Path MUSIC_DIR = Paths.get("music");
        private static final int MUSIC_SEARCH_LIMIT = 6;

        private final JFrame frame = new JFrame("日程提醒");
        private final boolean showWindow;
        private final DefaultListModel<ScheduleEntry> listModel = new DefaultListModel<>();
        private final JList<ScheduleEntry> scheduleList = new JList<>(listModel);
        private final JTextField titleField = new JTextField();
        private final JSpinner dateSpinner = createDateSpinner();
        private final JSpinner timeSpinner = createTimeSpinner();
        private final JComboBox<RepeatRule> repeatCombo = new JComboBox<>(RepeatRule.values());
        private final JLabel statusLabel = new JLabel("就绪");
        private final List<ScheduleEntry> entries = new ArrayList<>();
        private final Object lock = new Object();
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(new DaemonFactory());
        private final MusicService musicService = new MusicService(MUSIC_DIR);
        private HttpServer httpServer;
        private TrayIconWrapper trayIconWrapper;
        private boolean browserOpened = false;

        SchedulerApp(boolean showWindow) {
            this.showWindow = showWindow;
            buildUI();
            setupTray();
            loadFromDisk();
            refreshListModel();
            startHttpServer();
            startReminderLoop();
        }

        void maybeShowWindow() {
            if (showWindow) {
                frame.setVisible(true);
            }
        }

        private void buildUI() {
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.setSize(520, 420);
            frame.setLocationRelativeTo(null);

            JPanel root = new JPanel(new BorderLayout(12, 12));
            root.setBorder(new EmptyBorder(12, 12, 12, 12));
            frame.setContentPane(root);

            JLabel header = new JLabel("添加日程：标题、日期、时间、重复规则");
            header.setFont(header.getFont().deriveFont(Font.BOLD, 16f));
            root.add(header, BorderLayout.NORTH);

            root.add(buildFormPanel(), BorderLayout.WEST);
            root.add(buildListPanel(), BorderLayout.CENTER);
            root.add(buildFooter(), BorderLayout.SOUTH);

            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    if (trayIconWrapper != null && trayIconWrapper.isSupported()) {
                        frame.setVisible(false);
                        trayIconWrapper.showInfo("日程提醒仍在运行，点击托盘图标可恢复窗口。");
                    } else {
                        shutdownAndExit();
                    }
                }
            });
            frame.setVisible(showWindow);
        }

        private JComponent buildFormPanel() {
            JPanel form = new JPanel();
            form.setLayout(new BorderLayout(8, 8));
            form.setBorder(BorderFactory.createTitledBorder("新增日程"));

            JPanel fields = new JPanel();
            fields.setLayout(new javax.swing.BoxLayout(fields, javax.swing.BoxLayout.Y_AXIS));

            fields.add(labeledField("标题", titleField));
            fields.add(labeledField("日期", dateSpinner));
            fields.add(labeledField("时间", timeSpinner));
            fields.add(labeledField("重复", repeatCombo));

            JButton addButton = new JButton("添加");
            addButton.addActionListener(e -> addEntry());

            JButton deleteButton = new JButton("删除选中");
            deleteButton.addActionListener(e -> removeSelected());

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
            actions.add(addButton);
            actions.add(deleteButton);

            form.add(fields, BorderLayout.CENTER);
            form.add(actions, BorderLayout.SOUTH);
            form.setPreferredSize(new Dimension(230, 260));
            return form;
        }

        private JComponent labeledField(String labelText, JComponent field) {
            JPanel panel = new JPanel(new BorderLayout(6, 6));
            JLabel label = new JLabel(labelText, SwingConstants.LEFT);
            panel.add(label, BorderLayout.WEST);
            panel.add(field, BorderLayout.CENTER);
            return panel;
        }

        private JComponent buildListPanel() {
            scheduleList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            scheduleList.setCellRenderer(new EntryRenderer());
            JScrollPane scroll = new JScrollPane(scheduleList);
            scroll.setBorder(BorderFactory.createTitledBorder("日程列表（按时间排序）"));
            return scroll;
        }

        private JComponent buildFooter() {
            JPanel footer = new JPanel(new BorderLayout());
            JButton saveButton = new JButton("立即保存");
            saveButton.addActionListener(e -> saveSafe());
            footer.add(saveButton, BorderLayout.WEST);
            footer.add(statusLabel, BorderLayout.EAST);
            return footer;
        }

        private JSpinner createDateSpinner() {
            Date now = new Date();
            JSpinner spinner = new JSpinner(new javax.swing.SpinnerDateModel(now, null, null, Calendar.DAY_OF_MONTH));
            JSpinner.DateEditor editor = new JSpinner.DateEditor(spinner, "yyyy-MM-dd");
            ((DateFormatter) editor.getTextField().getFormatter()).setAllowsInvalid(false);
            spinner.setEditor(editor);
            return spinner;
        }

        private JSpinner createTimeSpinner() {
            Date time = new Date();
            JSpinner spinner = new JSpinner(new javax.swing.SpinnerDateModel(time, null, null, Calendar.MINUTE));
            JSpinner.DateEditor editor = new JSpinner.DateEditor(spinner, "HH:mm");
            ((DateFormatter) editor.getTextField().getFormatter()).setAllowsInvalid(false);
            spinner.setEditor(editor);
            return spinner;
        }

        private void addEntry() {
            String title = titleField.getText().trim();
            if (title.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "请输入标题", "提醒", JOptionPane.WARNING_MESSAGE);
                return;
            }
            LocalDate date = toLocalDate((Date) dateSpinner.getValue());
            LocalTime time = toLocalTime((Date) timeSpinner.getValue());
            RepeatRule repeatRule = (RepeatRule) repeatCombo.getSelectedItem();

            ScheduleEntry entry = new ScheduleEntry(title, date, time, repeatRule);
            entry.alignToFuture(LocalDateTime.now());
            synchronized (lock) {
                entries.add(entry);
            }
            refreshListModel();
            saveSafe();
            statusLabel.setText("已添加：" + title);
        }

        private void removeSelected() {
            ScheduleEntry selected = scheduleList.getSelectedValue();
            if (selected == null) {
                return;
            }
            synchronized (lock) {
                entries.remove(selected);
            }
            refreshListModel();
            saveSafe();
            statusLabel.setText("已删除：" + selected.getTitle());
        }

        private void refreshListModel() {
            SwingUtilities.invokeLater(() -> {
                listModel.clear();
                synchronized (lock) {
                    entries.sort(Comparator.comparing(ScheduleEntry::getDateTime));
                    for (ScheduleEntry entry : entries) {
                        listModel.addElement(entry);
                    }
                }
            });
        }

        private void loadFromDisk() {
            if (!Files.exists(STORAGE)) {
                return;
            }
            try {
                List<ScheduleEntry> loaded = readXml();
                LocalDateTime now = LocalDateTime.now();
                boolean downloaded = false;
                synchronized (lock) {
                    entries.clear();
                    for (ScheduleEntry entry : loaded) {
                        entry.alignToFuture(now);
                        entries.add(entry);
                    }
                }
                for (ScheduleEntry entry : loaded) {
                    if (maybeDownloadMusic(entry)) {
                        downloaded = true;
                    }
                }
                if (downloaded) {
                    saveSafe();
                }
                updateStatus("已加载 " + loaded.size() + " 条日程");
            } catch (Exception ex) {
                updateStatus("加载失败，已忽略文件");
            }
        }

        private void saveSafe() {
            try {
                writeXml();
                updateStatus("已保存");
            } catch (Exception ex) {
                updateStatus("保存失败：" + ex.getMessage());
            }
        }

        private void startHttpServer() {
            try {
                httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
                httpServer.createContext("/", this::handleIndex);
                httpServer.createContext("/api/schedules", this::handleSchedules);
                httpServer.createContext("/api/music/search", this::handleMusicSearch);
                httpServer.createContext("/api/music/comments", this::handleMusicComments);
                httpServer.createContext("/api/music/lyric", this::handleMusicLyric);
                httpServer.setExecutor(Executors.newCachedThreadPool(new DaemonFactory()));
                httpServer.start();
                updateStatus("HTTP 服务已启用: http://localhost:" + HTTP_PORT);
                openBrowserIfSupported();
            } catch (IOException ex) {
                updateStatus("HTTP 启动失败：" + ex.getMessage());
            }
        }

        private void openBrowserIfSupported() {
            if (browserOpened) {
                return;
            }
            if (!Desktop.isDesktopSupported()) {
                return;
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                return;
            }
            try {
                desktop.browse(new URI("http://localhost:" + HTTP_PORT));
                browserOpened = true;
            } catch (Exception ignored) {
                // 忽略浏览器启动失败，保留后台服务
            }
        }

        private void handleIndex(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }
            String html;
            if (Files.exists(DASHBOARD_HTML)) {
                html = Files.readString(DASHBOARD_HTML, StandardCharsets.UTF_8);
            } else {
                html = "<html><body><h2>找不到 neo_brutalism_dashboard.html </h2>"
                    + "<p>请确保文件位于当前工作目录。</p></body></html>";
            }
            sendResponse(exchange, 200, html, "text/html; charset=utf-8");
        }

        private void handleSchedules(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            switch (method) {
                case "GET" -> handleSchedulesGet(exchange);
                case "POST" -> handleSchedulesPost(exchange);
                case "DELETE" -> handleSchedulesDelete(exchange);
                default -> sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
            }
        }

        private void handleMusicSearch(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }
            String query = exchange.getRequestURI().getQuery();
            String keyword = queryValue(query, "q");
            if (keyword == null || keyword.isBlank()) {
                sendResponse(exchange, 400, "{\"error\":\"缺少 q\"}", "application/json");
                return;
            }
            keyword = URLDecoder.decode(keyword, StandardCharsets.UTF_8);
            List<MusicService.MusicMatch> matches = musicService.search(keyword, MUSIC_SEARCH_LIMIT);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < matches.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(matches.get(i).toJson());
            }
            sb.append(']');
            sendResponse(exchange, 200, sb.toString(), "application/json; charset=utf-8");
        }

        private void handleMusicComments(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }
            String query = exchange.getRequestURI().getQuery();
            String id = queryValue(query, "id");
            if (id == null || id.isBlank()) {
                sendResponse(exchange, 400, "{\"error\":\"缺少 id\"}", "application/json");
                return;
            }
            List<MusicService.MusicComment> comments = musicService.hotComments(id, 6);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < comments.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(comments.get(i).toJson());
            }
            sb.append(']');
            sendResponse(exchange, 200, sb.toString(), "application/json; charset=utf-8");
        }

        private void handleMusicLyric(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }
            String query = exchange.getRequestURI().getQuery();
            String id = queryValue(query, "id");
            if (id == null || id.isBlank()) {
                sendResponse(exchange, 400, "{\"error\":\"缺少 id\"}", "application/json");
                return;
            }
            String lyric = musicService.lyric(id);
            String body = "{\"lyric\":\"" + lyric.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
            sendResponse(exchange, 200, body, "application/json; charset=utf-8");
        }

        private void handleSchedulesGet(HttpExchange exchange) throws IOException {
            StringBuilder sb = new StringBuilder("[");
            synchronized (lock) {
                for (int i = 0; i < entries.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(entries.get(i).toJson());
                }
            }
            sb.append(']');
            sendResponse(exchange, 200, sb.toString(), "application/json; charset=utf-8");
        }

        private void handleSchedulesPost(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> payload = parseJsonMap(body);
            String title = str(payload.get("title"));
            String dateStr = str(payload.get("date"));
            String timeStr = str(payload.get("time"));
            String repeatStr = str(payload.get("repeat"));
            String musicTitle = str(payload.get("musicTitle"));
            String musicUrl = str(payload.get("musicUrl"));
            if (title.isBlank() || dateStr.isBlank() || timeStr.isBlank()) {
                sendResponse(exchange, 400, "{\"error\":\"title/date/time 不能为空\"}", "application/json");
                return;
            }
            RepeatRule repeatRule = RepeatRule.NONE;
            try {
                repeatRule = repeatStr.isBlank() ? RepeatRule.NONE : RepeatRule.valueOf(repeatStr);
            } catch (IllegalArgumentException ignored) {
                repeatRule = RepeatRule.NONE;
            }
            ScheduleEntry entry = new ScheduleEntry(title, LocalDate.parse(dateStr, DATE_FORMAT), LocalTime.parse(timeStr, TIME_FORMAT), repeatRule, musicTitle, musicUrl, "");
            entry.alignToFuture(LocalDateTime.now());
            maybeDownloadMusic(entry);
            synchronized (lock) {
                entries.add(entry);
            }
            refreshListModel();
            saveSafe();
            sendResponse(exchange, 201, entry.toJson(), "application/json; charset=utf-8");
        }

        private void handleSchedulesDelete(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            String id = queryValue(query, "id");
            if (id == null || id.isBlank()) {
                sendResponse(exchange, 400, "{\"error\":\"缺少 id\"}", "application/json");
                return;
            }
            boolean removed;
            synchronized (lock) {
                removed = entries.removeIf(e -> id.equals(e.getId()));
            }
            if (removed) {
                refreshListModel();
                saveSafe();
                sendResponse(exchange, 200, "{\"status\":\"deleted\"}", "application/json");
            } else {
                sendResponse(exchange, 404, "{\"error\":\"未找到\"}", "application/json");
            }
        }
        private Map<String, Object> parseJsonMap(String json) {
            Object obj = MiniJson.parse(json);
            if (obj instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) map;
                return m;
            }
            return Map.of();
        }

        private String str(Object o) {
            return o == null ? "" : o.toString();
        }

        private String queryValue(String query, String key) {
            if (query == null || query.isEmpty()) {
                return null;
            }
            for (String part : query.split("&")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2 && kv[0].equals(key)) {
                    return kv[1];
                }
            }
            return null;
        }

        private boolean maybeDownloadMusic(ScheduleEntry entry) {
            if (entry.getMusicUrl() == null || entry.getMusicUrl().isBlank()) {
                return false;
            }
            java.nio.file.Path existing = entry.getMusicFilePath();
            if (existing != null && Files.exists(existing)) {
                return false;
            }
            String saved = musicService.downloadMusic(entry.getMusicUrl(), entry.getId());
            if (!saved.isBlank()) {
                synchronized (lock) {
                    entry.setMusicFile(saved);
                }
                return true;
            }
            return false;
        }

        private void playAudioForEntry(ScheduleEntry entry) {
            boolean played = false;
            Path file = entry.getMusicFilePath();
            if (file != null && Files.exists(file)) {
                try {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().open(file.toFile());
                        played = true;
                    }
                } catch (Exception ignored) {
                }
            } else if (entry.getMusicUrl() != null && !entry.getMusicUrl().isBlank()) {
                try {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().browse(URI.create(entry.getMusicUrl()));
                        played = true;
                    }
                } catch (Exception ignored) {
                }
            }
            if (!played) {
                Toolkit.getDefaultToolkit().beep();
            }
        }

        private void sendResponse(HttpExchange exchange, int status, String body, String contentType) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private void startReminderLoop() {
            executor.scheduleAtFixedRate(this::checkReminders, 0, 1, TimeUnit.MINUTES);
        }

        private void checkReminders() {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime threshold = now.plusMinutes(1);
            boolean changed = false;
            List<ScheduleEntry> dueEntries = new ArrayList<>();
            synchronized (lock) {
                for (ScheduleEntry entry : entries) {
                    entry.alignToFuture(now);
                    LocalDateTime time = entry.getDateTime();
                    boolean dueNow = !entry.isNotified() && !time.isAfter(threshold) && !time.isBefore(now);
                    if (dueNow) {
                        dueEntries.add(entry);
                        entry.markNotified();
                        if (entry.getRepeatRule() != RepeatRule.NONE) {
                            entry.moveToNext();
                            changed = true;
                        }
                    }
                }
            }
            if (changed) {
                refreshListModel();
                saveSafe();
            }
            for (ScheduleEntry entry : dueEntries) {
                showReminder(entry);
            }
        }

        private void showReminder(ScheduleEntry entry) {
            playAudioForEntry(entry);
            if (trayIconWrapper != null && trayIconWrapper.isSupported()) {
                trayIconWrapper.showReminder(entry);
            } else {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    frame,
                    entry.getTitle(),
                    "日程提醒",
                    JOptionPane.INFORMATION_MESSAGE
                ));
            }
        }

        private void setupTray() {
            trayIconWrapper = new TrayIconWrapper();
            if (!trayIconWrapper.isSupported()) {
                updateStatus("系统托盘不可用，本地弹窗提示");
                return;
            }
            trayIconWrapper.install(this::restoreWindow, this::shutdownAndExit);
        }

        private void restoreWindow() {
            SwingUtilities.invokeLater(() -> {
                frame.setVisible(true);
                frame.setExtendedState(JFrame.NORMAL);
                frame.toFront();
            });
        }

        private void shutdownAndExit() {
            saveSafe();
            executor.shutdownNow();
            if (httpServer != null) {
                httpServer.stop(0);
            }
            if (trayIconWrapper != null) {
                trayIconWrapper.remove();
            }
            System.exit(0);
        }

        private void updateStatus(String message) {
            if (statusLabel != null) {
                statusLabel.setText(message);
            } else {
                System.out.println(message);
            }
        }

        private LocalDate toLocalDate(Date date) {
            return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }

        private LocalTime toLocalTime(Date date) {
            return date.toInstant().atZone(ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0);
        }

        private List<ScheduleEntry> readXml() throws Exception {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            try (FileInputStream in = new FileInputStream(STORAGE.toFile())) {
                Document doc = builder.parse(in);
                NodeList nodes = doc.getElementsByTagName("entry");
                List<ScheduleEntry> list = new ArrayList<>();
                for (int i = 0; i < nodes.getLength(); i++) {
                    Element node = (Element) nodes.item(i);
                    String id = text(node, "id");
                    String title = text(node, "title");
                    LocalDate date = LocalDate.parse(text(node, "date"), DATE_FORMAT);
                    LocalTime time = LocalTime.parse(text(node, "time"), TIME_FORMAT);
                    RepeatRule repeat = RepeatRule.valueOf(text(node, "repeat"));
                    String musicTitle = text(node, "musicTitle");
                    String musicUrl = text(node, "musicUrl");
                    String musicFile = text(node, "musicFile");
                    ScheduleEntry entry = new ScheduleEntry(id, title, date, time, repeat, musicTitle, musicUrl, musicFile);
                    list.add(entry);
                }
                return list;
            }
        }

        private void writeXml() throws Exception {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();
            Element root = doc.createElement("schedules");
            doc.appendChild(root);

            synchronized (lock) {
                for (ScheduleEntry entry : entries) {
                    Element node = doc.createElement("entry");
                    appendNode(doc, node, "id", entry.getId());
                    appendNode(doc, node, "title", entry.getTitle());
                    appendNode(doc, node, "date", entry.getDateTime().toLocalDate().format(DATE_FORMAT));
                    appendNode(doc, node, "time", entry.getDateTime().toLocalTime().format(TIME_FORMAT));
                    appendNode(doc, node, "repeat", entry.getRepeatRule().name());
                    appendNode(doc, node, "musicTitle", entry.getMusicTitle());
                    appendNode(doc, node, "musicUrl", entry.getMusicUrl());
                    appendNode(doc, node, "musicFile", entry.getMusicFile());
                    root.appendChild(node);
                }
            }

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            try (FileOutputStream out = new FileOutputStream(STORAGE.toFile())) {
                transformer.transform(new DOMSource(doc), new StreamResult(out));
            }
        }

        private void appendNode(Document doc, Element parent, String name, String value) {
            Element child = doc.createElement(name);
            child.appendChild(doc.createTextNode(value));
            parent.appendChild(child);
        }

        private String text(Element element, String tag) {
            NodeList nodes = element.getElementsByTagName(tag);
            return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
        }

        private static final class EntryRenderer extends DefaultListCellRenderer {
            @Override
            public java.awt.Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus
            ) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof ScheduleEntry entry) {
                    label.setText(entry.toString());
                }
                return label;
            }
        }

        private static final class DaemonFactory implements ThreadFactory {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "schedule-reminder");
                t.setDaemon(true);
                return t;
            }
        }

        private static final class TrayIconWrapper {
            private java.awt.TrayIcon trayIcon;

            boolean isSupported() {
                return java.awt.SystemTray.isSupported();
            }

            void install(Runnable onShow, Runnable onExit) {
                if (!isSupported()) {
                    return;
                }
                java.awt.SystemTray tray = java.awt.SystemTray.getSystemTray();
                trayIcon = new java.awt.TrayIcon(buildImage(), "日程提醒");
                trayIcon.setImageAutoSize(true);
                java.awt.PopupMenu menu = new java.awt.PopupMenu();
                java.awt.MenuItem showItem = new java.awt.MenuItem("显示窗口");
                showItem.addActionListener(e -> onShow.run());
                java.awt.MenuItem exitItem = new java.awt.MenuItem("退出并保存");
                exitItem.addActionListener(e -> onExit.run());
                menu.add(showItem);
                menu.add(exitItem);
                trayIcon.setPopupMenu(menu);
                trayIcon.addActionListener(e -> onShow.run());
                try {
                    tray.add(trayIcon);
                } catch (Exception ex) {
                    trayIcon = null;
                }
            }

            void showReminder(ScheduleEntry entry) {
                if (trayIcon != null) {
                    trayIcon.displayMessage("日程提醒", entry.getTitle(), java.awt.TrayIcon.MessageType.INFO);
                }
            }

            void showInfo(String message) {
                if (trayIcon != null) {
                    trayIcon.displayMessage("提示", message, java.awt.TrayIcon.MessageType.NONE);
                }
            }

            void remove() {
                if (trayIcon != null) {
                    java.awt.SystemTray.getSystemTray().remove(trayIcon);
                }
            }

            private Image buildImage() {
                int size = 16;
                BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                g.setColor(new Color(0x2196F3));
                g.fillOval(0, 0, size - 1, size - 1);
                g.setColor(Color.WHITE);
                g.drawLine(4, 8, 7, 11);
                g.drawLine(7, 11, 12, 5);
                g.dispose();
                return Toolkit.getDefaultToolkit().createImage(image.getSource());
            }
        }
    }
}
