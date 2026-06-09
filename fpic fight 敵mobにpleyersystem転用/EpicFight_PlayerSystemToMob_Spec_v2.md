# EpicFight プレイヤーデータ / システムの Mob 転用 設計仕様書 v2

> EpicFight の **プレイヤー側データ構造とゲームシステム**（スタミナ・ガード・回避・スタン・コンボ状態・戦闘スタイル・スキルスロット 等）を、敵 Mob 側へ **移植 / エミュレート** するための設計書。
> 「スキル単体のモーション転用」ではなく、**プレイヤーが戦闘で使っている仕組みそのものを Mob に持たせる** ことを目的とする。
>
> **v2 の変更点**：共通仕様を [`EpicFight_Common_Spec.md`](EpicFight_Common_Spec.md) に集約し重複を削減。Indestructible `advanced_mobpatch` のフィールド名を**一次情報（1.18 tutorial JSON）で確定**して反映。パリィ例を具体化し、スタミナ自前管理の AI 連携例を追加。

**前提**：本書は [`EpicFight_Common_Spec.md`](EpicFight_Common_Spec.md) を土台とする。クラス階層・`epicfight_mobpatch` フィールド・predicate 一覧・`EpicFightBridge`・バージョン互換は **Common を参照**。本書は戦闘システム移植に固有の論点（特に層2 Indestructible）を扱う。

**対象環境（本書固有の追加）**

| 項目 | 値 |
|------|------|
| 推奨アドオン | **Epic Fight - Indestructible**（`advanced_mobpatch` を提供。スタミナ/スタン/ガード機構の Mob 移植に必須級） |
| Indestructible 参照 | CurseForge / GitHub `Cyber2049/Epic-Fight---Indestructible` |

**関連ドキュメント**

| ドキュメント | 役割 | 本書との関係 |
|--------------|------|--------------|
| [`EpicFight_Common_Spec.md`](EpicFight_Common_Spec.md) | 共通基盤 | mobpatch / predicate / IR / Bridge の参照元 |
| [`EpicFight_SkillToMob_Spec_v2.md`](EpicFight_SkillToMob_Spec_v2.md) | スキル / モーションの解析→転用 | 「攻撃モーション」担当。本書と相補 |
| [`EpicFight_MobMimic_Spec.md`](EpicFight_MobMimic_Spec.md) | プレイヤースタイルの**動的学習** | 「学習」担当。本書の状態移植と統合 |
| [`EpicFight_BaseModBehavior_Spec.md`](EpicFight_BaseModBehavior_Spec.md) | 本体mod導入時の挙動 | **土台**。本体導入でプレイヤーが得るスタミナ/ガード等の実体・Mob自動パッチ・DP上書き規則 |
| `docs/epicfight/14` 武器ムーブセットのボス転用 | 武器単位の moveset | 武器スタイルの土台 |

---

## 目次

