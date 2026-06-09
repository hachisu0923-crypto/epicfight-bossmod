# EpicFight MVP Boss — `efbossmvp`

EpicFight 本体導入を前提に、**新規ボスエンティティ**を追加する
**Forge 1.20.1 mod プロジェクト**。現在 2 体のボスを収録：

- **`efbossmvp:dread_knight`** — 剣 1 本で複数のプレイヤースキル（`battojutsu` / `blade_rush` /
  `sweeping_edge` / `dancing_edge` / `greatsword_slam`）を HP フェーズで使い分ける。純データパック方式。
- **`efbossmvp:ronin`** — 打刀デュエリスト。攻撃コンボに加え、**本物の Guard / Parry / カウンター /
  スタミナ**（Epic Fight - Indestructible の `advanced_mobpatch`）と、**Java の反応レイヤ**
  （Step 回避 / Phantom Ascent / Emergency Escape）を持つ「プレイヤースキル相当」のボス。

> 設計根拠は同フォルダの設計仕様書ファミリ（`EpicFight_*_Spec*.md`）と、EpicFight 1.20.1 実ソース
> （`Epic-Fight/epicfight`）・Indestructible 実ソース（`Cyber2049/Epic-Fight---Indestructible`）の検証に基づく。

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

EpicFight は `mods.toml` で `mandatory=true`（ハード依存）。Dread Knight 単体は EpicFight API へ
コンパイル依存しない純データパック方式（`runtimeOnly` で足りる）。ただし **Ronin の層3
（`efcompat/RoninSkillAI`）が EpicFight API を呼ぶため、本プロジェクトでは EpicFight を
`implementation`（compile 依存）にしている**。Dread Knight だけが必要なら `efcompat/` を削除して
`runtimeOnly` に戻せる。

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

## ボス② Ronin（打刀デュエリスト・3 層アーキ）

`efbossmvp:ronin` は「Mob はプレイヤースキルのロジックを実行しない」制約を、**3 層**で“相当”再現する。

```
層1  EpicFight 標準           攻撃モーション（打刀コンボ＋大技）             ← データパック
層2  Indestructible           本物の Guard / Parry / カウンター / スタミナ   ← データパック(advanced_mobpatch)
層3  自作 Java (Forgeイベント) Step 回避(i-frame) / Phantom Ascent / Emergency Escape ← yesman.epicfight API
```

全部 1 ファイル `data/efbossmvp/advanced_mobpatch/ronin.json`（Indestructible 形式）に層1+2 を内包。
層3 は `efcompat/RoninSkillAI.java` が EpicFight patch を読んで駆動する。

**ユーザー指定スキルの対応（本物 / 近似 / Java）**

| 指定スキル | 実現 | 手段 |
|-----------|------|------|
| **Guard / Parrying** | ✅ 本物 | 層2 `custom_guard_motion` + behavior の `guard`/`parry`/`parry_animation` |
| **Revelation**（ブロック/パリィで溜め→反撃） | ✅ 近似 | 層2 behavior の `counter`（相手の予備動作 `attack_level=1` を見て反撃） |
| **スタミナ** | ✅ 本物 | 層2 attributes（`max_stamina` 他、Indestructible） |
| **Technician**（消費減・回復速） | 〜 近似 | 層2 `stamina_cost_multiply` 0.7 / `stamina_regan_*` を優遇 |
| **Sword Master**（攻撃速度） | 〜 近似 | 層2 `counter_speed` 1.2・cooldown 短め |
| **Step（回避）** | ✅ Java | 層3：相手の `attack_level=1` で `biped/skill/step_*` + i-frame |
| **Phantom Ascent（跳躍）** | 〜 Java | 層3：上+前方インパルス + `uchigatana_airslash` で間合い詰め |
| **Emergency Escape** | ✅ Java | 層3：被弾時（recovery 中 or 低 HP）に確率でヒットを無効化し回避へ |

武器は **EpicFight 自身の `epicfight:uchigatana`**（カテゴリ `uchigatana` / 非プレイヤーは `two_hand`）を
`finalizeSpawn` で装備。`combat_behavior` / `custom_guard_motion` / `humanoid_weapon_motions` はすべて
`["uchigatana"]` + `two_hand` で統一している。攻撃アニメ（`mob_uchigatana1/2/3`・`uchigatana_dash`・
`uchigatana_airslash`・`mob_tachi_special`・`skill/battojutsu`）と Step アニメ（`skill/step_*`）は
1.20.1 実ソースで実在確認済み。

> ⚠️ **この層の前提（重要）**
> - **Epic Fight - Indestructible（+ Invincible Lib）が必須**。未導入だと `advanced_mobpatch` が読まれず
>   Ronin は EpicFight 化されない（`mods.toml` でハード依存宣言済み）。
> - **層3 は EpicFight を compile 依存**にする（`build.gradle` で `implementation`）。よって
>   **1.20.1 + EpicFight の dev 環境でのビルドが前提**。EpicFight / Indestructible の API・スキーマは
>   バージョン依存なので、`advanced_mobpatch` のフィールドは導入版同梱 example で、層3 の API シグネチャは
>   コンパイルで最終確認すること。
> - 数値（weight / cooldown / chance / i-frame）は初期値。**ゲーム内で“理不尽にならない”よう調整**前提。
> - **層3 を外しても層1+2（攻撃＋本物ガード/パリィ/スタミナ）は成立**する：
>   `efcompat/` パッケージを削除すればよい（EpicFight の compile 依存も外せる＝`runtimeOnly` に戻す）。

