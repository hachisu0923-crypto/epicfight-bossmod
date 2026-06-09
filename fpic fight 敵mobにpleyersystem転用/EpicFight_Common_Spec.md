# EpicFight 敵Mob転用 共通仕様書（Common Spec）

> EpicFight のプレイヤー戦闘要素を敵 Mob へ転用する設計ファミリの **共通土台**。
> `epicfight_mobpatch` のフィールド、`combat_behavior` / `conditions` の仕様、中間表現(IR)、`EpicFightBridge`、バージョン互換、用語集など、複数の設計書で共有される基盤情報を 1 箇所に集約する。
> 各設計書（Skill / System / Mimic）は重複記述を避けるため本書を参照する。

**対象環境**

| 項目 | 値 |
|------|------|
| Minecraft | 1.20.1 |
| Modloader | Forge (47.x 系) |
| 前提 / ソフト依存 Mod | EpicFight (`epicfight`) |
| 推奨補助 | GeckoLib 4.x（独自アニメ / 任意）、SmartBrainLib（ボス AI / 任意）、Epic Fight - Indestructible（`advanced_mobpatch` / 任意） |
| 言語 | Java 17 / データパック JSON / Python（解析スクリプト） |
| 権威ある参照元 | Epic Fight 公式 Wiki `https://epicfight-docs.readthedocs.io/` |

---

## 0. この設計ファミリの全体像

| 文書 | 担当領域 | 一言で |
|------|----------|--------|
| **本書** `EpicFight_Common_Spec.md` | 共通基盤 | mobpatch / predicate / IR / Bridge / バージョンの**共有リファレンス** |
| `EpicFight_SkillToMob_Spec_v2.md` | スキル / モーションの解析→転用 | プレイヤースキルの**攻撃モーション**を Mob に撃たせる（静的解析・移植） |
| `EpicFight_PlayerSystemToMob_Spec_v2.md` | 戦闘システムの移植 | **スタミナ・ガード・パリィ・スタン**等の仕組みを Mob に持たせる |
| `EpicFight_MobMimic_Spec.md` | プレイヤースタイルの動的学習 | 周囲プレイヤーの戦い方を**実行時に観察・学習**して反映する |
| `EpicFight_BaseModBehavior_Spec.md` | 本体mod導入時の挙動 | 本体を入れると何が起きるか（プレイヤー自動変換 / Mob自動パッチ / DP上書き規則 / ミラー戦） |

```
                EpicFight_Common_Spec.md（本書／共通基盤）
                          ▲ 参照
        ┌─────────────────┼─────────────────┐
   SkillToMob_v2     PlayerSystemToMob_v2     MobMimic
   （攻撃モーション）   （戦闘システム）        （動的学習）
        静的・移植         状態・システム移植        実行時・観察
```

旧版 `EpicFight_SkillToMob_Spec.md` / `EpicFight_PlayerSystemToMob_Spec.md` は比較のため保持。最新の設計は `_v2` 系を正とする。

`EpicFight_BaseModBehavior_Spec.md` は本体mod導入時の実行時挙動（プレイヤー自動変換・Mob自動パッチ・DP上書き規則）を記述する**前提リファレンス**で、上図の転用3本柱の土台となる（本書と並ぶ基盤）。

---

## 検証ステータス凡例（全文書共通）

本ファミリでは、記述の確からしさを以下の記号で示す。

| 記号 | 意味 |
|:---:|------|
| ✅ | **確認済み**。一次情報（公式 Wiki / 実データパック / ソース）で裏付け済み |
| ⚠️ | **要注意 / 要ゲーム内テスト**。構造は妥当だが実挙動（コリジョン・体感・ダメージ）はゲーム内検証が必要 |
| 🔍 | **要追加検証**。バージョン依存、または一次情報で未確定。実装前に対象バージョンで確認すること |

> 原則：API クラス名・メソッド名・組み込み `WeaponType` / アニメ識別子はすべて **バージョン依存** とみなす。確定情報は対象バージョンの公式 Wiki / GitHub ソースで照合する。揺れる箇所は `EpicFightBridge`（§7）に隔離する。

---

## 用語集

