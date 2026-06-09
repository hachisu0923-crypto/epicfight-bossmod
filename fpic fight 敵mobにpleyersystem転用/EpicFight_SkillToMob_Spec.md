# EpicFight プレイヤースキルの敵Mob転用 設計仕様書

> EpicFight のプレイヤー用スキル / モーションを **解析** し、敵 Mob が使用できるよう **転用** する仕組みの設計書。
> mod ファイル (jar / データパック) からの仕様・モーション抽出、および「データパック方式」「Java 方式」両面での転用、外部データを使った最適化までを扱う。

**対象環境**

| 項目 | 値 |
|------|------|
| Minecraft | 1.20.1 |
| Modloader | Forge (47.x 系) |
| 前提 / ソフト依存 Mod | EpicFight (`epicfight`) |
| 推奨補助 | GeckoLib 4.x（独自アニメ用 / 任意）、SmartBrainLib（ボス AI / 任意） |
| 言語 | Java 17 / データパック JSON / Python（解析スクリプト） |
| 権威ある参照元 | Epic Fight 公式 Wiki `https://epicfight-docs.readthedocs.io/` |

**関連ドキュメントとの位置づけ**

| ドキュメント | 役割 | 本書との関係 |
|--------------|------|--------------|
| `docs/epicfight/11` 武器データパック | 武器の登録・ムーブセット | 武器カテゴリ定義の参照元 |
| `docs/epicfight/12` ボス実装 | ボスエンティティ本体 | 転用先の Mob 実装 |
| `docs/epicfight/13` 他 Mod 武器連携 | サードパーティ武器 | 武器タイプ解析と共通 |
| `docs/epicfight/14` 武器ムーブセットのボス転用 | 武器→ボスへの moveset 移転 | **本書の前段**。武器単位の転用を扱う |
| `EpicFight_MobMimic_Spec.md` | プレイヤー戦闘スタイルの観察・学習 | **本書の姉妹編**。あちらは「動的学習」、本書は「静的解析・移植」 |
| **本書（15）プレイヤースキルの敵Mob転用** | スキル / モーションの解析と転用 | doc14（武器）を **スキル単位** に拡張 |

---

## 1. 全体像

### 1.1 本書が解く問題

EpicFight のプレイヤーは、武器コンボに加えて **スキル**（Sweeping Edge、Battojutsu、Blade Rush、Meteor Slam 等）を使える。これらの派手なモーションを **敵 Mob に使わせたい**。
ただし後述のとおり「スキルオブジェクトをそのまま Mob で実行する」のは構造的に困難なため、本書は **スキルを構成要素に分解し、Mob が扱える形（アニメーション + 戦闘行動定義）へ再構成する** アプローチを取る。

### 1.2 なぜ「スキルの直接移植」は難しいのか

EpicFight のスキルはプレイヤー側 (`PlayerPatch`) に強く結合している。

- スキルは `SkillContainer` / `SkillSlot` で管理される
- `SkillContainer` のサーバー側実行経路（`getServerExecutor` に相当する処理）は **`ServerPlayerPatch` へのキャスト** を前提とする
- Mob 側の `MobPatch` / `HumanoidMobPatch` には `SkillContainer` が標準で存在しない

→ スキルインスタンスを Mob から `execute` しようとすると、`ClassCastException` 等のクラッシュ要因になる。**スキルの「ロジック層」をそのまま借りるのは高リスク**。

> ⚠️ クラス名・メソッド名（`SkillContainer`, `getServerExecutor`, `ServerPlayerPatch` 等）は **バージョン依存**。実装前に対象 EpicFight バージョンの該当パッケージ `yesman.epicfight.skill` を確認すること。

### 1.3 現実的な転用戦略：スキルを 3 層に分解する

| 層 | スキルにおける実体 | Mob への転用方法 |
|----|--------------------|------------------|
| **モーション層** | `AttackAnimation` / `StaticAnimation`（見た目・判定タイミング） | データパックの `combat_behavior` で直接再生 / Java で `AttackAnimation` 再構築 |
| **効果層** | ダメージ・コリジョン・ノックバック・状態異常（多くは `AttackAnimation` に内蔵） | モーション層と同時に付与される。スタミナ・クールダウン等のプレイヤー固有要素は **破棄して Mob 用に再設計** |
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
| **Guard**（ガード） | Guard, Parrying, Impact Guard | 被弾リアクション | △ | ガードアニメ再生 + 被弾割込み AI が必要 |
| **Mobility**（移動） | Demolition Leap, Phantom Ascent | 移動補助 | △ | 限定的。移動 AI と組み合わせ |
| **Passive**（パッシブ） | Berserker, Sword Master, Stamina Pillager, Forbidden Strength | ステータス補正 | ◎（別手段） | スキル移植不要。`attributes` や属性 modifier で **数値的に再現** |

