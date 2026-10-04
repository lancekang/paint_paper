package kr.dfluid.paint.engine

/**
 * GLSL ES 3.00 셰이더 모음.
 *
 * 좌표 규약: 캔버스 좌표 (0,0) = 왼쪽 위, 단위 px.
 * 캔버스 크기 텍스처에 그릴 때 NDC y = -1 이 텍스처 0행 = 캔버스 맨 윗줄이 되도록 맞춥니다.
 * 타일에 그릴 때는 뷰포트를 타일 원점만큼 음수로 밀어서 같은 셰이더를 씁니다.
 *
 * 모든 정점 셰이더는 v_uv(소스 텍스처 좌표)와 v_cuv(캔버스 정규화 좌표)를 내보냅니다.
 */
object Shaders {

    /** 단위 사각형 → 캔버스 전체. */
    const val QUAD_VS = """#version 300 es
layout(location = 0) in vec2 a_pos;
out vec2 v_uv;
out vec2 v_cuv;
void main() {
    v_uv = a_pos;
    v_cuv = a_pos;
    gl_Position = vec4(a_pos * 2.0 - 1.0, 0.0, 1.0);
}
"""

    /** 타일 한 장을 캔버스 위 제자리에. */
    const val TILE_VS = """#version 300 es
layout(location = 0) in vec2 a_pos;
uniform vec2 u_origin;
uniform vec2 u_canvas;
out vec2 v_uv;
out vec2 v_cuv;
void main() {
    vec2 p = u_origin + a_pos * 256.0;
    v_uv = a_pos;
    v_cuv = p / u_canvas;
    gl_Position = vec4(v_cuv * 2.0 - 1.0, 0.0, 1.0);
}
"""

    /** 떠 있는 픽셀(자유 변형)을 아핀 행렬로 캔버스에. u_m = 로컬 px → 캔버스 px. */
    const val AFFINE_VS = """#version 300 es
layout(location = 0) in vec2 a_pos;
uniform mat3 u_m;
uniform vec2 u_size;
uniform vec2 u_canvas;
out vec2 v_uv;
out vec2 v_cuv;
void main() {
    vec3 p = u_m * vec3(a_pos * u_size, 1.0);
    v_uv = a_pos;
    v_cuv = p.xy / u_canvas;
    gl_Position = vec4(v_cuv * 2.0 - 1.0, 0.0, 1.0);
}
"""

    /**
     * 떠 있는 픽셀을 원근 행렬로 캔버스에 (자유 변형의 원근·자유 모서리 모드).
     * clip = (p.xy/캔버스·2 − w, 0, w)로 두면 GPU가 v_uv를 원근 보정해 보간합니다.
     */
    const val PROJ_VS = """#version 300 es
layout(location = 0) in vec2 a_pos;
uniform mat3 u_h;
uniform vec2 u_size;
uniform vec2 u_canvas;
out vec2 v_uv;
out vec2 v_cuv;
void main() {
    vec3 p = u_h * vec3(a_pos * u_size, 1.0);
    v_uv = a_pos;
    v_cuv = p.xy / p.z / u_canvas;
    gl_Position = vec4(p.xy / u_canvas * 2.0 - p.z, 0.0, p.z);
}
"""

    /**
     * 텍스처 복사 (+불투명도, +선택 마스크).
     * u_maskMode: 0 없음, 1 마스크 안만, 2 마스크 밖만
     */
    const val COPY_FS = """#version 300 es
precision highp float;
in vec2 v_uv;
in vec2 v_cuv;
uniform sampler2D u_tex;
uniform sampler2D u_mask;
uniform float u_opacity;
uniform int u_maskMode;
out vec4 o;
void main() {
    vec4 c = texture(u_tex, v_uv) * u_opacity;
    if (u_maskMode == 1) c *= texture(u_mask, v_cuv).r;
    else if (u_maskMode == 2) c *= 1.0 - texture(u_mask, v_cuv).r;
    o = c;
}
"""

