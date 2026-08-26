# TurboDL プラグインエコシステム開発協定

**ステータス:** 公式 · **バージョン:** 1.0 · **適用対象 TurboDL API:** `1.x`

これは TurboDL プロジェクトとプラグイン作者の間の公式契約です。破壊的なコア更新がすべてのプラグインを静かに壊してしまうことがないよう、エコシステムが成長できるために存在します。互換性ルールがエコシステム全体で一貫し続けるように、TurboDL プロジェクト自身が維持します（第三者には委任しません）。

プラグインを*書く*だけであれば、[docs/plugins/README.md](README_ja.md) から始めてください。本書はそのガイドが依拠する*ルールブック*です。

---

## 1. スコープと用語

- **Core** — `turbodl-core` モジュール: スタンドアロンのダウンロードエンジンと、プラグインが使用を許される公開データモデル/契約。
- **ランタイムカーネル** — `turbo-plugin-runtime` モジュール: ライフサイクル、ディスポーザ、サービスレジストリ、イベントバス、拡張ポイントレジストリ、バージョンハンドシェイク。仕組みのみで、ビジネスロジックは持ちません。
- **プラグイン** — `dev.turbodl.plugin.runtime.Plugin` を実装するものすべて。ローダー、バックエンド、パーサー、フック、アダプタはすべてプラグインです。カーネルだけがプラグインではありません。
- **安定 API** — §3 に列挙されたシンボル。それ以外はすべて内部実装であり、予告なく変更される可能性があります。
- **MUST / SHOULD / MAY** は RFC 2119 に従います。

---

## 2. バージョニングと互換性ポリシー

TurboDL は**公開されているプラグイン向け API** をセマンティックバージョニングで管理し、`dev.turbodl.core.ApiVersion.CURRENT` として公開しています。

- **メジャー（MAJOR）** — 安定 API のシンボル（§3）に対する破壊的変更のたびに増えます。メジャーをまたぐ変更は常に非互換とみなされます。
- **マイナー（MINOR）** — 後方互換な追加（デフォルト値付きの新しいメソッド、新しい拡張ポイント、新しいオプション設定）のたびに増えます。
- **パッチ（PATCH）** — 後方互換な修正のたびに増えます。

カーネルによって強制されるハンドシェイクルール:

```
host.satisfies(required)  ==  (host.major == required.major && host >= required)
```

- すべてのプラグインは `Plugin.requiredApiVersion` を宣言します（デフォルト `1.0.0`）。
- ホストは `ApiVersion.CURRENT.satisfies(plugin.requiredApiVersion)` を満たす場合にのみプラグインを読み込みます。
- 不一致の場合、プラグインは `PluginState.INCOMPATIBLE` とマークされ、`onLoad` は**決して**呼ばれず、診断がログに記録されます。これは設計によるものです: 破壊的なリリースは動作を壊すのではなく、ロード時に大きなエラーとして失敗します。

プラグイン作者の義務:
- `requiredApiVersion` には、実際に使用する API のうち最も低いバージョンを設定しなければなりません（MUST）。
- 対応する各 Core メジャーごとに、新しいプラグインリリースを公開すべきです（SHOULD）。
- ハンドシェイクをすり抜けるために内部（§3 外）クラスに依存してはなりません（MUST NOT）。

### TurboDL からプラグイン作者への約束

単一のメジャーライン内で、プロジェクトは:
- 安定 API のシンボルを削除したり、シグネチャを変更したりしてはなりません（MUST NOT）。
- 既存の実装を壊す方法で拡張ポイントの文書化された意味論を変更してはなりません（MUST NOT）。
- 新しい安定 API（MINOR）を追加してもよい（MAY）——追加はソース・バイナリの両方で互換である必要があります（インターフェースへの追加はデフォルト実装を伴って提供されます）。
- すべての変更を `CHANGELOG` に文書化しなければならず（MUST）、メジャーバンプには移行ノートを提供しなければなりません（MUST）。

---

## 3. 安定 API サーフェス（`1.x`）

互換性ポリシーの対象となるのは、これらのシンボルのみです。パッケージプレフィックス: `dev.turbodl.core.*`（core）と `dev.turbodl.plugin.runtime.*`（カーネル）。

**Core のコントラクト**
- `ApiVersion`（+ `CURRENT`、`satisfies`、`parse`）
- `DownloadRequest`、`TaskState`、`TaskProgress`、`TurboEvent`（sealed 階層）
- `DownloadBackend`、`BackendContext`、`BackendResult`、`BackendResolver`
- `TurboConfig`、`ProxyMode`、`ProxyType`、`DnsMode`
- `TurboClient` の公開メソッド: `submit`、`await`、`pause`、`resume`、`cancel`、`updateConfig`、`shutdown`、`events`、`progress`、`backendResolver`
- `TurboBackends.builtinHttp`、`TurboHttpClients.create`

