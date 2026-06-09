# EpicFight MVP Boss — `efbossmvp`

EpicFight 本体導入を前提に、**新規ボスエンティティ `efbossmvp:dread_knight`** を追加し、
**データパックでプレイヤースキルのアニメ（`sweeping_edge` / `greatsword_slam` 等）を使わせる**動作確認用 MVP。

> 設計根拠は同フォルダの設計仕様書ファミリ（`EpicFight_*_Spec*.md`）と、EpicFight 1.20.1 実ソース（`Antikythera-Studios/epicfight`）の検証に基づく。

## アーキテクチャ（重要）

**EpicFight 化はデータパックが全部やる。** 新規 mod エンティティは
`data/efbossmvp/epicfight_mobpatch/dread_knight.json` だけで EpicFight 化される
（`MobPatchReloadListener` が DP 読込時に armature/mesh/renderer を自動登録）。

→ **mod の Java 側に EpicFight の import は一切無い**。mod は「ただの Forge エンティティ」：
1. EntityType 登録＋属性
2. **バニラ風クライアント Renderer 登録**（EpicFight がこれを上書きパッチする土台。未登録だと描画されない）
3. スポーンエッグ

EpicFight は `mods.toml` で `mandatory=true`（ハード依存）＋ dev 実行用に build.gradle へ runtime 依存追加のみ。

## 構成

```
src/main/java/com/example/efbossmvp/
  EfBossMvp.java            @Mod メイン
  ModEntities.java          EntityType 登録
  DreadKnightEntity.java    Monster 継承・AI・属性
  ModItems.java             スポーンエッグ
  ModEvents.java            MODバス: 属性/スポーン配置/タブ
  client/ClientEvents.java  MODバス(client): バニラ風レンダラ
src/main/resources/
  META-INF/mods.toml        epicfight を mandatory=true 宣言
  assets/efbossmvp/lang/    en_us / ja_jp
datapack/                   テストワールドの datapacks/ に配置
  pack.mcmeta               pack_format 15
  data/efbossmvp/epicfight_mobpatch/dread_knight.json   ★中核
build.gradle.snippet        既存 build.gradle への追記分
gradle.properties.snippet   epicfight_version 等
```

## 統合手順（自前の Forge 1.20.1 MDK へ）

1. `src/` を MDK の `src/` にマージ（パッケージ `com.example.efbossmvp` はリネーム可。リネーム時は全 `.java`＋`mods.toml`＋`assets/lang` の `efbossmvp` を一括置換）。
2. `build.gradle.snippet` / `gradle.properties.snippet` の内容を既存ファイルへ追記し、EpicFight を dev runtime 依存に追加。
3. `datapack/` をテストワールドの `saves/<world>/datapacks/` に配置（または mod jar 内 `src/main/resources/data/...` に同梱でも可）。

## 動作確認

1. EpicFight 20.14.x(1.20.1) を導入した状態で `./gradlew runClient`。
2. ワールド入場 → `/reload`（DP 反映）。
3. クリエイティブのスポーンエッグ、または `/summon efbossmvp:dread_knight ~ ~ ~`。剣/大剣を持たせる（`/item replace entity @e[type=efbossmvp:dread_knight,limit=1] weapon.mainhand with minecraft:diamond_sword` 等）。
4. 確認項目：
   - (a) EpicFight モーション（biped/zombie 描画）で移動・攻撃するか
   - (b) 近接で `mob_onehand1/2` コンボ
   - (c) HP 50% 以下で `sweeping_edge`、25% 以下で `greatsword_slam`
   - (d) **スキルアニメ内蔵コライダーで実ダメージ**が入るか
   - (e) クラッシュ無し

## 既知の罠（踏むと壊れる）

- `greatsword_slam` が正（`meteor_slam` は存在せず `NoSuchElementException` で起動時クラッシュ）。
- `isHumanoid:true` では `humanoid_weapon_motions` が必須。
- humanoid の `combat_behavior` は weapon_categories＋style＋behavior_series の3階層。
- HP 帯フェーズは各攻撃を独立 `behavior_series` にして先頭 behavior に `health` gate（同一 series 内の複数 behavior はコンボ連鎖で出し分けではない）。
- `renderer` 文字列は解決必須（`minecraft:zombie`）。解決不能で `Invalid Renderer type` クラッシュ。
- ログに `No animation ... NoSuchElementException` / `undefined weapon motions` / `Invalid Renderer type` が出たら綴り・必須フィールドを点検。

## バージョン注意

EpicFight 本体・registry 名（`epicfight:biped/skill/...`）・`faction` 有効値・フィールド名はバージョン依存。導入する jar 版（例 `20.14.17-mc1.20.1-forge`）で最終一致を確認すること。
