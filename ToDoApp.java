import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
                if (path.equals("/api/tasks") && exchange.getRequestMethod().equals("GET")) {
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
                    try (Connection c = connection(); PreparedStatement p = c.prepareStatement("DELETE FROM tasks WHERE id=?")) {
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
            s.executeUpdate("CREATE TABLE IF NOT EXISTS tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, done INTEGER NOT NULL DEFAULT 0, category TEXT NOT NULL, content TEXT NOT NULL, details TEXT NOT NULL DEFAULT '', priority TEXT NOT NULL DEFAULT '中', importance TEXT NOT NULL DEFAULT '中', due_date TEXT NOT NULL, created_date TEXT NOT NULL, completed_date TEXT NOT NULL DEFAULT '')");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS categories (name TEXT PRIMARY KEY)");
            s.executeUpdate("INSERT OR IGNORE INTO categories(name) VALUES ('雑務'),('学習'),('求職')");
        }
    }

    private static Connection connection() throws SQLException { return DriverManager.getConnection(DB_URL); }

    private static int createTask(Map<String, String> d) throws SQLException {
        String content = required(d, "content");
        String category = value(d, "category", "雑務");
        String due = value(d, "dueDate", LocalDate.now().plusDays(7).toString());
        String created = LocalDate.now().toString();
        try (Connection c = connection(); PreparedStatement p = c.prepareStatement("INSERT INTO tasks(done,category,content,details,priority,importance,due_date,created_date,completed_date) VALUES(?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setInt(1, Boolean.parseBoolean(value(d,"done","false")) ? 1 : 0);
            p.setString(2, category); p.setString(3, content); p.setString(4, value(d,"details",""));
            p.setString(5, value(d,"priority","中")); p.setString(6, value(d,"importance","中"));
            p.setString(7, due); p.setString(8, created);
            p.setString(9, Boolean.parseBoolean(value(d,"done","false")) ? value(d,"completedDate",created) : "");
            p.executeUpdate();
            rememberCategory(c, category);
            try (ResultSet rs = p.getGeneratedKeys()) { if (rs.next()) return rs.getInt(1); }
        }
        throw new SQLException("IDを取得できませんでした。");
    }

    private static void updateTask(int id, Map<String, String> d) throws SQLException {
        String content = required(d, "content");
        boolean done = Boolean.parseBoolean(value(d,"done","false"));
        try (Connection c = connection(); PreparedStatement p = c.prepareStatement("UPDATE tasks SET done=?,category=?,content=?,details=?,priority=?,importance=?,due_date=?,completed_date=? WHERE id=?")) {
            p.setInt(1, done ? 1 : 0); p.setString(2,value(d,"category","雑務")); p.setString(3,content);
            p.setString(4,value(d,"details","")); p.setString(5,value(d,"priority","中")); p.setString(6,value(d,"importance","中"));
            p.setString(7,value(d,"dueDate","")); p.setString(8,done ? value(d,"completedDate",LocalDate.now().toString()) : ""); p.setInt(9,id);
            if (p.executeUpdate() == 0) throw new IllegalArgumentException("タスクが見つかりません。");
            rememberCategory(c, value(d,"category","雑務"));
        }
    }

    private static void updateCompletion(int id, Map<String, String> d) throws SQLException {
        boolean done = Boolean.parseBoolean(value(d, "done", "false"));
        String completedDate = done ? value(d, "completedDate", LocalDate.now().toString()) : "";
        try (Connection c = connection(); PreparedStatement p = c.prepareStatement("UPDATE tasks SET done=?, completed_date=? WHERE id=?")) {
            p.setInt(1, done ? 1 : 0);
            p.setString(2, completedDate);
            p.setInt(3, id);
            if (p.executeUpdate() == 0) throw new IllegalArgumentException("タスクが見つかりません。");
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
        try (Connection c = connection(); PreparedStatement p = c.prepareStatement("UPDATE tasks SET " + column + "=? WHERE id=?")) {
            p.setString(1, newValue);
            p.setInt(2, id);
            if (p.executeUpdate() == 0) throw new IllegalArgumentException("タスクが見つかりません。");
            if (field.equals("category")) rememberCategory(c, newValue);
        }
    }

    private static List<Map<String,String>> allTasks() throws SQLException {
        List<Map<String,String>> rows = new ArrayList<>();
        try (Connection c=connection(); Statement s=c.createStatement(); ResultSet rs=s.executeQuery("SELECT id,done,category,content,details,priority,importance,due_date,created_date,completed_date FROM tasks ORDER BY id ASC")) {
            while (rs.next()) {
                Map<String,String> r=new LinkedHashMap<>(); r.put("id",rs.getString("id")); r.put("done",rs.getInt("done")==1?"true":"false");
                r.put("category",rs.getString("category")); r.put("content",rs.getString("content")); r.put("details",rs.getString("details"));
                r.put("priority",rs.getString("priority")); r.put("importance",rs.getString("importance")); r.put("dueDate",rs.getString("due_date"));
                r.put("createdDate",rs.getString("created_date")); r.put("completedDate",rs.getString("completed_date")); rows.add(r);
            }
        }
        return rows;
    }

    private static String tasksJson() throws SQLException {
        StringBuilder b=new StringBuilder("["); boolean first=true;
        for(Map<String,String> r:allTasks()) { if(!first)b.append(','); first=false; b.append('{'); boolean f=true;
            for(Map.Entry<String,String> e:r.entrySet()) { if(!f)b.append(','); f=false; b.append('"').append(json(e.getKey())).append("\":\"").append(json(e.getValue())).append('"'); }
            b.append('}');
        } return b.append(']').toString();
    }

    private static String categoriesJson() throws SQLException {
        StringBuilder b=new StringBuilder("["); boolean first=true;
        try(Connection c=connection(); Statement s=c.createStatement(); ResultSet rs=s.executeQuery("SELECT name FROM categories ORDER BY name")) {
            while(rs.next()) { if(!first)b.append(','); first=false; b.append('"').append(json(rs.getString(1))).append('"'); }
        } return b.append(']').toString();
    }

    private static void rememberCategory(Connection c,String category)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("INSERT OR IGNORE INTO categories(name) VALUES(?)")){p.setString(1,category);p.executeUpdate();}
    }

    private static Map<String,String> form(HttpExchange e) throws IOException {
        String raw=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8); Map<String,String> out=new LinkedHashMap<>();
        for(String pair:raw.split("&")) { String[] p=pair.split("=",2); out.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),p.length>1?URLDecoder.decode(p[1],StandardCharsets.UTF_8):""); }
        return out;
    }
    private static String value(Map<String,String>d,String k,String fallback){return d.getOrDefault(k,fallback).trim();}
    private static String required(Map<String,String>d,String k){String v=value(d,k,"");if(v.isEmpty())throw new IllegalArgumentException("タスクの内容を入力してください。");return v;}
    private static String json(String s){if(s==null)return "";return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t");}
    private static void send(HttpExchange e,int status,String message)throws IOException {
        byte[] body=message.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type","application/json; charset=UTF-8");
        if(status==404)e.getResponseHeaders().set("Content-Type","text/plain; charset=UTF-8");
        e.sendResponseHeaders(status,body.length);e.getResponseBody().write(body);e.getResponseBody().close();
    }

    private static String page() {
        return """
<!doctype html><html lang="ja"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>ToDoApp</title>
<style>
:root{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;color:#274052;background:#e9f7fc;font-synthesis:none}*{box-sizing:border-box}body{margin:0;padding:34px 4vw 60px;background:linear-gradient(145deg,#e8f7ff,#f4fbff 55%,#e7f5fb);min-height:100vh}main{max-width:1500px;margin:auto}h1{font-size:2rem;margin:0 0 24px;color:#23546a}.card{background:#ffffffd9;border:1px solid #d2e8f1;border-radius:18px;padding:22px;box-shadow:0 10px 30px #4b829012;margin-bottom:24px}.form-grid{display:grid;grid-template-columns:repeat(4,minmax(145px,1fr));gap:14px}label{display:flex;flex-direction:column;gap:6px;font-size:.88rem;font-weight:650;color:#416375}label.wide{grid-column:span 2}input,select,textarea{font:inherit;color:#264555;background:#fff;border:1px solid #c8dfe8;border-radius:9px;padding:10px 11px;min-width:0}textarea{resize:vertical;min-height:42px}button{font:inherit;cursor:pointer;border:0;border-radius:10px;padding:10px 16px;font-weight:700;background:#438eaa;color:white}button:hover{filter:brightness(.96)}.primary{align-self:end;min-height:43px}.toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:4px 0 12px}.toggle{display:flex;align-items:center;gap:9px;font-weight:650;color:#426475}.toggle input{width:18px;height:18px;accent-color:#438eaa}.empty{margin:0 0 10px;color:#67808e}.table-wrap{overflow:auto;border-radius:13px;border:1px solid #d4e8df}table{width:100%;border-collapse:collapse;min-width:1120px;background:white}th{position:sticky;top:0;background:#e5f3eb;color:#385b4a;font-size:.84rem;text-align:left;white-space:nowrap}th,td{padding:9px 8px;border-bottom:1px solid #dcece2}tbody tr:nth-child(odd){background:#f2faf5}tbody tr:nth-child(even){background:#e9f5ed}td input,td select{width:100%;padding:7px 8px;background:#ffffffde;border-color:#d2e4d9;font-size:.86rem}td.content-cell{min-width:190px}td.detail-cell{min-width:210px}td.date-cell{min-width:140px}td.category-cell{min-width:165px}td.priority-cell{min-width:88px}td.priority-cell select{min-width:76px;padding-left:5px;padding-right:5px}td.priority-cell.high-priority select,td.priority-cell.high-importance select{background:#fff0b3}td.date-cell.overdue input{background:#f9dce5;border-color:#eca9bb}td.id-cell{font-weight:700;color:#527365}.done-row td{opacity:.68}.done-row td.content-cell input{text-decoration:line-through}.check-cell{text-align:center}.check-cell input{width:18px;height:18px;accent-color:#54977a}.actions{display:flex;align-items:center;gap:5px;white-space:nowrap}.actions button{padding:6px 9px;font-size:.78rem}.delete{background:#c87575}.new-row td{background:#f8fcf9}.new-row-hint{color:#698678;font-size:.82rem;text-align:center;padding:13px}#notice{min-height:1.2em;color:#2e7182;font-size:.92rem}.date-label{white-space:nowrap}@media(max-width:850px){body{padding:22px 14px}.form-grid{grid-template-columns:repeat(2,minmax(130px,1fr))}.card{padding:16px}label.wide{grid-column:span 2}}@media(max-width:470px){.form-grid{grid-template-columns:1fr}label.wide{grid-column:span 1}.toolbar{align-items:flex-start;flex-direction:column}}
.long-text-preview{display:-webkit-box;width:100%;padding:0;background:transparent;color:#35586a;text-align:left;font-size:.86rem;line-height:1.45;font-weight:500;white-space:normal;overflow:hidden;-webkit-box-orient:vertical;-webkit-line-clamp:2;cursor:pointer}.long-text-preview:hover{text-decoration:underline}.content-cell{min-width:220px;max-width:300px}.detail-cell{min-width:240px;max-width:340px}.edit-dialog{width:min(760px,calc(100vw - 28px));max-height:90vh;padding:0;border:1px solid #cde2eb;border-radius:18px;box-shadow:0 24px 70px #244b5c44;color:#274052}.edit-dialog::backdrop{background:#17384777;backdrop-filter:blur(2px)}.edit-dialog form{display:grid;gap:16px;padding:24px}.edit-dialog h2{margin:0;color:#23546a}.edit-dialog label{display:flex;flex-direction:column;gap:7px}.edit-dialog textarea{width:100%;min-height:130px;resize:vertical}.edit-dialog #editDetails{min-height:260px}.dialog-actions{display:flex;justify-content:flex-end;gap:8px}.dialog-actions .cancel{background:#d9e8ed;color:#35586a}</style></head><body><main><h1>ToDoApp</h1>
<section class="card"><form id="newTask"><div class="form-grid">
<label>カテゴリ<select name="category" id="category"><option>雑務</option><option>学習</option><option>求職</option><option value="__new__">＋新しいカテゴリ</option></select></label>
<label class="wide">タスクの内容<input name="content" required maxlength="300" placeholder="例：書類を整理する"></label>
<label class="wide">詳細・実現方法<textarea name="details" rows="1" placeholder="手順やメモ"></textarea></label>
<label>優先度<select name="priority"><option>高</option><option selected>中</option><option>低</option></select></label>
<label>重要度<select name="importance"><option>高</option><option selected>中</option><option>低</option></select></label>
<label>期限日<input type="date" name="dueDate" id="dueDate" required></label>
<button class="primary" type="submit">タスクを追加</button></div></form><p id="notice" aria-live="polite"></p></section>
<section><div class="toolbar"><h2>タスクリスト</h2><label class="toggle"><input type="checkbox" id="hideDone">未完了のみ表示</label></div>
<p class="empty" id="empty" hidden>現在、タスクがありません</p><div class="table-wrap"><table><thead><tr><th>完了</th><th>ID</th><th>カテゴリ</th><th>タスクの内容</th><th>詳細・実現方法</th><th>優先度</th><th>重要度</th><th>期限日</th><th>入力日</th><th>完了日</th><th>操作</th></tr></thead><tbody id="rows"></tbody></table></div></section></main>
<dialog id="editDialog" class="edit-dialog"><form id="editForm"><h2>タスク内容の編集</h2><label>タスクの内容<textarea id="editContent" rows="4" required></textarea></label><label>詳細・実現方法<textarea id="editDetails" rows="10"></textarea></label><p id="editError" role="alert"></p><div class="dialog-actions"><button type="button" class="cancel" id="cancelEdit">キャンセル</button><button type="submit">変更を保存</button></div></form></dialog>
<script>
const $=s=>document.querySelector(s), today=new Date(), dateString=d=>{const x=new Date(d.getTime()-d.getTimezoneOffset()*60000);return x.toISOString().slice(0,10)};
$('#dueDate').value=dateString(new Date(today.getFullYear(),today.getMonth(),today.getDate()+7));
async function request(url,options={}){const r=await fetch(url,options);if(!r.ok){let m='処理に失敗しました';try{m=(await r.json()).error||m}catch{}throw Error(m)}return r.status===204?null:r.json()}
async function saveField(id,name,value){try{const data=new URLSearchParams({field:name,value});await request('/api/tasks/'+id,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},body:data});$('#notice').textContent='変更を即時保存しました'}catch(e){$('#notice').textContent=e.message}}
let categoryOptions=['雑務','学習','求職'];
function field(name,value,type='text',choices){if(name==='category'){const s=document.createElement('select');s.dataset.name=name;categoryOptions.forEach(v=>{const o=document.createElement('option');o.value=v;o.textContent=v;s.append(o)});const add=document.createElement('option');add.value='__new__';add.textContent='＋新しいカテゴリを入力';s.append(add);if(!categoryOptions.includes(value)){categoryOptions.push(value);const o=document.createElement('option');o.value=value;o.textContent=value;s.insertBefore(o,add)}s.value=value;s.addEventListener('change',()=>{if(s.value==='__new__'){const v=prompt('新しいカテゴリ名を入力してください');if(v?.trim()){const name=v.trim();if(!categoryOptions.includes(name))categoryOptions.push(name);const o=document.createElement('option');o.value=name;o.textContent=name;s.insertBefore(o,add);s.value=name}else s.value=value}});return s}if(choices){const s=document.createElement('select');s.dataset.name=name;choices.forEach(v=>{const o=document.createElement('option');o.value=v;o.textContent=v;s.append(o)});s.value=value;return s}const i=document.createElement('input');i.dataset.name=name;i.type=type;i.value=value||'';if(name==='content')i.required=true;return i}
function cell(row,child,cls=''){const td=document.createElement('td');if(cls)td.className=cls;td.append(child);row.append(td);return td}
function previewButton(text,label){const button=document.createElement('button');button.type='button';button.className='long-text-preview';button.textContent=text;button.title=label+'（クリックして全文を表示・編集）';button.addEventListener('click',()=>openEditor(button.closest('tr')));return button}
function openEditor(row){$('#editDialog').dataset.rowId=row.dataset.id;$('#editContent').value=row.querySelector('[data-name="content"]').value;$('#editDetails').value=row.querySelector('[data-name="details"]').value;$('#editError').textContent='';$('#editDialog').showModal()}
$('#cancelEdit').addEventListener('click',()=>$('#editDialog').close());
$('#editForm').addEventListener('submit',async e=>{e.preventDefault();const dialog=$('#editDialog');const row=[...$('#rows').querySelectorAll('tr')].find(r=>r.dataset.id===dialog.dataset.rowId);if(!row)return;const data=new URLSearchParams();row.querySelectorAll('[data-name]').forEach(el=>data.set(el.dataset.name,el.type==='checkbox'?el.checked:el.value));data.set('content',$('#editContent').value);data.set('details',$('#editDetails').value);try{await request('/api/tasks/'+dialog.dataset.rowId,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},body:data});dialog.close();$('#notice').textContent='タスクの内容と詳細を保存しました';await load()}catch(error){$('#editError').textContent=error.message}});
async function load(){const tasks=await request('/api/tasks');const body=$('#rows');body.replaceChildren();const visible=tasks.filter(t=>!$('#hideDone').checked||t.done!=='true');$('#empty').hidden=tasks.length!==0;visible.forEach(t=>{const tr=document.createElement('tr');tr.dataset.id=t.id;if(t.done==='true')tr.classList.add('done-row');
const check=document.createElement('input');check.type='checkbox';check.checked=t.done==='true';check.dataset.name='done';cell(tr,check,'check-cell');const id=document.createElement('span');id.textContent=t.id;cell(tr,id,'id-cell');
const categoryControl=field('category',t.category);cell(tr,categoryControl,'category-cell');categoryControl.addEventListener('change',()=>{if(categoryControl.value!=='__new__')saveField(t.id,'category',categoryControl.value)});const contentCell=cell(tr,previewButton(t.content,'内容を編集'),'content-cell');const contentStore=document.createElement('input');contentStore.type='hidden';contentStore.dataset.name='content';contentStore.value=t.content;contentCell.append(contentStore);const detailsCell=cell(tr,previewButton(t.details||'（詳細なし）','詳細を編集'),'detail-cell');const detailsStore=document.createElement('input');detailsStore.type='hidden';detailsStore.dataset.name='details';detailsStore.value=t.details;detailsCell.append(detailsStore);const priorityControl=field('priority',t.priority,'',['高','中','低']);const priorityCell=cell(tr,priorityControl,'priority-cell');const setPriorityColor=()=>priorityCell.classList.toggle('high-priority',priorityControl.value==='高');priorityControl.addEventListener('change',()=>{setPriorityColor();saveField(t.id,'priority',priorityControl.value)});setPriorityColor();const importanceControl=field('importance',t.importance,'',['高','中','低']);const importanceCell=cell(tr,importanceControl,'priority-cell');const setImportanceColor=()=>importanceCell.classList.toggle('high-importance',importanceControl.value==='高');importanceControl.addEventListener('change',()=>{setImportanceColor();saveField(t.id,'importance',importanceControl.value)});setImportanceColor();const dueDate=field('dueDate',t.dueDate,'date');const dueDateCell=cell(tr,dueDate,'date-cell');const updateOverdue=()=>dueDateCell.classList.toggle('overdue',t.done!=='true'&&!!dueDate.value&&dueDate.value<dateString(new Date()));dueDate.addEventListener('change',()=>{updateOverdue();saveField(t.id,'dueDate',dueDate.value)});updateOverdue();const created=document.createElement('span');created.textContent=t.createdDate;cell(tr,created,'date-cell');const completedDate=field('completedDate',t.completedDate,'date');cell(tr,completedDate,'date-cell');completedDate.addEventListener('change',()=>saveField(t.id,'completedDate',completedDate.value));check.addEventListener('change',async()=>{const previousDate=completedDate.value;const todayValue=dateString(new Date());if(check.checked){if(!completedDate.value||todayValue>completedDate.value)completedDate.value=todayValue}else completedDate.value='';check.disabled=true;try{const data=new URLSearchParams({done:String(check.checked),completedDate:completedDate.value});await request('/api/tasks/'+t.id,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},body:data});$('#notice').textContent=check.checked?'完了として保存しました':'未完了として保存しました';await load()}catch(e){check.checked=!check.checked;completedDate.value=previousDate;$('#notice').textContent=e.message}finally{check.disabled=false}});
const save=document.createElement('button');save.textContent='保存';save.onclick=async()=>{try{const data=new URLSearchParams();tr.querySelectorAll('[data-name]').forEach(el=>data.set(el.dataset.name,el.type==='checkbox'?el.checked:el.value));await request('/api/tasks/'+t.id,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},body:data});$('#notice').textContent='変更を保存しました';await load()}catch(e){$('#notice').textContent=e.message}};const del=document.createElement('button');del.className='delete';del.textContent='削除';del.onclick=async()=>{if(!confirm('このタスクを削除しますか？'))return;await request('/api/tasks/'+t.id,{method:'DELETE'});await load()};const actions=document.createElement('div');actions.className='actions';actions.append(save,del);cell(tr,actions);body.append(tr)});
const blank=document.createElement('tr');blank.className='new-row';const hint=document.createElement('td');hint.colSpan=11;hint.className='new-row-hint';hint.textContent='上の入力欄から新しいタスクを追加できます';blank.append(hint);blank.onclick=()=>$('#newTask input[name="content"]').focus();body.append(blank)}
$('#newTask').addEventListener('submit',async e=>{e.preventDefault();try{const data=new URLSearchParams(new FormData(e.currentTarget));const added=await request('/api/tasks',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},body:data});sessionStorage.setItem('todo-notice','タスク #'+added.id+' を追加しました');location.reload()}catch(err){$('#notice').textContent=err.message}});
$('#hideDone').addEventListener('change',load);async function categories(){categoryOptions=await request('/api/categories');const sel=$('#category');categoryOptions.forEach(c=>{if(![...sel.options].some(o=>o.value===c)){const o=document.createElement('option');o.value=c;o.textContent=c;sel.insertBefore(o,sel.lastElementChild)}});sel.addEventListener('change',()=>{if(sel.value==='__new__'){const v=prompt('新しいカテゴリ名を入力してください');if(v?.trim()){const name=v.trim();if(!categoryOptions.includes(name))categoryOptions.push(name);const o=document.createElement('option');o.value=name;o.textContent=name;sel.insertBefore(o,sel.lastElementChild);sel.value=o.value}else sel.value='雑務'}})}
const previousNotice=sessionStorage.getItem('todo-notice');if(previousNotice){$('#notice').textContent=previousNotice;sessionStorage.removeItem('todo-notice')}
categories().then(load).catch(e=>$('#notice').textContent=e.message);
</script></body></html>
""";
    }
}