**ランタイムカーネルのコントラクト**
- `Plugin`、`PluginContext`（+ `service` reified ヘルパー）
- `PluginHost` の公開メソッド: `install`、`installAll`、`uninstall`、`shutdown`、`publishEvent`、`applyRequestInterceptors`、`diagnostics`、および `services`/`extensions`/`eventBus` アクセサ
- `Disposer`、`PluginState`、`PluginInfo`、`DiagnosticsSnapshot`
- `ExtensionPointKey`、`ExtensionRegistration`、`ExtensionRegistry`、`ServiceRegistry`、`EventBus`
- `PluginLoaderProvider`（+ `KEY`）、`PluginSource`
- `dev.turbodl.plugin.runtime.ext.*`: `ExtensionPoints`、`LinkParser`、`TaskPreHook`、`TaskPostHook`、`BackendRegistry`

**明示的に安定ではないもの**（内部実装。依存しないこと）: `SegmentDownloader`、`SegmentScheduler`、`BuiltinHttpBackend`、`HttpClientFactory`、`PartMerger`、`SpeedLimiter`、および上記に列挙されていないすべてのもの。

---

## 4. プラグインの識別子と命名

- `Plugin.id` はグローバルに一意で、リリース間で安定し、小文字でドット区切りでなければなりません（MUST）: `<カテゴリ>.<名前>`、例: `backend.http`、`backend.hls`、`loader.kotlin`、`adapter.cordis`。
- 公式プロジェクトが所有する予約カテゴリプレフィックス: `backend.`、`loader.`、`core.`。サードパーティのプラグインはベンダー修飾名を使うべきです（SHOULD）: 例: `adapter.acme-cloud`、`backend.acme-ftp`（新しいプロトコルバックエンドは `backend.` を使用できますが、ベンダー修飾すべきです（SHOULD））。
- `registerService` で登録するサービス id は同じ規則に従い、プラグインの主要サービスではプラグイン id と一致させるべきです（SHOULD）。
- 公開済みの `Plugin.id` を変更することは、それに依存するすべての人にとって破壊的変更です。あなたのプラグインにとってのメジャーイベントとして扱ってください。

---

## 5. ライフサイクル契約

- `onLoad` は正確に1回だけ実行され、それは（a）バージョンハンドシェイクが成功し、（b）宣言されたすべての `dependencies`（サービス id）が存在する場合に限ります。
- プラグインはすべての副作用に対して対応する後片付けを登録しなければなりません（MUST）。実際には `PluginContext` のメソッドを優先してください——サービス/イベント/拡張の登録はディスポーザに自動配線され、それ以外（スレッド、ソケット、一時ファイル、外部 SDK のハンドル）には `context.disposer.register { ... }` を使います。
- `onLoad` が例外を投げた場合、ホストはプラグインのディスポーザチェーンをロールバックし、`FAILED` とマークします。部分的な副作用はロールバック可能でなければなりません（MUST）。
- `onUnload` は追加の処理を行ってもよい（MAY）が、ディスポーザがまだ実行されていないと仮定してはなりません（MUST NOT）（ディスポーザはその後で実行されます）。
- プラグインはいつでも安全に `uninstall` できる必要があります（MUST）: アンロード後、プラグインのサービス、リスナー、拡張実装のどれも到達可能であってはなりません。
- `onLoad`/`onUnload` は速やかに戻らなければなりません（MUST）。長時間またはブロッキングな処理はプラグイン自身のコルーチン/スレッドに属し、ディスポーザ経由で破棄します。

---

## 6. 拡張ポイント

- `PluginContext.registerExtension(key, impl, priority)` で実装を登録します。
- コンシューマーが「その」実装を選ぶ場所（例: バックエンドのルーティング）では、優先度（priority）の高い方が勝ちます。公式の基本プラグインは優先度 `0` を使います。基本機能を上書きしようとするプラグインはより高い値を使います（HLS は `100`、アダプタは一般に `200`）。意図を達成できる最も低い優先度を選んでください。
- `DownloadBackend.supports` は低コストで、副作用がなく、控えめでなければなりません（MUST）——実際に処理できるリクエストに対してのみ `true` を返し、ルーティングが予測可能になり、一致しないリクエストは組み込みの HTTP バックエンドにフォールバックするようにします。
- `LinkParser.parse` は処理しない入力に対して `null` を返さなければなりません（MUST NOT throw）——ルーターが次のパーサーを試せるようにするためです。
- フック/パーサー/バックエンドの実装は、同時に呼び出されることを許容しなければなりません（MUST）。

---

