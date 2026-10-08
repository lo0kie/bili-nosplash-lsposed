# bilibili防瞎眼

LSPosed 模块：让 B 站的启动屏跟随系统深色模式，消掉冷启动那段白屏。

## 下载

[Releases](https://github.com/lo0kie/bili-nosplash-lsposed/releases) —— 装 `-release` 那个。

## 使用

1. 启用模块，作用域勾选 `tv.danmaku.bili` 与 `com.android.systemui`；
2. 强停 bilibili 与系统界面（SystemUI），再打开 B 站。

## 已验证

实测通过：

- **8.34.0**（OnePlus PLK110 / ColorOS 17 / Android 17）
- **9.13.0**（OnePlus PJD110 / ColorOS 16 / Android 16）

## 编译

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

## 许可

[MIT](LICENSE)
