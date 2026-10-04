# DFPaint — Claude Code 작업 안내

안드로이드 태블릿용 드로잉 앱(클립 스튜디오 지향). Kotlin + OpenGL ES 3.0, **Android 프레임워크만 사용 (AndroidX·외부 라이브러리 없음)**.
아키텍처 문서: https://claude.ai/code/artifact/69d09e14-43b0-47a0-99d6-ba4050210900

## 현재 상태 (v0.2.0)

- v0.1(MVP)과 v0.2(2단계)는 Cowork 클라우드 세션에서 작성했습니다.
- 2026-10-01: Gradle 빌드 통과, Galaxy Tab S7(SM-T875N, Android 13)에 설치. 같은 날 추가한 기능:
  - **대칭 그리기** (`input/Symmetry.kt`, 상단 바 "대칭" 버튼으로 끄기→좌우→상하→사방→방사 순환, `OverlayView`에 안내선)
  - **직선 자** (`CanvasView`의 `lineMode`, `Host.straightLine`, `CanvasRenderer.setStrokeLine`)
- 2026-10-04: 빌드·설치 확인. 추가한 것:
  - **아이콘 UI**: 상단 바·도구 막대·레이어 패널·변형 바를 벡터 아이콘으로. 아이콘은 `tools/icons/gen_icons.js`가 `res/drawable/ic_*.xml`을 생성하므로 **XML을 직접 고치지 말고 생성기를 고친 뒤 `node tools/icons/gen_icons.js`** (미리보기 `tools/icons/preview.html`). 말풍선은 `View.tooltipText`(길게 누르기/펜 호버), 단축키 표시는 `Ui.Tips`가 붙입니다.
  - **새 캔버스 배경**: 흰색/투명/색 지정. 배경이 있으면 맨 아래 "배경" 레이어(색으로 채운 타일) + "레이어 1". 선택은 `AppSettings.canvasTransparent/canvasBackground`에 기억.
  - **성능 측정** (상단 바 게이지 아이콘): FPS·프레임 시간·펜 지연(`engine/PerfMonitor.kt`), 부하 테스트 문서(A4 300dpi · 50장), 합성 벤치마크(전체/512px 영역/표시만, glFinish 포함).
  - **자동 선택**: 선택 도구의 4번째 모양 "자동"(SelShape.WAND). 채우기의 FloodFill로 영역을 구해 SelectionMask.applyMask로 합칩니다 (CanvasRenderer.selectByColor). 옵션은 AppSettings.wand*.
  - **레이어 마스크**: 마스크 픽셀은 surfaces[-id] (알파 = 가리는 정도, 없는 타일 = 보임). 마스크 편집 중(CanvasRenderer.maskEditing)에는 editId(n) = -id로 그리고, 그리기↔지우개를 뒤집어 확정합니다(commitToActive/updatePreview). 합성은 layerSrc()가 maskTmp에 "레이어 × (1 − 마스크)"를 만들어 씀. 구조 변경(applyShape)이 -id surface도 보관/복원. 레이어를 이동·변형하면 마스크도 같은 행렬로 함께 옮김(Op.Transform.maskFloating/maskMatrix, placeFloating). .dfp는 layers/<id>_mask.png, PSD는 채널 -2(255 − 가림).
  - **입·출 처리** (`input/StrokeTaper.kt`): 원래 스탬프와 시작점부터 거리를 기록. 입은 그리는 중에 반지름 배율, 출은 펜을 뗄 때 획 전체를 다시 계산해 `CanvasRenderer.setStrokeLine`으로 스트로크 버퍼를 통째로 다시 그림. 가늘어진 구간은 스탬프 보충. 값은 `Brush.taperIn/taperOut`(캔버스 px).
  - **원근 자·동심원 자** (`input/GuideRuler.kt`): 획 시작 후 10dp 움직일 때까지 점을 모았다가(`CanvasView.feedPoint/resolveRuler`) 방향이 가장 가까운 직선/원을 고르고 이후 점을 투영. 손잡이 위에서 시작하면 Mode.RULER로 손잡이 이동. 안내선은 `OverlayView.drawRuler`. 자 상태는 AppSettings.rulerState에 저장(onPause), 캔버스 크기가 바뀌면 비율대로 옮김(fitCanvas).
  - **필터 · 색조 보정** (`engine/Filters.kt`, `Shaders.FILTER_FS/FILTER_COMBINE_FS`): Op.Filter가 필터 영역(내용 타일 경계 + 흐리기 여백, 선택이 있으면 그 경계와 교차) 크기 버퍼 3장(orig/work/result)을 잡고, 값이 바뀌면 stale만 표시해 다음 합성 때 한 번 계산(흐리기 = 가로·세로 2패스, 선형 보간 탭). 합치는 단계에서 선택 영역·투명 잠금·언샤프를 처리. 확정은 result를 타일에 블렌딩 없이 덮어쓰고 TilesCommand 한 단계. UI는 `MainActivity.showFilter`(배경을 어둡게 하지 않는 아래쪽 대화상자), 끝나면 `Listener.onFilterEnded`.
  - **PSD 입출력** (`document/PsdIO.kt`): RGB 8비트, 레이어·폴더(통과)·불투명도·합성 모드·클리핑·표시·투명 잠금(lspf)·한글 이름(luni). 쓰기는 RLE. 읽기는 무압축/RLE/ZIP(예측 포함) 레이어 채널, 마스크·효과는 무시. JVM 왕복 테스트와 ag-psd 교차 확인은 통과, 포토샵/클립 스튜디오에서 실제로 열어 보지는 않았습니다.

