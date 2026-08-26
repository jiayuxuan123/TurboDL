# TurboDL Plugin-Ökosystem-Entwicklungskonvention

**Status:** Offiziell · **Version:** 1.0 · **Gilt für TurboDL-API:** `1.x`

Dies ist der offizielle Vertrag zwischen dem TurboDL-Projekt und Plugin-Autoren. Er existiert, damit das Ökosystem wachsen kann, ohne dass ein kompatibilitätsbrechendes Core-Update stillschweigend jedes Plugin zerstört. Er wird vom TurboDL-Projekt selbst gepflegt (nicht an Dritte delegiert), damit Kompatibilitätsregeln im gesamten Ökosystem konsistent bleiben.

Wenn du nur ein Plugin *schreiben* möchtest, beginne mit [docs/plugins/README.md](README_de.md); dieses Dokument ist das *Regelwerk*, auf das sich jener Guide stützt.

---

## 1. Geltungsbereich und Begriffe

- **Core** — das Modul `turbodl-core`: die eigenständige Download-Engine und die öffentlichen Datenmodelle/Verträge, die Plugins verwenden dürfen.
- **Runtime-Kernel** — das Modul `turbo-plugin-runtime`: Lifecycle, Disposer, Service-Registry, Event-Bus, Erweiterungspunkt-Registry, Versions-Handshake. Nur Mechanismus, keine Geschäftslogik.
- **Plugin** — alles, was `dev.turbodl.plugin.runtime.Plugin` implementiert. Loader, Backends, Parser, Hooks und Adapter sind alle Plugins. Nur der Kernel ist kein Plugin.
- **Stabile API** — die in §3 aufgeführten Symbole. Alles andere ist intern und kann sich ohne Vorankündigung ändern.
- **MUST / SHOULD / MAY** folgen RFC 2119.

---

## 2. Versionierungs- und Kompatibilitätsrichtlinie

TurboDL versioniert seine **öffentliche, plugin-gerichtete API** mit semantischer Versionierung, freigelegt als `dev.turbodl.core.ApiVersion.CURRENT`.

- **MAJOR** — erhöht bei jeder kompatibilitätsbrechenden Änderung an einem stabilen API-Symbol (§3). MAJOR-übergreifend gilt immer als inkompatibel.
- **MINOR** — erhöht bei abwärtskompatiblen Ergänzungen (neue Methoden mit Standardwerten, neue Erweiterungspunkte, neue optionale Konfiguration).
- **PATCH** — erhöht bei abwärtskompatiblen Korrekturen.

Vom Kernel erzwungene Handshake-Regel:

```
host.satisfies(required)  ==  (host.major == required.major && host >= required)
```

- Jedes Plugin deklariert `Plugin.requiredApiVersion` (Standard `1.0.0`).
- Der Host lädt ein Plugin nur, wenn `ApiVersion.CURRENT.satisfies(plugin.requiredApiVersion)`.
- Bei Nichtübereinstimmung wird das Plugin als `PluginState.INCOMPATIBLE` markiert, `onLoad` wird **niemals** aufgerufen, und eine Diagnose wird protokolliert. Das ist beabsichtigt: Ein brechendes Release scheitert laut zur Ladezeit, statt Verhalten zu beschädigen.

Plugin-Autoren:
- MÜSSEN `requiredApiVersion` auf die niedrigste Version setzen, deren API sie tatsächlich verwenden.
- SOLLTEN für jedes von ihnen unterstützte Core-MAJOR ein neues Plugin-Release veröffentlichen.
- DÜRFEN NICHT auf interne (nicht-§3) Klassen setzen, um den Handshake zu umgehen.

### TurboDLs Zusagen an Plugin-Autoren

