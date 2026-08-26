# TurboDL

> Hochleistungsfähige Multi-Thread-Download-Engine-SDK — reines Kotlin/JVM, keine Android-Abhängigkeit, direkt in jede JVM-Anwendung (einschließlich Android) integrierbar.

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](../../LICENSE)

**Sprachen:** [English](../../README.md) · [简体中文](README_zh-CN.md) · [繁體中文](README_zh-TW.md) · [日本語](README_ja.md) · [한국어](README_ko.md) · Deutsch

TurboDL ist ein von Grund auf neu geschriebener Multi-Thread-Download-Kern. Er **übernimmt nur die Architektur- und Algorithmus-Ideen** ausgereifter Download-Manager (aria2, IDM/XDM, axel, Persepolis, Motrix, ab-download-manager), **ohne deren Quellcode zu kopieren**, und wird daher unter der freizügigen **MIT-Lizenz (mit ergänzenden Plugin-Ökosystem-Bedingungen)** veröffentlicht, frei nutzbar in Open-Source- oder kommerziellen Projekten.

## Funktionen

- **Multi-Thread-Segmentdownload**: parallele HTTP-Range-Segmente, Wiederverwendung von Verbindungen (HTTP/2-Multiplexing + keep-alive).
- **Dynamische Segmentierung**: feingranulares Vor-Aufteilen + Work-Stealing, sodass langsame Verbindungen die gesamte Übertragung nicht ausbremsen und der „letztes Segment auf einem einzelnen Thread“-Long-Tail entfällt (IDM/XDM-Idee).
- **Sequenzielle Segmentpriorität**: frühere Segmente werden zuerst geladen, was progressive Vorschau/Wiedergabe ermöglicht (axel/Persepolis-Idee).
- **Robuste Fallbacks & Wiederholungen**:
  - Server unterstützt Range nicht / ignoriert es → automatischer Fallback auf Einzelstrom-Download der ganzen Datei;
  - ein fehlerhaftes/zeitüberschrittenes Segment → **nur dieses Segment wird wiederholt**, die gesamte Aufgabe wird nicht verworfen;
  - prüft, ob die tatsächlich zurückgegebenen Bytes zum angeforderten Range passen (schützt davor, dass Server den Range manipulieren und die ganze Datei zurückgeben).
- **Zurückhaltende Adaptivität**: die Nebenläufigkeit wird **nur** bei 429/503 oder wenn aufeinanderfolgende Fehler einen Schwellwert erreichen gedrosselt; normales Netzwerk-Jitter reduziert die Thread-Anzahl **niemals** (keine AIMD-Jitter-Heuristiken).
- **Fortsetzbare Downloads**: Segmente werden persistiert; Pause/Fortsetzen setzt beim tatsächlichen Fortschritt auf der Festplatte fort.
- **Integritätsprüfung auf Byte-Ebene**: die Gesamtgröße wird nach dem Zusammenführen geprüft, beschädigte Dateien werden abgewiesen.

## Neun Engine-Fähigkeiten

| Fähigkeit | Konfigurationsfeld |
|---|---|
| Globales Tempolimit | `globalSpeedLimitBytesPerSec` (Token-Bucket, 0 = unbegrenzt) |
| Thread-Anzahl (bis 256) | `maxConnectionsPerTask` (1..256) |
| Gleichzeitige Aufgaben | `maxConcurrentTasks` (1..64) |
| Max. Download-Wiederholungen | `maxRetries` (0..50) |
| Dynamische Segmentierung | `dynamicSegmentation` (true/false) |
| Manueller/automatischer Proxy | `proxy = Direct / System / Manual(HTTP,SOCKS,auth) / Pac(url)` |
| DNS-Konfiguration | `dns = System / StaticHosts / DoH(url)` |
| SSL ignorieren | `trustAllCerts` |
| Theming | von der übergeordneten App übernommen (das SDK befasst sich nicht mit UI-Rendering) |

## Schnellstart

```kotlin
import dev.turbodl.core.*
import java.io.File

val client = TurboClient(
    TurboConfig(
        maxConnectionsPerTask = 16,
        globalSpeedLimitBytesPerSec = 0,        // unbegrenzt
        dynamicSegmentation = true,
        proxy = ProxyMode.Direct,
        dns = DnsMode.System,
    )
)

// Eine Aufgabe einreichen
val id = client.submit(
    DownloadRequest(
        url = "https://example.com/big.zip",
        destination = File("big.zip"),
    )
)

// Fortschrittsereignisse beobachten
scope.launch {
    client.events.collect { event ->
        when (event) {
            is TurboEvent.Progress -> println("${event.progress.percent}%  ${event.progress.speedBytesPerSec} B/s")
            is TurboEvent.Completed -> println("fertig: ${event.file}")
            is TurboEvent.Failed -> println("fehlgeschlagen: ${event.reason}")
            else -> {}
        }
    }
}

// Bis zum Abschluss suspendieren
val result = client.await(id)   // Result<File>

// Steuerung
client.pause(id)
client.resume(id)
client.cancel(id, deleteOutput = true)

client.shutdown()
```

## Kommandozeile

```
./gradlew :turbodl-cli:installDist
./turbodl-cli/build/install/turbodl-cli/bin/turbodl <url> [Ausgabe] --threads 16 --limit 0 [--insecure] [--no-dynamic]
```

