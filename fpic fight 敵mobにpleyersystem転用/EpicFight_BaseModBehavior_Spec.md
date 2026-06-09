# EpicFight 本体mod導入時の挙動 仕様書（Base Mod Behavior）

> 設計文書群は EpicFight (`epicfight`) 本体を前提依存として扱うが、本体mod を入れると **全体の挙動が大きく変わる**（プレイヤーが自動でEpicFight戦闘化／バニラMobが自動パッチ化／自作DPが本体内蔵パッチを上書き）。本書はその**実行時挙動**を、EpicFight 1.20.1 ソース・公式Wikiで検証してまとめたもの。
>
> 検証：5観点を並列調査→懐疑的検証→統合（16エージェント / 約76万トークン, 2026-06-09）。**根拠はEpicFight 1.20.1ブランチの実ソース／公式Wiki**。凡例（✅/⚠️/🔍）は [`EpicFight_Common_Spec.md`](EpicFight_Common_Spec.md) と共通。

**関連ドキュメント**

| ドキュメント | 役割 | 本書との関係 |
|--------------|------|--------------|
| [`EpicFight_Common_Spec.md`](EpicFight_Common_Spec.md) | 共通基盤 | mobpatch / predicate / IR / Bridge の参照元 |
| [`EpicFight_SkillToMob_Spec_v2.md`](EpicFight_SkillToMob_Spec_v2.md) | スキル攻撃モーション転用 | 本書はその「土台＝本体導入時の前提挙動」を補完 |
| [`EpicFight_PlayerSystemToMob_Spec_v2.md`](EpicFight_PlayerSystemToMob_Spec_v2.md) | 戦闘システム移植 | プレイヤー側システムの実体を本書が記述 |
| [`EpicFight_MobMimic_Spec.md`](EpicFight_MobMimic_Spec.md) | 動的学習 | 観察対象（プレイヤー戦闘）の実挙動を本書が記述 |

---

## 目次

