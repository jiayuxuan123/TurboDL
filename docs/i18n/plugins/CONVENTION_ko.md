# TurboDL 플러그인 생태계 개발 협정

**상태:** 공식 · **버전:** 1.0 · **적용 TurboDL API:** `1.x`

이것은 TurboDL 프로젝트와 플러그인 작성자 사이의 공식 계약입니다. 이것은 호환성을 깨는 core 업데이트가 모든 플러그인을 조용히 망가뜨리는 일 없이 생태계가 성장할 수 있도록 존재합니다. 호환성 규칙이 생태계 전반에서 일관되게 유지되도록, TurboDL 프로젝트 자체가 유지관리합니다(서드파티에 위임하지 않음).

플러그인을 *작성*하기만 원한다면 [docs/plugins/README.md](README_ko.md)에서 시작하세요; 이 문서는 그 가이드가 의존하는 *규칙집*입니다.

---

## 1. 범위와 용어

- **Core** — `turbodl-core` 모듈: 독립 실행형 다운로드 엔진과 플러그인이 사용할 수 있는 공개 데이터 모델/계약.
- **런타임 커널(Runtime kernel)** — `turbo-plugin-runtime` 모듈: 생명주기, disposer, 서비스 레지스트리, 이벤트 버스, 확장 포인트 레지스트리, 버전 핸드셰이크. 메커니즘만, 비즈니스 로직 없음.
- **플러그인(Plugin)** — `dev.turbodl.plugin.runtime.Plugin`을 구현하는 모든 것. 로더, 백엔드, 파서, 훅, 어댑터는 모두 플러그인입니다. 커널만 플러그인이 아닙니다.
- **안정 API** — §3에 나열된 심볼. 그 외 모든 것은 내부이며 예고 없이 바뀔 수 있습니다.
- **MUST / SHOULD / MAY** 는 RFC 2119를 따릅니다.

---

## 2. 버전 관리와 호환성 정책

TurboDL은 **공개된, 플러그인 대상 API**를 시맨틱 버저닝으로 버전 관리하며, `dev.turbodl.core.ApiVersion.CURRENT`로 노출합니다.

- **MAJOR(메이저)** — 안정 API 심볼(§3)의 호환성을 깨는 변경 시 증가. 크로스 MAJOR는 항상 비호환으로 간주됩니다.
- **MINOR(마이너)** — 하위 호환 추가 시 증가(기본값이 있는 새 메서드, 새 확장 포인트, 새 선택적 설정).
- **PATCH(패치)** — 하위 호환 수정 시 증가.

커널이 강제하는 핸드셰이크 규칙:

```
host.satisfies(required)  ==  (host.major == required.major && host >= required)
```

- 모든 플러그인은 `Plugin.requiredApiVersion`(기본값 `1.0.0`)을 선언합니다.
- 호스트는 `ApiVersion.CURRENT.satisfies(plugin.requiredApiVersion)`일 때만 플러그인을 로드합니다.
- 불일치 시 플러그인은 `PluginState.INCOMPATIBLE`로 표시되고, `onLoad`는 **절대** 호출되지 않으며, 진단이 로그로 남습니다. 이는 의도된 설계입니다: 호환성을 깨는 릴리스는 동작을 손상시키는 대신 로드 시점에 크게 실패합니다.

플러그인 작성자:
- `requiredApiVersion`을 실제로 사용하는 API의 최저 버전으로 설정해야 합니다(MUST).
- 지원하는 각 core MAJOR마다 새 플러그인 릴리스를 발행해야 합니다(SHOULD).
- 핸드셰이크를 회피하기 위해 내부(비 §3) 클래스에 의존하면 안 됩니다(MUST NOT).

### 플러그인 작성자에 대한 TurboDL의 약속