Innerhalb einer einzelnen MAJOR-Linie wird das Projekt:
- KEIN stabiles API-Symbol entfernen oder dessen Signatur ändern (MUST NOT).
- die dokumentierte Semantik eines Erweiterungspunkts NICHT so ändern, dass bestehende Implementierungen brechen (MUST NOT).
- neue stabile API hinzufügen DÜRFEN (MINOR) — Ergänzungen müssen quell- und binärkompatibel sein (Schnittstellenergänzungen werden mit Standardimplementierungen ausgeliefert).
- jede Änderung im `CHANGELOG` dokumentieren und bei einem MAJOR-Sprung einen Migrationshinweis bereitstellen (MUST).

---

## 3. Die stabile API-Oberfläche (`1.x`)

Nur diese Symbole werden von der Kompatibilitätsrichtlinie abgedeckt. Paket-Präfixe: `dev.turbodl.core.*` (core) und `dev.turbodl.plugin.runtime.*` (Kernel).

**Core-Verträge**
- `ApiVersion` (+ `CURRENT`, `satisfies`, `parse`)
- `DownloadRequest`, `TaskState`, `TaskProgress`, `TurboEvent` (sealed Hierarchie)
- `DownloadBackend`, `BackendContext`, `BackendResult`, `BackendResolver`
- `TurboConfig`, `ProxyMode`, `ProxyType`, `DnsMode`
- `TurboClient`-öffentliche Methoden: `submit`, `await`, `pause`, `resume`, `cancel`, `updateConfig`, `shutdown`, `events`, `progress`, `backendResolver`
- `TurboBackends.builtinHttp`, `TurboHttpClients.create`

**Runtime-Kernel-Verträge**
- `Plugin`, `PluginContext` (+ `service` reified Helfer)
- `PluginHost`-öffentliche Methoden: `install`, `installAll`, `uninstall`, `shutdown`, `publishEvent`, `applyRequestInterceptors`, `diagnostics` sowie die Accessoren `services`/`extensions`/`eventBus`
- `Disposer`, `PluginState`, `PluginInfo`, `DiagnosticsSnapshot`
- `ExtensionPointKey`, `ExtensionRegistration`, `ExtensionRegistry`, `ServiceRegistry`, `EventBus`
- `PluginLoaderProvider` (+ `KEY`), `PluginSource`
- `dev.turbodl.plugin.runtime.ext.*`: `ExtensionPoints`, `LinkParser`, `TaskPreHook`, `TaskPostHook`, `BackendRegistry`

**Ausdrücklich NICHT stabil** (intern; nicht darauf verlassen): `SegmentDownloader`, `SegmentScheduler`, `BuiltinHttpBackend`, `HttpClientFactory`, `PartMerger`, `SpeedLimiter` und alles, was oben nicht aufgeführt ist.

---

## 4. Plugin-Identität und Namensgebung

- `Plugin.id` MUSS global eindeutig, über Releases hinweg stabil, kleingeschrieben, punktgetrennt sein: `<category>.<name>`, z. B. `backend.http`, `backend.hls`, `loader.kotlin`, `adapter.cordis`.
- Reservierte Kategorie-Präfixe im Besitz des offiziellen Projekts: `backend.`, `loader.`, `core.`. Drittanbieter-Plugins SOLLTEN einen herstellerqualifizierten Namen verwenden, z. B. `adapter.acme-cloud`, `backend.acme-ftp` (ein neues Protokoll-Backend darf `backend.` verwenden, SOLLTE aber herstellerqualifiziert sein).
- Über `registerService` registrierte Service-IDs folgen denselben Regeln und SOLLTEN für den Primärdienst eines Plugins mit der Plugin-ID übereinstimmen.
- Das Ändern einer veröffentlichten `Plugin.id` ist für jeden, der davon abhängt, eine kompatibilitätsbrechende Änderung; behandle es als MAJOR-Ereignis für dein Plugin.

---

## 5. Lifecycle-Vertrag

