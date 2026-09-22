# Glove-Statistics 缝手套记工

面向手工缝纫手套计件工作的 Android 离线统计应用。数据只存在本机，不需要联网，也不需要任何权限。

## 功能

### 记工（首页）
- 先选日期（默认今天，可切换补录过去的日期）。
- **家里手套**：从手套库选种类 → 单价自动带出 → 输入数量 → 实时显示「单价 × 数量」的预计收入 → 保存。
  同一天可以记多笔、多种手套。
- **厂房工作**：录入当天收入 + 可选备注。**独立成账，不计入手套收入。**
- 同一天可以既在家里做手套、也去厂房干活，两类记录互不影响。

### 记录（月历）
- 一整月的月历，把「干了什么活」直接标在日期上：
  - 绿色圆点 = 当天在家做手套
  - 橙色圆点 = 当天去了厂房
  - 两个都有 = 那天两头都干了
  - **没有圆点 = 未干活**
- 点任意一天展开当天明细，可以**修改或删除**每一条手套记录 / 厂房记录。
- 支持翻到上个月、下个月回看与补录。

### 手套库
- 新建「手套名称 + 默认单价」，保存到本地，以后记工直接选，不用重复输入。
- 支持改名、改价、删除。
- 记录里保存的是**当天的单价快照**，所以以后调价不会把过去已经算好的收入改掉。
- 删除手套种类不会删掉历史记录，历史收入照常统计。

### 本月
- 分别显示**家里手套收入**与**厂房工作收入**，以及两者相加的**全部劳动收入**。
- 显示本月干活天数、手套总数量、各手套种类的数量与收入明细（按收入排序）。
- 可切换月份查看任意一个月。

### 导出与备份
导出会打开系统文件选择器（SAF），文件由你自己决定存到手机本地、SD 卡还是网盘，**不需要申请存储权限**。

- **PNG 统计图**：适合发微信或存相册。可选三种范围：
  - 只导出手套账单
  - 只导出厂房收入
  - 导出全部劳动收入（两张表 + 汇总合计）
- **XML 完整备份**：包含全部手套种类、所有月份的手套记录与厂房记录，适合换手机或长期存档。
- **从 XML 备份恢复**：会用备份内容替换当前全部数据（恢复前会二次确认）。

## 收入计算规则

| 项目 | 计算方式 | 是否计入手套收入 |
| --- | --- | --- |
| 家里手套 | 单价 × 数量 | 是 |
| 厂房工作 | 当天实际收入 | 否，单独统计 |
| 本月全部劳动收入 | 手套收入 + 厂房收入 | —— |

## 技术栈与结构

Kotlin + Jetpack Compose（Material 3），minSdk 26，本地存储用 SharedPreferences（纯文本，无数据库）。

```
app/src/main/java/com/mr5u/glovestatistics/
├── Data.kt          数据模型 + LocalStore（本地存储）+ WorkViewModel（业务与统计）
├── Reports.kt       PNG 报表绘制 + XML 备份导出 / 解析
├── Export.kt        系统文件选择器（SAF）通道：导出与恢复
├── MainActivity.kt  应用入口与底部导航
├── TodayScreen.kt   记工页
├── CalendarScreen.kt 记录页（月历）
├── Screens.kt       手套库页 + 本月页 + 导出对话框
└── UiCommon.kt      共用组件与对话框
```

## 构建

```bash
./gradlew assembleDebug        # 生成 debug APK
./gradlew assembleRelease      # 生成 release APK（用 .tooling/debug.keystore 签名，可直接安装）
```

release 包目前用 Android 的 debug 签名（`.tooling/debug.keystore`），这样 `assembleRelease`
能直接产出可安装的 APK，方便自己装或发 GitHub Release。**这个签名只适合自用，不适合上架应用商店**；
将来要上架时把 `app/build.gradle.kts` 里的 `signingConfig` 换成自己的正式 keystore 即可。

Android SDK 路径写在 `local.properties`（该文件不入版本库）。

### 工具链版本

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Gradle | 9.7.1 | AGP 9.x 要求 Gradle 9.x |
| Android Gradle Plugin | 9.4.1 | compose 1.12.x 要求 AGP ≥ 9.1.0 |
| Kotlin | 2.2.21 | **只声明 `org.jetbrains.kotlin.plugin.compose`**；`org.jetbrains.kotlin.android` 自 AGP 9.0 起由 AGP 内置提供，声明了会直接报错 |
| compileSdk / targetSdk | 37 | compose 1.12.x 要求 compileSdk ≥ 37 |
| minSdk | 26 | Android 8.0 起 |
| Compose BOM | 2026.09.00 | 对应 compose 1.12.1 |

> 换工具链版本时要**成套换**：BOM、AGP、compileSdk 三者有连带要求。
> 例如把 BOM 降回 2025 年的版本，就要把 AGP 降回 8.x、compileSdk 降回 35，
> 否则会报 “requires AGP 9.1.0 or higher / compile against version 37”。

### 离线类型检查（本机无网络时）

如果 Gradle 拉不到依赖（本机无外网），`gradlew` 无法构建。但如果 Android Studio 已经
成功 Sync 过一次，Gradle 缓存里就有**真实的** Compose / androidx 构件，此时可以绕过 Gradle
直接编译 app 源码做验证：

```powershell
powershell -File .tooling/typecheck.ps1
```

它做的是**真实编译**（不是签名检查）：用缓存里的真 AAR/JAR + 本机最高的 `android-XX`
平台 jar + 真实的 Compose 编译器插件，对 `app/src/main/java` 完整编译一遍。
退出码 0 表示源码在真实依赖下前后端都编译通过（会打印生成的 class 数）。

配合 `.tooling/extract-aars.ps1`（从 Gradle 缓存抽取 AAR 里的 `classes.jar`）。

**这仍不能替代真实构建**：它不打包资源、不生成 APK，也不验证运行行为。
出 APK 必须用 Gradle。

### 已知环境注意事项

- 项目路径含中文（`Glove-Statistics - 副本`）时，AGP 默认会直接报错终止；
  本项目已在 `gradle.properties` 里加 `android.overridePathCheck=true` 关闭该检查。
- Gradle 需要 JDK 17~21。**不要用 Android Studio 自带的 JBR（本机是 JDK 25）**，
  它会让 Gradle 构建直接失败；请在 `Settings → Build Tools → Gradle → Gradle JDK`
  里指定 `Z:\SoftWare\JAVA\JDK21`。
- 首次构建需要联网下载 Gradle 发行版与 Compose / androidx 构件。
- AGP 9 起 Kotlin 支持内置，**不要**再声明 `org.jetbrains.kotlin.android` 插件；
  但 `org.jetbrains.kotlin.plugin.compose` 必须保留。
