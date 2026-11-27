# Dtv 更新日志

> 本应用为免费客户端，更新请走应用内「检查更新」或官方发布渠道。
> 每版留档：源码备份在 _BACKUP_Dtv_v<版本>，APK 留档在 artifacts/apk/TVDouyinNative/。
> 版本号规则：十进制小进位（1.99 → 2.00 → 2.01）；versionCode 单调递增（Android 升级判断）。

## 2.40（versionCode 140）

**① 搜索改成「搜作者」——网页视频搜索在这台设备上确实修不通，改走唯一通的那条道**
- 先把结论摆明：抖音网页版的视频搜索三条接口（`general/search/single`、`search/item`、`challenge/search`）在本机与当前 IP 环境下**恒返回 `search_nil_type=verify_check`（0 条）**。逐项实测全部无效：带 a_bogus（含第三方成熟实现）、带 msToken / 网页签名 / 新 ttwid / 新 verifyFp、换 UA（Win Chrome / iOS Safari / 安卓 Chrome）、换域名（`www-hj`、`www5`）。决定性对照：同一 IP **不带登录 Cookie** 时返回的是 `2483 请先登录` 而不是 verify_check —— 卡的是「登录态 + 非浏览器环境」，不是网络全封。
- 还试过在 App 内开一个隐形 WebView，让抖音网页自己算 `__ac_signature` 再交给 HTTP 用：真机日志 0.8 秒就算出了签名，但页面本身落在「验证码中间页」，DOM 探测结果 `{"t":"验证码中间页","n":0}`，一个 `/video/` 链接都没有。这条路整个删掉（`AcSigner.kt` 已移除，搜索不再做 0.8～16 秒的无效预热）。
- 顺带纠正「接口自检」页的误判：候选 C `discover/search` 显示"条目数=2"是因为自检只数了最长的那个 JSONArray —— 那 2 条是**用户**，不是视频。`discover/search` 是搜人接口，返回 `user_list / challenge_list / music_list`，从来不返回作品。
- 真正可用且真有内容的就是它：实测同一 Cookie、不带任何签名 → `keyword=篮球` 10 位作者（路人王篮球 / 746 万粉）、`keyword=美食` 9 位作者、乱码词 0 位（正常空结果）。所以搜索页定位改成：**输入作者昵称 → 右侧列出作者 → OK 进作者页看他/她的全部作品**，接口与「我的关注」点作者完全同一套（`user/post`），不引入新依赖。

**② 搜索结果页按作者重做：头像 + 昵称 + 粉丝数/认证 + 简介**
- 结果区改两列作者卡（横向卡片），焦点态沿用统一描边；OK 直接进作者作品页（`sec_uid` + 昵称），返回键回到搜索结果而不是回首页。
- 服务端只给第一页 10 位作者（`offset=10` 实测 0 条），所以不放翻页，避免"拉到一半卡住"的假象；`tvCount` 如实显示「共 N 位作者」。
- 搜不到时不再抛"触发风控 verify_check"这种让人一头雾水的字样，改成明确指引：本版搜索按作者检索，请输入昵称（也可以先打拼音让联想词给出中文名）。
- 热搜榜 / 搜索历史 / 自绘字母键盘 / 联想词全部保留，中文词照样能从联想和热搜点进去。

## 2.39（versionCode 139）

**① 只保留「关注 / 推荐」两个频道，其余频道连同代码一起删除**
- 美食 / 游戏 / 影视 / 音乐 / 健身 / 萌宠 / 搞笑 / 旅行 / 生活 全部下线：分类关键词表、分类搜索加载（`loadCategoryFeed`）、风控兜底筛选（`categoryFallback`）、批次偏移 `categoryOffset`、兜底提示 `lastLoadFallback` 一并删除，不再有任何「搜索被风控 → 用推荐流筛选」的分支。
- 现在点「推荐」就是原生推荐流，点「关注」就是关注流，行为单一、可预期。

**② 左侧纵向图标导航与频道合并成顶部一行，全中文、无图标**
- 删掉整条 sidebar（Dtv 字样 + 5 个图标项），焦点不再往左侧跑。
- 新的一行（左 → 右）：`关注` `推荐` `我的关注` `搜索` `设置` `登录`，全部是同一套中文文字 chip，选中态、聚焦放大、按下回弹与原来一致；左右循环、↓ 进列表、网格 ← 回行首。

**③ 登录仍只有「手机推送 Cookie」一条路（扫码登录试过后废弃）**
- 本版开发中试过在电视内嵌网页、让抖音自己在屏幕上弹扫码登录框，最终放弃：抖音网页没有独立登录页地址，二维码只是主页面上的一层浮层；而且它对 App 进程内伪造的点击/按键一律不响应（只有真实遥控器输入才算数），做不到「进页面自动出码」，且老盒子跑网页版抖音不稳定（偶发卡死/被系统回收）。
- 所以登录逻辑保持原样：登录页左侧二维码 → 手机扫码打开 Cookie 推送页 → 粘贴保存；另有「手动输入 Cookie」兜底。本版对这段逻辑只做了文案微调。

## 2.38（versionCode 138）

**① 图文作品自动连播下一条，且不再露出上一段视频的残帧**
- 根因：轮播用 `(index + 1) % size` 取模，永远在本条图片集里转圈；切图文时只 `pause()` 了引擎，Surface 里最后一帧还在，图片层又只有图片本身不透明，于是「静止视频画面铺满屏、图文叠在上面」。
- 修复：放到最后一张后按播放模式推进下一条（单集循环才回到第一张）；进图文时 SurfaceView 置 INVISIBLE + 图文层加 #FF000000 黑底，切回视频再恢复；←→ 在图文里改成上一张 / 下一张。

**② 左右方向键现在能拖动进度**
- 根因：只要控制栏可见（按任意键就会可见），←→ 就被拿去在底栏按钮间挪焦点，`doSeek` 的代码永远走不到。
- 修复：←→ 固定为快退 / 快进 15 秒；只有焦点真的落在底栏按钮上时才移焦点。底栏按钮入口改为「暂停时按 ↑ 进、再按 ↑ 或返回退出」，提示文案改成「←→快进快退 · 菜单键更多」。

**③ 新增倍速播放 0.5×～2.0×**
- 菜单键（或触屏菜单按钮）→「播放倍速」：0.5 / 0.75 / 1.0 / 1.25 / 1.5 / 2.0，即时生效并记住，下次起播沿用。ExoPlayer、IJK 生效；系统播放器不支持，会明确提示切引擎。
- `playSpeed` 字段此前存在但没有任何入口，这次接上了 UI。

**④ 列表作品大量重复：按 awemeId 在适配器层去重**
- 根因：`VideoGridAdapter.replaceAll/append` 直接 `addAll`；关注/推荐流的 cursor 翻页会回绕吐同一条，首页因此出现同一作品多遍。
- 修复：适配器维护 `seen` 集合，替换和分页都过滤重复 id（空 id 不受影响）；因为宿主把同一个 `items` 实例交给适配器和播放页，去重对播放队列同样生效。

**⑤ 风控：只有「关注/推荐」能用，其它频道全挂的原因与回避**
- 取证结论：分类频道走的是无签名的 `general/search/single`。同一 Cookie、同一参数在 PC 上 20 条正常；设备上返回 730B 的 `search_nil_info=antispam_check`（0 条）。差异不在 Cookie（逐字相同），而在客户端指纹与请求频率（TLS/HTTP2 指纹、短时间连续翻页）。而 `/tab/feed`（推荐）、`/follow/feed`（关注）带 X-Bogus，不受这条判定影响 —— 正好对上「除关注、推荐外全不正常」。另实测「给搜索硬加 X-Bogus 反而必被判」，所以不能靠补签名绕过。
- 回避措施：搜索请求最小间隔 1.6 秒 + 同 keyword/offset 3 分钟缓存（砍掉突发量）；首次失败自动改用「浏览器式带 a_bogus」再试一次；仍失败则**降级到推荐流按频道关键词筛选**，频道不再空着，并在底栏如实提示「搜索被风控 → 已用推荐流筛选」；`antispam_check` / `verify_check` 现在会写进日志与占位文案。

