# EpicFight プレイヤーデータ / システムの Mob 転用 設計仕様書

> EpicFight の **プレイヤー側データ構造とゲームシステム**（スタミナ・ガード・回避・スタン・コンボ状態・戦闘スタイル・スキルスロット 等）を、敵 Mob 側へ **移植 / エミュレート** するための設計書。
> 「スキル単体のモーション転用」ではなく、**プレイヤーが戦闘で使っている仕組みそのものを Mob に持たせる** ことを目的とする。

**対象環境**

| 項目 | 値 |
|------|------|
| Minecraft | 1.20.1 |
| Modloader | Forge (47.x 系) |
| 前提 / ソフト依存 | EpicFight (`epicfight`) |
| 推奨アドオン | **Epic Fight - Indestructible**（`advanced_mobpatch` を提供。スタミナ/スタン/ガード機構の Mob 移植に必須級） |
| 補助 | SmartBrainLib（ボス AI / 任意）、GeckoLib 4.x（独自アニメ / 任意） |
| 言語 | Java 17 / データパック JSON / Python（解析） |
| 権威ある参照 | Epic Fight 公式 Wiki `https://epicfight-docs.readthedocs.io/` / Indestructible（CurseForge / GitHub `Cyber2049/Epic-Fight---Indestructible`） |

**関連ドキュメントとの位置づけ**

| ドキュメント | 扱う対象 | 本書との関係 |
|--------------|----------|--------------|
| `docs/epicfight/14` 武器ムーブセットのボス転用 | 武器単位の moveset | 武器スタイルの土台 |
| `EpicFight_MobMimic_Spec.md` | プレイヤースタイルの **動的学習** | 「学習」担当。本書の状態移植と連携 |
| `EpicFight_SkillToMob_Spec.md` | スキル / モーションの **解析→転用** | 「攻撃モーション」担当 |
| **本書** プレイヤーデータ/システムの Mob 転用 | スタミナ・ガード・スタン・状態など **システム全体** | 上記を束ねる **横断的な土台**。攻撃以外の戦闘システムを担当 |

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

### 2.1 継承階層（参考・バージョン依存）

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

> ⚠️ クラス名・階層は **バージョン依存**。実装前に対象バージョンの `yesman.epicfight.world.capabilities.entitypatch.*` を確認すること。

### 2.2 共通基底 `LivingEntityPatch` が **既に持つ** もの（= Mob でもそのまま使える土台）

| 機能 | 説明 |
|------|------|
| アニメーション再生 | `playAnimationInstantly(...)` 等。Mob でも可 |
| armature / モデル | スケルトン構造 |
| 当たり判定・コリジョン | 攻撃判定 |
| stun（基本） | 被弾硬直 |
| attribute（impact / armor_negation / max_strikes 等） | EpicFight 戦闘ステータス |

### 2.3 `PlayerPatch`（特に `ServerPlayerPatch`）**だけ** が持つもの（= 転用対象）

| 機能 | プレイヤーでの役割 | Mob への扱い |
|------|--------------------|--------------|
| `SkillContainer` 配列 + `SkillSlot` | スキルの保持・発動 | **破棄**（Mob は AI で攻撃選択）。攻撃モーションのみ `SkillToMob` 方式で転用 |
| スタミナ（残量・回復） | 行動コスト | **移植**（層2 / 層3） |
| 戦闘スタイル管理 | 武器に応じた挙動 | 層1 `humanoid_weapon_motions` で再現 |
| コンボカウンタ・コンボ繋ぎ | 連撃 | 層1 `combat_behavior` の `behavior_series` で再現 |
| 入力状態（攻撃/ガード/回避キー） | 操作 | **代替**（`conditions` / AI Goal） |
| ロックオン | 照準 | Mob の `target` で代替 |

### 2.4 取得と橋渡し