단일 MAJOR 라인 내에서 프로젝트는:
- 어떤 안정 API 심볼의 시그니처도 제거하거나 변경하면 안 됩니다(MUST NOT).
- 기존 구현을 깨는 방식으로 확장 포인트의 명시된 의미를 변경하면 안 됩니다(MUST NOT).
- 새 안정 API를 추가할 수 있습니다(MINOR) — 추가는 소스 및 바이너리 호환이어야 합니다(인터페이스 추가는 기본 구현과 함께 제공).
- 모든 변경을 `CHANGELOG`에 기록하고, MAJOR 증가 시 마이그레이션 노트를 제공해야 합니다(MUST).

---

## 3. 안정 API 표면(`1.x`)

이 심볼들만 호환성 정책의 대상입니다. 패키지 접두사: `dev.turbodl.core.*`(core)와 `dev.turbodl.plugin.runtime.*`(커널).

**Core 계약**
- `ApiVersion`(+ `CURRENT`, `satisfies`, `parse`)
- `DownloadRequest`, `TaskState`, `TaskProgress`, `TurboEvent`(sealed 계층)
- `DownloadBackend`, `BackendContext`, `BackendResult`, `BackendResolver`
- `TurboConfig`, `ProxyMode`, `ProxyType`, `DnsMode`
- `TurboClient` 공개 메서드: `submit`, `await`, `pause`, `resume`, `cancel`, `updateConfig`, `shutdown`, `events`, `progress`, `backendResolver`
- `TurboBackends.builtinHttp`, `TurboHttpClients.create`

**런타임 커널 계약**
- `Plugin`, `PluginContext`(+ `service` reified 헬퍼)
- `PluginHost` 공개 메서드: `install`, `installAll`, `uninstall`, `shutdown`, `publishEvent`, `applyRequestInterceptors`, `diagnostics`, 그리고 `services`/`extensions`/`eventBus` 접근자
- `Disposer`, `PluginState`, `PluginInfo`, `DiagnosticsSnapshot`
- `ExtensionPointKey`, `ExtensionRegistration`, `ExtensionRegistry`, `ServiceRegistry`, `EventBus`
- `PluginLoaderProvider`(+ `KEY`), `PluginSource`
- `dev.turbodl.plugin.runtime.ext.*`: `ExtensionPoints`, `LinkParser`, `TaskPreHook`, `TaskPostHook`, `BackendRegistry`

**명시적으로 불안정**(내부; 의존 금지): `SegmentDownloader`, `SegmentScheduler`, `BuiltinHttpBackend`, `HttpClientFactory`, `PartMerger`, `SpeedLimiter`, 그리고 위에 나열되지 않은 모든 것.

---

## 4. 플러그인 신원과 명명

- `Plugin.id`는 전역적으로 고유하고, 릴리스 전반에서 안정적이며, 소문자, 점 구분이어야 합니다(MUST): `<category>.<name>`, 예: `backend.http`, `backend.hls`, `loader.kotlin`, `adapter.cordis`.
- 공식 프로젝트가 소유한 예약 카테고리 접두사: `backend.`, `loader.`, `core.`. 서드파티 플러그인은 벤더 한정 이름을 사용해야 합니다(SHOULD), 예: `adapter.acme-cloud`, `backend.acme-ftp`(새 프로토콜 백엔드는 `backend.`를 사용할 수 있으나 벤더 한정 SHOULD).
- `registerService`로 등록한 서비스 id는 동일한 규칙을 따르며, 플러그인의 주요 서비스에는 플러그인 id와 일치해야 합니다(SHOULD).
- 발행된 `Plugin.id`를 변경하는 것은 그것에 의존하는 모든 이에게 호환성을 깨는 변경입니다; 이를 당신 플러그인의 MAJOR 이벤트로 취급하세요.

---

## 5. 생명주기 계약