    /**
     * 레이어에 칠하기. 블렌딩 상태로 일반/지우개/투명 잠금(atop)을 고릅니다.
     * u_kind: 0 커버리지 텍스처 × 색 (붓, 채우기), 1 선형 그라데이션, 2 원형 그라데이션
     * u_c0, u_c1: 프리멀티플라이드 색
     */
    const val MERGE_FS = """#version 300 es
precision highp float;
in vec2 v_uv;
in vec2 v_cuv;
uniform int u_kind;
uniform sampler2D u_stroke;
uniform sampler2D u_sel;
uniform int u_useSel;
uniform vec4 u_c0;
uniform vec4 u_c1;
uniform vec2 u_p0;
uniform vec2 u_p1;
uniform vec2 u_canvas;
uniform float u_opacity;
out vec4 o;
void main() {
    vec4 c;
    if (u_kind == 0) {
        c = u_c0 * texture(u_stroke, v_cuv).r;
    } else {
        vec2 p = v_cuv * u_canvas;
        vec2 d = u_p1 - u_p0;
        float t;
        if (u_kind == 1) t = dot(p - u_p0, d) / max(dot(d, d), 1e-6);
        else t = length(p - u_p0) / max(length(d), 1e-6);
        c = mix(u_c0, u_c1, clamp(t, 0.0, 1.0));
    }
    c *= u_opacity;
    if (u_useSel == 1) c *= texture(u_sel, v_cuv).r;
    o = c;
}
"""

    /**
     * 블렌드 모드 합성 (핑퐁). 프리멀티플라이드 separable blend:
     * rgb = S(1-Da) + D(1-Sa) + B(s,d)·Sa·Da,  a = Sa + Da - Sa·Da
     * u_preserve = 1 (클리핑): S(1-Da) 항을 빼고 a = Da  (source-atop 일반화)
     */
    const val BLEND_FS = """#version 300 es
precision highp float;
in vec2 v_uv;
in vec2 v_cuv;
uniform sampler2D u_src;
uniform sampler2D u_dst;
uniform float u_opacity;
uniform int u_mode;
uniform int u_preserve;
out vec4 o;

vec3 blendFn(vec3 s, vec3 d) {
    if (u_mode == 1) return s * d;
    if (u_mode == 2) return s + d - s * d;
    if (u_mode == 3) return mix(2.0 * s * d, 1.0 - 2.0 * (1.0 - s) * (1.0 - d), step(0.5, d));
    if (u_mode == 4) return min(s + d, vec3(1.0));
    if (u_mode == 5) return min(s, d);
    if (u_mode == 6) return max(s, d);
    if (u_mode == 7) return abs(s - d);
    return s;
}

void main() {
    vec4 S = texture(u_src, v_uv) * u_opacity;
    vec4 D = texture(u_dst, v_cuv);
    vec3 s = S.a > 0.0 ? S.rgb / S.a : vec3(0.0);
    vec3 d = D.a > 0.0 ? D.rgb / D.a : vec3(0.0);
    vec3 mixed = blendFn(s, d) * S.a * D.a;
    float a;
    vec3 rgb;
    if (u_preserve == 1) {
        a = D.a;
        rgb = D.rgb * (1.0 - S.a) + mixed;
    } else {
        a = S.a + D.a - S.a * D.a;
        rgb = S.rgb * (1.0 - D.a) + D.rgb * (1.0 - S.a) + mixed;
    }
    o = vec4(min(rgb, vec3(a)), a);
}
"""

    /**
     * BLEND_FS와 같은 식을 framebuffer fetch로: 대상 픽셀을 텍스처가 아니라 현재 FBO에서 바로 읽습니다.
     * 핑퐁(다른 FBO로 복사 → 합성 → 되돌리기)이 없어져 타일 기반 GPU에서 렌더 패스 전환이 사라집니다.
     * GL_EXT_shader_framebuffer_fetch가 있을 때만 씁니다. 블렌딩은 끈 채로 그립니다.
     */
    const val BLEND_FETCH_FS = """#version 300 es
#extension GL_EXT_shader_framebuffer_fetch : require
precision highp float;
in vec2 v_uv;
in vec2 v_cuv;
uniform sampler2D u_src;
uniform float u_opacity;
uniform int u_mode;
uniform int u_preserve;
inout highp vec4 o;

vec3 blendFn(vec3 s, vec3 d) {
    if (u_mode == 1) return s * d;
    if (u_mode == 2) return s + d - s * d;
    if (u_mode == 3) return mix(2.0 * s * d, 1.0 - 2.0 * (1.0 - s) * (1.0 - d), step(0.5, d));
    if (u_mode == 4) return min(s + d, vec3(1.0));
    if (u_mode == 5) return min(s, d);
    if (u_mode == 6) return max(s, d);
    if (u_mode == 7) return abs(s - d);
    return s;
}

void main() {
    vec4 S = texture(u_src, v_uv) * u_opacity;
    vec4 D = o;
    vec3 s = S.a > 0.0 ? S.rgb / S.a : vec3(0.0);
    vec3 d = D.a > 0.0 ? D.rgb / D.a : vec3(0.0);
    vec3 mixed = blendFn(s, d) * S.a * D.a;
    float a;
    vec3 rgb;
    if (u_preserve == 1) {
        a = D.a;
        rgb = D.rgb * (1.0 - S.a) + mixed;
    } else {
        a = S.a + D.a - S.a * D.a;
        rgb = S.rgb * (1.0 - D.a) + D.rgb * (1.0 - S.a) + mixed;
    }
    o = vec4(min(rgb, vec3(a)), a);
}
"""

