# 版本发布流程

本文记录 Linkora 发布到 Maven Central（Central Portal）的完整步骤与注意事项。
发布配置在根 [pom.xml](../pom.xml) 的 `release` profile 中（source / javadoc / GPG / central-publishing）。

## 一次性前置（已完成，换机器才需要重做）

| 项 | 说明 |
|---|---|
| Central Portal 账号 | [central.sonatype.com](https://central.sonatype.com) 用 GitHub 账号（lianghang1）登录，namespace `io.github.lianghang1` 自动验证 |
| 发布 token | Portal → View Account → Generate User Token，写入 `D:\apache-maven-3.8.8\conf\settings.xml` 的 `<servers>`（`<id>central</id>`，**必须与 pom 里 `publishingServerId` 一致**）。注意：Maven 重装会覆盖 conf 目录，token 可在 Portal 重新生成 |
| GPG 密钥 | Gpg4win 装在 `D:\DevTools\GnuPG\`（不在 PATH）。签名密钥指纹 `866F80611678E67D3DE301692475E73279D8E692`（ed25519、空口令），公钥已传 keyserver.ubuntu.com。私钥/公钥/吊销证书备份在 `C:\Users\lh\Documents\GPG-backup-lianghang\`，**务必再拷一份到 U 盘或网盘** |

## 发版步骤（每个版本）

```powershell
# 1) 全模块版本号转正式版（PowerShell 的 -D 参数必须整体加引号）
mvn versions:set "-DnewVersion=0.2.0" "-DgenerateBackupPoms=false"

# 2) 检查 diff（根 pom 与全部模块 pom 的 <version>），提交
git add -A
git commit -m '发版 0.2.0'

# 3) 构建签名并上传 Portal（PowerShell 必须先把 GPG 加入 PATH）
$env:Path = 'D:\DevTools\GnuPG\bin;' + $env:Path
mvn clean deploy -Prelease

# 4) 构建结束时日志输出 deploymentId，Portal → Deployments 核对组件清单后点 Publish
#    （autoPublish=false，永远人工确认后发布）
# 5) 约 30 分钟后生效，验证：
#    https://repo1.maven.org/maven2/io/github/lianghang1/linkora-parent/maven-metadata.xml
```

发版后回到开发版本：

```powershell
mvn versions:set "-DnewVersion=0.3.0-SNAPSHOT" "-DgenerateBackupPoms=false"
git add -A; git commit -m '进入 0.3.0-SNAPSHOT 开发'
```

## 发版核对清单

1. **组件清单必须只有 10 个**：parent + core/transport/serialize-jackson/server/client/
   client-storage-sqlite/server-storage-jdbc/spring/spring-boot-starter。
   出现任何 `linkora-example-*` / `linkora-testkit` → **立即 Drop，严禁 Publish**
   （Central 发布后永久不可删除）。
2. 新增示例模块时，必须把新 artifactId 追加进根 pom 插件配置的 **`excludeArtifacts`**
   （该插件不识别 `maven.deploy.skip` 属性，漏加就会把示例发上 Central）。
3. `mvn versions:set` 只改 `<version>`，**不会**更新示例应用的 `linkora.version` 属性——
   四个 spring-boot/mvc 示例固定引用已发布正式包，发版后手动把它们升到新版本并冒烟验证
   （见 [examples/spring-boot-server](../examples/spring-boot-server/README.md)）。

## 已踩过的坑

- **central-publishing-maven-plugin 必须 ≥ 0.12.0**：0.6.0 无法解析 Portal 响应新增的
  `warnings` 字段（`UnrecognizedPropertyException`，此时上传其实已成功，勿慌）。
- **插件不识别 `maven.deploy.skip`**：排除模块只有 `excludeArtifacts` 一种官方机制。
- Portal 组件清单中 pom 打包组件显示两行（`?type=pom` 与无 type），parent 实际 1 个组件显示 2 行。
- 公司 nexus 镜像（`mirrorOf=*`）不影响发版：central-publishing 插件直连
  `central.sonatype.com`，不走 Maven mirror 机制。
- Git 自带的 MSYS gpg 缺 keyboxd 不可用，必须用 Gpg4win 的 gpg。
