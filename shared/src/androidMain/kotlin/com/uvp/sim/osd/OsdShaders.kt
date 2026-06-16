package com.uvp.sim.osd

/**
 * OSD 渲染管线 GLSL ES 3.0 shader 源码 — 4 个 program。
 *
 * 全部 `#version 300 es`,跟 EGL 1.4 + GL ES 3.0 配置匹配。
 *
 * 使用方:
 * - [CAMERA_VERTEX] + [CAMERA_FRAGMENT_OES] → CameraTexturePass(OES external 纹理 → fbo)
 * - [OSD_TEXT_VERTEX] + [OSD_TEXT_FRAGMENT_SDF] → OsdTextPass(SDF 字体 + 描边)
 *
 * 顶点坐标系 NDC([-1, 1]),OSD 文本由 CPU 端布局算出 NDC 顶点直接传入。
 */
internal object OsdShaders {

    /**
     * 摄像头纹理顶点 shader — 全屏四边形。
     *
     * 输入:
     * - aPosition: vec2 顶点 NDC(-1..1)
     * - aTexCoord: vec2 OES 纹理坐标(0..1)
     *
     * 输出:
     * - vTexCoord: 传给 fragment shader
     */
    const val CAMERA_VERTEX = """#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;
out vec2 vTexCoord;
uniform mat4 uTexMatrix;
void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
}
"""

    /**
     * 摄像头纹理 fragment shader — 采样 OES external texture(SurfaceTexture)。
     *
     * 必须用 GL_OES_EGL_image_external_essl3 扩展(GL ES 3.0 + ESSL 300 兼容)。
     */
    const val CAMERA_FRAGMENT_OES = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTexture;
in vec2 vTexCoord;
out vec4 fragColor;
void main() {
    fragColor = texture(uTexture, vTexCoord);
}
"""

    /**
     * OSD 文本顶点 shader — 接受 CPU 算好的 NDC 坐标 + atlas UV。
     *
     * 输入:
     * - aPosition: vec2 顶点 NDC
     * - aUv: vec2 atlas UV(0..1)
     */
    const val OSD_TEXT_VERTEX = """#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aUv;
out vec2 vUv;
void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vUv = aUv;
}
"""

    /**
     * OSD 文本 SDF fragment shader — 单色填充 + 描边。
     *
     * SDF 距离场存在 atlas 的 R 通道,0.5 是字符边界:
     * - dist > 0.5 字符内部
     * - dist > 0.5 - outlineWidth 描边内部
     *
     * smoothstep 抗锯齿,边缘 ±0.05 距离单位。
     *
     * uniform:
     * - uAtlas: 字体 atlas
     * - uFillColor: 主色
     * - uOutlineColor: 描边色
     * - uOutlineWidth: 描边宽度(SDF 距离单位,推荐 0.1 ~ 0.2)
     */
    const val OSD_TEXT_FRAGMENT_SDF = """#version 300 es
precision mediump float;
uniform sampler2D uAtlas;
uniform vec4 uFillColor;
uniform vec4 uOutlineColor;
uniform float uOutlineWidth;
in vec2 vUv;
out vec4 fragColor;
void main() {
    float dist = texture(uAtlas, vUv).r;
    // 抗锯齿宽度用屏幕空间导数自适应:一个屏幕像素在距离场里跨多少,
    // smoothstep 就软化多少。固定阈值在字号缩放后会糊,fwidth 在任意字号都得到 ~1px 锐利边缘。
    float aa = fwidth(dist);
    float fillAlpha = smoothstep(0.5 - aa, 0.5 + aa, dist);
    float outlineAlpha = smoothstep(0.5 - uOutlineWidth - aa,
                                    0.5 - uOutlineWidth + aa, dist);
    vec4 color = mix(uOutlineColor, uFillColor, fillAlpha);
    color.a *= outlineAlpha;
    if (color.a < 0.001) discard;
    fragColor = color;
}
"""
}