**重要な指針**

- **◎ Weapon Innate / Revelation** … 攻撃モーション系。最も転用が容易で効果も大きい。最優先で扱う。
- **◎ Passive** … 移植する必要が**ない**。Berserker（低 HP で火力上昇）等は Mob の attribute modifier やボスのフェーズ強化で代替する方が安全。
- **△ Dodge / Guard / Mobility** … プレイヤー入力 / 被弾に反応する性質のため、Mob では AI Goal の実装が別途必要。アニメーション自体は再生可能（`BIPED_ROLL_FORWARD`, `BIPED_BLOCK` 等）。

### 2.2 スキルの内部クラス（解析時に押さえる）

| クラス（参考） | パッケージ | 役割 |
|----------------|-----------|------|
| `Skill` | `yesman.epicfight.skill` | スキル基底 |
| `SkillContainer` | 同上 | プレイヤーのスキル保持・実行（**ServerPlayerPatch 結合**） |
| `SkillSlot` / `SkillSlots` | 同上 | スロット定義（組み込みは `SkillSlots`） |
| `SkillCategories` | 同上 | カテゴリ（PASSIVE / IDENTITY / DODGE / GUARD 等） |
| `SkillDataManager` | 同上 | スキルの実行時データ |
| `AttackAnimation` | `yesman.epicfight.api.animation.types` | スキルが再生する攻撃アニメ（コリジョン・フェーズ内蔵） |

> ⚠️ いずれも **バージョン依存**。`SkillSlot` を自 Mod で拡張する場合は公式 Wiki「Registering custom skill slots」の手順（`SkillSlot.ENUM_MANAGER.assign` / `registerEnumCls`）に従う。ただし **本書の転用では基本的にスロット拡張は不要**（Mob はスロットを使わないため）。

### 2.3 スキル / 武器のデータパック上書き

EpicFight はスキルや武器のパラメータをデータパックで上書きできる。**これも解析・最適化の対象**：

- スキルのスタミナ消費・クールダウン・倍率 等のパラメータ JSON
- 武器タイプ定義（`weapon_categories` のコンボ割り当て）

→ 既存 Mod の jar 内データパックを読み取れば、「どの武器がどのコンボ / スキルを持つか」を**コードを逆コンパイルせずに**把握できる場合がある。

---

## 3. mod ファイル解析パイプライン

「modファイルを読み込み仕様や motion を解析し転用する」中核。`mc-boss-analyzer` / `mc-texture-gen` スキルの解析フローと統合する。

### 3.1 解析対象（jar / データパック内の場所）

| パス（典型） | 内容 | 抽出物 | 逆コンパイル要否 |
|--------------|------|--------|:---:|
| `data/<modid>/animmodels/animations/**.json` 等 | アニメーションのキーフレームデータ | モーション本体、尺、ボーン軌道 | 不要 |
| `data/<modid>/epicfight_mobpatch/*.json` | 既存の Mob 戦闘定義 | combat_behavior, livingmotions, attributes の **実例** | 不要 |
| `data/<modid>/.../weapon` 系 JSON | 武器タイプ / カテゴリ割当 | weapon_category ↔ コンボ対応 | 不要 |
| スキルパラメータ JSON（あれば） | スタミナ・CD・倍率 | バランス値 | 不要 |
| `*.class`（`Animations` 相当 / `Skill` サブクラス） | アニメ登録、`AttackAnimation` のコリジョン・フェーズ時刻、ダメージ係数 | フェーズタイミング、判定ボックス、付随効果 | **必要** |

> 📌 EpicFight のアニメーションを **データパック側から参照** する際の ID は、登録時のリソースパス（例：`epicfight:biped/combat/mob_onehand1`）。jar 内のアニメファイルパスと `Animations` クラスの登録名を突き合わせて対応表を作る。

### 3.2 解析フェーズ

