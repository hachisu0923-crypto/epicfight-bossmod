# EpicFight プレイヤースキルの敵Mob転用 設計仕様書 v2

> EpicFight のプレイヤー用スキル / モーションを **解析** し、敵 Mob が使用できるよう **転用** する仕組みの設計書。
> mod ファイル（jar / データパック）からの仕様・モーション抽出、および「データパック方式」「Java 方式」両面での転用、外部データを使った最適化までを扱う。
>
> **v2 の変更点**：共通仕様を [`EpicFight_Common_Spec.md`](EpicFight_Common_Spec.md) に集約し重複を削減。未完成コード例を補完し、jar→IR→データパックの一気通貫サンプルを追加。一次情報で裏付けた箇所を ✅⚠️🔍 で明示（凡例は Common）。

**前提**：本書は [`EpicFight_Common_Spec.md`](EpicFight_Common_Spec.md) を土台とする。`epicfight_mobpatch` のフィールド・`combat_behavior` 構造・`conditions` predicate・武器カテゴリ・IR 基底・`EpicFightBridge`・バージョン互換は **Common を参照**。本書はスキル転用に固有の論点のみを扱う。

**関連ドキュメント**

| ドキュメント | 役割 | 本書との関係 |
|--------------|------|--------------|
| [`EpicFight_Common_Spec.md`](EpicFight_Common_Spec.md) | 共通基盤 | mobpatch / predicate / IR / Bridge の参照元 |
| [`EpicFight_PlayerSystemToMob_Spec_v2.md`](EpicFight_PlayerSystemToMob_Spec_v2.md) | 戦闘システム移植 | 攻撃**以外**（スタミナ・ガード等）を担当。本書と相補 |
| [`EpicFight_MobMimic_Spec.md`](EpicFight_MobMimic_Spec.md) | プレイヤースタイルの**動的学習** | **本書の姉妹編**。あちらは「動的学習」、本書は「静的解析・移植」 |
| [`EpicFight_BaseModBehavior_Spec.md`](EpicFight_BaseModBehavior_Spec.md) | 本体mod導入時の挙動 | **土台**。本体導入時のプレイヤー自動変換・Mob自動パッチ・DPが本体内蔵パッチを上書きする規則 |
| `docs/epicfight/14` 武器ムーブセットのボス転用 | 武器→ボスへの moveset 移転 | **本書の前段**。武器単位の転用を扱う |

---

## 目次

