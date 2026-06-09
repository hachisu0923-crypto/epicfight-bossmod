# EpicFight MVP Boss — `efbossmvp`

EpicFight 本体導入を前提に、**新規ボスエンティティ `efbossmvp:dread_knight`** を追加する
**そのままビルドできる完成 Forge 1.20.1 mod プロジェクト**。
ボスは **1 つの武器（剣）で複数のプレイヤースキル**（`battojutsu` / `blade_rush` /
`sweeping_edge` / `dancing_edge` / `greatsword_slam`）を HP フェーズで使い分ける。

> 設計根拠は同フォルダの設計仕様書ファミリ（`EpicFight_*_Spec*.md`）と、EpicFight 1.20.1 実ソース（`Antikythera-Studios/epicfight`）の検証に基づく。

## アーキテクチャ（重要）

**EpicFight 化はデータパックが全部やる。** 新規 mod エンティティは
`data/efbossmvp/epicfight_mobpatch/dread_knight.json` だけで EpicFight 化される
（`MobPatchReloadListener` が DP 読込時に armature/mesh/renderer を自動登録）。

→ **mod の Java 側に EpicFight の import は一切無い**。mod は「ただの Forge エンティティ」：
1. EntityType 登録＋属性（HP200 のボス級）
2. **バニラ風クライアント Renderer 登録**（EpicFight がこれを上書きパッチする土台。未登録だと描画されない）
3. スポーンエッグ
4. **スポーン時にネザライトの剣を装備**（`finalizeSpawn`）。下記の通り
   combat_behavior は「剣を持っている時」だけ発動するため、これが無いと素手＝FIST 扱いでスキルが出ない。

EpicFight は `mods.toml` で `mandatory=true`（ハード依存）。本 mod は EpicFight API へ
コンパイル依存しない純データパック方式なので、build.gradle では `runtimeOnly` 1 行で足りる。

## ボスの戦闘設計（1 武器・複数スキル）

武器カテゴリは **`sword`（`one_hand`）に 1 本化**。バニラの剣 = EpicFight の SWORD カテゴリと
確実に対応するため（greatsword カテゴリは専用アイテムが要るので堅牢性で sword を採用）。
各スキルは **独立した `behavior_series`** として `weight` 確率で出し分け、`health` predicate で
HP フェーズ解禁、`cooldown` でスパムを抑制する。スキルアニメは `AnimationManager.byKey()` で
直接解決され **Mob 用ホワイトリストが無い**ため、剣 1 本でも全スキルが再生され内蔵コライダーで実ダメージが入る。

| 解禁 HP | スキル / アニメ | 種別 | 間合い | cooldown(tick) |
|--------:|-----------------|------|--------|---------------:|
| 常時 | `mob_onehand1` → `mob_onehand2` | 基本コンボ（2 段連鎖） | ~2.3 | 16 |
| 常時 | `skill/battojutsu` | 抜刀・接近 | 2.0–5.0 | 100 |
| < 70% | `skill/blade_rush_combo1` | ダッシュ斬 | 0–5.0 | 130 |
| < 50% | `skill/sweeping_edge` | 広範囲 AoE | < 3.0 | 120 |
| < 50% | `skill/dancing_edge` | 多段フラリー | < 3.0 | 150 |
| < 25% | `skill/greatsword_slam` | 大技フィニッシャ | < 3.5 | 160 |

> **武器を変えたい場合**：`DreadKnightEntity#finalizeSpawn` の装備アイテムと、
> `dread_knight.json` の `combat_behavior` / `humanoid_weapon_motions` の `weapon_categories` /
> `style` を揃えて変更する（例: greatsword を使うなら EpicFight が GREATSWORD と判定する
> 大剣アイテムを装備し、両者を `["greatsword"]` / `two_hand` にする）。

## 構成

```
build.gradle                 ForgeGradle 6 / Forge 1.20.1（EpicFight を runtimeOnly 同梱）
settings.gradle              pluginManagement（MinecraftForge / Gradle Plugin Portal）
gradle.properties            mc/forge/mappings/epicfight バージョン・mod メタ
gradlew / gradlew.bat        Gradle wrapper（8.1.1）
gradle/wrapper/              wrapper jar / properties
src/main/java/com/example/efbossmvp/
  EfBossMvp.java             @Mod メイン
  ModEntities.java           EntityType 登録
  DreadKnightEntity.java     Monster 継承・AI・属性・剣を装備
  ModItems.java              スポーンエッグ
  ModEvents.java             MODバス: 属性/スポーン配置/タブ
  client/ClientEvents.java   MODバス(client): バニラ風レンダラ
src/main/resources/
  META-INF/mods.toml         epicfight を mandatory=true 宣言
  assets/efbossmvp/lang/     en_us / ja_jp
  data/efbossmvp/epicfight_mobpatch/dread_knight.json   ★中核（jar 同梱で /reload 不要）
datapack/                    手動でワールド datapacks/ に置きたい人向けの同内容コピー
  pack.mcmeta                pack_format 15
  data/efbossmvp/epicfight_mobpatch/dread_knight.json
```