```
[Phase 0] 入力受付
    jar / zip を展開 → ファイル種別を分類（animation json / mobpatch json / class）

[Phase 1] 構造スキャン
    - epicfight_mobpatch/*.json を列挙 → 既存 combat_behavior を収集
    - アニメーション json を列挙 → モーション ID 一覧化
    - 必要なら .class を逆コンパイル → AttackAnimation のフェーズ / コリジョン / ダメージを抽出

[Phase 2] モーション仕様の正規化
    抽出物を「中間表現(IR)」へ変換（3.3 のスキーマ）
    フェーズ時刻・判定・武器カテゴリ・スタイルを統一フォーマットに

[Phase 3] 転用ターゲットへのマッピング
    IR → 転用先 Mob の epicfight_mobpatch JSON（ルートA）
       → もしくは Java の AttackAnimation / CombatBehaviors（ルートB）
```

### 3.3 中間表現（IR）スキーマ

解析結果を保持する、転用ルート非依存の中間 JSON。

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
      "style": "one_hand",
      "player_only_traits": {
        "stamina_cost": 6,
        "cooldown_ticks": 200
      }
    }
  ]
}
```

- `phases` … `AttackAnimation` のフェーズ時刻（先読み / 予備動作 / 判定発生 / 硬直）。Mob のヒット感を決める。
- `player_only_traits` … スタミナ・CD 等の **プレイヤー固有要素**。Mob では破棄し、`cooldown`（tick）として AI 用に**再設計**する。

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

EpicFight 標準の `epicfight_mobpatch` データパックで、**コードを書かずに** 敵 Mob へ戦闘行動を付与する。出典：Epic Fight 公式 Wiki「Custom entity datapack」。

### 4.1 データパックの基本

- 配置：`data/<modid>/epicfight_mobpatch/<entityname>.json`
  （`<entityname>` は対象エンティティのレジストリ名。`/summon` の Tab 補完で確認可能）
- `pack.mcmeta` の `pack_format`：**1.20.1 = `15`**（参考：1.21.1 = 48）

### 4.2 mobpatch JSON の主要フィールド

| フィールド | 意味 |
|------------|------|
| `model` | 使用モデル（例 `epicfight:entity/biped_old_texture`） |
| `armature` | スケルトン（例 `epicfight:entity/biped`） |
| `renderer` | レンダラ（バニラ登録名、例 `minecraft:zombie`） |
| `isHumanoid` | `true` で **持っている武器に応じてモーションを切替**（武器別スキル転用の鍵） |
| `faction` | 同陣営は同士討ちしない。`enderman` / `piglins` / `wither` / `neutral` / `undead` / `illager` / `villager` |
| `attributes` | `impact`（スタン延長）/ `armor_negation`（防御貫通 %）/ `max_strikes`（多段ヒット数）/ `chasing_speed`（追跡速度）/ `scale`（サイズ） |
| `default_livingmotions` | `idle` / `walk` / `chase` / `fall` / `death` / `mount` の各モーション |
| `stun_animations` | `short` / `long` / `knockdown` / `fall` の被弾モーション |
| `combat_behavior` | **攻撃ムーブ定義**（非 humanoid 用 / humanoid 用で書式が変わる） |
| `humanoid_weapon_motions` | `isHumanoid:true` 時の、武器カテゴリ別 living motion |

簡易設定として `{"preset": "minecraft:creeper"}`（バニラクラスを継承するエンティティ向け）、無効化として `{"disabled": true}` も使える。

### 4.3 プレイヤースキルのモーションで攻撃させる（核心）

EpicFight 本体にはプレイヤースキルのアニメ定数が揃っている（`SWEEPING_EDGE`, `DANCING_EDGE`, `BATTOJUTSU`, `BLADE_RUSH_*`, `METEOR_SLAM`, `TSUNAMI`, `EVISCERATE_*` 等）。
これらの **登録パス** を `combat_behavior` の `animation` に指定すれば、Mob がそのモーションで攻撃する（モーションに内蔵された判定でダメージも発生する）。

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
              "animation": "epicfight:skill/sweeping_edge" }
          ]
        }
      ]
    }
  ]
}
```

> 📌 **要検証**：プレイヤースキルアニメの正確なデータパック参照パス（`epicfight:skill/...` か `epicfight:biped/combat/...` か等）は、`Animations` クラスの登録名から解析して確定する（3.1）。上例の `epicfight:skill/sweeping_edge` はプレースホルダ。

