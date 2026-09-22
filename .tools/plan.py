#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
扫描计划评估器。

在 analyze.py 的基础上多算一个关键指标：**鲁棒裕量**
  u = min(sx, sy) / (limY * -cz)

  sx = limX*(-cz) - |cx|   （>0 表示在取景框内）
  sy = limY*(-cz) - |cy|

u 表示"某个光点在其最佳姿态下离取景框边缘还有多远"，
u=1 代表正好落在画面正中，u 越小越脆弱（真机上姿态稍有偏差就会漏拍）。

对每个候选计划输出：
  - 覆盖率
  - 最脆弱的那个光点的 u（越低越危险）
  - 有多少光点的 u 低于阈值（真机上大概率漏拍）
"""
import math
import sys

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


def sweep_poses(elevations, step=4.0, offset=0.0):
    poses = []
    for el in elevations:
        az = offset
        while az < 360.0 + offset:
            poses.append((az, el))
            az += step
    return poses


def evaluate(poses, dirs, limx, limy):
    """返回 (命中数, 每个光点的最佳裕量 u 列表)"""
    n = len(dirs)
    best_u = [0.0] * n
    for (az, el) in poses:
        w = world_to_device(az, el)
        for i in range(n):
            nx, ny, nz = dirs[i]
            cx = w[0][0] * nx + w[0][1] * ny + w[0][2] * nz
            cy = w[1][0] * nx + w[1][1] * ny + w[1][2] * nz
            cz = w[2][0] * nx + w[2][1] * ny + w[2][2] * nz
            if cz <= 0.02:
                continue
            sx = limx * cz - abs(cx)
            sy = limy * cz - abs(cy)
            if sx < 0.0 or sy < 0.0:
                continue
            u = min(sx, sy) / (limy * cz)
            if u > best_u[i]:
                best_u[i] = u
    hits = sum(1 for u in best_u if u > 0.0)
    return hits, best_u


def report(name, elevations, step, dirs, limx, limy, frag_threshold=0.10):
    poses = sweep_poses(elevations, step)
    hits, u = evaluate(poses, dirs, limx, limy)
    weak = [i for i, v in enumerate(u) if 0.0 < v < frag_threshold]
    miss = [i for i, v in enumerate(u) if v <= 0.0]
    worst = min(u) if u else 0.0
    print("%-42s poses=%4d  cov=%3d/%d (%.1f%%)  minU=%.3f  weak=%d  miss=%d"
          % (name, len(poses), hits, DOT_COUNT, 100.0 * hits / DOT_COUNT, worst, len(weak), len(miss)))
    for i in miss:
        d = dirs[i]
        print("      MISS #%3d elev=%6.1f az=%7.1f" % (
            i, math.degrees(math.asin(max(-1, min(1, d[1])))), math.degrees(math.atan2(d[2], d[0]))))
    return hits, u


def main():
    dirs = fibonacci_dirs(DOT_COUNT)
    limx, limy = limits()

    plans = [
        ("A 原计划 5带 4°", (70.0, 35.0, -5.0, -42.0, -75.0), 4.0),
        ("B 5带 3°", (70.0, 35.0, -5.0, -42.0, -75.0), 3.0),
        ("C 7带 4°", (80.0, 58.0, 34.0, 6.0, -22.0, -48.0, -76.0), 4.0),
        ("D 7带 3°", (80.0, 58.0, 34.0, 6.0, -22.0, -48.0, -76.0), 3.0),
        ("E 9带 3°", (84.0, 68.0, 50.0, 30.0, 8.0, -14.0, -36.0, -58.0, -80.0), 3.0),
        ("F 9带 2°", (84.0, 68.0, 50.0, 30.0, 8.0, -14.0, -36.0, -58.0, -80.0), 2.0),
        ("G 11带 3°", (86.0, 72.0, 58.0, 44.0, 30.0, 8.0, -16.0, -30.0, -44.0, -58.0, -82.0), 3.0),
    ]
    results = {}
    for name, elev, step in plans:
        results[name] = report(name, elev, step, dirs, limx, limy)
    return results


if __name__ == "__main__":
    main()
