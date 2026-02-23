# Dubbo Easy Invoke

在 IntelliJ IDEA 右侧直接调用 Dubbo 接口，减少在控制台、文档和 IDE 之间来回切换。

## 特性
- `Dubbo Invoke` Tool Window（右侧面板，类似 Maven/Gradle）
- 设置页配置 Zookeeper 地址
- 应用/接口联动下拉，支持关键字实时模糊匹配
- 入参与结果自动 JSON 美化
- 一键操作：`刷新`、`调用接口`、`解析地址`、`重置`、`粘贴入参`、`复制入参`、`复制结果`
- 内置 Console：按状态分色输出（加载中、成功、失败、提示）并显示调用地址（IP:Port）
- 大量应用/接口场景优化：并发拉取 + 内存缓存 + 磁盘缓存 + 后台刷新最新数据

## 快速开始

### 环境要求
- JDK 21
- IntelliJ IDEA 2025.2+

### 本地运行插件
```bash
./gradlew runIde
```

### 打包
```bash
./gradlew buildPlugin
```

插件 ZIP 默认输出到：`build/distributions/`

## 使用方式
1. 打开 `Settings > Tools > Dubbo Easy Invoke`，配置 Zookeeper 地址（如 `127.0.0.1:2181`）。
2. 在 IDEA 右侧打开 `Dubbo Invoke` 面板。
3. 选择应用和接口，输入入参，点击 `调用接口`。
4. 如需查看目标机器，点击 `解析地址`，在 Console 查看 `IP:Port`。

## 入参说明
- 支持直接输入 Dubbo 表达式：`1, "abc"`
- 支持 JSON（对象 / 数组 / 标量），会自动格式化
- JSON 数组会按原结构透传，例如输入 `[123]` 会按 `[123]` 调用，不会被改写

## 缓存与数据新鲜度
- 首次加载优先命中缓存（内存或磁盘），提升打开速度
- 命中缓存后会自动后台刷新最新注册中心数据
- 点击 `刷新` 会强制拉取最新数据
- 注册中心暂不可用时，会回退到本地缓存

## 开发命令
```bash
./gradlew runIde
./gradlew build
./gradlew buildPlugin
./gradlew verifyPlugin
```

## 后续规划
- 收藏接口与入参（本地保存、搜索、一键回填）
- 方案文档：`FAVORITES_UI_PLAN.md`

## 反馈
欢迎提交 Issue / PR，一起把 Dubbo 调试体验做得更顺手。
