# TurboDL

> 고성능 멀티스레드 다운로드 엔진 SDK — 순수 Kotlin/JVM, Android 의존성 없음, 모든 JVM 애플리케이션(Android 포함)에 직접 통합 가능.

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](../../LICENSE)

**언어:** [English](../../README.md) · [简体中文](README_zh-CN.md) · [繁體中文](README_zh-TW.md) · [日本語](README_ja.md) · 한국어 · [Deutsch](README_de.md)

TurboDL은 처음부터 새로 작성한 멀티스레드 다운로드 코어입니다. 성숙한 다운로드 관리자(aria2, IDM/XDM, axel, Persepolis, Motrix, ab-download-manager)의 아키텍처 및 알고리즘 **아이디어만 참고**했으며 **어떤 소스 코드도 복사하지 않았습니다**. 따라서 관대한 **MIT 라이선스(플러그인 생태계 보충 조항 포함)** 로 배포되며, 오픈소스 또는 상용 프로젝트에서 자유롭게 사용할 수 있습니다.

## 특징

- **멀티스레드 분할 다운로드**: HTTP Range 분할 병렬 처리, 연결 재사용(HTTP/2 멀티플렉싱 + keep-alive).
- **동적 분할**: 세분화된 사전 분할 + 작업 훔치기(work stealing)로, 느린 연결이 전체 전송을 지연시키지 않으며 "마지막 조각을 단일 스레드로 마무리"하는 롱테일을 제거합니다(IDM/XDM 아이디어).
- **순차 조각 우선**: 앞쪽 조각을 먼저 다운로드하여 점진적 미리보기/재생을 가능하게 합니다(axel/Persepolis 아이디어).
- **견고한 폴백 및 재시도**:
  - 서버가 Range를 지원하지 않거나 무시함 → 전체 파일 단일 스트림 다운로드로 자동 폴백;
  - 불량/타임아웃 조각 → **해당 조각만 재시도**, 전체 작업을 폐기하지 않음;
  - 실제로 반환된 바이트가 요청한 Range 구간과 일치하는지 검증(서버가 Range를 조작하여 전체 파일을 반환하는 것을 방지).
- **절제된 적응성**: 429/503을 받거나 연속 실패가 임계값에 도달할 때**만** 동시성을 곱셈적으로 낮추며, 일반적인 네트워크 지터로는 스레드 수를 **절대** 줄이지 않습니다(AIMD 지터 휴리스틱을 그대로 쓰지 않음).
- **이어받기**: 조각이 영속화되어, 일시정지/재개 시 디스크의 실제 진행 상태에서 이어집니다.
- **바이트 수준 무결성 검사**: 병합 후 총 크기를 검증하여 손상된 파일을 거부합니다.

## 9가지 엔진 기능

| 기능 | 설정 필드 |
|---|---|
| 전역 속도 제한 | `globalSpeedLimitBytesPerSec`(토큰 버킷, 0 = 무제한) |
| 스레드 수(최대 256) | `maxConnectionsPerTask`(1..256) |
| 동시 작업 수 | `maxConcurrentTasks`(1..64) |
| 최대 다운로드 재시도 횟수 | `maxRetries`(0..50) |
| 동적 분할 | `dynamicSegmentation`(true/false) |
| 수동/자동 프록시 | `proxy = Direct / System / Manual(HTTP,SOCKS,인증) / Pac(url)` |
| DNS 설정 | `dns = System / StaticHosts / DoH(url)` |
| SSL 무시 | `trustAllCerts` |
| 테마 | 상위 앱이 담당(SDK는 UI 렌더링에 관여하지 않음) |

## 빠른 시작

```kotlin
import dev.turbodl.core.*
import java.io.File

val client = TurboClient(
    TurboConfig(
        maxConnectionsPerTask = 16,
        globalSpeedLimitBytesPerSec = 0,        // unlimited
        dynamicSegmentation = true,
        proxy = ProxyMode.Direct,
        dns = DnsMode.System,
    )
)

// 작업 제출
val id = client.submit(
    DownloadRequest(
        url = "https://example.com/big.zip",
        destination = File("big.zip"),
    )
)

// 진행 이벤트 관찰
scope.launch {
    client.events.collect { event ->
        when (event) {
            is TurboEvent.Progress -> println("${event.progress.percent}%  ${event.progress.speedBytesPerSec} B/s")
            is TurboEvent.Completed -> println("완료: ${event.file}")
            is TurboEvent.Failed -> println("실패: ${event.reason}")
            else -> {}
        }
    }
}

// 완료될 때까지 suspend
val result = client.await(id)   // Result<File>

// 제어
client.pause(id)
client.resume(id)
client.cancel(id, deleteOutput = true)

client.shutdown()
```

## 커맨드 라인

```
./gradlew :turbodl-cli:installDist
./turbodl-cli/build/install/turbodl-cli/bin/turbodl <url> [출력 파일] --threads 16 --limit 0 [--insecure] [--no-dynamic]
```

## 빌드

```
./gradlew build        # 컴파일 + 단위 테스트 실행
```