### 4.4 `combat_behavior` / `conditions` の仕様

**behavior series**

| キー | 意味 |
|------|------|
| `weight` | このシリーズが選ばれる確率 `weight / Σweight` |
| `canBeInterrupted` | 動作シリーズを中断できるか |
| `looping` | 中断時に現在の動作を保持するか |
| `cooldown` | 再使用までの tick |
| `behaviors` | 実際の (条件, アニメ) のリスト |

**conditions（発動条件＝プレイヤー入力の代替）**

| predicate | 判定内容 | 引数 |
|-----------|----------|------|
| `random_chance` | 乱数がしきい値超か | `chance`: double |
| `within_eye_height` | Y 距離が攻撃者の目線高以下か | — |
| `within_distance` | 対象との距離が範囲内か | `min`, `max`: double |
| `within_angle` | 対象への角度が範囲内か | `min`, `max`: double |
| `within_angle_horizontal` | Y 軸方向角度が範囲内か | `min`, `max`: double |
| `health` | 自身の HP 条件 | `health`: double, `comparator`: `greater_absolute`/`less_absolute`/`greater_ratio`/`less_ratio` |

→ プレイヤーの「キー入力で発動」は、Mob では `within_distance`（間合い）・`health`（HP 連動）・`random_chance`（ばらけさせる）の組合せで再現する。**ボスらしい「HP が減るほど派手なスキルを使う」挙動はここで作る**。

### 4.5 武器別ムーブセット（`humanoid_weapon_motions`）

`isHumanoid: true` のとき、武器カテゴリごとに living motion と combat behavior を切り替えられる。
プレイヤーが剣 / 槍 / 大剣で別コンボを持つのと同様に、**敵が持ち替えた武器に応じてプレイヤー由来のコンボを使う** よう構成できる。

- `weapon_categories` 許可値：`AXE, FIST, GREATSWORD, HOE, PICKAXE, SHOVEL, SWORD, UCHIGATANA, SPEAR, TACHI, TRIDENT, LONGSWORD, DAGGER, SHIELD, RANGED`
- `style` 許可値：`one_hand`, `two_hand`, `common`

EpicFight 内蔵の Mob 攻撃アニメ（武器別）も活用できる：

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

### 4.6 IR → mobpatch JSON 自動生成

解析で得た IR（3.3）から `combat_behavior` を自動生成する。最適化のデフォルトもここで当てる。

```
IR.skills をループ:
  - origin_category == passive          → スキップ（attributes / 属性 modifier で代替）
  - origin_category in {weapon_innate, revelation}:
        behavior = {
          weight: 攻撃の派手さに反比例した値（強技ほど低 weight）,
          cooldown: max(player_only_traits.cooldown_ticks * 0.5, 60),  // CD を Mob 用に再設計
          canBeInterrupted: (強技なら false),
          behaviors: [ { conditions: [間合い/HP], animation: IR.animation.path } ]
        }
        weapon_category / style に対応する series に追加
  - origin_category in {dodge, guard, mobility} → ルート B の AI 実装にフラグ立て
```

---

## 5. 転用ルート B：Java / 解析方式（高度・カスタム制御）

データパックで表現できない制御（フェーズ遷移、独自効果、被弾割込み回避など）が必要な場合。

### 5.1 `HumanoidMobPatch` を継承

公式の最小構成（1.21.1 例。**1.20.1 Forge では `@Mod.EventBusSubscriber` + MOD バス明示**）：

```java
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
        animator.addLivingAnimation(LivingMotions.IDLE, EFBridge.idle());
        animator.addLivingAnimation(LivingMotions.WALK, EFBridge.walk());
        animator.addLivingAnimation(LivingMotions.CHASE, EFBridge.walk());
    }
}
```

登録は `EntityPatchRegistryEvent`（MOD バス）、アーマチュアは `Armatures.registerEntityTypeArmature(...)`、レンダラは `PatchedRenderersEvent.Add`（クライアント・MOD バス）。

### 5.2 解析した `AttackAnimation` の再構築

IR のフェーズ / コリジョン / ダメージから、**自前のアニメ定義** を作る（既存スキルアニメをそのまま使う場合は不要）。
`AttackAnimation` / `ComboAttackAnimation` のコンストラクタ引数（先読み・予備動作・判定・硬直・コライダー・アーマチュア）に IR の値を流し込む。

