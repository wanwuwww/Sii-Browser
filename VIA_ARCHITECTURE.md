# Via 7.3.4 反编译架构地图（用于复刻 Sii 浏览器导航）

> 本文档基于 `via_replicate/` 反编译参考包生成（R8 混淆产物，包名/类名/资源名均为无意义短名）。
> 用途：作为逐个研究混淆类的导航图。所有结论均来自已读源码，不确定处已标注「未确认」。
> 约束：Via 是闭源软件，本图仅用于理解功能与交互，复刻 Sii 时用全新代码实现。

---

## 1. 总览

| 项 | 值 |
|---|---|
| 包名 | mark.via |
| 版本 | 7.3.4（versionCode 20260920） |
| 形态 | 基于 Android System WebView 的轻量浏览器，纯 Java/Kotlin，无原生库 |
| 入口 | `mark.via.Shell`（主 Activity，singleTask）、`mark.via.Trampoline`（透明中转） |
| 关键事实 | **主界面 UI 全部由 Fragment 承载**，Shell 只是容器壳 |

> **Sii 裁剪范围（用户已定）**：不做云同步（qb/WebDAV）、不做 AI 功能（p9 包）、不集成 Firebase 统计/崩溃上报（f2/l1 包）、不收集用户信息。以下文档中相关模块标注「Sii 排除」。

架构一句话：`Shell/CustomTab`（Activity 壳）→ `c8.s6`（BrowserFragment，主界面 9000+ 行巨型类）→ `c8.f8`（根容器）、`c8.ua`（网页/地址栏控制器）、`c8.tc`（网站设定掩码）；全局状态集中在 `pa.a` 门面单例。

---

## 2. 界面（Activity/Fragment）清单

### 2.1 Activity（Manifest 全部注册）

| Activity | 导出 | LaunchMode | 主题/形态 | 功能 |
|---|---|---|---|---|
| `mark.via.Shell` | 是 | singleTask | 全屏，支持 PiP，adjustResize | 主浏览器窗口。`MAIN/LAUNCHER/APP_BROWSER`，桌面快捷方式 meta（xml/g） |
| `mark.via.Trampoline` | 是 | —（taskAffinity 空） | Theme.Translucent.NoTitleBar（透明） | 外部链接中转：VIEW（http/https/via/inline/file/content）、SEND 文本、NFC NDEF_DISCOVERED。有 CustomTabs SESSION extra → 转 CustomTab，否则转 Shell |
| `mark.via.Search` | 是 | — | Theme.Translucent.NoTitleBar，label=@string/st（"在 Via 中搜索"） | 系统级搜索入口：PROCESS_TEXT / WEB_SEARCH → 转发 Shell（附 `mark.via.REFERER`） |
| `mark.via.CustomTab` | 否 | —（taskAffinity=.customtab） | persistNever，stateAlwaysHidden | Custom Tabs 模式独立窗口，与 Shell 共用同一套 Fragment（`c8.s6` 或 `ra.h`） |

关键判断：**四个 Activity 里只有 Shell 与 CustomTab 真正承载浏览器 UI**，Trampoline/Search 是 30-50 行的转发壳（已读全文核实）。

### 2.2 Fragment（真正的界面层）

| Fragment | 继承 | 作用 |
|---|---|---|
| `c8.s6` | Fragment + o8.b/c/d/e、wa、r4.f、e8.* 十余个接口 | **BrowserFragment 主界面**：WebView、地址栏、工具栏、手势、下载、文件选择、全屏视频、网站设定。根视图 = `c8.f8` |
| `o8.a` | Fragment + o8.b | **Fragment 基类**：y7.c 与 ra.h 都继承它，处理无障碍焦点 |
| `y7.c` | o8.a | 首次启动引导页（「同意并继续」，含使用协议/隐私政策可点击链接） |
| `ra.h` | o8.a | 独立界面 Fragment，3 个 TextView + 背景。Shell/CustomTab 在 `pa.r.o().f()` 开关为真时使用。具体形态未确认（可能是主页/新标签页欢迎界面） |

