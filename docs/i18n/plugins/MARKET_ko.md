# TurboDL 플러그인 마켓 (GitHub 토픽 기반)

TurboDL은 중앙 집중식 패키지 서버를 운영하지 않습니다. 플러그인 "마켓"은 단순히 **GitHub 토픽 태그와 기계가 읽을 수 있는 매니페스트**입니다. 누구나 저장소를 푸시하고, 올바른 토픽을 추가하고, 저장소 루트에 `turbodl-plugin.json`을 두는 것으로 플러그인을 발행할 수 있습니다. 누구나 GitHub 토픽 검색으로 플러그인을 발견할 수 있습니다. 이는 생태계를 개방적이고, 탈중앙적이며, 인프라가 필요 없게 유지합니다.

이 문서는 태그, 매니페스트, 발행/발견 흐름을 정의합니다. [플러그인 생태계 개발 협정](CONVENTION_ko.md)(호환성 규칙집) 및 [플러그인 제작 가이드](README_ko.md)와 함께 사용됩니다.

---

## 1. 플러그인 발견

모든 TurboDL 플러그인은 루트 토픽 **`turbodl-plugin`**을 가집니다. 여기서 둘러보세요:

```
https://github.com/topics/turbodl-plugin
```

GitHub 검색에서 토픽을 조합해 능력별로 좁히세요:

```
topic:turbodl-plugin topic:turbodl-backend      # 프로토콜 백엔드
topic:turbodl-plugin topic:turbodl-adapter       # shim/서비스 어댑터
topic:turbodl-plugin topic:turbodl-hls           # HLS 관련
```

GitHub API도 동작합니다:

```
GET https://api.github.com/search/repositories?q=topic:turbodl-plugin+topic:turbodl-backend
```

---

## 2. 토픽 태그("선반")

모든 플러그인 저장소는 `turbodl-plugin`과, 정확히 하나의 **카테고리** 토픽, 그리고 임의 개수의 **능력** 토픽을 가져야 합니다(MUST).

**루트(필수)**
- `turbodl-plugin`

**카테고리(하나 선택, 필수)**
- `turbodl-backend` — 다운로드 프로토콜 추가/덮어쓰기(`DownloadBackend`)
- `turbodl-adapter` — 외부 시스템/서비스 브리지(shim; 보통 `LinkParser` + 백엔드)
- `turbodl-parser` — 링크/매니페스트 파서만(`LinkParser`)
- `turbodl-hook` — 작업 전/후 처리(`TaskPreHook` / `TaskPostHook`)
- `turbodl-loader` — 플러그인 로더(`PluginLoaderProvider`, 예: JS provider)

**능력(선택, 임의 개수)**
- 프로토콜/포맷: `turbodl-hls`, `turbodl-dash`, `turbodl-ftp`, `turbodl-magnet`, `turbodl-m3u8`
- 동작: `turbodl-remux`, `turbodl-checksum`, `turbodl-notify`, `turbodl-unpack`
- 통합: `turbodl-cloud`, `turbodl-drm-free`

카테고리와 능력 태그는 마켓 플러그인을 만들고 찾기 쉽게 하는 핵심입니다: 당신은 선반을 고르고, 사용자는 그곳으로 필터링합니다.

---

## 3. `turbodl-plugin.json` 매니페스트

이 파일을 저장소 루트에 두세요. 도구(또는 미래의 공식 인덱서)가 당신의 플러그인을 이해하기 위해 읽는 유일한 기계 판독형 기술자입니다.

```json
{
  "manifestVersion": "1.0",
  "id": "backend.hls",
  "name": "HLS VOD Backend",
  "description": "Downloads HLS VOD (.m3u8) streams: variant selection, AES-128, byte-range.",
  "version": "1.0.0",
  "author": "your-name-or-org",
  "homepage": "https://github.com/you/turbodl-plugin-hls",
  "license": "MIT",

  "category": "turbodl-backend",
  "capabilities": ["turbodl-hls", "turbodl-m3u8"],

  "turbodl": {
    "apiMajor": 1,
    "requiredApiVersion": "1.0.0"
  },

  "entry": {
    "language": "kotlin",
    "pluginClass": "dev.turbodl.plugin.hls.HlsPlugin"
  },

  "artifact": {
    "type": "maven",
    "coordinates": "dev.turbodl:turbo-plugin-hls:1.0.0"
  },

  "extensionPoints": ["turbo.downloadBackend"],
  "services": ["backend.hls"]
}
```

