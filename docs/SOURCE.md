# 公开源码文件

GitHub 只发 **`wuad-android/`** 这一层。上层 `vibepm-demo-agent`（技能、原型母版、其它 docs）不要塞进本仓库。

内核 `libmihomo.so` 用 `fetch_mihomo.py` 现拉，不要把 so、APK、机场 token 提交上去。

## 该提交

```
wuad-android/
├── README.md
├── .gitignore
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/wrapper/
├── fetch_mihomo.py
├── docs/
│   ├── TECH.md
│   ├── COVERAGE.md
│   └── SOURCE.md
├── scripts/                    # 可选：名单/选择器辅助脚本
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── cpp/            # mihomo_exec.c + CMakeLists.txt
        │   ├── java/app/wuad/  # 见下表
        │   └── res/            # 布局、图标、主题、颜色
        └── test/java/app/wuad/core/PriorityLogicTest.kt
```

`gradle/wrapper/gradle-wrapper.jar` 建议一并提交，别人才能直接 `gradlew`。

## Kotlin 源码（`app/src/main/java/app/wuad/`）

| 文件 | 干什么 |
| --- | --- |
| `WuadApp.kt` | Application，初始化存储 |
| **VPN** | |
| `vpn/WuadVpnService.kt` | 唯一隧道、保活、排除本应用和「排除」名单 |
| `vpn/DnsTunLoop.kt` | DNS-only 时在隧道里转发 53 |
| `vpn/DnsTcp.kt` | DNS TCP |
| **DNS** | |
| `dns/WuadDnsServer.kt` | `127.0.0.1:1053` 拦截 / 转发 |
| `dns/DnsCodec.kt` | DNS 编解码 |
| `core/Blocklist.kt` | 合并后的广告域集合 |
| `core/DnsSubStore.kt` | DNS 订阅 |
| `core/HostlistParse.kt` | hosts / 域名列表解析 |
| `core/DnsUpStore.kt` | 上游 |
| `core/DnsSafe.kt` | 官方 fake-ip-filter，无私货 |
| `core/DnsProbe.kt` | 私人 DNS 探测 |
| `core/DnsLogParse.kt` | DNS 日志解析 |
| `core/RuleStore.kt` | 自定义 DNS 拦/放 + GKD 条目 |
| **分流** | |
| `core/ClashConfig.kt` | 写 mihomo YAML |
| `core/ClashBits.kt` | YAML 片段 |
| `core/MihomoEngine.kt` | 起停内核 |
| `core/MihomoNative.kt` | JNI |
| `core/MihomoApi.kt` | 本机 API：节点、测速 |
| `core/SubStore.kt` | 机场订阅条目 |
| `core/SubFetch.kt` | App 侧拉订阅 |
| `core/RouteStore.kt` | 分流规则 |
| `core/RouteUpdater.kt` | 规则集更新 |
| `core/RouteToggle.kt` | 分流开关 |
| `core/GeoStore.kt` | geo 四件套 |
| **GKD** | |
| `skip/WuadSkipService.kt` | 无障碍点击 |
| `core/GkdParser.kt` | json5 订阅 |
| `core/GkdSelector.kt` | 右向左选择器 |
| `core/SelectorParse.kt` | 选择器解析 |
| `core/GkdScope.kt` | 桌面 / 系统 / matchAnyApp |
| `core/GkdCats.kt` | 官方 11 分类 |
| `core/GkdGroups.kt` | 规则组 |
| `core/Json5Lite.kt` | json5 |
| `core/SplashHunt.kt` | 开屏辅助 |
| **界面** | |
| `ui/MainActivity.kt` | 底栏四页 |
| `ui/HomeFragment.kt` | 首页开关、KPI、分流卡片 |
| `ui/DnsFragment.kt` | DNS 页 |
| `ui/RouteFragment.kt` | 分流页 |
| `ui/GkdFragment.kt` | GKD 分类开关 |
| `ui/NodesActivity.kt` | 节点 |
| `ui/AppsActivity.kt` | 按 App |
| `ui/LogsActivity.kt` | 日志 |
| `ui/SettingsActivity.kt` | 设置 |
| `ui/GuideActivity.kt` | 侧载引导 |
| `ui/ResourcesActivity.kt` | geo 资源 |
| `ui/RulesActivity.kt` `RulesPage.kt` `RuleAdapter.kt` `RuleEditActivity.kt` `GkdRulesActivity.kt` | 规则列表 / 编辑 |
| **其它** | |
| `core/Prefs.kt` | 本地偏好 |
| `core/EventStore.kt` | 本机日志 |
| `core/ConnWatcher.kt` `ConnLabel.kt` | 连接日志 |
| `core/AppNames.kt` | DNS 查询归因到 App |
| `core/RemoteFiles.kt` | 拉远程文件 |
| `core/RuleUpdater.kt` `RuleModels.kt` | 规则更新 |
| `core/StatAgg.kt` `TrafficFmt.kt` | 今日数字、流量格式 |

JNI：`app/src/main/cpp/mihomo_exec.c` 只负责 exec 官方 `libmihomo.so`，不是自研内核。

## 不要提交

| 路径 | 原因 |
| --- | --- |
| `local.properties` | 本机 SDK 路径 |
| `*.apk` / `无广告-*.apk` | 装包走 Releases，不进 git |
| `app/src/main/jniLibs/**/*.so` | 用 `fetch_mihomo.py` 拉 |
| `app/build/` `.gradle/` `.cxx/` `.idea/` | 构建垃圾 |
| `preview-gkd.html` `home-preview.html` | 本地预览，不是产品 |
| 设备拉下来的 `wuad.xml`、`shared_prefs`、`files/mihomo/providers/` | **含机场 token** |
| 订阅 URL 截图、日志里的节点订阅链接 | 泄漏机场 |
| 上层 `../.agents/` `../frame/` `../web/` `../AGENTS.md` | 另一套原型工具，不是本 App |

`.gitignore` 已覆盖 APK、so、构建目录、`local.properties`。推之前仍建议 `git status` 扫一眼，别把 `无广告-0.4.xx-debug.apk` 加进去。

## 许可证

链接了官方 mihomo（GPL-3.0）。公开仓库请在 GitHub 创建时勾 **GPL-3.0**，或自行放 `LICENSE`。名单、GKD 订阅各走上游许可证，不要改成声称「这些名单是本仓库原创」。