> Shell 的界面切换逻辑（已核实）：`onCreate` 里 `pa.r.o().f()` 为真 → `i.j(this, ra.h.class, ra.h.a3(1), null)`（替换式）；否则 → 添加 `s6` Fragment（tag=`s6`）。首次启动（`l.A2() >= 1` 判断）走主界面，否则显示 `y7.c`。`CustomTab.V()` 逻辑同 Shell。

### 2.3 layout 资源说明

资源名整体被 R8 混淆（`res/layout/` 下 40 个文件名为 `a.xml`…`a9.xml`；R.java 字段名 `f9101a`…）。**主界面无 layout XML**——`c8.s6` 的根视图是代码动态创建的 `new c8.f8(context)`（FrameLayout 子类）。已定位的 layout：

| layout 文件 | 内容 | 用途 |
|---|---|---|
| `ac.xml` | `com.tuyafeng.scanner.BarcodeView` + `MyViewfinderView` + TextView | 二维码扫描界面 |
| `u.xml` | TextView + ScrollView + MaxHeightLayout(ListView) + CheckBox + 按钮行 | 通用选择/询问对话框（含列表与复选项，如「选择一个目录」「权限询问」） |
| 其余 38 个 | 多为 LinearLayout/RecyclerView/ImageView 组合 | 对话框、列表项、Widget 布局，未逐一比对（需要时按内容反查） |

---

## 3. Shell 职责与子模块（已读 Shell.java 全文）

Shell 自身只做 6 件事，其余全部委托 Fragment：

| Shell 职责 | 实现 |
|---|---|
| 1. 主题/夜间模式 | `attachBaseContext`：`pa.r.j().I0()`（夜间模式配置字符串）→ `t1.p/t1.l`（AppCompat 配置）；`onCreate` 里 `setTheme(I.d() ? v.f14216a : v.f14217b)`（v=主题类，a=暗色主题，b=亮色主题） |
| 2. 设置生命周期 | `pa.r.a(this).v(this)` 注册（pa.a 门面 → Shell 引用）；`onConfigurationChanged` 响应系统暗色切换并遍历 Fragment 通知 `o8.b.b0()` |
| 3. 按键分发 | `dispatchKeyEvent` → 找 `c8.s6` Fragment 调 `s6.d8(keyEvent)`（s6 处理音量键翻页/游戏模式等） |
| 4. Intent 分发 | `onNewIntent`：遍历所有 Fragment，找实现 `o8.e` 的调 `a0(intent)`（深链/外部打开入口） |
| 5. 首次启动路由 | `l.A2()`（某版本/计数）≥1 → 进主界面（s6 或 ra.h），否则 → `y7.c` 引导页 |
| 6. 软键盘监听 | `SoftInputAssistObserver K`：软键盘弹出状态 → 通知实现 `o8.d` 的 Fragment `a(boolean)`（s6 用） |

**Shell 字段**：`l I`（实际类型 `w9.l`，设置接口）、`b J`（某启动任务管理器：`J.b().i()/f()/e()`，未确认用途）、`SoftInputAssistObserver K`、`Context L`、`boolean M`（首焦标记）。

### s6（BrowserFragment）字段 → 子模块映射（已核实字段区）