필드 설명:
- `id`는 플러그인의 `Plugin.id`와 같아야 하며(MUST), 협정(§4)의 명명 규칙을 따라야 합니다.
- `turbodl.apiMajor`와 `requiredApiVersion`은 플러그인이 코드에서 선언한 것(`Plugin.requiredApiVersion`)과 일치해야 합니다(MUST). 이것이 마켓/도구가 주어진 TurboDL 버전에서 실행할 수 없는 플러그인을 **다운로드하기 전에** 걸러내는 방식입니다.
- `category`는 카테고리 토픽 중 하나여야 합니다(MUST); `capabilities`는 저장소의 능력 토픽을 반영해야 합니다(SHOULD).
- `entry.language`는 현재 `kotlin`입니다. `js`는 미래의 JS provider를 위해 **예약**되어 있습니다; core와 Kotlin 로더는 JS를 인지하지 않습니다.
- `artifact.type`은 `maven`(발행된 JAR) 또는 `jar`(`artifact.url`의 직접 release 에셋 URL)입니다. 당신의 배포 방식에 맞게 고르세요.

검증용 JSON Schema는 [`turbodl-plugin.schema.json`](../../plugins/turbodl-plugin.schema.json)에 있습니다.

---

## 4. 권장 저장소 레이아웃

```
turbodl-plugin-<name>/
├─ turbodl-plugin.json          # manifest (root)
├─ README.md                    # what it does, install snippet, supported TurboDL MAJOR
├─ LICENSE
├─ src/main/kotlin/...          # the Plugin implementation
└─ src/test/kotlin/...          # a test proving it loads + performs its capability
```

저장소 설명과 README는 지원하는 TurboDL MAJOR 라인을 명시적으로 밝혀야 합니다(SHOULD)(예: "TurboDL 1.x").

---

## 5. 발행 체크리스트

1. [제작 가이드](README_ko.md)와 [협정](CONVENTION_ko.md)에 따라 `Plugin`을 구현합니다.
2. `Plugin.requiredApiVersion`을 실제로 사용하는 API의 최저 버전으로 설정합니다.
3. 저장소 루트에 `turbodl-plugin.json`을 추가하고; schema에 대해 검증합니다.
4. GitHub 토픽 추가: `turbodl-plugin` + 카테고리 하나 + 능력.
5. README에 설치 스니펫과 지원하는 TurboDL MAJOR를 채웁니다.
6. `artifact`와 일치하는 아티팩트(Maven 좌표 또는 release JAR)를 발행합니다.
7. 버전이 매니페스트 `version`과 같은 release를 태깅합니다.

이것이 "마켓"의 전부입니다: 푸시, 태깅, 완료. 게이트키퍼도, 서버도 없습니다.

---

## 6. 플러그인 설치(소비자 측)

1. 플러그인 아티팩트를 빌드에 추가합니다(매니페스트의 Maven 좌표), `turbodl-core` 및 `turbo-plugin-runtime`와 함께.
2. 호스트에 설치합니다:

```kotlin
val host = PluginHost()
host.install(HlsPlugin())                 // or the plugin's documented entry class
// If you use the bootstrap convenience:
val boot = TurboBootstrap.create(extraPlugins = listOf(HlsPlugin()))
```

3. 버전 핸드셰이크가 자동으로 실행됩니다. 플러그인이 당신의 TurboDL보다 새로운 API를 필요로 하면 `INCOMPATIBLE`로 표시되고 절대 로드되지 않습니다 — `host.diagnostics().render()`를 확인하세요.

---

## 7. 신뢰와 안전

중앙 심사가 없으므로, 서드파티 플러그인을 다른 의존성처럼 취급하세요:
- 소스를 읽으세요; 테스트와 명확한 라이선스가 있는 플러그인을 선호하세요.
- 설치 전에 매니페스트 `apiMajor`가 당신의 TurboDL과 일치하는지 확인하세요.
- [협정 §9](CONVENTION_ko.md)는 플러그인이 따라야 할 보안 규칙을 나열합니다(신뢰할 수 없는 입력 검증, 데이터 유출 금지, 조용한 TLS 약화 금지). 이를 위반하는 플러그인은 해당 저장소에 신고되어야 하며 어떤 공식 인덱스에서도 목록에서 제외될 수 있습니다(MAY).

---

## 8. 미래: 선택적 공식 인덱스

토픽 기반 마켓은 서버가 필요 없습니다. 수요가 커지면, 프로젝트는 주기적으로 `topic:turbodl-plugin`을 크롤링하고, 각 `turbodl-plugin.json`을 검증하며, 카테고리·능력·지원 API MAJOR로 필터링할 수 있는 검색 가능한 목록을 렌더링하는 정적, 자동 생성 인덱스를 발행할 수 있습니다(MAY). 이는 항상 GitHub 토픽 위의 편의 계층으로 남으며, 결코 게이트키퍼가 아닙니다.
