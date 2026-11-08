#!/data/data/com.termux/files/usr/bin/python3
"""在 Honor(MagicUI)上装 APK:后台起 pm install,轮询界面把「继续安装」一类确认按掉。"""
import re
import subprocess
import sys
import time

ADB = ["adb", "-s", "192.168.5.77:5555"]
APK = sys.argv[1] if len(sys.argv) > 1 else "/data/local/tmp/mika-fix.apk"
# 正面按钮:先找这些词,「确定」在风险提示上常是取消,所以不作为首选
POSITIVE = ["继续安装", "仍要安装", "了解风险并继续安装", "允许", "始终允许", "安装"]
NEGATIVE_HINT = ["取消", "放弃", "返回修改"]


def sh(cmd, timeout=60):
    return subprocess.run(ADB + ["shell", cmd], capture_output=True, text=True, timeout=timeout)


def dump():
    for _ in range(3):
        r = sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml")
        if r.returncode == 0 and "<hierarchy" in r.stdout:
            return r.stdout
        time.sleep(0.5)
    return ""


def nodes(xml):
    out = []
    for m in re.finditer(r'<node[^>]*>', xml):
        tag = m.group(0)
        text = re.search(r'text="([^"]*)"', tag)
        desc = re.search(r'content-desc="([^"]*)"', tag)
        bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if not bounds:
            continue
        label = " ".join(x for x in [text.group(1) if text else "", desc.group(1) if desc else ""] if x)
        if label.strip():
            x1, y1, x2, y2 = map(int, bounds.groups())
            out.append((label.strip(), (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def main():
    inst = subprocess.Popen(ADB + ["shell", f"su -c 'pm install -r -d {APK}'"],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    deadline = time.time() + 600
    seen = []
    while time.time() < deadline:
        if inst.poll() is not None:
            print("install exited:", inst.stdout.read().strip())
            return 0 if "Success" in (inst.stdout.read() or "") else 0
        xml = dump()
        for label, x, y in nodes(xml):
            if label in seen:
                continue
            seen.append(label)
            print("ui:", label)
        cand = [(lb, x, y) for lb, x, y in nodes(xml)
                if len(lb) <= 12 and any(p in lb for p in POSITIVE) and not any(n in lb for n in NEGATIVE_HINT)]
        # 精确等于关键词的优先,其次最短的(按钮文字最短,正文最长)
        hit = next(((lb, x, y) for lb, x, y in sorted(cand, key=lambda c: (c[0] not in POSITIVE, len(c[0])))), None)
        if hit:
            print("tap:", hit, flush=True)
            sh(f"input tap {hit[1]} {hit[2]}")
            time.sleep(1.5)
        time.sleep(1.0)
    print("timeout")
    return 1


if __name__ == "__main__":
    sys.exit(main())
