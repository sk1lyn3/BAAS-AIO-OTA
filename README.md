# BAAS AIO OTA

以 [BAAS AIO](https://github.com/sk1lyn3/BAAS-AIO) 为基础，测试 core 在线更新及构建发布流程。

应用包名为 `moe.baas.aio.ota`，使用独立签名和更新频道，可与 BAAS AIO 同时安装。

- `dev`：开发和日常验证；`main`：准备发布的版本。两者均包含完整源码和工作流。
- 本地构建：`git clone --recurse-submodules` 后，使用 `tools/build_apk.sh`；构建与签名参数沿用 BAAS AIO。
- 普通构建：手动运行 **Build AIO APK**，选择 Release 或 Debug，下载 Actions 产物；不会自动发布。
- APK 发布：合入 `main`，更新版本及 `.github/release-notes/v版本号.md`，创建对应标签，再运行 **Prepare AIO Release**。生成包含 Release／Debug APK 的草稿，审核后发布。
- core 更新：准备 `.github/release-notes/core-提交前七位.md`，手动运行 **Publish core package**。工作流签名、发布更新包并切换频道，无需发布 APK。

发布说明使用“新的功能”“问题修复”分组；core 仅有活动更新时可只保留对应分组。已有 `core-test` 分支保留作历史测试样本。
