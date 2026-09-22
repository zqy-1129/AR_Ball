#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
离线复现 App 里的覆盖判定算法，用来诊断"哪些角度永远扫不到"，
并据此生成补扫姿态。

覆盖判定（与 GuideSphereRenderer / ScanSession 完全一致）：
  相机位于球坐标 (az, el)，光轴指向原点；把光点方向转到设备坐标系后
  判断是否落在视锥的安全矩形内。
"""
import math

DOT_COUNT = 140
FOV_Y_DEG = 46.0
MARGIN = 0.95
ASPECT = 720.0 / 1600.0


def fibonacci_dirs(n):
    ga = math.pi * (3.0 - math.sqrt(5.0))
    out = []
    for i in range(n):
        y = 1.0 - (i + 0.5) * 2.0 / n
        r = math.sqrt(max(0.0, 1.0 - y * y))
        t = ga * i
        out.append((r * math.cos(t), y, r * math.sin(t)))
    return out


def world_to_device(az_deg, el_deg):
    az = math.radians(az_deg)
    el = math.radians(el_deg)
    ce, se = math.cos(el), math.sin(el)
    ca, sa = math.cos(az), math.sin(az)
    px, py, pz = ce * ca, ce * sa, se
    fx, fy, fz = -px, -py, -pz
    ux, uy, uz = (0.0, 1.0, 0.0) if abs(fz) > 0.999 else (0.0, 0.0, 1.0)
    rx, ry, rz = fy * uz - fz * uy, fz * ux - fx * uz, fx * uy - fy * ux
    rl = math.sqrt(rx * rx + ry * ry + rz * rz)
    rx, ry, rz = rx / rl, ry / rl, rz / rl
    ux2, uy2, uz2 = ry * fz - rz * fy, rz * fx - rx * fz, rx * fy - ry * fx
    return [[rx, ry, rz], [ux2, uy2, uz2], [-fx, -fy, -fz]]


def limits():
    th = math.tan(math.radians(FOV_Y_DEG / 2.0))
    return th * ASPECT * MARGIN, th * MARGIN


def covered_flags(dirs, az, el, limx, limy):
    w = world_to_device(az, el)
    res = []
    for (nx, ny, nz) in dirs:
        cx = w[0][0] * nx + w[0][1] * ny + w[0][2] * nz
        cy = w[1][0] * nx + w[1][1] * ny + w[1][2] * nz
        cz = w[2][0] * nx + w[2][1] * ny + w[2][2] * nz
        ok = cz > 0.02 and abs(cx) <= limx * cz and abs(cy) <= limy * cz
        res.append(ok)
    return res


def simulate(poses, dirs, limx, limy):
    lit = [False] * len(dirs)
    for (az, el) in poses:
        for i, ok in enumerate(covered_flags(dirs, az, el, limx, limy)):
            if ok:
                lit[i] = True
    return lit


def sweep_poses(elevations=(70.0, 35.0, -5.0, -42.0, -75.0), step=4.0, offset=0.0, passes=1):
    poses = []
    for p in range(passes):
        shift = offset * p
        for el in elevations:
            az = shift
            while az < 360.0 + shift:
                poses.append((az, el))
                az += step
    return poses


def aim_pose(dirv):
    """让某个光点正好落在画面正中央（正对镜头）所需的相机姿态"""
    nx, ny, nz = dirv
    az = math.degrees(math.atan2(ny, nx))
    el = math.degrees(math.asin(max(-1.0, min(1.0, nz))))
    return az % 360.0, el


def main():
    dirs = fibonacci_dirs(DOT_COUNT)
    limx, limy = limits()
    poses = sweep_poses()
    lit = simulate(poses, dirs, limx, limy)
    n_lit = sum(lit)
    print("单遍 %d 个姿态 -> 覆盖 %d/%d (%.1f%%)" % (len(poses), n_lit, DOT_COUNT, 100.0 * n_lit / DOT_COUNT))

    missing = [i for i in range(DOT_COUNT) if not lit[i]]
    print("未覆盖角度数:", len(missing))
    for i in missing:
        d = dirs[i]
        el = math.degrees(math.asin(d[1]))
        az = math.degrees(math.atan2(d[2], d[0]))
        print("  #%3d  dir=(%.3f, %.3f, %.3f)  elev=%.1f  az=%6.1f  瞄准姿态=(%.1f, %.1f)"
              % (i, d[0], d[1], d[2], el, az, *aim_pose(d)))

    # 补扫：对每个未覆盖点精确瞄准
    fill = [aim_pose(dirs[i]) for i in missing]
    lit2 = simulate(poses + fill, dirs, limx, limy)
    print("补扫后 -> 覆盖 %d/%d" % (sum(lit2), DOT_COUNT))

    # 输出补扫序列，供 drive.py 使用
    print("\nFILL_POSES = [")
    for az, el in fill:
        print("    (%.2f, %.2f)," % (az, el))
    print("]")


if __name__ == "__main__":
    main()