```java
// バニラ Entity から EpicFight パッチを取得（存在チェック必須）
LivingEntityPatch<?> patch =
    EpicFightCapabilities.getEntityPatch(entity, LivingEntityPatch.class);
```

すべて `EpicFightBridge`（§6.4）経由でソフト依存化する。

---

## 3. データ / システム別 転用マトリクス（中核）

| プレイヤー側 データ/システム | 実体（PlayerPatch 側） | 分類 | 転用手段（層） | 備考 |
|------------------------------|------------------------|:---:|----------------|------|
| 移動/待機モーション | LivingMotions ↔ アニメ | Port | 層1 `default_livingmotions` | doc14 参照 |
| 武器別スタイル / 持ち替え | Style 管理 | Port | 層1 `humanoid_weapon_motions`(`isHumanoid:true`) | |
| 武器コンボ | コンボカウンタ | Port | 層1 `combat_behavior.behavior_series` | |
| スキル攻撃モーション | SkillContainer 経由 | Port(見た目のみ) | 層1 `combat_behavior.animation`（`SkillToMob`） | スキル本体は破棄 |
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

`data/<modid>/epicfight_mobpatch/<entityname>.json`（`pack_format`：1.20.1 = `15`）。
詳細フィールドは `SkillToMob` / doc14 に記載済みのため、ここではシステム移植の観点で要点のみ。

| フィールド | 移植できるプレイヤー要素 |
|------------|--------------------------|
| `attributes`（impact / armor_negation / max_strikes / chasing_speed / scale） | 戦闘ステータス、パッシブ火力の数値代替 |
| `default_livingmotions`（idle/walk/chase/fall/death/mount） | 基本モーション |
| `humanoid_weapon_motions`（`isHumanoid:true`、weapon_categories、style） | **武器別スタイル**（プレイヤーの持ち替え挙動） |
| `combat_behavior`（weight/cooldown/conditions/behaviors） | **コンボ**・攻撃選択 |
| `stun_animations`（short/long/knockdown/fall） | **基本スタン** |

→ ここまでで「武器を持って、スタイルに応じたコンボを振り、被弾で怯む」までは標準で再現できる。
**スタミナ・ガード・パリィ・スタンシールド** は標準では不足 → 層2。

---

## 5. 層2：Indestructible `advanced_mobpatch` でシステムを移植（中核）

Epic Fight - Indestructible（公式アドオン）は、`epicfight_mobpatch` を拡張し、**プレイヤー固有だった戦闘システムを Mob に付与** する。

### 5.1 位置づけ

- 配置：`data/<modid>/advanced_mobpatch/<entityname>.json`（**最新版のパス**。旧版は別ディレクトリだった）
- 単体では動かず、`epicfight_mobpatch` の記述と併用する
- **ボスに使うことが推奨**されている（負荷・複雑さのため）

### 5.2 追加されるシステム（プレイヤー由来）

| 機構 | プレイヤーでの対応 | Mob での効果 |
|------|--------------------|--------------|
| **スタミナ機構** | プレイヤーのスタミナ | Mob がスタミナを持ち、消費/回復する。`stamina` predicate で「スタミナがある時だけ強攻撃」等 |
| **スタンシールド機構** | Endurance 系の怯み耐性 | 一定までの攻撃で怯まない（ボスらしさ） |
| **スタン機構** | 被弾硬直の拡張 | より細かいスタン制御 |
| 防御 / 徘徊 / カスタムアニメ挙動 | — | 行動の幅 |

> ⚠️ スタミナ最大値・回復速度・スタンシールド量などの **正確な JSON フィールド名は要確認**。Indestructible の公式 example データパック（CurseForge の Files、tutorial JSON）を参照して確定すること。本書では「これらが `advanced_mobpatch` 内の追加 attribute / 専用ブロックとして指定できる」ことを前提に設計する。

### 5.3 武器別ガードアニメーション（プレイヤーのガードを Mob へ）