> `src/main/resources/data/...` と `datapack/data/...` は同一内容。前者は mod jar に
> 内蔵される built-in datapack（導入だけで有効）、後者はワールドへ手動投入したい場合用。
> どちらか一方を編集したら両方を同期すること。

## ビルド（jar を作る）

このフォルダ自体が完成プロジェクト。ルート（`mvp-boss-mod/`）で：

```
./gradlew build      # → build/libs/efbossmvp-1.0.0.jar
./gradlew runClient  # EpicFight を runtimeOnly 同梱した dev クライアントで起動
```

**ビルド要件（重要）**
- **JDK 17** が必要（`java.toolchain = 17`）。Java 21 等では ForgeGradle の脱/再コンパイルが失敗しうる。
- **ネットワーク必須**：初回は Gradle 配布物・Forge userdev・Minecraft・EpicFight(Modrinth Maven) を取得する。
  （オフライン/制限環境では取得できずビルド不可。`gradle.properties` の `epicfight_version` は導入版に合わせる）

## 導入（実際にプレイへ）

1. `./gradlew build` で出来た `build/libs/efbossmvp-1.0.0.jar` と **EpicFight 本体 jar** を
   両方 `mods/` に入れる（Minecraft 1.20.1 / Forge 47.x）。
2. スポーンエッグ（クリエイティブのスポーンエッグタブ）、または `/summon efbossmvp:dread_knight ~ ~ ~`。
   ボスはネザライトの剣を持って出現する（手動装備は不要）。

## 動作確認

1. `./gradlew runClient` でワールド入場。jar 同梱データなので `/reload` は基本不要
   （ワールドの `datapacks/` に手動配置した場合のみ `/reload`）。
2. スポーンエッグ or `/summon efbossmvp:dread_knight ~ ~ ~`。
3. 確認項目：
   - (a) EpicFight モーション（biped/zombie 描画）で移動・攻撃するか
   - (b) 近接で `mob_onehand1/2` コンボ
   - (c) HP 70% で `blade_rush`、50% で `sweeping_edge` / `dancing_edge`、25% で `greatsword_slam`、
         開幕から `battojutsu` の接近斬りが混ざるか
   - (d) **スキルアニメ内蔵コライダーで実ダメージ**が入るか
   - (e) クラッシュ無し

## 既知の罠（踏むと壊れる）

- `greatsword_slam` が正（`meteor_slam` は存在せず `NoSuchElementException` で起動時クラッシュ）。
- **武器を持っていないと combat_behavior が発動しない**（素手＝FIST）。本 mod は `finalizeSpawn` で
  剣を装備して回避済み。`weapon_categories` と実際に持つアイテムのカテゴリは必ず一致させること。
- `isHumanoid:true` では `humanoid_weapon_motions` が必須（`combat_behavior` の weapon_categories/style と一致）。
- humanoid の `combat_behavior` は weapon_categories＋style＋behavior_series の 3 階層。
- HP 帯フェーズは各スキルを独立 `behavior_series` にして `health` gate（同一 series 内の複数 behavior は
  コンボ連鎖であって出し分けではない）。
- `dread_knight.json` に EpicFight が知らない独自キー（`comment` 等）を足さない。綴り 1 文字違いと同様にロード失敗要因。
- `renderer` 文字列は解決必須（`minecraft:zombie`）。解決不能で `Invalid Renderer type` クラッシュ。
- ログに `No animation ... NoSuchElementException` / `undefined weapon motions` / `Invalid Renderer type` が
  出たら綴り・必須フィールドを点検。

## バージョン注意

EpicFight 本体・registry 名（`epicfight:biped/skill/...`）・`faction` 有効値・フィールド名はバージョン依存。
導入する jar 版（既定 `20.14.17-mc1.20.1-forge`）で最終一致を確認すること。使用しているスキル ID は
`EpicFight_Common_Spec.md` §5.3 の検証済み一覧（1.20.1）に基づく。