| 子模块 | s6 字段 | 类型/线索 |
|---|---|---|
| 根容器 | `f2751o0` | `c8.f8`（FrameLayout 子类：工具栏区/WebView 容器/进度条/图片按钮） |
| 网页+地址栏控制器 | `f2747m0` | `c8.ua`（1913 行：持有 wa、w9.l、u4.a、y9.a、ca.d、pb.b、o9.c、ka.g 等全部数据源；处理资源请求 `E0()`、设置地址栏 `i1()`） |
| 标签/网站设定掩码 | `R0 = new tc()` | `c8.tc`（`Map<String,Integer>` 位掩码，按域名记录开关位：拦截/放行/脚本等） |
| 手势 | `N0` + 内部类 `o` | `GestureDetector.SimpleOnGestureListener`（滑动前进后退、视频手势等） |
| 长按菜单 | `I1 = new g()` | `View.OnLongClickListener`（长按标记广告/链接菜单） |
| 亮度/进度滑块 | 内部类 `f` | `SeekBar.OnSeekBarChangeListener` |
| 下载 | `O0`（mark.via.download.j1）、`P0`（c5.a）、内部类 `m`（j5.c 回调）、`N1`（mark.via.download.e） | 下载管理器 + DownloadService 绑定代理 |
| 文件选择 | `E0/F0`（ValueCallback）+ `M1/P1/Q1/R1/S1`（androidx.activity.result.b） | 上传文件、取色、权限请求等 |
| 全屏视频 | `G0` | `WebChromeClient.CustomViewCallback` |
| 证书 | `A8()` → `L0.d()`（o4.a → r4.a） | SSL 证书信息 |
| 广播 | `D0` + 内部类 `n`（BroadcastReceiver） | 系统广播监听 |
| 悬浮工具栏 | `U1/s0/u0`（com.tuyafeng.support.widget.z/v/a） | tuyafeng 支持库的悬浮控件 |
| 自定义控件 | `A0/B0/C0/j1/u1/v0/x0`（mark.via.common.widget.t/m/f/k1/v0/g0/n0） | 地址栏、菜单按钮、进度条等 UI 组件 |
| WebView 回调接口 | 实现 `e8.n0.a`（进度）、`e8.k.a`、`e8.l.a`、`e8.c0.a`、`e8.i.a`、`e8.i0.a`、`e8.k0.a`、`e8.j.a`（资源请求）等 | e8 包是 WebView 回调接口集合 |

---

## 4. 功能模块 → 实现位置线索（strings 文案 → 类）

> 方法：strings.xml 的 key 被混淆，但 value 是真实文案；按语义分组后映射到已核实的类。标注「未确认」的表示已知功能存在、但具体类待逐个验证。

### 已定位（有代码佐证）

| 功能 | 关键文案（string key） | 实现位置 |
|---|---|---|
| 广告拦截 | ct 广告拦截、hj 内建规则、tv 规则语法、eu/ev 自定义规则、ja/jb 规则订阅、mi 拦截规则存储、b6 已拦截统计、g4/ml 标记广告 | **u4.a**（拦截引擎，返回空响应/1x1 gif；工厂 m() 组装 x4/a5/y4/z4）；入口 `c8.ua.E0()`（shouldInterceptRequest）；长按标记广告入口 s6 内部类 g |
| 脚本 | tj 脚本、se 代码、tw UserScript 帮助、s1 运行时机、rh/rq 依赖下载、za/zf 解析、GM_* API | `c8.ua`（注入与 GM_* 桥）；s6 import 了 `android.webkit.JavascriptInterface`；x7.u（文案类）含大量帮助文本 |
| 下载 | a0/a2 下载、bf 下载管理、d5 内建下载器、cc/cd 前台通知、fn/gt/gu 失败文案、go 下载目录 | `mark.via.download` 包（j1、e 等 21 个类）、`DownloadService`（前台服务+通知）、`DownloadReceiver`（PAUSE/RESUME/REDOWNLOAD/ALL 广播）、c5.a/c5.b（下载管理器单例与绑定） |
| 二维码 | qu 二维码、sb 扫描二维码、sc 扫图片、m6 链接过长、qv 未检测到 | layout `ac.xml`（tuyafeng.scanner BarcodeView）+ 调用方（未确认类） |
| 朗读 | r0 朗读网页、r1 朗读面板、un 开始朗读、uu 停止、db/dc 不支持 | `mark.via.service.ReadAloudService`（mediaPlayback 前台服务）+ `ReadAloudReceiver` |
| 桌面快捷方式 | bb 添加快捷方式、es 已创建、nw 权限拒绝 | Manifest 中 Shell 的 shortcuts meta（xml/g）+ 相关调用类（未确认） |
| 搜索工具栏/搜索引擎 | si/sq 搜索引擎、sy/sz 快捷词、t0/t1 快捷词错误、t2 搜索工具栏、hn 启用 | `mark.via.Search` 转发 + Shell 内搜索框（x7.p 等工具类）；搜索引擎配置在设置 |
| Custom Tabs | ga 禁用 Custom Tabs、gb 中转页 | `CustomTabsConnectionService` + `CustomTab` + `Trampoline.c()` |
| 网站设定 | tx/ty/tz/yw 网站设定、oo 仅当前网站、u3 不保存密码网站 | **c8.tc**（域名→位掩码）+ s6 中对应开关的读写；具体弹窗类未确认 |

