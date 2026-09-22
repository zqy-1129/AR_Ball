package com.remy.guidesphere.gl

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 四元数 / 3x3 旋转矩阵工具。
 *
 * 约定（与 Android SensorManager 一致）：
 *  - 四元数 q = [w, x, y, z]
 *  - 3x3 矩阵以 **行主序 (row-major)** 存放在 FloatArray(9) 中
 */
object QuatMath {

    /** 把四元数写入长度为 4 的数组，顺带做归一化；退化时重置为单位四元数。 */
    fun normalize(q: FloatArray) {
        val len = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (len < 1e-6f || !len.isFinite()) {
            q[0] = 1f; q[1] = 0f; q[2] = 0f; q[3] = 0f
            return
        }
        val inv = 1f / len
        q[0] *= inv; q[1] *= inv; q[2] *= inv; q[3] *= inv
    }

    /**
     * 球面线性插值：把 [from] 向 [to] 推进 t (0..1)。
     * 结果写入 [out]。带半球一致性处理，避免插值走远路。
     */
    fun slerp(out: FloatArray, from: FloatArray, to: FloatArray, t: Float) {
        var w1 = to[0]; var x1 = to[1]; var y1 = to[2]; var z1 = to[3]
        var cos = from[0] * w1 + from[1] * x1 + from[2] * y1 + from[3] * z1

        // 保证走短弧
        if (cos < 0f) {
            cos = -cos
            w1 = -w1; x1 = -x1; y1 = -y1; z1 = -z1
        }

        if (cos > 0.9995f) {
            // 几乎重合，退化为线性插值 + 归一化，数值更稳
            out[0] = from[0] + t * (w1 - from[0])
            out[1] = from[1] + t * (x1 - from[1])
            out[2] = from[2] + t * (y1 - from[2])
            out[3] = from[3] + t * (z1 - from[3])
            normalize(out)
            return
        }

        val theta0 = kotlin.math.acos(cos.coerceIn(-1f, 1f))
        val theta = theta0 * t
        val sinTheta = kotlin.math.sin(theta)
        val sinTheta0 = kotlin.math.sin(theta0)
        val s0 = kotlin.math.cos(theta) - cos * sinTheta / sinTheta0
        val s1 = sinTheta / sinTheta0
        out[0] = s0 * from[0] + s1 * w1
        out[1] = s0 * from[1] + s1 * x1
        out[2] = s0 * from[2] + s1 * y1
        out[3] = s0 * from[3] + s1 * z1
        normalize(out)
    }

    /**
     * 四元数 -> 3x3 旋转矩阵（行主序），表示「设备坐标系 -> 世界坐标系」。
     * 与 SensorManager.getRotationMatrixFromVector 的语义保持一致。
     */
    fun toRotationMatrix(out9: FloatArray, q: FloatArray) {
        val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z

        out9[0] = 1f - 2f * (yy + zz); out9[1] = 2f * (xy - wz);       out9[2] = 2f * (xz + wy)
        out9[3] = 2f * (xy + wz);       out9[4] = 1f - 2f * (xx + zz); out9[5] = 2f * (yz - wx)
        out9[6] = 2f * (xz - wy);       out9[7] = 2f * (yz + wx);       out9[8] = 1f - 2f * (xx + yy)
    }

    /** 把行主序 3x3 矩阵转置写入 out9。 */
    fun transpose3(out9: FloatArray, src9: FloatArray) {
        out9[0] = src9[0]; out9[1] = src9[3]; out9[2] = src9[6]
        out9[3] = src9[1]; out9[4] = src9[4]; out9[5] = src9[7]
        out9[6] = src9[2]; out9[7] = src9[5]; out9[8] = src9[8]
    }