`advanced_mobpatch` で利用できるガードモーション：

| 武器 | アニメパス |
|------|-----------|
| longsword | `indestructible:guard/guard_longsword` |
| sword | `indestructible:guard/guard_sword` |
| greatsword | `indestructible:guard/guard_greatsword` |
| katana / uchigatana | `indestructible:guard/guard_katana` / `indestructible:guard/guard_uchigatana` |
| spear | `indestructible:guard/guard_spear` |
| dual sword | `indestructible:guard/guard_dualsword` |
| yamato | `indestructible:guard/guard_yamato`（yamatomoveset 導入時） |

### 5.4 高度な behavior predicate（プレイヤーの状態を Mob の判断材料に）

標準の `within_distance` / `within_angle` / `health` / `random_chance`（`SkillToMob` 記載）に加え、Indestructible が追加：

| predicate | 判定内容 | 引数 | 転用での使いどころ |
|-----------|----------|------|--------------------|
| `stamina` | **自分**のスタミナ | `stamina`: double, `comparator`: `greater_ratio` 等 | スタミナ管理した攻撃選択 |
| `attack_level` | **相手**の攻撃フェーズ（free:0 / preDelay:1 / contact:2 / recovery:3） | `min`, `max` | **パリィ/カウンター**（相手の preDelay 中にガード or 反撃） |
| `guard_break` | 相手がガードブレイク中か | `invert`: bool | 崩した相手に追撃 |
| `knock_down` | 相手がノックダウン中か | `invert`: bool | ダウン追撃 / 起き攻め |
| `using_item` | 相手がアイテム使用中か | `edible`: bool（食料/ポーション限定） | **回復中を狙う** |
| `phase` | behavior motion が設定したカスタムフェーズ | `min`, `max` | 多段ギミック・フェーズ連動 |

### 5.5 行動例：パリィして反撃するボス

```jsonc
// advanced_mobpatch 内 combat_behavior（概念例。フィールド名は要確認）
{
  "weapon_categories": ["sword", "longsword"],
  "style": "one_hand",
  "behavior_series": [
    {
      // ① 相手が攻撃の予備動作(preDelay)に入った瞬間にガード（パリィ姿勢）
      "weight": 40.0, "canBeInterrupted": true, "looping": false, "cooldown": 40,
      "behaviors": [
        { "conditions": [
            {"predicate":"attack_level","min":1,"max":1},
            {"predicate":"within_distance","min":0.0,"max":2.5},
            {"predicate":"stamina","stamina":20,"comparator":"greater_ratio"}
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
          "animation": "epicfight:skill/sweeping_edge" }
      ]
    }
  ]
}
```

→ プレイヤーが「相手の攻撃を見てガード→崩して反撃」する駆け引きを、**Mob 側で予備動作読み（`attack_level`）＋スタミナ管理（`stamina`）＋崩し追撃（`guard_break`）** として再現できる。

---

## 6. 層3：Java 実装で `PlayerPatch` の状態を移植

データパックで表現できない「プレイヤーの今の状態を動的に Mob へ移す」「独自のスタミナ運用」等。`EpicFight_MobMimic_Spec.md` の capability 設計と統合する。

### 6.1 プレイヤーの現在状態を読み取る

```java
// 周囲プレイヤーのスタミナ / スタイルを読む（バージョン依存・Bridge 隔離）
ServerPlayerPatch pp = (ServerPlayerPatch)
    EpicFightCapabilities.getEntityPatch(player, PlayerPatch.class);
// 例: float stamina = pp.getStamina();  // メソッド名は要確認
//     Style style   = pp.getCurrentStyle(weaponCap); 等
```

> ⚠️ `getStamina` / スタイル取得などのメソッド名は **バージョン依存**。実在を対象バージョンのソースで確認し、`try/catch(Throwable)` で保護する。

### 6.2 Mob 側 capability に保存（MobMimic と統合）

