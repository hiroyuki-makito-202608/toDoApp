import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToDoApp {
    private static final String DB_URL = "jdbc:sqlite:todo.db";

    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        initializeDatabase();
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") && exchange.getRequestMethod().equals("GET")) {
                String message = "Hello, Server!";
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                String accept = exchange.getRequestHeaders().getFirst("Accept");
                if (accept != null && accept.contains("text/html")) {
                    message = page();
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                }
                byte[] body = message.getBytes("UTF-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().close();
                return;
            }
            try {
                if (path.equals("/style.css") && exchange.getRequestMethod().equals("GET")) {
                    byte[] css = Files.readAllBytes(Path.of("web/style.css"));
                    exchange.getResponseHeaders().set("Content-Type", "text/css; charset=UTF-8");
                    exchange.sendResponseHeaders(200, css.length);
                    exchange.getResponseBody().write(css);
                    exchange.getResponseBody().close();
                } else if (path.equals("/api/tasks") && exchange.getRequestMethod().equals("GET")) {
                    send(exchange, 200, tasksJson());
                } else if (path.equals("/api/tasks") && exchange.getRequestMethod().equals("POST")) {
                    Map<String, String> data = form(exchange);
                    int id = createTask(data);
                    send(exchange, 201, "{\"id\":" + id + "}");
                } else if (path.startsWith("/api/tasks/") && exchange.getRequestMethod().equals("POST")) {
                    int id = Integer.parseInt(path.substring("/api/tasks/".length()));
                    Map<String, String> data = form(exchange);
                    if (data.size() == 2 && data.containsKey("field") && data.containsKey("value")) {
                        updateSingleField(id, data);
                    } else if (data.size() == 2 && data.containsKey("done") && data.containsKey("completedDate")) {
                        updateCompletion(id, data);
                    } else {
                        updateTask(id, data);
                    }
                    send(exchange, 200, "{\"ok\":true}");
                } else if (path.startsWith("/api/tasks/") && exchange.getRequestMethod().equals("DELETE")) {
                    int id = Integer.parseInt(path.substring("/api/tasks/".length()));
                    try (Connection c = connection();
                            PreparedStatement p = c.prepareStatement("DELETE FROM tasks WHERE id=?")) {
                        p.setInt(1, id);
                        p.executeUpdate();
                    }
                    send(exchange, 200, "{\"ok\":true}");
                } else if (path.equals("/api/categories") && exchange.getRequestMethod().equals("GET")) {
                    send(exchange, 200, categoriesJson());
                } else {
                    send(exchange, 404, "そのパスはありません。");
                }
            } catch (IllegalArgumentException | SQLException e) {
                send(exchange, 400, "{\"error\":\"" + json(e.getMessage()) + "\"}");
            } catch (Exception e) {
                send(exchange, 500, "{\"error\":\"サーバー内部でエラーが発生しました。\"}");
            }
        });
        server.setExecutor(null);
        server.start();
        System.out.println("サーバー起動: http://localhost:8080 （止めるときは Ctrl+C）");
    }

    private static void initializeDatabase() throws SQLException {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, done INTEGER NOT NULL DEFAULT 0, category TEXT NOT NULL, content TEXT NOT NULL, details TEXT NOT NULL DEFAULT '', priority TEXT NOT NULL DEFAULT '中', importance TEXT NOT NULL DEFAULT '中', due_date TEXT NOT NULL, created_date TEXT NOT NULL, completed_date TEXT NOT NULL DEFAULT '')");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS categories (name TEXT PRIMARY KEY)");
            s.executeUpdate("INSERT OR IGNORE INTO categories(name) VALUES ('雑務'),('学習'),('求職')");
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    private static int createTask(Map<String, String> d) throws SQLException {
        String content = required(d, "content");
        String category = value(d, "category", "雑務");
        String due = value(d, "dueDate", LocalDate.now().plusDays(7).toString());
        String created = LocalDate.now().toString();
        try (Connection c = connection();
                PreparedStatement p = c.prepareStatement(
                        "INSERT INTO tasks(done,category,content,details,priority,importance,due_date,created_date,completed_date) VALUES(?,?,?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
            p.setInt(1, Boolean.parseBoolean(value(d, "done", "false")) ? 1 : 0);
            p.setString(2, category);
            p.setString(3, content);
            p.setString(4, value(d, "details", ""));
            p.setString(5, value(d, "priority", "中"));
            p.setString(6, value(d, "importance", "中"));
            p.setString(7, due);
            p.setString(8, created);
            p.setString(9, Boolean.parseBoolean(value(d, "done", "false")) ? value(d, "completedDate", created) : "");
            p.executeUpdate();
            rememberCategory(c, category);
            try (ResultSet rs = p.getGeneratedKeys()) {
                if (rs.next())
                    return rs.getInt(1);
            }
        }
        throw new SQLException("IDを取得できませんでした。");
    }

    private static void updateTask(int id, Map<String, String> d) throws SQLException {
        String content = required(d, "content");
        boolean done = Boolean.parseBoolean(value(d, "done", "false"));
        try (Connection c = connection();
                PreparedStatement p = c.prepareStatement(
                        "UPDATE tasks SET done=?,category=?,content=?,details=?,priority=?,importance=?,due_date=?,completed_date=? WHERE id=?")) {
            p.setInt(1, done ? 1 : 0);
            p.setString(2, value(d, "category", "雑務"));
            p.setString(3, content);
            p.setString(4, value(d, "details", ""));
            p.setString(5, value(d, "priority", "中"));
            p.setString(6, value(d, "importance", "中"));
            p.setString(7, value(d, "dueDate", ""));
            p.setString(8, done ? value(d, "completedDate", LocalDate.now().toString()) : "");
            p.setInt(9, id);
            if (p.executeUpdate() == 0)
                throw new IllegalArgumentException("タスクが見つかりません。");
            rememberCategory(c, value(d, "category", "雑務"));
        }
    }

    private static void updateCompletion(int id, Map<String, String> d) throws SQLException {
        boolean done = Boolean.parseBoolean(value(d, "done", "false"));
        String completedDate = done ? value(d, "completedDate", LocalDate.now().toString()) : "";
        try (Connection c = connection();
                PreparedStatement p = c.prepareStatement("UPDATE tasks SET done=?, completed_date=? WHERE id=?")) {
            p.setInt(1, done ? 1 : 0);
            p.setString(2, completedDate);
            p.setInt(3, id);
            if (p.executeUpdate() == 0)
                throw new IllegalArgumentException("タスクが見つかりません。");
        }
    }

    private static void updateSingleField(int id, Map<String, String> d) throws SQLException {
        String field = value(d, "field", "");
        String column = switch (field) {
            case "category" -> "category";
            case "priority" -> "priority";
            case "importance" -> "importance";
            case "dueDate" -> "due_date";
            case "completedDate" -> "completed_date";
            default -> throw new IllegalArgumentException("この項目は即時保存できません。");
        };
        String newValue = value(d, "value", "");
        try (Connection c = connection();
                PreparedStatement p = c.prepareStatement("UPDATE tasks SET " + column + "=? WHERE id=?")) {
            p.setString(1, newValue);
            p.setInt(2, id);
            if (p.executeUpdate() == 0)
                throw new IllegalArgumentException("タスクが見つかりません。");
            if (field.equals("category"))
                rememberCategory(c, newValue);
        }
    }

    private static List<Map<String, String>> allTasks() throws SQLException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Connection c = connection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT id,done,category,content,details,priority,importance,due_date,created_date,completed_date FROM tasks ORDER BY id ASC")) {
            while (rs.next()) {
                Map<String, String> r = new LinkedHashMap<>();
                r.put("id", rs.getString("id"));
                r.put("done", rs.getInt("done") == 1 ? "true" : "false");
                r.put("category", rs.getString("category"));
                r.put("content", rs.getString("content"));
                r.put("details", rs.getString("details"));
                r.put("priority", rs.getString("priority"));
                r.put("importance", rs.getString("importance"));
                r.put("dueDate", rs.getString("due_date"));
                r.put("createdDate", rs.getString("created_date"));
                r.put("completedDate", rs.getString("completed_date"));
                rows.add(r);
            }
        }
        return rows;
    }

    private static String tasksJson() throws SQLException {
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (Map<String, String> r : allTasks()) {
            if (!first)
                b.append(',');
            first = false;
            b.append('{');
            boolean f = true;
            for (Map.Entry<String, String> e : r.entrySet()) {
                if (!f)
                    b.append(',');
                f = false;
                b.append('"').append(json(e.getKey())).append("\":\"").append(json(e.getValue())).append('"');
            }
            b.append('}');
        }
        return b.append(']').toString();
    }

    private static String categoriesJson() throws SQLException {
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        try (Connection c = connection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT name FROM categories ORDER BY name")) {
            while (rs.next()) {
                if (!first)
                    b.append(',');
                first = false;
                b.append('"').append(json(rs.getString(1))).append('"');
            }
        }
        return b.append(']').toString();
    }

    private static void rememberCategory(Connection c, String category) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT OR IGNORE INTO categories(name) VALUES(?)")) {
            p.setString(1, category);
            p.executeUpdate();
        }
    }

    private static Map<String, String> form(HttpExchange e) throws IOException {
        String raw = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            String[] p = pair.split("=", 2);
            out.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8),
                    p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "");
        }
        return out;
    }

    private static String value(Map<String, String> d, String k, String fallback) {
        return d.getOrDefault(k, fallback).trim();
    }

    private static String required(Map<String, String> d, String k) {
        String v = value(d, k, "");
        if (v.isEmpty())
            throw new IllegalArgumentException("タスクの内容を入力してください。");
        return v;
    }

    private static String json(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t",
                "\\t");
    }

    private static void send(HttpExchange e, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        if (status == 404)
            e.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        e.sendResponseHeaders(status, body.length);
        e.getResponseBody().write(body);
        e.getResponseBody().close();
    }

    private static String page() throws IOException {
        return Files.readString(Path.of("web/index.html"), StandardCharsets.UTF_8);
    }
}
