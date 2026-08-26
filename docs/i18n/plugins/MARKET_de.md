# TurboDL Plugin-Markt (GitHub-Themen-basiert)

> Deutsche Übersetzung · Original: [English](../../../README.md) · [Deutsch Übersicht](../README_de.md)

TurboDL betreibt keinen zentralen Paketserver. Der Plugin-„Markt“ ist einfach **GitHub-Themen-Tags plus ein maschinenlesbares Manifest**. Jeder kann ein Plugin veröffentlichen, indem er ein Repository pusht, die richtigen Themen hinzufügt und ein `turbodl-plugin.json` in die Repo-Wurzel legt. Jeder kann Plugins mit einer GitHub-Themen-Suche entdecken. Das hält das Ökosystem offen, dezentral und infrastrukturfrei.

Dieses Dokument definiert die Tags, das Manifest und den Veröffentlichungs-/Entdeckungsablauf. Es ergänzt die [Plugin-Ökosystem-Entwicklungskonvention](CONVENTION_de.md) (das Kompatibilitäts-Regelwerk) und den [Plugin-Autoren-Guide](README_de.md).

---

## 1. Plugins entdecken

Alle TurboDL-Plugins tragen das Wurzelthema **`turbodl-plugin`**. Durchsuche sie hier:

```
https://github.com/topics/turbodl-plugin
```

Grenze nach Fähigkeit ein, indem du Themen in der GitHub-Suche kombinierst:

```
topic:turbodl-plugin topic:turbodl-backend      # Protokoll-Backends
topic:turbodl-plugin topic:turbodl-adapter       # Shim-/Service-Adapter
topic:turbodl-plugin topic:turbodl-hls           # HLS-bezogen
```

Die GitHub-API funktioniert ebenfalls:

```
GET https://api.github.com/search/repositories?q=topic:turbodl-plugin+topic:turbodl-backend
```

---

## 2. Themen-Tags (die „Regale“)

Jedes Plugin-Repo MUSS `turbodl-plugin` haben, plus genau ein **Kategorie**-Thema, plus beliebig viele **Fähigkeits**-Themen.

**Wurzel (erforderlich)**
- `turbodl-plugin`

**Kategorie (eine wählen, erforderlich)**
- `turbodl-backend` — fügt ein Download-Protokoll hinzu/überschreibt es (`DownloadBackend`)
- `turbodl-adapter` — überbrückt ein externes System/einen externen Dienst (Shim; meist `LinkParser` + Backend)
- `turbodl-parser` — nur Link-/Manifest-Parser (`LinkParser`)
- `turbodl-hook` — Vor-/Nachbearbeitung von Aufgaben (`TaskPreHook` / `TaskPostHook`)
- `turbodl-loader` — ein Plugin-Loader (`PluginLoaderProvider`, z. B. ein JS-Provider)

**Fähigkeit (optional, beliebig viele)**
- Protokoll/Format: `turbodl-hls`, `turbodl-dash`, `turbodl-ftp`, `turbodl-magnet`, `turbodl-m3u8`
- Verhalten: `turbodl-remux`, `turbodl-checksum`, `turbodl-notify`, `turbodl-unpack`
- Integration: `turbodl-cloud`, `turbodl-drm-free`

Kategorie- und Fähigkeits-Tags machen Markt-Plugins leicht baubar und auffindbar: Du wählst dein Regal, Nutzer filtern darauf.

---

## 3. Das `turbodl-plugin.json`-Manifest

Lege diese Datei in die Repository-Wurzel. Sie ist der einzige maschinenlesbare Deskriptor, den ein Werkzeug (oder ein künftiger offizieller Indexer) liest, um dein Plugin zu verstehen.

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

Feldhinweise:
- `id` MUSS der `Plugin.id` des Plugins entsprechen und den Namensregeln der Konvention (§4) folgen.
- `turbodl.apiMajor` und `requiredApiVersion` MÜSSEN mit dem übereinstimmen, was das Plugin im Code deklariert (`Plugin.requiredApiVersion`). So filtert ein Markt/Werkzeug Plugins, die auf einer gegebenen TurboDL-Version nicht laufen können, **vor** dem Herunterladen aus.
- `category` MUSS eines der Kategorie-Themen sein; `capabilities` SOLLTE die Fähigkeits-Themen des Repos widerspiegeln.
- `entry.language` ist heute `kotlin`. `js` ist für einen künftigen JS-Provider **reserviert**; der Core und der Kotlin-Loader bleiben JS-unkundig.
- `artifact.type` ist `maven` (veröffentlichtes JAR) oder `jar` (direkte Release-Asset-URL in `artifact.url`). Wähle, was deine Verteilung verwendet.