`MobMimicCapability`（MobMimic Spec）に「観測したプレイヤースタミナ運用」「採用スタイル」を保持し、Mob の行動傾向へ反映する。**学習（MobMimic）＋ 状態移植（本書）** を 1 つの capability で扱う。

### 6.3 Mob 用スタミナの自前管理（Indestructible を使わない場合）

```java
public class MobStamina implements INBTSerializable<CompoundTag> {
    private float stamina, max = 30f, regenPerTick = 0.1f;

    public void tick() { stamina = Math.min(max, stamina + regenPerTick); }
    public boolean tryConsume(float cost) {
        if (stamina < cost) return false;   // スタミナ切れ → 強攻撃を撃たない
        stamina -= cost; return true;
    }
}
```

→ AI Goal 側で「強攻撃の前に `tryConsume`」を呼び、スタミナ切れ時は通常攻撃にフォールバック。プレイヤーのスタミナ駆け引きを Mob で再現。

### 6.4 `EpicFightBridge`（ソフト依存ラッパ・再掲方針）

- 依存は `compileOnly` + `mandatory=false`、Indestructible も任意依存として扱う
- `ModList.get().isLoaded("epicfight")` / `isLoaded("indestructible")` ガード
- すべての API 呼び出しを `try/catch(Throwable)` 境界で包み、未導入時はバニラ挙動へフォールバック
- バージョンで揺れる箇所（`Animations.*` 直接 vs `AnimationAccessor`、`PlayerPatch` のメソッド名、Forge/NeoForge のバス）を 1 箇所に集約

---

## 7. 解析：jar からプレイヤーシステムのパラメータを抽出

`mc-boss-analyzer` / `SkillToMob` の解析パイプラインに、**システムパラメータ** の抽出を追加する。

### 7.1 抽出対象

| 対象 | 取得元 | 用途 |
|------|--------|------|
| スタミナ最大値 / 回復速度 | データパック（スキル/プレイヤー設定）or コード | 層2/層3 のスタミナ値 |
| スキルのスタミナ消費 / CD | スキルパラメータ JSON | Mob 用に再設計（プレイヤー値の流用は避ける） |
| ガードのパラメータ（軽減率等） | コード | ガード AI の挙動 |
| スタン耐性 / スタンシールド量 | コード / 設定 | 層2 設定 |
| 既存ボスの `advanced_mobpatch` | 他 Mod / Indestructible example | **そのまま流用できるテンプレ** |

### 7.2 IR への統合

`SkillToMob` の中間表現(IR)に `system_params` を追加：

```json
{
  "system_params": {
    "stamina": { "max": 30, "regen_per_tick": 0.1 },
    "stun_shield": { "amount": 10 },
    "guard": { "weapon": "sword", "animation": "indestructible:guard/guard_sword" }
  }
}
```

---

## 8. 外部データの活用と最適化

| ソース | 取得できるもの | 用途 |
|--------|----------------|------|
| Indestructible の example データパック | 完成した `advanced_mobpatch`（stamina/guard/predicate の実記述） | **最良のテンプレ**。フィールド名確定にも使う |
| 他 EpicFight 系ボス Mod の `epicfight_mobpatch` / `advanced_mobpatch` | combat_behavior・システム設定の実例 | 流用・改変 |
| EpicFight 本体 `Animations`（`BIPED_MOB_*` 等） | Mob 用アニメ | アニメ ID 供給 |
| Bosses' Rise 等（オープンソース） | フェーズ・行動選択の設計思想 | 方式の参考 |

**最適化の観点**

- スタミナ運用：`stamina` predicate + `cooldown` で「スタミナ切れで隙ができる」演出
- ガード頻度：パリィ（`attack_level`）の `weight` を上げすぎると理不尽 → 体感調整
- フェーズ：`phase` / `health(less_ratio)` で HP 帯ごとに別 behavior series
- 難易度：`stun_shield` 量・`impact`・`armor_negation` でバランス
- 自動生成：IR → `advanced_mobpatch` JSON 起草（LLM 支援＋人手検証）