| 用語 | 説明 |
|------|------|
| **EntityPatch** | EpicFight が各エンティティに付与する拡張オブジェクト（capability）。アニメ・armature・戦闘判定の入口 |
| **PlayerPatch / MobPatch** | それぞれプレイヤー用 / Mob 用の EntityPatch。`HumanoidMobPatch` は人型 Mob 用で武器持ち替えに対応（転用の主役） |
| **LivingMotion** | idle / walk / chase など「状態に対応するアニメの種類」を表す列挙的な概念 |
| **AttackAnimation** | 攻撃モーション。コリジョン（判定ボックス）・フェーズ時刻・ダメージ係数を内蔵する |
| **フェーズ（phase）** | 攻撃アニメの時間区分：先読み(antic) / 予備動作(preDelay) / 判定発生(contact) / 硬直(recovery) |
| **combat_behavior** | mobpatch 内の「いつ・どの条件で・どのアニメで攻撃するか」を定義するブロック |
| **predicate（条件詞）** | `combat_behavior` の発動条件。プレイヤーのキー入力の代替（間合い・HP・スタミナ等） |
| **faction（陣営）** | 同陣営は同士討ちしない。`undead` / `illager` 等 |
| **IR（中間表現）** | 解析結果を保持する転用ルート非依存の中間 JSON（§6） |
| **EpicFightBridge** | バージョンで揺れる EpicFight API 呼び出しを 1 箇所に隔離するソフト依存ラッパ（§7） |
| **advanced_mobpatch** | アドオン Epic Fight - Indestructible が追加する拡張 mobpatch。スタミナ・ガード・スタンシールド等を Mob に付与（System 文書で詳述） |

---

## 1. クラス継承階層（参考・バージョン依存）🔍

```
EntityPatch<T>                         … EpicFight 拡張の最上位
└─ LivingEntityPatch<T>                … 生物共通（アニメ・armature・当たり判定・stun・attribute）
   ├─ PlayerPatch<P extends Player>
   │   ├─ LocalPlayerPatch             … クライアント自機
   │   ├─ ServerPlayerPatch            … サーバー権威・スキル実行・スタミナ管理
   │   └─ RemoteClientPlayerPatch      … 他プレイヤー描画
   └─ MobPatch<M extends Mob>
       └─ HumanoidMobPatch<M>          … 人型・武器持ち替え対応（転用の主役）
```

> 🔍 クラス名・階層は対象バージョンの `yesman.epicfight.world.capabilities.entitypatch.*` で確認すること。

### 1.1 `LivingEntityPatch` が **既に持つ**もの（= Mob でもそのまま使える土台）

| 機能 | 説明 |
|------|------|
| アニメーション再生 | `playAnimationInstantly(...)` 等。Mob でも可 |
| armature / モデル | スケルトン構造 |
| 当たり判定・コリジョン | 攻撃判定 |
| stun（基本） | 被弾硬直 |
| attribute（impact / armor_negation / max_strikes 等） | EpicFight 戦闘ステータス |

### 1.2 `PlayerPatch`（特に `ServerPlayerPatch`）**だけ**が持つもの（= 転用対象）

| 機能 | プレイヤーでの役割 | Mob への扱い | 担当文書 |
|------|--------------------|--------------|----------|
| `SkillContainer` 配列 + `SkillSlot` | スキルの保持・発動 | **破棄**（Mob は AI で攻撃選択）。攻撃モーションのみ転用 | Skill |
| スタミナ（残量・回復） | 行動コスト | **移植**（層2 / 層3） | System |
| 戦闘スタイル管理 | 武器に応じた挙動 | 層1 `humanoid_weapon_motions` で再現 | System |
| コンボカウンタ・コンボ繋ぎ | 連撃 | 層1 `combat_behavior` の `behavior_series` で再現 | System |
| 入力状態（攻撃/ガード/回避キー） | 操作 | **代替**（`conditions` / AI Goal） | System |
| ロックオン | 照準 | Mob の `target` で代替 | System |

### 1.3 取得と橋渡し

```java
// バニラ Entity から EpicFight パッチを取得（存在チェック必須）
LivingEntityPatch<?> patch =
    EpicFightCapabilities.getEntityPatch(entity, LivingEntityPatch.class);
```

すべて `EpicFightBridge`（§7）経由でソフト依存化する。

---