### 数据层（已定位，数据库建表语句实证）

| 功能 | 存储包 | 表结构 | 数据接口（经 pa 门面） |
|---|---|---|---|
| 书签（文件夹树） | o9 | `bookmark_items`(_id,url,title,folder_id,ordering) + `bookmark_folders`(_id,title,parent_folder_id) | `o9.c`（pa.z() / pa.r.d()） |
| 书签（旧版扁平） | ea | `bookmarks`(id,url,title,folder,clickTimes) | `ea.c`（并入 y9.a） |
| 历史 | ga | `history`(id,url,title,updated_at) | `ga.c`（并入 y9.a） |
| 主页收藏 | fa | `favorites`(f_id,f_url,f_title,f_order) | `fa.c`（并入 y9.a） |
| 脚本/插件（addons） | ia | `addons`(id,oid,name,author,url,info,code,flag) | `ia.b`（并入 y9.a） |
| 规则/自定义条目 | ja | `settings`(id,title,content,note,flag,type) | `ja.b`（并入 y9.a） |
| 网站设定（按域名） | da→ca | `conf`(domain,data JSON) | `ca.d`（pa.e()）——此前误判为云同步，已更正 |
| 标签页恢复 | na | `tabs`(_id,url,title,file_path,flags,last_visited_at) | `na.h`（pa.m()） |
| 下载任务（分块） | f5 | `tasks` + `chunks`（完整分块下载字段） | f5 是内建下载引擎底层 |
| 密码管理器 | v9 | 无独立表（加密文件/偏好，v9.g 接口按域名存取 f 条目） | `v9.g`（pa.n()） |
| **AI 对话（Sii 排除）** | p9 | `backends`(models)/`threads`/`messages`/`suggestions` | `p9.o`（pa.A()） |
| **云同步 WebDAV（Sii 排除）** | qb | webDavConfig 常量 | 设置页直接引用 |
| **Firebase 分析/Transport（Sii 排除）** | f2/l1 | events/raw_events/transport_contexts 等 | 不接入，不收集用户信息 |

> y9.a（pa.a()）= ea(旧书签)+ga(历史)+fa(主页收藏)+ia(脚本)+ja(规则) 的聚合接口，供主页/起始页等一次性取数据。

### 功能实现定位（除数据层外的功能模块，均已实证）

| 功能 | 实现位置 | 证据 |
|---|---|---|
| 翻译 | `i6` 包（f0=Google 翻译 URL、e0=JS 注入，含 google translate + xnx3/translate 两种引擎）+ `f8.w0`（翻译面板：TRANSLATE_CACHE、源/目标语言） | URL/JS 常量实证 |
| 以图搜图 | `c8.s6` 内方法（Tineye/SauceNAO/IQDB/3D IQDB/Ascii2D 五个引擎 URL 在 3269-3293 行） | URL 常量实证 |
| 阅读模式/自动展开全文 | `c8.lb`（60+ 站点折叠规则 JS：CSDN/知乎/简书/百度系/微博等） | JS 规则实证 |
| 网页剪贴板拦截 | `c8.ya`（via-fake-clipboard JS，询问文案 %DOMAIN%） | JS 实证 |
| blob 资源下载 | `c8.a` + `s6`（window.via.download 桥接 JS） | JS 实证 |
| 浏览器 JS 接口 | s6 内 `window.via.*` 桥（cmd 514/515 等原生命令） | i6.o JS 实证 |
| 开源许可 | `hb.j4`（第三方库许可列表，含 xnx3/translate 等） | 实证 |

