#!/usr/bin/env python3
"""采集 gal_sample-game.monitor 3 秒并统计噪声特征"""
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

import subprocess, wave, struct, math, sys

out = "/tmp/rec.wav"
subprocess.run(["parecord", "-d", "gal_sample-game.monitor", "--file-format=wav",
                "--rate=48000", "--channels=2", "--format=s16le", out],
               timeout=5, capture_output=True)
try:
    w = wave.open(out)
except Exception as e:
    print("open err:", e); sys.exit(1)
n = w.getnframes()
d = w.readframes(n)
s = struct.unpack("<%dh" % (len(d) // 2), d)
if not s:
    print("empty"); sys.exit(1)
peak = max(abs(x) for x in s)
rms = math.sqrt(sum(x * x for x in s) / len(s))
# 静音段（前 0.3s）噪声水平
head = s[:len(s)//10]
h_rms = math.sqrt(sum(x*x for x in head)/len(head)) if head else 0
# 频谱：用简单 FFT
import ctypes
print(f"frames={n} peak={peak} rms={rms:.1f} head_rms={h_rms:.1f}")
# 统计静音率（|x| < 16 视为静音）
silent = sum(1 for x in s if abs(x) < 16) / len(s)
print(f"silent_ratio={silent:.2%}")
# 采样间隔内的跳变（爆音指标）
jumps = sum(1 for i in range(1, len(s)) if abs(s[i]-s[i-1]) > 2000)
print(f"big_jumps={jumps} ({jumps/len(s):.3%})")
