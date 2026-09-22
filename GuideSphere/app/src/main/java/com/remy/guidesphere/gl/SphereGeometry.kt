package com.remy.guidesphere.gl

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 引导光点的网格排布参数：8 个方位扇区 × 每扇区 [SECTOR_COLS] 个方位列 ×
 * [ELEV_ROWS] 个仰角行，每个扇区即一整片均匀铺开的「面」。
 * [SPHERE_DOT_COUNT] 必须等于 8 × [SECTOR_COLS] × [ELEV_ROWS]，与渲染器保持同步。
 */
private const val SECTOR_COUNT_GRID = 8
const val SECTOR_COLS = 3
const val ELEV_ROWS = 7
const val ELEV_SPAN = 80f
const val SPHERE_DOT_COUNT = SECTOR_COUNT_GRID * SECTOR_COLS * ELEV_ROWS

/**
 * 单位球几何数据生成。
 *
 * 三类几何：
 *  1. [shellVertexData] / [shellIndices] 半透明球壳（三角面片），用来形成“玻璃球”体感；
 *  2. [gridVertexData]  经纬网格线（GL_LINES），给用户三维朝向参照；
 *  3. [dotDirections]   引导光点方向，每条方向是一个待拍摄角度。
 *
 * 光点按「8 个方位扇区 × [SECTOR_COLS] 个方位列 × [ELEV_ROWS] 个仰角行」均匀铺开：
 * 每个扇区（45° 竖直楔形）是一整片网格点，扫到该扇区时整片一起点亮，
 * 视觉上就是一个「被标出的面」，而不是零散的单点。
 *
 * 所有几何都是**静态**的，加载期一次性写入 VBO，运行期零重建。
 */