**⑥ 各频道自动刷新 / 换一批**
- 菜单键（INFO 亦可）→「频道菜单」：「换一批 · 当前频道」「自动刷新 开/关」「打开设置」。
- 换一批：推荐流直接重拉（每批本来就是新内容）；关注流重拉首页；分类把批次偏移 +20 去拉下一批搜索结果。
- 自动刷新默认开：停在列表顶部时每 5 分钟自动换一批，翻页中/播放页前台一律不动。

## 2.37（versionCode 137）

**更新下载不再卡住整个应用：下载期间遥控器、播放、各页面一律照常可用**
- 根因：升级下载用的是 `ProgressDialog` 且 `setCancelable(false)` —— 对话框是独立窗口、独占输入焦点，7MB 下载期间上下左右 / 确定 / 返回全部落到对话框上，app 等于冻住，更没法切去看视频。
- 修复：
  - 去掉模态进度框，改为**后台线程下载 + 轻量 Toast 进度**（每 10% 提示一次），不抢焦点、不吃按键；
  - 下载结束**不再自动拉起系统安装器**（那会打断播放），改为记下「待安装版本 + 已下好的包」，首页在前台时就地弹「立即安装 / 稍后」，在播放页则只静默记录；
  - 下次进首页 / 点检查更新时优先发现本地已下好的包，直接给「立即安装」（另附「删除重下」），不必重新联网、重下 7MB；
  - 下载线程降到后台优先级，不跟视频抢 CPU/网络。

## 2.36（versionCode 136）

**① 弹幕改为全局等速：右边的长句不会再追上并盖住前面那句**
- 根因：单条速度 = (画面宽 + 文字宽) / 8 秒 —— 字越长跑得越快。同一条横道里先出的短句慢、后出的长句快，几秒后必然追尾，视觉上就是「右边的弹幕盖住左边那句」。
- 修复：速度只按画面宽度算（所有弹幕同速），间距在发射瞬间就由 3 倍字高的 gap 决定，之后再不会重合；横道空闲判定同步改为 `右边缘 <= 右边界 - gap`。

**② 图文作品改用原图加载：满屏不再糊成缩略图**
- 根因：图文全屏走的是列表封面用的 `ImageLoader.load()`，内部固定按 **480px** 采样（为省电视内存）。1536x2038 的原图被压到 480 宽再放大到满屏 ≈ 3 倍模糊。
- 修复：新增 `loadLarge()` 专用通道（`maxDim=2200`，原图不降采样，RGB_565 控内存 + 单张 12MB 护栏），图文轮播改走它；并预热下一张，4 秒切图不再等网络。
- 图片地址仍取 `url_list` 末尾的高清档（`q75.jpeg` 1536x2038），不再用缩略图。

## 2.35（versionCode 135）

**① 移除手机遥控模块（保留 Cookie 推送登录）**
- 删除 `WSServer`（8090 WebSocket 控制通道）、`DiscoveryServer`（UDP 发现）、`PlayerBus`（命令总线）、`SearchBus`（手机推搜索词）、`ExternalActions`（外部唤醒动作）。
- `ConfigServer` 由 44KB 瘦身为单一职责：只留推送页 `/`、`/save`、`/savecreds`、`/status`；`/remote` 遥控页、`/pair` 配对、`/auth`、`/clients*` 白名单、`/apk/*` 分发、`/cmd` 命令、`/hot`、`/search`、`/diag*` 全部删除。
- `App` 不再启动发现/WS 服务，删除按键注入 `sendKey`；`PlayerActivity` 删除状态快照、命令监听、推送视频、遥控搜索；`SearchActivity` 删除「手机输入」提示与总线注册；`LoginActivity` 删除配对码/自建服务残留；`Prefs` 删除配对码、tv_uuid、手机白名单。
- **Cookie 推送登录链路完整保留**：登录页二维码 → 手机浏览器打开 `http://电视IP:8088` → 粘贴 Cookie → 保存，电视自动刷新为「已登录」。

**② 移除 APP 开发作者相关信息**
- 「关于」页：删除作者名、作者主页二维码、`about_qr` 资源，以及「请仅从作者官方渠道下载」「扫一扫直达作者主页」等提示；保留版本号与签名指纹。
- 设置页「关于」入口文案改为「版本号与签名指纹」；签名不一致的警告改为中性表述。

**③ 新增弹幕显示行数 / 位置设置**
- 设置页新增「弹幕显示」分组：弹幕行数（1~5，默认 3 行）、弹幕位置（顶部/中部/底部，默认屏幕顶部）。
- `DanmakuView` 改为按 `laneCount` 动态分道，`posMode` 决定整块弹幕的落点；切视频/开关弹幕时从 `Prefs` 重新读取，改完立即生效。

**④ 「我的关注」作者有作品却不显示数量 / 不能播放（换方案彻底解决）**
- 数量：关注列表接口 `/user/following/list/` 返回的作者对象里 `aweme_count` 恒为 0（实测 TRIN 服务端不给该字段）→ 关注列表只能显示「查看作品」。新增 `/user/profile/other/` 补数：逐位串行补齐（140ms 间隔防风控、失败重试一次、结果内存缓存），列表实时刷新成「20 个作品」；作者页标题同步显示真实作品总数。
- 播放：实测 TRIN 整页 20 条全是 `aweme_type=68`（图文），`video.duration=0`，但 `video.play_addr` 里仍有 1~2 条「伪视频」地址 —— 旧逻辑只要 `play_addr` 非空就当视频播，拿到一条时长为 0 的流，播放器必然起不来。现在带图且（`aweme_type=68` 或 时长为 0）一律判为图文并走图片轮播；同时把图片地址从 `url_list[0]`（缩略图）改为取末尾最大图，电视上不再糊。

## 2.34（versionCode 134）

**① 弹幕开着却不显示（核心修复）**
- 根因：`DanmakuView` 只有动画循环（tick / maybeSpawn），**从来没有 `onDraw`** —— 文案拉到了、items 里也有条目、定时器一直在跑，但一帧都没画到画布上。日志里评论区明明拿到了 29 条，屏幕上却空空如也。
- 修复：补齐真正的绘制（黑描边 + 彩色字，2px 偏移保证亮背景可读），并顺带优化排版：6 条横道按「上一条已飞远」挑选防重叠、字号随屏宽自适应（1920 → 41px）、单条文字截断 34 字（抖音评论常是长段落）、横穿 8 秒、每 180ms 发一条。
- 弹幕只在**真实画面区域**内飞：竖屏视频两侧的大黑边上不再出现文字（`PlayerActivity.updateDanmakuBand()` 按 SurfaceView 布局 + 图层缩放反推可视矩形，每秒自检一次，值不变时零开销）。
- 评论区为空时不再静默：提示「弹幕：本视频评论为空」并写日志，能区分「没评论」和「功能坏了」。

**② version.json 构建时自动生成**
- 新增 Gradle 任务 `writeVersionJson`，挂在 `assembleDebug` / `assembleRelease` 之后（`gradle assembleDebug` 即自动跑）。
- 字段全部由真实产物推导：versionCode/versionName 取 `defaultConfig`；sha256、size 取实际 APK；notes 取 CHANGELOG 本版章节；apkUrl 走 GitHub Release 规则（可用 `-PdtvApkUrl=...` 覆盖）。彻底避免清单与包错位、sha256 手填漏填。

## 2.33（versionCode 133）

**① 三类播放器竖屏视频变形（系统 / IJK / Exo）**
- 根因：`applySurfaceScale` 的「极端比例保护」用 `sx < 0.5f || sy < 0.5f` 当判据。9:16 竖屏在 16:9 屏上 `sx = 0.5625/1.7778 = 0.316`，必然触发保护 → 直接放弃图层缩放、拉满全屏，画面横向拉宽 3.16 倍。
- 修复：改为按等比缩放后的**实际像素尺寸**判定（`isUnusableScale`：`viewW*sx < 120px || viewH*sy < 120px`），只有 1:20 长图这类真·细线才放弃等比；`resyncGeometryIfNeeded` 对拉伸类引擎提前 return，避免每秒自愈刷日志。真机三引擎实测均为等比 + 黑边。

