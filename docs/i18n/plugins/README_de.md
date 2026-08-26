# TurboDL Plugins

> Deutsche Übersetzung · Original: [English](../../../README.md) · [Deutsch Übersicht](../README_de.md)

TurboDL ist eine eigenständige Download-Engine **und** eine optionale Plugin-Plattform. Dieses Verzeichnis ist die Heimat für alles, was ein Plugin-Autor benötigt:

- **[Plugin-Autoren-Guide](#ein-plugin-schreiben)** — diese Datei: Konzepte + eine Schritt-für-Schritt-Anleitung.
- **[Entwicklungs-Konvention](CONVENTION_de.md)** — das offizielle Kompatibilitäts-Regelwerk (stabile API, Versionierung, Namensgebung, Sicherheit). Vor der Veröffentlichung lesen.
- **[Plugin-Markt](MARKET_de.md)** — wie man Plugins über GitHub-Themen (Topics) + ein `turbodl-plugin.json`-Manifest ([Schema](../../plugins/turbodl-plugin.schema.json)) veröffentlicht/entdeckt.

Ausführbare Beispiele liegen im Modul [`demo`](../../../demo):
`./gradlew :demo:run --args="1"` (Kotlin-Plugin), `"2"` (Bootstrap), `"3"` (Shim-Adapter).

---

## Philosophie

Drei Ideen prägen das Design:

1. **Core funktioniert eigenständig.** `turbodl-core` ist eine vollständige, eigenständige Multi-Threading-Download-Engine ohne jede Plugin-Abhängigkeit. Plugins sind streng additiv; wenn du nie eines lädst, ändert sich nichts.

2. **Alles ist ein Plugin; der Kernel ist nur Mechanismus.** Der Runtime-Kernel (`turbo-plugin-runtime`) weiß nichts über HTTP, HLS oder irgendein Protokoll. Er stellt nur Mechanismus bereit: Lifecycle, Aufräumen (Disposer), eine Service-Registry mit Abhängigkeitsauflösung, einen typsicheren Event-Bus, eine Erweiterungspunkt-Registry, einen Versions-Handshake und Diagnose. Der Kotlin-Loader, das HTTP-Backend, das HLS-Backend — sie alle sind gewöhnliche Plugins. Selbst eine künftige JS-Runtime wäre nur ein Plugin, das `PluginLoaderProvider` implementiert.

3. **Hybrid A+B.** (A) Core bringt ein eingebautes HTTP-Backend mit, sodass es sofort einsatzbereit ist. (B) Ein Plugin-Backend kann das eingebaute überschreiben oder über die Erweiterungs-Registry neue Protokolle hinzufügen — ohne dass core jemals vom Runtime abhängt. Die Abhängigkeitsrichtung ist strikt: `runtime → core`, niemals umgekehrt.

Das Ergebnis: eine kleine, stabile Vertragsfläche, gegen die Dritte entwickeln können, mit einer Kompatibilitäts-Richtlinie (der [Konvention](CONVENTION_de.md)), sodass ein künftiges, inkompatibles Core-Release laut scheitert, statt Plugins stillschweigend zu beschädigen.

---

## Die Bausteine

| Konzept | Typ | Wofür |
|---|---|---|
| Plugin | `Plugin` | Die Einheit, die du implementierst. Hat eine `id`, eine `requiredApiVersion`, optionale `dependencies`. |
| Kontext | `PluginContext` | An `onLoad` übergeben; womit du Services/Events/Erweiterungen registrierst. Alle Registrierungen werden beim Entladen automatisch aufgeräumt. |
| Aufräumen | `Disposer` | LIFO-Aufräumkette; beim Entladen mit Isolation pro Callback abgearbeitet. |
| Services | `ServiceRegistry` | Leichtgewichtige id→Instanz-Registry + Abhängigkeits-Gating. |
| Events | `EventBus` | `TurboEvent`s beobachten und `DownloadRequest`s zum Einreichzeitpunkt abfangen. |
| Erweiterungspunkte | `ExtensionPointKey<T>` | Typisierte Verträge, die Plugins implementieren; Konsumenten fragen nach Key/Priorität ab. |
| Version | `ApiVersion` | Der Handshake: Der Host muss die `requiredApiVersion` eines Plugins erfüllen. |

**Eingebaute Erweiterungspunkte** (`dev.turbodl.plugin.runtime.ext.ExtensionPoints`):
- `DOWNLOAD_BACKEND` — ein Protokoll hinzufügen/überschreiben (`DownloadBackend`)
- `LINK_PARSER` — einen rohen Link in `DownloadRequest`s umwandeln (`LinkParser`)
- `TASK_PRE_HOOK` — eine Anfrage vor dem Einreichen umschreiben (`TaskPreHook`)
- `TASK_POST_HOOK` — nach Abschluss einer Aufgabe reagieren (`TaskPostHook`)

Der einzige Erweiterungspunkt, den der **Kernel** namentlich kennt, ist `PluginLoaderProvider` (Loader).

---

## Ein Plugin schreiben

### 1. Von den Verträgen abhängen

```kotlin
dependencies {
    implementation("dev.turbodl:turbodl-core:<version>")
    implementation("dev.turbodl:turbo-plugin-runtime:<version>")
}
```

### 2. `Plugin` implementieren

```kotlin
import dev.turbodl.core.ApiVersion
import dev.turbodl.plugin.runtime.Plugin
import dev.turbodl.plugin.runtime.PluginContext

class MyPlugin : Plugin {
    override val id = "adapter.acme"                 // eindeutig, punktgetrennt, herstellerqualifiziert
    override val name = "ACME Adapter"
    override val requiredApiVersion = ApiVersion(1, 0, 0)   // niedrigste API, die du tatsächlich nutzt

    override fun onLoad(context: PluginContext) {
        // hier Services / Events / Erweiterungen registrieren — beim Entladen alles automatisch aufgeräumt
    }
}
```

### 3. Fähigkeiten in `onLoad` registrieren

```kotlin
// Ein Service, von dem andere Plugins abhängen können:
context.registerService(id, myService)

// Engine-Events beobachten (beim Entladen automatisch abgemeldet):
context.onEvent { event -> /* ... */ }

// Anfragen vor der Ausführung umschreiben:
context.interceptRequest { req -> req.copy(headers = req.headers + ("X-Trace" to "1")) }

// Eine Erweiterungspunkt-Implementierung bereitstellen (höhere Priorität gewinnt für „die“ Impl):
context.registerExtension(ExtensionPoints.DOWNLOAD_BACKEND, myBackend, priority = 100)

// Alles andere, das aufgeräumt werden muss:
context.disposer.register { myThreadPool.shutdown() }
```

Alles, was über `context` registriert wird, wird automatisch abgebaut, wenn das Plugin deinstalliert wird. Wenn `onLoad` eine Ausnahme wirft, werden Teilregistrierungen für dich zurückgerollt.

### 4. Ein `DownloadBackend` schreiben

Ein Backend besitzt das Protokoll; die Engine besitzt Zustand, Events, Zusammenführung und Integrität. Regeln (vollständige Liste in [Konvention §7](CONVENTION_de.md)):

- `supports(request)` — billig, nebenwirkungsfrei, konservativ.
- `context.isActive()` und Coroutine-Abbruch respektieren.
- `context.reportTotalSize(total)`, sobald bekannt (`-1` bei unbekannt), `context.reportProgress(...)` unterwegs, `context.throttle(bytes)`, um das globale Tempolimit einzuhalten.
- Teile in `context.workDir` schreiben, sie **in Reihenfolge** als `BackendResult.orderedParts` zurückgeben.
- Bei nicht behebbarer/außerhalb des Geltungsbereichs liegender Eingabe **eine Ausnahme werfen** — niemals eine beschädigte Datei ausgeben.
- Brauchst du die HTTP-Transportrichtlinie von core (Proxy/DNS/TLS)? Nutze `TurboHttpClients.create(config)`; fasse niemals core-Interna an.

Siehe `turbo-plugin-hls` für ein vollständiges, nicht triviales Backend (Playlist-Parsing, AES-128, Byte-Range, explizite Ablehnung nicht unterstützter Konstrukte).

### 5. Installieren

```kotlin
val host = PluginHost()
host.install(MyPlugin())

// Downloads über Plugin-Backends routen (fällt auf eingebautes HTTP zurück, wenn keins passt):
val client = TurboClient(config)
client.backendResolver = BackendRegistry(host.extensions)
```

Oder die Bootstrap-Bequemlichkeit nutzen:

```kotlin
val boot = TurboBootstrap.create(extraPlugins = listOf(MyPlugin()))
val id = boot.client.submit(DownloadRequest(url, dest))
```

### 6. Mit Diagnose verifizieren

```kotlin
println(host.diagnostics().render())
// Listet Plugins + Zustände (LOADED / WAITING / FAILED / INCOMPATIBLE / UNLOADED),
// Erweiterungspunkte, Services, Listener-Anzahl.
```

Wenn dein Plugin `INCOMPATIBLE` anzeigt, erfüllt die laufende TurboDL-API deine `requiredApiVersion` nicht — prüfe den Handshake ([Konvention §2](CONVENTION_de.md)).

### 7. Veröffentlichen

Folge den Schritten des [Plugin-Markts](MARKET_de.md): `turbodl-plugin.json` hinzufügen, das Repository mit `turbodl-plugin` + einer Kategorie + Fähigkeiten taggen, ein Artefakt veröffentlichen, ein Release schneiden.

---

## Shim-Adapter

Ein „Shim“ umschließt einen externen Downloader/ein externes SDK und stellt es TurboDL über `LinkParser` + `DownloadBackend` bereit, ohne dass TurboDL etwas über dieses System weiß. Beginne mit der Vorlage in `demo/.../Example3ShimAdapter.kt` — ersetze den Platzhalter `ExternalDownloader` durch ein echtes SDK.

---

## Eine Anmerkung zu JS

Eine JavaScript-Runtime ist eine **reservierte, künftige** Fähigkeit. Sie würde als separates Plugin ausgeliefert, das `PluginLoaderProvider` implementiert; der Core und der Kotlin-Loader bleiben JS-unkundig, und es wird keine JS-Engine in den Kernel gezogen. Das Manifest reserviert `entry.language: "js"` für jenen Tag.