class SphereGeometry(
    val dotCount: Int,
    stacks: Int = 36,
    slices: Int = 54,
    latStepDeg: Float = 30f,
    lonStepDeg: Float = 30f,
    ringSegments: Int = 96
) {

    /** 球壳顶点：pos.xyz + normal.xyz 交错，共 6 float / 顶点 */
    val shellVertexData: FloatArray
    val shellIndices: IntArray

    /** 网格线顶点：同样是 pos.xyz + normal.xyz 交错，按 GL_LINES 成对使用 */
    val gridVertexData: FloatArray

    /** 光点方向，3 float / 点，均为单位向量 */
    val dotDirections: FloatArray = FloatArray(dotCount * 3)

    init {
        // ---------- 球壳 ----------
        val vCount = (stacks + 1) * (slices + 1)
        shellVertexData = FloatArray(vCount * 6)
        var p = 0
        for (i in 0..stacks) {
            val phi = PI * i / stacks           // 0..PI，从北极到南极
            val sinPhi = sin(phi).toFloat()
            val cosPhi = cos(phi).toFloat()
            for (j in 0..slices) {
                val theta = 2.0 * PI * j / slices
                val x = (sinPhi * cos(theta)).toFloat()
                val y = cosPhi
                val z = (sinPhi * sin(theta)).toFloat()
                shellVertexData[p++] = x; shellVertexData[p++] = y; shellVertexData[p++] = z
                shellVertexData[p++] = x; shellVertexData[p++] = y; shellVertexData[p++] = z
            }
        }
        shellIndices = IntArray(stacks * slices * 6)
        var q = 0
        for (i in 0 until stacks) {
            for (j in 0 until slices) {
                val a = i * (slices + 1) + j
                val b = a + slices + 1
                shellIndices[q++] = a
                shellIndices[q++] = b
                shellIndices[q++] = a + 1
                shellIndices[q++] = a + 1
                shellIndices[q++] = b
                shellIndices[q++] = b + 1
            }
        }

        // ---------- 经纬网格 ----------
        val grid = ArrayList<Float>(4096)
        // 纬线（等仰角圆）
        var lat = -90f + latStepDeg
        while (lat < 90f - 1e-3f) {
            appendCircle(grid, lat, steps = latCircleSteps(lat, ringSegments))
            lat += latStepDeg
        }
        // 经线（子午线）
        var lon = 0f
        while (lon < 360f - 1e-3f) {
            appendMeridian(grid, lon, steps = 56)
            lon += lonStepDeg
        }
        gridVertexData = grid.toFloatArray()

        // ---------- 引导光点：按 8 个方位扇区组织的网格点 ----------
        // 用户要求「以一整面均匀分布的多个点来标识」——每个方位扇区（竖直楔形）不再
        // 是零散的 Fibonacci 散点，而是一整片均匀铺开的网格点：扇区横跨 45° 方位、
        // 内部再细分成若干方位列，配合若干仰角行，扫到该扇区时整片点一起点亮，
        // 视觉上就是一个「被标出的面」。扇区归属由 ScanSession 按世界水平面
        // atan2(ny, nx) 推导，与具体排布无关，这里只要保证每个扇区都有点即可。
        var p3 = 0
        for (s in 0 until SECTOR_COUNT_GRID) {
            // 把 45° 扇区切成 COLS 个方位列，均匀铺在扇区内
            for (c in 0 until SECTOR_COLS) {
                val azDeg = (s + (c + 0.5f) / SECTOR_COLS) * (360f / SECTOR_COUNT_GRID)
                val az = Math.toRadians(azDeg.toDouble())
                val azCos = cos(az)
                val azSin = sin(az)
                for (row in 0 until ELEV_ROWS) {
                    val elDeg = -ELEV_SPAN + (row + 0.5f) / ELEV_ROWS * (2f * ELEV_SPAN)
                    val el = Math.toRadians(elDeg.toDouble())
                    val ce = cos(el)
                    val se = sin(el)
                    // 世界水平面分量用 (x, y)，竖直轴是 Z（见 ScanSession 注释）
                    val x = (ce * azCos).toFloat()
                    val y = (ce * azSin).toFloat()
                    val z = se.toFloat()
                    if (p3 + 3 > dotDirections.size) break
                    dotDirections[p3] = x
                    dotDirections[p3 + 1] = y
                    dotDirections[p3 + 2] = z
                    p3 += 3
                }
            }
        }
    }

    private fun latCircleSteps(latDeg: Float, base: Int): Int {
        // 越靠近极点，圆周长越小，段数相应减少，避免浪费顶点
        val scale = cos(Math.toRadians(latDeg.toDouble())).toFloat().coerceAtLeast(0.15f)
        return (base * scale).toInt().coerceAtLeast(24)
    }

    private fun appendCircle(out: ArrayList<Float>, latDeg: Float, steps: Int) {
        val latRad = Math.toRadians(latDeg.toDouble())
        val y = sin(latRad).toFloat()
        val r = cos(latRad).toFloat()
        val prev = FloatArray(3)
        val cur = FloatArray(3)
        for (i in 0..steps) {
            val t = 2.0 * PI * i / steps
            // (r*cos t, y, r*sin t) 本身就是单位向量（r = cos lat, y = sin lat）
            cur[0] = (r * cos(t)).toFloat(); cur[1] = y; cur[2] = (r * sin(t)).toFloat()
            if (i > 0) {
                push(out, prev); push(out, cur)
            }
            prev[0] = cur[0]; prev[1] = cur[1]; prev[2] = cur[2]
        }
    }

    private fun appendMeridian(out: ArrayList<Float>, lonDeg: Float, steps: Int) {
        val lonRad = Math.toRadians(lonDeg.toDouble())
        val prev = FloatArray(3)
        val cur = FloatArray(3)
        for (i in 0..steps) {
            val phi = PI * i / steps
            val sp = sin(phi)
            cur[0] = (sp * cos(lonRad)).toFloat()
            cur[1] = cos(phi).toFloat()
            cur[2] = (sp * sin(lonRad)).toFloat()
            if (i > 0) {
                push(out, prev); push(out, cur)
            }
            prev[0] = cur[0]; prev[1] = cur[1]; prev[2] = cur[2]
        }
    }

    private fun push(out: ArrayList<Float>, v: FloatArray) {
        // 位置即法线
        out.add(v[0]); out.add(v[1]); out.add(v[2])
        out.add(v[0]); out.add(v[1]); out.add(v[2])
    }
}