**② 删除播放历史 / 收藏模块（含全部关联代码）**
- 删除 `HistoryActivity`、`CollectActivity` 及其布局引用、两个侧栏图标资源。
- `PlayerActivity`：收藏按钮、菜单「收藏」项（后续菜单索引整体前移）、遥控 `collect` 命令、全部历史/收藏上报、本地换链/重试/起播提速分支、邻接视频预取机制（`prefetchNeighbors` 等）。
- `Prefs`：收藏读写、观看历史（HISTORY_MAX/addHistory/getHistory/clearHistory 等）；保留点赞与搜索历史。
- `ConfigServer`：`/lists` 路由、`history`/`collect` 打开页、status 里的 `collected`、手机页收藏按钮与「历史/收藏」卡片。
- `FeedBus`/`PlayerBus`/`WSServer` 的 SOURCE_HISTORY/SOURCE_COLLECT 与命令白名单同步清理；`DouyinApi.collectVideo` 删除。

**③ 在线更新（GitHub Releases）修正**
- 缺 `REQUEST_INSTALL_PACKAGES` 权限：Android 8+ 无此权限时 `startActivity(APK)` 只会静默失败，安装器根本不弹（实测本机 Android 10 盒子确实被拦）。
- `file_paths.xml` 把 `path` 写成具体文件 `update.apk`：FileProvider 要求 URI 文件位于 root 之下，直接指向文件会抛越界异常（`length=48; index=49`）→ 安装器永远唤不起来。改为 `path="."`。
- 下载加镜像回退（github 直连 → ghfast.top → gh-proxy）+ APK 合法性校验（≥3MB 且 PK 头），避免网关拦截返回 HTML 被当安装包。
- 版本号换算修正：`versionNameToCode` 旧实现把 "2.33" 算成 233，与真实 versionCode 133 差 100，走 versionCode 兜底会「永远有新版」；现按 build.gradle 十进制小进位规则（(major-1)*100+minor）。
- 版本字符串容错（`v2.33` / `2.33-beta` / `2.33.1`）；多 APK 时优先 32 位包。
- 「稍后」记住该版本，不再每次启动弹窗；下载加进度条；缺安装权限时自动跳系统授权页并提示。

**④ 全代码审查附带修复**
- 手机遥控页 `pair()` 读服务端不回显的 `j.code`（显示 undefined）→ 改为本地文案。
- 手机遥控 APK 安装统一走 `UpdateChecker.install`（带合法性校验 + 权限检查）。
- 清掉历史/收藏遗留的死注释与死代码。

## 2.31-fix6（本地修复版，未升 versionCode）fix5 回归修复

**背景**：fix5 引入两处回归 —— 默认线路1全部缓冲、换线路2后画面变形。本版回退并改用正确解法。

**① 默认线路1全部视频无法播放（一直缓冲）**
- 根因：fix5 把裸 `/aweme/v1/play/` 接口地址塞进 `candidateUrls()` 当线路1。播放器（尤其 Exo）请求该接口时带不齐 Cookie/Referer、302 跨域后 headers 丢失 → 拿到风控/登录页 → 一直缓冲。
- 修复：`candidateUrls()` 回退到 fix3 逻辑，不再把裸 play 接口喂给播放器。该接口只能由 `refreshViaPlayApi`（OkHttp 带完整 headers + Cookie）解析成 CDN 直链后使用。

**② 手动换线路2后很多视频变形**
- 根因：fix5 把 Exo 分支从 `applyAspect(ratio)`（约束 AspectFrame 容器）改成"交给引擎 SCALE_TO_FIT"；但 Exo 的 SCALE_TO_FIT 只对 TextureView 生效，对本项目 SurfaceView 无效 → 画面被拉伸。同时删掉了极端比例保护。
- 修复：Exo 分支恢复 AspectFrame 容器约束 + 比例自愈原逻辑；`applySurfaceScale` 恢复极端比例（sx/sy<0.5）放弃缩放保护。

**③ TRIN 作品仍不能播（改用正确解法）**
- 黑屏看门狗无首帧时优先调用 `refreshUrlAndReplay` → `refreshViaPlayApi`（带 Cookie 请求官方 play 接口拿新鲜 CDN 直链）重播；去重集合用 `bf_` 前缀 key 避免与其它刷新冲突。不再依赖裸地址或盲目换引擎。

**④ 发布日期改为完整「年-月-日」**
- `formatPublishDate` 由「同年内省略年份」改为始终 `yyyy-MM-dd`（如「发布于 2024-03-15」）。

> 验证：本环境无 Android SDK / kotlinc，无法本地编译；5 个改动 Kotlin 文件括号平衡（词法级）、符号引用、重复声明均已静态校验通过。更新功能本版未改（沿用 fix5），真机验证后如有问题再反馈。

## 2.31-fix5（本地修复版，未升 versionCode）五项修复

**① 历史/收藏起播慢 —— 直接取「最新连接」**
- 根因：起播先拿当初存的过期签名直链，卡满看门狗/等预取才换新地址。
- 修复：`DouyinApi.stablePlayUrl(awemeId)` 现场构造长效播放接口地址（每次请求 302 到当前有效 CDN，签名永远新鲜）；`candidateUrls()` 把该地址置顶，历史/收藏首播直接用最新连接，无需等待。

**② TRIN 等图表作品有声音无画面 —— 换线路而非只换引擎**
- 根因（第 3 版才找到）：默认播的是 bit_rate 选档地址（解不出画面），官方抖音用 `/aweme/v1/play/` 主链（必播）。旧版无首帧只换引擎，三个引擎拿到**同一条坏地址**→ 都没画面，所以「换播放器没用」。
- 修复：`candidateUrls()` 对所有来源置顶长效主链地址；黑屏看门狗改为先遍历所有线路（含官方地址）、全试完才换引擎；重试上限 = 线路数 × 引擎数。

**③ 赞/评论后增加作品发布日期**
- `VideoItem` 加 `createTime`（解析 `create_time`）；历史/收藏/恢复点均持久化 `ct`；播放页信息栏在赞/评论后显示「发布于 MM-dd」（跨年补年份）。

**④ APP 更新改到用户的 GitHub Releases**
- 更新源由 `Alone2235/DTV` 的 version.json 改为 `https://github.com/zxsjc/douyintv/releases`（Releases API 读最新 tag/APK/说明，SHA-256 从 digest 取）；保留自定义 version.json 兼容；版本比较改为语义化「2.31 > 2.9」，避免误报。

**⑤ 视频有时变形**
- 两处根因：`applySurfaceScale` 对极端比例「放弃缩放让画面铺满」= 拉伸变形；Exo 引擎「容器约束 + SCALE_TO_FIT」双重缩放冲突。
- 修复：始终等比缩放（留黑边不拉伸）；Exo 统一为容器铺满 + 引擎单层等比；比例自愈逻辑同步修正。

> 验证：本环境无 Android SDK / kotlinc，无法本地编译；5 个改动 Kotlin 文件括号平衡（词法级）、符号引用、构造参数数量均已静态校验通过。请用 Android Studio 执行 `:app:assembleDebug` 真机验证。

## 2.31-fix4（本地修复版，未升 versionCode）编译错误修复

- `DouyinApi.kt` 的 `refreshViaPlayApi()` 引用了 `REFERER`，但本文件（object DouyinApi）未定义该常量；同包其它文件里的 `REFERER` 均为 `private`（file-private / 成员私有），跨文件不可见，导致 `:app:compileDebugKotlin` 报 `Cannot access 'val REFERER: String': it is private in file`。
- 修复：在 `DouyinApi` 内补齐自有常量 `private const val REFERER = "https://www.douyin.com/"`，不改动其它文件。已做跨文件私有常量交叉扫描，无同类隐患。

## 2.31-fix3（本地修复版，未升 versionCode）三项问题修复

