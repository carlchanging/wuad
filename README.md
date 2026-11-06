# 无广告

Android 侧载：一份 `VpnService` 同时做 **DNS 去广告**、**mihomo 分流**、**GKD 开屏跳过**。

当前版本 **0.4.76**（`versionCode` 86）。仅 **arm64-v8a**。

公开时只发本目录 `wuad-android/`，源码对照见 [docs/SOURCE.md](docs/SOURCE.md)。

---

## 背景：为什么做这个

作者原先同时用三款软件：

| 原来用的 | 解决什么 | 占不占 VPN |
| --- | --- | --- |
| AdGuard | DNS 保护，拦广告域名 | 占 |
| FLClash | mihomo 分流 / 机场节点 | 占 |
| GKD | 无障碍点开屏「跳过」 | 不占，但常和前两个一起开 |

Android **全局只能有一个 `VpnService`**。AdGuard 和 FLClash 开一个，另一个必挂。想去广告又想分流，只能反复开关，或者忍痛二选一。GKD 本身不抢 VPN，但侧载环境（尤其 ColorOS）还要单独放行无障碍、耗电、自启动，三套 App 各设一遍。

所以目标很窄，不是再造一个「更大的 AdGuard / Clash / GKD」：

1. **一个坑活三件事**：DNS 去广告、可选分流、开屏跳过，共用一份隧道。
2. **只做日常路径**：对照三家用户天天用的那截，不抄完整产品树。
3. **不做 HTTPS 中间人**：不装用户证书、不解密 HTTPS。同域页内广告（常见于微信公众号）明确拦不到。
4. **规则跟订阅走**：名单用 anti-AD / AdRules / GKD 订阅，分流用官方 mihomo + geo。不为微信、会议软件写私货白名单或私货跳过，以免把正经域名点死。
5. **侧载能活**：ColorOS / 国产 ROM 上把无障碍、电池、锁定任务写进引导，进程被杀后尽量恢复开关。
6. **不上传使用数据**：没有统计 SDK，更新只在你点（或你自己打开「启动时检查」）。

这是个人刚需合成，不是三家官方套壳，也不是它们的发行版。

---

## GitHub 仓库填写（可直接复制）

**About / 简介（≤350 字）：**

```
Android 只能挂一个 VPN。把 AdGuard DNS 保护、FLClash 分流、GKD 开屏跳过合成一份侧载应用，解决三套软件互踢。不做 HTTPS 中间人。仅 arm64。
```

**Topics：**

```
android  adblock  dns  clash-meta  mihomo  gkd  vpn  sideload  coloros
```

**Release 标题：** `无广告 0.4.76`

**Release 正文模板：**

```
Android 只能挂一个 VPN。作者原先同时用 AdGuard DNS、FLClash、GKD，前两个互踢，所以合成这一份侧载应用。

0.4.76 已实现：
- DNS 去广告（anti-AD 默认；AdRules / 自定义可加；Peter Lowe 预置默认关；上游默认 223.5.5.5）
- 可选 mihomo 分流（官方内核 v1.19.33，订阅 / 节点 / 规则 / 按 App）
- 隧道 IPv6 默认关，避免 gvisor 直连 IPv6 失败拖慢国内 App
- GKD 11 个官方分类，默认只开「开屏广告」
- 首页 KPI、日志、侧载引导；不做 HTTPS 中间人，无统计上报

安装：侧载 APK → 按引导开无障碍、电池白名单、锁定最近任务。
关掉系统「私人 DNS」，否则 53 端口劫持失效。
```

完整对标见 [docs/COVERAGE.md](docs/COVERAGE.md)，架构见 [docs/TECH.md](docs/TECH.md)，源码文件见 [docs/SOURCE.md](docs/SOURCE.md)。

---

## 0.4.76 已实现

底栏四页：**首页 / DNS / 分流 / GKD**。总开关拉起唯一的 VPN；DNS 与分流可单独关。跳过只吃无障碍，VPN 挂不挂都能点。

### 首页

- 总开关：开隧道；进程被杀后回到前台会按「想开着」恢复
- 分流卡片：开关 + 当前节点；关着时不占节点行，点进节点列表
- 今日数字：DNS 拦截 / 连接 / 点击，点进去就是对应日志
- 入口：日志、应用策略、设置、侧载引导
- 页脚只保留「无广告」和版本号

### DNS（对照 AdGuard DNS 保护）

- 本机 `127.0.0.1:1053` UDP+TCP，广告域回 `0.0.0.0` / `::`
- 多订阅合并去重：anti-AD 默认开；AdRules、Peter Lowe 预置（后者默认关）
- 可再加自定义订阅；自定义拦截 / 放行（放行优先）
- 上游：明文 / DoH / DoT，默认 `223.5.5.5`，可加阿里 DoH、Cloudflare
- 按 App 关闭 DNS 过滤
- 私人 DNS 冲突提示

### 分流（对照 FLClash）

