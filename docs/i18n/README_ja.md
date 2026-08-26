# TurboDL

> 高性能なマルチスレッドダウンロードエンジン SDK — 純粋な Kotlin/JVM、Android 依存なし、あらゆる JVM アプリケーション（Android を含む）に直接統合できます。

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](../../LICENSE)

**言語:** [English](../../README.md) · [简体中文](README_zh-CN.md) · [繁體中文](README_zh-TW.md) · 日本語 · [한국어](README_ko.md) · [Deutsch](README_de.md)

TurboDL はゼロから書き下ろされたマルチスレッドダウンロードコアです。成熟したダウンロードマネージャー（aria2、IDM/XDM、axel、Persepolis、Motrix、ab-download-manager）のアーキテクチャとアルゴリズムの**アイデアのみを参考**しており、**いずれのソースコードもコピーしていません**。そのため、寛容な **MIT ライセンス（プラグインエコシステム補足条項付き）** でリリースされ、オープンソースまたは商用プロジェクトで自由に使用できます。

## 特徴

- **マルチスレッド分割ダウンロード**：HTTP Range の並列分割、接続の再利用（HTTP/2 多重化 + keep-alive）。
- **動的分割**：細粒度の事前分割 + ワークスティーリングにより、遅い接続が全体の転送を引きずらず、「最後のセグメントを単一スレッドで仕上げる」ロングテールを排除します（IDM/XDM のアイデア）。
- **順次セグメント優先**：前方のセグメントを先にダウンロードし、段階的なプレビュー/再生を可能にします（axel/Persepolis のアイデア）。
- **堅牢なフォールバックとリトライ**：
  - サーバーが Range をサポートしない/無視する → ファイル全体の単一ストリームダウンロードに自動フォールバック；
  - 不良/タイムアウトしたセグメント → **そのセグメントのみをリトライ**し、タスク全体を破棄しません；
  - 実際に返されたバイトが要求した Range 区間と一致するかを検証（サーバーが Range を改ざんしてファイル全体を返すのを防止）。
- **抑制的な適応**：429/503 を受け取ったとき、または連続失敗がしきい値に達したとき**のみ**並行度を乗算的に引き下げます。通常のネットワークのゆらぎではスレッド数を**決して**減らしません（AIMD のゆらぎヒューリスティックをそのままは使いません）。
- **レジューム可能なダウンロード**：セグメントは永続化され、一時停止/再開はディスク上の実際の進捗から続行します。
- **バイトレベルの整合性チェック**：マージ後に総サイズを検証し、破損したファイルを拒否します。

## 9 つのエンジン機能

| 機能 | 設定項目 |
|---|---|
| グローバル速度制限 | `globalSpeedLimitBytesPerSec`（トークンバケツ、2=0 は無制限） |
| スレッド数（最大 256） | `maxConnectionsPerTask`（1..256） |
| 同時タスク数 | `maxConcurrentTasks`（1..64） |
| 最大ダウンロードリトライ回数 | `maxRetries`（0..50） |
| 動的分割 | `dynamicSegmentation`（true/false） |
| 手動/自動プロキシ | `proxy = Direct / System / Manual(HTTP,SOCKS,認証) / Pac(url)` |
| DNS 設定 | `dns = System / StaticHosts / DoH(url)` |
| SSL 無視 | `trustAllCerts` |
| テーマ | 上位アプリが担当（SDK は UI レンダリングに関与しない） |

## クイックスタート

```kotlin
import dev.turbodl.core.*
import java.io.File

val client = TurboClient(
    TurboConfig(
        maxConnectionsPerTask = 16,
        globalSpeedLimitBytesPerSec = 0,        // 無制限
        dynamicSegmentation = true,
        proxy = ProxyMode.Direct,
        dns = DnsMode.System,
    )
)

// タスクを投入
val id = client.submit(
    DownloadRequest(
        url = "https://example.com/big.zip",
        destination = File("big.zip"),
    )
)

// 進捗イベントを監視
scope.launch {
    client.events.collect { event ->
        when (event) {
            is TurboEvent.Progress -> println("${event.progress.percent}%  ${event.progress.speedBytesPerSec} B/s")
            is TurboEvent.Completed -> println("完了: ${event.file}")
            is TurboEvent.Failed -> println("失敗: ${event.reason}")
            else -> {}
        }
    }
}

// 完了まで suspend
val result = client.await(id)   // Result<File>

// 制御
client.pause(id)
client.resume(id)
client.cancel(id, deleteOutput = true)

client.shutdown()
```

## コマンドライン

```
./gradlew :turbodl-cli:installDist
./turbodl-cli/build/install/turbodl-cli/bin/turbodl <url> [出力ファイル] --threads 16 --limit 0 [--insecure] [--no-dynamic]
```