**① 关注实际 35 人却只显示 21 人（上一版改错了方向，本版纠正）**
- 真机实测结论（原代码注释已写明）：该接口**忽略 offset**、一页最多给 21 条，翻页唯一可靠游标是 `max_time`。
- 上一版把 offset 当主游标递增 → 服务端每次都返回第一页那 21 条、去重后 fresh 为空 → 提前停在 21。
- 本版纠正：`offset` 固定 0、`max_time` 始终带上作唯一游标；批量同秒关注打滑时（本页新增 < 5）改用本页 `pageMinTime - 1` 跳过整簇；`has_more` 默认恢复 1；放宽停止条件（不再因单次 has_more=0 急停），补页上限 40 页 / 200 人。

**② TRIN 等作者视频「有声音无画面」**
- 根因：黑屏看门狗用 `posAdvancing` 判定「有画面」——但**音频在解码时 position 照样前进**，于是「有声音无画面」被误判为正常、永不自救。这类作品多为特殊编码，IJK 解不出视频帧但音轨正常。
- 修复：无 `onVideoSize` 首帧回调时不再看 position，改为**换引擎重播**（不同引擎解码器实现不同，更可能解出画面）；用按 awemeId 的计数器限制最多把所有引擎各试一遍，防无限循环。
- 加固：`applySurfaceScale` 对极端宽高比（缩放系数 < 0.5）放弃图层缩放、让画面铺满，避免长图/图表类作品被缩成一条线导致全黑。

**③ 历史/收藏起播仍慢**
- 根因：持久化只存主链 `u`、**没存备用线路 altUrls**，主链签名过期后「换线路」只在一条死链上循环；且起播先用死链卡满 8 秒看门狗才刷新。
- 修复：历史/收藏存取均带上 altUrls（含长效 `/aweme/v1/play/` 地址）；`candidateUrls` 对本地源把长效接口地址排首位（一次 302 拿新鲜 CDN）；新增 `refreshViaPlayApi` 最快通道（长效接口一次轻量 GET 直取新鲜直链，附带 CDN 域名校验防风控跳转）；起播卡死看门狗对本地源 8 秒→3 秒；刷新成功后同步新 address/图片/备用线路回本地记录。

> 验证：本环境无 Android SDK / kotlinc，无法本地编译；6 个改动 Kotlin 文件括号平衡、XML 格式、符号引用均已静态校验通过。请用 Android Studio 执行 `:app:assembleDebug` 真机验证。

## 2.31-fix（2026-11-25）四项体验修复（本地修复版，未升 versionCode）

**① 关注列表只显示 30 人 / 宽广度TRIN 显示无作品**
- 关注列表改用服务端 `offset` 为主游标（原 `max_time` 单游标在批量关注时原地打滑），`sec_uid` 缺失时用 `uid` 兜底，自动补页上限 60→200，`has_more` 判定放宽。
- 作者作品解析关闭「仅横屏」过滤，并为 `/aweme/post` 增加精简参数兜底重试 → 竖屏作者不再误判「没有作品」。

**② 左侧纵向焦点图标不居中**
- 7 个导航项由 `TextView+drawableTop` 改为 `FrameLayout+ImageView(layout_gravity=center)`；`MainActivity` 对应控件类型由 `TextView` 改为 `View`，消除 `ClassCastException`。

**③ 横向标签切换后焦点框线异常 / 图片大小不一致 / 快按焦点飞到左侧**
- 卡片焦点描边由三层（背景柔光+主体描边+前景 3dp）收敛为单层 2dp（新增 `card_focus_bg.xml`），框线干净统一。
- 移除重复的一层 `ItemDecoration`（卡片自带 8dp margin，最后一列曾因少一层而更宽），封面高度改为按实际列宽实时计算并随布局变化重算 → 同屏图片尺寸一致。
- 焦点转交改为同步；标签/网格容器按 ↓ 时由 `dispatchKeyEvent` 直接把焦点交给首张可见卡；第一列卡片显式设置左键焦点目标 → 快速连按焦点不再外逃到侧栏图标。

**④ 历史/收藏旧记录加载慢、多次换播放器/线路失败**
- `refreshVideoUrl` 改为复用带会话缓存的 `cachedAuthorPosts`（原手写翻页既不写缓存也不复用，同一作者每条各翻十几秒），翻 5 页覆盖更多旧记录；缓存改 `ConcurrentHashMap` + 按作者加锁。
- 详情快速通道由「失败永久禁用」改为 60 秒冷却后自动重试；详情解析关闭横屏过滤，避免一条竖屏旧记录把通道废掉。
- 手动换线路（菜单/遥控）对历史收藏先换新解析地址，不再在过期直链间空转；自动换引擎不再写 `Prefs.setEngine` 污染用户记忆，并重置线路/重试计数。

> 验证：本环境无 Android SDK / kotlinc，无法本地编译；Kotlin 文件括号平衡、XML 格式、符号引用均已静态校验通过。请用 Android Studio / Gradle 执行 `:app:assembleDebug` 真机验证。

## 2.31-fix2（2026-11-25）四项修复（本地修复版，未升 versionCode）

**① 低配盒子播放一段时间卡死 / 变慢（重大）**
- 根因：`progressTick` 每 500ms 在主线程调 `Prefs.isDigged/isCollected`，而这两个函数每次都 `readText()` 整个 json 再解析 —— 老 eMMC 盒子上等于每半秒两次磁盘 IO，主线程被拖死。改为进程内缓存（进视频读一次、切视频才重读，点赞/收藏切换同步更新），tick 只读内存。
- `memTick` 写盘由每 10 秒主线程整文件读写改为每 30 秒一次且挪到后台线程池。
- 预取地址由「每位作者 new Thread」改为复用固定线程池，连播历史/收藏不再无限累积线程；`onDestroy` 统一关闭。

**② 焦点分区（纵向上下、横向左右、纵向右键进标签）**
- 根因：纵向导航右键一律指向 `gridView`，从图标右移直接掉进列表；且各区无显式焦点链，靠框架猜测会串区。
- 建立完整三区拓扑：纵向 7 项上下循环、右键进当前选中的横向 tab；动态 tab 显式分配 id 并左右循环、上进首页图标、下进列表；列表左键回 tab。切换分类时自动重新接线。

**③ TRIN 等图文/图集类作品仍不能播放**
- 真正根因：`parseItem` 硬性要求 `video.play_addr` → 抖音「图文」作品（无视频流，只有 `images[]`）被整条丢弃，作者主页里表现为「有作品却显示没有 / 点进去放不出来」。
- `VideoItem` 新增 `imageUrls` 字段；`parseItem` 无 play_addr 但有 `images` 时构造图文条目；作者作品接口与详情接口复用同一解析，自动覆盖。
- 播放器新增图文轮播：全屏 `ImageView` 每 4 秒切一张、支持暂停/上下滑，进度按「第几张/共几张」显示；图文也记历史。历史/收藏/恢复点的序列化全部带上 `imageUrls`（含刷新与冷启动恢复）。

**④ 历史/收藏旧记录启动仍慢**
- 追加修复：`playCurrent` 里预取到的新地址原本在 `rawUrl` 取出之后才 merge 进列表 → 当次仍用过期直链、必然失败再等看门狗。改为先 merge、再取 URL，预取一旦就绪当次即用新地址。
- 进入播放页即（与引擎初始化并行）启动历史/收藏预取，不再等 8 秒看门狗才发现地址过期。
- 预取结果接受条件放宽到「有视频地址 或 有图片列表」，图文作品刷新地址也能被缓存复用。

> 验证：本环境无 Android SDK / kotlinc，无法本地编译；Kotlin 文件词法级括号平衡、XML well-formed、VideoItem 构造参数个数与符号引用均已静态校验通过。请用 Android Studio / Gradle 执行 `:app:assembleDebug` 真机验证，重点实测四项交互。

## 2.20（2026-10-13）签名统一 —— 修复「每版签名不同无法更新」

- ★ 生成本项目固定签名密钥 dtv.keystore（随仓库提交，本地/CI/发布共用）
- debug 与 release 构建现在都用同一把签名 → 本地构建 = 云端 CI = Release 产物，签名不再漂移
- 更新链路恢复：旧版提示升级、下载后能正常覆盖安装（无「签名不同」报错）
- 注：改用新签名的**第一次**需要先卸载重装一次（旧 debug 签名无法覆盖），之后永久正常

