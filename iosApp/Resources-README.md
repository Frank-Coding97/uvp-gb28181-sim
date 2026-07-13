# iosApp Resources / SceneKit `.scn` (v1.3-C)

**⚠️ 此目录当前不包含 `security_camera.scn`,需要老板手动转换。**

## 手动转换步骤(T-C1-0 + T-C1-1)

Blender 5.x + Xcode 15+ 必备。

### Blender 侧

1. Blender 打开 → `File → Import → glTF 2.0`,加载:
   ```
   composeApp/src/androidMain/assets/security_camera.glb
   ```
2. Object mode 下**新建三个 Empty (Plain Axes)** 并严格命名:
   - `pan_pivot`(顶层)
   - `tilt_pivot`(pan_pivot 子)
   - `zoom_pivot`(tilt_pivot 子)
3. 把原 glb 的 `PTZ_Yaw_Pivot` 及镜头 mesh(Cylinder / Sphere / Sphere.001 / Sphere.002)
   reparent 到 `zoom_pivot` 下,层级:
   ```
   pan_pivot
   └── tilt_pivot
       └── zoom_pivot
           └── 镜头 mesh (Cylinder + Sphere*)
   ```
4. `File → Export → Collada (.dae)`,勾 `Include Empty`,导出到临时目录。

### Xcode 侧

1. Xcode 打开 `.dae`,右键 `Editor → Convert SceneKit scene file (.scn)`。
2. SceneKit Editor 左侧 outline 目视确认 `pan_pivot` / `tilt_pivot` / `zoom_pivot` 三个 empty 节点保留。
3. 另存为 `iosApp/iosApp/Resources/security_camera.scn`(即当前目录)。
4. 打开 `iosApp/project.yml` 确认 `sources: - path: iosApp` 自动包含 Resources/ 下 .scn。
   如果 Resources 未被扫到,补:
   ```yaml
   sources:
     - path: iosApp
     - path: iosApp/Resources
       type: folder
   ```
5. `cd iosApp && xcodegen generate` 重生成 xcodeproj。

## 校验

- `SCNScene.sceneNamed("security_camera.scn")` 返回非 nil
- `SceneKitCameraScene.bindPivots()` 找到 3 个 pivot 名字命中(见 `composeApp/src/iosMain/kotlin/com/uvp/sim/ui/simulate/scenekit/SceneKitCameraScene.kt`)

## 兜底 fallback(资源不到位时)

`SceneKitCameraScene.loadFromBundle()` 检测 `.scn` 缺失时,自动 fallback 到 **纯代码生成的占位场景**
(SCNBox 机身 + SCNCylinder 镜筒 + 手动挂 3 个 SCNNode 作为 pivot)。
体感对比 glb 版本会粗糙(无阴影 / 无材质细节),但保证 v1.3-C 分支不阻塞开发。

正式发版前必须完成上述 glb→dae→scn 转换。