### 剩余未定位（功能存在，类待验证）

| 功能 | 关键文案 | 说明 |
|---|---|---|
| 资源嗅探 | rs/rr/ru/rt/zg/tr（资源日志/嗅探） | s6 中 f2772y1 Map 疑似资源日志；建议读 s6 时定位 |
| 更新检查 | dm/m3/o2 | 未确认（无硬编码 URL，可能查应用市场） |
| 举报/安全浏览 | re/yg/yh/r6 | 未确认 |
| 页面截图/打印/离线 | ar/s5/qr/at/as | s6 内方法（s6 有 print 相关 import） |
| 数据导出/导入 | ig/ie/if/kw/kz/f6/f7 | 未确认（可能 c8 包） |
| 皮肤/背景 | m9/u5-u9/o1/o3/tn | s6 中 Ab() 方法已见背景图逻辑（w9.l 存背景路径） |

> 注：剩余 6 项多为 s6 的菜单/对话框分支，建议在读 `c8.s6`（9000 行，按方法区间切块）时顺带定位，不必单独考古。

---

## 5. 模块依赖图（Mermaid）

```mermaid
graph TB
    subgraph 入口层
        BrowserApp[mark.via.BrowserApp<br/>Application]
        Trampoline[mark.via.Trampoline<br/>透明中转]
        Search[mark.via.Search<br/>系统搜索]
    end

    subgraph Activity 层
        Shell[mark.via.Shell<br/>主窗口 singleTask]
        CustomTab[mark.via.CustomTab<br/>Custom Tabs 窗口]
    end

    subgraph 门面层
        pa_factory[pa.p.K 工厂]
        pa_a[pa.a 门面接口<br/>全局单例]
    end

    subgraph 数据单例
        w9l[w9.l 设置接口<br/>405 个 getter/setter]
        w9r[w9.r 功能开关]
        u4a[u4.a 广告拦截引擎]
        y9a[y9.a 聚合数据<br/>书签ea+历史ga+收藏fa<br/>+脚本ia+规则ja]
        o9c[o9.c 书签树]<br/>
        cad[ca.d 网站设定]
        nah[na.h 标签页恢复]
        v9g[v9.g 密码管理器]
        f5p[f5 下载引擎底层]
        ex1[Sii 排除：p9 AI / qb 云同步<br/>f2·l1 Firebase 遥测]
    end

    subgraph Fragment 层
        o8a[o8.a Fragment 基类]
        s6[c8.s6 BrowserFragment<br/>主界面 9000+ 行]
        y7c[y7.c 首启引导页]
        rah[ra.h 独立界面]
    end

    subgraph 浏览器核心
        f8[c8.f8 根容器<br/>FrameLayout]
        ua[c8.ua 网页/地址栏控制器]
        tc[c8.tc 网站设定位掩码]
        widgets[mark.via.common.widget.*<br/>地址栏/菜单/进度控件]
        e8pkg[e8.* WebView 回调接口包]
    end

    subgraph 下载模块
        j1[mark.via.download.j1]
        dlsvc[DownloadService<br/>前台服务]
        dlrecv[DownloadReceiver]
        c5a[c5.a 下载管理器]
    end

    subgraph 服务层
        ctsvc[CustomTabsConnectionService]
        rasvc[ReadAloudService]
        widgetsvc[SearchWidgetProvider<br/>TransparentSearchWidgetProvider]
    end

    BrowserApp --> pa_factory --> pa_a
    Shell --> o8a
    Shell --> s6
    Shell --> y7c
    Shell --> pa_a
    CustomTab --> s6
    CustomTab --> rah
    CustomTab --> pa_a
    Trampoline --> Shell
    Trampoline --> CustomTab
    Search --> Shell
    pa_a --> w9l
    pa_a --> w9r
    pa_a --> u4a
    pa_a --> y9a
    pa_a --> o9c
    pa_a --> cad
    pa_a --> nah
    pa_a --> v9g
    pa_a --> f5p
    pa_a -.排除.-> ex1
    s6 --> f8
    s6 --> ua
    s6 --> tc
    s6 --> widgets
    s6 -.实现.-> e8pkg
    s6 --> j1
    s6 --> c5a
    ua --> w9l
    ua --> u4a
    ua -.回调.-> wa[s6 实现的 wa 接口<br/>浏览器宿主回调]
    dlsvc --> c5a
    dlrecv --> dlsvc
    ctsvc --> CustomTab
```