단위 테스트는 내장 HTTP(Range) 서버로 다음을 커버합니다: 멀티스레드 바이트 수준 정확성, 동적 분할, Range 미지원 시 폴백, Range 조작 시 폴백, 일시적 503 시 해당 조각만 재시도, 전역 속도 제한.

## 모듈

- `turbodl-core`: 다운로드 엔진 SDK(배포되는 라이브러리, 독립 사용 가능, 플러그인 프레임워크 의존성 없음).
- `turbodl-cli`: SDK 사용법을 보여주는 커맨드 라인 예제.
- `turbo-plugin-runtime`: **선택적** 플러그인 런타임 커널(생명주기 / disposer / 이벤트 버스 / 서비스 레지스트리 / 확장 포인트 / 버전 핸드셰이크 / 진단). core는 이것에 의존하지 않으며, 포함하지 않아도 core는 평소대로 동작합니다.
- `turbo-plugin-bootstrap`: **선택적** 부트스트랩 모듈로 기본 플러그인(Kotlin 로더 + HTTP 백엔드)을 원클릭으로 배선; 필수 의존성이 아닙니다.
- `turbo-plugin-hls`: **선택적** HLS VOD 프로토콜 어댑터 플러그인 — master/media M3U8 재생목록을 해석하고, 세그먼트를 동시에 다운로드하며(세그먼트별 재시도), AES-128을 복호화하고, EXT-X-BYTERANGE를 준수하며, 병합을 위해 정렬된 조각을 엔진에 반환합니다. 라우팅되는 `DownloadBackend`로 스스로를 등록합니다; 지원하지 않는 구조(라이브 스트림, DRM/SAMPLE-AES, fMP4 EXT-X-MAP, discontinuity)는 손상된 출력을 만드는 대신 명시적으로 실패합니다.
- `demo`: 실행 가능한 세 가지 예제 — Kotlin 네이티브 플러그인, bootstrap 사용, Shim 어댑터 템플릿. `./gradlew :demo:run --args="1"`(또는 `2`, `3`, `all`)로 실행.

## 플러그인 프레임워크(선택)

TurboDL은 독립 실행형 엔진이자 **동시에** 선택적 플러그인 플랫폼입니다. 세 가지 아이디어가 설계를 이끕니다:

1. **Core는 단독으로 동작한다.** `turbodl-core`는 플러그인 의존성이 전혀 없는 완전한 멀티스레드 엔진입니다. 플러그인은 엄격하게 부가적입니다.
2. **모든 것이 플러그인이며, 커널은 메커니즘일 뿐이다.** 런타임 커널은 어떤 프로토콜도 알지 못합니다 — 생명주기, 정리(disposer), 서비스 레지스트리, 타입 안전 이벤트 버스, 확장 포인트 레지스트리, 버전 핸드셰이크, 진단이라는 메커니즘만 제공합니다. Kotlin 로더, HTTP 백엔드, HLS 백엔드는 모두 평범한 플러그인입니다.
3. **하이브리드 A+B.** Core는 내장 HTTP 백엔드(A)를 함께 제공하며; 플러그인 백엔드는 레지스트리를 통해 그것을 덮어쓰거나 프로토콜을 추가할 수 있습니다(B) — core가 런타임에 의존하는 일은 결코 없습니다. 의존 방향은 엄격하게 `runtime → core`이며, 그 반대는 없습니다.

버전이 매겨진 공개 API(`ApiVersion`, 현재 `1.0.0`)와 로드 시점 핸드셰이크는, 향후 호환성을 깨는 core 릴리스가 동작을 조용히 손상시키는 대신 크게 실패하도록 보장합니다(플러그인이 `INCOMPATIBLE`로 표시되어 결코 로드되지 않음).

플러그인 문서:
- [플러그인 제작 가이드](plugins/README_ko.md) — 플러그인을 만들고 통합하는 방법.
- [개발 협정](plugins/CONVENTION_ko.md) — 공식 호환성 규칙집(안정 API, 버전 관리, 명명, 안전).
- [플러그인 마켓](plugins/MARKET_ko.md) — GitHub 토픽 + `turbodl-plugin.json` 매니페스트를 통한 플러그인 발행/발견.

## 설계 노트 및 감사의 말

TurboDL의 설계는 다음 오픈소스 프로젝트의 아이디어(아이디어만, **소스 코드 복사 없음**)를 받아들였으며, 이에 감사드립니다:
[aria2](https://github.com/aria2/aria2), [Xtreme Download Manager](https://github.com/subhra74/xdm), [axel](https://github.com/axel-download-accelerator/axel), [Persepolis](https://github.com/persepolisdm/persepolis), [Motrix](https://github.com/agalwood/Motrix), [ab-download-manager](https://github.com/amir1376/ab-download-manager).

## 라이선스

[MIT License](../../LICENSE), "플러그인 생태계 보충 조항" 포함(플러그인은 독립적인 저작물이며, 자유롭게 라이선스할 수 있고, 공개 API / 확장 포인트를 통해 상호작용한다는 이유만으로 파생 저작물로 간주되지 않음을 명확히 함).