## 2.19（2026-10-13）安全加固 + 外部唤醒入口

**P0 安全修复（局域网攻击面）**
- ★ /pair 原响应会**回显配对码** → 局域网任何人调一次即可拿到码，配对形同虚设。现在**绝不回显**
- /pair 加失败限速：连续 5 次失败锁定 60 秒并**作废旧码重新生成**（防 6 位码爆破）
- /lists、/hot、/save、/savecreds 原来**无鉴权** → 补齐 uthed()（未授权一律拒绝）
- ndroid:allowBackup 由 true 改为 **false**（防 db backup 导出 Cookie，无需 root）

**外部唤醒入口（此前只能从 App 内点）**
- 新增可外部调用指令：checkupdate（检查更新并弹窗）、upgrade（直接下载安装）、
  settings / bout / home（拉起对应页面）、exit（退出）、
eload（重载视频）
- 配合 2.18 的方向键/OK/返回/菜单注入，遥控端与外部工具都能完整驱动电视端

## 2.18（2026-10-13）补齐遥控器缺口：方向键 / OK / 返回 / 菜单

**手机遥控 → 电视界面操作（此前只能操控播放）**
- 电视端新增按键注入：App.sendKey() 跟踪前台 Activity 并注入 KeyEvent（DOWN+UP）
- 新增指令（WebSocket 8090 与 HTTP /cmd 双通道同语义）：
  dpad_up / dpad_down / dpad_left / dpad_right / ok / ack / menu / key(任意 keycode)
- 新增 seekto（arg=毫秒）：精确跳转进度，替代只能 ±10s 的 seek
- 无前台界面时返回 
o-foreground，遥控端可据此提示

## 2.17（2026-10-13）开源 + 在线更新渠道

**开源与发布基建**
- 项目开源至 GitHub：<https://github.com/Alone2235/DTV>（MIT）
- 新增 `README.md`：功能、编译方式、目录结构、更新机制说明
- 新增 GitHub Actions 工作流 `.github/workflows/android-build.yml`：push 编译出包、**推 tag 自动发 Release 附 APK**
- 新增一键发布脚本 `tools/publish.ps1`：升版本 → 编译 → 上传代码 → 更新清单 → 发 Release + 传 APK
- `version.json` 在线更新清单（仓库根目录）

**在线更新（App 内）**
- 新增 `UpdateChecker.kt`：启动 3 秒后静默拉取 `version.json` → 比对 `versionCode` → 有新版本弹窗（版本号 + 更新说明）
- 下载 APK 到 `filesDir/update.apk`（带进度）→ 走 FileProvider 调起系统安装器
- 清单地址支持覆盖（`Prefs.setUpdateUrl`），内置 jsDelivr 镜像常量备用
- 未配置仓库地址时静默跳过，不影响启动

**2.16 已并入**
- 手机遥控底层：UDP 发现（8089）/ HTTP 配置升级（8088）/ WebSocket 控制（8090）/ 配对码白名单
- 弹幕支持；播放黑屏自动重载修复；倍速、线路、循环等遥控指令对齐

## 2.13（2026-10-12）UI/动效大改版 · 第 1 步：统一动效系统

**动效系统（FocusAnim）**
- 新增统一动效工具 `FocusAnim`：焦点缩放（1.06→1.10x，**ease-out 140ms**）、淡入（220ms）、按下回弹（160ms）
- 播放器底栏 8 个功能键接入：焦点动画带缓动曲线（原来生硬直切）+ 按下回弹反馈
- 全应用焦点手感收敛到同一入口，后续主界面/卡片/侧边栏逐步接入（大改版进行中）

**2.12 已修（并入本版）**
- 手机端搜索传参 bug（/cmd 读 kw 改 arg，HTTP+WS 双通道修）+ 自动拉起搜索页
- 手机「重载」命令播放器无处理 → 补 playCurrent()
- WS 通道搜索不拉起页面 → 补拉起
- APK 分片上传：兼容 NanoHTTPD postData 字符串/文件路径两种形态（大分片不再失败）

## 2.10（2026-10-11）手机客户端 TV 底层（第 1 阶段）

**UDP 设备发现（8089）**
- 手机广播 `DTV_DISCOVER` → 电视应答 `{app,uuid,name,version,port}`，自动发现同 WiFi 设备

**白名单授权（配对即长期授权）**
- 配对码校验通过时，手机 `client_uuid` 写入电视白名单（`/pair?code&client_uuid&name`）
- `/auth?uuid=` 握手鉴权；`/clients` 列表 / `/clients/del` 删除 / `/clients/clear` 清空（电视侧管理）
- `/cmd` 与 `/apk/*` 支持「配对码 或 白名单」双通道鉴权（`authed()`）

**WebSocket 控制通道（8090，RFC6455 极简实现）**
- `ws://电视IP:8090/?uuid=<client_uuid>`，仅白名单放行
- JSON 命令：next/prev/digg/collect/toggle/pause/play/seek/line/engine/danmaku/loop/landscape/speed/search/push/volumeup/volumedown/mute/home

**播放倍速**
- Engines 新增 `setSpeed()`：Exo（PlaybackParameters）/ IJK（setRate）生效，系统播放器空实现
- 手机 `speed` 命令循环 0.75/1.0/1.25/1.5/2.0；快照带上当前倍速

**APK 分发（升级安装）**
- `/apk/info`：版本/大小/剩余空间；`/apk/upload`：分片续传（offset 追加）
- `/apk/install`：FileProvider 唤起系统安装器（4.2 无 root，需电视上手动确认）

**其他**
- `/status` 增加 version / uuid / allowCount；分享推送逻辑抽为静态 `pushVideoStatic`（WS/HTTP 共用）
- 版本号 2.10（versionCode 110）

## 2.09（2026-10-11）

**弹幕功能**
- 新增轻量弹幕层 `DanmakuView`（纯 Canvas，无第三方库，API 17 兼容）：
  评论文案从右向左飞过，5 条横道随机入道、多色（白/黄/品牌红/绿/蓝/橙）、黑描边保证可读
- 数据源 = 当前视频评论区（30 条封顶），切视频自动换文案；暂停/离开前台自动停帧
- 开关在播放器菜单「弹幕 · 开/关」，状态记忆（Prefs）

**手机遥控客户端（第一版：局域网 Web 客户端 + 配对码）**
- 配置服务升级为 **App 级常驻**（App.onCreate 启动，登录页不再独占 8088 端口）
- **6 位配对码**（首次自动生成/可重生成）：命令接口全部校验配对码，防同网段乱控
- 手机端页面 `http://<电视IP>:8088/remote`：
  - 配对（6 位码）+ 状态展示（当前视频/作者/进度条/播放中/引擎/弹幕）
  - **全屏触控板**：轻触播放暂停、左右滑上/下一条、上下滑快退/快进（10s）
  - 快捷按键：上一条/播放暂停/下一条 + 赞/藏/弹幕/线路/引擎/循环/横屏/静音/音量±/首页
  - 搜索（关键词 + 热搜词块）、推视频（分享链接或 aweme_id）
  - **观看历史 / 我的收藏列表**（封面+标题，点一条即推送到电视播放）
- 播放页新增遥控命令通道 `PlayerBus`（命令 post 回主线程执行）+ 播放状态快照
- DouyinApi 新增：分享链接解析（短链跟随 302）、aweme_id 提取、单视频详情拉取

## 2.08（2026-10-10）

**播放黑屏自动重载修复（低配+高配全设备）**
- **根因**：播放页按视频比例调整画面容器尺寸（AspectFrame）→ 触发 SurfaceView
  的 Surface 销毁/重建 → 非 Exo 引擎（IJK/系统）不托管 Surface，重建 = 整段重播；
  Exo 也伴随黑帧闪屏。这就是「播放中黑屏然后自动重新加载」的机制。