1. [全体像](#1-全体像)
2. [EpicFight スキル体系の理解（解析対象）](#2-epicfight-スキル体系の理解解析対象)
3. [mod ファイル解析パイプライン](#3-mod-ファイル解析パイプライン)
4. [転用ルート A：データパック方式（推奨）](#4-転用ルート-aデータパック方式推奨)
5. [転用ルート B：Java / 解析方式](#5-転用ルート-bjava--解析方式高度カスタム制御)
6. [一気通貫サンプル（jar→IR→データパック）](#6-一気通貫サンプルjarir データパック)
7. [外部データの活用と最適化](#7-外部データの活用と最適化)
8. [実装マイルストーン](#8-実装マイルストーン)
9. [既知の課題・検証不能項目](#9-既知の課題検証不能項目)

---

## 1. 全体像

### 1.1 本書が解く問題

EpicFight のプレイヤーは、武器コンボに加えて **スキル**（Sweeping Edge、Battojutsu、Blade Rush、Meteor Slam 等）を使える。これらの派手なモーションを **敵 Mob に使わせたい**。
ただし「スキルオブジェクトをそのまま Mob で実行する」のは構造的に困難なため、本書は **スキルを構成要素に分解し、Mob が扱える形（アニメーション + 戦闘行動定義）へ再構成する** アプローチを取る。

### 1.2 なぜ「スキルの直接移植」は難しいのか 🔍

EpicFight のスキルはプレイヤー側 (`PlayerPatch`) に強く結合している。

- スキルは `SkillContainer` / `SkillSlot` で管理される
- `SkillContainer` のサーバー側実行経路（`getServerExecutor` 相当）は **`ServerPlayerPatch` へのキャスト** を前提とする
- Mob 側の `MobPatch` / `HumanoidMobPatch` には `SkillContainer` が標準で存在しない

→ スキルインスタンスを Mob から `execute` しようとすると `ClassCastException` 等のクラッシュ要因になる。**スキルの「ロジック層」をそのまま借りるのは高リスク**。

> ⚠️ クラス名・メソッド名（`SkillContainer`, `getServerExecutor`, `ServerPlayerPatch` 等）は **バージョン依存**。実装前に対象 EpicFight バージョンの `yesman.epicfight.skill` を確認すること。

### 1.3 現実的な転用戦略：スキルを 3 層に分解する

| 層 | スキルにおける実体 | Mob への転用方法 |
|----|--------------------|------------------|
| **モーション層** | `AttackAnimation` / `StaticAnimation`（見た目・判定タイミング） | データパックの `combat_behavior` で直接再生 / Java で `AttackAnimation` 再構築 |
| **効果層** | ダメージ・コリジョン・ノックバック・状態異常（多くは `AttackAnimation` に内蔵） | モーション層と同時に付与。スタミナ・CD 等のプレイヤー固有要素は **破棄して Mob 用に再設計** |
| **発動条件層** | プレイヤーのキー入力 / スタミナ / スキルスロット | **Mob 用 AI 条件**（`conditions`：間合い・HP・確率）へ置換 |

**結論**：モーション層と効果層を抽出し、発動条件層を Mob 用 AI へ置き換える。スキルの「プレイヤー専用配管（スロット・スタミナ・入力）」は捨てる。

### 1.4 二つの実装ルート

| ルート | 概要 | コスト | 制御の自由度 | 推奨用途 |
|--------|------|--------|--------------|----------|
| **A. データパック方式** | `epicfight_mobpatch` JSON で Mob に戦闘行動を定義 | 低（コード不要） | 中 | 大半の敵 Mob、量産、まず最初に試す |
| **B. Java / 解析方式** | jar 解析でアニメ・パラメータを抽出し独自 `AttackAnimation` / `CombatBehaviors` を構築 | 高 | 高 | ボス、フェーズ遷移、独自効果が必要な場合 |

両ルートは排他ではなく、**A をベースに、足りない制御だけ B で補う** のが効率的。

---

## 2. EpicFight スキル体系の理解（解析対象）

公式 Wiki の分類に基づく。各スキルの「性質」によって転用の容易さが変わる。

### 2.1 スキルカテゴリと Mob 転用適性

| カテゴリ | 代表スキル | 性質 | Mob 転用適性 | 転用方法 |
|----------|-----------|------|:---:|----------|
| **Weapon Innate**（武器固有） | Sweeping Edge, Dancing Edge, Battojutsu, Blade Rush, Eviscerate, Tsunami | 攻撃モーション | ◎ | `AttackAnimation` を `combat_behavior` に直接指定 |
| **Revelation**（天啓） | Meteor Slam, Revelation | 攻撃モーション | ○ | 同上。空中・スタック条件は AI 条件へ置換 |
| **Dodge**（回避） | Roll, Step | 入力リアクション | △ | 回避アニメ再生 + 専用回避 AI が必要 |
| **Guard**（ガード） | Guard, Parrying, Impact Guard | 被弾リアクション | △ | ガードアニメ再生 + 被弾割込み AI が必要（→ System 文書） |
| **Mobility**（移動） | Demolition Leap, Phantom Ascent | 移動補助 | △ | 限定的。移動 AI と組み合わせ |
| **Passive**（パッシブ） | Berserker, Sword Master, Stamina Pillager, Forbidden Strength | ステータス補正 | ◎（別手段） | スキル移植不要。`attributes` や属性 modifier で **数値的に再現** |

**重要な指針**

- **◎ Weapon Innate / Revelation** … 攻撃モーション系。最も転用が容易で効果も大きい。最優先で扱う。
- **◎ Passive** … 移植する必要が**ない**。Berserker（低 HP で火力上昇）等は Mob の attribute modifier やボスのフェーズ強化で代替する方が安全。
- **△ Dodge / Guard / Mobility** … プレイヤー入力 / 被弾に反応する性質のため、Mob では AI Goal の実装が別途必要。Guard / Parry の Mob 化は [`EpicFight_PlayerSystemToMob_Spec_v2.md`](EpicFight_PlayerSystemToMob_Spec_v2.md) §5 が担当。アニメ自体は再生可能（`BIPED_ROLL_FORWARD`, `BIPED_BLOCK` 等）。

### 2.2 スキルの内部クラス（解析時に押さえる）🔍

| クラス（参考） | パッケージ | 役割 |
|----------------|-----------|------|
| `Skill` | `yesman.epicfight.skill` | スキル基底 |
| `SkillContainer` | 同上 | プレイヤーのスキル保持・実行（**ServerPlayerPatch 結合**） |
| `SkillSlot` / `SkillSlots` | 同上 | スロット定義 |
| `SkillCategories` | 同上 | カテゴリ（PASSIVE / IDENTITY / DODGE / GUARD 等） |
| `AttackAnimation` | `yesman.epicfight.api.animation.types` | スキルが再生する攻撃アニメ（コリジョン・フェーズ内蔵） |

> ⚠️ いずれも **バージョン依存**。本書の転用では基本的にスロット拡張は不要（Mob はスロットを使わないため）。

### 2.3 スキル / 武器のデータパック上書き（解析・最適化の対象）

EpicFight はスキルや武器のパラメータをデータパックで上書きできる：

- スキルのスタミナ消費・クールダウン・倍率 等のパラメータ JSON
- 武器タイプ定義（`weapon_categories` のコンボ割り当て）

→ 既存 Mod の jar 内データパックを読み取れば、「どの武器がどのコンボ / スキルを持つか」を**コードを逆コンパイルせずに**把握できる場合がある。

---

## 3. mod ファイル解析パイプライン

「modファイルを読み込み仕様や motion を解析し転用する」中核。`mc-boss-analyzer` / `mc-texture-gen` の解析フローと統合する。

### 3.1 解析対象（jar / データパック内の場所）

| パス（典型） | 内容 | 抽出物 | 逆コンパイル要否 |
|--------------|------|--------|:---:|
| `data/<modid>/animmodels/animations/**.json` 等 | アニメーションのキーフレーム | モーション本体、尺、ボーン軌道 | 不要 |
| `data/<modid>/epicfight_mobpatch/*.json` | 既存の Mob 戦闘定義 | combat_behavior, livingmotions, attributes の **実例** | 不要 |
| `data/<modid>/.../weapon` 系 JSON | 武器タイプ / カテゴリ割当 | weapon_category ↔ コンボ対応 | 不要 |
| スキルパラメータ JSON（あれば） | スタミナ・CD・倍率 | バランス値 | 不要 |
| `*.class`（`Animations` 相当 / `Skill` サブクラス） | アニメ登録、`AttackAnimation` のコリジョン・フェーズ時刻、ダメージ係数 | フェーズタイミング、判定ボックス、付随効果 | **必要** |

> 📌 アニメ ID とデータパック参照パスの突合方法は Common §5 を参照。内蔵 mob 戦闘アニメは `epicfight:biped/combat/...` で参照可 ✅。

### 3.2 解析フェーズ

```
[Phase 0] 入力受付
    jar / zip を展開 → ファイル種別を分類（animation json / mobpatch json / class）

[Phase 1] 構造スキャン
    - epicfight_mobpatch/*.json を列挙 → 既存 combat_behavior を収集
    - アニメーション json を列挙 → モーション ID 一覧化
    - 必要なら .class を逆コンパイル → AttackAnimation のフェーズ / コリジョン / ダメージを抽出

[Phase 2] モーション仕様の正規化
    抽出物を「中間表現(IR)」へ変換（Common §6 + 本書 3.3 の拡張）

[Phase 3] 転用ターゲットへのマッピング
    IR → 転用先 Mob の epicfight_mobpatch JSON（ルートA）
       → もしくは Java の AttackAnimation / CombatBehaviors（ルートB）
```

### 3.3 IR のスキル拡張（`player_only_traits`）

Common §6 の基底 IR に、スキル固有の **プレイヤー固有要素** を加える：

```json
{
  "id": "examplemod:flame_slash",
  "origin_category": "weapon_innate",
  "animation": { "...": "Common §6 基底のまま" },
  "weapon_category": "sword",
  "style": "one_hand",
  "player_only_traits": {
    "stamina_cost": 6,
    "cooldown_ticks": 200
  }
}
```

- `player_only_traits` … スタミナ・CD 等の **プレイヤー固有要素**。Mob では破棄し、`cooldown`（tick）として AI 用に**再設計**する（プレイヤー値の流用は避ける）。

### 3.4 解析スクリプト（参考・Python）

```python
import zipfile, json, pathlib

def scan_epicfight_jar(jar_path: str) -> dict:
    ir = {"source_mod": None, "mobpatches": [], "animations": [], "skills": []}
    with zipfile.ZipFile(jar_path) as z:
        for name in z.namelist():
            # 既存 mobpatch（最良の転用テンプレ）
            if "/epicfight_mobpatch/" in name and name.endswith(".json"):
                data = json.loads(z.read(name))
                ir["mobpatches"].append({"file": name, "data": data})
                # combat_behavior からアニメ ID を逆引き収集
            # アニメーション本体
            elif "/animations/" in name and name.endswith(".json"):
                ir["animations"].append(name)
    return ir

# 注意: AttackAnimation のフェーズ時刻・コリジョン・ダメージ係数は
#       多くの場合コード(.class)内に定義されており、JSON だけでは取得不能。
#       その場合は逆コンパイル結果から手動 / 半自動で IR に補完する。
```

> ⚠️ **検証不能項目**：`AttackAnimation` のコリジョン形状やダメージ計算はコード依存で、ヘッドレス環境では実挙動を確認できない。IR の数値は「初期推定値」とし、ゲーム内テストで調整する前提で記述する。

---

## 4. 転用ルート A：データパック方式（推奨）

EpicFight 標準の `epicfight_mobpatch` で、**コードを書かずに** 敵 Mob へ戦闘行動を付与する。
基本フィールド・`combat_behavior` 構造・predicate 一覧は **Common §2〜§4 を参照**。本節はスキルモーションを撃たせる核心のみ扱う。

### 4.1 プレイヤースキルのモーションで攻撃させる（核心）

EpicFight 本体にはプレイヤースキルのアニメ定数が揃っている（`SWEEPING_EDGE`, `DANCING_EDGE`, `BATTOJUTSU`, `BLADE_RUSH_*`, `METEOR_SLAM`, `TSUNAMI`, `EVISCERATE_*` 等）。
これらの **登録パス**（`epicfight:biped/skill/<名>`）を `combat_behavior` の `animation` に指定すれば、Mob がそのモーションで攻撃する（モーションに内蔵された判定でダメージも発生する）。
**定数名とパスが一致しない例がある**点に注意（例：`METEOR_SLAM` の実パスは `epicfight:biped/skill/greatsword_slam`）。検証済みの対応表は Common §5.3 を参照。

```jsonc
// 例: ゾンビが「素手の自動コンボ」＋「HP半減でスキルモーション」を使う
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
  "combat_behavior": [
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
        },
        {
          // ★ HP 50% 以下で、プレイヤースキル相当のモーションを撃つ
          "weight": 30.0, "canBeInterrupted": false, "looping": false, "cooldown": 160,
          "behaviors": [
            { "conditions": [
                {"predicate":"health","health":0.5,"comparator":"less_ratio"},
                {"predicate":"within_distance","min":0.0,"max":3.0}
              ],
              "animation": "epicfight:biped/skill/sweeping_edge" }
          ]
        }
      ]
    }
  ]
}
```

> ✅ **確認済み（EpicFight 1.20.1 ソース）**：プレイヤースキルアニメの登録パスは **`epicfight:biped/skill/<名>`**（例 `epicfight:biped/skill/sweeping_edge`）。データパックの `animation` 値は `AnimationManager.byKey()` で解決され、**mob 専用ホワイトリストは無く**プレイヤースキルアニメ（すべて `AttackAnimation`＝コリジョン内蔵）も受理される（`MobPatchReloadListener.deserializeCombatBehaviorsBuilder`）。よって上例の `sweeping_edge` は実際に再生され、内蔵判定でダメージも出る。確認済みのスキル名→パス対応は Common §5.3 を参照。
>
> ⚠️ **綴り厳守**：registry 名が 1 文字でも違うと `NoSuchElementException` で **mobpatch ロードが失敗（起動時クラッシュ）**。特に `METEOR_SLAM` は変数名と異なり登録パスが **`epicfight:biped/skill/greatsword_slam`**（`meteor_slam` ではない）。
>
> 🔍 **GUI 既知バグ**：公式データパックエディタの combat/stun リストにスキルアニメが表示されない（[issue #1983](https://github.com/Epic-Fight/epicfight/issues/1983)）。registry 名は `Animations` クラスから拾って **JSON 直書き**するのが確実。

### 4.2 「HP が減るほど派手なスキル」を作る

プレイヤーの「キー入力で発動」は、Mob では `within_distance`（間合い）・`health`（HP 連動）・`random_chance`（ばらけさせる）の組合せで再現する（predicate 詳細は Common §4）。**ボスらしい挙動はここで作る**：

- HP 100〜50%：通常コンボ中心（高 weight の series）
- HP 50〜25%：スキルモーション series を解禁（`health less_ratio 0.5`）
- HP 25% 以下：強スキルを高頻度化（別 series ＋ `health less_ratio 0.25`、`cooldown` 短縮）

### 4.3 武器別ムーブセット（`humanoid_weapon_motions`）

`isHumanoid: true` のとき、武器カテゴリごとに living motion と combat behavior を切り替えられる（許可値・内蔵アニメ表は Common §5）。
プレイヤーが剣 / 槍 / 大剣で別コンボを持つのと同様に、**敵が持ち替えた武器に応じてプレイヤー由来のコンボを使う** よう構成できる。

### 4.4 IR → mobpatch JSON 自動生成（補完版）

解析で得た IR（3.3）から `combat_behavior` を自動生成する。最適化のデフォルトもここで当てる。プレースホルダだった条件部分を**具体化**：

```python
def ir_skill_to_behavior(skill: dict) -> dict | None:
    cat = skill["origin_category"]
    if cat == "passive":
        return None  # attributes / 属性 modifier で代替（スキップ）
    if cat in ("dodge", "guard", "mobility"):
        return {"_route_b_flag": cat}  # ルートB の AI 実装へ回す

    # weapon_innate / revelation
    pot = skill.get("player_only_traits", {})
    strong = skill["animation"].get("damage_multiplier", 1.0) >= 1.5
    return {
        # 強技ほど低 weight（派手さに反比例）
        "weight": 20.0 if strong else 50.0,
        # プレイヤー CD をそのまま使わず Mob 用に再設計
        "cooldown": max(int(pot.get("cooldown_ticks", 100) * 0.5), 60),
        "canBeInterrupted": not strong,   # 強技は中断不可
        "looping": False,
        "behaviors": [{
            "conditions": [
                {"predicate": "within_distance", "min": 0.0, "max": 3.0},
                # 強技は HP 連動で“ここぞ”に温存
                *([{"predicate": "health", "health": 0.5, "comparator": "less_ratio"}] if strong else [])
            ],
            "animation": skill["animation"]["path"],
        }],
    }
```

---

## 5. 転用ルート B：Java / 解析方式（高度・カスタム制御）

データパックで表現できない制御（フェーズ遷移、独自効果、被弾割込み回避など）が必要な場合。`HumanoidMobPatch` 継承・登録手順・`EpicFightBridge` は Common §1・§7 を土台とする。

### 5.1 `HumanoidMobPatch` を継承 🔍

```java
// 1.20.1 Forge 例（@Mod.EventBusSubscriber + MOD バス明示）
public class BossPatch extends HumanoidMobPatch<BossEntity> {

    public BossPatch(BossEntity original) {
        super(original, Factions.UNDEAD);
    }

    @Override
    public void updateMotion(boolean b) {
        super.commonMobUpdateMotion(b); // 武器に応じた living motion 解決
    }

    @Override
    protected void initAI() {
        super.initAI();
        // 解析 IR から構築した CombatBehaviors を AnimatedAttackGoal に渡す
        this.original.goalSelector.addGoal(1,
            new AnimatedAttackGoal<>(this, buildBehaviorsFromIR()));
        this.original.goalSelector.addGoal(2,
            new TargetChasingGoal(this, this.getOriginal(), 1.2f, true));
        this.original.targetSelector.addGoal(1,
            new NearestAttackableTargetGoal<>(original, Player.class, true));
    }

    @Override
    public void initAnimator(Animator animator) {
        super.initAnimator(animator);
        animator.addLivingAnimation(LivingMotions.IDLE, EpicFightBridge.idle());
        animator.addLivingAnimation(LivingMotions.WALK, EpicFightBridge.walk());
        animator.addLivingAnimation(LivingMotions.CHASE, EpicFightBridge.walk());
    }
}
```

登録は `EntityPatchRegistryEvent`（MOD バス）、アーマチュアは `Armatures.registerEntityTypeArmature(...)`、レンダラは `PatchedRenderersEvent.Add`（クライアント・MOD バス）。

### 5.2 解析した `AttackAnimation` の再構築

IR のフェーズ / コリジョン / ダメージから **自前のアニメ定義** を作る（既存スキルアニメをそのまま使う場合は不要）。

```java
// 概念図（引数順・型は対象バージョンで要確認 🔍）
EpicFightBridge.combo(
    /* convertTime */ 0.30f, /* antic */ 0.05f,
    /* contact */ 0.45f, /* recovery */ 0.95f,
    /* collider */ EpicFightBridge.colliderToolR(),
    /* path */ "yourmod:biped/skill/flame_slash"
);
```

### 5.3 `CombatBehaviors.Builder`（条件を具体化）

旧版の `/* condition */` プレースホルダを、IR から具体化した条件で埋める：

```java
// ✅ 1.20.1 ソースで確認した実 API 形：newBehaviorSeries は BehaviorSeries.Builder を取る「入れ子」構造。
//    Behavior.Builder は withinDistance / health / randomChance 等の専用述語メソッドを持つ（条件はラムダではなく述語ビルダで指定）。
private CombatBehaviors<BossPatch> buildBehaviorsFromIR() {
    return new CombatBehaviors.Builder<BossPatch>()
        // 通常コンボ series（高 weight・中断可・ループ）
        .newBehaviorSeries(
            CombatBehaviors.BehaviorSeries.<BossPatch>builder(
                    /* weight */ 60.0F, /* canBeInterrupted */ true, /* looping */ true)
                .nextBehavior(
                    CombatBehaviors.Behavior.<BossPatch>builder()
                        .animationBehavior(EpicFightBridge.mobOnehand1())
                        .withinDistance(0.0D, 2.2D)))
        // スキル series（低 weight・中断不可・CD あり・HP 50% 以下で発動）
        .newBehaviorSeries(
            CombatBehaviors.BehaviorSeries.<BossPatch>builder(
                    /* weight */ 30.0F, /* canBeInterrupted */ false, /* looping */ false)
                .nextBehavior(
                    CombatBehaviors.Behavior.<BossPatch>builder()
                        .animationBehavior(EpicFightBridge.sweepingEdge())
                        .cooldown(160)
                        .withinDistance(0.0D, 3.0D)
                        .health(0.5F, Comparator.LESS_RATIO)))  // HP 50% 以下
        .build(this);
}
```

> 🔍 `CombatBehaviors.Builder` / `Behavior.builder()` のメソッド名・シグネチャは対象バージョンで要確認。`EpicFightBridge` 経由で隔離する。

### 5.4 （実験的・非推奨）スキルそのものの実行

`SkillContainer` 相当を Mob に持たせ、スキルを `execute` する試み。
**`ServerPlayerPatch` へのキャストを内部で行う経路があるため高リスク**（§1.2）。Mixin / リフレクションでの回避は EpicFight 更新で容易に壊れる。
→ 本書では **採用しない**。どうしても必要な場合のみ、対象バージョン限定・隔離前提で実験する。

---

## 6. 一気通貫サンプル（jar→IR→データパック）

`examplemod.jar` に「炎斬り（flame_slash）」という剣スキルがあると仮定し、入力→中間→出力の値が一貫する形で追跡する。

### 6.1 入力：jar 内に見つかる情報

- `data/examplemod/animmodels/animations/biped/skill/flame_slash.json`（キーフレーム、尺 28 tick）
- `class` 解析（`Animations` / `Skill` サブクラス）から：preDelay 0.30 / contact 0.45 / recovery 0.95、collider `toolR`、ダメージ係数 1.8、スタミナ 6・CD 200tick、武器カテゴリ sword・style one_hand

### 6.2 中間：IR（Common §6 基底 + 本書 3.3 拡張）

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
        "phases": [ { "antic": 0.0, "preDelay": 0.30, "contact": 0.45, "recovery": 0.95 } ],
        "collider": "toolR",
        "damage_multiplier": 1.8,
        "stun": "long"
      },
      "weapon_category": "sword",
      "style": "one_hand",
      "player_only_traits": { "stamina_cost": 6, "cooldown_ticks": 200 }
    }
  ]
}
```

### 6.3 出力：生成された `combat_behavior`（§4.4 のロジック適用）

`damage_multiplier 1.8 ≥ 1.5` → 強技扱い：weight 20 / canBeInterrupted false / cooldown `max(200*0.5, 60)=100` / HP 連動条件付き。

```jsonc
{
  "weapon_categories": ["sword"],
  "style": "one_hand",
  "behavior_series": [
    {
      "weight": 20.0, "canBeInterrupted": false, "looping": false, "cooldown": 100,
      "behaviors": [
        { "conditions": [
            {"predicate":"within_distance","min":0.0,"max":3.0},
            {"predicate":"health","health":0.5,"comparator":"less_ratio"}
          ],
          "animation": "examplemod:biped/skill/flame_slash" }
      ]
    }
  ]
}
```

### 6.4 ゲーム内確認手順 ⚠️

1. データパックを `world/datapacks/` に配置 → `/reload`
2. `/summon` で対象 Mob を出し、剣を持たせる
3. HP を半分以下に削り（`/damage` 等）、間合い 3.0 以内で flame_slash モーションが発動するか確認
4. **要確認**：モーションの判定でダメージが入るか（コリジョンはコード依存 ⚠️）、CD・weight の体感、クラッシュ無し
5. ずれていれば IR の数値・`weight`/`cooldown` を調整して再 `/reload`

---

## 7. 外部データの活用と最適化

### 7.1 外部データソース

| ソース | 取得できるもの | 用途 |
|--------|----------------|------|
| 他 Mod の `epicfight_mobpatch` データパック | 完成した `combat_behavior` の実例 | **最良の転用テンプレート**。書式そのまま流用・改変 |
| EpicFight 本体の `Animations`（`BIPED_MOB_*` 等） | Mob 用攻撃アニメ・武器コンボ・スキルアニメ | アニメ ID の供給源（追加アセット不要） |
| EpicFight アドオン（Skill Tree 等）のデータパック | 追加スキルのパラメータ | バランス値の参考 |
| Bosses of Mass Destruction / Bosses' Rise（オープンソース） | フェーズ設計・行動選択の考え方 | ボス AI 設計の参考（方式の参考） |

### 7.2 最適化の観点

| 観点 | 手段 |
|------|------|
| 攻撃選択のバランス | `weight` を強技ほど低く。連打させたい技は高く |
| スパム防止 | `cooldown`（tick）。プレイヤーの CD をそのまま使わず Mob 用に短縮・再設計 |
| 間合い / 状況連動 | `conditions` の `within_distance` / `within_angle` / `health` を組み合わせ |
| 難易度バランス | `attributes.impact`（怯ませ）/ `armor_negation`（火力）/ `max_strikes`（多段） |
| フェーズ演出 | `health` comparator（`less_ratio`）で HP 帯ごとに別 behavior series |
| 大量生成 | IR → JSON 自動生成（4.4）。LLM に IR を渡して `combat_behavior` を起草させ、人手で検証 |

### 7.3 解析結果のキャッシュ / プロファイル化

- 解析した IR を **プロファイル JSON** として保存し再利用
- mod / バージョンごとに「アニメ ID 対応表」をキャッシュ → 再解析を避ける
- 出力先：`/mnt/user-data/outputs/`（既存スキルと統一）

---

## 8. 実装マイルストーン

| フェーズ | 内容 | 成果物 |
|---------|------|--------|
| **P1 解析基盤** | jar / データパック展開・分類、既存 mobpatch 収集 | 解析スクリプト、ファイル分類器 |
| **P2 IR 化** | アニメ ID 対応表、AttackAnimation パラメータ抽出（必要に応じ逆コンパイル） | 中間表現 JSON（Common §6 + 3.3） |
| **P3 データパック生成（ルートA）** | IR → `epicfight_mobpatch` JSON 自動生成 | 動作する敵 Mob データパック |
| **P4 Java 転用（ルートB）** | `HumanoidMobPatch` + `CombatBehaviors` + `EpicFightBridge` | ボス用パッチ、ブリッジ層 |
| **P5 最適化** | weight / cooldown / conditions チューニング、フェーズ演出 | バランス調整済み定義 |
| **P6 検証** | ゲーム内でモーション・判定・クラッシュ有無を確認（§6.4） | テストログ、既知の課題更新 |

---

## 9. 既知の課題・検証不能項目

- ✅ **スキルアニメのデータパック参照パス**：`epicfight:biped/skill/<名>` で確定（EpicFight 1.20.1 ソース）。`AnimationManager.byKey()` は mob 専用フィルタ無しで `AttackAnimation` を受理。対応表は Common §5.3。⚠️ 定数名とパスが不一致の例（`METEOR_SLAM`→`greatsword_slam`）と、綴り違いによるロード失敗（起動時クラッシュ）に注意。GUI 非表示バグは [issue #1983](https://github.com/Epic-Fight/epicfight/issues/1983)。
- ⚠️ **`AttackAnimation` のコリジョン / ダメージ**：コード依存。ヘッドレス環境では実挙動を確認できないため、IR の数値は初期推定値。
- ⚠️ **スキルのプレイヤー固有挙動**（無敵フレーム、スタミナ連動、スタック蓄積等）：Mob には自動では移らない。必要なら AI / イベントで明示再現。
- **Dodge / Guard 系の被弾割込み**：標準の `combat_behavior` では表現困難。[`EpicFight_PlayerSystemToMob_Spec_v2.md`](EpicFight_PlayerSystemToMob_Spec_v2.md) §5（Indestructible）またはルート B の専用 Goal が必要。
- 🔍 **EpicFight バージョン更新による破壊的変更**：本書のクラス名・引数は対象バージョンで必ず再確認。`EpicFightBridge` 隔離と `try/catch(Throwable)` で被害を局所化（Common §7）。