    /**
     * 필터 한 단계 (QUAD_VS, 필터 영역 크기 타깃). 프리멀티플라이드 입출력.
     * u_kind: 1 색조·채도·명도 (p = 색조°, 채도, 명도), 2 밝기·대비 (p = 밝기, 대비),
     *         3 색 반전, 4 가우시안 한 방향 (u_dir = 텍셀 단위 방향, p.x = 반지름 px), 5 포스터화 (p.x = 단계)
     * 흐리기는 선형 보간 텍스처에서 두 탭을 한 번에 읽습니다 (탭 수 절반).
     */
    const val FILTER_FS = """#version 300 es
precision highp float;
in vec2 v_uv;
uniform sampler2D u_src;
uniform int u_kind;
uniform vec4 u_p;
uniform vec4 u_q;
uniform vec2 u_dir;
uniform int u_taps;
out vec4 o;

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
    if (u_kind == 4) {
        float sigma = max(u_p.x * 0.5, 0.3);
        float k = -0.5 / (sigma * sigma);
        vec4 acc = texture(u_src, v_uv);
        float ws = 1.0;
        for (int i = 1; i <= u_taps; i += 2) {
            float fi = float(i);
            float w1 = exp(fi * fi * k);
            float w2 = exp((fi + 1.0) * (fi + 1.0) * k);
            float wt = w1 + w2;
            vec2 d = u_dir * ((fi * w1 + (fi + 1.0) * w2) / wt);
            acc += (texture(u_src, v_uv + d) + texture(u_src, v_uv - d)) * wt;
            ws += 2.0 * wt;
        }
        o = acc / ws;
        return;
    }
    vec4 S = texture(u_src, v_uv);
    if (S.a <= 0.0) { o = vec4(0.0); return; }
    vec3 c = S.rgb / S.a;
    if (u_kind == 1) {
        vec3 h = rgb2hsv(c);
        h.x = fract(h.x + u_p.x / 360.0 + 1.0);
        h.y = clamp(h.y * (1.0 + u_p.y), 0.0, 1.0);
        c = hsv2rgb(h);
        c = u_p.z >= 0.0 ? mix(c, vec3(1.0), u_p.z) : c * (1.0 + u_p.z);
    } else if (u_kind == 2) {
        c += u_p.x * 0.5;
        float f = u_p.y >= 0.0 ? 1.0 / (1.0 - u_p.y * 0.95) : 1.0 + u_p.y;
        c = (c - 0.5) * f + 0.5;
    } else if (u_kind == 3) {
        c = 1.0 - c;
    } else if (u_kind == 5) {
        float n = max(u_p.x - 1.0, 1.0);
        c = floor(c * n + 0.5) / n;
    } else if (u_kind == 6) {
        float lum = dot(c, vec3(0.299, 0.587, 0.114));
        c = mix(c, mix(u_p.yzw, u_q.xyz, lum), u_p.x);
    }
    o = vec4(clamp(c, 0.0, 1.0) * S.a, S.a);
}
"""