### 依赖关系要点

1. **Activity 只做容器**：Shell/CustomTab 不含业务 UI，全部在 Fragment；两者共用 `c8.s6`（CustomTab 带 `CUSTOM_TAB=true` 的 Bundle）。
2. **pa.a 是唯一全局入口**：所有单例通过 `pa.r` 静态方法取（`pa.r.j()`=设置、`pa.r.o()`=开关、`pa.r.c()`=广告拦截…），实际都转发到 `BrowserApp.e()`（pa.a 实现）。
3. **c8.ua 是浏览器大脑**：持有几乎所有数据单例，处理资源请求（广告拦截/脚本入口）、地址栏状态、网页交互；s6 通过 `wa` 接口反向回调（弹菜单、显示按钮）。
4. **e8 包 = WebView 回调接口集合**：s6 实现十余个 `e8.*.a` 接口（进度、资源请求、权限等），是 WebViewClient/ChromeClient 的桥接层。
5. **下载独立成模块**：`mark.via.download.*`（数据/UI）+ `DownloadService`（前台进程）+ `c5` 包（管理器），s6 通过 j5.c 接口接收进度。

---

## 6. 混淆包导航地图（供逐一研究）

> 顶层包共 100+ 个：`android/androidx/com/google/okhttp...` 是第三方库（勿动），`autodispose2` 是第三方 RxJava 绑定库；其余单字母/字母+数字包是应用自有代码。已知核心包：