Ein JSON-Schema zur Validierung liegt unter [`turbodl-plugin.schema.json`](../../plugins/turbodl-plugin.schema.json).

---

## 4. Empfohlenes Repository-Layout

```
turbodl-plugin-<name>/
├─ turbodl-plugin.json          # manifest (root)
├─ README.md                    # what it does, install snippet, supported TurboDL MAJOR
├─ LICENSE
├─ src/main/kotlin/...          # the Plugin implementation
└─ src/test/kotlin/...          # a test proving it loads + performs its capability
```

Repository-Beschreibung und README SOLLTEN die unterstützte TurboDL-MAJOR-Linie ausdrücklich angeben (z. B. „TurboDL 1.x“).

---

## 5. Veröffentlichungs-Checkliste

1. Implementiere ein `Plugin` gemäß dem [Autoren-Guide](README_de.md) und der [Konvention](CONVENTION_de.md).
2. Setze `Plugin.requiredApiVersion` auf die niedrigste API, die du tatsächlich verwendest.
3. Füge `turbodl-plugin.json` in die Repo-Wurzel ein; validiere es gegen das Schema.
4. Füge GitHub-Themen hinzu: `turbodl-plugin` + eine Kategorie + Fähigkeiten.
5. Fülle README mit einem Installations-Snippet und der unterstützten TurboDL-MAJOR.
6. Veröffentliche ein Artefakt (Maven-Koordinaten oder ein Release-JAR), das zu `artifact` passt.
7. Tagge ein Release, dessen Version gleich der Manifest-`version` ist.

Das ist der gesamte „Markt“: pushen, taggen, fertig. Kein Türsteher, kein Server.

---

## 6. Ein Plugin installieren (Konsumentenseite)

1. Füge das Plugin-Artefakt zu deinem Build hinzu (Maven-Koordinaten aus dem Manifest), neben `turbodl-core` und `turbo-plugin-runtime`.
2. Installiere es in deinen Host:

```kotlin
val host = PluginHost()
host.install(HlsPlugin())                 // or the plugin's documented entry class
// If you use the bootstrap convenience:
val boot = TurboBootstrap.create(extraPlugins = listOf(HlsPlugin()))
```

3. Der Versions-Handshake läuft automatisch. Wenn das Plugin eine neuere API als dein TurboDL benötigt, wird es als `INCOMPATIBLE` markiert und niemals geladen — prüfe `host.diagnostics().render()`.

---

## 7. Vertrauen und Sicherheit

Es gibt keine zentrale Prüfung, behandle Drittanbieter-Plugins daher wie jede Abhängigkeit:
- Lies den Quellcode; bevorzuge Plugins mit Tests und einer klaren Lizenz.
- Prüfe vor der Installation, dass die Manifest-`apiMajor` zu deinem TurboDL passt.
- [Konvention §9](CONVENTION_de.md) listet die Sicherheitsregeln auf, die Plugins befolgen sollen (Validierung nicht vertrauenswürdiger Eingaben, keine Datenexfiltration, keine stille TLS-Schwächung). Plugins, die dagegen verstoßen, sollten in ihrem Repository gemeldet werden und DÜRFEN aus jedem offiziellen Index entfernt werden.

---

## 8. Zukunft: optionaler offizieller Index

Der themenbasierte Markt braucht keinen Server. Wenn die Nachfrage wächst, KANN das Projekt einen statischen, generierten Index veröffentlichen, der periodisch `topic:turbodl-plugin` crawlt, jedes `turbodl-plugin.json` validiert und eine durchsuchbare Liste rendert, gefiltert nach Kategorie, Fähigkeit und unterstützter API-MAJOR. Dies bliebe stets eine Komfortschicht über GitHub-Themen, niemals ein Türsteher.