```java
// 概念図（引数順・型は対象バージョンで要確認）
EFBridge.combo(
    /* convertTime */ 0.30f, /* antic */ 0.05f,
    /* contact */ 0.45f, /* recovery */ 0.95f,
    /* collider */ EFBridge.colliderToolR(),
    /* path */ "yourmod:biped/skill/flame_slash"
);
```

### 5.3 `CombatBehaviors.Builder`

```java
private CombatBehaviors<BossPatch> buildBehaviorsFromIR() {
    return new CombatBehaviors.Builder<BossPatch>()
        .newBehaviorSeries(/* weight */ 30.0f, /* canBeInterrupted */ false, /* looping */ false)
            .nextBehavior(/* condition: distance < 3, hp < 0.5 */, EFBridge.sweepingEdge())
        .build(this);
}
```

### 5.4 `EpicFightBridge`（ソフト依存ラッパ）

**`EpicFight_MobMimic_Spec.md` と同じ方針を踏襲**。バージョンで揺れる API を 1 箇所に隔離する。

- 依存は `compileOnly` + `mandatory=false`（`mods.toml`）
- すべての EpicFight API 呼び出しを `ModList.get().isLoaded("epicfight")` ガードと `try/catch(Throwable)` 境界で包む
- 隔離すべき主な揺れ：

| 揺れる箇所 | 1.20.1 Forge | 1.21.1 NeoForge |
|------------|--------------|-----------------|
| アニメ参照 | `Animations.X`（直接 `StaticAnimation` フィールドの可能性） | `AnimationAccessor<T>`（`builder.nextAccessor(...)`） |
| アニメ登録 | `@Mod.EventBusSubscriber` + **MOD バス明示** | `@EventBusSubscriber` |
| パッチ登録 | `EntityPatchRegistryEvent`（MOD バス） | 同 |
| `initAnimator` 引数 | `Animator` 系（要確認） | `Animator` |

```java
public final class EpicFightBridge {
    public static boolean loaded() { return ModList.get().isLoaded("epicfight"); }

    public static void playSkillMotion(LivingEntity entity, String animKey) {
        if (!loaded()) return;
        try {
            var patch = EpicFightCapabilities.getEntityPatch(entity, LivingEntityPatch.class);
            if (patch != null) patch.playAnimationInstantly(resolve(animKey));
        } catch (Throwable t) {
            // バージョン不一致・API 変更を握りつぶし、バニラ挙動へフォールバック
        }
    }
    // resolve(): バージョンごとに Animations.* / AnimationAccessor を解決
}
```

### 5.5 （実験的・非推奨）スキルそのものの実行

`SkillContainer` 相当を Mob に持たせ、スキルを `execute` する試み。
**`ServerPlayerPatch` へのキャストを内部で行う経路があるため高リスク**（1.3 参照）。Mixin / リフレクションでの回避は EpicFight 更新で容易に壊れる。
→ 本書では **採用しない**。どうしても必要な場合のみ、対象バージョン限定・隔離前提で実験する。

---

## 6. 外部データの活用と最適化

### 6.1 外部データソース

| ソース | 取得できるもの | 用途 |
|--------|----------------|------|
| 他 Mod の `epicfight_mobpatch` データパック | 完成した `combat_behavior` の実例 | **最良の転用テンプレート**。書式そのまま流用・改変 |
| EpicFight 本体の `Animations`（`BIPED_MOB_*` 等） | Mob 用攻撃アニメ・武器コンボ・スキルアニメ | アニメ ID の供給源（追加アセット不要） |
| EpicFight アドオン（Skill Tree 等）のデータパック | 追加スキルのパラメータ | バランス値の参考 |
| Bosses of Mass Destruction / Bosses' Rise（オープンソース） | フェーズ設計・行動選択の考え方 | ボス AI 設計の参考（直接移植ではなく方式の参考） |

### 6.2 最適化の観点