## 2. `epicfight_mobpatch` 全フィールドリファレンス

EpicFight 標準のデータパックで、**コードを書かずに** 敵 Mob へ戦闘能力を付与する。出典：Epic Fight 公式 Wiki「Custom entity datapack」。

- 配置：`data/<modid>/epicfight_mobpatch/<entityname>.json` ✅
  （`<entityname>` は対象エンティティのレジストリ名。`/summon` の Tab 補完で確認可能）
- `pack.mcmeta` の `pack_format`：**1.20.1 = `15`** ✅（参考：1.21.1 = 48）

### 2.1 主要フィールド

| フィールド | 意味 | 状態 |
|------------|------|:---:|
| `model` | 使用モデル。**リソースロケーション表記**（例 `epicfight:entity/biped_old_texture`） | ✅ |
| `armature` | スケルトン（例 `epicfight:entity/biped`） | ✅ |
| `renderer` | レンダラ（バニラ登録名、例 `minecraft:zombie`） | ✅ |
| `isHumanoid` | `true` で **持っている武器に応じてモーションを切替**（武器別スキル転用の鍵） | ✅ |
| `faction` | 同陣営は同士討ちしない。`enderman` / `piglins` / `wither` / `neutral` / `undead` / `illager` / `villager` | ✅ |
| `attributes` | `impact`（スタン延長）/ `armor_negation`（防御貫通 %）/ `max_strikes`（多段ヒット数）/ `chasing_speed`（追跡速度）/ `scale`（サイズ） | ✅ |
| `default_livingmotions` | `idle` / `walk` / `chase` / `fall` / `death` / `mount` の各モーション | ✅ |
| `stun_animations` | `short` / `long` / `knockdown` / `fall` の被弾モーション | ✅ |
| `combat_behavior` | **攻撃ムーブ定義**（§3） | ✅ |
| `humanoid_weapon_motions` | `isHumanoid:true` 時の、武器カテゴリ別 living motion | ✅ |

> 📌 `model` / `armature` は公式 Wiki「Mob Capabilities Editor」では概念図（model＝形状、armature＝骨格、renderer＝描画）として説明されるが、**実 JSON ではリソースロケーション文字列**を取る。実データパック例（他 Mod のボスパッチ等）で確認済み ✅。

簡易設定として `{"preset": "minecraft:creeper"}`（バニラクラスを継承するエンティティ向け）、無効化として `{"disabled": true}` も使える。

### 2.2 最小サンプル

```jsonc
{
  "model": "epicfight:entity/biped_old_texture",
  "armature": "epicfight:entity/biped",
  "renderer": "minecraft:zombie",
  "isHumanoid": true,
  "faction": "undead",
  "attributes": { "impact": 0.5, "armor_negation": 0.0, "max_strikes": 1, "chasing_speed": 1.0, "scale": 1.0 },
  "default_livingmotions": {
    "idle": "epicfight:biped/living/idle",
    "walk": "epicfight:biped/living/walk",
    "chase": "epicfight:biped/living/walk",
    "death": "epicfight:biped/living/death"
  },
  "stun_animations": {
    "short": "epicfight:biped/combat/hit_short",
    "long": "epicfight:biped/combat/hit_long",
    "knockdown": "epicfight:biped/combat/knockdown"
  },
  "combat_behavior": [ /* §3 */ ]
}
```

---

## 3. `combat_behavior` / `behavior_series` 構造

### 3.1 behavior series

| キー | 意味 | 状態 |
|------|------|:---:|
| `weight` | このシリーズが選ばれる確率 `weight / Σweight` | ✅ |
| `canBeInterrupted` | 動作シリーズを中断できるか | ✅ |
| `looping` | 中断時に現在の動作を保持するか | ✅ |
| `cooldown` | 再使用までの tick | ✅ |
| `behaviors` | 実際の (条件, アニメ) のリスト | ✅ |

各 `combat_behavior` 要素は `weapon_categories`（対象武器カテゴリ）と `style`、その下に `behavior_series` 配列を持つ。

### 3.2 構造サンプル