- 修复：
  - **比例容器每视频只应用一次**（aspectAppliedToken 防抖），杜绝多次 onVideoSize
    回调反复重建 Surface
  - **非 Exo 引擎跳过容器缩放**：IJK/系统引擎不再被 Surface 重建逼着重播；
    Exo 由 setVideoSurfaceHolder 托管，重建仅重绑不重播
  - **黑屏看门狗**：起播后播放位置正常前进但首帧（onVideoSize）迟迟不到 = 黑屏
    → 自动重载一次（每视频最多一次），把「黑屏卡死」变成「黑屏后自动恢复」；
    缓冲中不触发，避免误杀
  - **内存自杀探针只对老设备生效**（DeviceProfile.isLegacy）：那套 native/FD/RSS
    阈值是按 IJK 泄漏的老电视调的，高配机器正常值就高，会误触发「整个 App 重载」
    → 高配只靠连播计数兜底（20 → 60 次）

**点赞/收藏纯本地化（用户拍板）**
- 服务器侧 WebProtect 方案太麻烦且实测易风控（踢下线网页端），
  改为**纯本地记忆**：点赞/收藏只记本机状态，用于回显与收藏页，不再发任何请求
- 彻底告别：写操作不再碰服务器 → 不会再踢掉浏览器登录态

## 2.07（2026-10-10）

**WebProtect / bd_ticket_guard 凭据链路打通（真实凭据分析后落地）**
- 依据用户提供的完整浏览器 Cookie 导出 + 公开算法资料（zcx2077 系列），确认：
  - `webid` 不在 Cookie，藏在 `web_sign_token` **JWT 的 payload.id**（已实测解出 = 抓包同值）
  - `msToken` 不在 Cookie（在 localStorage），**本地随机串必然触发风控踢下线** —— 这就是 2.06 仍被拒的根因
  - WebProtect 层**确实启用**：Cookie 里有 `bd_ticket_guard_client_data`（含客户端 EC 公钥）、
    `bd_ticket_guard_ts_sign_id`、`__security_mc_1_s_sdk_sign_data_key_web_protect`
- 本版落地：
  - **自动薅取**：保存 Cookie 时自动解 webid（JWT）、客户端公钥（bd_ticket_guard_client_data）、
    ts_sign_id 入库；`webid` 从此是真值，不再用随机串
  - **凭据导入**：推送页新增「WebProtect 凭据」粘贴框（浏览器 F12 Console 一条命令导出
    localStorage → 粘贴 → 自动按关键字归档 EC 私钥 / ticket / ts_sign / server_cert）
  - **签名器 WebProtectSigner**：EC P-256 私钥导入（PKCS8 / SEC1 / JWK 三格式）+ 
    `req_sign = ECDSA-SHA256(priv, "ticket=..&path=..&timestamp=..")`，
    按公开样本同型（DER 签名 base64）；凭据齐全时写请求自动附加
    `x-bd-ticket-guard-*` 头（头名以真机抓包为准，待微调）
  - 交互请求 UA/凭据一致性：webid/msToken/uid 全部来自浏览器真实值，不再本地编造
- **待真机确认**：① 需要你从浏览器 localStorage 导出导入 EC 私钥等凭据；
  ② 点赞一次并抓 cURL（含全部请求头）给我，对齐 `x-bd-ticket-guard-*` 头名

## 2.06（2026-10-10）

**点赞/收藏「未登录 + 网页端被踢下线」根治（真机实测反馈）**
- **根因**：旧版用 App 本地随机生成的 webid/msToken 做写操作 —— 抖音风控要求这些
  浏览器级凭据与登录会话严格同源，随机串直接被判定「异常登录」→ 作废整个 sessionid
  （电视显示未登录，**浏览器那边也被踢下线**）。
- 本版改为 **电视完全扮演浏览器**：
  - `webid` / `msToken` / `uid` 优先取登录 Cookie 里的**浏览器真实值**（抖音本来就
    把 webid/msToken/uid_tt 写进 Cookie），本地随机值只作最后兜底
  - **浏览器真实 UA**：推送页 JS 自动捕获 `navigator.userAgent` 随 Cookie 一起保存，
    写请求 UA 与登录会话一致（a_bogus 用同一 UA 生成）—— UA 不符也是「异地登录」诱因
  - Cookie 若缺 webid/msToken：自动以浏览器身份 GET douyin.com 首页，从 Set-Cookie
    补齐服务端签发的真实值（不回写 ttwid，不动会话绑定）
  - 保存 Cookie 后**体检**：检查 sessionid/ttwid/s_v_web_id/UIFID/webid/msToken 是否齐全，
    缺哪个直接提示「请用浏览器全选复制」，避免悄悄带病
  - 失败文案翻译：status_msg 含未登录/login → 提示「浏览器端可能已被踢下线，请重新
    复制 Cookie」；含 verify/captcha → 「触发风控验证」

## 2.05（2026-10-10）

**点赞/收藏：按真实抓包对齐请求结构（2.04 那版仍有偏差）**
- 2.04 用的是「POST + JSON body + 精简参数」，实测仍被拒（403 / 未登录）。
  本版按用户提供的 DevTools 抓包逐字对齐：
  - **URL 带全套浏览器指纹参数**（顺序照抄抓包）：
    `device_platform…round_trip_time=100&webid&uifid&msToken&verifyFp&fp&uid&a_bogus`
  - **a_bogus 签的就是上面这一整串 query**（不含 a_bogus 自己），
    签名 UA 与发送 UA 统一为 Edge/Chromium 154（INTERACT_UA）
  - **POST body 为 form-urlencoded**：`aweme_id=…&item_type=0&type=1|0`
  - **uifid 同时在 URL 与请求头**；补上写接口的 **CSRF 防伪头**
    `x-secsdk-csrf-token`（取 Cookie 的 passport_csrf_token，浏览器端由 sec-sdk 自动携带，
    缺失会触发 403 / verify 校验）
- 结论写死进代码注释：互动接口对浏览器身份的一致性要求极高（UA、webid、uifid、Cookie 必须同源），
  普通 web 指纹（a_bogus）与 CSRF 头都齐了才可能过

**预留 WebProtect 凭据存储**
- Prefs 新增 `wp_ec_private` / `wp_ticket` / `wp_ts_sign` / `wp_server_cert` 四个槽位 + `hasWebProtect()`
- 目前只是「存」，EC P-256 签名算法还没落地（myDV 未开源，且拆人成品不做），
  若真机实测仍被拒，下一步抓一次带完整签名样本即可补齐

**导航**
- 撤销 2.04 遗留的聚合中转，侧边栏固定 **6 项一步直达**：推荐 / 搜索 / 历史 / 收藏 / 设置 / 登录
- 卡片聚焦放大保持 1.04×（10 英尺焦点反馈）

## 2.04（2026-10-09）

**导航回退修正**（采纳用户反馈："换地方更麻烦"）
- 撤销「我的」聚合中转页（MyActivity），侧边栏恢复 **6 项一步直达**：推荐 / 搜索 / 历史 / 收藏 / 设置 / 登录
- 登录沉底（账户操作不占内容首屏）、热搜并入搜索、分类 Tab 常驻保留

**点赞/收藏接口按真实抓包重写**
- 路径修正：`/aweme/v1/web/commit/item/digg/`（真实抓包，原 `/digg/aweme` 是错的）
- 参数全套浏览器指纹凭据：webid / uifid（Cookie UIFID）/ verifyFp+fp（Cookie s_v_web_id）/ msToken / uid / a_bogus
- POST JSON body；webid / msToken / uid 暂需外部配置（Prefs，自动获取待后续）

**UI**
- 卡片聚焦放大 1.04×（10 英尺焦点反馈，Netflix/Apple TV 同款）

## 2.03（2026-10-09）

- **播放页点赞/收藏直达底栏**：新增「赞」「⭐」按钮（高频操作不进菜单），已赞/已藏红色回显
- **「我的」页新增「浏览偏好」开关**：横屏过滤一键切换（开=只留横屏，内容偏少；关=全部内容更丰富）

## 2.02（2026-10-09）

- **热搜并入搜索**：搜索页空态显示「热搜 · 历史」词条（热搜词匿名接口 + 本地搜索历史，点词即搜）
- **收藏列表**：「我的 → 我的收藏」网格页（收藏时本地保存完整视频信息，点卡片续看）
- 「我的」页新增收藏卡片（计数回显）