- `onLoad` läuft genau einmal, erst nachdem (a) der Versions-Handshake bestanden wurde und (b) alle deklarierten `dependencies` (Service-IDs) vorhanden sind.
- Ein Plugin MUSS für jede Nebenwirkung ein passendes Aufräumen registrieren. In der Praxis: Bevorzuge die `PluginContext`-Methoden — Service-/Event-/Erweiterungsregistrierungen werden automatisch mit dem Disposer verdrahtet — und verwende `context.disposer.register { ... }` für alles andere (Threads, Sockets, temporäre Dateien, externe SDK-Handles).
- Wenn `onLoad` eine Ausnahme wirft, rollt der Host die Disposer-Kette des Plugins zurück und markiert es als `FAILED`. Teil-Nebenwirkungen MÜSSEN sicher rückrollbar sein.
- `onUnload` DARF zusätzliche Arbeit leisten, DARF aber NICHT annehmen, dass der Disposer bereits gelaufen ist (er läuft danach).
- Plugins MÜSSEN jederzeit sicher `uninstall`-bar sein: Nach dem Entladen dürfen keine Services, Listener oder Erweiterungsimplementierungen des Plugins erreichbar bleiben.
- `onLoad`/`onUnload` MÜSSEN zügig zurückkehren. Lange oder blockierende Arbeit gehört in die eigene Coroutine/den eigenen Thread des Plugins, abgebaut über einen Disposer.

---

## 6. Erweiterungspunkte

- Registriere Implementierungen über `PluginContext.registerExtension(key, impl, priority)`.
- Höhere `priority` gewinnt dort, wo ein Konsument „die“ Implementierung auswählt (z. B. Backend-Routing). Offizielle Basis-Plugins verwenden Priorität `0`; ein Plugin, das eine Basisfähigkeit überschreiben will, verwendet einen höheren Wert (HLS verwendet `100`; Adapter üblicherweise `200`). Wähle die niedrigste Priorität, die deine Absicht erfüllt.
- `DownloadBackend.supports` MUSS billig, nebenwirkungsfrei und konservativ sein — gib `true` nur für Anfragen zurück, die du tatsächlich verarbeiten kannst, damit das Routing vorhersehbar bleibt und nicht passende Anfragen auf das eingebaute HTTP-Backend durchfallen.
- `LinkParser.parse` MUSS für Eingaben, die es nicht verarbeitet, `null` zurückgeben (keine Ausnahme werfen), damit der Router den nächsten Parser versuchen kann.
- Hook-/Parser-/Backend-Implementierungen MÜSSEN nebenläufige Aufrufe vertragen.

---

## 7. Regeln für das Schreiben von Backends

Ein `DownloadBackend` besitzt nur die Protokollschicht; die Engine besitzt Zustand, Events, Zusammenführung und Integrität. Ein Backend:

- MUSS kooperativen Abbruch respektieren: `BackendContext.isActive()` prüfen und Coroutine-Abbruch respektieren; bei Pause/Abbruch zügig stoppen.
- MUSS die Größe über `reportTotalSize` melden, sobald bekannt (`-1` für unbekannt/Streaming), und den Fortschritt über `reportProgress`.
- SOLLTE Byte-Schreibvorgänge über `BackendContext.throttle(bytes)` drosseln, damit das globale Tempolimit aufgabenübergreifend eingehalten wird.
- MUSS Ausgaben in `BackendContext.workDir` schreiben und sie als geordnete `BackendResult.orderedParts` zurückgeben; die Engine verkettet sie genau in dieser Reihenfolge.
- MUSS bei nicht behebbaren Fehlern mit einer Ausnahme scheitern, statt eine abgeschnittene oder beschädigte Ausgabe zu erzeugen. Wenn ein Format außerhalb des Geltungsbereichs liegt, ausdrücklich scheitern (siehe, wie das HLS-Backend live/DRM/fMP4 ablehnt, statt eine kaputte Datei auszugeben).
- DARF NICHT in Core-Interna greifen; nur §3-Symbole verwenden. Wenn du die HTTP-Transportrichtlinie von core (Proxy/DNS/TLS) brauchst, hole einen Client über `TurboHttpClients.create(config)`.