## 빌드 (Windows)

- Android Studio를 한 번 실행해 Setup Wizard(Standard)를 끝내야 SDK가 생깁니다.
- JDK: **`%LOCALAPPDATA%\Programs\jdk-17`** 를 씁니다. Android Studio 내장 JBR은 Java 25라서 Gradle 8.9가 실행되지 않습니다("What went wrong: 25.0.3").
- SDK: `C:\Users\12kan\AppData\Local\Android\Sdk` (`local.properties`)
- 이 환경에서는 `gradlew.bat`이 cmd에서 인식되지 않는 경우가 있어, 래퍼 jar를 직접 실행합니다 (Git Bash):
  ```bash
  export JAVA_HOME="$LOCALAPPDATA/Programs/jdk-17"
  "$JAVA_HOME/bin/java" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleDebug
  ```
  설치는 같은 명령에 `installDebug`.
- adb: 다른 프로그램이 구버전(40) adb 서버를 띄워 두면 SDK의 adb(41)와 서로 서버를 죽이며 기기가 끊깁니다. 그 프로그램을 끄거나, Android Studio의 Run으로 설치하세요.
- 결과물: `app\build\outputs\apk\debug\app-debug.apk`
- 버전: AGP 8.5.2, Gradle 8.9(wrapper), Kotlin 2.0.21, compileSdk 34, minSdk 26, JVM 17

## 코드 규칙

- UI 문자열과 주석은 한국어. 사용자 메시지는 존댓말(~합니다/~세요).
- AndroidX를 추가하지 않습니다. UI는 코드로 만든 프레임워크 위젯(`ui/Ui.kt` 도우미).
- **색은 하드코딩하지 말고 `Ui.TEXT/CARD/BUTTON_ON…`** (라이트/다크 테마, `Ui.applyTheme`). 대화상자는 `Ui.dialog(ctx)`, 아이콘은 `Ui.iconButton`(ghost = 묶음 안), 드롭다운은 `Ui.styleSpinner`, 슬라이더는 `Ui.styleSeek`.
- 테마가 바뀌면 `MainActivity.rebuildUi()`가 캔버스(GLSurfaceView)는 그대로 두고 둘레 UI(`buildChrome`)만 다시 만듭니다. **GLSurfaceView를 떼면 GL 컨텍스트를 잃으니 절대 떼지 말 것.**
- 떠 있는 패널(상단 바·도구 막대·오른쪽 패널)의 위치는 `ui/FloatingPanels.kt`가 view.x/y로 정합니다. LayoutParams는 TOP|START에 두고 여백으로 위치를 박지 말 것. 위치는 남는 공간 비율(0/0.5/1 = 시작/가운데/끝)로 `AppSettings.topBarFx…`에 저장.
- GL 호출은 **GL 스레드에서만**. UI 스레드는 `CanvasRenderer`의 public 메서드(명령 큐에 post)만 호출하고, 결과는 `Listener` 콜백(메인 스레드)으로 받습니다.
- `GLES20.xxx` / `GLES30.xxx`를 구분해서 씁니다 (3.0 전용만 GLES30).

## 핵심 규약 (깨뜨리면 그림이 틀어짐)

