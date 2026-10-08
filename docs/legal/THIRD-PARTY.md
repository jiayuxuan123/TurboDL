# Third-party components and acknowledgements

TurboDL's own code is MIT-licensed (see [LICENSE](../../LICENSE)). This file lists **other
people's work**: which third-party libraries are used, under what license, and which projects
supplied ideas.

It exists for a practical reason — when you integrate TurboDL into your own product, you need to
know what comes along with it.

## Runtime dependencies

These ship with your application. All of them are permissively licensed (Apache-2.0 / MIT), so
closed-source commercial integration is unproblematic.

| Component | Version | License | Used for |
|---|---|---|---|
| [Kotlin standard library](https://github.com/JetBrains/kotlin) | 2.0.21 toolchain | Apache-2.0 | Language and standard library |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 1.9.0 | Apache-2.0 | Concurrency and event streams |
| [OkHttp](https://github.com/square/okhttp) | 4.12.0 | Apache-2.0 | HTTP transport, connection reuse |
| [Okio](https://github.com/square/okio) | via OkHttp | Apache-2.0 | OkHttp's I/O layer (transitive) |
| [quickjs-kt](https://github.com/dokar3/quickjs-kt) | 1.0.15 | Apache-2.0 | Kotlin/JVM bindings for the JS engine |
| [QuickJS](https://bellard.org/quickjs/) | bundled with quickjs-kt | MIT | The JS engine itself (Fabrice Bellard) |

Only `turbodl-core` is required. Plugins and the CLI are opt-in:

- `turbo-plugin-js` is the only module that pulls in quickjs-kt and the bundled QuickJS native
  library. **If you do not use the JS loader, none of it is on your classpath.**
- `turbo-plugin-hls` and `turbo-plugin-bootstrap` depend only on core and runtime, adding no
  third-party libraries.

## Build-time tooling

Development and CI only; none of it reaches your artifacts: Gradle, the Kotlin compiler,
and `kotlin-test` / `kotlinx-coroutines-test` (Apache-2.0, tests only).

## Ideas, not code

TurboDL's engine design learned from several mature open-source download managers. Segment
scheduling, connection reuse, rate limiting and retry are well-understood problems, and their
prior art is worth studying. Those projects are:

[aria2](https://github.com/aria2/aria2),
[Xtreme Download Manager](https://github.com/subhra74/xdm),
[axel](https://github.com/axel-download-accelerator/axel),
[Persepolis](https://github.com/persepolisdm/persepolis),
[Motrix](https://github.com/agalwood/Motrix),
[ab-download-manager](https://github.com/amir1376/ab-download-manager).

**No source code was copied from any of them**, so none of their licenses impose obligations here.
This list is an acknowledgement, not a license notice, and it implies no endorsement of TurboDL by
those projects.

The same statement appears as clause 3 of the `LICENSE` supplemental terms; the two are kept in
step.

## If you redistribute

- TurboDL's own code: keep the copyright and license notice from `LICENSE`. That is all MIT asks.
- The runtime dependencies above: keep their respective notices. Gradle's normal dependency
  handling does this for you; if you shade everything into a fat JAR, carry their license files
  along with it.
- Plugins: the ones in this repository are licensed with it. Plugins from elsewhere are their
  authors' business, not this project's.