```jsonc
{
  "weapon_categories": ["sword", "axe"],
  "style": "one_hand",
  "behavior_series": [
    {
      "weight": 70.0, "canBeInterrupted": true, "looping": true,
      "behaviors": [
        { "conditions": [ {"predicate":"within_distance","min":0.0,"max":2.2} ],
          "animation": "epicfight:biped/combat/mob_onehand1" },
        { "conditions": [ {"predicate":"within_distance","min":0.0,"max":2.2} ],
          "animation": "epicfight:biped/combat/mob_onehand2" }
      ]
    }
  ]
}
```

---

## 4. `conditions` predicate リファレンス（発動条件＝プレイヤー入力の代替）

プレイヤーの「キー入力で発動」は、Mob では distance（間合い）・health（HP 連動）・random_chance（ばらけさせる）等の組合せで再現する。

### 4.1 標準 predicate（EpicFight 本体）✅

| predicate | 判定内容 | 引数 |
|-----------|----------|------|
| `random_chance` | 乱数がしきい値超か | `chance`: double |
| `within_eye_height` | Y 距離が攻撃者の目線高以下か | — |
| `within_distance` | 対象との距離が範囲内か | `min`, `max`: double |
| `within_angle` | 対象への角度が範囲内か | `min`, `max`: double |
| `within_angle_horizontal` | Y 軸方向角度が範囲内か | `min`, `max`: double |
| `health` | 自身の HP 条件 | `health`: double, `comparator`: `greater_absolute`/`less_absolute`/`greater_ratio`/`less_ratio` |

### 4.2 拡張 predicate（Epic Fight - Indestructible アドオン）✅

| predicate | 判定内容 | 引数 | 使いどころ |
|-----------|----------|------|------------|
| `stamina` | **自分**のスタミナ | `stamina`: double, `comparator`: `greater_ratio` 等 | スタミナ管理した攻撃選択 |
| `attack_level` | **相手**の攻撃フェーズ（free:0 / preDelay:1 / contact:2 / recovery:3） | `min`, `max` | パリィ/カウンター（相手の preDelay 中にガード or 反撃） |
| `guard_break` | 相手がガードブレイク中か | `invert`: bool | 崩した相手に追撃 |
| `knock_down` | 相手がノックダウン中か | `invert`: bool | ダウン追撃 / 起き攻め |
| `using_item` | 相手がアイテム使用中か | `edible`: bool（食料/ポーション限定） | 回復中を狙う |
| `phase` | behavior motion が設定したカスタムフェーズ | `min`, `max` | 多段ギミック・フェーズ連動 |

> 拡張 predicate は Indestructible 導入時のみ利用可。詳細・用例は `EpicFight_PlayerSystemToMob_Spec_v2.md` §5。

---

## 5. 武器カテゴリ・style と内蔵 Mob 攻撃アニメ

### 5.1 許可値 ✅

- `weapon_categories`：`AXE, FIST, GREATSWORD, HOE, PICKAXE, SHOVEL, SWORD, UCHIGATANA, SPEAR, TACHI, TRIDENT, LONGSWORD, DAGGER, SHIELD, RANGED`
- `style`：`one_hand`, `two_hand`, `common`

### 5.2 EpicFight 内蔵 Mob 攻撃アニメ（武器別・追加アセット不要）

| 武器カテゴリ | 流用できる Mob 攻撃アニメ（例） |
|--------------|----------------------------------|
| sword (one_hand) | `BIPED_MOB_ONEHAND1` / `BIPED_MOB_ONEHAND2` |
| greatsword | `BIPED_MOB_GREATSWORD` |
| longsword | `BIPED_MOB_LONGSWORD1` / `2` |
| tachi | `BIPED_MOB_TACHI` |
| uchigatana | `BIPED_MOB_UCHIGATANA1` 〜 `3` |
| spear | `BIPED_MOB_SPEAR_ONEHAND` / `BIPED_MOB_SPEAR_TWOHAND1`〜`3` |
| dagger | `BIPED_MOB_DAGGER_ONEHAND1`〜`3` / `BIPED_MOB_DAGGER_TWOHAND1`〜`2` |
| dual sword | `BIPED_MOB_SWORD_DUAL1`〜`3` |
| ranged | `BIPED_MOB_THROW` |