1. [一言サマリ](#1-一言サマリ)
2. [プレイヤー側：自動でEpicFight戦闘化](#2-プレイヤー側自動でepicfight戦闘化)
3. [Mob側：バニラ約22種が自動パッチ](#3-mob側バニラ約22種が自動パッチ)
4. [自作ボス側：DPは本体パッチを「上書き」する](#4-自作ボス側dpは本体パッチを上書きする)
5. [プレイヤー↔ボスのミラー戦](#5-プレイヤーボスのミラー戦)
6. [Config / gamerule 一覧と推奨](#6-config--gamerule-一覧と推奨)
7. [副作用・クラッシュ要因・非人型の限界](#7-副作用クラッシュ要因非人型の限界)
8. [反証で訂正された主張](#8-反証で訂正された主張)
9. [実機テストが必要な残点](#9-実機テストが必要な残点)

---

## 1. 一言サマリ

EpicFight 本体を導入すると：

- **プレイヤー**は起動時に自動でEpicFight戦闘システム化（バトルモード切替・コンボ・スタミナ・回避/ガード/パリィ・スキル）される。**完全に切るConfigは無い** ✅
- **バニラの人型/特定Mob 約22種**は**データパック無しでも**内蔵Patchクラスで自動パッチされ、EpicFightアニメ＋コライダー攻撃で戦う ✅
- 自作DP（`epicfight_mobpatch`）は本体内蔵パッチを**「上書き（置換）」**する形で勝つため、自作ボス設計はそのまま機能する ✅
- 結果、**プレイヤー↔ボスのソウルライク的ミラー戦が概ね成立**する ✅（ただし faction はプレイヤーに作用しない等の注意あり）

---

## 2. プレイヤー側：自動でEpicFight戦闘化

本体導入だけで全プレイヤーがEpicFight戦闘capabilityを得る ✅。

- **自動付与**：`EntityPatchProvider` が `EntityType.PLAYER` にパッチを供給登録（サーバ＝`ServerPlayerPatch`、クライアントは `LocalPlayer→LocalPlayerPatch` / `RemotePlayer→AbstractClientPlayerPatch` を条件分岐）。
  出典：`EntityPatchProvider.java`（1.20.1）。
- **バトルモード切替**（既定 **R** キー）で Building｜Combat をトグル。左クリックでアニメ付き基本コンボ／スプリント攻撃／チャージ（長押し）／ジャンプ攻撃。武器カテゴリ・オフハンドでモーションが変化。
- **スタミナゲージ＋特殊攻撃ゲージ**（画面右下）。HP持ちにダメージを与えてゲージを溜めると武器の特殊攻撃が発動可能。
- **回避 Dodge**（既定 Left Alt）：Roll（スタミナ4）/ Step（3）。実行中は物理攻撃への**無敵 i-frame**。装備重量がスタミナ消費に影響。Dodge スキルは1つだけ装備可。
- **ガード/パリィ**：Guard は常時装備可（近接ブロック、連続被弾でスタミナ消費）。addon として Parrying / Impact Guard を1つ装備可。武器タイプで使用可否制限。
- **スキル習得**：スキルブックを右クリック→**K** 画面で必要経験値レベルを払って習得・装備。系統は Dodge / Guard / Passive×3 / Mobility×1 / Revelation×1。スキルブックは構造物チェスト・敵Mobドロップで入手 🔍（必要レベルやドロップ値は二次情報）。

### 2.1 「ボスに与えたスキルを自分も使えるか」＝部分的に対称 ⚠️

| スキル | プレイヤーでの正規経路 | Mob 転用との関係 |
|--------|------------------------|------------------|
| **Meteor Slam / Revelation** | Revelation スキルとして**習得・装備**（空中で下向き発動） | Mob 側はアニメ直指定 `epicfight:biped/skill/greatsword_slam`（別レイヤ） |
| **Sweeping Edge / Blade Rush / Battojutsu / Liechtenauer** | **Weapon Innate**（対応武器を持てば特殊攻撃ゲージ経由で発動） | Mob はモーションのみ転用。Skill本体は実行不可 |

> 📌 いずれも**プレイヤー側のみが本来の経路で動く**。前段の確定事実「Mob は `ServerPlayerPatch` ハードキャストで Skill 本体を execute 不可」と整合（Common 参照・SkillToMob_v2 §1.2）。Mob はあくまで「モーション＋内蔵判定」の転用。

> ⚠️ **faction はプレイヤーに作用しない**：`PlayerPatch.getFaction()` は常に `NEUTRAL` を返し、プレイヤーは `MobPatch` を持たないため同士討ち回避（`isTargetInvulnerable`）の同陣営判定に乗らない。**ボスは常にプレイヤーを狙える**。

---

## 3. Mob側：バニラ約22種が自動パッチ

本体導入だけで、**データパック不要**で以下が自動的にEpicFight戦闘化する ✅。

- **対象（内蔵Patchクラス, 約22種）**：Zombie / Skeleton / Stray / WitherSkeleton / Drowned / Husk系 / Piglin・PiglinBrute・ZombifiedPiglin / Zoglin / Hoglin / Vindicator / Evoker / Pillager / Ravager / Vex / Witch / Spider / CaveSpider / Creeper / Enderman / IronGolem / EnderDragon / Wither 等。
  仕組み：`EntityPatchProvider.registerEntityPatches()` が `FMLCommonSetupEvent` で静的マップ `CAPABILITIES` に内蔵Patchを登録。
- **挙動**：各Patchの `initAnimator()` が既定アニメをハードコード（例 `ZombiePatch` は `ZOMBIE_IDLE/WALK/CHASE`）。導入直後からEpicFightモーションで移動・攻撃し、**AttackAnimation内蔵コライダーで実ダメージ**を出す ✅。
- **非人型も内蔵対応** ✅：Creeper/Spider/Enderman/Ravager/Vex/IronGolem/Dragon/Wither 等は専用armature/mesh（`CreeperMesh`/`SpiderMesh` 等）を持つ。「非人型は未対応」は**誤り**。正しくは「内蔵プリセット範囲外（armature未定義のバニラ亜種・modded mob）が未対応」。
- **未収録Mob**：上記以外のバニラ亜種・大半の **modded mob は自動パッチされず vanilla 挙動のまま**。例外として gamerule `globalStun` が有効な時のみ `GlobalMobPatch` の簡易スタン（戦闘アニメ無し）が当たる。
- ⚠️ **本体標準Mobの多くはガード機構を持たない**：怯み（stun）はするが、**ガード/パリィ/スタンシールドの付与は Indestructible（`advanced_mobpatch`）が前提**（PlayerSystem_v2 §5）。

---

## 4. 自作ボス側：DPは本体パッチを「上書き」する

自作DP `data/<ns>/epicfight_mobpatch/<entity>.json` の扱い ✅：

- **登録経路**：`MobPatchReloadListener`（DIRECTORY=`epicfight_mobpatch`）が読み、`EntityPatchProvider.putCustomEntityPatch()` で**別マップ `CUSTOM_CAPABILITIES`** に登録（本体内蔵 `CAPABILITIES` とは別レイヤ）。
- **優先順位の決定打**：`provider = CUSTOM_CAPABILITIES.getOrDefault(type, CAPABILITIES.get(type))`。
  → 同一エンティティに本体既定と自作DPの両方があれば **自作DPが必ず勝つ（override／置換）**。よって自作ボス設計はそのまま機能する。
- ⚠️ **置換であって部分マージではない**：自作DPで上書きすると本体既定パッチ**全体が消える**。既定の良い挙動を残したいなら `preset` / `isHumanoid` でのプリセット流用が前提。
- **無効化**：`"disabled": true` で `NullPatchProvider` となり、そのMobのEpicFight化を無効化（本体既定パッチも剥がせる）。コミュニティの「Epic Fight No Animations」DPがこの手法。
- **リロード**：`prepare()` が内部マップを clear して毎回再構築するため `/reload`・再ログインで最新DPが反映。クライアントへはサーバー権威で `SPDatapackSync` パケット同期。
- **前段の確定事実は本体導入下でも維持**：`combat_behavior.animation` のスキルアニメは内蔵コライダーで実ダメージを発生させ、**プレイヤーに対しても通常通り当たる**（攻撃処理は `LivingEntityPatch` 共通パス、攻撃者がMobでもPlayerでも同経路）✅。

### 4.1 ロード順・登録への影響

- 本体内蔵登録・自作modのJava登録（`EntityPatchRegistryEvent`, `IModBusEvent`）はいずれも common setup フェーズで**同一 registry マップへ put**するため構造的に衝突しない。本体導入で自作mod拡張点は壊れない。
- データパックは `AddReloadListenerEvent` 経由の `SimpleJsonResourceReloadListener` として `/reload`・サーバーデータロードで読まれる。
- **本体Configに「パッチ生成を止めるフラグ」や「mob一括blacklist」は無い** 🔍。内蔵パッチの個別無効化はDPの `"disabled"`・gamerule・他modの `EntityPatchRegistryEvent` 上書きで行う。

---

## 5. プレイヤー↔ボスのミラー戦

**概ね成立する** ✅。ソウルライク的な駆け引きが生まれる。

- ボス（パッチ済みMob）の AttackAnimation 内蔵コライダーは**プレイヤーに通常通り実ダメージ**を与える。
- プレイヤーは **GuardSkill**（`TAKE_DAMAGE_EVENT_ATTACK` をフックし、攻撃者が `LivingEntity` で前方からの blockable 攻撃なら `setCanceled(true)`+BLOCKED）・**回避 i-frame**（Roll/Step 実行中の物理無敵）・**ロックオン**で対抗できる。
- `impact`（スタン延長）/ `armor_negation` / `max_strikes` は `LivingEntityPatch` 共通属性 → **双方向に作用**。プレイヤー攻撃でボスをスタン/スタッガー/ノックダウンでき、ガードのスタミナ消費は penalty×impact（ボスの `impact` が高いほどプレイヤーのガードを崩しやすい）。
- Indestructible 併用時はボスの **stun shield** がプレイヤー攻撃の怯みに抵抗する。

**駆け引きの要点**：(1) 回避 i-frame かガード方向（視線ベクトルとの dot>0）で被弾を凌ぐ (2) ガードはスタミナ管理が肝（ガードブレイク注意）(3) プレイヤー攻撃でボスをスタンさせ反撃窓を作る (4) faction はプレイヤーに無関係でボスは常に狙ってくる。

> ⚠️ 訂正：「faction がプレイヤーに作用する」は**不成立**（§2.1）。また**本体のみのボスはガード機構を持たないことが多く、ガードブレイク攻防は Indestructible 前提**。

---

## 6. Config / gamerule 一覧と推奨

> 📌 **重要**：ボス挙動の多くは Config TOML ではなく **gamerule（ワールド保存・`/gamerule` 同期）とデータパック**で決まる。gamerule 名は EF版で変遷するため、導入する正確な jar 版で実名を再確認すること 🔍。

### 6.1 gamerule（ボス運用に効くもの）

| gamerule | 既定 | 効果 / 推奨 |
|----------|------|-------------|
| `globalStun` | true（新版） | 未パッチmodへも簡易スタン。modded mob主体のボス戦で怯ませたいならON |
| `noMobsInBossfight` | true | ボス戦中の雑魚出現を抑制。1on1演出維持なら true |
| `allowVanillaMelee`（旧 `doVanillaAttack`） | true | バニラ風近接の許可。**コマンドは実名要確認** |
| `weightPenalty` | 100 | 装備重量のスタミナ消費。回避多用させたいなら下げる |
| `keepSkills` | true（新版） | 死亡時スキル保持。ボス周回設計なら true |
| `stiffComboAttacks` / `hasFallAnimation` / `canSwitchPlayerMode`(旧 `canSwitchCombat`) / `disableEntityUI` / `initialMode` / `skillReplaceCooldown`(6000) / `epicDrop` | — | 演出/モードの微調整 |

### 6.2 Config TOML

| ファイル | 主なキー |
|----------|----------|
| **ServerConfig** | `allow_custom_animations`（既定 false）→ **自作アニメを専用サーバーで使うなら true 必須** |
| **ClientConfig**（`epicfight-client.toml`） | `use_compute_shader`（**false 推奨**＝描画グリッチ回避）, `show_target_indicator`, `ground_slams`, `blood_effects`, `max_hit_projectiles`(30), `camera.lock_on_range`(20), `preference_work`(ADAPTIVE/SWITCH_MODE), `long_press_count`(2) |
| **CommonConfig** | `loot.skill_book_mob_drop_chance_modifier` / `loot.skill_book_chest_drop_chance_modifier`（スキルブック入手率） |

> **プレイヤー戦闘を完全に無効化するConfigは無い** ✅。capability 自体の剥奪手段は存在せず、gamerule/client config による部分制御のみ。

---

## 7. 副作用・クラッシュ要因・非人型の限界

| 事象 | 内容 | 出典/条件 |
|------|------|-----------|
| ⚠️ サーバーでの player data 破損 | mobpatch DP を**専用サーバー**に入れると同期で `ClassCastException`（HashMap$Node→EntityType）→「Invalid player data」でログイン不可。シングルでは出ない | issue #1485（当時EF19.5.19/1.19.2）。**1.20.1での修正状況は要確認** 🔍 |
| ⚠️ アニメ未定義クラッシュ | 存在しないアニメ名をDP参照 → `NoSuchElementException` でクラッシュ | registry名の綴り誤り（Common §5.3 と同根） |
| ⚠️ スキル画面のキャスト | スキル画面操作で `LocalPlayerPatch→ServerPlayerPatch` 不正キャスト | issue #2124。前段の「getServerExecutor ハードキャスト」と同構造 |
| ⚠️ `isHumanoid=true` 誤用 | 非人型に付けるとアニメ/挙動不整合 | 公式Wiki Mob Capabilities Editor が警告 |
| ⚠️ 他mod競合 | Embeddium/Oculus で `isBattleMode()` の `NoSuchMethodError`、Better Combat 併用で専用サーバー攻撃不発 等 | バージョン依存 |
| ⚠️ 描画グリッチ | 同種Mob多数同時ロードで UV/テクスチャ融合 | issue #2411/#2179。`use_compute_shader=false` で回避可能性 |
| 🔍 バージョン整合必須 | EFM本体・Indestructible・連携modのマイナー版を揃える。不一致はクラッシュ | 20.12.5-20.12.6 で custom weapon アニメ再生不能の回帰も |
| ⚠️ 非人型の限界 | 内蔵プリセット約22種の範囲外（armature未定義のバニラ亜種・modded mob）は未パッチ。**modded mobのボス化は手動定義（armature/mesh含む）が必須** | §3 |
| ⚠️ 上書きで既定喪失 | 同一エンティティをDP上書きすると本体既定パッチが全置換 | `preset` 流用で緩和（§4） |

---

## 8. 反証で訂正された主張

懐疑的検証で過大/誤りと判明し、本書で訂正済み：

1. **「faction がプレイヤーに作用する」は誤り**（§2.1, §5）。`PlayerPatch.getFaction()` は常に NEUTRAL。
2. **「ボスと完全対称にプレイヤーもスキルを使える」は部分的**（§2.1）。Revelation/Dodge/Guard等はスキルブック習得、Sweeping Edge等は Weapon Innate。Mob は Skill 本体実行不可。
3. **「非人型は未対応」は部分的に誤り**（§3）。約22種は専用armatureで対応。未対応は「プリセット範囲外」。
4. **「本体のみでガードブレイク攻防が成立」は過大**（§3, §5）。Mobのガード/パリィ/スタンシールドは Indestructible 前提。
5. **gamerule 旧名**（`doVanillaAttack` / `canSwitchCombat`）は 1.20.1 で `allowVanillaMelee` / `canSwitchPlayerMode` にリネーム済み。旧名はコマンドで効かない。
6. **「プレイヤー戦闘を完全無効化するConfigがある」は誤り**（§6）。部分制御のみ。

---

## 9. 実機テストが必要な残点

- 起動時の自動パッチ挙動（バニラMobが実際にEpicFightモーションで攻撃し実ダメージを出すか）の動的確認。本書はソース静的解析＋Wiki/実例ベース。
- 自作DPの上書きが本体既定に実際に勝つか（同一エンティティで両方存在時）。
- `registerEntityPatches()` の正確なパッチ対象Mob全リスト（husk/zombie_villager 等の派生の拾われ方）🔍。
- gamerule の正確な実名・既定値を導入jar版で再確認 🔍。
- ServerConfig/CommonConfig TOML 本体の網羅的キー（mob無効化トグル等）の実在 🔍。
- issue #1485（専用サーバーの player data 破損）が導入1.20.1版で解消済みか。
- Indestructible 併用時のボスへのガード/パリィ/stun shield がプレイヤー攻撃のガードブレイク/怯み無効化として期待通り作用するか。
- `HumanoidMobPatch`（攻撃behavior親）の実体クラス名/パッケージの確定（`CustomHumanoidMobPatch` 等の可能性）🔍。

---

## 付録：参照（一次情報）

- EpicFight 本体ソース（1.20.1）：`https://github.com/Epic-Fight/epicfightmod`
  - `EntityPatchProvider.java`（プレイヤー/Mob 自動パッチ登録、CUSTOM_CAPABILITIES 上書き）
  - `MobPatchReloadListener.java`（`epicfight_mobpatch` DP 読み込み）
  - 各 `*Patch` クラス（`ZombiePatch` 等の `initAnimator`）
- 公式Wiki：`https://epicfight-docs.readthedocs.io/`（`/Misc/Gameplay/`, `/Misc/Gameplay/skills/`, `/Guides/Entities/`）
- 既知issue：#1485（サーバー player data 破損）/ #2124（getServerExecutor キャスト）/ #1983（DPエディタGUI）/ #2411・#2179（描画グリッチ）
- Indestructible（Mob のガード/パリィ/スタミナ）：`https://www.curseforge.com/minecraft/mc-mods/epic-fight-indestructible`