- `onLoad`는 정확히 한 번, (a) 버전 핸드셰이크가 통과하고 (b) 선언된 모든 `dependencies`(서비스 id)가 존재한 뒤에만 실행됩니다.
- 플러그인은 모든 부작용에 대해 대응하는 정리를 등록해야 합니다(MUST). 실무적으로는 `PluginContext` 메서드를 선호하세요 — 서비스/이벤트/확장 등록은 disposer에 자동 연결됩니다 — 그 외의 모든 것(스레드, 소켓, 임시 파일, 외부 SDK 핸들)에는 `context.disposer.register { ... }`를 사용하세요.
- `onLoad`가 예외를 던지면 호스트는 플러그인의 disposer 체인을 롤백하고 `FAILED`로 표시합니다. 부분 부작용은 안전하게 롤백 가능해야 합니다(MUST).
- `onUnload`는 추가 작업을 할 수 있으나(MAY), disposer가 이미 실행되었다고 가정해서는 안 됩니다(MUST NOT)(그것은 이후에 실행됨).
- 플러그인은 언제든 안전하게 `uninstall`될 수 있어야 합니다(MUST): 언로드 후에는 플러그인의 서비스, 리스너, 확장 구현 중 어떤 것도 도달 가능한 상태로 남아서는 안 됩니다.
- `onLoad`/`onUnload`는 신속히 반환해야 합니다(MUST). 길거나 블로킹하는 작업은 플러그인 자체의 코루틴/스레드에서 수행하고 disposer로 해제하세요.

---

## 6. 확장 포인트

- `PluginContext.registerExtension(key, impl, priority)`로 구현을 등록합니다.
- 소비자가 "그" 구현을 선택하는 곳(예: 백엔드 라우팅)에서는 더 높은 `priority`가 이깁니다. 공식 기본 플러그인은 우선순위 `0`을 사용하고; 기본 기능을 덮어쓰려는 플러그인은 더 높은 값을 사용합니다(HLS는 `100`; 어댑터는 보통 `200`). 의도를 달성하는 가장 낮은 우선순위를 선택하세요.
- `DownloadBackend.supports`는 저렴하고, 부작용이 없으며, 보수적이어야 합니다(MUST) — 실제로 처리할 수 있는 요청에만 `true`를 반환하여 라우팅이 예측 가능하도록 하고, 일치하지 않는 요청은 내장 HTTP 백엔드로 떨어지게 하세요.
- `LinkParser.parse`는 처리하지 않는 입력에 대해 `null`을 반환해야 합니다(예외를 던지지 않음)(MUST), 그래야 라우터가 다음 파서를 시도할 수 있습니다.
- 훅/파서/백엔드 구현은 동시 호출을 견뎌야 합니다(MUST).

---

## 7. 백엔드 작성 규칙

`DownloadBackend`는 프로토콜 계층만 소유하고; 엔진은 상태, 이벤트, 병합, 무결성을 소유합니다. 백엔드는:

- 협력적 취소를 존중해야 합니다(MUST): `BackendContext.isActive()`를 확인하고 코루틴 취소를 존중하며; 일시정지/취소 시 신속히 멈춥니다.
- 알게 되면 `reportTotalSize`로 크기를 보고해야 합니다(MUST)(알 수 없음/스트리밍 시 `-1` 사용), 그리고 `reportProgress`로 진행을 보고합니다.
- `BackendContext.throttle(bytes)`를 통해 바이트 쓰기를 제한하여 전역 속도 제한이 작업 전반에서 지켜지도록 해야 합니다(SHOULD).
- 출력을 `BackendContext.workDir`에 쓰고 정렬된 `BackendResult.orderedParts`로 반환해야 합니다(MUST); 엔진은 정확히 그 순서로 연결합니다.
- 복구 불가능한 오류에는 잘리거나 손상된 출력을 만드는 대신 예외로 실패해야 합니다(MUST). 형식이 범위 밖일 때는 명시적으로 실패하세요(HLS 백엔드가 깨진 파일을 내보내는 대신 live/DRM/fMP4를 거부하는 방식 참고).
- core 내부에 접근하면 안 됩니다(MUST NOT); §3 심볼만 사용하세요. core의 HTTP 전송 정책(프록시/DNS/TLS)이 필요하면 `TurboHttpClients.create(config)`로 클라이언트를 얻으세요.