---

## 9. バージョン互換性の注意

| 項目 | 値 / 注意 |
|------|-----------|
| データパック `pack_format`（1.20.1） | `15` |
| Indestructible 対応 | 1.16.5 / 1.18.2 / 1.19.2 / 1.20.1（Forge）。**EFM 16.6.5・18.3.8 以降のみサポート**、それ未満は非対応 |
| `advanced_mobpatch` パス | 最新版は `data/<modid>/advanced_mobpatch/...`（旧版は別パス）。導入する Indestructible バージョンに合わせる |
| EpicFight 本体 API | クラス名・メソッド名・アニメ参照方式（`Animations.*` vs `AnimationAccessor`）はバージョン依存。`EpicFightBridge` に隔離 |
| `PlayerPatch` 状態取得 API | `getStamina` 等の有無・名称はバージョン依存。要ソース確認 |

---

## 10. 実装マイルストーン

| フェーズ | 内容 | 成果物 |
|---------|------|--------|
| **P1** | `PlayerPatch`/`MobPatch` 構造の確定、転用マトリクス確定 | 本書の対応表（対象バージョンで検証済み） |
| **P2** | 層1：`epicfight_mobpatch` で基本戦闘（スタイル・コンボ・基本スタン） | 動く戦闘 Mob |
| **P3** | 層2：`advanced_mobpatch` でスタミナ/ガード/パリィ/スタンシールド | システム移植済みボス |
| **P4** | 層3：`PlayerPatch` 状態読取 + Mob capability + 自前スタミナ | 動的移植・MobMimic 統合 |
| **P5** | 解析：jar からシステムパラメータ抽出 → IR 統合 | システム込み IR |
| **P6** | 最適化・ゲーム内検証（スタミナ運用・パリィ頻度・体感） | バランス調整済み定義 |

---

## 11. 既知の課題・検証不能項目

- **`advanced_mobpatch` のスタミナ / スタンシールドの正確な JSON フィールド名**：公式 example データパック（Indestructible の CurseForge Files）で要確認。本書の JSON 例は構造の概念図。
- **`PlayerPatch` 状態取得 API**（`getStamina` 等）：バージョン依存。ヘッドレス環境では実挙動確認不可。
- **依存の増加**：Indestructible を入れると前提が 2 つになる。ソフト依存で包み、未導入時は層1 のみで動作するようフォールバックを用意。
- **パリィ AI の体感**：`attack_level` 反応はフレーム単位の挙動でテスト必須。理不尽にならない `weight`/`cooldown` 調整が必要。
- **EpicFight / Indestructible 更新による破壊的変更**：クラス名・パス・フィールドは対象バージョンで必ず再確認。

---

## 付録：参照（一次情報）

- Epic Fight 公式 Wiki：`https://epicfight-docs.readthedocs.io/`
  - Getting started（パッチ・API）：`/API/Starting/`
  - Custom entity datapack（`epicfight_mobpatch`）：`/Guides/Entities/page1/`
  - Mob Capabilities Editor：`/Guides/Entities/page2/`
  - Skills（スキル分類）：`/Misc/Gameplay/skills/`
- Epic Fight - Indestructible（`advanced_mobpatch` / 追加 predicate / ガードアニメ）
  - CurseForge：`https://www.curseforge.com/minecraft/mc-mods/epic-fight-indestructible`
  - GitHub（tutorial / example）：`https://github.com/Cyber2049/Epic-Fight---Indestructible`
- EpicFight 本体ソース（クラス照合）：`https://github.com/Epic-Fight/epicfight`
- 関連自ドキュメント：`EpicFight_SkillToMob_Spec.md` / `EpicFight_MobMimic_Spec.md` / `docs/epicfight/14`
