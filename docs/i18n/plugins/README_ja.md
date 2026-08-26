# TurboDL プラグイン

TurboDL はスタンドアロンのダウンロードエンジンである**と同時に、オプションのプラグインプラットフォーム**でもあります。このディレクトリには、プラグイン作者が必要とするすべての情報がまとめられています:

- **[プラグイン制作ガイド](#プラグインの作成)** — 本書: 概念とステップバイステップの手順。
- **[開発協定](CONVENTION_ja.md)** — 公式の互換性ルールブック（安定 API、バージョニング、命名、安全性）。公開する前に必ず読んでください。
- **[プラグインマーケット](MARKET_ja.md)** — GitHub トピックと `turbodl-plugin.json` マニフェスト（[スキーマ](../../plugins/turbodl-plugin.schema.json)）によるプラグインの公開・発見方法。

実行可能なサンプルは [`demo`](../../demo) モジュールにあります:
`./gradlew :demo:run --args="1"`（Kotlin プラグイン）、`"2"`（bootstrap）、`"3"`（shim アダプタ）。

---

## 哲学

設計を支える3つのアイデア:

1. **Core は単独で動作する。** `turbodl-core` はプラグイン依存ゼロの、完全なスタンドアロンのマルチスレッドダウンロードエンジンです。プラグインは厳密に追加要素であり、1つも読み込まなければ何も変わりません。

2. **すべてがプラグインであり、カーネルは仕組みだけを提供する。** ランタイムカーネル（`turbo-plugin-runtime`）は HTTP や HLS、その他あらゆるプロトコルについて知りません。ライフサイクル、後片付け（ディスポーザ）、依存解決付きのサービスレジストリ、型安全なイベントバス、拡張ポイントレジストリ、バージョンハンドシェイク、診断という仕組みだけを提供します。Kotlin ローダー、HTTP バックエンド、HLS バックエンド——それらはすべて普通のプラグインです。将来の JS ランタイムでさえ、`PluginLoaderProvider` を実装したただのプラグインになるでしょう。

3. **ハイブリッド A+B。** （A）Core は組み込みの HTTP バックエンドを同梱するため、箱から出してすぐ役立ちます。（B）プラグインバックエンドは拡張レジストリを通じて組み込みバックエンドを上書きしたり、新しいプロトコルを追加したりできます——Core がランタイムに依存することは決してありません。依存の方向は厳密に `runtime → core` であり、逆は決してありません。

その結果、サードパーティが開発対象にできる小さく安定した契約面（contract surface）が生まれ、互換性ポリシー（[協定](CONVENTION_ja.md)）により、将来の破壊的コアリリースはプラグインを静かに壊すのではなく、大きなエラーとして失敗します。

---

## 構成要素

| 概念 | 型 | 用途 |
|---|---|---|
| プラグイン | `Plugin` | あなたが実装する単位。`id`、`requiredApiVersion`、任意の `dependencies` を持ちます。 |
| コンテキスト | `PluginContext` | `onLoad` に渡されます。サービス・イベント・拡張の登録に使います。登録内容はアンロード時に自動で後片付けされます。 |
| 後片付け | `Disposer` | LIFO の後片付けチェーン。アンロード時にコールバック単位で隔離して実行されます。 |
| サービス | `ServiceRegistry` | 軽量な id→インスタンスのレジストリ。依存関係のゲーティングも担当します。 |
| イベント | `EventBus` | `TurboEvent` の観測と、送信時における `DownloadRequest` のインターセプト。 |
| 拡張ポイント | `ExtensionPointKey<T>` | プラグインが実装する型付き契約。コンシューマーはキーと優先度で照会します。 |
| バージョン | `ApiVersion` | ハンドシェイクの要: ホストはプラグインの `requiredApiVersion` を満たす必要があります。 |

**組み込み拡張ポイント**（`dev.turbodl.plugin.runtime.ext.ExtensionPoints`）:
- `DOWNLOAD_BACKEND` — プロトコルの追加・上書き（`DownloadBackend`）
- `LINK_PARSER` — 生のリンクを `DownloadRequest` に変換する（`LinkParser`）
- `TASK_PRE_HOOK` — 送信前にリクエストを書き換える（`TaskPreHook`）
- `TASK_POST_HOOK` — タスク完了後に反応する（`TaskPostHook`）

**カーネル**が名前で知っている唯一の拡張ポイントは `PluginLoaderProvider`（ローダー）です。

---

## プラグインの作成

### 1. コントラクトに依存する

```kotlin
dependencies {
    implementation("dev.turbodl:turbodl-core:<version>")
    implementation("dev.turbodl:turbo-plugin-runtime:<version>")
}
```

### 2. `Plugin` を実装する

```kotlin
import dev.turbodl.core.ApiVersion
import dev.turbodl.plugin.runtime.Plugin
import dev.turbodl.plugin.runtime.PluginContext

class MyPlugin : Plugin {
    override val id = "adapter.acme"                 // 一意・ドット区切り・ベンダー修飾
    override val name = "ACME Adapter"
    override val requiredApiVersion = ApiVersion(1, 0, 0)   // 実際に使う最低限の API

    override fun onLoad(context: PluginContext) {
        // サービス / イベント / 拡張をここで登録——アンロード時にすべて自動で後片付け
    }
}
```

### 3. `onLoad` で機能を登録する

```kotlin
// 他のプラグインが依存できるサービス:
context.registerService(id, myService)

// エンジンのイベントを観測する（アンロード時に自動で購読解除）:
context.onEvent { event -> /* ... */ }

// 実行前にリクエストを書き換える:
context.interceptRequest { req -> req.copy(headers = req.headers + ("X-Trace" to "1")) }

// 拡張ポイントの実装を提供する（「その」実装を選ぶ場合は優先度の高い方が勝つ）:
context.registerExtension(ExtensionPoints.DOWNLOAD_BACKEND, myBackend, priority = 100)

// 後片付けが必要なその他のもの:
context.disposer.register { myThreadPool.shutdown() }
```

`context` を通じて登録したものはすべて、プラグインがアンインストールされたときに自動的に後片付けされます。`onLoad` が例外を投げた場合、部分的な登録はロールバックされます。

### 4. `DownloadBackend` を書く

バックエンドはプロトコルを担当し、エンジンは状態・イベント・マージ・整合性を担当します。ルール（完全なリストは[協定 §7](CONVENTION_ja.md)）:

- `supports(request)` — 低コストで、副作用がなく、控えめに。
- `context.isActive()` とコルーチンのキャンセルを尊重する。
- 総サイズが判明したら `context.reportTotalSize(total)`（不明なら `-1`）、進行に応じて `context.reportProgress(...)`、グローバルな速度制限を守るために `context.throttle(bytes)` を呼ぶ。
- パーツを `context.workDir` に書き込み、**順序どおり** `BackendResult.orderedParts` として返す。
- 回復不能またはスコープ外の入力では、**例外を投げる**——壊れたファイルを出力してはならない。
- Core の HTTP 転送ポリシー（proxy/DNS/TLS）が必要なら、`TurboHttpClients.create(config)` を使う。Core の内部には一切触れない。

完全で非自明なバックエンド（プレイリスト解析、AES-128、バイトレンジ、未対応構造の明示的拒否）の例は `turbo-plugin-hls` を参照してください。

### 5. インストールする

```kotlin
val host = PluginHost()
host.install(MyPlugin())

// プラグインバックエンド経由でダウンロードをルーティングする（一致しない場合は組み込み HTTP にフォールバック）:
val client = TurboClient(config)
client.backendResolver = BackendRegistry(host.extensions)
```

または bootstrap の便利 API を使う:

```kotlin
val boot = TurboBootstrap.create(extraPlugins = listOf(MyPlugin()))
val id = boot.client.submit(DownloadRequest(url, dest))
```

### 6. 診断で検証する

```kotlin
println(host.diagnostics().render())
// プラグインと状態（LOADED / WAITING / FAILED / INCOMPATIBLE / UNLOADED）、
// 拡張ポイント、サービス、リスナー数を一覧表示します。
```

プラグインが `INCOMPATIBLE` と表示された場合、実行中の TurboDL API があなたの `requiredApiVersion` を満たしていません——ハンドシェイクを確認してください（[協定 §2](CONVENTION_ja.md)）。

### 7. 公開する

[プラグインマーケット](MARKET_ja.md) の手順に従います: `turbodl-plugin.json` を追加し、リポジトリに `turbodl-plugin` + カテゴリ + 機能（capabilities）のタグを付け、アーティファクトを公開し、リリースを切り出します。

---

## Shim アダプタ

「shim」は外部のダウンローダー/SDK をラップし、TurboDL にそのシステムの存在を意識させることなく、`LinkParser` + `DownloadBackend` を通じて公開します。`demo/.../Example3ShimAdapter.kt` のテンプレートから始めて、プレースホルダーの `ExternalDownloader` を実際の SDK に置き換えてください。

---

## JS についての注記

JavaScript ランタイムは**予約された将来の**機能です。それは `PluginLoaderProvider` を実装する独立したプラグインとして提供される予定で、Core と Kotlin ローダーは JS について知らず、カーネルに JS エンジンが取り込まれることもありません。マニフェストはその日のために `entry.language: "js"` を予約しています。