- 官方 MetaCubeX mihomo **v1.19.33**，gvisor TUN，fake-ip
- **隧道 IPv6 默认关**（`ipv6: false`，不声明 `::/0`）
- 多份机场订阅，App 侧下载（不走内核 http-provider）
- 节点列表（分流关着也显示）、延迟测速、AUTO
- 规则 / 全局 / 直连；GEOSITE / GEOIP / 自定义规则（可把域名设为 DIRECT）
- 按 App：直连、代理、不进隧道（排除走系统 `addDisallowedApplication`）
- geo 四件套（geoip / geosite / ASN）；Loyalsoldier 规则集可选
- 没填机场时 PROXY 退化直连，`REJECT` / `DIRECT` 仍有效
- 嗅探 HTTP/TLS/QUIC 已开；纠 fake-ip 的 `override-destination` 默认关

### GKD（对照开屏跳过）

- json5 订阅，官方选择器从右往左匹配
- 默认 [Lin-arm](https://github.com/Lin-arm/GKD_subscription)，国内镜像回退
- 11 个官方分类开关，默认只开「开屏广告」：青少年模式、更新提示、评价提示、通知提示、权限提示、局部广告、全屏广告、分段广告、功能类、其他
- 按 App 忽略；同条 20 秒不连点
- 点击进日志，和首页 KPI 同一口径

### 日志 / 应用 / 设置

- 日志三栏：DNS、连接、点击；系统事件另记
- 应用列表：DNS / 分流 / GKD 分别开关
- 打开应用时检查更新（**默认关**）
- ColorOS 引导：限制设置无障碍、耗电 / 自启动、锁定最近任务
- 前台服务保活：`START_STICKY`、划掉任务不立刻拆隧道

三家逐项对标见 [docs/COVERAGE.md](docs/COVERAGE.md)。

---

## 明确做不到

- **HTTPS 中间人 / 证书植入。** 同域 HTTPS 里的广告（常见于微信公众号）DNS 拦不住。
- App 自建 HTTPDNS、DoH 直连，不走 53 端口。
- 系统「私人 DNS」开着时，本机劫持 53 失效。
- 完整 AdGuard 过滤语法、元素隐藏、钓鱼库、家长控制。
- 完整 FLClash：多配置、覆写/脚本、Web 面板、x86、连接实时表。
- 完整 GKD：快照审查、选择器调试台、网页规则、多订阅管理。
- 直连失败再自动改走海外。
- 统计上报、崩溃上报。没有账号，没有自家服务器。

---

## 隐私

出网只有：

1. 你点的规则 / GKD / 机场 / geo 更新
2. 设置里「打开应用时检查更新」（**默认关**）
3. DNS 上游解析
4. 分流开着时走你选的节点

内核 API、连接轮询走 `127.0.0.1`。日志和名单在本机。没有定时任务往外传使用数据。

---

## 安装

1. 从 [Releases](../../releases) 下 APK，侧载。
2. 打开引导：无障碍（ColorOS 在「限制设置」）、耗电/自启动、最近任务锁定。
3. 关掉系统私人 DNS。
4. 首页打开总开关。需要节点再填机场订阅。

最低 Android 8（API 26），目标 API 35。只编了 arm64。

---

## 编译

需要 JDK 17、Android SDK 35、NDK 26.3、CMake 3.22。

```text
python fetch_mihomo.py          # 拉官方 mihomo-android-arm64 v1.19.33 → jniLibs
./gradlew :app:assembleDebug    # 或 Windows 下 gradle.bat
```


---

## 致谢

日常能力对照这三家，**不是官方套壳，也不是它们的发行版**：

- [AdGuard](https://adguard.com/) — DNS 保护思路
- [FLClash](https://github.com/chen08209/FlClash) / [mihomo](https://github.com/MetaCubeX/mihomo) — 内核与订阅习惯
- [GKD](https://github.com/gkd-kit/gkd) — 选择器与订阅格式

名单与资源：

- [anti-AD](https://github.com/privacy-protection-tools/anti-AD)
- [AdGuard Hostlists Registry](https://github.com/AdguardTeam/HostlistsRegistry)（AdRules DNS、Peter Lowe）
- [Lin-arm/GKD_subscription](https://github.com/Lin-arm/GKD_subscription)
- [Loyalsoldier/clash-rules](https://github.com/Loyalsoldier/clash-rules)
- [MetaCubeX/meta-rules-dat](https://github.com/MetaCubeX/meta-rules-dat)

mihomo 是 GPL-3.0。仓库若公开发布，许可证需要和它兼容（通常整仓也用 GPL-3.0）。名单、订阅各走各自许可证。

---

## 文档

| 文件 | 内容 |
| --- | --- |
| [docs/TECH.md](docs/TECH.md) | 架构、模块、出网、权限 |
| [docs/COVERAGE.md](docs/COVERAGE.md) | 相对 AdGuard / FLClash / GKD 的功能覆盖 |
| [docs/SOURCE.md](docs/SOURCE.md) | 公开源码文件清单、不要提交的路径 |