## ビルド

```
./gradlew build        # コンパイル + ユニットテストの実行
```

ユニットテストは組み込み HTTP(Range) サーバーを使用して次をカバーします：マルチスレッドのバイトレベル正確性、動的分割、Range 非対応時のフォールバック、Range 改ざん時のフォールバック、一時的な 503 時に当該セグメントのみリトライ、グローバル速度制限。

## モジュール

- `turbodl-core`: ダウンロードエンジン SDK（公開ライブラリ、単独で使用可能、プラグインフレームワーク依存なし）。
- `turbodl-cli`: SDK の使い方を示すコマンドライン例。
- `turbo-plugin-runtime`: **オプション**のプラグインランタイムカーネル（ライフサイクル / disposer / イベントバス / サービスレジストリ / 拡張ポイント / バージョンハンドシェイク / 診断）。core はこれに依存せず、含めなくても core は通常どおり動作します。
- `turbo-plugin-bootstrap`: **オプション**のブートストラップモジュール。基礎プラグイン（Kotlin ローダー + HTTP バックエンド）をワンクリックで配線します。必須依存ではありません。
- `turbo-plugin-hls`: **オプション**の HLS VOD プロトコルアダプタプラグイン —— master/media M3U8 プレイリストを解決し、セグメントを並行ダウンロードし（セグメントごとにリトライ）、AES-128 を復号し、EXT-X-BYTERANGE を尊重し、マージのために順序付きパーツをエンジンに返します。ルーティングされる `DownloadBackend` として自身を登録します。非対応の構造（ライブストリーム、DRM/SAMPLE-AES、fMP4 EXT-X-MAP、discontinuity）は、壊れた出力を生成する代わりに明示的に失敗します。
- `demo`: 実行可能な3つのサンプル——Kotlin ネイティブプラグイン / bootstrap 利用 / Shim アダプタのテンプレート。`./gradlew :demo:run --args="1"`（または 2 / 3 / all）で実行。

## プラグインフレームワーク（オプション）

TurboDL はスタンドアロンのダウンロードエンジンである**と同時に、オプションのプラグインプラットフォーム**でもあります。設計を支える3つのアイデア:

1. **Core は単独で動作する。** `turbodl-core` はプラグイン依存ゼロの完全なマルチスレッドダウンロードエンジンです。プラグインは厳密に追加要素であり、何も読み込まなければ動作は変わりません。
2. **すべてがプラグインであり、カーネルは仕組みだけを提供する。** ランタイムカーネルはどのプロトコルについても知りません。ライフサイクル、後片付け（ディスポーザ）、サービスレジストリ、型安全なイベントバス、拡張ポイントレジストリ、バージョンハンドシェイク、診断という仕組みだけを提供します。Kotlin ローダー、HTTP バックエンド、HLS バックエンドはいずれも普通のプラグインです。
3. **ハイブリッド A+B。** Core は組み込みの HTTP バックエンド（A）を同梱するため、そのままでも役立ちます。プラグインバックエンドはレジストリを通じてそれを上書きしたり、新しいプロトコルを追加したりできます（B）——Core がランタイムに依存することはありません。依存の方向は厳密に `runtime → core` の一方向のみです。

バージョン管理された公開 API（`ApiVersion`、現在 `1.0.0`）とロード時のハンドシェイクにより、将来の破壊的コアリリースは、静かに動作を壊すのではなく大きなエラーとして失敗します（プラグインは `INCOMPATIBLE` とマークされ、読み込まれません）。

プラグインのドキュメント:
- [プラグイン制作ガイド](plugins/README_ja.md) — プラグインの作成方法と統合方法。
- [開発協定](plugins/CONVENTION_ja.md) — 公式の互換性ルールブック（安定 API、バージョニング、命名、安全性）。
- [プラグインマーケット](plugins/MARKET_ja.md) — GitHub トピックと `turbodl-plugin.json` マニフェストによるプラグインの公開・発見。

## 設計ノートと謝辞

TurboDL の設計は以下のオープンソースプロジェクトのアイデア（アイデアのみ、**ソースコードのコピーなし**）を取り入れており、ここに感謝します：
[aria2](https://github.com/aria2/aria2)、[Xtreme Download Manager](https://github.com/subhra74/xdm)、[axel](https://github.com/axel-download-accelerator/axel)、[Persepolis](https://github.com/persepolisdm/persepolis)、[Motrix](https://github.com/agalwood/Motrix)、[ab-download-manager](https://github.com/amir1376/ab-download-manager)。

## ライセンス

[MIT License](../../LICENSE)、「プラグインエコシステム補足条項」付き（プラグインは独立した作品であり、自由にライセンスでき、公開 API / 拡張ポイントを介して相互作用するだけでは派生作品とはみなされないことを明確化）。