    /**
     * 行主序 3x3 旋转矩阵 -> 四元数 [w, x, y, z]。
     * 用于把加速度计 + 磁力计解算出的矩阵并入统一的四元数平滑流程。
     */
    fun fromRotationMatrix(out4: FloatArray, m9: FloatArray) {
        val m00 = m9[0]; val m01 = m9[1]; val m02 = m9[2]
        val m10 = m9[3]; val m11 = m9[4]; val m12 = m9[5]
        val m20 = m9[6]; val m21 = m9[7]; val m22 = m9[8]
        val trace = m00 + m11 + m22
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            out4[0] = 0.25f * s
            out4[1] = (m21 - m12) / s
            out4[2] = (m02 - m20) / s
            out4[3] = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            out4[0] = (m21 - m12) / s
            out4[1] = 0.25f * s
            out4[2] = (m01 + m10) / s
            out4[3] = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            out4[0] = (m02 - m20) / s
            out4[1] = (m01 + m10) / s
            out4[2] = 0.25f * s
            out4[3] = (m12 + m21) / s
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            out4[0] = (m10 - m01) / s
            out4[1] = (m02 + m20) / s
            out4[2] = (m12 + m21) / s
            out4[3] = 0.25f * s
        }
        normalize(out4)
    }

    /**
     * 把「行主序 3x3」旋转 + 均匀缩放写成 OpenGL 使用的「列主序 4x4」矩阵。
     * 输出的 4x4 只含旋转与缩放（无平移），第 15 位为 1。
     */
    fun toGlMatrix(out16: FloatArray, rotRowMajor9: FloatArray, scale: Float) {
        // 行主序 r[row*3+col] -> 列主序 m[col*4+row]
        out16[0] = rotRowMajor9[0] * scale
        out16[1] = rotRowMajor9[3] * scale
        out16[2] = rotRowMajor9[6] * scale
        out16[3] = 0f

        out16[4] = rotRowMajor9[1] * scale
        out16[5] = rotRowMajor9[4] * scale
        out16[6] = rotRowMajor9[7] * scale
        out16[7] = 0f

        out16[8] = rotRowMajor9[2] * scale
        out16[9] = rotRowMajor9[5] * scale
        out16[10] = rotRowMajor9[8] * scale
        out16[11] = 0f

        out16[12] = 0f
        out16[13] = 0f
        out16[14] = 0f
        out16[15] = 1f
    }

    /**
     * 由方位角/俯仰角构造「环绕物体的虚拟相机」姿态矩阵（设备->世界，行主序）。
     *
     * 相机位于球坐标 (azimuth, elevation)，始终朝向原点（即被拍摄物体）。
     * 该矩阵的行向量分别为相机的 right / up / back 轴在世界坐标下的表示，
     * 正好等于 世界->设备 的旋转矩阵（即设备->世界的转置）。
     *
     * @param out9 输出行主序 3x3
     */
    fun cameraOrbitToWorldToDevice(out9: FloatArray, azimuthRad: Float, elevationRad: Float) {
        val ce = kotlin.math.cos(elevationRad)
        val se = kotlin.math.sin(elevationRad)
        val ca = kotlin.math.cos(azimuthRad)
        val sa = kotlin.math.sin(azimuthRad)

        // 相机位置方向（单位向量，物体在原点）
        val px = ce * ca
        val py = ce * sa
        val pz = se

        // 相机光轴（指向物体）
        val fx = -px; val fy = -py; val fz = -pz

        // 世界上方向 (0,0,1)，退化时用 (0,1,0)
        var ux = 0f; var uy = 0f; var uz = 1f
        if (abs(fz) > 0.999f) { ux = 0f; uy = 1f; uz = 0f }

        // right = normalize(cross(forward, up))
        var rx = fy * uz - fz * uy
        var ry = fz * ux - fx * uz
        var rz = fx * uy - fy * ux
        val rl = sqrt(rx * rx + ry * ry + rz * rz).coerceAtLeast(1e-5f)
        rx /= rl; ry /= rl; rz /= rl

        // up' = cross(right, forward)
        val ux2 = ry * fz - rz * fy
        val uy2 = rz * fx - rx * fz
        val uz2 = rx * fy - ry * fx

        // camera->world 的列 = (right, up', -forward)；其转置 = world->device
        // world->device 的行 = (right, up', -forward)
        out9[0] = rx;   out9[1] = ry;   out9[2] = rz
        out9[3] = ux2;  out9[4] = uy2;  out9[5] = uz2
        out9[6] = -fx;  out9[7] = -fy;  out9[8] = -fz
    }
}
