# NeriPlayer Desktop · 音理音理（Windows 原生版）

把 Android 应用 [NeriPlayer](https://github.com/cwuom/NeriPlayer) 重写为**不依赖 Android 运行环境**的 Windows 原生桌面应用。
技术栈是 Kotlin + Compose Desktop（JVM / Skia 渲染），打包为单个 `.msi`，安装即用。

<p align="center">
  <img src="docs/screenshots/home.png" width="46%" alt="首页" />
  <img src="docs/screenshots/nowplaying.png" width="46%" alt="播放页" />
</p>

## 这是 Windows 版

本仓库是 Linux 桌面版 [Asternent/NeriPlayerForLinux](https://github.com/Asternent/NeriPlayerForLinux) 的
`desktop/` 子项目的 **Windows 移植版**，也是原 Android 应用
[cwuom/NeriPlayer](https://github.com/cwuom/NeriPlayer) 桌面化的延续：

- 原 Android 应用 → [cwuom/NeriPlayer](https://github.com/cwuom/NeriPlayer)
- Linux 桌面版（本项目的上游）→ [Asternent/NeriPlayerForLinux](https://github.com/Asternent/NeriPlayerForLinux)
- **本仓库（Windows 版）→ 只面向 Windows**

Kotlin 源码、Material 3 主题、页面与交互与 Linux 版**完全同源**（窗口标题与界面文案仍是 NeriPlayer），
改动的只有与操作系统直接打交道的那几层：

| | Linux 版 | 本仓库（Windows 版） |
| --- | --- | --- |
| 安装包 | `.deb` | `.msi`（WiX Toolset v3 打包），另有免安装应用目录 |
| 系统媒体控制 | MPRIS over D-Bus | WinRT **SMTC**（JNA 直调，见下文）驱动系统媒体浮层 / 锁屏显示；键盘媒体键由 Win32 全局热键接管 |
| 系统托盘 | StatusNotifierItem + DBusMenu（另需系统 python3） | Windows 通知区域原生托盘（AWT `SystemTray`），无额外运行时依赖 |
| 数据目录 | XDG（`~/.config`、`~/.local/share`、`~/.cache`） | `%APPDATA%\NeriPlayer`、`%LOCALAPPDATA%\NeriPlayer` |
| 界面缩放 | 探测 Xft.dpi / GDK_SCALE / QT_SCALE_FACTOR | 直接跟随 Windows 显示缩放，不会二次放大 |
| ffmpeg | 由 deb 声明依赖，装完即在 PATH | 自动探测 winget / Chocolatey / Scoop 等常见安装位置 |
| 打包依赖 | `dpkg`、桌面图标与 `.desktop` | WiX Toolset v3（只打包 MSI 时需要） |

除上表之外，功能、界面与本文档其余部分对两个平台同样适用。

## 下载与安装

从本仓库的 [Releases](../../releases) 下载，三种形式任选：

| 文件 | 说明 |
| --- | --- |
| `NeriPlayer-1.5.0.exe` | **安装程序（推荐）**：双击即可安装，带卸载项、开始菜单快捷方式，可自选安装目录 |
| `NeriPlayer-1.5.0.msi` | MSI 安装包：适合批量分发或静默安装，`msiexec /i NeriPlayer-1.5.0.msi` |
| `NeriPlayer-1.5.0-windows-portable.zip` | **免安装版**：解压后双击目录里的 `NeriPlayer.exe` 直接运行 |

安装完成后从开始菜单启动「NeriPlayer」。

- 安装包内置 jlink 运行时，终端用户**不需要**安装 JDK。
- **每用户安装**：默认只给当前用户安装（`perUserInstall = true`），不需要管理员权限；安装向导可以自选安装目录
  （`dirChooser = true`），并创建开始菜单快捷方式与 `NeriPlayer` 菜单分组。
- **升级**：MSI 与 EXE 都使用固定的 `upgradeUuid`，直接安装新版本即可覆盖升级，不会新旧版本并存。
- **免安装版（portable）**：`gradlew createDistributable` 会产出
  `build\compose\binaries\main\app\NeriPlayer\`，里面是自带运行时的完整应用目录 —— 整个目录拷到哪都能跑
  （双击其中的 `NeriPlayer.exe`）。打成 zip 分发即可，不写注册表，删掉目录就等于卸载。
  仓库自带的 CI 配置 `ci/windows-build.yml` 每次构建都会把这个目录打成
  `NeriPlayer-windows-portable.zip` 作为构建工件上传。
  它是标准的 GitHub Actions 工作流，启用方式：把该文件移动到 `.github/workflows/windows-build.yml`
  并提交（注意推送所用的 Personal Access Token 需要带 `workflow` scope，否则 GitHub 会拒绝这次提交）。
- **安装包元数据保持英文**：`description` / `vendor` 交给 jpackage 时会写进它的参数文件，
  而 jpackage 读参数文件用的是系统默认字符集（JDK 17 在中文 Windows 上是 GBK），
  写中文会乱码并让 jpackage 以 `Input length = 1` 直接失败 —— 所以「程序和功能」里显示的是英文描述，
  应用界面与本文档仍是中文。
- **高分屏（HiDPI）**：默认跟随 Windows 的显示缩放（设置 → 系统 → 显示 → 缩放），也可以在
  「设置 → 界面 → 界面缩放」里手动选择 100% ~ 250%；临时指定用
  `$env:NERIPLAYER_UI_SCALE=1.5; .\NeriPlayer.exe`（CMD 里是 `set NERIPLAYER_UI_SCALE=1.5`）。
- **横屏 / 宽窗口**：窗口内容宽度到 900 dp 就切换成横屏排版（设置两栏、首页卡片并排、歌词限宽居中），
  窗口拖窄会自动回到单栏的紧凑排版。
- **建议安装 `ffmpeg`**：`winget install Gyan.FFmpeg`。它用于解码 m4a / aac / opus 等格式，并提供倍速、
  变调、响度增强与十段均衡器；缺少时自动降级到 Java Sound 引擎（mp3 / flac / ogg / wav / aiff 仍可播放）。
  安装包不内置 ffmpeg，需要单独安装。
- 首次启动显示使用须知，随后在「设置 → 媒体库」添加音乐文件夹并扫描即可建立本地曲库。

## 界面预览

| | |
| --- | --- |
| 首页：继续播放 / 推荐 / 热歌榜 <br> ![首页](docs/screenshots/home.png) | 探索：多源搜索与风格标签 <br> ![探索](docs/screenshots/explore.png) |
| 媒体库：本地 / 收藏 / 在线音源 <br> ![媒体库](docs/screenshots/library.png) | 播放页：封面、进度、音效与队列 <br> ![播放页](docs/screenshots/nowplaying.png) |
| 歌词页：逐行高亮与翻译 <br> ![歌词](docs/screenshots/lyrics.png) | 播放队列：排序 / 移除 / 跳转 <br> ![队列](docs/screenshots/queue.png) |
| 悬浮歌词（描边样式，可拖动定位） <br> ![悬浮歌词](docs/screenshots/floating-lyrics.png) | 哔哩哔哩收藏夹 <br> ![B站收藏夹](docs/screenshots/bilibili-favorites.png) |
| 设置 → 账号：网易云 / 哔哩哔哩扫码登录 <br> ![账号](docs/screenshots/settings.png) | 设置 → 同步：GitHub 跨设备同步 <br> ![同步](docs/screenshots/github-sync.png) |
| 托盘控制面板：封面、进度、传输控制与音量 <br> ![托盘面板](docs/screenshots/tray-panel.png) | 同一面板的深色主题 <br> ![托盘面板深色](docs/screenshots/tray-panel-dark.png) |
| 横屏 · 首页：推荐与热歌卡片两两并排 <br> ![横屏首页](docs/screenshots/landscape-home.png) | 横屏 · 设置：两栏排版，不用滚很久 <br> ![横屏设置](docs/screenshots/landscape-settings.png) |
| 横屏 · 播放页：封面与歌词并排 <br> ![横屏播放页](docs/screenshots/landscape-nowplaying.png) | 横屏 · 探索：标签按宽度自动折行铺满 <br> ![横屏探索](docs/screenshots/landscape-explore.png) |
| 界面缩放：跟随系统 / 手动 100%–250% <br> ![界面缩放](docs/screenshots/ui-scale.png) | 高分屏（系统 200% 缩放）下的实际渲染 <br> ![HiDPI](docs/screenshots/hidpi-200.png) |

> 截图取自应用窗口的**内容区**（不含系统标题栏），深浅色与系统缩放差异不影响这里的排版展示。
> 除下面三张**本仓库的 Windows 实机截图**外，上表沿用上游 Linux 版仓库的原始截图 ——
> 两端共用同一套 Compose 界面代码，排版与配色完全一致。

### Windows 实机截图

| | |
| --- | --- |
| 首页（Windows 实机，在线推荐与热歌榜已加载） <br> ![Windows 首页](docs/screenshots/windows-home.png) | 探索（Windows 实机，联网加载推荐歌单封面） <br> ![Windows 探索](docs/screenshots/windows-explore.png) |

歌词页（Windows 实机：在线歌词逐行高亮、跟随播放滚动）：

![Windows 歌词页](docs/screenshots/windows-lyrics.png)

评论面板（Windows 实机，网易云 / 哔哩哔哩原生评论）：

![Windows 评论](docs/screenshots/windows-comments.png)

首次启动的使用须知（系统缩放 125%，界面跟随系统 DPI）：

![Windows 使用须知](docs/screenshots/windows-onboarding.png)

安装包产物实际运行的窗口（`packageExe` 生成，标题栏是应用图标）：

![Windows 打包版](docs/screenshots/windows-packaged.png)

## 功能清单

| 功能 | 状态 |
| --- | --- |
| 首页（继续播放 / 为你推荐 / 热歌榜 / 推荐新歌 / 私人雷达 / 热门榜单） | ✅ 网易云公开接口 + 本地栏目回退 |
| 探索（多源搜索、搜索历史、热门搜索、风格标签、推荐歌单） | ✅ 网易云 / 哔哩哔哩 / 本地媒体库 |
| 媒体库（本地 / 收藏 / 网易云 / 哔哩哔哩 / 下载 / QQ 音乐） | ✅ 本地歌曲 · 歌手 · 专辑 · 歌单；在线歌单、专辑与收藏夹（QQ 音乐为入口占位） |
| 本地媒体库 | ✅ 文件夹扫描、读取标签与内嵌封面、搜索与排序 |
| 播放引擎 | ✅ ffmpeg 解码（倍速 / 变调 / 响度 / 十段均衡器）+ Java Sound 回退引擎 |
| 歌词 | ✅ 本地 `.lrc` → 内嵌标签 → 在线歌词；**可选优先来源**（网易云 / 酷狗 / LRCLIB / AMLL TTML DB，各带默认偏移）；翻译、音译（罗马音）、逐行高亮、点击跳转、字号调节 |
| 歌词第二行 | ✅ 翻译与音译互斥，播放页歌词界面右上角「译 / 音 / 原」一键循环切换 |
| 歌曲评论 | ✅ 网易云 / 哔哩哔哩原生评论（热门 + 最新，滚动加载更多、只读） |
| 悬浮歌词 | ✅ 无边框置顶窗口，可拖动定位，样式与手机端一致 |
| 账号登录 | ✅ 网易云与哔哩哔哩应用内扫码登录 |
| GitHub 同步 | ✅ 歌单 / 收藏 / 最近播放 / 播放统计，与手机端数据互通 |
| 歌单与收藏 | ✅ 自建歌单、我喜欢的音乐、增删与排序 |
| 最近播放 / 继续播放 | ✅ 支持移除单条与清空 |
| 播放统计 | ✅ 日 / 周 / 月 / 年 / 总，按次数、时长、最近排序 |
| 播放队列 / 睡眠定时器 | ✅ 队列增删排序、倒计时与播完当前停止 |
| 主题 | ✅ 深浅色、动态取色跟随封面、9 种 Material 调色风格、2021 / 2025 色彩规范 |
| 与手机端同步协议互通 | ✅ 同一仓库、同一文件格式（JSON 与省流二进制） |
| 定位到正在播放 | ✅ 列表高亮当前歌曲（播放指示器）+ 一键滚动定位并闪烁提示 |
| 歌曲下载到本地 | ✅ 单曲 / 批量下载、进度与取消重试、离线播放、下载分栏与管理面板 |
| 后台常驻与系统控制 | ✅ 托盘常驻后台播放 + Windows 通知区域托盘图标（点击弹出应用内主题化控制面板）+ 系统媒体控制（SMTC 系统媒体浮层 / 锁屏显示当前曲目，全局媒体热键响应键盘媒体键） |
| 界面缩放（HiDPI） | ✅ 跟随 Windows 显示缩放（屏幕 DPI / `sun.java2d.uiScale`），或手动 100% ~ 250%（设置 → 界面） |
| 横屏（宽窗口）自适应 | ✅ 窗口 ≥ 900 dp 自动切多栏：设置两栏、首页卡片并排、歌词限宽居中；窄窗口保持单栏 |
| 内存占用 | ✅ 封面按需缩放解码（最长边 768 px）+ 64 MB 字节预算缓存；安装版堆上限 512 MB 并定期归还内存 |

### 与原 Android 应用的差异

以下能力是 Android 平台专有或依赖系统服务，桌面端不适用或未移植：
状态栏歌词、桌面小组件、启动器快捷方式、USB DAC 独占、省电保活与 ANR 日志、下载管理、
一起听（依赖自建服务端）；YouTube Music 需要 Google 账号授权与专用解析，桌面端保留入口与说明。

**歌词来源里的「QQ 音乐」也未移植**：它的歌词接口要把请求参数包成一层签名过的 JSON
（`u.y.qq.com/cgi-bin/musicu.fcg`），收益不抵复杂度，而网易云 / 酷狗 / LRCLIB / AMLL 已覆盖绝大多数曲目。

**评论是只读的**：网易云的点赞与发评论需要一个由 Android WebView + 网易 Watchman JS 生成的
`checkToken`（桌面端要为此内嵌一个浏览器内核），因此桌面版只做「看评论」，不做点赞与回复。

## 快捷键

| 按键 | 作用 |
| --- | --- |
| `空格` | 播放 / 暂停 |
| `←` / `→` | 后退 / 前进 5 秒 |
| `↑` / `↓` | 音量 +5% / −5% |
| `Ctrl` + `←` / `→` | 上一首 / 下一首 |
| `Ctrl` + `L` | 开关悬浮歌词 |

## 使用说明

### 歌词来源与音译

「设置 → 歌词来源」可以指定**优先歌词来源**，在线歌曲会先去那里找，找不到再按
「本地 `.lrc` → 内嵌标签 → 歌曲所属平台」的顺序回退；本地歌曲始终优先使用同目录的 `.lrc`。

| 来源 | 说明 |
| --- | --- |
| 自动 | 不额外指定，按老规矩走 |
| 网易云 | 用「标题 + 歌手」搜索匹配，可取到翻译与音译（`romalrc`） |
| 酷狗 | 按时长挑最接近的候选，取 LRC（含翻译） |
| LRCLIB | 开放的歌词库，只取**带时间轴**的版本（纯文本歌词在逐行高亮里没法用） |
| AMLL TTML DB | 社区逐字歌词库，取到行级时间轴；按标题搜索并用歌手 / 网易云 ID 交叉确认 |

两个细节：

- **时长校验**：第三方来源的时长与平台给的对不齐时留了余量（`max(7 秒, 期望时长 × 6%)`，上限 15 秒），
  超出范围就判定不是同一首，避免串词。
- **默认偏移**：每个来源可以单独配一个时间偏移（±5 秒、50 ms 一档，正值 = 歌词提前），
  叠加在 LRC 自带的 `[offset:]` 之上 —— 有些歌词库整体偏几百毫秒，逐首调太麻烦。
  桌面版默认全是 0（位置时钟直接来自解码器，不需要像手机端那样给网易云补 1 秒）。

播放页歌词界面右上角的 **「译 / 音 / 原」** 按钮在「翻译 → 音译 → 原文」之间循环；
**翻译与音译互斥**，同一时刻只显示第二行中的一种 —— 两行都堆在原文下面会把行距撑得很开。
歌词没有音译时会自动回落到翻译。

### 查看评论

播放页歌词界面右上角的评论按钮可以打开评论面板（本地歌曲不会出现这个按钮）：

- **网易云**：一次拿到热门评论与最新评论，显示昵称、头像、正文、点赞数、回复数与 IP 归属地；
- **哔哩哔哩**：按视频 `aid` 取评论（由 `bvid` 经 `web-interface/view` 解析），按热度排序；
- 滚动到底部点「加载更多」按已取条数翻页，追加时按评论 id 去重。

评论是只读的，原因见上文「与原 Android 应用的差异」。

### 横屏 / 宽窗口布局

窗口内容宽度达到 **900 dp** 时自动切换到横屏排版，把横向空间真正用起来；把窗口拖窄回单栏的紧凑排版。
两种排版共用同一套控件，拖动窗口边缘就能实时切换。

| 页面 | 紧凑（窄窗口） | 横屏（宽窗口 / 最大化） |
| --- | --- | --- |
| 设置 | 单列长列表 | 左右两栏（账号 · 主题 / 界面 · 播放） |
| 首页 | 歌曲卡片单列 | 卡片两两并排 |
| 探索 | 标签一行 6 个 | 按宽度自动折行铺满，推荐歌单一行放更多 |
| 播放页 | 封面在上、歌词在下 | 封面与歌词左右并排，封面更大 |
| 全屏歌词 | 占满窗口 | 正文限宽居中，避免一行横跨整屏难扫读 |
| 媒体库 / 统计 / 最近播放 / 下载 | 单列列表 | 列表铺满窗口，不再收在中间一条窄列里 |

### 登录网易云与哔哩哔哩

1. 打开「设置 → 账号」；
2. 点对应平台的 **扫码登录**，用手机 App（网易云「扫一扫」/ 哔哩哔哩「＋ → 扫一扫」）扫码确认；
3. 登录后媒体库会多出「我的歌单」与「我的收藏夹」，在线播放也会使用账号可用的更高音质。

凭据（Cookie）只保存在本机 `%APPDATA%\NeriPlayer\accounts.json`，不会上传到任何第三方服务。
不登录也能使用搜索、推荐、歌词与试听音质。

### GitHub 同步

与原 Android 端共用同一套协议：数据写在你自己的 GitHub 仓库根目录，普通通道 `backup.json`、
省流通道 `backup-raw.bin`（GZIP + Protobuf），两端可互相读写。

1. 在 GitHub 生成带 `repo` 权限的 Token（建议 fine-grained 且只授权给同步仓库），填入并点「验证 Token」；
2. 点「创建私有仓库」或「选择现有仓库」；
3. 开启「自动同步」后，歌单 / 收藏 / 最近播放 / 统计变化会在 15 秒内自动同步；也可随时「立即同步」。

合并策略：歌单按修改时间取新、歌曲取并集并尊重删除墓碑；最近播放按歌曲身份去重取最新；
播放统计按身份 + 日期分桶取 `max`（避免两端重复计数）。提交走 Git 数据接口并带读取时的 HEAD，
其他设备并发提交会被判定为冲突并自动重试。Token 保存在 `%APPDATA%\NeriPlayer\sync.json`。

### 悬浮歌词

- 开启：设置 → 悬浮歌词，或播放页工具栏按钮，或快捷键 `Ctrl+L`；
- **鼠标拖动**即可移动，位置按屏幕比例保存，换分辨率或多屏不会跑出屏幕；
- 鼠标悬停会浮出「上一首 / 播放暂停 / 下一首 / 关闭」；
- 样式：十种歌词颜色、阴影与描边两种渲染（含颜色、宽度 / 模糊）、字号、
  主歌词与翻译不透明度、背景颜色与不透明度、最大宽度、对齐方式、显示翻译、切换淡入；
- 「应用内隐藏」避免遮挡主窗口，「锁定位置」防止误拖动。

### 定位到正在播放

列表里正在播放的歌曲会**高亮显示**，序号位置变成跳动的音量条；顶部「定位」按钮
（歌单详情、在线歌单 / 收藏夹、媒体库本地列表）与队列面板里的「定位到正在播放」
会滚动到该行并让它**闪烁提示**；当前歌曲不在该列表时会明确提示「当前歌曲不在该列表中」。
队列面板打开时自动定位到正在播放，切换歌曲后也会跟随。

### 下载到本地

与原应用一致：把在线歌曲存到本地，离线也能播放。

- 单曲：歌曲「更多」菜单 → 下载到本地；批量：歌单 / 专辑 / 收藏夹详情顶部 → 下载全部
- 媒体库新增「下载」分栏，列出已下载歌曲（可直接播放、删除文件、打开目录）
- 下载管理面板：进度条、取消、重试、清空已完成、打开下载目录；媒体库顶栏有下载入口（带进行中数量角标）
- 已下载歌曲在列表中显示离线标记，播放时**优先使用本地文件**，断网也能放
- 设置 → 下载：下载目录（可选）、并发数（1–4）、下载音质（标准 / 较高 / 极高 / 无损）、占用统计与失效记录清理
- 默认下载目录是「音乐」文件夹下的 `NeriPlayer`（即 `%USERPROFILE%\Music\NeriPlayer`；
  如果你在资源管理器里把「音乐」挪到了别的盘，应用会跟随系统登记的已知文件夹位置）

下载完成时会一并写入元数据，与手机端行为一致：

- 把标题 / 艺术家 / 专辑 / 歌词写进音频**内嵌标签**，其它播放器也能看到完整信息
- 封面**内嵌进音频文件**，同时保存为同名 sidecar 图片（`歌手 - 标题.jpg` / `.png` / `.webp`）
- 歌词保存为同名 `.lrc`（本地歌词优先级链路可以直接读取，离线也能看歌词）
- 下载管理面板提供 **「补齐标签」**：为旧版本下载（或缺标签）的文件补写封面与歌曲信息，无需重新下载

### 后台常驻与系统控制

对应手机端的「前台服务 + 通知栏控制」，Windows 上用**系统托盘**与**系统媒体控制（SMTC）**实现：

- **关闭窗口 = 最小化到托盘**（默认开启）：窗口隐藏后音乐继续播放，点托盘图标可以重新打开或退出；
  最小化窗口同样会收进托盘（可在设置里关掉这两种行为）
- **托盘图标**：注册在 Windows 通知区域（底层就是 `Shell_NotifyIcon`），鼠标悬停显示
  「正在播放：歌曲 - 歌手」；**左键或右键点击**都会弹出**应用主题风格的控制面板** ——
  封面、歌曲信息、队列位置、进度条、上一首 / 播放暂停 / 下一首、音量滑杆、悬浮歌词开关、
  下载管理、设置、显示主窗口与退出，深浅色与动态取色完全跟随主界面
- 面板贴在鼠标附近弹出，鼠标移开、按 `Esc` 或点任意一项都会收起（不依赖窗口焦点）
- **系统媒体浮层（SMTC）**：注册为系统媒体会话后，Windows 的媒体浮层（标题 / 歌手 / 专辑 /
  播放状态 / 进度条）、锁屏界面与音量键旁边的播放信息都会显示当前曲目，随机与循环状态也一并同步
- **键盘媒体键**：通过 Win32 `RegisterHotKey` 注册为**全局热键**（播放暂停 / 上一首 / 下一首 / 停止），
  按下即由应用自己处理，不依赖系统把按键派发给媒体会话
- **歌曲变化通知**：窗口隐藏时发送系统通知（可在设置里关闭）
- 窗口标题也会跟随显示「▶ 歌曲 - 歌手 · NeriPlayer」，任务栏一眼可见
- 设置页的「后台与系统控制」会实时显示两项状态：**系统通知区域**是否可用、
  **系统媒体控制**是否已接管以及当前后端

> 为什么不用 Compose Desktop 自带的托盘菜单：它的菜单是 AWT 原生弹出菜单，灰底、无图标，
> 字号也不跟随应用主题，观感与应用本体割裂。这里改为直接管理 `TrayIcon`（不放任何弹出菜单），
> 左右键统一交给应用内自绘的控制面板，配色、圆角与图标和主界面完全一致。
>
> 系统媒体控制怎么做的：Windows 没有 Java 版的媒体控制 API，这里用 JNA 直接按 ABI 调用 WinRT ——
> `RoGetActivationFactory` 取到 `ISystemMediaTransportControlsInterop`，再 `GetForWindow` 建立属于本进程
> 窗口的 SMTC 会话，然后写入曲目元数据、播放状态、进度与随机 / 循环状态（Linux 版走的是 D-Bus 上的
> MPRIS 接口）。所有 COM 调用固定在一条初始化过 COM 的线程上执行。
>
> **只用了 SMTC 的「写入」方向**：读取方向（`add_ButtonPressed` 等事件）要求调用方提供一个手写 vtable 的
> COM 委托对象，而 JVM 侧 JNA 生成的回调桩在 WinRT 的事件注册路径上会让进程以 `0xC0000409` 直接崩溃
> （已实测：改用显式回调类、公开接口、逐条校正 vtable 槽位后依然如此，且回调函数根本没有被进入）。
> 因此媒体键的「读入」方向交给更可靠的全局热键：系统浮层照常显示当前曲目，键盘媒体键由应用自己响应。
> 受影响的一点是：直接在系统浮层上点播放 / 上一首不会生效，用键盘媒体键或应用内控件即可。

## 数据目录（Windows）

Windows 把「漫游配置」和「本机数据」分成两棵树，本应用同样把两者分开存放，
这样「清理缓存」不会顺手把设置删掉：

| 路径 | 内容 |
| --- | --- |
| `%APPDATA%\NeriPlayer\settings.json` | 设置、主题、队列与进度快照 |
| `%APPDATA%\NeriPlayer\accounts.json` | 网易云 / 哔哩哔哩登录凭据 |
| `%APPDATA%\NeriPlayer\sync.json` | GitHub 同步配置与 Token |
| `%LOCALAPPDATA%\NeriPlayer\library.json` | 本地媒体库索引 |
| `%LOCALAPPDATA%\NeriPlayer\playlists.json` | 歌单与「我喜欢的音乐」 |
| `%LOCALAPPDATA%\NeriPlayer\history.json` | 最近播放 |
| `%LOCALAPPDATA%\NeriPlayer\stats.json` | 播放统计 |
| `%LOCALAPPDATA%\NeriPlayer\downloads.json` | 已下载歌曲目录索引 |
| `%LOCALAPPDATA%\NeriPlayer\search_history.json` | 搜索历史 |
| `%LOCALAPPDATA%\NeriPlayer\logs` | 运行日志 |
| `%LOCALAPPDATA%\NeriPlayer\cache\artwork`、`covers` | 内嵌封面提取与在线封面缓存 |

- 实际路径会显示在「设置 → 关于」里（数据目录 / 缓存目录），把该路径粘进资源管理器的地址栏就能直接打开。
- 企业环境里 `%APPDATA%` / `%LOCALAPPDATA%` 常被重定向到网络盘，应用一律通过这两个环境变量定位，不硬编码路径。
- **便携使用**：可以用环境变量把三棵树都指到别处（例如 U 盘或应用目录旁边），
  对应 Linux 版的 XDG 覆盖：

  ```powershell
  $env:NERIPLAYER_CONFIG_HOME="D:\NeriPlayer\config"
  $env:NERIPLAYER_DATA_HOME="D:\NeriPlayer\data"
  $env:NERIPLAYER_CACHE_HOME="D:\NeriPlayer\cache"
  .\NeriPlayer.exe
  ```

- **默认音乐目录**：优先读取系统登记的「音乐」已知文件夹（你在资源管理器里改过位置也能取到），
  取不到时回退到 `%USERPROFILE%\Music`；该目录存在时会自动加入媒体库。

## 从源码构建

需要 **JDK 17+** 与网络（首次构建会下载 Maven 依赖），Gradle Wrapper 已随仓库提交，不需要另装 Gradle：

```powershell
gradlew run                    # 直接运行
gradlew createDistributable    # 免安装应用目录：build\compose\binaries\main\app\NeriPlayer\
gradlew packageMsi             # MSI：build\compose\binaries\main\msi\NeriPlayer-1.5.0.msi
gradlew packageExe             # EXE 安装程序：build\compose\binaries\main\exe\NeriPlayer-1.5.0.exe
```

- `gradlew` 是批处理包装脚本，在 CMD / PowerShell 里直接写 `gradlew`（或 `.\gradlew.bat`）；
  在 Git Bash 之类的 bash 里用 `./gradlew`。
- **`packageMsi` / `packageExe` 需要 WiX Toolset v3**（提供 `candle.exe` / `light.exe`）。
  Compose 插件会自己下载一份到 `build\wix311\`，通常不用手动装；如果它下载失败，
  再从 <https://github.com/wixtoolset/wix3/releases> 取 `wix314-binaries.zip` 解压并把目录加进 `PATH` 即可。
  只想产出可运行的应用目录就用 `gradlew createDistributable`，**不需要 WiX**，
  产物自带运行时，压缩成 zip 就是免安装版。
- 打包时会自动裁剪图标库（`trimMaterialIcons` 任务）：Compose 的 `material-icons-extended` 把一万多个图标
  都编成了独立类（jar 36 MB），而应用真正用到的只有几十个。编译期照常使用完整依赖，打包与运行时替换成
  扫描常量池后只保留被引用图标的精简 jar（约 120 KB），MSI 与免安装版都因此明显变小。
  新增图标用法后无需手工维护：该任务每次打包都会重新扫描本工程的 class 与编译期依赖重新生成。
- 运行参数在 `build.gradle.kts` 里固定为 `-Xmx512m -XX:MaxMetaspaceSize=192m -XX:+UseG1GC`
  并开启定期归还空闲堆，对应上表的「内存占用」一行。

### 测试

```powershell
gradlew selfTest                      # 核心自检：解码 / 播放 / 跳转 / 变速 / 歌词 / 在线接口 / 同步与悬浮歌词算法
gradlew syncE2E -DGH_TOKEN=xxx        # GitHub 同步真实端到端（会创建并自动删除一个临时私有仓库）
gradlew syncE2E -DGH_MOCK_BASE=http://127.0.0.1:8765   # 不连云端的 GitHub API 模拟服务，无需 Token
```

界面回归通过脚本驱动：设置环境变量 `NERIPLAYER_UI_TEST="tab:library;play:0;pause;sleep:9000;expect-paused"`
即可让应用自动执行一串操作（切页、播放、暂停、队列、歌词、托盘面板、下载、定位等都有对应命令）并输出断言结果，
便于在无人值守环境下验证界面行为。脚本里还能插入 `mem-report` 打印一次内存快照
（JVM 堆 / 进程常驻内存 / 封面缓存占用），用来做内存占用的回归对比。

`tools/make_demo_library.py` 可以生成一套带标签与内嵌封面的演示曲库（需要 Python 3、Pillow，
以及 PATH 上的 ffmpeg），方便在没有真实音乐文件的环境里跑上述测试。

## 疑难排查（Windows）

### `packageMsi` / `packageExe` 报错找不到 `candle.exe` / `light.exe`

Compose 插件会自动下载 WiX 3.11 到 `build\wix311\`；下载失败时（网络受限）手动装一份：
从 <https://github.com/wixtoolset/wix3/releases> 下载 `wix314-binaries.zip`，解压后把目录加入 `PATH`，
重新执行任务即可。不想折腾 WiX 就直接用 `gradlew createDistributable`，它不依赖 WiX。

### 设置里显示「Java Sound 回退引擎」，或者某些格式放不了、音效不可用

说明没找到 `ffmpeg`。用 `winget install Gyan.FFmpeg` 安装（也可以装 Chocolatey / Scoop 版，
或把 ffmpeg 解压到 `C:\ffmpeg\bin`），**装完重启应用**：应用只在启动时探测一次 ffmpeg 的位置。
它按 PATH → `%LOCALAPPDATA%\Microsoft\WinGet\Links` → Chocolatey → Scoop → `C:\ffmpeg\bin` →
`%ProgramFiles%\ffmpeg\bin` 等常见位置依次查找，因此即使 PATH 没刷新也多半能找到。
确认方法：「设置 → 关于」里的「音频引擎」会显示 ffmpeg 版本；显示 `Java Sound 回退引擎` 就是还没找到。

### 键盘媒体键没反应

1. 打开「设置 → 后台与系统控制」，确认 **「启用系统媒体控制（SMTC）」** 是开着的，
   并看这一组的底部状态：显示「已接管（SMTC 系统媒体浮层 + 全局媒体热键）」说明系统浮层与热键都已就绪；
2. 媒体键走的是**全局热键**，会被别的播放器抢占 —— 关掉其它正在运行的播放器再重启本应用；
3. 只显示「已接管（全局媒体热键）」说明 WinRT 的 `SystemMediaTransportControls` 不可用，
   媒体键仍能控制播放，只是系统媒体浮层里不会显示曲目；
4. 显示「未接管」说明热键也注册失败（通常被其它播放器占用），改完开关后重启应用再试；
5. 注意：直接在 Windows 媒体浮层上点按钮不会控制本应用（原因见上文「为什么不用 SMTC 的事件」），
   请用键盘媒体键或应用内的播放控件。

### 托盘图标在通知区域里找不到

Windows 默认会把新出现的图标收进**溢出区**：点任务栏上的 `^` 展开，把 NeriPlayer 图标拖到任务栏上即可常驻。
如果整个通知区域被系统策略关掉，设置页会显示「系统通知区域：不可用」，此时托盘常驻与「关闭窗口收进托盘」
会自动停用（播放与系统媒体控制不受影响）。

### 关闭窗口后应用还在运行

这是设计行为：**关闭窗口 = 最小化到托盘**，音乐继续播放。想让它直接退出，在
「设置 → 后台与系统控制」里关掉「关闭窗口时最小化到托盘」（以及「最小化时隐藏到托盘」），
或者从托盘控制面板里点「退出」。

### 界面过大 / 过小，或者改了缩放比例不生效

「设置 → 界面 → 界面缩放」里选「跟随系统」时，应用使用 Windows 报告的真实显示缩放，不会重复放大。
多显示器且各屏缩放不同时，建议保持「跟随系统」。手动比例会立即作用于界面，窗口尺寸在下次启动时生效；
临时调试可以用 `NERIPLAYER_UI_SCALE`。

## 常见问题（FAQ）

**需要先装 JDK 吗？**
不需要。MSI 与 `createDistributable` 的产物都内置 jlink 运行时；只有从源码构建才需要 JDK 17+。

**这个仓库支持 Linux 或 macOS 吗？**
不支持，本仓库只做 Windows。Linux 桌面版请用上游的
[Asternent/NeriPlayerForLinux](https://github.com/Asternent/NeriPlayerForLinux)，
Android 版请用 [cwuom/NeriPlayer](https://github.com/cwuom/NeriPlayer)。

**升级新版本会不会丢设置和曲库？**
不会。设置、账号、曲库、歌单、历史与统计都在 `%APPDATA%` / `%LOCALAPPDATA%` 里，与安装目录无关；
MSI 使用固定的 `upgradeUuid`，覆盖升级即可。免安装版只要换掉程序目录，数据同样保留。

**能把数据放到 U 盘或应用目录旁边吗？**
可以，用 `NERIPLAYER_CONFIG_HOME` / `NERIPLAYER_DATA_HOME` / `NERIPLAYER_CACHE_HOME` 三个环境变量，
配合免安装版就是一个完整的绿色便携应用。

**在线音源需要登录吗？**
不登录就能搜索、看推荐、读歌词、按试听音质播放；扫码登录后可访问「我的歌单」「我的收藏夹」，
并使用账号可用的更高音质。

**下载的歌存在哪？**
默认在「音乐」文件夹下的 `NeriPlayer`；可以在「设置 → 下载」里改成任意目录，
下载管理面板与「设置 → 下载」都能一键打开该目录。

**会把我听的歌上传到什么地方吗？**
不会。应用没有自建服务端：在线内容请求直连各平台公开接口，账号凭据与同步 Token 只存在本机；
只有你主动开启的 GitHub 同步会把歌单 / 收藏 / 最近播放 / 统计写进**你自己的**仓库。

**为什么某首歌播放失败？**
在线音频地址具有时效性（尤其是哔哩哔哩的音频轨），失败时界面会给出提示，重试或稍后再试通常即可；
如果提示解码失败，优先检查 ffmpeg 是否装好。

## 技术栈

| 层次 | 使用的东西 |
| --- | --- |
| 语言 / 运行时 | Kotlin 2.4.0（JVM toolchain 17），打包内置 jlink 运行时 |
| 界面 | JetBrains Compose Multiplatform（Compose Desktop）1.11.1，Material 3，Skia 渲染 |
| 主题取色 | material-color-utilities（materialkolor）3.0.1 —— 动态取色与 9 种调色风格 |
| 序列化 / 协程 | kotlinx-serialization 1.7.3（JSON + Protobuf，用于同步省流通道）、kotlinx-coroutines-swing 1.9.0 |
| 音频 | ffmpeg（外部可执行文件）为主引擎，Java Sound + mp3spi / vorbisspi / jflac 为回退引擎 |
| 标签与封面 | jaudiotagger 3.0.1（读写内嵌标签、封面与歌词） |
| 系统集成 | JNA + jna-platform 5.17.0：WinRT SMTC（系统媒体浮层）与 Win32 `RegisterHotKey`（媒体键）；AWT `SystemTray`（通知区域托盘） |
| 扫码登录 | zxing 3.5.3（应用内渲染二维码） |
| 构建 | Gradle（Wrapper 已提交）+ Compose Desktop packaging：WiX Toolset v3 生成 MSI |

## 代码结构

```
.
├── ci/windows-build.yml        CI：Windows 构建（免安装 zip 工件；MSI 步骤在没有 WiX 时跳过）
├── src/main/kotlin/moe/ouom/neriplayer/desktop/
│   ├── Main.kt                 应用入口、窗口、悬浮歌词窗口与快捷键
│   ├── core/                   数据模型、Windows 目录布局、媒体库扫描、歌单与统计、
│   │                           播放引擎与播放器、歌词解析与仓库、系统媒体控制（SMTC）与界面缩放
│   ├── net/                    HTTP 客户端、网易云接口、哔哩哔哩 WBI 签名接口、扫码登录、
│   │                           外部歌词源（LRCLIB / 酷狗 / AMLL TTML DB）与评论
│   ├── sync/                   GitHub 同步：数据模型、序列化、Git 传输、合并策略
│   ├── ui/                    主题、通用组件、悬浮歌词、托盘与控制面板、同步与账号设置、各页面
│   └── tools/                 自检与界面自动化脚本
├── src/main/resources/         托盘图标等运行时资源
├── docs/screenshots/           界面截图
├── packaging/                  MSI 图标（neriplayer.ico）、PNG 图标与许可文件
├── tools/                      辅助脚本（make_demo_library.py：生成演示曲库）
├── build.gradle.kts            依赖、图标裁剪、Compose Desktop 打包（MSI / EXE）与验证任务
└── gradlew / gradlew.bat       Gradle Wrapper（Windows / bash）
```

## 已知限制

- 在线音源通过各平台公开接口访问，音频地址具有时效性；播放失败会自动提示并可重试。
- 哔哩哔哩收藏夹列表接口不返回封面，未打开过时显示占位图标，打开过一次后会缓存首条封面。
- 多 P 视频（合集）目前播放第一段音频轨。
- YouTube Music 需要 Google 账号授权与专用解析，桌面端保留入口与说明，暂未接入。
- 媒体库里的「QQ 音乐」目前只是入口占位（界面会显示「功能开发中」）。
- 免安装版不写注册表，不会出现在「应用和功能」里，也不会成为系统默认播放器；需要这些行为请安装 MSI。

## 许可

与原项目一致，以 **GPL-3.0-only** 发布（见 [packaging/LICENSE](packaging/LICENSE)）。

- 原 Android 应用：**NeriPlayer** —— [cwuom/NeriPlayer](https://github.com/cwuom/NeriPlayer)
- Linux 桌面版（本项目 Kotlin 源码与界面的来源）：**NeriPlayerForLinux** —— [Asternent/NeriPlayerForLinux](https://github.com/Asternent/NeriPlayerForLinux)
- 本仓库只做 Windows 平台适配，作者署名与版权仍归上述上游项目的贡献者所有；移植部分同样以 GPL-3.0-only 授权。

本项目仅供学习与研究使用，请遵守各平台服务条款；不提供任何媒体内容、密钥，
也不具备绕过付费 / DRM / 地区限制的能力。