| 観点 | 手段 |
|------|------|
| 攻撃選択のバランス | `weight` を強技ほど低く。連打させたい技は高く |
| スパム防止 | `cooldown`（tick）。プレイヤーの CD をそのまま使わず Mob 用に短縮・再設計 |
| 間合い / 状況連動 | `conditions` の `within_distance` / `within_angle` / `health` を組み合わせ |
| 難易度バランス | `attributes.impact`（怯ませ）/ `armor_negation`（火力）/ `max_strikes`（多段） |
| フェーズ演出 | `health` comparator（`less_ratio`）で HP 帯ごとに別 behavior series |
| 大量生成 | IR → JSON 自動生成（4.6）。LLM に IR を渡して `combat_behavior` を起草させ、人手で検証 |

### 6.3 解析結果のキャッシュ / プロファイル化

- 解析した IR を **プロファイル JSON** として保存し再利用（`mc-boss-analyzer` のアセット抽出結果と同じディレクトリ体系で管理）
- mod / バージョンごとに「アニメ ID 対応表」をキャッシュ → 再解析を避ける
- 出力先：`/mnt/user-data/outputs/`（既存スキルと統一）

---

## 7. バージョン互換性の注意（再掲・集約）

| 項目 | 1.20.1 Forge | 1.21.1 NeoForge |
|------|--------------|-----------------|
| データパック `pack_format` | **15** | 48 |
| アニメ参照 API | `Animations.*` 直接フィールド（要確認） | `AnimationAccessor<T>` |
| イベント登録 | `@Mod.EventBusSubscriber` + MOD バス明示 | `@EventBusSubscriber` |
| イベントパッケージ | `yesman.epicfight.api.forgeevent` | `yesman.epicfight.api.neoevent` |

**原則**：API クラス名・メソッド名・組み込み `WeaponType` / アニメ識別子はすべて **バージョン依存** とみなし、`EpicFightBridge` に隔離する。確定情報は必ず対象バージョンの公式 Wiki / GitHub ソースで照合する。

---

## 8. 実装マイルストーン

| フェーズ | 内容 | 成果物 |
|---------|------|--------|
| **P1 解析基盤** | jar / データパック展開・分類、既存 mobpatch 収集 | 解析スクリプト、ファイル分類器 |
| **P2 IR 化** | アニメ ID 対応表、AttackAnimation パラメータ抽出（必要に応じ逆コンパイル） | 中間表現 JSON（3.3） |
| **P3 データパック生成（ルートA）** | IR → `epicfight_mobpatch` JSON 自動生成 | 動作する敵 Mob データパック |
| **P4 Java 転用（ルートB）** | `HumanoidMobPatch` + `CombatBehaviors` + `EpicFightBridge` | ボス用パッチ、ブリッジ層 |
| **P5 最適化** | weight / cooldown / conditions チューニング、フェーズ演出 | バランス調整済み定義 |
| **P6 検証** | ゲーム内でモーション・判定・クラッシュ有無を確認 | テストログ、既知の課題更新 |

---

## 9. 既知の課題・検証不能項目

- **スキルアニメのデータパック参照パス**：`Animations` 定数 → リソースパスの対応は解析で確定する必要があり、ゲーム内検証まで断定不可。
- **`AttackAnimation` のコリジョン / ダメージ**：コード依存。ヘッドレス環境では実挙動を確認できないため、IR の数値は初期推定値。
- **スキルのプレイヤー固有挙動**（無敵フレーム、スタミナ連動、スタック蓄積等）：Mob には自動では移らない。必要なら AI / イベントで明示再現。
- **Dodge / Guard 系の被弾割込み**：標準の `combat_behavior` では表現困難。ルート B の専用 Goal が必要。
- **EpicFight バージョン更新による破壊的変更**：本書のクラス名・引数は対象バージョンで必ず再確認。`EpicFightBridge` 隔離と `try/catch(Throwable)` で被害を局所化。

---

## 付録：参照（一次情報）

- Epic Fight 公式 Wiki トップ：`https://epicfight-docs.readthedocs.io/`
- Getting started（API・エンティティパッチ・スキルスロット）：`/API/Starting/`
- Custom entity datapack（`epicfight_mobpatch` 仕様）：`/Guides/Entities/page1/`
- Mob Capabilities Editor：`/Guides/Entities/page2/`
- Skills（スキル分類）：`/Misc/Gameplay/skills/`
- Porting 1.20.1 → 1.21.1：`/API/Porting/Porting-from-1.20.1-to-1.21.1/`
- GitHub（ソース照合用）：`https://github.com/Epic-Fight/epicfight`