    /**
     * 필터 결과를 원본과 합칩니다 (QUAD_VS, 필터 영역 크기 타깃).
     * u_sharpen = 1: u_filt는 흐린 이미지, 결과 = 원본 + (원본 − 흐림) × u_amount
     * u_lock = 1 (투명 픽셀 잠금): 알파는 원본 그대로, 색만 필터 결과
     * u_useSel = 1: 선택 영역만큼만 섞음. u_rect = 필터 영역(캔버스 px)
     */
    const val FILTER_COMBINE_FS = """#version 300 es
precision highp float;
in vec2 v_uv;
uniform sampler2D u_orig;
uniform sampler2D u_filt;
uniform sampler2D u_sel;
uniform int u_useSel;
uniform int u_lock;
uniform int u_sharpen;
uniform float u_amount;
uniform vec4 u_rect;
uniform vec2 u_canvas;
out vec4 o;
void main() {
    vec4 O = texture(u_orig, v_uv);
    vec4 F = texture(u_filt, v_uv);
    if (u_sharpen == 1) {
        vec4 r = O + (O - F) * u_amount;
        float a = clamp(r.a, 0.0, 1.0);
        F = vec4(clamp(r.rgb, vec3(0.0), vec3(a)), a);
    }
    if (u_lock == 1) {
        vec3 c = F.a > 0.0 ? F.rgb / F.a : (O.a > 0.0 ? O.rgb / O.a : vec3(0.0));
        F = vec4(clamp(c, 0.0, 1.0) * O.a, O.a);
    }
    float k = 1.0;
    if (u_useSel == 1) k = texture(u_sel, (u_rect.xy + v_uv * u_rect.zw) / u_canvas).r;
    o = mix(O, F, k);
}
"""

    /**
     * 색 혼합 스탬프 (STAMP_VS, 인스턴스 1개). 작업 버퍼에 블렌딩 없이 그립니다.
     * u_patch = 작업 버퍼 일부를 복사해 둔 텍스처 (왼쪽 위 = u_origin 캔버스 px, 크기 u_psize).
     * 손끝(u_mode 0): 직전 스탬프 자리(u_shift = 직전 − 지금)의 색을 끌어옴. 흐리기(1): 둘레 평균.
     * k = 스탬프 모양 × 알파 × 강도 (× 선택 영역). 결과 = mix(지금 색, 가져온 색, k).
     */
    const val SMUDGE_FS = """#version 300 es
precision highp float;
in vec2 v_local;
in float v_alpha;
in float v_aa;
in vec2 v_canvasPos;
uniform float u_hardness;
uniform sampler2D u_patch;
uniform vec2 u_origin;
uniform vec2 u_psize;
uniform vec2 u_shift;
uniform int u_mode;
uniform float u_strength;
uniform float u_blurR;
uniform vec2 u_canvas;
uniform sampler2D u_sel;
uniform int u_useSel;
uniform int u_lock;
out vec4 o;
void main() {
    vec2 uv = (v_canvasPos - u_origin) / u_psize;
    vec4 cur = texture(u_patch, uv);
    float d = length(v_local);
    float w = max(v_aa, 1.0 - u_hardness);
    float a = clamp((1.0 - d) / w, 0.0, 1.0);
    a = a * a * (3.0 - 2.0 * a);
    float k = a * v_alpha * u_strength;
    if (u_useSel == 1) k *= texture(u_sel, v_canvasPos / u_canvas).r;
    vec4 src;
    if (u_mode == 0) {
        src = texture(u_patch, uv + u_shift / u_psize);
    } else {
        src = cur;
        for (int i = 0; i < 12; i++) {
            float ang = float(i) * 0.5235988;
            float r = (i % 2 == 0) ? u_blurR : u_blurR * 0.5;
            src += texture(u_patch, uv + vec2(cos(ang), sin(ang)) * r / u_psize);
        }
        src /= 13.0;
    }
    vec4 outc = mix(cur, src, k);
    if (u_lock == 1) {
        vec3 c = outc.a > 0.0 ? outc.rgb / outc.a : (cur.a > 0.0 ? cur.rgb / cur.a : vec3(0.0));
        outc = vec4(clamp(c, 0.0, 1.0) * cur.a, cur.a);
    }
    o = outc;
}
"""

    /**
     * 경계 효과 1단계 (QUAD_VS, 캔버스 크기): 가로로 가장 가까운 불투명(알파 ≥ 0.5) 픽셀까지 거리 → r = 거리/255 (없으면 1).
     */
    const val BORDER_H_FS = """#version 300 es
precision highp float;
in vec2 v_cuv;
uniform sampler2D u_src;
uniform vec2 u_texel;
uniform int u_r;
out vec4 o;
void main() {
    float best = 255.0;
    for (int i = -u_r; i <= u_r; i++) {
        float a = texture(u_src, v_cuv + vec2(float(i) * u_texel.x, 0.0)).a;
        if (a >= 0.5) best = min(best, abs(float(i)));
    }
    o = vec4(best / 255.0, 0.0, 0.0, 1.0);
}
"""

