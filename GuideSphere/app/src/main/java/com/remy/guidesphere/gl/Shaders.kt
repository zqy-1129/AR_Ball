package com.remy.guidesphere.gl

/**
 * 全部 GLSL ES 3.0 着色器源码。
 *
 * 设计要点：
 *  - 球壳 / 网格线共用一套「菲涅尔」着色器，通过 uBias / uPower 调节轮廓强度；
 *  - 光点用 gl_PointSize 做透视校正的点精灵，片元里用径向衰减模拟辉光；
 *  - 全流程使用**预乘 alpha**（premultiplied alpha），因此混合因子为
 *    (ONE, ONE_MINUS_SRC_ALPHA) 表示 over，(ONE, ONE) 表示 add；
 *  - 若将来新增**跨顶点/片元两段共用**的 uniform，两段必须显式声明同一精度
 *    （顶点段 float 默认 highp、片元段默认 mediump，不写限定符会直接链接失败：
 *    "Uniform xxx precision mismatch with other stage. Error: Linking failed."）。
 *    当前已经没有任何跨阶段共用的 uniform，但这条规则要一直遵守。
 *  - 禁止使用反向 smoothstep（edge0 > edge1）：部分移动端驱动上的行为未定义，
 *    统一写成 `1.0 - smoothstep(小, 大, x)`。
 */
internal object Shaders {

    /**
     * 球壳 / 网格线。
     * aPos 为单位球面上的位置，aNormal 与之相等（球面法线即位置方向）。
     */
    const val SHELL_VS = """
        #version 300 es
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec3 aNormal;

        uniform mat4 uMvp;
        uniform mat4 uModel;

        out vec3 vNormalW;
        out vec3 vPosW;

        void main() {
            vec4 wp = uModel * vec4(aPos, 1.0);
            vPosW = wp.xyz;
            vNormalW = mat3(uModel) * aNormal;
            gl_Position = uMvp * vec4(aPos, 1.0);
        }
    """

    const val SHELL_FS = """
        #version 300 es
        precision mediump float;

        in vec3 vNormalW;
        in vec3 vPosW;

        uniform vec3 uCamPos;
        uniform vec3 uCoreColor;
        uniform vec3 uRimColor;
        uniform float uAlpha;
        uniform float uBias;
        uniform float uPower;

        out vec4 fragColor;

        void main() {
            vec3 N = normalize(vNormalW);
            vec3 V = normalize(uCamPos - vPosW);
            float ndv = clamp(abs(dot(N, V)), 0.0, 1.0);
            float fres = pow(1.0 - ndv, uPower);
            float a = uAlpha * (uBias + (1.0 - uBias) * fres);
            vec3 col = mix(uCoreColor, uRimColor, fres);
            fragColor = vec4(col * a, a);
        }
    """

    /**
     * 引导光点（点精灵）。整面点阵是覆盖度的唯一视觉表达，因此这个程序只需要一个通道。
     *
     * location 0: 单位球面上的方向
     * location 1: 已点亮程度 0..1（用于颜色/尺寸插值）
     * location 2: 点亮瞬间的脉冲 0..1（用于放大 + 高光）
     * location 3: “下一个建议面”的高亮程度 0..1（用于呼吸外环）
     */
    const val DOT_VS = """
        #version 300 es
        layout(location = 0) in vec3 aDir;
        layout(location = 1) in float aLit;
        layout(location = 2) in float aPulse;
        layout(location = 3) in float aHint;

        uniform mat4 uMvp;
        uniform mat4 uModel;
        uniform vec3 uCamPos;
        uniform float uPxPerWorld;   // (viewportHeight / 2) / tan(fovY / 2)
        uniform float uDotWorldSize; // 光点世界空间直径
        uniform float uMaxPointSize; // 驱动支持的 gl_PointSize 上限

        out float vLit;
        out float vPulse;
        out float vFacing;
        out float vHint;

        void main() {
            vec4 wp = uModel * vec4(aDir, 1.0);
            vec3 n = normalize(mat3(uModel) * aDir);
            vec3 viewDir = normalize(uCamPos - wp.xyz);
            float facing = dot(n, viewDir);

            vLit = aLit;
            vPulse = aPulse;
            vFacing = facing;
            vHint = aHint;

            gl_Position = uMvp * vec4(aDir, 1.0);

            // 透视校正：距离越远点越小；背面光点稍微收一点，形成景深层次
            float w = max(gl_Position.w, 0.05);
            float sizePx = (uDotWorldSize * uPxPerWorld) / w;
            sizePx *= mix(0.62, 1.0, clamp((facing + 0.35) / 1.10, 0.0, 1.0));
            sizePx *= (1.0 + 0.85 * aPulse);                 // 点亮瞬间放大
            sizePx *= (1.0 + 0.55 * aHint * (1.0 - aLit));   // 目标面高亮时略放大
            gl_PointSize = clamp(sizePx, 1.0, uMaxPointSize);
        }
    """

    const val DOT_FS = """
        #version 300 es
        precision mediump float;

        in float vLit;
        in float vPulse;
        in float vFacing;
        in float vHint;

        uniform vec3 uLitColor;
        uniform vec3 uDimColor;
        uniform vec3 uHintColor;
        uniform float uDimStrength;
        uniform float uLitStrength;

        out vec4 fragColor;

        void main() {
            vec2 pc = gl_PointCoord * 2.0 - 1.0;
            float r2 = dot(pc, pc);
            if (r2 > 1.0) discard;   // 裁成圆形，避免方点
            float r = sqrt(r2);

            float glow = exp(-r2 * 3.6);              // 柔和外辉光
            float core = 1.0 - smoothstep(0.0, 0.55, r); // 中心实心核

            // 面向相机的光点更亮，背面的作为“透视提示”压暗
            float face01 = clamp((vFacing + 0.45) / 1.15, 0.0, 1.0);
            float dimA = uDimStrength * (0.42 + 0.58 * face01);
            float litA = uLitStrength * (0.34 + 0.66 * face01);

            vec3 dimCol = mix(uDimColor, uHintColor, vHint * 0.85);
            vec3 litCol = mix(uLitColor, vec3(1.0), core * 0.75 + vPulse * 0.55);

            float a = mix(dimA, litA, vLit) * glow;
            vec3 col = mix(dimCol, litCol, vLit);

            // 点亮瞬间额外补一圈高光
            a += vPulse * 0.45 * glow;
            col += vec3(0.6, 0.95, 1.0) * vPulse * glow * 0.55;

            // 目标面：未点亮时保留一圈外环并呼吸，明确指出"接下来该扫这一整片"
            if (vHint > 0.01 && vLit < 0.5) {
                float ring = smoothstep(0.62, 0.72, r) * (1.0 - smoothstep(0.86, 0.98, r));
                a += vHint * ring * 1.15;
                col += uHintColor * vHint * ring * 1.2;
                a += vHint * glow * 0.35;
            }

            fragColor = vec4(col * a, a);
        }
    """

    /**
     * 全屏背景（相机不可用时的降级渐变），也用于在需要时铺底。
     */
    const val BG_VS = """
        #version 300 es
        layout(location = 0) in vec2 aPos;
        out vec2 vUv;
        void main() {
            vUv = aPos * 0.5 + 0.5;
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """

    const val BG_FS = """
        #version 300 es
        precision mediump float;
        in vec2 vUv;
        uniform vec3 uTop;
        uniform vec3 uBottom;
        out vec4 fragColor;
        void main() {
            vec3 c = mix(uBottom, uTop, vUv.y);
            fragColor = vec4(c, 1.0);
        }
    """
}