> 📌 **アニメ ID とデータパック参照パスの対応**：データパックから参照する際の ID は登録時のリソースパス。内蔵 mob 戦闘アニメは `epicfight:biped/combat/...`（例 `epicfight:biped/combat/mob_onehand1`）として参照できることを実データパック例で確認 ✅。`BIPED_MOB_*` 定数名 → リソースパスの完全対応表は対象バージョンの `Animations` クラスで突合する 🔍。

### 5.3 検証済みプレイヤースキルアニメ → 参照パス（EpicFight 1.20.1）✅

`combat_behavior` の `animation` には、プレイヤースキルのアニメ（すべて `AttackAnimation`＝コリジョン内蔵）を **`epicfight:biped/skill/<名>`** で指定できる。解決は `AnimationManager.byKey()` で行われ **mob 専用ホワイトリストは無い**ため、Mob でも再生され内蔵判定でダメージが出る（`MobPatchReloadListener` / `AnimationManager.java` で確認）。

| 定数（`Animations`） | データパック参照パス | 備考 |
|----------------------|----------------------|------|
| `SWEEPING_EDGE` | `epicfight:biped/skill/sweeping_edge` | `EXTRA_COLLIDERS=1`（広範囲） |
| `DANCING_EDGE` | `epicfight:biped/skill/dancing_edge` | 3 フェーズ |
| `BATTOJUTSU` | `epicfight:biped/skill/battojutsu` | `ColliderPreset.BATTOJUTSU` |
| `BLADE_RUSH_COMBO1`〜`3` | `epicfight:biped/skill/blade_rush_combo1`〜`3` | 掴み連携はロジック側で再現されない |
| `METEOR_SLAM`（変数名） | `epicfight:biped/skill/greatsword_slam` | ⚠️ **変数名とパスが不一致**。`meteor_slam` は存在しない |