## 2.01（2026-10-09）★ 信息架构重构（3 模块 + 二级菜单）★

- **侧边栏 7 项 → 3 项**：推荐 / 搜索 / 我的（登录·热搜·历史·设置不再占一级导航）
- **分类 Tab 常驻主界面顶部**：推荐/美食/游戏…横排切频道（不再单独"分类"入口）
- **新「我的」聚合页**（MyActivity）：登录卡片（状态回显）/ 观看历史 / 设置 —— 二级菜单雏形
- **无 Cookie 引导**：冷启动无登录态 → 直接去登录页
- **热搜榜入口移除侧边栏**（保留独立页，搜索页空态后续并入热搜词）

## 2.00（2026-10-09）

- **观看历史按日期分组**：今天 / 昨天 / 更早 三组 + 分组标题（观看时间随记录保存）
- **作者页返回保留上下文**：点视频进播放器不再 finish 作者页，返回回到作者页（不再丢回主界面）

## 1.99（2026-10-09）

**功能完善**
- 评论扩展：支持**图片评论 / 视频评论封面**显示（▶ 角标）；**点评论 → 评论者主页**（作者页）
- 点赞/收藏**状态记忆**：菜单回显「👍 已赞 / ⭐ 已收藏」，支持反转（再点取消），本地记录状态（无需重新点赞即知）
- **搜索历史**：空态显示历史词条（点词即搜），搜索自动记录，上限 20
- 卡片标题占比压缩：标题 2 行→1 行、信息区 52→44dp（封面更突出）

## 1.98（2026-10-09）UI 设计语言统一（融合 iOS / 澎湃审美）

- 大圆角体系：卡片 10→16dp、面板 14→20dp、按钮 6→10dp（radius_xl 令牌）
- 聚焦柔光：focus_card 升级为「白描边 + 外圈光晕」（layer-list+inset 模拟，API 17 兼容）
- 侧边栏：淡品牌胶囊选中（iOS tab 风格）+ 白字；宽度/项高/padding 加大（呼吸感）
- 按钮统一：主按钮去亮黄描边（品牌红+白描边）、次按钮中性灰、播放页按钮高度 42→46dp
- 封面内缩 6dp 呈现大圆角收边；卡片间距加大

## 1.97（2026-10-09）

**新功能**
- 📚 观看历史：起播自动记录（本地文件、去重、上限 100 条），主界面新增「历史」入口 + 历史页网格续看
- 👍⭐ 点赞 / 收藏：播放页菜单新增入口，接口用 a_bogus 签名 + verifyFp（Cookie 内 s_v_web_id 提取），失败时原样显示抖音 status_msg 便于排查（★ 需真机验证）
- 🖥️ 触屏菜单：播放页底栏新增「菜单」按钮（遥控 MENU 键之外的第二入口）

**Bug 修复**
- 🖼️ 老设备封面不加载：封面请求严格 TLS 失败后自动宽松重试（与播放同策略）
- 🔍 搜索界面精简：三段并排改「两段式」——联想词条嵌入结果区顶部，搜到结果自动收起
- 构建 OOM 根治尝试：gradle jvmargs 768m→1024m + workers.max=2（本机 10GB 但常驻程序占 ~9GB，构建时可用内存常跌破 1GB）

**UI 统一（三阶段）+ AppleTV 风格主界面**
- 颜色令牌化：12 个布局的散乱色值归一到语义令牌（消灭 6 种"副文字灰"、青色分隔条等）
- 尺寸/字号网格化：随手 dp/sp 值 → space_* / 4 档字号体系
- 主界面 AppleTV 风格：侧边栏分组导航（浏览/工具/账户 + 符号图标）+ 选中态品牌化（淡红底红字）+ 大标题 28sp + meta 副文本
- 按钮统一：btn_chip → focus_chip（聚焦品牌红反馈）

## 1.96（2026-10-08）★★★ IJK「第 5 个必崩/回退推荐页」根治收编 ★★★

> 版本号规则（自本版起）：对外显示 `1.xx`，次版本号 = versionCode（96 → 1.96，
> 97 → 1.97…），主版本固定 1。versionCode 单调递增保证已发布用户可覆盖升级。

**根因定性（/data/tombstones 三连实锤，彻底推翻"内存泄漏/固件杀"旧结论）：**
- tombstone_07：主线程 `eglMakeCurrent` → `gralloc.nike` → `UMP msync` abort（Mali GPU 驱动缺陷）
- tombstone_05/06：`MediaCodec::queueCSDInputBuffer` → memcpy 空指针（分辨率/旋转变化触发 CSD 重传）
- tombstone_09：`ff_read` 读线程撞 `OMX::freeNode` → `CallbackDispatcher` 析构 assert

**三条路径全部在硬解（MediaCodec/OMX）上** —— 逐个修是打地鼠，终局改为**机型自适应分流**：
- 新增 `DeviceProfile` 能力检测：老设备（≤96MB 堆 或 API≤17）→ **IJK 软解 + 540p 档**；
  正常设备 → **硬解 + 1080/720p 高清**（发布到网上不影响其它机型体验）。
- IJK 释放顺序 `stop → 排水400ms → 解绑Surface → release → 排水250ms`（消除 Mali/OMX 并发窗口）；
  `skip_loop_filter=8`（只跳非参考帧：软解省解码又不糊，48 全跳会马赛克）。
- 崩溃熔断：**IJK 崩 1 次 → 自动降级 ExoPlayer**（不再等崩 2 次才切到声音更差的 VLC）。
- 内存探针阈值按机型内存比例（不再写死 80MB/360MB，正常设备不会误触发）。

**真机验证（创维 8S52 / Android 4.2 / 647MB）：软解 540p 连刷 15+ 个视频不崩、基本流畅。**
**留档备注：此版确认收编"刷到第 5 个左右黑屏/回退推荐页"的多年 bug。**

## 80.3-buf8（2026-10-07）
- IJK 包缓冲 24MB → 8MB（native heap 大头，起播峰值砍半）。仍被杀。

## 80.2-lop（2026-10-07）
- 软解 → 硬解（mediacodec=1）：解码帧移到 OMX/GPU 侧（App 进程内存峰值大降；
  释放竞态已由 native 补丁 v2 修复，可放心开回）。
- 选档优先 360p（647MB 盒子砍解码帧缓冲）。仍被杀。

## 80.1-memfix（2026-10-07）
- ★ 真相定性：不是 native 崩溃（无 tombstone），是**被杀**（events 铁证
  am_proc_died "proc died without state saved"）。
- 弃用 reset 复用（reset 不重建 MediaCodec，codec 资源随换源累积）；
  改每次完整释放重建 + drain 延迟 400/300/300 → 100ms。仍被杀。

## 80.0-ijkp（2026-10-07）
- ★ 本地编译 IJK 0.8.8 成功（Windows + MSYS2 + NDK r10e 全程环境攻坚）：
  ffmpeg 全库 + libijkffmpeg.so + libijkplayer.so + libijksdl.so（armv7a）。
- native 补丁 v1（OMX 析构前等 200ms + 唤醒读线程）+ v2（IO 中断 g_ffp_io_abort：
  释放时 av_read_frame 秒退，根治 release 卡网络 10s 的资源堆积）。
- 替换官方 aar so（删 ijkplayer-armv7a/arm64 依赖，jniLibs 放自编译 so）。

## 播放器"第 5 个必退"最终结论（2026-10-07）
- 定性：**非崩溃、非 ANR、非 LMK 阈值、非 App 内存**（软解/硬解/360p/缓冲/
  串行释放/LMK 阈值调低全试无效）。
- 剩余嫌疑：创维酷开固件进程管理（ipcsvc 体系，GCService 已禁用但被反复拉起）。
  深挖 ROI 低，搁置。
- 可选路线：VLC 引擎（65-77 实测从不被杀，声音断续为硬件缺陷）；崩溃自愈兜底保留。

## 79.0-resume-c（2026-10-06）

