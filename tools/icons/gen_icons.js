// DFPaint 아이콘 생성기: 24×24 선(stroke) 아이콘 정의 → res/drawable/ic_*.xml + 미리보기 HTML.
// 사용: node tools/icons/gen_icons.js   (프로젝트 루트에서)
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..', '..');
const outDir = path.join(root, 'app', 'src', 'main', 'res', 'drawable');
const previewFile = path.join(__dirname, 'preview.html');

const COLOR = '#E9E9EB';
const SW = 1.8;

// ---- 도형 도우미 ----
const f = (n) => +n.toFixed(2);
const circle = (cx, cy, r) => `M${f(cx - r)},${f(cy)}a${r},${r} 0 1,0 ${f(2 * r)},0a${r},${r} 0 1,0 ${f(-2 * r)},0`;
const rect = (x, y, w, h, r = 0) => r === 0
  ? `M${x},${y}h${w}v${h}h${-w}Z`
  : `M${x + r},${y}h${w - 2 * r}a${r},${r} 0 0,1 ${r},${r}v${h - 2 * r}a${r},${r} 0 0,1 ${-r},${r}h${-(w - 2 * r)}a${r},${r} 0 0,1 ${-r},${-r}v${-(h - 2 * r)}a${r},${r} 0 0,1 ${r},${-r}Z`;
// 점선 사각형 (VectorDrawable은 dash를 지원하지 않으므로 조각으로 그림)
const dashedRect = 'M4,7V4h3M10,4h4M17,4h3v3M20,10v4M20,17v3h-3M14,20h-4M7,20H4v-3M4,14v-4';

function gear() {
  const teeth = 8, ro = 9.5, ri = 7.2, cx = 12, cy = 12;
  let d = '';
  for (let i = 0; i < teeth; i++) {
    const a0 = (i / teeth) * Math.PI * 2;
    const step = Math.PI * 2 / teeth;
    const pts = [
      [ri, a0 - step * 0.28], [ro, a0 - step * 0.16], [ro, a0 + step * 0.16], [ri, a0 + step * 0.28],
    ];
    pts.forEach(([r, a], j) => {
      const x = f(cx + r * Math.cos(a)), y = f(cy + r * Math.sin(a));
      d += (i === 0 && j === 0 ? 'M' : 'L') + x + ',' + y;
    });
  }
  return d + 'Z';
}

function radialSym() {
  let d = circle(12, 12, 1.6);
  for (let i = 0; i < 6; i++) {
    const a = (i / 6) * Math.PI * 2 - Math.PI / 2;
    d += `M${f(12 + 4 * Math.cos(a))},${f(12 + 4 * Math.sin(a))}L${f(12 + 9 * Math.cos(a))},${f(12 + 9 * Math.sin(a))}`;
  }
  return d;
}

