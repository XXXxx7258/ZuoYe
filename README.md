# 本地日程提醒工具（25年11.20课程作业）

## 概要
基于 Java Swing / SystemTray 的本地日程提醒服务，默认无窗口后台运行，支持 XML 持久化、重复规则（不重复/每天/每周）、托盘提醒、内置 HTTP API，以及一页式 Neo-Brutalism 前端（`neo_brutalism_dashboard.html`）做主要交互。已集成网易云点歌/热评/歌词，支持将歌曲设为提醒铃声并本地下载播放。

## 核心功能
- 日程管理：新增/删除、按时间排序，重复事件自动滚动到未来。
- 提醒：后台线程每分钟检查 1 分钟内将到事件，内置 Neo 风格弹窗提醒，可暂停铃声或关闭（铃声用内置 mp3 播放，失败回退系统提示音）。
- 持久化：工作目录 `schedule.xml`（UTF-8），字段含 id/title/date/time/repeat/musicTitle/musicUrl/musicFile。
- HTTP API：内置 `http://localhost:18080`，为前端提供 CRUD + 音乐搜索/热评/歌词。
- 前端：Neo-Brutalism 单页，表单新增、列表删除，60 组件展厅+命令面板等交互；音乐区支持搜索/试听/设置提醒音乐、查看热评、歌词随播放高亮滚动。
- 运行模式：默认无 Swing 窗口；需要窗口时可开关。

## 快速开始
```bash
# 编译（需 jlayer 播放 mp3，jar 已在 lib/）
javac -encoding UTF-8 -cp lib/jlayer-1.0.1.jar -d out src/*.java

# 运行（无窗，托盘+HTTP+提醒）
java -cp \"out;lib/jlayer-1.0.1.jar\" Main

# 运行（显示 Swing 窗口）
java -cp \"out;lib/jlayer-1.0.1.jar\" Main --gui
# 或环境变量 SCHEDULER_GUI=true java -cp \"out;lib/jlayer-1.0.1.jar\" Main
```
启动后浏览器访问 `http://localhost:18080`，直连前端页面。

## HTTP API
- GET `/api/schedules` → 200 `[ {id,title,date,time,repeat,musicTitle,musicUrl,musicFile} ]`
- POST `/api/schedules`
  - body（JSON）：`{ "title": "...", "date": "yyyy-MM-dd", "time": "HH:mm", "repeat": "NONE|DAILY|WEEKLY", "musicTitle": "...", "musicUrl": "..." }`
  - 返回：201 创建对象
- DELETE `/api/schedules?id=...` → 200 删除；不存在返回 404
- GET `/api/music/search?q=关键词` → 网易云搜索（带播放直链）
- GET `/api/music/comments?id=歌曲ID` → 网易云热评
- GET `/api/music/lyric?id=歌曲ID` → 歌词

## 前端（neo_brutalism_dashboard.html）
- 左侧导航 + 右侧展厅（60 组件）+ 日程表单/列表联动。
- 表单提交调用 `/api/schedules`；列表按钮删除；Toast 显示状态。可选择提醒音乐。
- 音乐区：网易云搜索/试听/设为提醒，热评卡片，歌词随播放时间高亮滚动。
- Hero 区提示访问地址；命令面板支持快捷键 Ctrl/Cmd+K。

## 数据存储
- 路径：工作目录 `schedule.xml`
- 编码：UTF-8
- 结构：`id` (UUID), `title`, `date` (yyyy-MM-dd), `time` (HH:mm), `repeat` (枚举), `musicTitle`, `musicUrl`, `musicFile`（本地下载路径）
- 兼容：旧文件无 id 时自动生成新 UUID 写回。

## 提醒与托盘
- 轮询：每 1 分钟扫描当前~+1 分钟的事件。
- 托盘菜单：显示窗口 / 退出并保存。
- 弹窗提醒：Neo 风格对话框，展示标题/时间，按钮可暂停铃声或关闭；铃声用内置 mp3 播放，失败回退系统提示音。

## 目录结构
```
src/Main.java                  # 后端入口，托盘+HTTP+逻辑，提醒调用内置 mp3 播放
src/MusicService.java          # 网易云搜索/热评/歌词/下载
src/MiniJson.java              # 轻量 JSON 解析
src/Mp3Player.java             # 基于 jlayer 的简易 MP3 播放
neo_brutalism_dashboard.html   # 前端单页
schedule.xml                   # 运行生成的日程数据
out/                           # 编译输出（示例目录）
```

## 常用命令
- 编译：`javac -encoding UTF-8 -d out src/Main.java`
- 运行（无窗）：`java -cp out Main`
- 运行（有窗）：`java -cp out Main --gui`
- 访问前端：`http://localhost:18080`

## 设计备注 / 下一步
- 已解耦前后端：主要交互走 HTTP + 前端；Swing 窗口为可选。
- 可扩展：提前量提醒配置、SQLite/文件锁提升可靠性、多用户/权限、PWA/移动端适配。 