---

## 8. Events und Services

- Event-Listener und Request-Interceptoren DÜRFEN KEINE Ausnahme werfen; der Bus isoliert und protokolliert Fehler, aber ein wohlerzogenes Plugin behandelt seine eigenen Fehler.
- Interceptoren MÜSSEN weitgehend rein und schnell sein; die Eingabe unverändert zurückzugeben ist ein No-op. Sie laufen auf dem Einreichpfad.
- Services sind eine leichtgewichtige id→Instanz-Registry, kein IoC-Container. Hänge von einem Service ab, indem du seine ID in `dependencies` aufführst; schlage einen mit `context.service<T>(id)` nach.
- Blockiere den Event-Bus nicht; lagere schwere Arbeit aus.

---

## 9. Sicherheit

- Behandle alle Playlist-/Manifest-/Redirect-/Link-Inhalte als nicht vertrauenswürdige Eingabe. Validiere Schemata; lehne nicht-`http(s)`-URIs ab, sofern dein Protokoll nicht ausdrücklich anderes verlangt (das HLS-Backend lehnt `file://` ab, um SSRF/lokale Dateilesevorgänge zu verhindern).
- Exfiltriere keine Benutzerdaten oder Anmeldeinformationen. Ein Plugin DARF NICHT Request-URLs, Header, Cookies oder heruntergeladene Inhalte an Drittanbieter-Endpunkte übertragen, es sei denn, das ist der ausdrückliche, dokumentierte Zweck des Plugins.
- Fixiere Abhängigkeitsversionen; vermeide es, große oder ungeprüfte transitive Abhängigkeiten in den Runtime zu ziehen.
- Ein Plugin DARF TLS NICHT schwächen (`trustAllCerts`), außer wenn der Benutzer sich per Konfiguration ausdrücklich dafür entscheidet; niemals fest eingeschaltet codieren.
- Behandle Geheimnisse (Schlüssel, Token) per Referenz; protokolliere niemals deren Werte.

---

## 10. Verpackung und Verteilung

- Ein Plugin-Repository SOLLTE eine primäre Fähigkeit ausliefern. Stelle ein `turbodl-plugin.json`-Manifest bereit (siehe Plugin-Markt-Dokument) und tagge das Repo mit den passenden GitHub-Themen.
- Deklariere sowohl im Manifest als auch in den Release-Notizen, welche TurboDL-MAJOR-Linie das Release anvisiert.
- Stelle ein ausführbares Beispiel oder einen Test bereit, der beweist, dass das Plugin lädt und seine Fähigkeit ausführt.
- Lizenziere dein Plugin, wie du willst. Unter TurboDLs Zusatzbedingungen macht die Interaktion über die öffentliche API/Erweiterungspunkte dein Plugin nicht zu einem abgeleiteten Werk von TurboDL.

---

## 11. Disziplin bei kompatibilitätsbrechenden Änderungen (für Plugin-Autoren)

Wende auf dein eigenes Plugin dieselbe Disziplin an, die TurboDL auf den Core anwendet:
- Erhöhe die MAJOR deines Plugins, wenn du seine `Plugin.id` änderst, einen von ihm veröffentlichten Service entfernst oder das beobachtbare Verhalten einer Erweiterung änderst.
- Halte `requiredApiVersion` korrekt.
- Dokumentiere Migrationsschritte im Changelog deines Plugins.

---

## 12. Änderung dieser Konvention

Dieses Dokument ist versioniert. Abwärtskompatible Klarstellungen erhöhen seine MINOR; eine Änderung, die zuvor konforme Plugins ungültig macht, erhöht seine MAJOR und MUSS mit dem entsprechenden Core-MAJOR und einem Migrationshinweis ausgeliefert werden. Vorschläge laufen über das TurboDL-Repository.
