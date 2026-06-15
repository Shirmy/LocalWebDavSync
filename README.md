# LocalSync

> 🤖 **本项目 100% 由 AI 编写,作者本人没有动过一行代码。**
> 从架构设计、功能实现、UI 到调试,全部代码均由 AI 生成,本人仅负责提出需求与验收。

一个把本地文件夹单向同步到 WebDAV 远程目录的 Android 应用。基于内容哈希做增量检测,支持多任务、删除模式、仅 Wi-Fi 同步与自动扫描,主要针对坚果云等 WebDAV 服务测试。

> 应用内显示名为 **LocalSync**;仓库名 `LocalWebDavSync`。

## 功能特性

- **多同步任务**:每个任务把一个本地 SAF 文件夹映射到一个 WebDAV 远程文件夹,可独立启用/暂停。
- **增量同步**:通过文件内容哈希 + 大小 + 修改时间检测新增、修改、删除,只上传有变化的文件,避免重复传输。
- **两种删除模式**:
  - *复制到云端*:本地删除文件时,保留云端副本。
  - *同步到云端*:本地删除文件时,一并删除云端对应文件。
- **仅 Wi-Fi 同步**:可按任务设置,使用移动网络时跳过该任务。
- **启动自动扫描**:可选打开 App 后自动扫描并同步全部任务。
- **扫描结果与状态跟踪**:按"待同步 / 已同步 / 失败 / 已忽略"分类展示每轮扫描详情,失败文件支持重试。
- **开发者日志**:内置日志页,支持主要/成功/失败/全部/Debug 过滤,便于排查同步问题。
- **凭据加密存储**:WebDAV 地址、用户名、应用密码通过 AndroidX Security 加密保存在本地。

## 技术栈

- **语言**:Kotlin(JVM 17)
- **UI**:Jetpack Compose + Material 3,Navigation Compose
- **架构**:MVVM,`AppContainer` 手动依赖注入,Repository 分层
- **本地存储**:Room(同步任务、文件记录、日志)
- **网络**:OkHttp,自实现 WebDAV(PROPFIND / PUT / DELETE / MKCOL)
- **后台任务**:WorkManager
- **文件访问**:Storage Access Framework(DocumentFile)+ `MANAGE_EXTERNAL_STORAGE`

## 项目结构

```
app/src/main/java/com/example/localwebdavsync/
├── MainActivity.kt            # 入口 Activity
├── AppContainer.kt            # 依赖容器(手动 DI)
├── navigation/                # 页面导航
├── ui/                        # Compose 界面与 ViewModel
│   ├── home/                  #   主页:任务列表、同步操作、扫描状态
│   ├── task/                  #   任务新建/编辑、WebDAV 目录浏览
│   ├── settings/              #   WebDAV 设置、权限、自动扫描开关
│   ├── log/                   #   开发者日志
│   ├── components/            #   通用 UI 组件
│   └── theme/                 #   主题与样式
├── repository/                # 业务逻辑层
│   ├── SyncTaskRepository      #   任务增删改查
│   ├── LocalScanRepository     #   本地扫描、变更检测
│   ├── WebDavUploadRepository  #   上传/删除执行
│   ├── SettingsRepository      #   加密设置读写
│   ├── DeveloperLogRepository  #   日志
│   └── NetworkStateProvider    #   网络状态(Wi-Fi/移动)
├── sync/                      # 本地文件夹扫描器
├── webdav/                    # WebDAV 客户端(OkHttp)
├── data/                      # Room 实体、DAO、数据库
│   ├── entity/                #   SyncTask / SyncFileRecord / LogRecord
│   ├── dao/
│   └── database/
└── util/                      # 哈希、权限、格式化等工具
```

## 环境要求

- Android Studio(较新版本)
- JDK 17
- Android SDK:`compileSdk` / `targetSdk` 36,`minSdk` 26
- 一个 WebDAV 账号(如坚果云,设置中填写第三方应用管理生成的「应用密码」)

## 构建与运行

```bash
# 克隆
git clone https://github.com/Shirmy/LocalWebDavSync.git
cd LocalWebDavSync

# 调试构建
./gradlew assembleDebug

# 或在 Android Studio 中打开并直接运行
```

`local.properties` 会由 Android Studio 自动生成 SDK 路径。该文件已被 `.gitignore` 忽略,不会进入版本库。

### 发布签名(可选)

Release 构建的签名信息从 `local.properties` 读取(若未配置,则使用默认未签名/调试逻辑)。在 `local.properties` 中添加:

```properties
RELEASE_STORE_FILE=your-release.jks
RELEASE_STORE_PASSWORD=你的密钥库密码
RELEASE_KEY_ALIAS=你的别名
RELEASE_KEY_PASSWORD=你的密钥密码
```

> ⚠️ `*.jks` 密钥库文件和 `local.properties`(含明文签名密码)均已被 `.gitignore` 排除,**切勿提交到仓库**。

## 使用步骤

1. 打开应用,进入「设置」,填写 WebDAV 地址、用户名和应用密码,点击「测试并保存」。
2. 首次使用按提示授予文件管理权限(`MANAGE_EXTERNAL_STORAGE`)。
3. 回到主页「新建任务」:选择本地文件夹、选择/新建 WebDAV 远程文件夹、设置删除模式与是否仅 Wi-Fi。
4. 在主页对单个任务执行扫描同步,或用「扫描同步全部」批量处理。
5. 在扫描详情和日志页查看结果,失败项可重试。

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 访问 WebDAV 服务器 |
| `ACCESS_NETWORK_STATE` | 判断 Wi-Fi / 移动网络以支持「仅 Wi-Fi」 |
| `MANAGE_EXTERNAL_STORAGE` | 读取被同步的本地文件夹 |

## 关于本项目

本项目是一次 **完全由 AI 完成代码编写** 的实践:作者全程没有手写任何一行代码,所有 Kotlin 源码、Compose 界面、WebDAV 同步逻辑、Room 数据层及构建配置均由 AI 生成。作者的角色仅限于描述需求、选择方案和验收结果。

## 许可证

本项目基于仓库内的 [LICENSE](LICENSE) 文件授权。
