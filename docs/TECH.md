# 技术说明

给仓库 About、Issue、贡献者看的实现说明。背景和当前版本功能清单见 [README](../README.md)。不写机场协议细节，不写可复现的攻击步骤。

## 为什么要合成一个 App

Android 全局只能有一个 `VpnService`。AdGuard DNS 保护、FLClash TUN、部分「VPN 去广告」都会占这个坑。GKD 本身不占 VPN，但用户同时开 AdGuard + FLClash 时必踢。

本项目用 **一份 VPN 进程** 覆盖 DNS 与分流；跳过引擎是独立的无障碍服务。

```
App 进程
├── WuadVpnService          系统唯一隧道
│   ├── Dns-only 模式       只路由上游 DNS 地址（对照 AdGuard DNS 保护）
│   └── TUN 0.0.0.0/0       分流开着时 IPv4 全流量进 gvisor
│                           （TUN_IPV6 默认 false，不声明 ::/0）
├── WuadDnsServer           127.0.0.1:1053 UDP+TCP
│   ├── Blocklist 命中 → 0.0.0.0 / ::
│   └── 未命中 → 用户上游（默认 223.5.5.5，可选 DoH/DoT）
├── libmihomo.so            官方 MetaCubeX v1.19.33
│   ├── fake-ip 198.18.0.1/16
│   ├── nameserver-policy: geosite cn/ads → :1053
│   └── 海外 → https://8.8.8.8/dns-query#PROXY
└── WuadSkipService         无障碍，不进隧道
    └── GkdSelector 右向左匹配 → 点击 / 手势
```

内核 `auto-detect-interface` 在 Android 14+ 上关掉，改由 App 写入当前非 VPN 网卡名。本应用 `addDisallowedApplication`，避免自己的流量再进自己的 TUN。

## 模块

| 模块 | 路径 | 职责 |
| --- | --- | --- |
| VPN | `vpn/WuadVpnService.kt` | 建隧道、保活、`START_STICKY`、`stopWithTask=false` |
| DNS | `dns/WuadDnsServer.kt` `dns/DnsCodec.kt` | 本机解析、拦截、转发、缓存 |
| 名单 | `core/Blocklist.kt` `DnsSubStore.kt` `HostlistParse.kt` | 多订阅合并 `HashSet` |
| 内核 | `core/MihomoEngine.kt` `ClashConfig.kt` `ClashBits.kt` | 写 YAML、起停官方 so |
| 订阅 | `core/SubFetch.kt` `SubStore.kt` | App 侧拉 Clash YAML，再给 file provider |
| 节点 | `core/MihomoApi.kt` | `127.0.0.1:19090` 列节点、测速、切组 |
| 规则 | `core/RouteStore.kt` `RouteUpdater.kt` `GeoStore.kt` | GEOSITE/GEOIP/Loyalsoldier |
| 跳过 | `skip/WuadSkipService.kt` `GkdSelector.kt` `GkdScope.kt` `GkdCats.kt` | 官方选择器、11 分类、桌面/系统范围 |
| 日志 | `core/EventStore.kt` `ConnWatcher.kt` | 本机持久化，点击 KPI 与日志同口径 |
| JNI | `cpp/mihomo_exec.c` | 拉起 `libmihomo.so`，不是自研内核 |

## DNS 行为

- 广告域：AdGuard 风格空地址（A=`0.0.0.0`，AAAA=`::`），NOERROR。
- 多份订阅进同一个集合，重复域名只存一次。
- 自定义放行优先于订阅拦截。
- 分流开着时：国内 / 广告走 `:1053`；海外走内核 fake-ip + 代理 DoH。
- `DnsSafe` 只保留官方 fake-ip-filter（局域网、STUN、校时、QQ ptlogin localhost），不加即时通讯私货白名单。
- App 用 HTTPDNS / 自建 DoH 时，请求不进 53，本 DNS 看不见。

## 分流行为