| 包 | 作用 | 依据 |
|---|---|---|
| `mark.via` | 入口（Shell/Trampoline/Search/CustomTab/BrowserApp）+ R.java + common/widget 控件 + download + service/receiver/widget | 已读全文 |
| `c8` | **浏览器核心**：s6（主 Fragment）、f8（根容器）、ua（控制器）、tc（设定掩码）、wa（回调接口）、pc（日志）、其余 40 个类（对话框/菜单/子组件） | 已读关键类 |
| `pa` | 全局门面：a（接口）、p（工厂）、q（初始化）、r（静态访问器）、实现类 | 已读 a/p/r |
| `w9.r` | WebView 提供商检测（包名/版本/forceDark），非功能开关 | 已核实 |
| `w9.a`/`w9.b` | 网页行为位掩码（webflag 存储，位语义待设置页模块确认） | 已读 |
| `w9.k`/`w9.m` | 设置实现/SharedPreferences 封装（Sii 对应 SharedPreferencesSettings） | 已读 |
| `w9.i` | 设置 key 清单（Sii 对应 SettingsKeys，key 名未混淆） | 已读 |
| `w9.l` | 设置接口（Sii 对应 Settings，分组继承） | 已读 |
| `o8` | Fragment 基类与契约：a（基类）、b/c/d/e（生命周期/Intent 回调接口） | 已读 |
| `u4` | 广告拦截引擎（含 x4/y4/z4/a5 子类） | 已读 a |
| `e8` | WebView 回调接口集合（进度/资源/权限/JS 对话框等） | 由 s6 implements 推断 |
| `x7` | 工具与文案：u（大量中英文案常量）、k（颜色）、p/r/n（杂项工具） | 由引用推断 |
| `z8` | 工具包：c1（文件）、f、h、z1（ActivityResult）、l0/l3、t1（配置）等 | 由引用推断 |
| `g6` | UI 工具：e（主题色）、y（尺寸/dp）、q（系统状态）、i、h | 由引用推断 |
| `c5` | 下载管理器（a/b 接口与实现） | 由 DownloadService 推断 |
| `mark.via.download` | 下载数据/列表 UI（21 类） | 已列目录 |
| `j5/j8/d8` | 下载回调/队列相关 | 由引用推断 |
| `o9` | 书签树（bookmark_items/bookmark_folders） | 建表语句实证 |
| `ea` | 书签旧版扁平表（并入 y9.a） | 建表语句实证 |
| `ga` | 历史（history） | 建表语句实证 |
| `fa` | 主页收藏（favorites） | 建表语句实证 |
| `ia` | 脚本/插件存储（addons） | 建表语句实证 |
| `ja` | 规则/自定义条目（settings 表） | 建表语句实证 |
| `da`/`ca` | 网站设定（conf：domain→JSON） | 建表语句实证 |
| `na` | 标签页恢复（tabs） | 建表语句实证 |
| `f5` | 内建下载引擎（tasks/chunks 分块下载） | 建表语句实证 |
| `v9` | 密码管理器（接口 g，按域名存取） | password 引用实证 |
| `p9` | AI 对话（backends/threads/messages）**Sii 排除** | 建表语句实证 |
| `qb` | 云同步 WebDAV **Sii 排除** | webDavConfig 实证 |
| `f2`/`l1` | Firebase Analytics / Datatransport **Sii 排除** | 建表语句实证 |
| `y9` | 聚合数据接口（ea+ga+fa+ia+ja 组合） | pa.p 构造链实证 |
| `j6` | 手写 DI 容器（i7.a 工厂、j6.c.a 缓存等） | pa.p 构造链实证 |
| `f2/zb/v3` | README 提及的核心业务包（含 jadx 未还原方法） | 未逐类核实 |
| `ra/y7` | ra.h 独立界面、y7.c 首启引导 | 已读 |
| `com.tuyafeng.*` | 作者个人支持库：scanner（扫码）、support.widget（悬浮控件）、crash | 由 layout/代码推断 |
| `ca` | 疑似云同步客户端（ca.d 由 pa.r.k() 返回） | 推断，待核实 |

---

## 附：研究路线建议

1. 按依赖图从 `pa.a` 各数据单例入手（每个单例一个文档条目），弄清书签/历史/密码/网站设定/标签页归属——**已完成**（见第 4 节数据层表）。剩余未定位：阅读模式/资源嗅探/翻译/以图搜图等（第 4 节剩余表）。
2. 逐段读 `c8.s6`（9000 行，按方法区间切块），把 `e8.*` 回调接口与 s6 实现方法对应起来——这是 WebView 生命周期地图。
3. 读 `c8.ua`（1913 行）理解地址栏状态机与资源请求管线（广告拦截/脚本注入在此交汇）。
4. 功能验证法：改一个 strings 文案（如"广告拦截"）→ 在 `x7.u` 或资源引用处定位使用点 → 反向找调用类。
5. Sii 实施时直接跳过：qb（云同步）、p9（AI）、f2/l1（Firebase）包，以及 Manifest 中 com.google.android.gms.measurement / firebase 相关注册（BrowserApp.onCreate 里去掉对应初始化）。
6. 剩余 6 项（资源嗅探/更新/举报/打印/导出导入/皮肤）在读 s6 时顺带定位；真机运行时验证菜单项行为更直接。
