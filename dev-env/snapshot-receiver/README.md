# Snapshot Receiver Mock — GB/T 28181-2022 §9.5

模拟"上级平台"接收下级设备主动 PUT 上传的抓拍 JPEG。WVP-Pro 主分支不实现这条接收路径
(2026-06-17 调研结论),自建平台前用此 mock 验证设备端 7.5 链路是否跑通。

## 启动

```bash
python3 tools/snapshot_receiver.py --port 8088 --dir ./dev-env/snapshot-receiver/incoming
```

输出示例:

```
[receiver] listening on 0.0.0.0:8088, saving to /Users/.../incoming
[receiver] sample UploadURL: http://<本机IP>:8088/snap/
[receiver] PUT /snap/20260617T120000_0.jpg → /Users/.../incoming/20260617T120000_0.jpg (234567 bytes)
```

## 联调流程

1. 启动 receiver:`python3 tools/snapshot_receiver.py`
2. 用 `ifconfig | grep "inet "` 找本机 IP(假设 192.168.1.10)
3. 在 SIP 调试端构造 SnapShotConfig MESSAGE 下发给设备(下文 sipp / curl 示例)
4. 设备收到后会:
   - 抓 N 张 JPEG → 落地 `<filesDir>/snapshots/<日期>/`
   - HTTP PUT 到 `http://192.168.1.10:8088/snap/<SnapShotID>.jpg`
   - 每张上传成功后回一条 SIP MESSAGE Notify(SubCmd=SnapShot)
5. 检查:
   - receiver 控制台收到 N 行 `[receiver] PUT ...`
   - `incoming/` 目录下出现 N 个 .jpg 文件
   - SIP 端收到 N 条 SnapShot Notify,SessionID 一致

## SnapShotConfig 信令样例(GB-2022 §9.5)

下发 SIP MESSAGE 给设备(按设备实际 ID 替换):

```xml
<?xml version="1.0" encoding="GB2312"?>
<Control>
<CmdType>DeviceControl</CmdType>
<SN>17</SN>
<DeviceID>34020000001320000001</DeviceID>
<SnapShotConfig>
<SessionID>S001</SessionID>
<UploadURL>http://192.168.1.10:8088/snap/</UploadURL>
<SnapNum>3</SnapNum>
<Interval>2</Interval>
</SnapShotConfig>
</Control>
```

## 字段约定

- `SessionID`: 平台用来追踪本次会话,设备在 NOTIFY 里回填
- `UploadURL`: 设备 PUT JPEG 的目的地。**末尾 `/`**:设备会自动追加 `<SnapShotID>.jpg`;
  **完整路径**(末尾 `.jpg`):设备直接用,只能上传 1 张
- `SnapNum`: 张数,设备 clamp 到 1..10
- `Interval`: 张间秒数,设备 clamp 到 0..60s

## 失败诊断

| 现象 | 可能原因 |
|---|---|
| receiver 收不到 PUT | 设备网络不通 / UploadURL 写错 / 防火墙阻塞 8088 |
| receiver 收到 PUT 但 body 不是 JPEG SOI(FF D8 FF) | 设备抓拍未成功,看 logcat |
| SIP 端收不到 NOTIFY | 上传成功但发 NOTIFY 失败,看 SystemLogger TransportError |
| SIP 端收到比 SnapNum 少的 NOTIFY | 中间有上传失败,设备策略:失败 retry 3 次后跳过本张不发 NOTIFY(spec AC5) |

## 后续

自建平台时,可参考 `snapshot_receiver.py` 的 `do_PUT` 实现,同样路径接入 / 落盘 / 转发到 minio,
同时实现 SnapShotConfig 下发逻辑(模拟器目前只接收,平台端待补)。
