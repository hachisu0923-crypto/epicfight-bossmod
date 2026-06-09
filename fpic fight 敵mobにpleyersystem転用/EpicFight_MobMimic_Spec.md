# EpicFight プレイヤー戦闘スタイルの動的学習（MobMimic）設計仕様書

> 周囲の **プレイヤーが実際にどう戦っているか** を実行時に観察し、その傾向（使用武器・スタイル・コンボ癖・スキル頻度・スタミナ運用）を敵 Mob の行動へ反映する仕組みの設計書。
> [`EpicFight_SkillToMob_Spec_v2.md`](EpicFight_SkillToMob_Spec_v2.md)（静的解析・移植）と [`EpicFight_PlayerSystemToMob_Spec_v2.md`](EpicFight_PlayerSystemToMob_Spec_v2.md)（状態・システム移植）が両文書から繰り返し参照する **姉妹編**。本書はそれらを「実行時の観察・学習」という時間軸で束ねる。

**前提**：本書は [`EpicFight_Common_Spec.md`](EpicFight_Common_Spec.md) を土台とする。クラス階層・状態取得・`EpicFightBridge`・バージョン互換は **Common を参照**。本書は「観察→学習→反映」のループに固有の論点を扱う。

**関連ドキュメント**

| ドキュメント | 役割 | 本書との関係 |
|--------------|------|--------------|
| [`EpicFight_Common_Spec.md`](EpicFight_Common_Spec.md) | 共通基盤 | クラス階層 / 状態取得 / Bridge の参照元 |
| [`EpicFight_SkillToMob_Spec_v2.md`](EpicFight_SkillToMob_Spec_v2.md) | スキル / モーションの静的解析→転用 | **静的（事前）**。本書は学習結果でその選択を動的に調整 |
| [`EpicFight_PlayerSystemToMob_Spec_v2.md`](EpicFight_PlayerSystemToMob_Spec_v2.md) | 戦闘システムの移植 | §6.2 で「学習＋状態移植を 1 capability で扱う」と明記。本書がその学習側を実体化 |
| [`EpicFight_BaseModBehavior_Spec.md`](EpicFight_BaseModBehavior_Spec.md) | 本体mod導入時の挙動 | **土台**。観察対象＝プレイヤー側戦闘システムの実挙動（スキル/スタミナ/ガード等）を記述 |

---

## 目次