    /**
     * 경계 효과 2단계: 세로로 훑어 유클리드 거리 = min sqrt(가로거리² + dy²) → 테두리 커버리지 × 색 (프리멀티플라이드).
     * 원본은 이 위에 일반 합성으로 겹칩니다.
     */
    const val BORDER_V_FS = """#version 300 es
precision highp float;
in vec2 v_cuv;
uniform sampler2D u_h;
uniform vec2 u_texel;
uniform int u_r;
uniform float u_width;
uniform vec4 u_color;
out vec4 o;
void main() {
    float best = 1.0e9;
    for (int j = -u_r; j <= u_r; j++) {
        float dx = texture(u_h, v_cuv + vec2(0.0, float(j) * u_texel.y)).r * 255.0;
        if (dx < 254.5) best = min(best, dx * dx + float(j * j));
    }
    float cov = clamp(u_width + 0.5 - sqrt(best), 0.0, 1.0);
    o = u_color * cov;
}
"""

    /**
     * 톤 효과 (QUAD_VS, 캔버스 크기): 농도 = 알파 × (1 − 밝기)를 망점 크기로.
     * 칸 간격 u_cell px, 각도 u_angle(라디안)으로 돌린 격자에서 칸 중심까지 거리 < 반지름이면 망점 색.
     * 농도가 0.5를 넘으면 망점이 겹치도록 반지름을 칸 대각선까지 키웁니다 (검은 바탕에 흰 점처럼 보임).
     */
    const val TONE_FS = """#version 300 es
precision highp float;
in vec2 v_cuv;
uniform sampler2D u_src;
uniform vec2 u_canvas;
uniform float u_cell;
uniform float u_angle;
uniform vec4 u_color;
out vec4 o;
void main() {
    vec4 s = texture(u_src, v_cuv);
    vec3 c = s.a > 0.0 ? s.rgb / s.a : vec3(1.0);
    float lum = dot(c, vec3(0.299, 0.587, 0.114));
    float dens = clamp(s.a * (1.0 - lum), 0.0, 1.0);
    vec2 p = v_cuv * u_canvas;
    float cs = cos(u_angle), sn = sin(u_angle);
    vec2 q = vec2(cs * p.x + sn * p.y, -sn * p.x + cs * p.y) / u_cell;
    vec2 f = fract(q) - 0.5;
    float d = length(f) * u_cell;
    // 넓이가 농도에 비례하는 반지름 (0.5 이상은 겹치는 원으로 근사)
    float r = sqrt(dens / 3.14159265) * u_cell;
    float aa = 0.75;
    float cov = clamp((r - d) / aa + 0.5, 0.0, 1.0);
    if (dens <= 0.002) cov = 0.0;
    o = u_color * cov;
}
"""

    /** 레이어 컬러: 알파는 그대로, 색만 u_color로 (밝은 부분은 흰색 쪽으로 남겨 선의 농담을 유지). */
    const val COLORIZE_FS = """#version 300 es
precision highp float;
in vec2 v_cuv;
uniform sampler2D u_src;
uniform vec3 u_color;
out vec4 o;
void main() {
    vec4 s = texture(u_src, v_cuv);
    vec3 c = s.a > 0.0 ? s.rgb / s.a : vec3(0.0);
    float lum = dot(c, vec3(0.299, 0.587, 0.114));
    vec3 outc = mix(u_color, vec3(1.0), lum);
    o = vec4(outc * s.a, s.a);
}
"""

    /** 캔버스 → 화면. u_view = 캔버스 px → 화면 px 아핀 행렬. */
    const val DISPLAY_VS = """#version 300 es
layout(location = 0) in vec2 a_pos;
uniform mat3 u_view;
uniform vec2 u_canvas;
uniform vec2 u_screen;
out vec2 v_uv;
out vec2 v_cuv;
void main() {
    v_uv = a_pos;
    v_cuv = a_pos;
    vec3 s = u_view * vec3(a_pos * u_canvas, 1.0);
    gl_Position = vec4(s.x / u_screen.x * 2.0 - 1.0, 1.0 - s.y / u_screen.y * 2.0, 0.0, 1.0);
}
"""