// 각 아이콘: 요소 배열. 문자열 = 선, {d, fill:true, alpha} = 채움
const icons = {
  // ---- 도구 ----
  tool_pen: ['M12,21L6,11L9,3H15L18,11Z', 'M12,21V13', circle(12, 11, 1.6)],
  tool_pencil: ['M17,3a2.85,2.83 0 1,1 4,4L7.5,20.5L2,22l1.5,-5.5Z', 'M15,5l4,4'],
  tool_airbrush: [
    rect(3, 10, 9, 11, 2), rect(5, 6, 5, 4, 1), 'M7.5,6V4h3',
    { d: circle(16, 7, 0.9), fill: true }, { d: circle(19.5, 4.5, 0.9), fill: true },
    { d: circle(19.5, 9.5, 0.9), fill: true }, { d: circle(16.5, 11.5, 0.9), fill: true },
    { d: circle(21, 13, 0.9), fill: true },
  ],
  tool_marker: ['M9,11l-6,6v3h9l3,-3', 'M22,12l-4.6,4.6a2,2 0 0,1 -2.8,0l-5.2,-5.2a2,2 0 0,1 0,-2.8L14,4'],
  // 서브 뷰: 액자 + 산
  subview: [rect(3, 4, 18, 16, 2), 'M3,17l5,-5l4,4l3,-3l6,6', circle(15.5, 8.5, 1.5)],
  // 애니메이션: 필름
  film: [rect(3, 4, 18, 16, 2), 'M7,4v16M17,4v16M3,9h4M3,15h4M17,9h4M17,15h4'],
  play: [{ d: 'M8,5v14l11,-7Z', fill: true }],
  pause: [{ d: rect(6, 5, 4, 14, 1), fill: true }, { d: rect(14, 5, 4, 14, 1), fill: true }],
  frame_prev: ['M6,5v14', { d: 'M19,5v14l-10,-7Z', fill: true }],
  frame_next: ['M18,5v14', { d: 'M5,5v14l10,-7Z', fill: true }],
  // 어니언 스킨: 겹친 원 세 개
  onion: [circle(8, 12, 5), circle(12, 12, 5), circle(16, 12, 5)],
  // 새 벡터 레이어: 곡선 + 제어점 + 더하기
  vector_add: ['M3,17C7,5 11,5 14,12', rect(1.5, 15.5, 3, 3), rect(12.5, 10.5, 3, 3), 'M19,14v6M16,17h6'],
  // 텍스트: T
  tool_text: ['M4,7V4h16v3', 'M12,4v16', 'M9,20h6'],
  // 도형: 사각형과 원이 겹친 모양
  tool_shape: [rect(3, 3, 11, 11, 1), circle(15, 15, 6)],
  // 색 혼합: 물방울 + 안쪽 소용돌이
  tool_blend: ['M12,2.5C12,2.5 5.5,10 5.5,14.5a6.5,6.5 0 0,0 13,0C18.5,10 12,2.5 12,2.5Z', 'M9,15a3,3 0 0,0 3,3'],
  tool_eraser: ['M7,21l-4.3,-4.3c-1,-1 -1,-2.5 0,-3.4l9.6,-9.6c1,-1 2.5,-1 3.4,0l5.6,5.6c1,1 1,2.5 0,3.4L13,21', 'M22,21H7', 'M5,11l9,9'],
  tool_select: [dashedRect],
  tool_move: ['M12,2v20M2,12h20', 'M9,5l3,-3l3,3', 'M9,19l3,3l3,-3', 'M5,9l-3,3l3,3', 'M19,9l3,3l-3,3'],
  tool_fill: [
    'M19,11l-8,-8l-8.6,8.6a2,2 0 0,0 0,2.8l5.2,5.2c0.8,0.8 2,0.8 2.8,0L19,11Z', 'M5,2l5,5', 'M2,13h15',
    'M22,20a2,2 0 1,1 -4,0c0,-1.6 1.7,-2.4 2,-4c0.3,1.6 2,2.4 2,4Z',
  ],
  tool_gradient: [
    rect(3, 3, 18, 18, 2),
    { d: 'M5,4.8h4v14.4h-4Z', fill: true, alpha: 0.95 },
    { d: 'M9,4.8h5v14.4h-5Z', fill: true, alpha: 0.55 },
    { d: 'M14,4.8h5v14.4h-5Z', fill: true, alpha: 0.2 },
  ],
  tool_eyedropper: ['M2,22l1,-1h3l9,-9', 'M3,21v-3l9,-9', 'M15,6l3.4,-3.4a2.1,2.1 0 1,1 3,3L18,9l0.4,0.4a2.1,2.1 0 1,1 -3,3l-3.8,-3.8a2.1,2.1 0 1,1 3,-3l0.4,0.4Z'],
  tool_hand: [
    'M18,11V6a2,2 0 0,0 -4,0', 'M14,10V4a2,2 0 0,0 -4,0v2', 'M10,10.5V6a2,2 0 0,0 -4,0v8',
    'M18,8a2,2 0 1,1 4,0v6a8,8 0 0,1 -8,8h-2c-2.8,0 -4.5,-0.86 -5.99,-2.34l-3.6,-3.6a2,2 0 0,1 2.83,-2.82L7,15',
  ],

  // ---- 상단 바 ----
  file_new: ['M14,3H7a2,2 0 0,0 -2,2v14a2,2 0 0,0 2,2h10a2,2 0 0,0 2,-2V8Z', 'M14,3v5h5', 'M12,11v6M9,14h6'],
  file_open: ['M6,14l1.5,-2.9A2,2 0 0,1 9.24,10H20a2,2 0 0,1 1.94,2.5l-1.54,6a2,2 0 0,1 -1.95,1.5H4a2,2 0 0,1 -2,-2V5a2,2 0 0,1 2,-2h3.9a2,2 0 0,1 1.69,0.9l0.81,1.2a2,2 0 0,0 1.67,0.9H18a2,2 0 0,1 2,2v2'],
  file_save: [
    'M15.2,3a2,2 0 0,1 1.4,0.6l3.8,3.8a2,2 0 0,1 0.6,1.4V19a2,2 0 0,1 -2,2H5a2,2 0 0,1 -2,-2V5a2,2 0 0,1 2,-2Z',
    'M17,21v-7a1,1 0 0,0 -1,-1H8a1,1 0 0,0 -1,1v7', 'M7,3v4a1,1 0 0,0 1,1h7',
  ],
  file_export: [
    'M12,3H5a2,2 0 0,0 -2,2v14a2,2 0 0,0 2,2h14a2,2 0 0,0 2,-2v-7', 'M21,15l-3.1,-3.1a2,2 0 0,0 -2.8,0L6,21',
    circle(8.5, 8.5, 1.6), 'M16,3h5v5', 'M21,3l-6,6',
  ],
  undo: ['M9,14L4,9l5,-5', 'M4,9h10.5a5.5,5.5 0 0,1 0,11H11'],
  redo: ['M15,14l5,-5l-5,-5', 'M20,9H9.5a5.5,5.5 0 0,0 0,11H13'],
  transform: [rect(2, 2, 4, 4), rect(18, 2, 4, 4), rect(2, 18, 4, 4), rect(18, 18, 4, 4), 'M6,4h12M6,20h12M4,6v12M20,6v12'],
  deselect: [dashedRect, 'M9,9l6,6M15,9l-6,6'],
  // 퀵 마스크: 점선 사각형 안에 채운 원
  quick_mask: [dashedRect, { d: circle(12, 12, 4.5), fill: true }],
  view_fit: ['M8,3H5a2,2 0 0,0 -2,2v3', 'M21,8V5a2,2 0 0,0 -2,-2h-3', 'M3,16v3a2,2 0 0,0 2,2h3', 'M16,21h3a2,2 0 0,0 2,-2v-3', rect(8, 8, 8, 8, 1)],
  view_rotate_reset: ['M3,12a9,9 0 1,0 9,-9a9.75,9.75 0 0,0 -6.74,2.74L3,8', 'M3,3v5h5'],
  view_flip: ['M3,7l5,5l-5,5V7', 'M21,7l-5,5l5,5V7', 'M12,20v2M12,14v2M12,8v2M12,2v2'],
  ruler: [
    'M21.3,15.3a2.4,2.4 0 0,1 0,3.4l-2.6,2.6a2.4,2.4 0 0,1 -3.4,0L2.7,8.7a2.41,2.41 0 0,1 0,-3.4l2.6,-2.6a2.41,2.41 0 0,1 3.4,0Z',
    'M14.5,12.5l2,-2M11.5,9.5l2,-2M8.5,6.5l2,-2M17.5,15.5l2,-2',
  ],
  sym_vertical: ['M12,2v3M12,8v3M12,14v3M12,20v2', 'M9,6L3,18h6Z', 'M15,6l6,12h-6Z'],
  sym_horizontal: ['M2,12h3M8,12h3M14,12h3M20,12h2', 'M6,9L18,3v6Z', 'M6,15l12,6v-6Z'],
  sym_quad: ['M12,2v3M12,8v8M12,19v3', 'M2,12h3M8,12h8M19,12h3', circle(6.5, 6.5, 2.2), circle(17.5, 6.5, 2.2), circle(6.5, 17.5, 2.2), circle(17.5, 17.5, 2.2)],
  sym_radial: [radialSym()],
  keyboard: [rect(2, 6, 20, 12, 2), 'M6,10h0.01M10,10h0.01M14,10h0.01M18,10h0.01M7,14h10'],
  settings: [gear(), circle(12, 12, 3)],
  panel: [rect(3, 3, 18, 18, 2), 'M15,3v18'],

  // ---- 레이어 패널 ----
  layer_add: ['M11,3L2,8l9,5l9,-5Z', 'M2,13l9,5l3.5,-1.95', 'M19,14v6M16,17h6'],
  folder_add: ['M12,10v6M9,13h6', 'M20,20a2,2 0 0,0 2,-2V8a2,2 0 0,0 -2,-2h-7.9a2,2 0 0,1 -1.69,-0.9L9.6,3.9A2,2 0 0,0 7.93,3H4a2,2 0 0,0 -2,2v13a2,2 0 0,0 2,2Z'],
  duplicate: [rect(8, 8, 14, 14, 2), 'M4,16c-1.1,0 -2,-0.9 -2,-2V4c0,-1.1 0.9,-2 2,-2h10c1.1,0 2,0.9 2,2'],
  trash: ['M3,6h18', 'M19,6v14c0,1 -1,2 -2,2H7c-1,0 -2,-1 -2,-2V6', 'M8,6V4c0,-1 1,-2 2,-2h4c1,0 2,1 2,2v2', 'M10,11v6M14,11v6'],
  arrow_up: ['M12,19V5', 'M5,12l7,-7l7,7'],
  arrow_down: ['M12,5v14', 'M19,12l-7,7l-7,-7'],
  merge_down: ['M12,2v9', 'M8.5,7.5L12,11l3.5,-3.5', 'M12,12.5L3,17l9,4.5l9,-4.5Z'],
  layer_clear: [rect(3, 3, 18, 18, 2), 'M9,9l6,6M15,9l-6,6'],
  clip: ['M6,4v8a4,4 0 0,0 4,4h8', 'M14,12l4,4l-4,4'],
  alpha_lock: [
    rect(4, 11, 16, 10, 2), 'M8,11V7a4,4 0 0,1 8,0v4',
    { d: rect(8, 13.5, 2.7, 2.7), fill: true }, { d: rect(13.3, 13.5, 2.7, 2.7), fill: true },
    { d: rect(10.65, 16.2, 2.7, 2.7), fill: true },
  ],
  group: ['M3,7a2,2 0 0,1 2,-2h4l2,2h8a2,2 0 0,1 2,2v9a2,2 0 0,1 -2,2H5a2,2 0 0,1 -2,-2Z', 'M8,13.5h7', 'M12,10.5l3,3l-3,3'],
  eye: ['M2,12C2,12 5.5,5 12,5C18.5,5 22,12 22,12C22,12 18.5,19 12,19C5.5,19 2,12 2,12Z', circle(12, 12, 3)],
  eye_off: [
    { d: 'M2,12C2,12 5.5,5 12,5C18.5,5 22,12 22,12C22,12 18.5,19 12,19C5.5,19 2,12 2,12Z', alpha: 0.45 },
    { d: circle(12, 12, 3), alpha: 0.45 }, 'M3,3l18,18',
  ],
  ruler_persp: ['M2,7h20', 'M12,7L3,21', 'M12,7L21,21', 'M12,7L8.5,21', 'M12,7L15.5,21', { d: circle(12, 7, 1.8), fill: true }],
  ruler_circle: [circle(12, 12, 9.5), circle(12, 12, 5.5), { d: circle(12, 12, 1.8), fill: true }],
  more: [{ d: circle(5, 12, 1.6), fill: true }, { d: circle(12, 12, 1.6), fill: true }, { d: circle(19, 12, 1.6), fill: true }],
  mask: [rect(3, 3, 18, 18, 2), { d: circle(12, 12, 5), fill: true, alpha: 0.9 }],
  chevron_right: ['M9,6l6,6l-6,6'],
  chevron_down: ['M6,9l6,6l6,-6'],
  chevron_left: ['M15,6l-6,6l6,6'],
  chevron_up: ['M6,15l6,-6l6,6'],

  // ---- 변형 바 ----
  flip_h: ['M3,7l5,5l-5,5V7', 'M21,7l-5,5l5,5V7', 'M12,20v2M12,14v2M12,8v2M12,2v2'],
  flip_v: ['M7,3l5,5l5,-5H7', 'M7,21l5,-5l5,5H7', 'M2,12h2M8,12h2M14,12h2M20,12h2'],
  rotate_90: ['M21,12a9,9 0 1,1 -9,-9c2.52,0 4.93,1 6.74,2.74L21,8', 'M21,3v5h-5'],
  check: ['M20,6L9,17l-5,-5'],
  close: ['M18,6L6,18', 'M6,6l12,12'],

  // ---- 성능 ----
  gauge: ['M12,14l4,-4', 'M3.34,19a10,10 0 1,1 17.32,0'],
  // 캔버스 편집: 사각형 + 바깥으로 넓히는 모서리
  canvas: [rect(6, 6, 12, 12, 1), 'M2,8V2h6M22,16v6h-6'],
  // 필터·색조 보정: 조절 슬라이더 세 줄
  adjust: ['M4,6h4M12,6h8M4,12h10M18,12h2M4,18h2M10,18h10', circle(10, 6, 2), circle(16, 12, 2), circle(8, 18, 2)],
};