## Bauen

```
./gradlew build        # kompilieren + Unit-Tests ausführen
```

Unit-Tests verwenden einen eingebetteten HTTP(Range)-Server und decken ab: Byte-Level-Korrektheit bei Multithreading, dynamische Segmentierung, Fallback bei nicht unterstütztem Range, Fallback bei manipuliertem Range, temporäres 503 mit Wiederholung nur des betroffenen Segments und globales Tempolimit.

## Module

- `turbodl-core`: das Download-Engine-SDK (die veröffentlichte Bibliothek, eigenständig nutzbar, ohne Plugin-Framework-Abhängigkeit).
- `turbodl-cli`: Kommandozeilen-Beispiel, das die SDK-Nutzung demonstriert.
- `turbo-plugin-runtime`: **optionaler** Plugin-Runtime-Kernel (Lifecycle / Disposer / Event-Bus / Service-Registry / Erweiterungspunkte / Versions-Handshake / Diagnose). core hängt nicht davon ab; wenn es nicht eingebunden ist, funktioniert core wie gewohnt.
- `turbo-plugin-bootstrap`: **optionales** Bootstrap-Modul für die Ein-Klick-Anbindung von Basis-Plugins (Kotlin-Loader + HTTP-Backend); keine Pflichtabhängigkeit.
- `turbo-plugin-hls`: **optionales** HLS-VOD-Protokoll-Adapter-Plugin — löst Master-/Media-M3U8-Playlisten auf, lädt Segmente parallel herunter (mit Wiederholung pro Segment), entschlüsselt AES-128, beachtet EXT-X-BYTERANGE und gibt geordnete Teile zur Zusammenführung an die Engine zurück. Registriert sich selbst als geroutetes `DownloadBackend`; nicht unterstützte Konstrukte (Live-Streams, DRM/SAMPLE-AES, fMP4/EXT-X-MAP, Diskontinuitäten) schlagen explizit fehl, statt beschädigte Ausgaben zu erzeugen.
- `demo`: Drei ausführbare Beispiele —— Kotlin-natives Plugin / Bootstrap-Nutzung / Shim-Adapter-Vorlage. Ausführen mit `./gradlew :demo:run --args="1"`（oder `2` / `3` / `all`）.

## Plugin-Framework (optional)

TurboDL ist eine eigenständige Engine **und** eine optionale Plugin-Plattform. Drei Ideen prägen das Design:

1. **Core funktioniert eigenständig.** `turbodl-core` ist eine vollständige Multi-Threading-Engine ohne jede Plugin-Abhängigkeit. Plugins sind streng additiv.
2. **Alles ist ein Plugin; der Kernel ist nur Mechanismus.** Der Runtime-Kernel kennt keinerlei Protokoll — er bietet nur Lifecycle, Aufräumen (Disposer), eine Service-Registry, einen typsicheren Event-Bus, eine Erweiterungspunkt-Registry, einen Versions-Handshake und Diagnose. Der Kotlin-Loader, das HTTP-Backend und das HLS-Backend sind alle gewöhnliche Plugins.
3. **Hybrid A+B.** Core bringt ein eingebautes HTTP-Backend mit (A); ein Plugin-Backend kann es überschreiben oder über die Registry weitere Protokolle hinzufügen (B) — ohne dass core jemals vom Runtime abhängt. Die Abhängigkeitsrichtung ist strikt: `runtime → core`, niemals umgekehrt.

Eine versionierte öffentliche API (`ApiVersion`, derzeit `1.0.0`) plus ein Load-time-Handshake stellt sicher, dass eine künftige, inkompatible Core-Version laut scheitert (ein Plugin wird als `INCOMPATIBLE` markiert und nie geladen), statt das Verhalten stillschweigend zu beschädigen.

Plugin-Dokumentation:
- [Plugin-Autoren-Guide](plugins/README_de.md) — wie man ein Plugin baut und integriert.
- [Entwicklungs-Konvention](plugins/CONVENTION_de.md) — das offizielle Kompatibilitäts-Regelwerk (stabile API, Versionierung, Namensgebung, Sicherheit).
- [Plugin-Markt](plugins/MARKET_de.md) — Plugins über GitHub-Themen (Topics) + ein `turbodl-plugin.json`-Manifest veröffentlichen und entdecken.

## Designhinweise & Danksagungen

TurboDLs Design greift die Ideen der folgenden Open-Source-Projekte auf (nur Ideen, **kein Quellcode kopiert**), mit Dank:
[aria2](https://github.com/aria2/aria2), [Xtreme Download Manager](https://github.com/subhra74/xdm), [axel](https://github.com/axel-download-accelerator/axel), [Persepolis](https://github.com/persepolisdm/persepolis), [Motrix](https://github.com/agalwood/Motrix), [ab-download-manager](https://github.com/amir1376/ab-download-manager).

## Lizenz

[MIT License](../../LICENSE), mit „ergänzenden Plugin-Ökosystem-Bedingungen“ (die klarstellen, dass Plugins eigenständige Werke sind, frei lizenziert werden dürfen und nicht allein dadurch als abgeleitete Werke gelten, dass sie über öffentliche APIs / Erweiterungspunkte interagieren).
