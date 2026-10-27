# ToDoApp

Java標準の`HttpServer`で動作する、SQLite保存のローカルToDoアプリです。画面は`http://localhost:8080/`で開きます。SQLite JDBCドライバーだけを外部依存として使用します。

## 必要なもの

- JDK 21以降
- SQLite JDBCドライバー（次の手順で`lib`に配置）

## ビルドと起動（PowerShell）

プロジェクトのフォルダーで一度だけドライバーをダウンロードし、コンパイルします。

```powershell
New-Item -ItemType Directory -Force lib, out | Out-Null
Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.41.2.2/sqlite-jdbc-3.41.2.2.jar" -OutFile "lib/sqlite-jdbc.jar"
javac --add-modules jdk.httpserver -encoding UTF-8 -cp "lib/sqlite-jdbc.jar" -d out ToDoApp.java
```

サーバーを起動します。

```powershell
java --add-modules jdk.httpserver -cp "out;lib/sqlite-jdbc.jar" ToDoApp
```

表示された`http://localhost:8080/`をブラウザーで開きます。終了は`Ctrl+C`です。タスクとカテゴリは、実行時のフォルダーに作られる`todo.db`に保存されます。

## 機能

- タスクの追加、一覧、編集、完了切り替え、削除
- 未完了タスクだけの表示
- 標準カテゴリの選択と、新しいカテゴリの登録
- 入力日・IDの自動採番、期限日の7日後初期値
- SQLiteによる永続化