const norm = (e) => (typeof e === 'string' ? { d: e } : e);

function toXml(elems) {
  const paths = elems.map(norm).map((e) => {
    if (e.fill) {
      return `    <path android:fillColor="${COLOR}"${e.alpha != null ? ` android:fillAlpha="${e.alpha}"` : ''}\n        android:pathData="${e.d}" />`;
    }
    return `    <path android:strokeColor="${COLOR}" android:strokeWidth="${SW}"${e.alpha != null ? ` android:strokeAlpha="${e.alpha}"` : ''}\n        android:strokeLineCap="round" android:strokeLineJoin="round"\n        android:pathData="${e.d}" />`;
  });
  return `<?xml version="1.0" encoding="utf-8"?>
<!-- tools/icons/gen_icons.js 로 생성된 파일입니다. 직접 고치지 말고 생성기를 고치세요. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
${paths.join('\n')}
</vector>
`;
}

function toSvg(elems) {
  const inner = elems.map(norm).map((e) => e.fill
    ? `<path fill="${COLOR}" fill-opacity="${e.alpha ?? 1}" d="${e.d}"/>`
    : `<path fill="none" stroke="${COLOR}" stroke-width="${SW}" stroke-opacity="${e.alpha ?? 1}" stroke-linecap="round" stroke-linejoin="round" d="${e.d}"/>`).join('');
  return `<svg viewBox="0 0 24 24" width="48" height="48">${inner}</svg>`;
}

fs.mkdirSync(outDir, { recursive: true });
for (const old of fs.readdirSync(outDir)) {
  if (/^ic_(?!launcher)/.test(old)) fs.unlinkSync(path.join(outDir, old));
}
for (const [name, elems] of Object.entries(icons)) {
  fs.writeFileSync(path.join(outDir, `ic_${name}.xml`), toXml(elems));
}
const cells = Object.entries(icons).map(([n, e]) => `<div class="c">${toSvg(e)}<span>${n}</span></div>`).join('');
fs.writeFileSync(previewFile, `<!doctype html><meta charset="utf-8"><title>DFPaint icons</title>
<style>body{background:#232427;color:#9C9EA3;font:12px sans-serif;display:flex;flex-wrap:wrap;gap:8px;padding:16px}
.c{width:110px;display:flex;flex-direction:column;align-items:center;gap:6px;padding:10px;background:#33353A;border-radius:8px}</style>${cells}`);
console.log(`${Object.keys(icons).length} icons → ${outDir}`);
