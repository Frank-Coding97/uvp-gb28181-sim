# Android CI/CD 发布

正式发布由推送 `vMAJOR.MINOR.PATCH` 标签触发。GitHub Actions 会先完成测试，再使用受保护的 release keystore 构建 APK，生成 `mobile.json`，通过 SSH 上传到 115，并在最后原子替换下载页的元数据指针。

## GitHub Secrets

配置以下仓库 Secrets；值不会写入 Git：

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`
- `DOWNLOAD_DEPLOY_HOST`
- `DOWNLOAD_DEPLOY_PORT`
- `DOWNLOAD_DEPLOY_USER`
- `DOWNLOAD_DEPLOY_SSH_KEY`
- `DOWNLOAD_DEPLOY_KNOWN_HOSTS`

`DOWNLOAD_DEPLOY_KNOWN_HOSTS` 必须是维护者核验过的服务器 host key，不能在 CI 中用首次连接自动接受替代。

当前 115 站点目录固定为 `/opt/uvp-download-site`；部署用户只需要 `releases/`、`.ci-staging/` 和 `.backups/` 三个目录的写权限，不需要 root 或 Docker 权限。

## 发布与回滚

```bash
git tag v1.0.4
git push origin v1.0.4
```

发布失败不会替换 `releases/mobile.json`。发布脚本会在 `/opt/uvp-download-site/.backups/` 保留旧元数据；需要人工回滚时，将备份的 `mobile.json` 原子移回 `releases/mobile.json`，再用 HTTPS 下载校验恢复结果。

CI 自动验收线上 JSON、APK 字节数和 SHA-256；真实 Android 安装与启动仍需人工验收。
