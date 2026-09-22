#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
通过模拟器控制台注入「真实传感器数据」，驱动引导球做 360° 环绕，
以此录制出「球跟着设备旋转 + 光点被逐个点亮」的功能演示。

原理
----
Android 的 SensorManager.getRotationMatrix(accel, mag) 会从
重力向量与地磁向量反解出「设备->世界」旋转矩阵。我们反过来：
先设计好希望的相机环绕姿态（方位角 / 俯仰角），再解析地算出
对应的加速度计与磁力计读数，注入模拟器。这样应用走的是**完全真实的
传感器代码路径**，而不是被我们喂了一个假的姿态。

用法:
    python drive.py sweep [rate_hz] [step_deg] [offset] [passes] [elev1,elev2,...]
    python drive.py aim   "az:el;az:el;..." [dwell_sec]   # 精确瞄准补扫
    python drive.py pose  AZ EL                           # 只设置某个姿态
    python drive.py record start|stop PATH                # 开始 / 停止录制
"""
import math
import os
import socket
import subprocess
import sys
import time

HOST = "127.0.0.1"
PORT = 5554
TOKEN_PATH = os.path.join(os.environ.get("USERPROFILE", ""), ".emulator_console_auth_token")

G = 9.81
# 典型的中纬度地磁场：水平分量 28uT 指北，垂直分量 42uT 向下
B_H = 28.0
B_V = 42.0


# --------------------------------------------------------------------------- 姿态数学

def orbit_world_to_device(az_deg, el_deg):
    """相机位于球坐标 (az, el) 处并始终朝向原点时，世界->设备的旋转矩阵（行主序）。"""
    az = math.radians(az_deg)
    el = math.radians(el_deg)
    ce, se = math.cos(el), math.sin(el)
    ca, sa = math.cos(az), math.sin(az)

    px, py, pz = ce * ca, ce * sa, se       # 相机位置方向（物体在原点）
    fx, fy, fz = -px, -py, -pz              # 相机光轴

    if abs(fz) > 0.999:
        ux, uy, uz = 0.0, 1.0, 0.0
    else:
        ux, uy, uz = 0.0, 0.0, 1.0

    rx, ry, rz = fy * uz - fz * uy, fz * ux - fx * uz, fx * uy - fy * ux
    rl = math.sqrt(rx * rx + ry * ry + rz * rz)
    rx, ry, rz = rx / rl, ry / rl, rz / rl

    ux2, uy2, uz2 = ry * fz - rz * fy, rz * fx - rx * fz, rx * fy - ry * fx

    # 世界->设备的行向量 = 相机的 right / up / (相机 +Z)
    return [
        [rx, ry, rz],
        [ux2, uy2, uz2],
        [-fx, -fy, -fz],
    ]


def sensors_for(az_deg, el_deg):
    """由目标姿态解析出模拟器需要的加速度计与磁力计读数。"""
    w = orbit_world_to_device(az_deg, el_deg)
    # 加速度计测得的是「世界系上方向」在设备系下的表示 * g
    accel = [w[i][2] * G for i in range(3)]
    # 地磁：北向分量 + 向下的垂直分量
    mag = [w[i][1] * B_H + w[i][2] * (-B_V) for i in range(3)]
    return accel, mag


# --------------------------------------------------------------------------- 控制台

class Console:
    def __init__(self):
        self.sock = socket.create_connection((HOST, PORT), timeout=15)
        time.sleep(0.3)
        self.sock.recv(8192)
        token = ""
        if os.path.exists(TOKEN_PATH):
            with open(TOKEN_PATH, "r", encoding="utf-8") as f:
                token = f.read().strip()
        if token:
            self.send_raw("auth " + token, wait=0.3)
        self.send_raw("sensor get acceleration", wait=0.2)

    def send_raw(self, cmd, wait=0.05):
        self.sock.sendall((cmd + "\r\n").encode())
        time.sleep(wait)
        try:
            self.sock.settimeout(1.0)
            return self.sock.recv(8192).decode(errors="ignore")
        except Exception:
            return ""

    def set_pose(self, az, el):
        accel, mag = sensors_for(az, el)
        self.send_raw("sensor set acceleration %.4f:%.4f:%.4f" % tuple(accel))
        self.send_raw("sensor set magnetic-field %.4f:%.4f:%.4f" % tuple(mag))

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass


# --------------------------------------------------------------------------- adb

def adb(*args, **kwargs):
    sdk = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk")
    exe = os.path.join(sdk, "platform-tools", "adb.exe")
    return subprocess.run([exe] + list(args), capture_output=True, text=True, **kwargs)


def tap(x, y):
    adb("shell", "input", "tap", str(int(x)), str(int(y)))


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return

    mode = sys.argv[1]

    if mode == "pose":
        az, el = float(sys.argv[2]), float(sys.argv[3])
        c = Console()
        c.set_pose(az, el)
        print("pose set: az=%.1f el=%.1f" % (az, el))
        c.close()
        return

    if mode == "sweep":
        # 依次扫过多个俯仰带，每个俯仰带完整绕一圈。
        # 姿态注入后传感器融合需要一点时间收敛，所以速率不宜过快。
        #
        # 默认使用 7 条俯仰带：相比只扫 5 条，最"脆弱"的光点离取景框边缘
        # 的角距从 2.4° 提升到 7.6°（见 plan.py 的 minU 指标），
        # 真机上才不会因为几度的姿态误差而漏拍。
        default_elev = [80.0, 58.0, 34.0, 6.0, -22.0, -48.0, -76.0]
        rate_hz = float(sys.argv[2]) if len(sys.argv) > 2 else 8.0
        step_deg = float(sys.argv[3]) if len(sys.argv) > 3 else 4.0
        offset_deg = float(sys.argv[4]) if len(sys.argv) > 4 else 0.0
        passes = int(sys.argv[5]) if len(sys.argv) > 5 else 1
        if len(sys.argv) > 6:
            elevations = [float(v) for v in sys.argv[6].split(",")]
        else:
            elevations = default_elev
        c = Console()
        interval = 1.0 / rate_hz
        total = 0
        for p in range(passes):
            shift = offset_deg * p
            for el in elevations:
                az = shift
                while az < 360.0 + shift:
                    t0 = time.time()
                    c.set_pose(az, el)
                    az += step_deg
                    total += 1
                    dt = time.time() - t0
                    if dt < interval:
                        time.sleep(interval - dt)
                print("pass %d elevation %.0f done" % (p + 1, el), flush=True)
        c.close()
        print("sweep finished (%d poses)" % total, flush=True)
        return

    if mode == "aim":
        # 精确瞄准若干光点方向，让它们落在画面正中（补扫死角用）。
        # 参数：逗号分隔的 "az:el" 列表
        pairs = []
        for item in sys.argv[2].split(";"):
            if not item.strip():
                continue
            a, e = item.split(":")
            pairs.append((float(a), float(e)))
        dwell = float(sys.argv[3]) if len(sys.argv) > 3 else 0.30
        c = Console()
        for idx, (az, el) in enumerate(pairs):
            c.set_pose(az, el)
            time.sleep(dwell)
            print("aim %d/%d az=%.1f el=%.1f" % (idx + 1, len(pairs), az, el), flush=True)
        c.close()
        print("aim finished", flush=True)
        return

    if mode == "record":
        c = Console()
        action = sys.argv[2]
        if action == "start":
            path = sys.argv[3]
            print(c.send_raw("screenrecord start --size 720x1600 --bit-rate 8M %s" % path, wait=1.0))
        else:
            print(c.send_raw("screenrecord stop", wait=1.0))
        c.close()
        return

    print("unknown mode:", mode)


if __name__ == "__main__":
    main()