> ⚠️ **綴り厳守 / クラッシュ要因**：registry 名が 1 文字でも違うと `byKey()` が null を返し `NoSuchElementException` で **mobpatch ロードが失敗（起動時クラッシュ）**。特に `METEOR_SLAM` は登録パスが `greatsword_slam` である点に注意。
>
> 🔍 **GUI 既知バグ**：公式データパックエディタの combat/stun 選択リストにスキルアニメが表示されない（[issue #1983](https://github.com/Epic-Fight/epicfight/issues/1983)）。registry 名は `Animations` クラスから拾って **JSON 直書き**するのが確実。
>
> ⚠️ **転用の上限**：再生されるのは「モーション＋アニメ内蔵の当たり判定」まで。落下距離による威力スケール（旧 Meteor Slam）・掴み連携・スタミナ/クールダウン連動などスキル固有ロジックは `ServerPlayerPatch` 側にあり、Mob では発火しない（`instanceof PlayerPatch` ガードで安全にフォールバック）。

---

## 6. 中間表現（IR）スキーマ（基底版）

jar 解析結果を保持する、転用ルート非依存の中間 JSON。各文書がフィールドを拡張する：

- `EpicFight_SkillToMob_Spec_v2.md` … `skills[].player_only_traits`（スタミナ・CD 等のプレイヤー固有要素）
- `EpicFight_PlayerSystemToMob_Spec_v2.md` … `system_params`（スタミナ・スタンシールド・ガード設定）

```json
{
  "source_mod": "examplemod",
  "skills": [
    {
      "id": "examplemod:flame_slash",
      "origin_category": "weapon_innate",
      "animation": {
        "path": "examplemod:biped/skill/flame_slash",
        "type": "AttackAnimation",
        "total_ticks": 28,
        "phases": [
          { "antic": 0.0, "preDelay": 0.30, "contact": 0.45, "recovery": 0.95 }
        ],
        "collider": "toolR",
        "damage_multiplier": 1.8,
        "stun": "long"
      },
      "weapon_category": "sword",
      "style": "one_hand"
    }
  ]
}
```

- `phases` … `AttackAnimation` のフェーズ時刻（先読み / 予備動作 / 判定発生 / 硬直）。Mob のヒット感を決める ⚠️（実値はゲーム内で調整）。
- `collider` / `damage_multiplier` … コード依存のためヘッドレスでは確定不能 ⚠️。IR の数値は初期推定値とする。

---

## 7. `EpicFightBridge`（ソフト依存ラッパ）

バージョンで揺れる EpicFight / Indestructible API を 1 箇所に隔離する。**全文書共通の方針**。

- 依存は `compileOnly` + `mandatory=false`（`mods.toml`）。Indestructible も任意依存として扱う
- すべての API 呼び出しを `ModList.get().isLoaded("epicfight")`（/ `"indestructible"`）ガードと `try/catch(Throwable)` 境界で包む
- 未導入・バージョン不一致時はバニラ挙動へフォールバック

```java
public final class EpicFightBridge {
    public static boolean loaded()        { return ModList.get().isLoaded("epicfight"); }
    public static boolean indestructible(){ return ModList.get().isLoaded("indestructible"); }

    public static void playSkillMotion(LivingEntity entity, String animKey) {
        if (!loaded()) return;
        try {
            var patch = EpicFightCapabilities.getEntityPatch(entity, LivingEntityPatch.class);
            if (patch != null) patch.playAnimationInstantly(resolve(animKey));
        } catch (Throwable t) {
            // バージョン不一致・API 変更を握りつぶし、バニラ挙動へフォールバック
        }
    }
    // resolve(): AnimationManager.byKey(registryName) で AnimationAccessor を解決（1.20.1 / 1.21 共通）
}
```

### 7.1 隔離すべき主な揺れ 🔍

| 揺れる箇所 | 1.20.1 Forge | 1.21.1 NeoForge |
|------------|--------------|-----------------|
| アニメ参照 | `AnimationAccessor<? extends StaticAnimation>`（1.20.1 で既に一般化。`builder.nextAccessor(...)` / `AnimationManager.byKey(name)`） | 同（`AnimationAccessor<T>`） |
| アニメ登録 | `@Mod.EventBusSubscriber` + **MOD バス明示** | `@EventBusSubscriber` |
| パッチ登録 | `EntityPatchRegistryEvent`（MOD バス） | 同 |
| `initAnimator` 引数 | `Animator` 系（要確認） | `Animator` |
| `PlayerPatch` 状態取得 | `getStamina` 等の有無・名称は要確認 | 同 |

---

## 8. バージョン互換性（集約）

| 項目 | 1.20.1 Forge | 1.21.1 NeoForge | 状態 |
|------|--------------|-----------------|:---:|
| データパック `pack_format` | **15** | 48 | ✅ |
| アニメ参照 API | `AnimationAccessor<? extends StaticAnimation>`（`AnimationManager.byKey`）。1.20.1 で既に一般化 | 同 `AnimationAccessor<T>` | ✅ |
| イベント登録 | `@Mod.EventBusSubscriber` + MOD バス明示 | `@EventBusSubscriber` | 🔍 |
| イベントパッケージ | `yesman.epicfight.api.forgeevent` | `yesman.epicfight.api.neoevent` | 🔍 |

### 8.1 Indestructible 互換 🔍

- 対応：1.16.5 / 1.18.2 / 1.19.2 / 1.20.1（Forge）
- **EFM（Epic Fight 本体）16.6.5・18.3.8 以降のみサポート**、それ未満は非対応
- `advanced_mobpatch` パス：最新版は `data/<modid>/advanced_mobpatch/...`（旧版は別パス）。導入する Indestructible バージョンに合わせる

---

## 付録：参照（一次情報）

- Epic Fight 公式 Wiki トップ：`https://epicfight-docs.readthedocs.io/`
  - Getting started（API・エンティティパッチ・スキルスロット）：`/API/Starting/`
  - Custom entity datapack（`epicfight_mobpatch` 仕様）：`/Guides/Entities/page1/`
  - Mob Capabilities Editor：`/Guides/Entities/page2/`
  - Skills（スキル分類）：`/Misc/Gameplay/skills/`
  - Porting 1.20.1 → 1.21.1：`/API/Porting/Porting-from-1.20.1-to-1.21.1/`
- EpicFight 本体ソース（クラス照合用）：`https://github.com/Epic-Fight/epicfight`
- Epic Fight - Indestructible（`advanced_mobpatch` / 追加 predicate / ガードアニメ）
  - CurseForge：`https://www.curseforge.com/minecraft/mc-mods/epic-fight-indestructible`
  - GitHub（tutorial / example）：`https://github.com/Cyber2049/Epic-Fight---Indestructible`