- 崩溃自愈落盘改 commit()（同步写盘）：apply() 是异步写，进程瞬死时
  恢复点丢失 → 重启仍回推荐页。commit 保证崩溃前恢复点必在盘上。

## 78.0-resume
- ★ 崩溃自愈：播放中每次起播成功实时存恢复点（列表+位置+来源），
  冷启动 30 秒内检测到恢复点 → 自动回到崩前视频继续播（不再闪回首页丢位置）。
- 同一位置连续恢复 2 次（IJK 反复崩）→ 自动强制 VLC 引擎兜底。
- 正常退出（onDestroy）清恢复点，不干扰正常使用。

## 77.0-vlc-rate48
- VLC 音频 `--aout-rate=48000`：从 libvlc.so 逆向挖出的**正确**采样率选项
  （force-audio-rate 在 3.6 不存在）。强喂 48k 绕开系统 HAL 的 44.1k 缺陷。
- 声音断续仍存在 → 判定为这台电视音频 HAL 的硬件级缺陷。

## 76.0-vlc-aout2
- 删雷：`--force-audio-rate` / `--audio-jitter-compensation` 在 VLC 3.6.3 不存在，
  libvlc_new 遇未知选项直接失败。改 `--audio-resampler=soxr`（唯一可用重采样器）。

## 75.0-vlc-ajit
- audiotrack + 48k 强制 + jitter 补偿（force-audio-rate 是雷 → VLC 启动失败）

## 74.0-vlc-48k
- `--force-audio-rate=48000`（VLC 3.6.3 无此选项 → libvlc_new 直接失败，踩雷）

## 73.0-vlc-buf8
- 网络缓存 3s → 8s：视频"整体不卡了"（确认卡=网络喂不饱）；音频断续仍在。

## 72.0-vlc-layout
- ★ 画面全屏：布局回调 OnNewVideoLayoutListener 里 setWindowSize(屏幕尺寸)
  （setVideoScale 只对 VLCVideoLayout 有效，setVideoSurface 模式必须走回调）。
- 音频回 OpenSL ES（绕开 AudioTrack 硬件缺陷）+ 8s 缓冲 + 音频时钟同步。

## 71.0-vlc-aout
- VLC 音频输出 opensles → audiotrack + 3s 缓冲（断续问题依旧，更严重）。

## 70.0-vlc-fit
- setVideoScale(SURFACE_BEST_FIT)（实测对 setVideoSurface 模式无效，72 才真修）；
  含 69.0 的缓冲提示修复。

## 69.0-vlc-buf
- VLC "缓冲中"常驻修复：Buffering 事件只在未进入 Playing 时显示
  （VLC 无"缓冲结束"事件，播放中水位波动会反复触发 → 之前提示永远挂着）。

## 68.0-vlc-http
- ★ VLC 真正起播：视频直链 https → http 降级（VLC 的 gnutls 在 Android 4.2
  上找不到系统 CA（ENOENT）→ TLS 必然失败；http 明文绕开）。
- 清掉全部踩雷参数（--no-ssl-verify / -vv / --no-mediacodec）。

## 67.0-vlc-diag
- 诊断版（-vv）：确认 `--no-ssl-verify` 是 unknown option → libvlc_new 返回 null
  （VLC 3.6 对未知选项直接失败，不忽略）。

## 66.0-vlc-tls
- 加 `--no-ssl-verify`（雷：libvlc 创建失败，VLC 启动失败）。

## 65.0-vlc
- ★ 接入 libVLC 3.6.3 引擎（官方 aar，minSdk 17 正好支持 Android 4.2；
  更新的 Media3 要 minSdk 21 用不了）。VLC 自带 ffmpeg，无 IJK 的 OMX 释放竞态。
- minSdk 16 → 17（libvlc 要求；目标机就是 API 17，零影响）。
- 首次实测：libvlc 起播成功（解码器用上 VPU），但 TLS 证书验证失败。

## 64.0-exo-audio
- Exo 宽容音频 sink 委托包装（DefaultAudioSink 是 final 类，用接口委托 +
  handleBuffer 容错吞掉时间戳 discontinuity）—— 实测未生效（sink 没被调用，
  buildAudioSink 覆写链路在 2.18.1 上未分派），Exo 音频问题搁置。

## 63.0-ijk-softtest
- IJK 软解实测版（mediacodec=0，完全绕开 OMX）—— 用户实测：真卡
  （A7 单核软解 540p 临界确认），软解路线否定。

## 62.0-ijk-throttle
- IJK 连刷节流：1.5 秒内不允许连切（快速连按撞释放竞态的防抖）。
  正常观看节奏无感。

## 61.0-ijk-reuse
- IJK 复用实例（reset + 换源，对齐 Exo 策略）—— 实测仍崩：
  tombstone_03 与 09 完全同款（ff_read 撞 OMX::CallbackDispatcher 析构）→
  实例数量不是变量，确认是 native 释放时序竞态。

## 60.0-ijk-drain
- IJK 释放改后台线程：stop → 等 400ms → release → 等 300ms → 再建。
  主线程不卡；实测仍崩（竞态在 stop/release 之间的线程退出，Java 层无解）。

## 59.0-csd-fix
- 关 mediacodec-handle-resolution-change=0：修 tombstone_07（MediaCodec_loop +
  queueCSDInputBuffer 空指针，分辨率变化重启 codec 重传 CSD 触发）。
- 新崩点浮出：tombstone_09（ff_read + CallbackDispatcher 析构）——
  IJK 0.8.8 native 释放竞态正式定性，Java 层调用序列无法根治。

## 58.0-dtv
- 中间版本（被 59.0 覆盖，未交付）。

## 57.0-dtv（2026-10-05）

- **修复 IJK 连播闪退**（刷约 5 个视频后退回推荐页）：
  根因是 release() 后 native 线程（ff_read / MediaCodec_loop）仍在退出，
  旧代码在 old == null 分支会**立即** new 新实例，与将死未死的旧线程
  并发访问已释放结构 → use-after-free → SIGSEGV → 进程被杀冷启动回首页。
  修复：统一 300ms release 守卫（lastReleaseAt + scheduleCreate），
  距上次 release 不足 300ms 一律推迟创建，堵死竞态窗口。
- **正式更名 Dtv**：应用名 / APK 输出名 / 版本号统一为 Dtv 体系
（applicationId 保持不变，酷安老用户可正常覆盖升级）。
- **设置 → 关于页**：作者名称 + 作者主页二维码 + 版本号 + 签名指纹（SHA-256），
  签名与官方不符时显示红色防篡改警告条。
- 修复 App.kt / AboutActivity.kt 编译错误（上一分支遗留）。

## 56.0-no-uaf（上一版）
- IJK 释放改为「release 后异步 250ms 再新建」，避免紧接 new 的 UAF；
  释放前先 setDisplay(null) 解绑 Surface，修复 Graphics 内存只涨不落、
  连播后被 LMK 杀回首页的问题。

## 更早关键版本（55.0 及以前）
| 版本 | 要点 |
|---|---|
| 55.0-self-restart | 进程被杀后的自我恢复路径 |
| 54.0-mem-trim | 播放器内存回收优化 |
| 50.0-h264-only | 仅播 H.264（避开 HEVC 硬解缺陷） |
| 49.0-hwonly | 强制仅硬解（软解在 A7 上卡音画同步） |
| 48.0-dec-diag / 47.0-play-diag | 解码器 / 播放链路诊断 |
| 45.0-ijk-hardened | IJK 强制硬解参数加固 |
| 44.0-ijk-restore | 恢复 IJK 为主力引擎 |
| 43.0-oom-fix | 内存泄漏修复 |
| 40.0-login-page | 登录页（扫码推 Cookie） |
| 36.0/35.0 | 真实搜索接通 / 搜索入口 |
| 33.0-abogus-search | ABogus 签名 + 搜索 |
| 31.0-aspect-center | 画面比例居中修复 |
| 29.0-overlay-clean | 移除诊断横幅等浮层 |
| 26.0-aspect-fix | Exo r1.5 Handler null 根因修复（视频尺寸回调恢复） |
| 20.0-surface-fix | 基准版本（流畅，偶无声） |
