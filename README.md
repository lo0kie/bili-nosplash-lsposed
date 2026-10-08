# bilibili防瞎眼

LSPosed 模块：让 B 站的启动屏跟随系统深色模式。

## 下载

[Releases](https://github.com/lo0kie/bili-nosplash-lsposed/releases)

## 使用

启用模块（作用域：`tv.danmaku.bili`、`com.android.systemui`）→ **重启SystemUI**。

> ColorOS 会缓存启动快照，**启用模块后需要重启手机**, 否则会短暂出现全宽白条。

> 启动页深色化的部分实现来自[哔哩漫游](https://github.com/yujincheng08/BiliRoaming)。

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
