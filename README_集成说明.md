# 弹幕通知字号 — ZTool 集成说明

## 功能

在「游戏助手 → 功能拓展」分组新增一张卡片，交互与「充电功率显示类型」一致（点击右侧下拉小窗取值）。

```
弹幕通知字号                              不启用 ▾
调整通知弹幕的文字大小，默认不启用，保持系统原样
```

下拉选项：不启用 / 16 / 18 / 20 / 22 / 24 / 26 / 28 / 30 / 32 sp

默认「不启用」= 完全不改动系统原有字号。

## 改动文件（7 个，1 个新建）

| 文件 | 改动 |
|---|---|
| `hook/modules/gametool/DanmuFontSizeHook.kt` | **新建**。核心 Hook |
| `data/keys/PreferenceKeys.kt` | 新增 2 个键 |
| `hook/base/HookManager.kt` | import + 1 行注册 |
| `data/gametool/GameToolSettingsRepository.kt` | 读写配置 |
| `viewmodel/GameToolSettingsViewModel.kt` | state 字段 + setter |
| `screens/gametool/GameToolSettingsScreen.kt` | 回调透传 + 卡片 |
| `res/values/strings.xml` | 追加到文件末尾 |

## 关键设计：为什么用两个键

`BaseHookModule.isEnabled()` 会读 **`getModuleName()` 对应的 Boolean 键**，
读不到就整个模块跳过。所以不能只用一个 FloatKey，必须拆成：

| 键 | 类型 | 作用 |
|---|---|---|
| `danmu_font_size_enable` | `BoolKey` | 模块开关，`getModuleName()` 返回它 |
| `danmu_font_size` | `FloatKey` | 实际字号，`0f` = 不启用 |

保存时两个一起写：`sizeSp > 0f` → enable=true。与项目里
`DISABLE_GAME_AUDIO` / `DISABLE_GAME_AUDIO_APP` 是同一套模式。

## Hook 实现要点

Hook 点：`com.zui.game.service.ui.NotificationMsgController#getTextView`

在 after 阶段拿到返回的 TextView 改字号。这是唯一可靠点，因为：
- 弹幕布局里 `android:textSize` 是硬编码的
- App 拿到 TextView 后只做 setText / setVisibility / setAlpha，**不会重置字号**
- 所以 after 阶段改完能稳定保留

同时按比例缩放 compound icon（图文混排时图标不脱节）。

配置**每次渲染都读**，修改后**下一条弹幕即刻生效**，无需重启游戏助手。
RemotePreferences 在进程内是有缓存的，开销可忽略。

## 合并方式

整个 `app/` 目录覆盖进你的仓库即可：

```bash
cp -r ztool_danmu_font_src/app/* <你的仓库>/app/
```

## 构建后需要做的事

1. 编译出 APK 并安装（需自行操作，不要自动安装）
2. LSPosed 里确认模块作用域勾选了 **com.zui.game.service**
3. 重启游戏助手（或点页面右下角「重启作用域」）
4. 进「游戏助手 → 功能拓展」选一个字号，再触发一条通知看效果

## 如果字号没变化

先看 LSPosed 日志里有没有：
- `Successfully hooked com.zui.game.service.ui.NotificationMsgController.getTextView`
  → 没有则 Hook 没装上，检查作用域
- `getTextView -> 22.0sp => 28.0sp`
  → 有这行但视觉没变，说明还有别的渲染路径，需要补 Hook 点

## 已知限制

- 本环境无法编译验证（需要 compileSdk 37 / AGP 9.4.1 / Kotlin 2.3.21，
  当前只有 34 / 8.2.0 / 2.0.21），代码已通过静态语法与交叉引用校验，
  但仍建议你本地编译时留意 IDE 提示。
