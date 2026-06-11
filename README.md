# Peek

Peek 是一款 Android 应用，通过蓝牙连接你的 **Keep C1 Mini（CC_23）** 动感单车，记录每次骑行的数据，并生成标准的 Garmin FIT 文件——可以直接上传到 Strava。

另外还支持 **FTMS 桥接**（⚠️ 待测试）：把手机变成一个蓝牙中转站，让 Zwift、Mywhoosh、TrainerRoad 等骑行软件也能连上你的 Keep 单车。**此功能已实现但还没实际测试过，上次尝试没连上，暂时没有新的测试机会。**

## 功能

- **蓝牙连接** Keep C1 Mini 单车——扫描、连接、自动识别
- **实时数据显示**——踏频、功率（瓦特）、阻力、速度、距离、卡路里
- **心率监测**——可以额外连接一个蓝牙心率带
- **骑行记录**——每秒记录一次数据，实时计算平均值
- **FIT 文件导出**——生成标准格式的骑行记录文件，包含踏频、功率、心率、阻力变化
- **上传 Strava**——在 app 里一键上传到 Strava，自动管理登录凭证
- **FTMS 桥接**（⚠️ 待测试）——手机模拟成标准骑行台，让 Zwift 等软件能连上
- **ERG + 模拟模式**（⚠️ 待测试）——支持目标功率训练和户外路感模拟
- **功率校准**（⚠️ 待测试）——学习你单车的阻力与功率对应关系，让 ERG 模式更准确
- **历史记录**——本地保存每次骑行的数据，可以随时查看和分享

## 使用条件

- 安卓手机，系统版本 8.0 或更高
- 一台 Keep C1 Mini 动感单车（型号 CC_23）
- 蓝牙心率带（可选，不接也能用）
- Strava 账号（可选，需要上传才用到）

## 安装

去 [Releases](https://github.com/Liki4/Peek/releases) 页面下载最新的 APK 文件，在手机上直接安装即可。

> 手机可能会提示"未知来源"，点"允许"就行，这是你自己下载的安装包，没有安全问题。

## 怎么用

1. **打开 app**——首次打开会弹窗请求蓝牙和通知权限，点"允许"。
2. **扫描单车**——app 会自动搜索附近的 Keep C1 Mini。如果没搜到，确认单车已通电。
3. **连接**——点一下列表里的单车，app 会自动完成连接和握手。
4. **开始骑行**——点"开始"按钮，app 会提示按下单车的旋钮。走到单车前按下旋钮，几秒倒计时后就开始记录。
5. **骑行中**——屏幕上实时显示踏频、功率、速度、距离、心率。可以把手机架在车头当码表用。
6. **暂停/继续**——点 app 里的"暂停"按钮，或者短按一下单车的旋钮，都可以暂停。再点一次或再按一次旋钮，继续骑行。
7. **结束骑行**——点"停止"按钮，或者长按单车旋钮，骑行结束。FIT 文件会自动生成并保存。
8. **上传 Strava**——结束页面上点"上传到 Strava"（需要先在设置里配好 Strava 凭据）。

> 你也可以直接按单车上的旋钮开始骑行，不需要先在 app 上点"开始"，app 会自动同步。

## 设置说明

| 设置项 | 说明 |
|---|---|
| 用户 ID / 设备 ID | 连接单车用的身份标识，首次安装会自动生成，不用管 |
| 体重（公斤） | 你的体重，功率校准和模拟模式会用到 |
| Strava 凭据 | 上传到 Strava 需要填的三样东西（获取方法见下方教程） |
| FTMS 桥接 | 打开后手机模拟成骑行台，Zwift 等软件可以连上来 |
| 调试日志 | 记录详细的骑行数据日志，一般用不到，出问题时排查用 |

## 心率带怎么连

在「连接单车」那个页面，除了扫描单车，还会同时扫描心率带。戴上心率带，在列表里找到它，点一下就连上了。

## Strava 凭据获取教程

Peek 上传 FIT 文件到 Strava 需要三个东西：Client ID、Client Secret、Refresh Token。

### 第一步：创建 Strava API 应用

1. 用浏览器打开 [strava.com/settings/api](https://www.strava.com/settings/api)，登录你的 Strava 账号
2. 填写以下信息：
   - **Application Name**：填 `Peek`（或者你喜欢的名字）
   - **Website**：填 `http://localhost`
   - **Authorization Callback Domain**：填 `localhost`
   - 图标可以不上传
3. 点 **Create**，创建完成后页面上会显示 **Client ID** 和 **Client Secret**，记下来

### 第二步：获取 Refresh Token

打开浏览器，在地址栏输入以下网址（把里面的 `YOUR_CLIENT_ID` 换成你自己的 Client ID）：

```
https://www.strava.com/oauth/authorize?client_id=YOUR_CLIENT_ID&response_type=code&redirect_uri=http://localhost&approval_prompt=force&scope=activity:write
```

点 **Authorize**（授权）。浏览器会跳转到一个打不开的页面（这是正常的），看地址栏，里面有个 `code=` 后面跟的一串字符（例如 `abc123...`），把它复制下来。

然后打开电脑的 **终端**（Mac）或 **命令提示符**（Windows），粘贴以下命令，把里面的三处英文分别换成你自己的 Client ID、Client Secret 和刚才复制的 code：

```bash
curl -X POST https://www.strava.com/oauth/token \
  -d client_id=你的ClientID \
  -d client_secret=你的ClientSecret \
  -d code=你复制的code \
  -d grant_type=authorization_code
```

回车运行，返回的结果里有一个 `refresh_token`，把它复制下来。

### 第三步：填入 Peek

打开 Peek，进入设置页面，把 **Client ID**、**Client Secret**、**Refresh Token** 三个值分别填进去，保存即可。Refresh Token 长期有效，配一次就行。

## 开发相关

构建和测试：

```bash
./gradlew :app:assembleDebug          # 编译调试版 APK
./gradlew :app:assembleRelease        # 编译正式版 APK（需要签名配置）
./gradlew :app:testDebugUnitTest      # 运行测试
```

技术栈：**Kotlin / Jetpack Compose** — 单模块 Android 应用，Nordic BLE 处理蓝牙，Garmin FIT SDK 生成骑行文件，Room 数据库做本地存储，OkHttp 访问 Strava API。

## License

MIT