## 7. バックエンド作成ルール

`DownloadBackend` はプロトコル層のみを担当し、エンジンは状態、イベント、マージ、整合性を担当します。バックエンドは:

- 協調的キャンセルを尊重しなければなりません（MUST）: `BackendContext.isActive()` を確認し、コルーチンのキャンセルを尊重し、一時停止・キャンセル時に速やかに停止します。
- サイズが判明したら `reportTotalSize` で報告しなければなりません（MUST）（不明/ストリーミングの場合は `-1` を使用）、進行状況は `reportProgress` で報告します。
- `BackendContext.throttle(bytes)` を通じてバイト書き込みをレート制限すべきです（SHOULD）——グローバルな速度制限がタスク間で守られるようにするためです。
- 出力を `BackendContext.workDir` に書き込み、順序付きの `BackendResult.orderedParts` として返さなければなりません（MUST）; エンジンはその正確な順序で連結します。
- 回復不能なエラーでは、切り詰められた/壊れた出力を生成するのではなく、例外で失敗しなければなりません（MUST）。フォーマットがスコープ外の場合は明示的に失敗します（HLS バックエンドがライブ/DRM/fMP4 を壊れたファイルを出力する代わりに拒否する方法を参照）。
- Core の内部に手を入れてはなりません（MUST NOT）; §3 のシンボルだけを使います。Core の HTTP 転送ポリシー（proxy/DNS/TLS）が必要な場合は、`TurboHttpClients.create(config)` でクライアントを取得します。

---

## 8. イベントとサービス

- イベントリスナーとリクエストインターセプターは例外を投げてはなりません（MUST NOT）; バスは失敗を隔離してログに記録しますが、行儀の良いプラグインは自分のエラーを自分で処理します。
- インターセプターは純粋に近く高速であるべきです（MUST）; 何もしない場合は入力を変更せずに返します。これらは送信パス上で実行されます。
- サービスは軽量な id→インスタンスのレジストリであり、IoC コンテナではありません。`dependencies` に id を列挙してサービスに依存し、`context.service<T>(id)` で参照します。
- イベントバスをブロックしないでください; 重い処理はオフロードします。

---

## 9. セキュリティと安全性

- すべてのプレイリスト/マニフェスト/リダイレクト/リンクコンテンツを信頼できない入力として扱ってください。スキームを検証し、プロトコルが明示的に要求しない限り `http(s)` 以外の URI を拒否します（HLS バックエンドは SSRF/ローカルファイル読み取りを防ぐために `file://` を拒否します）。
- ユーザーデータや資格情報を流出させてはなりません。プラグインは、それがプラグインの明示的かつ文書化された目的である場合を除き、リクエスト URL、ヘッダー、クッキー、ダウンロードコンテンツをサードパーティのエンドポイントに送信してはなりません（MUST NOT）。
- 依存バージョンを固定してください; 大きくて未検証の推移的依存関係をランタイムに引き込むのは避けてください。
- プラグインは、ユーザーが設定を通じて明示的にオプトインする場合を除き、TLS を弱体化（`trustAllCerts`）してはなりません（MUST NOT）; 決してハードコードしてオンにしないでください。
- 秘密情報（キー、トークン）は参照で扱い、その値をログに記録しないでください。

---

## 10. パッケージングと配布

- 1つのプラグインリポジトリは、1つの主要な機能を提供すべきです（SHOULD）。`turbodl-plugin.json` マニフェスト（プラグインマーケットのドキュメントを参照）を提供し、リポジトリに適切な GitHub トピックをタグ付けします。
- リリースがターゲットとする TurboDL のメジャーラインを、マニフェストとリリースノートの両方で宣言します。
- プラグインが読み込まれ、その機能を実行することを証明する、実行可能な例またはテストを提供します。
- プラグインのライセンスは自由に選択できます。TurboDL の補足条項の下では、公開 API/拡張ポイントを通じたやり取りは、あなたのプラグインを TurboDL の派生作品にはしません。

---

## 11. 破壊的変更の規律（プラグイン作者向け）

TurboDL が Core に適用しているものと同じ規律を、あなた自身のプラグインにも適用してください:
- `Plugin.id` を変更する、公開したサービスを削除する、拡張の観察可能な動作を変更する場合は、プラグインのメジャーを上げてください。
- `requiredApiVersion` を正確に保ってください。
- プラグインのチェンジログに移行手順を文書化してください。

---

## 12. 本協定の変更

この文書はバージョン管理されています。後方互換な明確化は MINOR を上げます。以前に適合していたプラグインを無効化する変更は MAJOR を上げ、対応する Core のメジャーと移行ノートを伴って出荷しなければなりません（MUST）。提案は TurboDL リポジトリを経由します。