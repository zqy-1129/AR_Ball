"""纯 Python 解码 PNG 并定位 HUD 上的暗色胶囊控件（无第三方依赖）。

用途：用 adb screencap 的截图反推自绘 HUD 上按钮的真实像素矩形，
避免按密度换算猜坐标（本项目在模拟器上就因此点错过按钮）。

用法：
    python probe_png.py <png路径> [x0 x1 y1]
默认在 x∈[430,719]、y∈[0,520] 范围内统计「暗像素」，输出连续的暗带（即胶囊）。
"""
import sys
import struct
import zlib


def decode_png(path):
    data = open(path, 'rb').read()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', 'not a png'
    pos = 8
    idat = b''
    w = h = bitdepth = colortype = None
    while pos < len(data):
        (ln,) = struct.unpack('>I', data[pos:pos + 4])
        typ = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if typ == b'IHDR':
            w, h, bitdepth, colortype, comp, filt, inter = struct.unpack('>IIBBBBB', body)
            assert bitdepth == 8 and inter == 0, 'only 8-bit non-interlaced supported'
        elif typ == b'IDAT':
            idat += body
        elif typ == b'IEND':
            break
    raw = zlib.decompress(idat)
    nch = {0: 1, 2: 3, 4: 2, 6: 4}[colortype]
    stride = w * nch
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if f == 1:
            for i in range(nch, stride):
                line[i] = (line[i] + line[i - nch]) & 0xFF
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                a = line[i - nch] if i >= nch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = line[i - nch] if i >= nch else 0
                b = prev[i]
                c = prev[i - nch] if i >= nch else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, nch, out


def main():
    path = sys.argv[1]
    x0, x1, y1 = 430, 719, 520
    if len(sys.argv) >= 5:
        x0, x1, y1 = int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
    w, h, nch, px = decode_png(path)
    print('尺寸 %dx%d 通道=%d' % (w, h, nch))
    if x1 > w - 1:
        x1 = w - 1
    if y1 > h:
        y1 = h
    stride = w * nch
    rows = []
    for y in range(y1):
        cnt = 0
        for x in range(x0, x1 + 1):
            i = y * stride + x * nch
            b = (px[i] + px[i + 1] + px[i + 2]) // 3
            if b < 110:
                cnt += 1
        rows.append(cnt)
    # 找连续暗带
    band = None
    for y, c in enumerate(rows):
        dark = c > (x1 - x0) * 0.45
        if dark and band is None:
            band = y
        elif not dark and band is not None:
            hmid = (band + y - 1) / 2
            print('暗带 y=%d..%d  高度=%d  中心y=%.0f' % (band, y - 1, y - band, hmid))
            band = None
    if band is not None:
        print('暗带 y=%d..%d 高度=%d 中心y=%.0f' % (band, y1 - 1, y1 - band, (band + y1 - 1) / 2))


if __name__ == '__main__':
    main()
