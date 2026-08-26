# TurboDL プラグインマーケット（GitHub トピックベース）

TurboDL は中央集権的なパッケージサーバーを運営していません。プラグインの「マーケット」とは、単に**GitHub トピックタグと機械可読なマニフェスト**のことです。誰でも、リポジトリをプッシュし、適切なトピックを追加し、リポジトリルートに `turbodl-plugin.json` を置くだけでプラグインを公開できます。誰でも GitHub のトピック検索でプラグインを発見できます。これにより、エコシステムはオープンで、分散型で、インフラゼロに保たれます。

本書は、タグ、マニフェスト、公開・発見のフローを定義します。[プラグインエコシステム開発協定](CONVENTION_ja.md)（互換性のルールブック）と[プラグイン制作ガイド](README_ja.md)と対で使用します。

---

## 1. プラグインを発見する

すべての TurboDL プラグインはルートトピック **`turbodl-plugin`** を持ちます。以下で閲覧できます:

```
https://github.com/topics/turbodl-plugin
```

GitHub 検索でトピックを組み合わせて、機能ごとに絞り込みます:

```
topic:turbodl-plugin topic:turbodl-backend      # プロトコルバックエンド
topic:turbodl-plugin topic:turbodl-adapter       # shim/サービスアダプタ
topic:turbodl-plugin topic:turbodl-hls           # HLS 関連
```

GitHub API も使えます:

```
GET https://api.github.com/search/repositories?q=topic:turbodl-plugin+topic:turbodl-backend
```

---

## 2. トピックタグ（「棚」）

すべてのプラグインリポジトリは `turbodl-plugin` を持ち、さらに**カテゴリ**トピックを1つだけ、そして任意の数の**機能（capability）**トピックを持たなければなりません（MUST）。

**ルート（必須）**
- `turbodl-plugin`

**カテゴリ（1つ選択、必須）**
- `turbodl-backend` — ダウンロードプロトコルを追加・上書きする（`DownloadBackend`）
- `turbodl-adapter` — 外部システム/サービスを橋渡しする（shim; 通常は `LinkParser` + バックエンド）
- `turbodl-parser` — リンク/マニフェストのパーサーのみ（`LinkParser`）
- `turbodl-hook` — タスクの前後処理（`TaskPreHook` / `TaskPostHook`）
- `turbodl-loader` — プラグインローダー（`PluginLoaderProvider`、例: JS プロバイダー）

**機能（オプション、任意の数）**
- プロトコル/フォーマット: `turbodl-hls`、`turbodl-dash`、`turbodl-ftp`、`turbodl-magnet`、`turbodl-m3u8`
- 振る舞い: `turbodl-remux`、`turbodl-checksum`、`turbodl-notify`、`turbodl-unpack`
- 統合: `turbodl-cloud`、`turbodl-drm-free`

カテゴリと機能のタグこそが、マーケットのプラグインを簡単に作って見つけられるようにする仕組みです: 棚を選び、ユーザーはそこに絞り込めます。

---

## 3. `turbodl-plugin.json` マニフェスト

このファイルをリポジトリルートに置きます。これは、ツール（または将来の公式インデクサー）があなたのプラグインを理解するために読み取る、単一の機械可読な記述子です。

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

フィールドの注記:
- `id` はプラグインの `Plugin.id` と一致しなければならず（MUST）、協定（§4）の命名規則に従わなければなりません。
- `turbodl.apiMajor` と `requiredApiVersion` は、プラグインがコードで宣言するもの（`Plugin.requiredApiVersion`）と一致しなければなりません（MUST）。これにより、マーケット/ツールは、特定の TurboDL バージョンで実行できないプラグインを**ダウンロードする前に**除外できます。
- `category` はカテゴリトピックのいずれかでなければならず（MUST）、`capabilities` はリポジトリの機能トピックを反映すべきです（SHOULD）。
- `entry.language` は現時点では `kotlin` です。`js` は将来の JS プロバイダーのために**予約**されています; Core と Kotlin ローダーは JS について知りません。
- `artifact.type` は `maven`（公開された JAR）または `jar`（`artifact.url` に指定した直接のリリースアセット URL）です。配布方法に合う方を選んでください。

検証用の JSON スキーマは [`turbodl-plugin.schema.json`](../../plugins/turbodl-plugin.schema.json) にあります。

---

## 4. 推奨リポジトリレイアウト

```
turbodl-plugin-<name>/
├─ turbodl-plugin.json          # マニフェスト（ルート）
├─ README.md                    # 何をするものか、インストールスニペット、対応 TurboDL メジャー
├─ LICENSE
├─ src/main/kotlin/...          # Plugin の実装
└─ src/test/kotlin/...          # 読み込みと機能の実行を証明するテスト
```

リポジトリの説明と README は、対応する TurboDL メジャーラインを明示的に記載すべきです（SHOULD）（例:「TurboDL 1.x」）。

---

## 5. 公開チェックリスト

1. [制作ガイド](README_ja.md)と[協定](CONVENTION_ja.md)に従って `Plugin` を実装します。
2. `Plugin.requiredApiVersion` を実際に使用する最低の API に設定します。
3. リポジトリルートに `turbodl-plugin.json` を追加し、スキーマに対して検証します。
4. GitHub トピックを追加します: `turbodl-plugin` + カテゴリ1つ + 機能。
5. README にインストールスニペットと対応 TurboDL メジャーを記入します。
6. `artifact` に一致するアーティファクト（Maven 座標またはリリース JAR）を公開します。
7. マニフェストの `version` と等しいバージョンのリリースにタグを付けます。

これが「マーケット」のすべてです: プッシュして、タグを付けて、完了。ゲートキーパーもサーバーもありません。

---

## 6. プラグインをインストールする（コンシューマー側）

1. ビルドにプラグインのアーティファクトを追加します（マニフェストの Maven 座標）、`turbodl-core` と `turbo-plugin-runtime` と一緒に。
2. ホストにインストールします:

```kotlin
val host = PluginHost()
host.install(HlsPlugin())                 // またはプラグインが文書化したエントリクラス
// bootstrap の便利 API を使う場合:
val boot = TurboBootstrap.create(extraPlugins = listOf(HlsPlugin()))
```

3. バージョンハンドシェイクは自動的に実行されます。プラグインがあなたの TurboDL より新しい API を必要とする場合、`INCOMPATIBLE` とマークされ、決して読み込まれません——`host.diagnostics().render()` で確認してください。

---

## 7. 信頼と安全性

中央のレビューはありません。サードパーティのプラグインは他の依存関係と同じように扱ってください:
- ソースを読み、テストと明確なライセンスのあるプラグインを優先してください。
- インストール前に、マニフェストの `apiMajor` があなたの TurboDL と一致するか確認してください。
- [協定 §9](CONVENTION_ja.md) はプラグインが従うことが期待されるセキュリティルールを列挙しています（未検証入力の検証、データ流出なし、黙った TLS 弱体化なし）。これらに違反するプラグインはそのリポジトリで報告し、公式インデックスから削除してもよい（MAY）とします。

---

## 8. 将来: オプションの公式インデックス

トピックベースのマーケットにはサーバーは必要ありません。需要が伸びれば、プロジェクトは静的で生成済みのインデックスを公開してもよい（MAY）とします。それは定期的に `topic:turbodl-plugin` をクロールし、各 `turbodl-plugin.json` を検証し、カテゴリ、機能、対応 API メジャーでフィルタリングされた検索可能なリストをレンダリングします。これは GitHub トピックの上に重なる便利レイヤーであり続け、決してゲートキーパーにはなりません。