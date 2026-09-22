#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一键录制引导球功能演示。

流程：
  1. 安装并启动 APK（自动授予相机权限）；
  2. 用模拟器控制台注入「真实传感器数据」，驱动设备绕物体做 360° 环绕；
  3. 环绕结束后从 logcat 读取**实际覆盖率**，若仍有漏拍角度，
     则按 App 的 168 点网格逐个精确瞄准补扫（闭环，直到 168/168）；
  4. 全过程用设备端 screenrecord 录屏，最后拉回宿主机。

前置：模拟器已启动（emulator-5554）、APK 已构建。
用法：python record_demo.py <apk路径> <输出mp4路径>
"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import plan as P                      # noqa: E402  离线覆盖模型
from drive import Console             # noqa: E402

PKG = "com.remy.guidesphere"
SDK = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk")
ADB = os.path.join(SDK, "platform-tools", "adb.exe")

# 演示用的环绕参数：捕获粒度已是「面」（一次拍摄 = 一个面），覆盖只取决于水平方位角，
# 所以只要绕物体水平转一圈（约 16~20 s）就能拍满 8 个面 = 168 点。俯仰固定近平视。
ELEVATIONS = (8.0,)
STEP_DEG = 3.0
RATE_HZ = 5.0

LIT_RE = re.compile(r"lit=(\d+)/(\d+)")

# 与 App 内 SphereGeometry 的网格排布保持同步：8 扇区 × 3 方位列 × 7 仰角行 = 168
SPHERE_DOTS = 8 * 3 * 7
GRID_SECTORS, GRID_COLS, GRID_ROWS, GRID_SPAN = 8, 3, 7, 80.0


def adb(*args, timeout=240):
    return subprocess.run([ADB] + list(args), capture_output=True, text=True, timeout=timeout)


def sh(cmd, timeout=240):
    return adb("shell", cmd, timeout=timeout)


def live_lit():
    """从 logcat 读取 App 上报的已点亮光点数（失败返回 -1）。"""
    r = adb("logcat", "-d", "-s", "GuideSphereStats:I", timeout=60)
    last = None
    for line in (r.stdout or "").splitlines():
        m = LIT_RE.search(line)
        if m:
            last = m
    return int(last.group(1)) if last else -1


def aim_order():
    """按 App 实际的 168 点网格（8 扇区 × 3 列 × 7 行）给出补扫目标。

    旧版用 plan.py 的 140 点 Fibonacci 模型，与 App 改为「整面点阵」后的
    网格排布不一致，会导致补扫打不到真实光点。这里直接按 App 的生成式
    网格公式造姿态（与 SphereGeometry.kt 同一套参数），按扇区分组顺序输出，
    顺应 App「目标扇区顺时针推进」的节奏。
    """
    out = []
    for s in range(GRID_SECTORS):
        for c in range(GRID_COLS):
            az = (s + (c + 0.5) / GRID_COLS) * (360.0 / GRID_SECTORS)
            for r in range(GRID_ROWS):
                el = -GRID_SPAN + (r + 0.5) / GRID_ROWS * (2 * GRID_SPAN)
                out.append((az, el))
    return out


def main():
    apk = sys.argv[1]
    out = sys.argv[2]
    remote = "/sdcard/guidesphere_demo.mp4"

    print("[1/6] 安装 APK ...", flush=True)
    r = adb("install", "-r", "-g", apk)
    print((r.stdout or r.stderr).strip(), flush=True)

    print("[2/6] 授权并启动 ...", flush=True)
    sh("pm grant %s android.permission.CAMERA" % PKG)
    sh("am force-stop %s" % PKG)
    adb("logcat", "-c")
    sh("am start -W -n %s/.MainActivity" % PKG)
    time.sleep(6)

    print("[3/6] 预热并开始录屏 ...", flush=True)
    c = Console()
    c.set_pose(0, 10)
    time.sleep(2.5)

    sh("rm -f %s" % remote)
    rec = subprocess.Popen(
        [ADB, "shell", "screenrecord", "--size", "720x1600", "--bit-rate", "12M",
         "--time-limit", "240", remote],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    time.sleep(3.0)  # 开场：静止展示引导球与相机画面

    print("[4/6] 绕物体水平环绕一圈（%d 带 × %.0f°）..." % (len(ELEVATIONS), STEP_DEG), flush=True)
    interval = 1.0 / RATE_HZ
    for el in ELEVATIONS:
        az = 0.0
        while az < 360.0:
            t0 = time.time()
            c.set_pose(az, el)
            az += STEP_DEG
            dt = time.time() - t0
            if dt < interval:
                time.sleep(interval - dt)
        print("   elevation %.0f done, lit=%d" % (el, live_lit()), flush=True)

    print("[5/6] 闭环补扫死角 ...", flush=True)
    targets = aim_order()
    idx = 0
    for az, el in targets:
        lit = live_lit()
        if lit >= SPHERE_DOTS:
            print("   已达 %d/%d，无需继续补扫" % (lit, SPHERE_DOTS), flush=True)
            break
        c.set_pose(az, el)
        idx += 1
        time.sleep(0.30)
    print("   补扫 %d 个姿态，最终 lit=%d" % (idx, live_lit()), flush=True)

    # 收尾：回到正面视角，让用户看清最终状态与完成提示
    c.set_pose(0, 12)
    time.sleep(5)
    c.close()

    print("[6/6] 停止录屏并拉取 ...", flush=True)
    sh("kill -2 $(pidof screenrecord)")
    time.sleep(3.0)
    r = adb("pull", remote, out)
    print((r.stdout or r.stderr).strip(), flush=True)
    print("DONE ->", out, flush=True)


if __name__ == "__main__":
    main()