1. [全体像](#1-全体像)
2. [`PlayerPatch` ↔ `MobPatch` のクラス対応](#2-playerpatch--mobpatch-のクラス対応)
3. [データ / システム別 転用マトリクス（中核）](#3-データ--システム別-転用マトリクス中核)
4. [層1：EpicFight 標準で移植できる部分](#4-層1epicfight-標準で移植できる部分)
5. [層2：Indestructible `advanced_mobpatch`（中核）](#5-層2indestructible-advanced_mobpatch-でシステムを移植中核)
6. [層3：Java 実装で `PlayerPatch` の状態を移植](#6-層3java-実装で-playerpatch-の状態を移植)
7. [解析：jar からシステムパラメータを抽出](#7-解析jar-からプレイヤーシステムのパラメータを抽出)
8. [外部データの活用と最適化](#8-外部データの活用と最適化)
9. [実装マイルストーン](#9-実装マイルストーン)
10. [既知の課題・検証不能項目](#10-既知の課題検証不能項目)

---

## 1. 全体像

### 1.1 「データ」と「システム」を分けて捉える

| 区分 | 内容 | 例 |
|------|------|----|
| **プレイヤーデータ** | `PlayerPatch` が保持する **状態値** | スタミナ残量、現在の戦闘スタイル、コンボ段数、装備武器カテゴリ、ガード/回避中フラグ、ロックオン対象、スキルスロットの中身 |
| **プレイヤーシステム** | データを動かす **仕組み** | スタミナ消費・回復、ガード判定、回避の無敵フレーム、コンボ繋ぎ、スタン/ガードブレイク、入力処理、スキル発動フロー |

→ Mob に「使わせる」には、必要なデータを持たせ、それを動かすシステムを Mob 文脈で再現する。

### 1.2 転用の 3 分類

| 分類 | 意味 | 対象例 |
|------|------|--------|
| **移植 (Port)** | Mob にも同じデータ/システムを持たせる | スタミナ、スタン、スタンシールド、ガード、パリィ |
| **代替 (Substitute)** | プレイヤー固有機構を別手段で近似 | パッシブスキル効果 → attribute、キー入力 → AI 条件(`conditions`) |
| **破棄 (Discard)** | Mob には不要 | スキルスロット UI、キーバインド、経験値、スキル習得画面 |

### 1.3 3 つの実装層（本書の骨格）

| 層 | 手段 | カバー範囲 | コスト |
|----|------|-----------|--------|
| **層1** | EpicFight 標準 `epicfight_mobpatch` | ステータス・モーション・武器スタイル・基本スタン | 低 |
| **層2** | **Indestructible `advanced_mobpatch`** | **スタミナ・スタンシールド・スタン・ガード・パリィ・高度な状況判断** | 低〜中（アドオン導入） |
| **層3** | Java 実装（`PlayerPatch` 状態読取 → Mob capability） | 上記で表現できない動的移植・カスタム制御 | 高 |

**原則**：層1 → 層2 で大半を賄い、足りない部分だけ層3 で補う。コードを書く前にまずデータパックで実現できないか検討する。

---

## 2. `PlayerPatch` ↔ `MobPatch` のクラス対応

クラス継承階層・`LivingEntityPatch` が既に持つもの・`PlayerPatch` だけが持つもの・取得方法は **Common §1 を参照**。

本書の要点：層2/層3 で移植する主対象は、Common §1.2 の表のうち **スタミナ・戦闘スタイル・コンボ・入力状態**。スキル本体は破棄し、攻撃モーションのみ [`EpicFight_SkillToMob_Spec_v2.md`](EpicFight_SkillToMob_Spec_v2.md) 方式で転用する。

---

## 3. データ / システム別 転用マトリクス（中核）

| プレイヤー側 データ/システム | 実体（PlayerPatch 側） | 分類 | 転用手段（層） | 備考 |
|------------------------------|------------------------|:---:|----------------|------|
| 移動/待機モーション | LivingMotions ↔ アニメ | Port | 層1 `default_livingmotions` | doc14 参照 |
| 武器別スタイル / 持ち替え | Style 管理 | Port | 層1 `humanoid_weapon_motions`(`isHumanoid:true`) | |
| 武器コンボ | コンボカウンタ | Port | 層1 `combat_behavior.behavior_series` | |
| スキル攻撃モーション | SkillContainer 経由 | Port(見た目のみ) | 層1 `combat_behavior.animation`（SkillToMob） | スキル本体は破棄 |
| **スタミナ** | PlayerPatch スタミナ | **Port** | **層2 stamina 機構** / 層3 自前管理 | Mob 標準には無い |
| **スタン / 被弾硬直** | LivingEntityPatch stun | Port | 層1 `stun_animations` + 層2 stun 機構 | |
| **スタンシールド** | （プレイヤー Endurance 等） | Port | **層2 stun shield 機構** | 怯み耐性 |
| **ガード** | Guard スキル | **Port** | **層2 ガードアニメ + ガード AI** | 武器別アニメあり |
| **パリィ / カウンター** | Parrying スキル | Port | 層2 `attack_level` predicate で相手の予備動作に反応 | |
| 回避（Roll/Step） | Dodge スキル | Port(限定) | 層3 回避 AI + 回避アニメ | |
| ガードブレイク反応 | — | Port | 層2 `guard_break` predicate | 相手の崩しを突く |
| パッシブ効果（Berserker 等） | Passive スキル | **Substitute** | attribute / attribute modifier で数値再現 | 移植不要 |
| スキルスロット / 入力 / 経験値 | UI・キー | **Discard** | — | Mob 不要 |
| プレイヤーの「今の状態」を Mob に渡す | ServerPlayerPatch 状態 | Port | 層3 状態読取（§6.1） | MobMimic と統合 |

---

## 4. 層1：EpicFight 標準で移植できる部分

`data/<modid>/epicfight_mobpatch/<entityname>.json`（フィールド詳細は **Common §2〜§5**）。ここではシステム移植の観点で要点のみ。

| フィールド | 移植できるプレイヤー要素 |
|------------|--------------------------|
| `attributes`（impact / armor_negation / max_strikes / chasing_speed / scale） | 戦闘ステータス、パッシブ火力の数値代替 |
| `default_livingmotions`（idle/walk/chase/fall/death/mount） | 基本モーション |
| `humanoid_weapon_motions`（`isHumanoid:true`、weapon_categories、style） | **武器別スタイル**（プレイヤーの持ち替え挙動） |
| `combat_behavior`（weight/cooldown/conditions/behaviors） | **コンボ**・攻撃選択 |
| `stun_animations`（short/long/knockdown/fall） | **基本スタン** |

→ ここまでで「武器を持って、スタイルに応じたコンボを振り、被弾で怯む」までは標準で再現できる ✅。
**スタミナ・ガード・パリィ・スタンシールド** は標準では不足 → 層2。

---

## 5. 層2：Indestructible `advanced_mobpatch` でシステムを移植（中核）

Epic Fight - Indestructible（公式アドオン）は、`epicfight_mobpatch` を拡張し、**プレイヤー固有だった戦闘システムを Mob に付与** する。

### 5.1 位置づけ

- 配置：`data/<modid>/advanced_mobpatch/<entityname>.json` ✅（最新版のパス。旧版は別ディレクトリ）
- 単体では動かず、`epicfight_mobpatch` の記述と併用する
- **ボスに使うことが推奨**されている（負荷・複雑さのため）

### 5.2 追加されるシステムと **確定フィールド名** ✅

> 📌 **重要**：以下のフィールド名は Indestructible の tutorial / example データパック（1.18）で確認した実値。mod 内部の綴りは **`regan`（"regen" の綴り違い）** が使われている点に注意。**この綴りのまま記述すること**。

**attributes（`advanced_mobpatch` 内の attribute ブロック）** ✅

| フィールド | 意味 | 既定値 |
|------------|------|:---:|
| `max_stamina` | 最大スタミナ | 15 |
| `stamina_regan_multiply` | スタミナ回復倍率 | 1.0 |
| `stamina_regan_delay` | スタミナ回復までの遅延(tick) | 30 |
| `stamina_lose_multiply` | スタン時のスタミナ減少率（0 で無効） | 0 |
| `max_stun_shield` | 最大スタンシールド量（0/false で無効） | false |
| `stun_shield_regan_multiply` | スタンシールド回復倍率 | 1 |
| `stun_shield_regan_delay` | スタンシールド枯渇後の回復遅延(tick) | 30 |
| `guard_radius` | この距離外だとガードを解除検討 | 3 |

**custom_guard_motion（ガード/パリィ定義）** ✅

| フィールド | 意味 | 既定値 |
|------------|------|:---:|
| `guard` | ガードアニメのパス（例 `indestructible:guard/guard_longsword`） | — |
| `stamina_cost_multiply` | 被弾ブロック後のスタミナ消費倍率 | 1 |
| `can_block_projectile` | 飛び道具をブロック可能か | false |
| `parry_cost_multiply` | パリィ時のスタミナ消費倍率 | 0.5 |
| `parry_animation` | パリィ専用アニメ（配列・任意） | — |

### 5.3 武器別ガードアニメーション（プレイヤーのガードを Mob へ）✅

| 武器 | アニメパス |
|------|-----------|
| longsword | `indestructible:guard/guard_longsword` |
| sword | `indestructible:guard/guard_sword` |
| greatsword | `indestructible:guard/guard_greatsword` |
| katana / uchigatana | `indestructible:guard/guard_katana` / `indestructible:guard/guard_uchigatana` |
| spear | `indestructible:guard/guard_spear` |
| dual sword | `indestructible:guard/guard_dualsword` |
| yamato | `indestructible:guard/guard_yamato`（yamatomoveset 導入時） |

### 5.4 高度な behavior predicate

標準 predicate（Common §4.1）に加え、Indestructible が追加する `stamina` / `attack_level` / `guard_break` / `knock_down` / `using_item` / `phase` を使える（一覧・引数は **Common §4.2**）。

転用での使いどころ：

- `stamina` … 「スタミナがある時だけ強攻撃」等、スタミナ管理した攻撃選択
- `attack_level`（free0/preDelay1/contact2/recovery3） … **パリィ/カウンター**（相手の preDelay 中にガード or 反撃）
- `guard_break` … 崩した相手に追撃
- `knock_down` … ダウン追撃 / 起き攻め
- `using_item`（`edible`） … 回復中を狙う
- `phase` … 多段ギミック・フェーズ連動

### 5.5 行動例：パリィして反撃するボス（確定フィールドで具体化）

```jsonc
// epicfight_mobpatch 側（抜粋）— combat_behavior
{
  "weapon_categories": ["sword", "longsword"],
  "style": "one_hand",
  "behavior_series": [
    {
      // ① 相手が攻撃の予備動作(preDelay=1)に入った瞬間にガード（パリィ姿勢）
      //    スタミナが 20% 超ある時のみ
      "weight": 40.0, "canBeInterrupted": true, "looping": false, "cooldown": 40,
      "behaviors": [
        { "conditions": [
            {"predicate":"attack_level","min":1,"max":1},
            {"predicate":"within_distance","min":0.0,"max":2.5},
            {"predicate":"stamina","stamina":0.2,"comparator":"greater_ratio"}
          ],
          "animation": "indestructible:guard/guard_sword" }
      ]
    },
    {
      // ② 相手を崩した(guard_break)直後に強攻撃で追撃
      "weight": 60.0, "canBeInterrupted": false, "looping": false, "cooldown": 80,
      "behaviors": [
        { "conditions": [
            {"predicate":"guard_break","invert":false},
            {"predicate":"within_distance","min":0.0,"max":3.0}
          ],
          "animation": "epicfight:biped/combat/mob_onehand2" }
      ]
    }
  ]
}
```

```jsonc
// advanced_mobpatch 側（抜粋）— スタミナ/スタンシールド/ガード定義（§5.2 の確定フィールド）
{
  "attributes": {
    "max_stamina": 30,
    "stamina_regan_multiply": 1.0,
    "stamina_regan_delay": 40,
    "max_stun_shield": 10,
    "stun_shield_regan_multiply": 1,
    "stun_shield_regan_delay": 30,
    "guard_radius": 3
  },
  "custom_guard_motion": [
    {
      "weapon_categories": ["sword", "longsword"],
      "guard": "indestructible:guard/guard_sword",
      "stamina_cost_multiply": 1,
      "can_block_projectile": false,
      "parry_cost_multiply": 0.5
    }
  ]
}
```

→ プレイヤーが「相手の攻撃を見てガード→崩して反撃」する駆け引きを、**Mob 側で予備動作読み（`attack_level`）＋スタミナ管理（`stamina`）＋崩し追撃（`guard_break`）** として再現できる。

> ⚠️ フィールドの**入れ子位置**（`attributes` 内か `custom_guard_motion` 内か、トップレベルか）は Indestructible のバージョンで差がありうる 🔍。導入バージョンの example データパックで最終確認すること。`stamina` predicate の引数が**比率(0〜1)**か**絶対値**かも要確認（本例は `greater_ratio` のため 0.2＝20% と解釈）。

---

## 6. 層3：Java 実装で `PlayerPatch` の状態を移植

データパックで表現できない「プレイヤーの今の状態を動的に Mob へ移す」「独自のスタミナ運用」等。[`EpicFight_MobMimic_Spec.md`](EpicFight_MobMimic_Spec.md) の capability 設計と統合する。`EpicFightBridge` 隔離は Common §7。

### 6.1 プレイヤーの現在状態を読み取る 🔍

```java
// 周囲プレイヤーのスタミナ / スタイルを読む（バージョン依存・Bridge 隔離）
ServerPlayerPatch pp = (ServerPlayerPatch)
    EpicFightCapabilities.getEntityPatch(player, PlayerPatch.class);
// 例: float stamina = pp.getStamina();  // メソッド名は要確認
//     Style style   = pp.getCurrentStyle(weaponCap); 等
```

> 🔍 `getStamina` / スタイル取得などのメソッド名は **バージョン依存**。実在を対象バージョンのソースで確認し、`try/catch(Throwable)` で保護する。

### 6.2 Mob 側 capability に保存（MobMimic と統合）

`MobMimicCapability`（[`EpicFight_MobMimic_Spec.md`](EpicFight_MobMimic_Spec.md)）に「観測したプレイヤースタミナ運用」「採用スタイル」を保持し、Mob の行動傾向へ反映する。**学習（MobMimic）＋ 状態移植（本書）** を 1 つの capability で扱う。

### 6.3 Mob 用スタミナの自前管理（Indestructible を使わない場合）

```java
public class MobStamina implements INBTSerializable<CompoundTag> {
    private float stamina, max = 30f, regenPerTick = 0.1f;
    private int   regenDelay = 0;

    public void tick() {
        if (regenDelay > 0) { regenDelay--; return; }      // 消費直後は回復を遅延
        stamina = Math.min(max, stamina + regenPerTick);
    }
    public boolean tryConsume(float cost) {
        if (stamina < cost) return false;   // スタミナ切れ → 強攻撃を撃たない
        stamina -= cost; regenDelay = 30; return true;      // 30tick 回復停止（プレイヤー挙動を模倣）
    }
    public float ratio() { return stamina / max; }

    @Override public CompoundTag serializeNBT() {
        var t = new CompoundTag(); t.putFloat("stamina", stamina); return t;
    }
    @Override public void deserializeNBT(CompoundTag t) { stamina = t.getFloat("stamina"); }
}
```

**AI Goal との連携例**：強攻撃 Goal の `canUse()` で `tryConsume` を呼び、スタミナ切れ時は通常攻撃 Goal にフォールバックさせる。

```java
// 強攻撃 AI Goal（概念）
@Override
public boolean canUse() {
    if (target == null || patch.distanceTo(target) > 3.0) return false;
    return mobStamina.tryConsume(6f);   // スタミナ 6 を消費できた時だけ強攻撃を許可
}
```

→ プレイヤーのスタミナ駆け引き（撃ちすぎると隙ができる）を Mob で再現。Indestructible 未導入環境でも層3 単独で成立する。

---

## 7. 解析：jar からプレイヤーシステムのパラメータを抽出

[`EpicFight_SkillToMob_Spec_v2.md`](EpicFight_SkillToMob_Spec_v2.md) §3 の解析パイプラインに、**システムパラメータ** の抽出を追加する。

### 7.1 抽出対象

| 対象 | 取得元 | 用途 |
|------|--------|------|
| スタミナ最大値 / 回復速度 | データパック（スキル/プレイヤー設定）or コード | 層2/層3 のスタミナ値 |
| スキルのスタミナ消費 / CD | スキルパラメータ JSON | Mob 用に再設計（プレイヤー値の流用は避ける） |
| ガードのパラメータ（軽減率等） | コード | ガード AI の挙動 |
| スタン耐性 / スタンシールド量 | コード / 設定 | 層2 設定 |
| 既存ボスの `advanced_mobpatch` | 他 Mod / Indestructible example | **そのまま流用できるテンプレ** |

### 7.2 IR への統合（`system_params`）

Common §6 の基底 IR に `system_params` を追加（§5.2 の確定フィールド名に合わせる）：

```json
{
  "system_params": {
    "stamina": { "max_stamina": 30, "stamina_regan_multiply": 1.0, "stamina_regan_delay": 40 },
    "stun_shield": { "max_stun_shield": 10, "stun_shield_regan_delay": 30 },
    "guard": { "weapon": "sword", "guard": "indestructible:guard/guard_sword", "parry_cost_multiply": 0.5 }
  }
}
```

---

## 8. 外部データの活用と最適化

| ソース | 取得できるもの | 用途 |
|--------|----------------|------|
| Indestructible の example データパック | 完成した `advanced_mobpatch`（stamina/guard/predicate の実記述） | **最良のテンプレ**。フィールド名・入れ子確定にも使う |
| 他 EpicFight 系ボス Mod の `epicfight_mobpatch` / `advanced_mobpatch` | combat_behavior・システム設定の実例 | 流用・改変 |
| EpicFight 本体 `Animations`（`BIPED_MOB_*` 等） | Mob 用アニメ | アニメ ID 供給（Common §5） |
| Bosses' Rise 等（オープンソース） | フェーズ・行動選択の設計思想 | 方式の参考 |

**最適化の観点**

- スタミナ運用：`stamina` predicate + `cooldown` で「スタミナ切れで隙ができる」演出
- ガード頻度：パリィ（`attack_level`）の `weight` を上げすぎると理不尽 → 体感調整
- フェーズ：`phase` / `health(less_ratio)` で HP 帯ごとに別 behavior series
- 難易度：`max_stun_shield` 量・`impact`・`armor_negation` でバランス
- 自動生成：IR → `advanced_mobpatch` JSON 起草（LLM 支援＋人手検証）

---

## 9. 実装マイルストーン

| フェーズ | 内容 | 成果物 |
|---------|------|--------|
| **P1** | `PlayerPatch`/`MobPatch` 構造の確定、転用マトリクス確定 | 本書の対応表（対象バージョンで検証済み） |
| **P2** | 層1：`epicfight_mobpatch` で基本戦闘（スタイル・コンボ・基本スタン） | 動く戦闘 Mob |
| **P3** | 層2：`advanced_mobpatch` でスタミナ/ガード/パリィ/スタンシールド | システム移植済みボス |
| **P4** | 層3：`PlayerPatch` 状態読取 + Mob capability + 自前スタミナ | 動的移植・MobMimic 統合 |
| **P5** | 解析：jar からシステムパラメータ抽出 → IR 統合 | システム込み IR |
| **P6** | 最適化・ゲーム内検証（スタミナ運用・パリィ頻度・体感） | バランス調整済み定義 |

---

## 10. 既知の課題・検証不能項目

- 🔍 **`advanced_mobpatch` のフィールド入れ子位置**：§5.2 の名前は確定 ✅ だが、`attributes` / `custom_guard_motion` / トップレベルのどこに置くかは導入バージョンの example で最終確認。`regan`（綴り違い）をそのまま使うこと。
- 🔍 **`stamina` predicate の引数解釈**：比率(0〜1)か絶対値か。`comparator` が `*_ratio` か `*_absolute` かで意味が変わる。
- 🔍 **`PlayerPatch` 状態取得 API**（`getStamina` 等）：バージョン依存。ヘッドレス環境では実挙動確認不可。
- **依存の増加**：Indestructible を入れると前提が 2 つになる。ソフト依存で包み（Common §7）、未導入時は層1 のみ／層3 自前スタミナで動作するようフォールバックを用意。
- ⚠️ **パリィ AI の体感**：`attack_level` 反応はフレーム単位の挙動でテスト必須。理不尽にならない `weight`/`cooldown` 調整が必要。
- 🔍 **EpicFight / Indestructible 更新による破壊的変更**：クラス名・パス・フィールドは対象バージョンで必ず再確認。
