#!/usr/bin/env python3
"""枫兮听书 —— Chaquopy license 检查屏蔽补丁

背景
----
Chaquopy 12.0.0 的 libchaquopy_java.so 在模块初始化时会调用
java.chaquopy.check_license() 校验 ticket.txt。该函数要求 ticket 形如
    <base64(json)>.<base64(rsa签名)>
而本项目使用自建运行时资产，ticket.txt 只有一个裸时间戳，
于是 `signed_ticket, signature_b64 = ticket.split(".")` 抛
    ValueError: need more than 1 value to unpack
（CPython 3.8 的 UNPACK_SEQUENCE 错误模板，无 Python 栈，
 因为发生在 Cython 模块 init 阶段）。

补丁
----
在 libchaquopy_java.so 中，把调用 check_license 的那条 `blr x8`
替换为「返回 ticket 对象本身（已 incref）」，从而完全跳过 license 校验。

原始序列（0x5c18 起）:
    0x5c18  adrp x8, #0x25000
    0x5c1c  ldr  x8, [x8, #0x500]     ; x8 = check_license 指针
    0x5c20  mov  x0, x26              ; x0 = ticket
    0x5c24  str  xzr, [sp, #0x28]
    0x5c28  blr  x8                   ; ← 崩溃点
    0x5c2c  str  x0, [sp, #0x28]
    0x5c30  cbz  x0, <error>

补丁后（0x5c1c 起 4 条）:
    0x5c1c  ldr  x9, [x26]            ; ob_refcnt
    0x5c20  add  x9, x9, #1           ; +1（抵消调用方随后的 decref）
    0x5c24  str  x9, [x26]
    0x5c28  mov  x0, x26              ; 返回 ticket（非 NULL → 不走错误分支）
"""
import sys

OFF = 0x5C1C
ORIG = bytes.fromhex("088142f94 1"[:0] + "088142f9" + "e0031aaa" + "ff1700f9" + "00013fd6")
PATCH = bytes.fromhex("490340f9" + "29050091" + "490300f9" + "e0031aaa")

def main(path):
    data = bytearray(open(path, "rb").read())
    cur = bytes(data[OFF:OFF+len(PATCH)])
    if cur == PATCH:
        print("    + license 补丁已存在")
        return 0
    if cur != ORIG:
        print("    !! 目标字节不匹配，拒绝打补丁", file=sys.stderr)
        print("       expect:", ORIG.hex(), file=sys.stderr)
        print("       actual:", cur.hex(), file=sys.stderr)
        return 1
    data[OFF:OFF+len(PATCH)] = PATCH
    open(path, "wb").write(bytes(data))
    print("    + license 检查已屏蔽（check_license 调用 -> 直接返回 ticket）")
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
