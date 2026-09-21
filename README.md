# BT Auto Connect

車のオーディオなど、ペアリング済みBluetoothデバイスの接続状態を表示し、
接続・自動再接続を試みる最小限のAndroidアプリです。

## ビルド方法

1. Android Studio(Giraffe以降推奨)で「Open」からこの `BTAutoConnect` フォルダを開く
2. 初回は Gradle Wrapper が無いので、Android Studio が自動生成するのを待つか、
   メニューの File > Sync Project with Gradle Files を実行する
3. 実機(Android 8.0 / API 26以上)をUSB接続し、Run ▶ で実機にインストール

## 使い方

1. アプリ起動時に Bluetooth 権限(Android 12以降は「近くのデバイス」権限)を許可
2. 画面下部の「ペアリング済みデバイス」一覧から車のオーディオをタップして対象に設定
   (対象は端末に保存され、次回起動時も引き継がれます)
3. 「接続する」で接続を試みます
4. 「自動再接続」をONにしておくと、対象デバイスが切断されたときに
   一定間隔(5秒×最大5回)で自動的に再接続を試みます
5. 自動接続がうまくいかない機種では「設定画面を開く」でシステムのBluetooth設定を開き、
   手動で接続してください

## 重要な制限事項(必ず読んでください)

Android は、ペアリング済みデバイスへの**接続を開始する**API
(`BluetoothA2dp.connect()` / `BluetoothHeadset.connect()`)を
一般アプリに公開していません。これらは `@SystemApi` 扱いで、
本来は `BLUETOOTH_PRIVILEGED` 権限(システム署名アプリのみ取得可能)が必要です。

本アプリはリフレクションでこれらの隠しメソッドを呼び出すことで
「動けば接続できる」実装にしていますが、結果は端末メーカーとAndroidバージョン次第です。

- 動く可能性がある: Samsung, Xiaomi など、独自のBluetoothスタックで
  この呼び出しを許可している端末
- 動かない可能性が高い: Pixel/AOSPに近い素のAndroid(SELinuxでブロックされ
  SecurityExceptionになります)

失敗した場合でも「切断されたら自動でシステム設定を開く」までは確実に動作するので、
最低限「タップ1回で接続画面にたどり着く」ショートカットとしては機能します。

## より確実な代替手段

もし「確実に自動接続」が必須要件であれば、Androidアプリ単体では実現が難しく、
以下のような別アプローチが必要になります。

- 端末をroot化し、`su` 経由で `BLUETOOTH_PRIVILEGED` を回避する
- 車載機側のBluetooth自動再接続機能(多くの車載オーディオは「最後に接続した
  デバイスに自動接続」する機能を持っています)を使う
- Tasker等のオートメーションアプリ+Accessibility Serviceで
  設定画面上のタップを自動操作する(遠回りですが非root環境でも動く場合があります)

## Teamsチャンネルの読み上げ(Microsoft Graph API)

「Teamsチャンネルを読み上げ」ボタンは、指定したチャンネルの最新メッセージ(最大5件)を
Microsoft Graph APIで取得し、音声で読み上げます。ボタンを長押しすると対象の
チーム名・チャンネル名を設定できます。読み上げ中にもう一度押すと停止します。

利用には次の事前設定が必要です(自分の組織のAzure ADに登録します)。

1. Azure portal の「アプリの登録」で新しいアプリを登録し、プラットフォームに
   「Android」を追加する(パッケージ名: `com.example.btautoconnect`、署名ハッシュは手順3で求めた値)
2. 「APIのアクセス許可」に、Microsoft Graphの委任された権限
   `Team.ReadBasic.All` / `Channel.ReadBasic.All` / `ChannelMessage.Read.All` を追加する
   (`ChannelMessage.Read.All` は多くの組織で**管理者の同意**が必要です)
3. 署名ハッシュを求める(デバッグビルドの例)

   ```bash
   keytool -exportcert -alias androiddebugkey -keystore ~/.android/debug.keystore | openssl sha1 -binary | openssl base64
   ```

4. 次の2か所のプレースホルダを実際の値に置き換える
   - `app/src/main/res/raw/auth_config.json` の `client_id` / `tenant_id` と、
     `redirect_uri` 末尾の署名ハッシュ(URLエンコードした値。`+`→`%2B`、`/`→`%2F`、`=`→`%3D`)
   - `app/src/main/AndroidManifest.xml` の `android:path="/SIGNATURE_HASH"`(エンコードしない値)