- 内核：官方 `mihomo-android-arm64` v1.19.33，禁止改成裁剪版。
- 订阅：`clash.meta` / FLClash / CMFA 一类 UA，支持 gzip、base64、分享链接展开。
- 没填机场时 PROXY 组退化直连，`REJECT` / `DIRECT` 规则仍有效。
- 按包名：`PROCESS-NAME` → DIRECT / PROXY；「排除」走 `VpnService.addDisallowedApplication`；本应用永远 exclude。有按 App 策略时才开 `find-process-mode: strict`。
- 预置规则：GEOSITE gfw→代理、cn→直连，GEOIP private/CN→直连，MATCH→代理。Loyalsoldier 三份默认关。
- 隧道 IPv6 默认关。嗅探开着，`override-destination` 默认关（不把 fake-ip 改成真 IP 再走 GEOIP）。

## 跳过行为

- 订阅默认 [Lin-arm](https://github.com/Lin-arm/GKD_subscription)，国内镜像：ghfast / gitmirror / jsdelivr / ghproxy。
- 选择器按正式 GKD **从右往左**，支持关系符、括号、`preKeys`、`matchAnyApp`。
- 默认只开「开屏广告」；其余 10 个官方分类默认关。同一条 20 秒内不连点。
- 选择器异常吞在无障碍线程，避免把 VPN 进程一起打死。
- 不做快照录制、不做选择器 IDE、不做网页 CSS 规则。

## 权限（清单里有的）

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 上游 DNS、更新名单、机场、geo、代理出站 |
| `ACCESS_NETWORK_STATE` | 找底层网卡、探测私人 DNS |
| `FOREGROUND_SERVICE` / `SPECIAL_USE` | VPN 前台服务 |
| `POST_NOTIFICATIONS` | 隧道通知 |
| `QUERY_ALL_PACKAGES` | 按 App 分流 / 忽略；内核读包名 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 引导里申请耗电白名单 |
| `RECEIVE_BOOT_COMPLETED` | 已声明，**没有接收器**，开机不拉活、不上报 |
| 无障碍 | 跳过引擎读窗口并点击 |

没有剪切板权限，没有定位，没有通讯录，没有统计 SDK。

## 出网清单

| 触发 | 目标 | 默认 |
| --- | --- | --- |
| DNS 解析 | 用户选的上游 | `223.5.5.5` |
| 更新 anti-AD | `https://anti-ad.net/domains.txt` | 手动；或「打开时检查」 |
| 更新 AdRules / Peter Lowe | Hostlists Registry | 订阅开启才拉 |
| 更新 GKD | 用户 URL + 镜像链 | 手动 |
| 更新分流规则 | Loyalsoldier jsDelivr | 对应规则开启且点更新 |
| 更新 geo | MetaCubeX latest | 缺文件或点资源页 |
| 更新机场 | 用户填写的 URL | 手动 |
| 分流海外解析 | `8.8.8.8` / `1.1.1.1` DoH via PROXY | 仅分流开着 |
| 内核 API | `127.0.0.1:19090` | 不出网 |
| 打开应用检查更新 | 上面几类 | **默认关** |

没有 WorkManager、没有 Alarm、没有 Firebase / 友盟。

## 构建与二进制

- `applicationId`：`app.wuad`
- minSdk 26 / targetSdk 35 / compileSdk 35
- Kotlin + ViewBinding，无 Compose
- `fetch_mihomo.py` 只下载官方 gz，解到 `app/src/main/jniLibs/arm64-v8a/libmihomo.so`
- CMake 编 `libmihomo_exec.so`：exec 官方内核
- 发布 APK 建议走 GitHub Releases，不要把调试机上的 `wuad.xml` 打进仓库

## 已知硬限制（不是 bug）

1. 同域 HTTPS 广告：没有 MITM 就改不了响应体。
2. 微信 `dns.weixin.qq.com` 一类 HTTPDNS：不走 53。
3. 厂商杀后台：侧载 + 无障碍在 ColorOS 上要手动放行；代码只能 `START_STICKY` 和恢复开关，挡不住强杀。
4. 部分开屏是 App 自己倒计时关掉的，日志不会有「点击」。
