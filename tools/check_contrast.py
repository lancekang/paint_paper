"""
UI 색 토큰(ui/Ui.kt applyTheme)의 명도 대비를 WCAG 2.2 기준으로 검사합니다.

  - 글자(1.4.3): 배경 대비 4.5:1 이상
  - 아이콘·컨트롤 경계·선택 상태 같은 비텍스트(1.4.11): 맞닿은 색 대비 3:1 이상

사용: python tools/check_contrast.py   (실패가 있으면 종료 코드 1)
"""
import re
import sys
from pathlib import Path

UI = Path(__file__).resolve().parent.parent / "app/src/main/java/kr/dfluid/paint/ui/Ui.kt"


def lum(argb: int) -> float:
    def ch(c):
        c /= 255.0
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (argb >> 16) & 255, (argb >> 8) & 255, argb & 255
    return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)


def ratio(a: int, b: int) -> float:
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def themes():
    src = UI.read_text(encoding="utf-8")
    body = src[src.index("fun applyTheme"):]
    dark_part = body[body.index("if (dark) {"):body.index("} else {")]
    light_part = body[body.index("} else {"):body.index("\n        }\n")]
    out = {}
    for name, part in (("다크", dark_part), ("라이트", light_part)):
        out[name] = {k: int(v, 16) & 0xFFFFFF for k, v in re.findall(r"([A-Z_]+) = 0x([0-9A-Fa-f]{8})", part)}
    return out


# (앞색, 바탕, 최소 대비, 설명)
TEXT = 4.5
NON_TEXT = 3.0
PAIRS = [
    ("TEXT", "CARD", TEXT, "본문 글자 / 카드"),
    ("TEXT", "PANEL", TEXT, "본문 글자 / 떠 있는 막대"),
    ("TEXT", "CARD_HEAD", TEXT, "본문 글자 / 카드 머리글·목록 행"),
    ("TEXT", "BUTTON", TEXT, "버튼 글자 / 버튼"),
    ("TEXT", "ROW_ON", TEXT, "글자 / 선택한 행"),
    ("SUBTEXT", "CARD", TEXT, "보조 글자 / 카드"),
    ("SUBTEXT", "PANEL", TEXT, "보조 글자 / 막대"),
    ("MUTED", "CARD", TEXT, "흐린 글자(이름표·안내) / 카드"),
    ("MUTED", "PANEL", TEXT, "흐린 글자 / 막대"),
    ("ON_ACCENT", "BUTTON_ON", TEXT, "강조 버튼 글자·아이콘 / 강조색"),
    ("BUTTON_ON", "CARD", NON_TEXT, "켜짐·선택 표시 / 카드"),
    ("BUTTON_ON", "PANEL", NON_TEXT, "켜짐·선택 표시 / 막대"),
    ("BUTTON_ON", "BUTTON", NON_TEXT, "켜짐 / 꺼짐 버튼"),
    ("CONTROL", "CARD", NON_TEXT, "버튼·입력칸 테두리 / 카드"),
    ("CONTROL", "PANEL", NON_TEXT, "버튼·입력칸 테두리 / 막대"),
    ("CONTROL", "BUTTON", NON_TEXT, "버튼 테두리 / 버튼 바탕"),
    ("TEXT", "BG", NON_TEXT, "아이콘(본문색) / 앱 바탕"),
]


def main() -> int:
    bad = 0
    for theme, t in themes().items():
        print(f"== {theme} ==")
        for fg, bg, need, desc in PAIRS:
            if fg not in t or bg not in t:
                print(f"  ??   {fg} / {bg}: 토큰 없음")
                bad += 1
                continue
            r = ratio(t[fg], t[bg])
            ok = r >= need
            bad += 0 if ok else 1
            print(f"  {'OK ' if ok else 'BAD'}  {r:5.2f} (≥{need})  {fg} / {bg}  {desc}")
    print("모두 통과" if bad == 0 else f"{bad}개 미달")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