---

## 8. 이벤트와 서비스

- 이벤트 리스너와 요청 인터셉터는 예외를 던지면 안 됩니다(MUST NOT); 버스가 실패를 격리하고 로깅하지만, 행실 좋은 플러그인은 자신의 오류를 처리합니다.
- 인터셉터는 대체로 순수하고 빨라야 합니다(MUST); 입력을 그대로 반환하면 no-op입니다. 제출 경로에서 실행됩니다.
- 서비스는 IoC 컨테이너가 아니라 경량 id→인스턴스 레지스트리입니다. `dependencies`에 그 id를 나열하여 서비스에 의존하고; `context.service<T>(id)`로 조회하세요.
- 이벤트 버스를 블로킹하지 마세요; 무거운 작업은 밖으로 넘기세요.

---

## 9. 보안

- 모든 재생목록/매니페스트/리다이렉트/링크 콘텐츠를 신뢰할 수 없는 입력으로 취급하세요. 스킴을 검증하고; 프로토콜이 명시적으로 달리 요구하지 않는 한 비 `http(s)` URI를 거부하세요(HLS 백엔드는 SSRF/로컬 파일 읽기를 방지하기 위해 `file://`를 거부합니다).
- 사용자 데이터나 자격 증명을 유출하지 마세요. 플러그인은 그것이 명시적이고 문서화된 목적이 아닌 한 요청 URL, 헤더, 쿠키, 다운로드한 콘텐츠를 서드파티 엔드포인트로 전송하면 안 됩니다(MUST NOT).
- 의존성 버전을 고정하세요; 크거나 검증되지 않은 전이 의존성을 런타임으로 끌어들이지 마세요.
- 플러그인은 사용자가 설정을 통해 명시적으로 동의하는 경우를 제외하고 TLS(`trustAllCerts`)를 약화시키면 안 됩니다(MUST NOT); 절대 하드코딩으로 켜지 마세요.
- 비밀(키, 토큰)은 참조로 다루세요; 그 값을 절대 로깅하지 마세요.

---

## 10. 패키징과 배포

- 하나의 플러그인 저장소는 하나의 주요 기능을 제공하는 것이 좋습니다(SHOULD). `turbodl-plugin.json` 매니페스트를 제공하고(플러그인 마켓 문서 참고), 저장소에 적절한 GitHub 토픽을 부여하세요.
- 매니페스트와 릴리스 노트 모두에 해당 릴리스가 대상으로 하는 TurboDL MAJOR 라인을 선언하세요.
- 플러그인이 로드되고 기능을 수행함을 증명하는 실행 가능한 예제나 테스트를 제공하세요.
- 플러그인의 라이선스는 원하는 대로 정하세요. TurboDL의 보충 조항 하에서, 공개 API/확장 포인트를 통해 상호작용하는 것만으로는 당신의 플러그인이 TurboDL의 파생물이 되지 않습니다.

---

## 11. 호환성을 깨는 변경에 대한 규율(플러그인 작성자용)

TurboDL이 core에 적용하는 것과 동일한 규율을 당신의 플러그인에도 적용하세요:
- 당신 플러그인의 `Plugin.id`를 변경하거나, 발행한 서비스를 제거하거나, 확장의 관찰 가능한 동작을 변경할 때 플러그인의 MAJOR를 올리세요.
- `requiredApiVersion`을 정확하게 유지하세요.
- 플러그인의 변경 로그에 마이그레이션 단계를 문서화하세요.

---

## 12. 이 협정의 변경

이 문서는 버전 관리됩니다. 하위 호환 명확화는 MINOR를 올리고; 이전에 준수하던 플러그인을 무효화하는 변경은 MAJOR를 올리며 대응하는 core MAJOR 및 마이그레이션 노트와 함께 배포되어야 합니다(MUST). 제안은 TurboDL 저장소를 통해 진행됩니다.