- **좌표**: 캔버스 (0,0) = 왼쪽 위, px. 텍스처 0행 = 캔버스 맨 윗줄. 캔버스 크기 타깃에 그릴 때 NDC y=-1 ↔ 0행. 그래서 `glReadPixels` 결과가 그대로 위→아래 이미지 행입니다.
- **픽셀 형식**: 프리멀티플라이드 RGBA8. 마스크/스트로크 버퍼는 R8.
- **타일**: 레이어 = 256px 희소 타일(`engine/Tiles.kt`). 타일에 캔버스 좌표계 셰이더로 그릴 때 뷰포트를 `(-originX, -originY, canvasW, canvasH)`로 둡니다. 시저는 타일 FBO 좌표로 변환(`scissorCanvasRect`).
- **합성**: 활성 노드의 루트 조상(클리핑이면 기준 레이어)보다 아래는 `belowCache`. 매 프레임은 dirty 영역만 다시 합성하고, 결과는 항상 같은 버퍼(`startBuf`)에 남깁니다.
- **블렌딩**: 일반 = `ONE, ONE_MINUS_SRC_ALPHA`, 지우개 = `ZERO, ONE_MINUS_SRC_ALPHA`, 클리핑·투명 잠금 = source-atop(`GlState.atop`).
- **실행취소**: `TilesCommand`(타일 교환), `StructureCommand`(트리 모양 전/후, 삭제된 레이어 픽셀은 parked), `SelectionCommand`(RLE), `CompoundCommand`.

## 파일 지도

```
engine/    CanvasRenderer(중심), Tiles, BrushEngine, Compositor, Shaders, RenderTarget, Selection, FloodFill, Viewport, GlUtil
brush/     Brush(프리셋), BrushLibrary(보조 도구·팁·필압 곡선), StrokeBuilder
document/  Model(트리), History, ProjectIO(.dfp v2)
input/     CanvasView(입력·제스처·도구)
shortcut/  Action(기본 키맵), ShortcutManager(저장소+디스패처), ShortcutSettingsActivity
ui/        MainActivity, OverlayView, LayerPanel, ToolOptions, BrushEditor, PressureCurveView, ColorPickerView, Dialogs, AppSettings, Ui
```

## 다음 단계

1. 빌드 통과 → 태블릿에서 실행해 기본 동작 확인 (펜 필압, 레이어, 실행취소, 저장/열기, 단축키).
2. 로드맵 관문 1·2 측정 결과 (2026-10-04, Tab S7 120Hz, `adb shell input stylus` 합성 입력):
   - 펜 지연 평균 8.1ms(1.0프레임), 그리는 중 116fps → 관문 1 통과(실제 S펜으로 재확인 필요)
   - A4 300dpi·50장: 브러시 영역 7.4ms(135fps), 활성 레이어 속성 변경 7.7ms(130fps), 화면 표시 7.5ms → 관문 2 통과
   - 남은 병목: **전체 다시 합성** (아래쪽 레이어의 표시/속성 변경, 다른 레이어 선택, 구조 실행취소 때 한 번씩). 52ms → 같은 세션 비교로 약 38~46ms(fetch) vs 45~49ms(fetch 끔). GPU 클럭에 따라 세션마다 ±30% 흔들리므로 **비교는 같은 세션에서** (벤치마크 5단계가 fetch 끔).
     벤치마크 보고서의 "└ 아래 합성 · 캐시 저장 · 캐시 복사 · 위 합성"이 구간별 시간. 대부분이 아래 레이어 합성.
   - 반영한 것: ① 비표준 합성 모드 타일 레이어는 내용 영역만 ② 활성 쪽 속성 변경 시 아래 캐시 유지 ③ **FBO 바인딩 캐시**(GlState.bindFbo — 모든 FBO 바인딩은 이것만, 지울 때 forgetFbo) ④ **framebuffer fetch 합성**(GL_EXT_shader_framebuffer_fetch, 없으면 핑퐁).
   - 다음 후보: 타일 그리기 호출 묶기(레이어당 타일마다 draw 1번 → 인스턴싱/텍스처 배열), 위쪽 캐시.
3. PSD를 클립 스튜디오/포토샵에서 열어 확인.
4. 3단계 후보: 벡터 레이어, 자/원근/대칭 가이드, 애니메이션 타임라인, 퀵 액세스·도킹 패널, NDK/Vulkan 브러시 엔진.
