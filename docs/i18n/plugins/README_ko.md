# TurboDL 플러그인

> 한국어 번역 · 원문: [English](../../../README.md) · [한국어 개요](../README_ko.md)

TurboDL은 독립 실행형 다운로드 엔진이자 **동시에** 선택적 플러그인 플랫폼입니다. 이 디렉터리는 플러그인 작성자에게 필요한 모든 자료가 모인 곳입니다:

- **[플러그인 제작 가이드](#플러그인-작성)** — 이 문서: 개념 + 단계별 안내.
- **[개발 협정](CONVENTION_ko.md)** — 공식 호환성 규칙집(안정 API, 버전 관리, 명명, 안전). 배포 전에 먼저 읽으세요.
- **[플러그인 마켓](MARKET_ko.md)** — GitHub 토픽 + `turbodl-plugin.json` 매니페스트([schema](../../plugins/turbodl-plugin.schema.json))를 통한 플러그인 발행/발견 방법.

실행 가능한 예제는 [`demo`](../../../demo) 모듈에 있습니다: `./gradlew :demo:run --args="1"`(Kotlin 플러그인), `"2"`(bootstrap), `"3"`(shim 어댑터).

---

## 철학

설계를 이끄는 세 가지 아이디어:

1. **Core는 단독으로 동작한다.** `turbodl-core`는 플러그인 의존성이 전혀 없는 완전한 독립 실행형 멀티스레드 다운로드 엔진입니다. 플러그인은 엄격하게 부가적입니다. 하나도 로드하지 않으면 아무것도 바뀌지 않습니다.

2. **모든 것이 플러그인이며, 커널은 메커니즘일 뿐이다.** 런타임 커널(`turbo-plugin-runtime`)은 HTTP, HLS 또는 어떤 프로토콜도 알지 못합니다. 오직 메커니즘만 제공합니다: 생명주기, 정리(disposer), 의존성 해결이 포함된 서비스 레지스트리, 타입 안전 이벤트 버스, 확장 포인트 레지스트리, 버전 핸드셰이크, 진단. Kotlin 로더, HTTP 백엔드, HLS 백엔드 — 이들은 모두 평범한 플러그인입니다. 미래의 JS 런타임조차 `PluginLoaderProvider`를 구현한 하나의 플러그인일 뿐입니다.

3. **하이브리드 A+B.** (A) Core는 내장 HTTP 백엔드를 함께 제공하여 바로 쓸 수 있습니다. (B) 플러그인 백엔드는 확장 레지스트리를 통해 내장 백엔드를 덮어쓰거나 새 프로토콜을 추가할 수 있습니다 — core가 런타임에 의존하는 일 없이. 의존 방향은 엄격하게 `runtime → core`이며, 그 반대는 없습니다.

결과: 서드파티가 그 위에서 개발할 수 있는 작고 안정적인 계약면과, 미래의 호환성을 깨는 core 릴리스가 플러그인을 조용히 손상시키는 대신 크게 실패하도록 하는 호환성 정책([협정](CONVENTION_ko.md)).

---

## 구성 요소

| 개념 | 타입 | 용도 |
|---|---|---|
| 플러그인 | `Plugin` | 당신이 구현하는 단위. `id`, `requiredApiVersion`, 선택적 `dependencies`를 가집니다. |
| 컨텍스트 | `PluginContext` | `onLoad`에 전달됨. 서비스/이벤트/확장 등록에 사용. 모든 등록은 언로드 시 자동 정리됩니다. |
| 정리 | `Disposer` | LIFO 정리 체인. 언로드 시 콜백별로 격리되어 배출됩니다. |
| 서비스 | `ServiceRegistry` | 경량 id→인스턴스 레지스트리 + 의존성 게이팅. |
| 이벤트 | `EventBus` | `TurboEvent` 관찰, 제출 시점에 `DownloadRequest` 가로채기. |
| 확장 포인트 | `ExtensionPointKey<T>` | 플러그인이 구현하는 타입화된 계약. 소비자는 key/우선순위로 조회. |
| 버전 | `ApiVersion` | 핸드셰이크: 호스트는 플러그인의 `requiredApiVersion`을 만족해야 함. |

**내장 확장 포인트**(`dev.turbodl.plugin.runtime.ext.ExtensionPoints`):
- `DOWNLOAD_BACKEND` — 프로토콜 추가/덮어쓰기(`DownloadBackend`)
- `LINK_PARSER` — 원시 링크를 `DownloadRequest`로 변환(`LinkParser`)
- `TASK_PRE_HOOK` — 제출 전 요청 재작성(`TaskPreHook`)
- `TASK_POST_HOOK` — 작업 종료 후 반응(`TaskPostHook`)

**커널**이 이름으로 아는 유일한 확장 포인트는 `PluginLoaderProvider`(로더)입니다.

---

## 플러그인 작성

### 1. 계약에 의존하기

```kotlin
dependencies {
    implementation("dev.turbodl:turbodl-core:<version>")
    implementation("dev.turbodl:turbo-plugin-runtime:<version>")
}
```

### 2. `Plugin` 구현하기

```kotlin
import dev.turbodl.core.ApiVersion
import dev.turbodl.plugin.runtime.Plugin
import dev.turbodl.plugin.runtime.PluginContext

class MyPlugin : Plugin {
    override val id = "adapter.acme"                 // 고유, 점 구분, 벤더 한정
    override val name = "ACME Adapter"
    override val requiredApiVersion = ApiVersion(1, 0, 0)   // 실제로 사용하는 최소 API

    override fun onLoad(context: PluginContext) {
        // 여기서 서비스 / 이벤트 / 확장 등록 — 언로드 시 모두 자동 정리
    }
}
```

### 3. `onLoad`에서 기능 등록하기

```kotlin
// 다른 플러그인이 의존할 수 있는 서비스:
context.registerService(id, myService)

// 엔진 이벤트 관찰(언로드 시 자동 구독 해제):
context.onEvent { event -> /* ... */ }

// 실행 전 요청 재작성:
context.interceptRequest { req -> req.copy(headers = req.headers + ("X-Trace" to "1")) }

// 확장 포인트 구현 제공(우선순위가 높은 쪽이 "그" 구현으로 선택됨):
context.registerExtension(ExtensionPoints.DOWNLOAD_BACKEND, myBackend, priority = 100)

// 정리가 필요한 그 밖의 모든 것:
context.disposer.register { myThreadPool.shutdown() }
```

`context`를 통해 등록한 모든 것은 플러그인이 언인스톨될 때 자동으로 해제됩니다. `onLoad`가 예외를 던지면 부분 등록은 대신 롤백됩니다.

### 4. `DownloadBackend` 작성하기

백엔드는 프로토콜을 소유하고, 엔진은 상태, 이벤트, 병합, 무결성을 소유합니다. 규칙(전체 목록은 [협정 §7](CONVENTION_ko.md)):

- `supports(request)` — 저렴하고, 부작용이 없으며, 보수적으로.
- `context.isActive()`와 코루틴 취소를 존중.
- 총 크기를 알게 되면 `context.reportTotalSize(total)`(알 수 없으면 `-1`), 진행 중에는 `context.reportProgress(...)`, 전역 속도 제한을 지키기 위해 `context.throttle(bytes)`.
- 조각을 `context.workDir`에 쓰고, **순서대로** `BackendResult.orderedParts`로 반환.
- 복구 불가능/범위 밖(out-of-scope) 입력에는 **예외를 던짐** — 손상된 파일을 절대 내보내지 않음.
- core의 HTTP 전송 정책(프록시/DNS/TLS)이 필요하면 `TurboHttpClients.create(config)` 사용; core 내부는 절대 건드리지 않음.

완전하고 비자명한 백엔드(재생목록 파싱, AES-128, 바이트 범위, 미지원 구조의 명시적 거부) 예시는 `turbo-plugin-hls`를 참고하세요.

### 5. 설치하기

```kotlin
val host = PluginHost()
host.install(MyPlugin())

// 다운로드를 플러그인 백엔드로 라우팅(일치가 없으면 내장 HTTP로 폴백):
val client = TurboClient(config)
client.backendResolver = BackendRegistry(host.extensions)
```

또는 bootstrap 편의 기능 사용:

```kotlin
val boot = TurboBootstrap.create(extraPlugins = listOf(MyPlugin()))
val id = boot.client.submit(DownloadRequest(url, dest))
```

### 6. 진단으로 검증하기

```kotlin
println(host.diagnostics().render())
// 플러그인과 상태(LOADED / WAITING / FAILED / INCOMPATIBLE / UNLOADED),
// 확장 포인트, 서비스, 리스너 수를 나열합니다.
```

플러그인이 `INCOMPATIBLE`로 표시되면, 실행 중인 TurboDL API가 당신의 `requiredApiVersion`을 만족하지 않는 것입니다 — 핸드셰이크를 확인하세요([협정 §2](CONVENTION_ko.md)).

### 7. 발행하기

[플러그인 마켓](MARKET_ko.md)의 단계를 따르세요: `turbodl-plugin.json` 추가, 저장소에 `turbodl-plugin` + 카테고리 + 능력 태그 부여, 아티팩트 발행, release 커팅.

---

## Shim 어댑터

"shim"은 외부 다운로더/SDK를 감싸서, TurboDL이 그 시스템에 대해 전혀 알지 못한 채 `LinkParser` + `DownloadBackend`를 통해 노출합니다. `demo/.../Example3ShimAdapter.kt`의 템플릿에서 시작하세요 — 플레이스홀더 `ExternalDownloader`를 실제 SDK로 교체하면 됩니다.

---

## JS에 대한 참고

JavaScript 런타임은 **예약된 미래** 기능입니다. `PluginLoaderProvider`를 구현하는 별도의 플러그인으로 제공될 예정이며, core와 Kotlin 로더는 JS를 인지하지 않고 커널에 JS 엔진이 포함되지도 않습니다. 매니페스트는 그날을 위해 `entry.language: "js"`를 예약해 둡니다.