1. [全体像：静的移植と動的学習の違い](#1-全体像静的移植と動的学習の違い)
2. [観察モデル（何を・いつ・どこで）](#2-観察モデル何をいつどこで)
3. [`MobMimicCapability` 設計](#3-mobmimiccapability-設計)
4. [学習モデル（蓄積・減衰・正規化）](#4-学習モデル蓄積減衰正規化)
5. [学習→行動への反映](#5-学習行動への反映)
6. [状態移植との統合（PlayerSystem 層3）](#6-状態移植との統合playersystem-層3)
7. [静的解析データとの併用](#7-静的解析データとの併用)
8. [実装マイルストーン](#8-実装マイルストーン)
9. [既知の課題・検証不能項目](#9-既知の課題検証不能項目)

---

## 1. 全体像：静的移植と動的学習の違い

3 文書は「いつ情報を得るか」で役割が分かれる。

| 観点 | SkillToMob / PlayerSystem（静的） | **MobMimic（本書・動的）** |
|------|-----------------------------------|----------------------------|
| 情報源 | jar / データパックの**事前解析** | **実行時**の周囲プレイヤー観察 |
| タイミング | ビルド時・データパック作成時 | ゲームプレイ中（tick ループ） |
| 出力 | `epicfight_mobpatch` / Java パッチ | capability に蓄えた傾向値 → 行動調整 |
| 例 | 「ゾンビは sweeping_edge を撃てる」 | 「このプレイヤーはガード多用 → Mob のガード頻度を上げる」 |
| 強み | 確定的・再現可能・サーバー負荷なし | プレイヤーごとに適応・飽きにくい |

### 1.1 なぜ動的学習を足すのか

静的転用だけだと、敵の行動は「事前に決めた確率テーブル（`weight`/`cooldown`）」で固定される。
MobMimic は、その確率テーブルを **対戦相手の癖に合わせて実行時に微調整** することで、「プレイヤーの戦い方を学ぶ敵」という体験を作る。

### 1.2 設計原則

- **静的をベース、動的は補正**：行動の土台は静的（mobpatch / Java）で作り、MobMimic は `weight` 倍率・条件のしきい値を **±で補正** するに留める（暴走防止）。
- **サーバー権威**：観察も学習も**サーバー側**で行う（`ServerPlayerPatch` 状態はサーバーが権威。Common §1）。
- **ソフト依存**：EpicFight 状態の読取はすべて `EpicFightBridge`（Common §7）経由。未導入時はバニラ的挙動へフォールバック。
- **軽量**：毎 tick の重い解析は避け、サンプリング（間引き）と減衰でコストを抑える（§4）。

---

## 2. 観察モデル（何を・いつ・どこで）

### 2.1 観察対象（プレイヤーの戦い方の特徴量）

| 特徴量 | 取得元 | 学習で使う意味 |
|--------|--------|----------------|
| 使用武器カテゴリ | 装備の `WeaponCapability`（Common §5 の許可値） | Mob が「対策する武器像」を持つ |
| 採用スタイル（one_hand / two_hand 等） | `PlayerPatch` のスタイル（§6 / Common §1.2） | 間合い・対応行動の傾向 |
| 攻撃頻度 / コンボ段数の癖 | 攻撃アニメ開始イベントの頻度 | Mob のガード/回避頻度の補正 |
| ガード / パリィの多用 | ガード状態・`attack_level` 反応の観測 | Mob の崩し（フェイント・ガード不能技）寄せ |
| 回避（Roll/Step）の多用 | 回避アニメ・移動パターン | Mob の追尾・範囲技寄せ |
| スキル使用頻度 | スキルモーション再生の観測 | Mob の警戒・カウンター寄せ |
| スタミナ運用（撃ち切る/温存） | `PlayerPatch` スタミナ推移（要 API・§6） | Mob のスタミナ攻め（枯渇待ち）寄せ |

### 2.2 観察のタイミングと範囲

```
[毎 N tick（例 N=10）サンプリング]
  対象 = Mob の現在ターゲット（または半径 R 内の最も近いプレイヤー）
  for 対象プレイヤー:
      patch = EpicFightBridge.getPlayerPatch(player)   // null ならスキップ
      観測 = { weaponCat, style, isGuarding, staminaRatio, ... }
      mimicCap.observe(観測)                            // §3

[イベント駆動（補助）]
  プレイヤーの攻撃/ガード/スキル開始イベントをフックし、
  頻度カウンタ（§4.1）をインクリメント
```

- **N tick 間引き**：毎 tick 取得はコスト過大。10 tick（0.5 秒）程度で十分。
- **対象限定**：ワールド全プレイヤーではなく「交戦中の相手」に絞る。

> 🔍 攻撃/ガード/スキル開始の検知方法（イベント名・フック点）は EpicFight のバージョンで異なる。`EpicFightBridge` に隔離し、無ければサンプリングのみで縮退運転する。

---

## 3. `MobMimicCapability` 設計

Mob 1 体ごとに付与し、観測した傾向を保持する capability。**MobMimic（学習）と PlayerSystem 層3（状態移植）を 1 capability で扱う**（PlayerSystem §6.2 の方針を実体化）。

```java
public class MobMimicCapability implements INBTSerializable<CompoundTag> {

    // ── 学習した傾向（0〜1 に正規化した EMA） ─────────────
    private float guardTendency   = 0f;   // 相手のガード多用度
    private float dodgeTendency    = 0f;   // 相手の回避多用度
    private float skillTendency    = 0f;   // 相手のスキル多用度
    private float aggression       = 0f;   // 攻撃頻度
    private WeaponCat observedWeapon = WeaponCat.UNKNOWN;
    private Style     observedStyle  = Style.COMMON;

    // ── 状態移植（PlayerSystem 層3 と共有） ───────────────
    private float observedStaminaRatio = 1f;   // 相手のスタミナ運用（枯渇しがちか）

    /** §2 のサンプリングから 1 観測を取り込む */
    public void observe(Observation o) {
        // 指数移動平均（EMA）で滑らかに更新（§4.2）
        guardTendency = ema(guardTendency, o.isGuarding ? 1f : 0f);
        observedStaminaRatio = ema(observedStaminaRatio, o.staminaRatio);
        if (o.weaponCat != WeaponCat.UNKNOWN) observedWeapon = o.weaponCat;
        observedStyle = o.style;
        // …頻度カウンタ由来の値も同様に更新
    }

    /** 毎 tick：使われない傾向は中立(0)へ減衰（§4.3） */
    public void decay() {
        guardTendency *= DECAY;  dodgeTendency *= DECAY;
        skillTendency *= DECAY;  aggression    *= DECAY;
    }

    // getter 群（§5 の行動反映が参照）
    public float guard() { return guardTendency; }
    public float dodge() { return dodgeTendency; }
    public float skill() { return skillTendency; }
    public float staminaRatio() { return observedStaminaRatio; }

    @Override public CompoundTag serializeNBT() { /* 各 float / enum を保存 */ return new CompoundTag(); }
    @Override public void deserializeNBT(CompoundTag t) { /* 復元 */ }
}
```

- 付与・同期は Forge capability の標準手順（`AttachCapabilitiesEvent`）。**学習値はサーバー保持**で十分（描画に不要なら同期不要 → 帯域節約）。
- enum（`WeaponCat` / `Style`）は Common §5 の許可値に対応させる。

---

## 4. 学習モデル（蓄積・減衰・正規化）

過学習・暴走を避けるため、**滑らかに上げ／使われなければ中立へ戻す**。

### 4.1 頻度カウンタ → レート

イベント駆動の生カウント（攻撃 n 回 / ガード g 回 …）を、一定窓（例 100 tick）の **レート**へ変換してから EMA に入れる。生カウントを直接使うと長時間プレイで飽和するため。

### 4.2 指数移動平均（EMA）で滑らかに

```java
private static final float ALPHA = 0.1f;   // 学習率（小さいほど慎重）
static float ema(float prev, float sample) {
    return prev + ALPHA * (sample - prev);
}
```

- `ALPHA` が大きいと敏感（すぐ寄る）・小さいと安定。0.05〜0.2 を体感で調整 ⚠️。

### 4.3 減衰（使われない傾向を忘れる）

```java
private static final float DECAY = 0.995f;  // 毎 tick わずかに 0 へ
```

- 相手が戦法を変えたら、古い傾向は数十秒で薄れる。**「学んだまま固着」を防ぐ**。

### 4.4 クランプ

すべての傾向値は `[0,1]` にクランプ。§5 の補正でも、最終 `weight` 倍率は例えば `[0.5, 2.0]` に制限し、行動の偏りすぎを防ぐ。

---

## 5. 学習→行動への反映

学習値は **静的に定義した行動の選択確率を補正する** ために使う（行動そのものを生成はしない）。反映先は 2 系統。

### 5.1 ルート A（データパック）への反映：層3 ブリッジで `weight` を補正

データパックの `combat_behavior` は静的だが、Java 側 AI（`AnimatedAttackGoal` 相当）が behavior を選ぶ瞬間に **学習値で重み付けを補正**する。

| 観測 | 補正する Mob 行動 | 方向 |
|------|-------------------|------|
| `guard()` 高（相手がガード多用） | ガード崩し技 / ガード不能技 / 投げ | weight ↑ |
| `dodge()` 高（相手が回避多用） | 範囲技 / 追尾突進 / ディレイ攻撃 | weight ↑ |
| `skill()` 高（相手がスキル多用） | カウンター姿勢（`attack_level` 反応） | weight ↑ |
| `staminaRatio()` 低（相手が息切れ気味） | 攻めの連打 series | weight ↑ |

```java
// behavior 選択時の重み補正（概念）
float w = baseWeight;                       // 静的 weight
if (behavior.tag == GUARD_BREAK)  w *= 1f + mimic.guard();   // 最大2倍
if (behavior.tag == AREA_ATTACK)  w *= 1f + mimic.dodge();
w = Mth.clamp(w, baseWeight * 0.5f, baseWeight * 2f);        // §4.4 クランプ
```

### 5.2 ルート B（Java）への反映：条件しきい値の動的化

ルート B（[`EpicFight_SkillToMob_Spec_v2.md`](EpicFight_SkillToMob_Spec_v2.md) §5）の `CombatBehaviors` 述語に、学習値依存のしきい値を混ぜる。

```java
.predicate(p -> {
    float guardLv = mimic(p).guard();
    // 相手のガードが多いほど“早めに”崩しに行く（間合いしきい値を広げる）
    float reach = 2.5f + guardLv * 1.0f;
    return p.getTarget() != null && p.getOriginal().distanceTo(p.getTarget()) < reach;
})
```

### 5.3 暴走防止（必須）

- 補正は**倍率の範囲を固定**（§4.4）。学習で「常に最強行動」へ偏らせない。
- 減衰（§4.3）で固着を防ぐ。
- フォールバック：`mimic` が無い / EpicFight 未導入時は補正を 1.0（無補正）にし、静的挙動のまま動く。

---

## 6. 状態移植との統合（PlayerSystem 層3）

本書の `observedStaminaRatio` 等は、[`EpicFight_PlayerSystemToMob_Spec_v2.md`](EpicFight_PlayerSystemToMob_Spec_v2.md) §6.1 の「プレイヤー現在状態の読取」と**同じ取得経路**を使う。

- 読取：`EpicFightBridge.getPlayerStamina(player)` 等（🔍 メソッド名はバージョン依存・要確認）
- 保持：本書 `MobMimicCapability`（1 capability に学習＋状態を集約）
- 利用：
  - **学習**（本書 §5）… 相手の運用傾向を Mob の攻め方へ
  - **状態移植**（PlayerSystem §6.3）… Mob 自身のスタミナ運用パラメータの初期値・調整に観測値を流用

> これにより PlayerSystem §6.2 が述べる「学習（MobMimic）＋ 状態移植（PlayerSystem）を 1 つの capability で扱う」が成立する。

---

## 7. 静的解析データとの併用

MobMimic は静的解析（SkillToMob / PlayerSystem の IR）と**競合せず補完**する。

| フェーズ | 静的（IR / mobpatch） | 動的（MobMimic） |
|---------|------------------------|-------------------|
| 行動の**集合**を決める | ◎ どの技を撃てるか | — |
| 行動の**初期確率** | ◎ `weight` / `cooldown` | — |
| 行動の**実行時補正** | — | ◎ 相手の癖で weight・しきい値を ± |
| パラメータの**初期値** | ◎ 解析で推定 | ○ 観測で上書き調整 |

→ **静的で骨格、動的で肉付け**。MobMimic 単体では何も撃てない（撃てる技の定義は静的側が供給する）。

---

## 8. 実装マイルストーン

| フェーズ | 内容 | 成果物 |
|---------|------|--------|
| **P1** | 観察フックの確定（サンプリング点・イベント点）、Bridge に読取 API を隔離 | 観察層（縮退運転対応） |
| **P2** | `MobMimicCapability` 実装（付与・NBT・EMA・減衰） | 学習 capability |
| **P3** | ルート A 補正（behavior 選択時の weight 補正） | 学習が効くデータパック Mob |
| **P4** | ルート B 補正（述語しきい値の動的化）＋ PlayerSystem 層3 統合 | 学習＋状態移植ボス |
| **P5** | 体感調整（`ALPHA`/`DECAY`/補正レンジ）、暴走防止検証 | バランス調整済み |
| **P6** | 検証：学習が体感できるか・理不尽でないか・負荷 | テストログ |

---

## 9. 既知の課題・検証不能項目

- 🔍 **状態取得・イベントフックの API**：プレイヤースタミナ/スタイル取得、攻撃/ガード/スキル開始の検知点はバージョン依存。`EpicFightBridge` で隔離し、無ければサンプリングのみで縮退（Common §7）。
- ⚠️ **学習パラメータの体感**（`ALPHA` / `DECAY` / 補正レンジ）：フレーム/秒単位の挙動でゲーム内テスト必須。
- ⚠️ **理不尽さ**：学習で常に最適手を取ると「読み合い」でなく「後出し」になる。補正レンジ固定・減衰・わずかな乱数で人間味を残す。
- **マルチプレイの公平性**：複数プレイヤー相手では「誰の癖を学ぶか」（ターゲット限定 / 加重平均）を明確化。
- **負荷**：観察は N tick 間引き・対象限定でコストを抑える。大量湧き Mob には学習を付けない（ボス限定推奨）。
- 🔍 **EpicFight 更新による破壊的変更**：状態取得経路が変わりうる。Bridge と `try/catch(Throwable)` で局所化。
