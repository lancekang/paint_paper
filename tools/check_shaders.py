"""Shaders.kt의 GLSL ES 3.00 셰이더를 모두 꺼내 glslangValidator로 컴파일해 봅니다.

사용: python tools/check_shaders.py   (프로젝트 루트에서)
GPU 없이 문법·타입 오류를 미리 잡습니다. 기기 드라이버 고유 문제는 못 잡습니다.
"""
import os
import re
import subprocess
import sys
import tempfile

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src = os.path.join(root, 'app', 'src', 'main', 'java', 'kr', 'dfluid', 'paint', 'engine', 'Shaders.kt')
sdk = os.environ.get('ANDROID_HOME') or os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk')
validator = os.path.join(sdk, 'emulator', 'lib64', 'vulkan', 'glslangValidator.exe')
if not os.path.exists(validator):
    sys.exit('glslangValidator를 찾을 수 없습니다: ' + validator)

text = open(src, encoding='utf-8').read()
shaders = re.findall(r'const val (\w+) = """(.*?)"""', text, re.S)
fails = 0
with tempfile.TemporaryDirectory() as tmp:
    for name, body in shaders:
        stage = 'vert' if name.endswith('_VS') else 'frag'
        # framebuffer fetch 확장은 데스크톱 검증기가 모르므로 inout 출력을 일반 out으로 바꿔 검사
        if 'GL_EXT_shader_framebuffer_fetch' in body:
            body = body.replace('#extension GL_EXT_shader_framebuffer_fetch : require\n', '')
            body = body.replace('inout highp vec4 o;', 'out highp vec4 o;')
            body = body.replace('vec4 D = o;', 'vec4 D = vec4(0.0);')
        path = os.path.join(tmp, f'{name}.{stage}')
        open(path, 'w', encoding='utf-8').write(body)
        r = subprocess.run([validator, path], capture_output=True, text=True)
        ok = r.returncode == 0
        if not ok:
            fails += 1
        print(('OK   ' if ok else 'FAIL ') + name)
        if not ok:
            print(r.stdout.strip())
print(f'{len(shaders)}개 중 {fails}개 실패')
sys.exit(1 if fails else 0)