## 構成

```
build.gradle                 ForgeGradle 6 / Forge 1.20.1（EpicFight を implementation、Indestructible/Invincible Lib を runtimeOnly）
settings.gradle              pluginManagement（MinecraftForge / Gradle Plugin Portal）
gradle.properties            mc/forge/mappings/epicfight バージョン・mod メタ
gradlew / gradlew.bat        Gradle wrapper（8.1.1）
gradle/wrapper/              wrapper jar / properties
src/main/java/com/example/efbossmvp/
  EfBossMvp.java             @Mod メイン
  ModEntities.java           EntityType 登録（dread_knight / ronin）
  DreadKnightEntity.java     Monster 継承・AI・属性・剣を装備
  RoninEntity.java           Monster 継承・AI・属性・打刀(epicfight:uchigatana)を装備
  ModItems.java              スポーンエッグ（dread_knight / ronin）
  ModEvents.java             MODバス: 属性/スポーン配置/タブ
  efcompat/RoninSkillAI.java FORGEバス(層3): Step回避 / Phantom Ascent / Emergency Escape（EpicFight API使用）
  client/ClientEvents.java   MODバス(client): バニラ風レンダラ（両ボス）
src/main/resources/
  META-INF/mods.toml         epicfight / indestructible を mandatory=true 宣言
  assets/efbossmvp/lang/     en_us / ja_jp
  data/efbossmvp/epicfight_mobpatch/dread_knight.json     ★Dread Knight 中核
  data/efbossmvp/advanced_mobpatch/ronin.json             ★Ronin 中核（Indestructible 形式）
datapack/                    手動でワールド datapacks/ に置きたい人向けの同内容コピー
  pack.mcmeta                pack_format 15
  data/efbossmvp/epicfight_mobpatch/dread_knight.json
  data/efbossmvp/advanced_mobpatch/ronin.json
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
- **ネットワーク必須**：初回は Gradle 配布物・Forge userdev・Minecraft・EpicFight(Modrinth Maven)・
  Invincible Lib(Modrinth)・Indestructible(CurseMaven) を取得する。
  （オフライン/制限環境では取得できずビルド不可。`gradle.properties` の各バージョン/座標は導入版に合わせる）
- **EpicFight が compile 依存**（Ronin 層3）。dev 環境で `yesman.epicfight.*` が解決できることが前提。

## 導入（実際にプレイへ）

1. `./gradlew build` で出来た `build/libs/efbossmvp-1.0.0.jar` と、**EpicFight 本体 jar**、
   **Epic Fight - Indestructible jar**、**Invincible Lib jar** を `mods/` に入れる
   （Minecraft 1.20.1 / Forge 47.x）。Indestructible 未導入だと Ronin は機能しない。
2. スポーンエッグ（スポーンエッグタブ）、または `/summon efbossmvp:dread_knight ~ ~ ~` /
   `/summon efbossmvp:ronin ~ ~ ~`。Dread Knight はネザライトの剣、Ronin は打刀を持って出現する。

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

### Ronin（層2/層3）固有の罠
- **打刀の mob style は `two_hand`**（プレイヤーのみ `sheath`。非プレイヤーは `Styles.TWO_HAND` に落ちる）。
  `weapon_categories` は `["uchigatana"]`。ここを `one_hand`/`sword` 等にすると combat_behavior が不発。
- **`advanced_mobpatch` のスキーマはバージョン依存**。本 JSON のフィールド名は Indestructible 1.18 実ソース
  （`AdvancedMobpatchReloader`）由来。導入する 20.x で差異があり得るので、未知キーでロード失敗したら
  同梱 example datapack に合わせて修正する。`stamina_regan_*` の綴りは "regan"（"regen" ではない）。
- **Indestructible / Invincible Lib の座標は要確認**：`gradle.properties` の `indestructible_curse_*`
  （projectId 915201 / fileId 7125700 = 20.13.0）と `invinciblelib_version` を、実際に導入する jar に合わせる。
- **層3 は EpicFight API バージョン依存**：`EpicFightCapabilities.getEntityPatch` /
  `LivingEntityPatch#playAnimationSynchronized(AssetAccessor, float)` /
  `getEntityState().getLevel()`（0 free/1 予備/2 接触/3 回復）/ `AnimationManager.byKey` は 1.20.1 実ソース確認済み。
  シグネチャが変わっていたら `RoninSkillAI` を合わせる。コンパイルできなければ `efcompat/` を外して層1+2 運用も可。
- mob には dodge を直接与える機構が Indestructible に**無い**ため、Step/Phantom Ascent/Emergency Escape は
  層3 で実装している（データパックだけでは再現不可）。

## バージョン注意

EpicFight 本体・registry 名（`epicfight:biped/skill/...`）・`faction` 有効値・フィールド名はバージョン依存。
導入する jar 版（既定 `20.14.17-mc1.20.1-forge`）で最終一致を確認すること。使用しているスキル ID は
`EpicFight_Common_Spec.md` §5.3 の検証済み一覧（1.20.1）に基づく。