    /** 투명 영역 = 체커보드, 선택 영역 경계 = 움직이는 점선(개미 행렬). */
    const val DISPLAY_FS = """#version 300 es
precision highp float;
in vec2 v_uv;
in vec2 v_cuv;
uniform sampler2D u_tex;
uniform sampler2D u_sel;
uniform int u_hasSel;
uniform float u_time;
out vec4 o;

bool inside(vec2 uv) {
    return texture(u_sel, uv).r > 0.5;
}

void main() {
    vec4 c = texture(u_tex, v_uv);
    vec2 q = floor(gl_FragCoord.xy / 12.0);
    float k = mod(q.x + q.y, 2.0);
    vec3 chk = mix(vec3(1.0), vec3(0.85), k);
    vec3 col = c.rgb + chk * (1.0 - c.a);
    if (u_hasSel == 1) {
        vec2 dx = dFdx(v_uv);
        vec2 dy = dFdy(v_uv);
        bool me = inside(v_uv);
        bool edge = inside(v_uv + dx) != me || inside(v_uv - dx) != me
                 || inside(v_uv + dy) != me || inside(v_uv - dy) != me;
        if (edge) {
            float stripe = mod(floor((gl_FragCoord.x + gl_FragCoord.y) / 5.0 + u_time * 6.0), 2.0);
            col = vec3(stripe);
        } else if (!me) {
            col *= 0.92;
        }
    }
    o = vec4(col, 1.0);
}
"""

    /** 스타일러스 호버 시 브러시 크기 원. 흰/검 이중선이라 어떤 배경에서도 보입니다. */
    const val CURSOR_FS = """#version 300 es
precision highp float;
uniform vec2 u_center;
uniform float u_radius;
out vec4 o;
void main() {
    float d = length(gl_FragCoord.xy - u_center);
    float dark = 1.0 - smoothstep(0.4, 1.1, abs(d - u_radius));
    float light = 1.0 - smoothstep(0.4, 1.1, abs(d - u_radius - 1.4));
    float dot0 = 1.0 - smoothstep(0.6, 1.4, d);
    float a = max(max(dark, light * 0.9), dot0);
    vec3 col = mix(vec3(1.0), vec3(0.0), max(dark, dot0));
    o = vec4(col * a, a);
}
"""

    /**
     * 브러시 스탬프 (인스턴싱).
     * i_center = 캔버스 px, i_params = (반지름, 회전각, 단축 비율, 알파)
     * 타원 경계가 |v_local| = 1 이 되도록 하고, 안티에일리어싱용으로 1px 여유를 둡니다.
     */
    const val STAMP_VS = """#version 300 es
layout(location = 0) in vec2 a_corner;
layout(location = 1) in vec2 i_center;
layout(location = 2) in vec4 i_params;
uniform vec2 u_canvas;
out vec2 v_local;
out float v_alpha;
out float v_aa;
out vec2 v_canvasPos;
void main() {
    float r = i_params.x;
    float ra = max(r * i_params.z, 0.35);
    vec2 ext = vec2(r + 1.0, ra + 1.0);
    v_local = a_corner * ext / vec2(r, ra);
    v_aa = 1.0 / ra;
    v_alpha = i_params.w;
    float c = cos(i_params.y);
    float s = sin(i_params.y);
    vec2 off = a_corner * ext;
    vec2 pos = i_center + vec2(off.x * c - off.y * s, off.x * s + off.y * c);
    v_canvasPos = pos;
    gl_Position = vec4(pos / u_canvas * 2.0 - 1.0, 0.0, 1.0);
}
"""

    const val STAMP_FS = """#version 300 es
precision highp float;
precision highp int;
in vec2 v_local;
in float v_alpha;
in float v_aa;
in vec2 v_canvasPos;
uniform float u_hardness;
uniform float u_grain;
uniform float u_grainScale;
uniform int u_useTip;
uniform sampler2D u_tip;
out vec4 o;

float hash(vec2 p) {
    uvec2 q = uvec2(ivec2(p) + 65536) * uvec2(1597334673u, 3812015801u);
    uint n = (q.x ^ q.y) * 1597334673u;
    return float(n) * (1.0 / 4294967295.0);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

void main() {
    float a;
    if (u_useTip == 1) {
        if (abs(v_local.x) > 1.0 || abs(v_local.y) > 1.0) discard;
        a = texture(u_tip, v_local * 0.5 + 0.5).r;
    } else {
        float d = length(v_local);
        float w = max(v_aa, 1.0 - u_hardness);
        a = clamp((1.0 - d) / w, 0.0, 1.0);
        a = a * a * (3.0 - 2.0 * a);
    }
    if (u_grain > 0.0) {
        vec2 gp = v_canvasPos / u_grainScale;
        float n = noise(gp * 0.8) * 0.65 + noise(gp * 0.27) * 0.35;
        a *= mix(1.0, smoothstep(0.3, 0.7, n), u_grain);
    }
    o = vec4(a * v_alpha);
}
"""
}
