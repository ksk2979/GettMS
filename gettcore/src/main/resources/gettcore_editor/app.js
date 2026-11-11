/* Gett 편집기 — 순수 JS, 외부 의존성 없음.
 *
 * 구조 (한 파일 안의 섹션):
 *   1. 공용 도구      h(), api(), toast(), askText()
 *   2. 상태           S, install(), serialize(), changed()
 *   3. 아이템 정보    카탈로그 조회, 아이콘, 상태(설치 안 됨 / 아이콘 없음)
 *   4. 아이템 선택기  pickItem({allowTags}) -> Promise<id | null>   (다른 탭에서도 재사용)
 *   5. 탭 등록        registerTab({id, title, render(container) -> paint?, validate(bundle) -> string[]})
 *   6. 상점 탭
 *   7. NPC·대화 탭
  7b. 퀘스트 탭 (엔티티 선택기, 발전과제 선택기 포함)
 *   7c. 보스 지점 탭, 펫 설정 탭, 보호구역 탭, 강화 탭
 *   8. 화면 뼈대      머리글, 저장, 결과 패널, 임시 저장 복원
 */
(function () {
  'use strict';

  /* ===================== 1. 공용 도구 ===================== */

  const TOKEN = new URLSearchParams(location.search).get('t') || '';
  const TOKEN_Q = '?t=' + encodeURIComponent(TOKEN);

  /** 작은 DOM 헬퍼. 문자열은 항상 텍스트 노드로 들어가므로 HTML 주입이 없다. */
  function h(tag, props, ...kids) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(props || {})) {
      if (v === undefined || v === null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
      else if (k === 'value' || k === 'checked' || k === 'disabled' || k === 'draggable') el[k] = v;
      else el.setAttribute(k, v === true ? '' : v);
    }
    const add = (c) => {
      if (c === null || c === undefined || c === false) return;
      if (Array.isArray(c)) c.forEach(add);
      else el.append(c instanceof Node ? c : document.createTextNode(String(c)));
    };
    kids.forEach(add);
    return el;
  }

  async function api(path, opts) {
    const o = Object.assign({}, opts);
    o.headers = Object.assign({ 'X-Gett-Token': TOKEN }, o.headers);
    return fetch(path, o);
  }

  let toastTimer = 0;
  function toast(msg) {
    document.querySelectorAll('.toast').forEach((t) => t.remove());
    const t = h('div', { class: 'toast', role: 'status' }, msg);
    document.body.append(t);
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.remove(), 2800);
  }

  const lsGet = (k) => { try { return localStorage.getItem(k); } catch (e) { return null; } };
  const lsSet = (k, v) => { try { localStorage.setItem(k, v); } catch (e) { /* 저장 공간이 막힌 환경 */ } };
  const lsDel = (k) => { try { localStorage.removeItem(k); } catch (e) { /* 무시 */ } };

  /** 모달 껍데기. close(value)로 닫으면 Promise가 해결된다. */
  function modal(title, build) {
    return new Promise((resolve) => {
      const prevFocus = document.activeElement;
      const overlay = h('div', { class: 'overlay' });
      function close(v) {
        document.removeEventListener('keydown', onKey, true);
        overlay.remove();
        if (prevFocus && prevFocus.focus) prevFocus.focus();
        resolve(v);
      }
      function onKey(e) {
        if (e.key === 'Escape') { e.stopPropagation(); close(null); }
      }
      const box = h('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true', 'aria-label': title });
      overlay.addEventListener('mousedown', (e) => { if (e.target === overlay) close(null); });
      document.addEventListener('keydown', onKey, true);
      build(box, close);
      overlay.append(box);
      document.body.append(overlay);
    });
  }

  /** 한 줄 입력 대화상자. validate(text) -> 오류 문자열(없으면 ''). 취소하면 null. */
  function askText(title, label, validate, initial) {
    return modal(title, (box, close) => {
      const input = h('input', { type: 'text', class: 'grow', value: initial || '', 'aria-label': label });
      const err = h('div', { class: 'muted' });
      const ok = h('button', { class: 'primary' }, '확인');
      function check() {
        const m = validate(input.value.trim());
        err.textContent = m;
        ok.disabled = !!m;
        return !m;
      }
      function submit() { if (check()) close(input.value.trim()); }
      input.addEventListener('input', check);
      input.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); });
      ok.addEventListener('click', submit);
      box.append(
        h('header', null, h('h2', null, title)),
        h('div', { class: 'body' }, h('div', { class: 'field' }, h('label', null, label), input), err),
        h('footer', null, h('span', { class: 'spacer' }), h('button', { onclick: () => close(null) }, '취소'), ok));
      check();
      setTimeout(() => input.focus(), 0);
    });
  }

  /* ===================== 2. 상태 ===================== */

  const DRAFT_KEY = 'gett-editor-draft-v1';
  const EMPTY_NPCS = () => ({ byUuid: {}, byName: {} });

  const S = {
    bundle: null,       // { shops, dialogs, npcs, quests, boss_points, pets, protect, reinforce, hunting, mobs, equipment, upgrade } — 서버가 준 모양 그대로 (pets·boss_points·protect·reinforce·hunting·mobs·equipment·upgrade만 기본값을 채움)
    refs: { advancements: [], missingItems: [], missingEntities: [] },
    baseline: '',       // 마지막으로 서버와 맞춘 상태의 직렬화
    catalog: { items: [], entities: [], itemTags: [], entityTags: [], bossTypes: [], petKinds: [], mobEffects: [], mobs: [], gear: [] },
    catalogOk: false,
    dpCatalog: null,    // 서버가 준 총·등장·Epic Fight 원래 값 (스냅숏의 datapackCatalog, 저장하지 않음)
    tab: 'shops',
    ui: { shopId: null, tabFilter: '', npcName: null, dialogId: null, questLine: null, quest: null, questId: null },
    result: null,       // 마지막 저장 결과 {kind, title, errors[], warnings[]}
    draftOffer: null,   // 복원 제안 {base, bundle}
    saving: false,
  };

  let savedQuestIds = new Set();

  function serialize(bundle) {
    return JSON.stringify({ shops: bundle.shops, dialogs: bundle.dialogs, npcs: bundle.npcs, quests: bundle.quests,
      boss_points: bundle.boss_points, pets: stripEmptyPetOverrides(bundle.pets), protect: bundle.protect,
      reinforce: bundle.reinforce, hunting: bundle.hunting, mobs: bundle.mobs, equipment: bundle.equipment,
      upgrade: bundle.upgrade, datapack: bundle.datapack });
  }

  /** 저장·비교용 펫 설정: 비어 있는 종별 덮어쓰기(ballChance / obtainChance)는 키째 뺀다. 원본은 바꾸지 않는다. */
  function stripEmptyPetOverrides(pets) {
    if (!pets || !pets.species) return pets;
    const species = {};
    for (const [k, s] of Object.entries(pets.species)) {
      const o = Object.assign({}, s);
      for (const f of ['ballChance', 'obtainChance']) if (!o[f] || !Object.keys(o[f]).length) delete o[f];
      species[k] = o;
    }
    return Object.assign({}, pets, { species });
  }

  /*
   * 서버 코덱은 기본값과 같은 필드를 JSON에서 뺀다. 화면에 모든 칸을 보여 주고 저장 때 그대로 보내도록
   * 기본값을 채운다 (PetConfig / PetSpecies / BossPoint 코덱의 기본값과 같아야 한다).
   */
  const PET_DEFAULTS = {
    healthbar: { showDistance: 32, fadeDelayTicks: 200, fadeTicks: 20, captureColor: '#55FF55' },
    capture: {
      minPercent: 1, maxPercent: 20, throwCooldownTicks: 20,
      // 포획틀 확률: 체력이 maxPercent일 때 base, minPercent일 때 max (그 사이는 직선).
      ballChance: { iron: { base: 5, max: 25 }, gold: { base: 8, max: 35 }, diamond: { base: 12, max: 50 },
        netherite: { base: 20, max: 70 } },
      bossBallChance: { iron: { base: 3, max: 12 }, gold: { base: 5, max: 18 }, diamond: { base: 8, max: 25 },
        netherite: { base: 12, max: 40 } },
      // 획득 확률(%): 포획된 몬스터 아이템이 펫으로 바뀔 확률. 잡힌 포획틀 등급의 값을 쓴다.
      obtainChance: { iron: 20, gold: 25, diamond: 30, netherite: 40 },
      bossObtainChance: { iron: 15, gold: 20, diamond: 25, netherite: 35 },
    },
  };
  const TIER_KEYS = ['iron', 'gold', 'diamond', 'netherite'];
  const SPECIES_DEFAULTS = { attack: 2, speed: 0.3, attackSpeed: 1, flySpeed: 0.6, climbSpeed: 0.3, boss: false,
    sizeScale: 1, healthMult: 1, attackMult: 1, killGold: 0, jumpHeight: 1 };
  const BOSS_POINT_DEFAULTS = { radius: 16, maxCount: 1, respawnMinutes: 30 };

  /** 등급별 확률 표: 숫자(예전 형식, 고정 확률)는 {base: n, max: n}으로, max가 없으면 base로 채운다. */
  function normalizeChances(def, src) {
    const out = {};
    for (const [k, d] of Object.entries(def)) {
      const v = (src || {})[k];
      if (v === undefined || v === null) out[k] = { base: d.base, max: d.max };
      else if (typeof v !== 'object') out[k] = { base: v, max: v };
      else out[k] = { base: v.base, max: v.max === undefined ? v.base : v.max };
    }
    return out;
  }

  /** 네 등급 모두 같은 % (예전 단일 obtainPercent). */
  const allTiers = (v) => Object.fromEntries(TIER_KEYS.map((k) => [k, v]));

  /*
   * 1.3.0 의 obtainPercent 기본값. 1.3.0 pets.json 은 capture 와 모든 보스 종에 이 값을 그대로 담았고 1.3.0 편집기도
   * 종마다 다시 썼으므로, 이 값은 "직접 고른 값"이 아니라 기본값으로 본다 (자바 PetConfig / PetSpecies 와 같은 규칙).
   */
  const LEGACY_DEFAULT_OBTAIN_PERCENT = 20;

  /**
   * 전역 획득 확률 표: 표가 없으면 예전 단일 obtainPercent(네 등급 모두, 단 1.3.0 기본값 20 은 무시), 그것도 없으면
   * 기본값. 빠진 등급은 기본값.
   */
  function normalizeObtain(def, table, legacy) {
    const useLegacy = typeof legacy === 'number' && legacy !== LEGACY_DEFAULT_OBTAIN_PERCENT;
    const src = table || (useLegacy ? allTiers(legacy) : {});
    const out = {};
    for (const k of TIER_KEYS) out[k] = src[k] === undefined || src[k] === null ? def[k] : src[k];
    return out;
  }

  /** 종별 포획 확률 덮어쓰기: 있는 등급만 남긴다 (부분 객체). */
  function partialChances(src) {
    const out = {};
    for (const k of TIER_KEYS) {
      const v = (src || {})[k];
      if (v === undefined || v === null) continue;
      out[k] = typeof v !== 'object' ? { base: v, max: v } : { base: v.base, max: v.max === undefined ? v.base : v.max };
    }
    return out;
  }

  /**
   * 종별 획득 확률 덮어쓰기: 있는 등급만. 표가 없고 예전 obtainPercent > 0 이면 네 등급 모두 그 값.
   * 단 보스 종의 20 은 1.3.0 의 암묵적 보스 기본값이라 덮어쓰기 없음 (보스 표를 쓴다).
   */
  function partialObtain(table, legacy, boss) {
    const useLegacy = typeof legacy === 'number' && legacy > 0 && !(boss && legacy === LEGACY_DEFAULT_OBTAIN_PERCENT);
    const src = table || (useLegacy ? allTiers(legacy) : {});
    const out = {};
    for (const k of TIER_KEYS) if (src[k] !== undefined && src[k] !== null) out[k] = src[k];
    return out;
  }

  function normalizePets(src) {
    const p = src || {};
    const cap = p.capture || {};
    const d = PET_DEFAULTS.capture;
    const species = {};
    for (const [k, s] of Object.entries(p.species || {})) {
      const o = Object.assign({}, SPECIES_DEFAULTS, s, {
        ballChance: partialChances(s.ballChance),
        obtainChance: partialObtain(s.obtainChance, s.obtainPercent, s.boss === true),
      });
      delete o.obtainPercent;   // 예전 키: 읽기만 하고 저장하지 않는다
      species[k] = o;
    }
    const capture = Object.assign({}, d, cap, {
      ballChance: normalizeChances(d.ballChance, cap.ballChance),
      bossBallChance: normalizeChances(d.bossBallChance, cap.bossBallChance),
      obtainChance: normalizeObtain(d.obtainChance, cap.obtainChance, cap.obtainPercent),
      bossObtainChance: normalizeObtain(d.bossObtainChance, cap.bossObtainChance, cap.obtainPercent),
    });
    delete capture.obtainPercent;
    return {
      healthbar: Object.assign({}, PET_DEFAULTS.healthbar, p.healthbar),
      capture,
      species,
    };
  }

  /** ProtectConfig 코덱의 기본값: 전역 둘 다 꺼짐, 구역 플래그는 모두 false(= 막음). */
  function normalizeProtect(src) {
    const p = src || {};
    return {
      explosionsBreakBlocks: !!p.explosionsBreakBlocks,
      endermanPickup: !!p.endermanPickup,
      regions: (Array.isArray(p.regions) ? p.regions : []).map((r) => Object.assign({}, r, {
        flags: Object.assign({ fireSpread: false, bossGrief: false, explosions: false }, r.flags),
      })),
    };
  }

  /** HuntingConfig 코덱의 기본값 (서버 코덱은 기본값과 같은 필드를 JSON에서 뺀다). */
  const HUNT_ZONE_DEFAULTS = { maxAlive: 10, refillSeconds: 10 };
  const HUNT_ENTRY_DEFAULTS = { health: 20, attack: 3, speed: 0.23, armor: 0, scale: 1, weight: 1, gold: 0, xpMultiplier: 1 };

  function normalizeHunting(src) {
    const p = src || {};
    return {
      zones: (Array.isArray(p.zones) ? p.zones : []).map((z) => Object.assign({}, HUNT_ZONE_DEFAULTS, z, {
        entries: (Array.isArray(z.entries) ? z.entries : []).map((e) => Object.assign({ name: '', entity: '' }, HUNT_ENTRY_DEFAULTS, e)),
      })),
    };
  }

  /** mobs.json: 종류별로 덮어쓸 값만 (빠진 값 = 그 몬스터의 기본값 그대로). */
  function normalizeMobs(src) {
    const p = src || {};
    const overrides = {};
    for (const [k, v] of Object.entries(p.overrides && typeof p.overrides === 'object' ? p.overrides : {})) overrides[k] = Object.assign({}, v);
    return { overrides };
  }

  /** equipment.json: 아이템별로 덮어쓸 툴팁 값만 (빠진 값 = 그 아이템의 원래 값). */
  const BACKPACK_TIER_DEFAULTS = [
    { material: 'minecraft:iron_ingot', count: 20, gold: 0 }, { material: 'minecraft:gold_ingot', count: 20, gold: 0 },
    { material: 'minecraft:diamond', count: 10, gold: 0 }, { material: 'minecraft:netherite_ingot', count: 4, gold: 0 }];
  const BACKPACK_TIER_LABELS = ['가죽 → 철', '철 → 금', '금 → 다이아몬드', '다이아몬드 → 네더라이트'];

  /** upgrade.json: 업그레이드 단계 목록 [{from, to, material, count, gold}] (빠진 count = 1, gold = 0). */
  function normalizeUpgrade(src) {
    const steps = (src && Array.isArray(src.steps) ? src.steps : []).filter((s) => s && typeof s === 'object')
      .map((s) => ({ from: String(s.from || ''), to: String(s.to || ''), material: String(s.material || ''),
        count: s.count === undefined ? 1 : s.count, gold: s.gold === undefined ? 0 : s.gold }));
    // 배낭 등급 비용 4단계(가죽→철→금→다이아몬드→네더라이트). 파일에 없거나 모자라면 기본값으로 채운다.
    const given = src && Array.isArray(src.backpackTiers) ? src.backpackTiers : [];
    const backpackTiers = BACKPACK_TIER_DEFAULTS.map((d, i) => {
      const t = given[i] && typeof given[i] === 'object' ? given[i] : d;
      return { material: String(t.material || ''), count: t.count === undefined ? 1 : t.count, gold: t.gold === undefined ? 0 : t.gold };
    });
    return { steps, backpackTiers };
  }

  function normalizeEquipment(src) {
    const p = src || {};
    const items = {};
    for (const [k, v] of Object.entries(p.items && typeof p.items === 'object' ? p.items : {})) items[k] = Object.assign({}, v);
    return { items };
  }

  /**
   * 데이터팩 사본(총·등장·Epic Fight)의 현재 덮어쓰기. 서버 스냅숏에 이 구역이 없으면 null — 그대로 저장하면 서버가 데이터팩을 건드리지 않는다.
   * { guns: {id: {reloadTime, rate, ..., damage: {탄: 피해}}}, spawnRules: {id: {disabled} | {spawners: [...]}}, spawnMobs: {몹: 배율},
   *   epicfight: {아이템: {스타일: {damage_bonus, speed_bonus, armor_negation, impact, max_strikes}}},
   *   recipes: {제작법 id: true} (막은 모드 장비 제작법),
   *   fishing: {fish, loot, crates: {id: 가중치}, tables: {표 id: {weights, added}}} (Tide 낚시, 낚시 탭 참고) }
   */
  function normalizeDatapack(src) {
    if (!src || typeof src !== 'object') return null;
    const obj = (v) => (v && typeof v === 'object' && !Array.isArray(v) ? JSON.parse(JSON.stringify(v)) : {});
    const removeOrphans = (Array.isArray(src.removeOrphans) ? src.removeOrphans : [])
      .filter((o) => o && typeof o.kind === 'string' && typeof o.id === 'string').map((o) => ({ kind: o.kind, id: o.id }));
    const recipes = {};
    for (const [id, v] of Object.entries(obj(src.recipes))) if (v === true) recipes[id] = true;
    const fsrc = obj(src.fishing);
    const fishing = { fish: obj(fsrc.fish), loot: obj(fsrc.loot), crates: obj(fsrc.crates), tables: {} };
    for (const [id, t] of Object.entries(obj(fsrc.tables))) {
      if (!t || typeof t !== 'object') continue;
      fishing.tables[id] = { weights: obj(t.weights), added: (Array.isArray(t.added) ? t.added : [])
        .filter((a) => a && typeof a.item === 'string').map((a) => ({ item: a.item, weight: a.weight, min: a.min, max: a.max })) };
    }
    return { guns: obj(src.guns), spawnRules: obj(src.spawnRules), spawnMobs: obj(src.spawnMobs), epicfight: obj(src.epicfight), recipes, fishing, removeOrphans };
  }

  function normalizeBossPoints(src) {
    return (Array.isArray(src) ? src : []).map((pt) => Object.assign({}, BOSS_POINT_DEFAULTS, pt));
  }

  /** "종 추가"의 새 항목. 펫 엔티티는 gettcore:pet_<야생 id의 경로>. */
  function newSpecies(wildId) {
    return Object.assign({ kind: 'fighter', petEntity: 'gettcore:pet_' + wildId.slice(wildId.indexOf(':') + 1) },
      SPECIES_DEFAULTS, { ballChance: {}, obtainChance: {} });
  }

  /** 서버 스냅숏(또는 임시 저장본)을 편집용 번들로. fallback: 임시 저장본에 없는 부분은 현재 번들의 것을 쓴다. */
  function bundleFrom(src, fallback) {
    const f = fallback || {};
    const b = {
      shops: src.shops || {},
      dialogs: src.dialogs || {},
      npcs: src.npcs || EMPTY_NPCS(),
      quests: src.quests || {},
      boss_points: normalizeBossPoints(src.boss_points !== undefined ? src.boss_points : f.boss_points),
      pets: normalizePets(src.pets !== undefined ? src.pets : f.pets),
      protect: normalizeProtect(src.protect !== undefined ? src.protect : f.protect),
      reinforce: normalizeReinforce(src.reinforce !== undefined ? src.reinforce : f.reinforce),
      hunting: normalizeHunting(src.hunting !== undefined ? src.hunting : f.hunting),
      mobs: normalizeMobs(src.mobs !== undefined ? src.mobs : f.mobs),
      equipment: normalizeEquipment(src.equipment !== undefined ? src.equipment : f.equipment),
      upgrade: normalizeUpgrade(src.upgrade !== undefined ? src.upgrade : f.upgrade),
      datapack: normalizeDatapack(src.datapack !== undefined ? src.datapack : f.datapack),
    };
    for (const s of Object.values(b.shops)) {
      s.tabs = s.tabs || [];
      s.entries = s.entries || [];
    }
    return b;
  }

  function isDirty() { return serialize(S.bundle) !== S.baseline; }

  function install(snap) {
    S.refs = Object.assign({ advancements: [], missingItems: [], missingEntities: [] }, snap.refs || {});
    S.dpCatalog = snap.datapackCatalog && typeof snap.datapackCatalog === 'object' ? snap.datapackCatalog : null;
    S.bundle = bundleFrom(snap);
    S.baseline = serialize(S.bundle);
    savedQuestIds = new Set();   // 서버에 저장된 적 있는 퀘스트 (id를 더는 못 바꾼다)
    for (const [k, line] of Object.entries(S.bundle.quests)) for (const q of ((line && line.quests) || [])) savedQuestIds.add(k + '/' + q.id);
    if (!S.bundle.shops[S.ui.shopId]) S.ui.shopId = Object.keys(S.bundle.shops)[0] || null;
  }

  let draftTimer = 0;
  function scheduleDraft() {
    clearTimeout(draftTimer);
    draftTimer = setTimeout(() => {
      if (isDirty()) lsSet(DRAFT_KEY, JSON.stringify({ base: S.baseline, bundle: JSON.parse(serialize(S.bundle)) }));
      else lsDel(DRAFT_KEY);
    }, 500);
  }

  /** 데이터가 바뀐 뒤 항상 부른다 (입력 중에도 안전: 화면을 다시 만들지 않는다). */
  function changed() {
    refreshChrome();
    paintCurrent();
    scheduleDraft();
  }

  /* ===================== 3. 아이템 정보 ===================== */

  const ITEM_ID = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/;
  let itemIndex = new Map();   // id -> {id, name, mod, key}
  let missingSet = new Set();

  function indexCatalog() {
    itemIndex = new Map();
    for (const it of S.catalog.items) {
      itemIndex.set(it.id, { id: it.id, name: it.name || it.id, mod: it.mod || '', key: ((it.name || '') + ' ' + it.id).toLowerCase() });
    }
  }

  function refreshMissing() { missingSet = new Set(S.refs.missingItems || []); }

  /** 'missing' 서버에 없음(저장하면 빠짐) / 'unknown' 이 클라이언트 카탈로그에 없음 / 'ok' */
  function itemStatus(id) {
    if (typeof id === 'string' && id.startsWith('#')) return 'ok';
    if (missingSet.has(id)) return 'missing';
    if (S.catalogOk && !itemIndex.has(id)) return 'unknown';
    return 'ok';
  }

  function itemName(id) {
    const it = itemIndex.get(id);
    return it ? it.name : id;
  }

  /** 아이콘 <img>. 화면에 들어올 때만 요청(lazy). 실패/태그/잘못된 id는 회색 칸. */
  function iconEl(id, size) {
    const px = size || 32;
    const placeholder = () => h('span', { class: 'icon noicon', style: `width:${px}px;height:${px}px` });
    if (typeof id !== 'string' || !ITEM_ID.test(id)) return placeholder();
    const i = id.indexOf(':');
    const url = '/icon/' + id.slice(0, i) + '/' + id.slice(i + 1) + '.png' + TOKEN_Q;
    const img = h('img', { class: 'icon', loading: 'lazy', alt: '', width: px, height: px, src: url });
    img.addEventListener('error', () => img.replaceWith(placeholder()), { once: true });
    return img;
  }

  /* ===================== 4. 아이템 선택기 ===================== */

  const PAGE = 120;

  /**
   * 아이템(또는 allowTags이면 태그) 하나를 고른다.
   * 해결 값: "minecraft:carrot" / "#minecraft:logs" / 취소하면 null.
   */
  function pickItem(opts) {
    const o = Object.assign({ allowTags: false, title: '아이템 선택' }, opts);
    return modal(o.title, (box, close) => {
      let mode = 'item';
      let page = 0;
      let query = '';
      let mod = '';

      const search = h('input', { type: 'text', class: 'grow', placeholder: '이름 또는 id 검색 (예: 당근, carrot)', 'aria-label': '검색' });
      const mods = Array.from(new Set(S.catalog.items.map((i) => i.mod).filter(Boolean))).sort((a, b) => a.localeCompare(b));
      const modSel = h('select', { 'aria-label': '모드 필터' }, h('option', { value: '' }, '모든 모드'),
        mods.map((m) => h('option', { value: m }, m)));
      const body = h('div', { class: 'body' });
      const info = h('span', { class: 'muted' });
      const prev = h('button', null, '이전');
      const next = h('button', null, '다음');
      const direct = h('input', { type: 'text', placeholder: o.allowTags ? '직접 입력: mod:item 또는 #mod:tag' : '직접 입력: mod:item', 'aria-label': '직접 입력' });
      const directBtn = h('button', null, '이 id 사용');
      const tabItem = h('button', { 'aria-selected': 'true' }, '아이템');
      const tabTag = h('button', { 'aria-selected': 'false' }, '태그');

      function setMode(m) {
        mode = m; page = 0; mod = ''; modSel.value = '';
        tabItem.setAttribute('aria-selected', String(m === 'item'));
        tabTag.setAttribute('aria-selected', String(m === 'tag'));
        modSel.classList.toggle('hidden', m === 'tag');
        draw();
      }
      tabItem.addEventListener('click', () => setMode('item'));
      tabTag.addEventListener('click', () => setMode('tag'));

      function matches(text) {
        const words = query.toLowerCase().split(/\s+/).filter(Boolean);
        return words.every((w) => text.includes(w));
      }

      function draw() {
        let total = 0;
        let shown;
        if (mode === 'item') {
          const list = [];
          for (const it of itemIndex.values()) {
            if (mod && it.mod !== mod) continue;
            if (query && !matches(it.key)) continue;
            list.push(it);
          }
          total = list.length;
          page = Math.min(page, Math.max(0, Math.ceil(total / PAGE) - 1));
          shown = list.slice(page * PAGE, page * PAGE + PAGE);
          body.replaceChildren(shown.length === 0
            ? h('p', { class: 'muted' }, S.catalogOk ? '검색 결과가 없습니다.' : '아이템 목록을 불러오지 못했습니다. 아래에서 id를 직접 입력하세요.')
            : h('div', { class: 'grid' }, shown.map((it) => h('button', { class: 'cell', title: it.id + (it.mod ? ' · ' + it.mod : ''), onclick: () => close(it.id) },
              iconEl(it.id, 32), h('span', { class: 'nm' }, it.name)))));
        } else {
          const list = (S.catalog.itemTags || []).map((t) => t.replace(/^#/, '')).filter((t) => !query || matches(t.toLowerCase()));
          total = list.length;
          page = Math.min(page, Math.max(0, Math.ceil(total / PAGE) - 1));
          shown = list.slice(page * PAGE, page * PAGE + PAGE);
          body.replaceChildren(shown.length === 0
            ? h('p', { class: 'muted' }, '검색 결과가 없습니다.')
            : h('div', { class: 'taglist' }, shown.map((t) => h('button', { onclick: () => close('#' + t) }, '#' + t))));
        }
        body.scrollTop = 0;
        const from = total === 0 ? 0 : page * PAGE + 1;
        info.textContent = `${from}–${Math.min(total, page * PAGE + PAGE)} / ${total}`;
        prev.disabled = page <= 0;
        next.disabled = (page + 1) * PAGE >= total;
      }

      search.addEventListener('input', () => { query = search.value.trim(); page = 0; draw(); });
      modSel.addEventListener('change', () => { mod = modSel.value; page = 0; draw(); });
      prev.addEventListener('click', () => { page--; draw(); });
      next.addEventListener('click', () => { page++; draw(); });
      function useDirect() {
        const v = direct.value.trim();
        const isTag = v.startsWith('#');
        if (!ITEM_ID.test(isTag ? v.slice(1) : v) || (isTag && !o.allowTags)) { toast('올바른 id가 아닙니다 (예: minecraft:carrot)'); return; }
        close(v);
      }
      directBtn.addEventListener('click', useDirect);
      direct.addEventListener('keydown', (e) => { if (e.key === 'Enter') useDirect(); });

      box.append(
        h('header', null, h('h2', null, o.title), o.allowTags ? h('span', { class: 'tabs' }, tabItem, tabTag) : null,
          search, modSel, h('button', { onclick: () => close(null) }, '닫기')),
        body,
        h('footer', null, prev, info, next, h('span', { class: 'spacer' }), direct, directBtn));
      draw();
      setTimeout(() => search.focus(), 0);
    });
  }

  /* ===================== 5. 탭 등록 ===================== */

  const TABS = [];
  /** 탭 모듈: {id, title, render(container) -> paint()?, validate(bundle) -> string[]}. */
  function registerTab(t) { TABS.push(t); }

  let currentPaint = null;
  function paintCurrent() { if (currentPaint) currentPaint(); }

  /**
   * 입력 요소에 검증 함수를 묶는다. paint 때마다 check()가 오류 문장을 돌려주면 빨갛게 표시.
   * 행을 지운 직후(changed → 다시 그리기 전)에는 지워진 행의 check 가 없는 번호를 읽어 예외가 날 수 있다 —
   * 예전에는 그 예외로 changed() 가 멈춰 뒤따르는 다시 그리기가 안 돼, 탭을 바꿀 때까지 목록이 그대로였다.
   */
  function makeChecks() {
    const list = [];
    return {
      add(el, check) { list.push({ el, check }); return el; },
      paint() {
        for (const { el, check } of list) {
          if (!el.isConnected) continue;
          let m = '';
          try { m = check(); } catch (e) { continue; }   // 지워진 행: 곧 다시 그려진다
          el.classList.toggle('bad', !!m);
          if (m) el.setAttribute('title', m); else el.removeAttribute('title');
        }
      },
    };
  }

  /* ===================== 6. 상점 탭 ===================== */

  const SHOP_ID = /^[a-z0-9_]+$/;
  const isPriceOk = (v) => Number.isInteger(v) && v >= 0;

  // 검증 규칙: 화면 표시와 오류 개수가 같은 함수를 쓴다.
  const shopRules = {
    tabId(shop, i) {
      const id = shop.tabs[i].id;
      if (!id || !id.trim()) return '분류 id가 비어 있습니다';
      if (shop.tabs.some((t, j) => j !== i && t.id === id)) return '분류 id가 중복됩니다';
      return '';
    },
    tabName(shop, i) { return shop.tabs[i].name && shop.tabs[i].name.trim() ? '' : '분류 이름이 비어 있습니다'; },
    tabIcon(shop, i) { return ITEM_ID.test(shop.tabs[i].icon || '') ? '' : '분류 아이콘을 고르세요'; },
    entryTab(shop, e) {
      if (!e.tab) return '분류를 선택하세요 (빈 분류)';
      return shop.tabs.some((t) => t.id === e.tab) ? '' : `없는 분류입니다: ${e.tab}`;
    },
    price(v) { return isPriceOk(v) ? '' : '0 이상의 정수여야 합니다'; },
    item(shop, i) {
      const id = shop.entries[i].item;
      if (!ITEM_ID.test(id || '')) return '올바른 아이템 id가 아닙니다';
      return shop.entries.some((e, j) => j !== i && e.item === id) ? '같은 아이템이 중복됩니다' : '';
    },
  };

  function validateShops(bundle) {
    const out = [];
    for (const shop of Object.values(bundle.shops)) {
      const p = `상점 ${shop.name || shop.id}`;
      shop.tabs.forEach((t, i) => {
        for (const f of [shopRules.tabId, shopRules.tabName, shopRules.tabIcon]) {
          const m = f(shop, i);
          if (m) out.push(`${p} › 분류 ${i + 1}: ${m}`);
        }
      });
      shop.entries.forEach((e, i) => {
        const w = `${p} › ${itemName(e.item)}`;
        const checks = [shopRules.item(shop, i), shopRules.entryTab(shop, e), shopRules.price(e.buyPrice), shopRules.price(e.sellPrice)];
        const labels = ['', '', '구매가: ', '판매가: '];
        checks.forEach((m, k) => { if (m) out.push(`${w}: ${labels[k]}${m}`); });
      });
    }
    return out;
  }

  /** 이 상점을 가리키는 대화 선택지 수 (삭제 경고용). */
  function shopRefCount(shopId) {
    let n = 0;
    for (const d of Object.values(S.bundle.dialogs)) {
      for (const node of Object.values((d && d.nodes) || {})) {
        for (const c of (node.choices || [])) {
          const a = c.action || {};
          if ((a.type === 'open_shop_buy' || a.type === 'open_shop_sell') && a.target === shopId) n++;
        }
      }
    }
    return n;
  }

  function uniqueTabId(shop, base) {
    let id = base; let n = 2;
    while (shop.tabs.some((t) => t.id === id)) id = base + '_' + (n++);
    return id;
  }

  const shopsTab = {
    id: 'shops',
    title: '상점',
    validate: validateShops,
    render(container) {
      const checks = makeChecks();
      const rerender = () => { renderTabContent(); };
      const shops = S.bundle.shops;
      const ids = Object.keys(shops);
      if (!shops[S.ui.shopId]) S.ui.shopId = ids[0] || null;
      const shop = shops[S.ui.shopId];

      // 왼쪽: 상점 목록
      const listEl = h('div', { class: 'list' }, ids.map((id) => h('button', {
        'aria-current': String(id === S.ui.shopId),
        onclick: () => { S.ui.shopId = id; S.ui.tabFilter = ''; rerender(); },
      }, shops[id].name || id)));
      const addShop = h('button', { class: 'primary', onclick: async () => {
        const id = await askText('상점 추가', '상점 id (영문 소문자·숫자·_)', (v) => {
          if (!v) return 'id를 입력하세요';
          if (!SHOP_ID.test(v)) return '영문 소문자, 숫자, _ 만 쓸 수 있습니다';
          if (shops[v]) return '이미 있는 id입니다';
          return '';
        });
        if (!id) return;
        shops[id] = { id, name: id, tabs: [], entries: [] };
        S.ui.shopId = id; S.ui.tabFilter = '';
        changed(); rerender();
      } }, '+ 상점 추가');
      const left = h('div', { class: 'card' }, h('h2', null, '상점 목록'), listEl, h('div', { class: 'field', style: 'margin-top:.5rem' }, addShop),
        ids.length === 0 ? h('p', { class: 'muted' }, '상점이 없습니다.') : null);

      if (!shop) {
        container.append(h('div', { class: 'split' }, left, h('div', { class: 'card muted' }, '왼쪽에서 상점을 추가하세요.')));
        return checks.paint;
      }

      // 오른쪽 위: 상점 정보
      const nameInput = h('input', { type: 'text', value: shop.name, 'aria-label': '상점 이름', oninput: (e) => { shop.name = e.target.value; changed(); },
        onchange: () => rerender() });
      const delShop = h('button', { class: 'danger', onclick: () => {
        const refs = shopRefCount(shop.id);
        const msg = `상점 '${shop.name || shop.id}'을(를) 삭제할까요?` + (refs ? `\n대화에서 이 상점을 여는 선택지가 ${refs}개 있어, 고치지 않으면 저장이 거부됩니다.` : '');
        if (!confirm(msg)) return;
        delete shops[shop.id];
        S.ui.shopId = null; S.ui.tabFilter = '';
        changed(); rerender();
      } }, '상점 삭제');
      // 시세 변동: 켜면 이 상점의 판매가가 economy.json 의 priceFluctuation 주기마다 기준가의 최소~최대 % 안에서 다시 정해진다 (끄면 정가).
      const fluctuate = h('label', { class: 'chk' }, h('input', { type: 'checkbox', checked: !!shop.fluctuate, 'aria-label': '시세 변동',
        onchange: (e) => { if (e.target.checked) shop.fluctuate = true; else delete shop.fluctuate; changed(); } }), ' 시세 변동');
      const head = h('div', { class: 'card' }, h('div', { class: 'field' },
        h('label', null, '이름'), nameInput, h('span', { class: 'muted' }, 'id: ' + shop.id), fluctuate, h('span', { class: 'spacer' }), delShop),
        h('p', { class: 'muted' }, '"시세 변동"을 켠 상점은 판매가가 일정 시간마다(기본 30분, 정각·30분에) 기준가의 70~130% 사이에서 10골드 단위로 바뀝니다. 기준가보다 높으면 초록 ▲, 낮으면 빨강 ▼ 로 보이고, 많이 팔아도 가격은 떨어지지 않습니다. 끄면 항상 아래에 적은 정가입니다. 구매가는 변하지 않으며, 살 수도 있는 아이템의 판매가는 구매가를 넘지 않습니다. 주기·범위는 서버 config/gettcore/economy.json 의 priceFluctuation 에서 바꿉니다.'));

      // 분류 편집
      const tabRows = shop.tabs.map((t, i) => {
        let prevId = t.id;
        const idIn = checks.add(h('input', { type: 'text', value: t.id, 'aria-label': '분류 id', style: 'width:8rem',
          oninput: (e) => { t.id = e.target.value; changed(); },
          onchange: () => {
            if (prevId !== t.id) { shop.entries.forEach((e) => { if (e.tab === prevId) e.tab = t.id; }); if (S.ui.tabFilter === prevId) S.ui.tabFilter = t.id; prevId = t.id; }
            changed(); rerender();
          } }), () => shopRules.tabId(shop, i));
        const nameIn = checks.add(h('input', { type: 'text', value: t.name, 'aria-label': '분류 이름', style: 'width:10rem',
          oninput: (e) => { t.name = e.target.value; changed(); }, onchange: () => rerender() }), () => shopRules.tabName(shop, i));
        const iconBtn = checks.add(h('button', { class: 'icon-btn', title: '아이콘 바꾸기', onclick: async () => {
          const id = await pickItem({ title: `'${t.name || t.id}' 분류 아이콘` });
          if (!id) return;
          t.icon = id; changed(); rerender();
        } }, iconEl(t.icon, 28), h('span', { class: 'muted' }, itemName(t.icon))), () => shopRules.tabIcon(shop, i));
        const used = () => shop.entries.filter((e) => e.tab === prevId).length;
        const del = h('button', { class: 'danger small', onclick: () => {
          const n = used();
          if (n && !confirm(`이 분류를 쓰는 품목이 ${n}개 있습니다. 삭제하면 해당 품목은 분류가 없는 상태(빨간 표시)가 됩니다. 삭제할까요?`)) return;
          shop.tabs.splice(i, 1);
          if (S.ui.tabFilter === t.id) S.ui.tabFilter = '';
          changed(); rerender();
        } }, '삭제');
        return h('tr', null, h('td', null, idIn), h('td', null, nameIn), h('td', null, iconBtn), h('td', null, del));
      });
      const addTab = h('button', { onclick: async () => {
        const icon = await pickItem({ title: '새 분류의 아이콘 아이템' });
        if (!icon) return;
        const base = icon.slice(icon.indexOf(':') + 1).replace(/[^a-z0-9_]/g, '_');
        shop.tabs.push({ id: uniqueTabId(shop, base), name: itemName(icon), icon });
        changed(); rerender();
      } }, '+ 분류 추가');
      const tabsCard = h('div', { class: 'card' }, h('h2', null, '분류 탭'),
        shop.tabs.length ? h('div', { class: 'scroll-x' }, h('table', null,
          h('thead', null, h('tr', null, h('th', null, 'id'), h('th', null, '이름'), h('th', null, '아이콘'), h('th'))),
          h('tbody', null, tabRows))) : h('p', { class: 'muted' }, '분류가 없습니다. 품목을 넣으려면 분류가 하나 이상 필요합니다.'),
        h('div', { class: 'field', style: 'margin-top:.5rem' }, addTab));

      // 품목 표
      if (S.ui.tabFilter && !shop.tabs.some((t) => t.id === S.ui.tabFilter)) S.ui.tabFilter = '';
      const filterSel = h('select', { 'aria-label': '분류 필터', onchange: (e) => { S.ui.tabFilter = e.target.value; rerender(); } },
        h('option', { value: '' }, '전체 분류'), shop.tabs.map((t) => h('option', { value: t.id }, t.name || t.id)));
      filterSel.value = S.ui.tabFilter;

      const addEntry = h('button', { class: 'primary', onclick: async () => {
        const id = await pickItem({ title: '상점에 추가할 아이템' });
        if (!id) return;
        if (shop.entries.some((e) => e.item === id)) { toast('이미 이 상점에 있는 품목입니다'); return; }
        const tab = S.ui.tabFilter || (shop.tabs[0] && shop.tabs[0].id) || '';
        // currentBuyPrice/currentSellPrice는 생략 — 서버가 기본가로 채운다
        shop.entries.push({ item: id, tab, buyPrice: 0, sellPrice: 0, canBuy: false, canSell: false });
        changed(); rerender();
      } }, '+ 품목 추가');

      let dragFrom = -1;
      function moveEntry(from, to) {   // to: 이동 후 최종 위치
        if (from === to || from < 0 || to < 0) return;
        const [e] = shop.entries.splice(from, 1);
        shop.entries.splice(to, 0, e);
        changed(); rerender();
      }

      const rows = [];
      shop.entries.forEach((e, i) => {
        if (S.ui.tabFilter && e.tab !== S.ui.tabFilter) return;
        const status = itemStatus(e.item);
        const tabOpts = shop.tabs.map((t) => h('option', { value: t.id }, t.name || t.id));
        if (!shop.tabs.some((t) => t.id === e.tab)) tabOpts.unshift(h('option', { value: e.tab || '' }, e.tab ? `(없음) ${e.tab}` : '— 선택 —'));
        const tabSel = checks.add(h('select', { 'aria-label': '분류', onchange: (ev) => { e.tab = ev.target.value; changed(); } }, tabOpts), () => shopRules.entryTab(shop, e));
        tabSel.value = e.tab || '';
        const num = (key) => checks.add(h('input', { type: 'number', min: 0, step: 1, value: Number.isNaN(e[key]) ? '' : e[key],
          'aria-label': key === 'buyPrice' ? '구매가' : '판매가',
          oninput: (ev) => { e[key] = ev.target.value === '' ? NaN : Number(ev.target.value); changed(); } }), () => shopRules.price(e[key]));
        const chk = (key, label) => h('input', { type: 'checkbox', checked: !!e[key], 'aria-label': label, onchange: (ev) => { e[key] = ev.target.checked; changed(); } });
        const handle = h('td', { class: 'handle', title: '끌어서 순서 변경' },
          h('span', null, '☰ '),
          h('button', { class: 'small', title: '위로', 'aria-label': '위로', onclick: () => moveEntry(i, i - 1) }, '▲'),
          h('button', { class: 'small', title: '아래로', 'aria-label': '아래로', onclick: () => moveEntry(i, i + 1) }, '▼'));
        const itemCell = checks.add(h('div', { class: 'itemcell' }, iconEl(e.item, 32),
          h('div', { class: 'names' }, h('span', null, itemName(e.item)), h('span', { class: 'id' }, e.item),
            status === 'missing' ? h('span', { class: 'tag bad' }, '설치되지 않은 아이템 (저장하면 삭제)')
              : status === 'unknown' ? h('span', { class: 'tag gray' }, '아이콘 없음') : null)), () => shopRules.item(shop, i));
        const tr = h('tr', { class: status === 'missing' ? 'missing' : '' }, handle,
          h('td', null, itemCell), h('td', null, tabSel), h('td', null, num('buyPrice')), h('td', null, num('sellPrice')),
          h('td', null, chk('canBuy', '구매 가능')), h('td', null, chk('canSell', '판매 가능')),
          h('td', null, h('button', { class: 'danger small', 'aria-label': '행 삭제', onclick: () => { shop.entries.splice(i, 1); changed(); rerender(); } }, '삭제')));
        // 끌어서 순서 변경: 손잡이를 누른 행만 드래그 가능
        handle.addEventListener('mousedown', () => { tr.draggable = true; });
        handle.addEventListener('mouseup', () => { tr.draggable = false; });
        tr.addEventListener('dragstart', (ev) => { dragFrom = i; tr.classList.add('dragging'); ev.dataTransfer.effectAllowed = 'move'; ev.dataTransfer.setData('text/plain', String(i)); });
        tr.addEventListener('dragend', () => { dragFrom = -1; tr.draggable = false; tr.classList.remove('dragging'); document.querySelectorAll('.drop-before,.drop-after').forEach((x) => x.classList.remove('drop-before', 'drop-after')); });
        tr.addEventListener('dragover', (ev) => {
          if (dragFrom < 0) return;
          ev.preventDefault();
          const r = tr.getBoundingClientRect();
          const before = ev.clientY < r.top + r.height / 2;
          tr.classList.toggle('drop-before', before);
          tr.classList.toggle('drop-after', !before);
        });
        tr.addEventListener('dragleave', () => tr.classList.remove('drop-before', 'drop-after'));
        tr.addEventListener('drop', (ev) => {
          if (dragFrom < 0) return;
          ev.preventDefault();
          const r = tr.getBoundingClientRect();
          const before = ev.clientY < r.top + r.height / 2;
          let to = before ? i : i + 1;
          if (dragFrom < to) to--;
          moveEntry(dragFrom, to);
        });
        rows.push(tr);
      });

      const hasMissing = shop.entries.some((e) => itemStatus(e.item) === 'missing');
      const entriesCard = h('div', { class: 'card' }, h('h2', null, `품목 (${shop.entries.length})`),
        h('div', { class: 'field' }, h('label', null, '보기'), filterSel, h('span', { class: 'spacer' }), addEntry),
        hasMissing ? h('p', { class: 'muted' }, '빨간 줄 품목은 서버에 설치되지 않은 아이템입니다. 저장하면 파일에서 빠집니다.') : null,
        rows.length ? h('div', { class: 'scroll-x' }, h('table', null,
          h('thead', null, h('tr', null, h('th'), h('th', null, '아이템'), h('th', null, '분류'), h('th', null, '구매가'), h('th', null, '판매가'),
            h('th', null, '구매'), h('th', null, '판매'), h('th'))),
          h('tbody', null, rows))) : h('p', { class: 'muted' }, shop.entries.length ? '이 분류에 품목이 없습니다.' : '품목이 없습니다.'));

      container.append(h('div', { class: 'split' }, left, h('div', { class: 'stack' }, head, tabsCard, entriesCard)));
      return checks.paint;
    },
  };
  registerTab(shopsTab);

  /* ===================== 7. NPC·대화 탭 ===================== */

  const DIALOG_ID = /^[a-z0-9_]+$/;
  const ACTIONS = [
    ['next', '다음 노드'], ['open_shop_buy', '상점 구매'], ['open_shop_sell', '상점 판매'],
    ['open_enchant', '마법부여'], ['open_reinforce', '강화'], ['open_upgrade', '업그레이드'], ['quest', '퀘스트'], ['close', '닫기'],
  ];
  const isShopAction = (t) => t === 'open_shop_buy' || t === 'open_shop_sell';

  // 서버 EditorValidator와 같은 규칙. 화면 표시와 오류 개수가 같은 함수를 쓴다.
  const dlgRules = {
    dialogId(key, d) {
      if (d.id !== key) return `키(${key})와 내부 id(${d.id})가 다릅니다`;
      return DIALOG_ID.test(key) ? '' : 'id는 영문 소문자·숫자·_ 만 쓸 수 있습니다';
    },
    start(d) { return d.nodes && d.nodes[d.start] ? '' : `시작 노드 '${d.start}'가 없습니다`; },
    nodeId(id) { return id.trim() ? '' : '노드 id가 비어 있습니다'; },
    choice(bundle, d, c) {
      const a = c.action || {};
      const t = a.target;
      if (!ACTIONS.some((x) => x[0] === a.type)) return `알 수 없는 동작입니다: ${a.type}`;
      if (a.type === 'next') return t && (d.nodes || {})[t] ? '' : `없는 노드를 가리킵니다: ${t || '(비어 있음)'}`;
      if (isShopAction(a.type)) return t && bundle.shops[t] ? '' : `없는 상점입니다: ${t || '(비어 있음)'}`;
      if (a.type === 'quest') { const line = t || 'main'; return bundle.quests[line] ? '' : `없는 퀘스트 라인입니다: ${line}`; }
      return '';
    },
    npc(bundle, dialogId) { return bundle.dialogs[dialogId] ? '' : `없는 대화입니다: ${dialogId}`; },
  };

  function validateNpcs(bundle) {
    const out = [];
    for (const [key, d] of Object.entries(bundle.dialogs)) {
      const p = `대화 ${d.name || key}`;
      const m0 = dlgRules.dialogId(key, d); if (m0) out.push(`${p}: ${m0}`);
      const m1 = dlgRules.start(d); if (m1) out.push(`${p}: ${m1}`);
      for (const [nid, node] of Object.entries(d.nodes || {})) {
        const m2 = dlgRules.nodeId(nid); if (m2) out.push(`${p}: ${m2}`);
        (node.choices || []).forEach((c, n) => {
          const m = dlgRules.choice(bundle, d, c);
          if (m) out.push(`${p} › 노드 ${nid} › 선택지 ${n + 1}: ${m}`);
        });
      }
    }
    for (const [name, did] of Object.entries(bundle.npcs.byName || {})) {
      if (!name.trim()) out.push('NPC 이름이 비어 있습니다');
      const m = dlgRules.npc(bundle, did); if (m) out.push(`NPC ${name}: ${m}`);
    }
    for (const [uuid, did] of Object.entries(bundle.npcs.byUuid || {})) {
      const m = dlgRules.npc(bundle, did); if (m) out.push(`NPC(UUID ${uuid}): ${m}`);
    }
    return out;
  }

  /** 이 대화 안에서 nodeId를 가리키는 next 선택지 수. */
  function nodeRefCount(d, nodeId) {
    let n = 0;
    for (const node of Object.values(d.nodes || {})) for (const c of (node.choices || [])) {
      if (c.action && c.action.type === 'next' && c.action.target === nodeId) n++;
    }
    return n;
  }

  /** 노드 id를 바꾸고, 이 대화의 시작 노드와 모든 next 대상도 같이 바꾼다 (노드 순서 유지). */
  function renameNode(d, oldId, newId) {
    const nodes = {};
    for (const [k, v] of Object.entries(d.nodes || {})) nodes[k === oldId ? newId : k] = v;
    d.nodes = nodes;
    if (d.start === oldId) d.start = newId;
    for (const node of Object.values(nodes)) for (const c of (node.choices || [])) {
      if (c.action && c.action.type === 'next' && c.action.target === oldId) c.action.target = newId;
    }
  }

  function dialogShopIds(d) {
    const ids = [];
    for (const node of Object.values((d && d.nodes) || {})) for (const c of (node.choices || [])) {
      const a = c.action || {};
      if (isShopAction(a.type) && a.target && !ids.includes(a.target)) ids.push(a.target);
    }
    return ids;
  }

  /** 현재 값이 목록에 없으면 "(없음) 값" 항목을 맨 앞에 넣고 선택한다. */
  function selectValue(sel, value) {
    if (!value) sel.prepend(h('option', { value: '' }, '— 선택 —'));
    else if (![...sel.options].some((o) => o.value === value)) sel.prepend(h('option', { value }, `(없음) ${value}`));
    sel.value = value || '';
  }

  const npcsTab = {
    id: 'npcs',
    title: 'NPC·대화',
    validate: validateNpcs,
    render(container) {
      const checks = makeChecks();
      const rerender = () => { renderTabContent(); };
      const b = S.bundle;
      const byName = b.npcs.byName || {};   // 없을 때 만들어 넣지 않는다: 화면만 봐도 바뀐 것으로 보이면 안 됨
      const byUuid = b.npcs.byUuid || {};
      const dialogs = b.dialogs;
      const ui = S.ui;
      const npcNames = Object.keys(byName);
      if (!(ui.npcName in byName)) ui.npcName = npcNames[0] || null;
      if (!dialogs[ui.dialogId]) ui.dialogId = (ui.npcName && dialogs[byName[ui.npcName]] && byName[ui.npcName]) || Object.keys(dialogs)[0] || null;
      const dlg = dialogs[ui.dialogId];

      /* ---- 왼쪽: NPC 목록, 대화 목록, byUuid ---- */
      const npcList = h('div', { class: 'list' }, npcNames.map((n) => h('button', {
        'aria-current': String(n === ui.npcName),
        class: dlgRules.npc(b, byName[n]) ? 'bad' : '',
        onclick: () => { ui.npcName = n; if (dialogs[byName[n]]) ui.dialogId = byName[n]; rerender(); },
      }, n, h('span', { class: 'muted' }, ' → ' + byName[n]))));
      const addNpc = h('button', { class: 'primary', onclick: async () => {
        const name = await askText('NPC 추가', 'NPC 이름 (Easy NPC의 이름과 같아야 함)', (v) => {
          if (!v) return '이름을 입력하세요';
          if (byName[v] !== undefined) return '이미 있는 이름입니다';
          return '';
        });
        if (!name) return;
        if (!b.npcs.byName) b.npcs.byName = byName;
        byName[name] = ui.dialogId || Object.keys(dialogs)[0] || '';
        ui.npcName = name;
        changed(); rerender();
      } }, '+ NPC 추가');

      const dialogList = h('div', { class: 'list' }, Object.keys(dialogs).map((id) => h('button', {
        'aria-current': String(id === ui.dialogId),
        onclick: () => { ui.dialogId = id; rerender(); },
      }, dialogs[id].name || id)));
      const addDialog = h('button', { onclick: async () => {
        const id = await askText('대화 추가', '대화 id (영문 소문자·숫자·_)', (v) => {
          if (!v) return 'id를 입력하세요';
          if (!DIALOG_ID.test(v)) return '영문 소문자, 숫자, _ 만 쓸 수 있습니다';
          if (dialogs[v]) return '이미 있는 id입니다';
          return '';
        });
        if (!id) return;
        const repoint = ui.npcName && confirm(`NPC '${ui.npcName}'이(가) 새 대화 '${id}'을(를) 쓰도록 바꿀까요?
(취소하면 NPC 연결은 그대로 두고 대화만 추가합니다)`);
        dialogs[id] = { id, name: id, start: 'start', nodes: { start: { text: '', choices: [{ label: '나가기', action: { type: 'close' } }] } } };
        if (repoint) byName[ui.npcName] = id;
        ui.dialogId = id;
        changed(); rerender();
      } }, '+ 대화 추가');

      const uuidKeys = Object.keys(byUuid);
      const uuidCard = uuidKeys.length ? h('div', { class: 'card' }, h('h2', null, 'UUID로 연결된 NPC'),
        h('p', { class: 'muted' }, '게임 안에서 연결된 것이라 여기서는 삭제만 할 수 있습니다.'),
        uuidKeys.map((u) => {
          const m = dlgRules.npc(b, byUuid[u]);
          return h('div', { class: 'field' }, h('span', { class: 'id' }, u), h('span', { class: m ? 'tag bad' : 'muted' }, '→ ' + byUuid[u] + (m ? ' (없음)' : '')),
            h('span', { class: 'spacer' }),
            h('button', { class: 'danger small', 'aria-label': 'UUID 연결 삭제', onclick: () => { delete byUuid[u]; changed(); rerender(); } }, '삭제'));
        })) : null;

      const left = h('div', { class: 'stack' },
        h('div', { class: 'card' }, h('h2', null, 'NPC (이름 → 대화)'), npcList,
          npcNames.length ? null : h('p', { class: 'muted' }, 'NPC가 없습니다.'), h('div', { class: 'field', style: 'margin-top:.5rem' }, addNpc)),
        h('div', { class: 'card' }, h('h2', null, '대화 목록'), dialogList,
          Object.keys(dialogs).length ? null : h('p', { class: 'muted' }, '대화가 없습니다.'), h('div', { class: 'field', style: 'margin-top:.5rem' }, addDialog)),
        uuidCard);

      /* ---- 가운데: 선택한 NPC + 대화 편집 ---- */
      const mid = [];
      if (ui.npcName) {
        const name = ui.npcName;
        const sel = checks.add(h('select', { 'aria-label': '이 NPC가 쓰는 대화', onchange: (e) => {
          byName[name] = e.target.value;
          if (dialogs[e.target.value]) ui.dialogId = e.target.value;
          changed(); rerender();
        } }, Object.keys(dialogs).map((id) => h('option', { value: id }, (dialogs[id].name || id) + ' (' + id + ')'))), () => dlgRules.npc(b, byName[name]));
        selectValue(sel, byName[name]);
        const delNpc = h('button', { class: 'danger', onclick: () => {
          const used = JSON.stringify(b.quests || {}).includes(JSON.stringify(name));
          if (!confirm(`NPC '${name}' 연결을 삭제할까요?` + (used ? '\n퀘스트에서 이 NPC 이름을 쓰고 있으면 고치지 않을 때 저장이 거부됩니다.' : ''))) return;
          delete byName[name];
          changed(); rerender();
        } }, 'NPC 삭제');
        mid.push(h('div', { class: 'card' }, h('div', { class: 'field' }, h('label', null, 'NPC'), h('strong', null, name),
          h('label', null, '대화'), sel, h('span', { class: 'spacer' }), delNpc)));
      }

      if (dlg) {
        const dkey = ui.dialogId;   // 항상 맵 키를 쓴다 (손으로 고친 파일에서 키와 내부 id가 다를 수 있음)
        const nodeIds = Object.keys(dlg.nodes || {});
        const nameIn = h('input', { type: 'text', value: dlg.name || '', 'aria-label': '대화 이름', oninput: (e) => { dlg.name = e.target.value; changed(); }, onchange: () => rerender() });
        const startSel = checks.add(h('select', { 'aria-label': '시작 노드', onchange: (e) => { dlg.start = e.target.value; changed(); rerender(); } },
          nodeIds.map((id) => h('option', { value: id }, id))), () => dlgRules.start(dlg));
        selectValue(startSel, dlg.start);
        const refNpcs = Object.values(byName).filter((v) => v === dkey).length + Object.values(byUuid).filter((v) => v === dkey).length;
        const delDlg = h('button', { class: 'danger', onclick: () => {
          if (!confirm(`대화 '${dlg.name || dkey}'을(를) 삭제할까요?` + (refNpcs ? `\n이 대화를 쓰는 NPC가 ${refNpcs}개 있습니다. 삭제해도 NPC 연결은 그대로 남아, 고치지 않으면 저장이 거부됩니다.` : ''))) return;
          delete dialogs[dkey];
          ui.dialogId = null;
          changed(); rerender();
        } }, '대화 삭제');
        mid.push(h('div', { class: 'card' }, h('div', { class: 'field' },
          h('label', null, '이름'), nameIn, h('span', { class: 'muted' }, 'id: ' + dkey),
          dlg.id !== dkey ? h('button', { class: 'small', title: `내부 id(${dlg.id})를 키(${dkey})에 맞춥니다`, onclick: () => { dlg.id = dkey; changed(); rerender(); } }, 'id를 키에 맞추기') : null,
          h('label', null, '시작 노드'), startSel, h('span', { class: 'spacer' }), delDlg),
          dlg.portrait ? h('p', { class: 'muted' }, '초상화: ' + dlg.portrait + ' (그대로 보존됨)') : null));

        for (const nid of nodeIds) mid.push(nodeCard(dlg, nid));

        mid.push(h('div', { class: 'card' }, h('button', { onclick: async () => {
          const id = await askText('노드 추가', '노드 id', (v) => (!v ? 'id를 입력하세요' : (dlg.nodes || {})[v] ? '이미 있는 노드 id입니다' : ''));
          if (!id) return;
          (dlg.nodes = dlg.nodes || {})[id] = { text: '', choices: [] };
          changed(); rerender();
        } }, '+ 노드 추가')));
      } else {
        mid.push(h('div', { class: 'card muted' }, '왼쪽에서 대화를 고르거나 추가하세요.'));
      }

      function nodeCard(d, nid) {
        const node = (d.nodes || {})[nid];
        const choicesOf = () => (node.choices = node.choices || []);   // 화면만 볼 때는 데이터를 건드리지 않는다 (바뀐 것으로 오인 방지)
        const idIn = h('input', { type: 'text', value: nid, 'aria-label': '노드 id', style: 'width:9rem', onchange: (e) => {
          const v = e.target.value.trim();
          if (v === nid) return;
          if (!v) { toast('노드 id가 비어 있습니다'); e.target.value = nid; return; }
          if ((d.nodes || {})[v]) { toast('이미 있는 노드 id입니다'); e.target.value = nid; return; }
          renameNode(d, nid, v);
          changed(); rerender();
        } });
        const refs = nodeRefCount(d, nid);
        const delNode = h('button', { class: 'danger small', onclick: () => {
          if (Object.keys(d.nodes || {}).length <= 1 && !confirm('마지막 노드입니다. 삭제하면 시작 노드가 없어 저장이 거부됩니다. 삭제할까요?')) return;
          if (refs && !confirm(`이 노드로 오는 선택지가 ${refs}개 있어, 고치지 않으면 저장이 거부됩니다. 삭제할까요?`)) return;
          delete d.nodes[nid];
          changed(); rerender();
        } }, '노드 삭제');
        const text = h('textarea', { rows: 3, class: 'grow', 'aria-label': '대사', value: node.text || '', oninput: (e) => { node.text = e.target.value; changed(); } });
        const moveChoice = (i, to) => { const cs = choicesOf(); if (to < 0 || to >= cs.length) return; const [c] = cs.splice(i, 1); cs.splice(to, 0, c); changed(); rerender(); };

        const rows = (node.choices || []).map((c, i) => {
          const a = c.action || { type: 'close' };   // 화면용 값. c.action은 사용자가 바꿀 때만 만든다
          const act = () => (c.action = c.action || a);
          const label = h('input', { type: 'text', value: c.label || '', 'aria-label': '선택지 라벨', class: 'grow', oninput: (e) => { c.label = e.target.value; changed(); } });
          const typeSel = h('select', { 'aria-label': '동작', onchange: (e) => {
            const a = act();
            a.type = e.target.value;
            if (a.type === 'next') a.target = Object.keys(d.nodes || {}).find((k) => k !== nid) || Object.keys(d.nodes || {})[0];
            else if (isShopAction(a.type)) a.target = Object.keys(b.shops)[0];
            else if (a.type === 'quest') a.target = b.quests.main ? 'main' : Object.keys(b.quests)[0];
            else delete a.target;
            if (a.target === undefined) delete a.target;
            changed(); rerender();
          } }, ACTIONS.map(([v, l]) => h('option', { value: v }, l)));
          typeSel.value = a.type;
          if (typeSel.value !== a.type) { typeSel.prepend(h('option', { value: a.type }, a.type)); typeSel.value = a.type; }
          let targetEl = null;
          if (a.type === 'next' || isShopAction(a.type) || a.type === 'quest') {
            let keys; let cur = a.target;
            if (a.type === 'next') keys = Object.keys(d.nodes || {});
            else if (isShopAction(a.type)) keys = Object.keys(b.shops);
            else { keys = Object.keys(b.quests); cur = cur || 'main'; }
            targetEl = checks.add(h('select', { 'aria-label': '대상', onchange: (e) => { act().target = e.target.value; changed(); } },
              keys.map((k) => h('option', { value: k }, isShopAction(a.type) ? (b.shops[k].name || k) + ' (' + k + ')' : k))),
              () => dlgRules.choice(b, d, c));
            selectValue(targetEl, cur);
          }
          return h('div', { class: 'field choice' }, label, typeSel, targetEl,
            h('button', { class: 'small', title: '위로', 'aria-label': '위로', onclick: () => moveChoice(i, i - 1) }, '▲'),
            h('button', { class: 'small', title: '아래로', 'aria-label': '아래로', onclick: () => moveChoice(i, i + 1) }, '▼'),
            h('button', { class: 'danger small', 'aria-label': '선택지 삭제', onclick: () => { choicesOf().splice(i, 1); changed(); rerender(); } }, '삭제'));
        });
        const addChoice = h('button', { class: 'small', onclick: () => {
          choicesOf().push({ label: '', action: { type: 'close' } });
          changed(); rerender();
        } }, '+ 선택지 추가');
        return h('div', { class: 'card node' },
          h('div', { class: 'field' }, h('label', null, '노드 id'), idIn,
            d.start === nid ? h('span', { class: 'tag gray' }, '시작') : null, h('span', { class: 'spacer' }), delNode),
          h('div', { class: 'field' }, text),
          rows, h('div', { class: 'field' }, addChoice));
      }

      /* ---- 오른쪽: 이 NPC가 파는 것 ---- */
      const shopIds = dlg ? dialogShopIds(dlg) : [];
      const right = h('div', { class: 'card' }, h('h2', null, '이 대화가 여는 상점'),
        shopIds.length === 0 ? h('p', { class: 'muted' }, '연결된 상점이 없습니다.') : shopIds.map((sid) => {
          const shop = b.shops[sid];
          if (!shop) return h('p', { class: 'tag bad' }, `없는 상점: ${sid}`);
          const shown = shop.entries.slice(0, 12);
          return h('button', { class: 'shopprev', title: '클릭하면 상점 탭으로 이동', onclick: () => { ui.shopId = sid; ui.tabFilter = ''; selectTab('shops'); } },
            h('strong', null, `${shop.name || sid} (${shop.entries.length})`),
            shown.map((e) => h('span', { class: 'row' }, iconEl(e.item, 20), h('span', null, itemName(e.item)),
              h('span', { class: 'muted' }, `${e.canBuy ? '구매 ' + e.buyPrice : ''}${e.canBuy && e.canSell ? ' / ' : ''}${e.canSell ? '판매 ' + e.sellPrice : ''}`))),
            shop.entries.length > shown.length ? h('span', { class: 'muted' }, `… 외 ${shop.entries.length - shown.length}개`) : null);
        }));

      container.append(h('div', { class: 'npcgrid' }, left, h('div', { class: 'stack' }, mid), right));
      return checks.paint;
    },
  };
  registerTab(npcsTab);

  /* ===================== 7b. 퀘스트 탭 ===================== */

  const QUEST_ID = /^[a-z0-9_]+$/;
  const OBJ_FIELD = { collect: 'item', kill: 'entity', advancement: 'id', talk: 'npc' };
  const OBJ_TYPES = [['collect', '수집'], ['kill', '처치'], ['advancement', '발전과제'], ['talk', '대화']];
  const DEFAULT_END_TEXT = '다음 메인 퀘스트를 준비 중입니다';

  const objTarget = (o) => (typeof o[OBJ_FIELD[o.type]] === 'string' ? o[OBJ_FIELD[o.type]] : '');
  const trigOf = (q, which) => q[which] || { type: 'auto' };
  const npcNamesOf = (bundle) => Object.keys((bundle.npcs && bundle.npcs.byName) || {});
  const isCountOk = (v) => Number.isInteger(v) && v >= 1;
  const isNonNeg = (v) => Number.isInteger(v) && v >= 0;

  const ANY_MONSTER = '@monster';
  const ANY_MONSTER_NAME = '몬스터 아무거나';

  let entityIndex = null;
  function entityName(id) {
    if (id === ANY_MONSTER) return ANY_MONSTER_NAME;
    if (!entityIndex) {
      entityIndex = new Map();
      for (const e of (S.catalog.entities || [])) entityIndex.set(e.id, e.name || e.id);
    }
    return entityIndex.get(id) || id;
  }
  function advTitle(id) {
    const a = (S.refs.advancements || []).find((x) => x.id === id);
    return a && a.title ? a.title : '';
  }
  /** 받침 있으면 "과", 없으면 "와" (게임의 QuestText.waGwa와 같은 규칙). */
  function waGwa(word) {
    const c = word ? word.charCodeAt(word.length - 1) : 0;
    if (c >= 0xAC00 && c <= 0xD7A3) return (c - 0xAC00) % 28 === 0 ? '와' : '과';
    return '와';
  }
  const missingEntity = (id) => (S.refs.missingEntities || []).includes(id);

  /** 게임 HUD와 같은 자동 라벨. */
  function autoLabel(o) {
    const t = objTarget(o);
    const n = o.count === undefined ? 1 : o.count;
    if (o.type === 'collect') return `${t.startsWith('#') ? t : itemName(t)} ${n}개 모으기`;
    if (o.type === 'kill') return `${t.startsWith('#') ? t : entityName(t)} ${n}마리 처치`;
    if (o.type === 'advancement') return `발전과제: ${advTitle(t) || t}`;
    if (o.type === 'talk') return `${t}${waGwa(t)} 대화`;
    return t;
  }

  // 서버 EditorValidator와 같은 규칙. 화면 표시와 오류 개수가 같은 함수를 쓴다.
  const questRules = {
    lineKey(key, line) {
      if (line.id !== key) return `키(${key})와 내부 id(${line.id})가 다릅니다`;
      return QUEST_ID.test(key) ? '' : 'id는 영문 소문자·숫자·_ 만 쓸 수 있습니다';
    },
    questId(line, q) {
      const id = q.id;
      if (!id || !id.trim()) return 'id가 비어 있습니다';
      if (!QUEST_ID.test(id)) return 'id는 영문 소문자·숫자·_ 만 쓸 수 있습니다';
      return line.quests.some((o) => o !== q && o.id === id) ? '같은 라인에 같은 id가 있습니다' : '';
    },
    trigger(bundle, q, which) {
      const t = trigOf(q, which);
      if (t.type === 'auto') return '';
      if (t.type !== 'npc') return `알 수 없는 방식입니다: ${t.type}`;
      const npc = t.npc || '';
      if (!npc.trim()) return 'NPC를 고르세요';
      return npcNamesOf(bundle).includes(npc) ? '' : `NPC 목록에 없는 이름입니다: ${npc}`;
    },
    objTarget(o) { return objTarget(o).trim() ? '' : '대상을 고르세요'; },
    objCount(o) { return o.count === undefined || isCountOk(o.count) ? '' : '1 이상의 정수여야 합니다'; },
    gold(r) { return r.gold === undefined || isNonNeg(r.gold) ? '' : '0 이상의 정수여야 합니다'; },
    xp(r) { return r.xp === undefined || isNonNeg(r.xp) ? '' : '0 이상의 정수여야 합니다'; },
    rewardItem(it) { return ITEM_ID.test(it.item || '') ? '' : '올바른 아이템 id가 아닙니다'; },
    rewardCount(it) { return it.count === undefined || isCountOk(it.count) ? '' : '1 이상의 정수여야 합니다'; },
    giver(bundle, key, line) {
      if (line.giver === undefined) return '';
      if (key === 'main') return '메인 라인에는 퀘스트 주는 NPC를 둘 수 없습니다';
      const g = line.giver || '';
      if (!g.trim()) return 'NPC를 고르세요';
      return npcNamesOf(bundle).includes(g) ? '' : `NPC 목록에 없는 이름입니다: ${g}`;
    },
    choicesNeedNpc(q) {
      return (q.choices || []).length && trigOf(q, 'complete').type === 'auto'
        ? '고르는 보상은 NPC에게 보고할 때 고릅니다 (완료 방식을 NPC로 바꾸세요)' : '';
    },
    /** 경고(저장은 막지 않음): 받을 수 있는 조건이 실제 퀘스트를 가리키는지. 서버는 없으면 조건 미충족으로 본다. */
    requiresWarning(bundle, line) {
      const req = line.requires;
      if (req === undefined || req === '') return '';
      const at = req.indexOf(':');
      if (at <= 0 || at === req.length - 1) return `'줄id:퀘스트id' 모양이 아닙니다: ${req}`;
      const target = bundle.quests[req.slice(0, at)];
      const qid = req.slice(at + 1);
      return target && (target.quests || []).some((x) => x.id === qid) ? '' : `없는 퀘스트입니다: ${req} (이대로면 아무도 받을 수 없습니다)`;
    },
  };

  /** 퀘스트 하나의 오류 문장들. 목록 표시·머리글 오류 개수·저장 차단이 같은 결과를 쓴다. */
  function questMessages(bundle, line, q) {
    const out = [];
    const add = (m, where) => { if (m) out.push(where ? `${where}: ${m}` : m); };
    add(questRules.questId(line, q), 'id');
    add(questRules.trigger(bundle, q, 'start'), '시작 방식');
    add(questRules.trigger(bundle, q, 'complete'), '완료 방식');
    (q.objectives || []).forEach((o, n) => {
      if (!OBJ_FIELD[o.type]) { out.push(`목표 ${n + 1}: 알 수 없는 종류입니다: ${o.type}`); return; }
      add(questRules.objTarget(o), `목표 ${n + 1}`);
      add(questRules.objCount(o), `목표 ${n + 1} 개수`);
    });
    const r = q.rewards || {};
    add(questRules.gold(r), '골드');
    add(questRules.xp(r), '경험치');
    (r.items || []).forEach((it, n) => {
      add(questRules.rewardItem(it), `보상 아이템 ${n + 1}`);
      add(questRules.rewardCount(it), `보상 아이템 ${n + 1} 개수`);
    });
    add(questRules.choicesNeedNpc(q), '고르는 보상');
    (q.choices || []).forEach((it, n) => {
      add(questRules.rewardItem(it), `고르는 보상 ${n + 1}`);
      add(questRules.rewardCount(it), `고르는 보상 ${n + 1} 개수`);
    });
    return out;
  }

  function validateQuests(bundle) {
    const out = [];
    for (const [key, line] of Object.entries(bundle.quests)) {
      const lp = `퀘스트 라인 ${line.name || key}`;
      const m = questRules.lineKey(key, line);
      if (m) out.push(`${lp}: ${m}`);
      const g = questRules.giver(bundle, key, line);
      if (g) out.push(`${lp}: 퀘스트 주는 NPC: ${g}`);
      for (const q of (line.quests || [])) {
        for (const msg of questMessages(bundle, Object.assign({}, line, { quests: line.quests || [] }), q)) out.push(`${lp} › ${q.title || q.id}: ${msg}`);
      }
    }
    return out;
  }

  function newQuestId(line) {
    const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
    for (;;) {
      const buf = new Uint8Array(6);
      crypto.getRandomValues(buf);
      const id = 'q_' + Array.from(buf, (b) => chars[b % chars.length]).join('');
      if (!line.quests.some((q) => q.id === id)) return id;
    }
  }

  /** 엔티티(또는 allowTags이면 태그) 하나를 고른다. 해결 값: "minecraft:zombie" / "#minecraft:skeletons" / null. */
  function pickEntity(opts) {
    const o = Object.assign({ allowTags: true, anyMonster: false, title: '엔티티 선택' }, opts);
    return modal(o.title, (box, close) => {
      let query = '';
      const search = h('input', { type: 'text', class: 'grow', placeholder: '이름 또는 id 검색 (예: 좀비, zombie)', 'aria-label': '검색' });
      const body = h('div', { class: 'body' });
      const direct = h('input', { type: 'text', placeholder: o.allowTags ? '직접 입력: mod:entity 또는 #mod:tag' : '직접 입력: mod:entity', 'aria-label': '직접 입력' });
      const matches = (text) => query.toLowerCase().split(/\s+/).filter(Boolean).every((w) => text.includes(w));
      function draw() {
        const ents = (S.catalog.entities || []).filter((e) => !query || matches(((e.name || '') + ' ' + e.id).toLowerCase()));
        const tags = o.allowTags ? (S.catalog.entityTags || []).map((t) => t.replace(/^#/, '')).filter((t) => !query || matches(t.toLowerCase())) : [];
        const anyMon = o.anyMonster && (!query || matches((ANY_MONSTER_NAME + ' ' + ANY_MONSTER + ' monster').toLowerCase()));
        body.replaceChildren(
          anyMon ? h('div', { class: 'taglist' }, h('button', { title: '좀비·스켈레톤·슬라임 등 적대 몹이면 무엇이든', onclick: () => close(ANY_MONSTER) },
            ANY_MONSTER_NAME + '  ', h('span', { class: 'muted' }, ANY_MONSTER))) : null,
          ents.length === 0 && tags.length === 0 && !anyMon ? h('p', { class: 'muted' }, S.catalogOk ? '검색 결과가 없습니다.' : '엔티티 목록을 불러오지 못했습니다. 아래에서 id를 직접 입력하세요.') : null,
          ents.length ? h('div', { class: 'taglist' }, ents.slice(0, 300).map((e) => h('button', { title: e.id, onclick: () => close(e.id) },
            (e.name || e.id) + '  ', h('span', { class: 'muted' }, e.id)))) : null,
          tags.length ? h('h2', { style: 'margin-top:.75rem' }, '태그') : null,
          tags.length ? h('div', { class: 'taglist' }, tags.slice(0, 300).map((t) => h('button', { onclick: () => close('#' + t) }, '#' + t))) : null);
      }
      function useDirect() {
        const v = direct.value.trim();
        const isTag = v.startsWith('#');
        if (!ITEM_ID.test(isTag ? v.slice(1) : v) || (isTag && !o.allowTags)) { toast('올바른 id가 아닙니다 (예: minecraft:zombie)'); return; }
        close(v);
      }
      search.addEventListener('input', () => { query = search.value.trim(); draw(); });
      direct.addEventListener('keydown', (e) => { if (e.key === 'Enter') useDirect(); });
      box.append(h('header', null, h('h2', null, o.title), search, h('button', { onclick: () => close(null) }, '닫기')), body,
        h('footer', null, h('span', { class: 'spacer' }), direct, h('button', { onclick: useDirect }, '이 id 사용')));
      draw();
      setTimeout(() => search.focus(), 0);
    });
  }

  /** 발전과제 하나를 고른다 (refs.advancements, 제목·id로 검색). 해결 값: id / null. */
  function pickAdvancement() {
    return modal('발전과제 선택', (box, close) => {
      let query = '';
      const search = h('input', { type: 'text', class: 'grow', placeholder: '제목 또는 id 검색', 'aria-label': '검색' });
      const body = h('div', { class: 'body' });
      const direct = h('input', { type: 'text', placeholder: '직접 입력: minecraft:story/mine_stone', 'aria-label': '직접 입력' });
      function draw() {
        const words = query.toLowerCase().split(/\s+/).filter(Boolean);
        const list = (S.refs.advancements || []).filter((a) => words.every((w) => ((a.title || '') + ' ' + a.id).toLowerCase().includes(w)));
        body.replaceChildren(list.length === 0 ? h('p', { class: 'muted' }, '검색 결과가 없습니다. 아래에서 id를 직접 입력할 수 있습니다.')
          : h('div', { class: 'taglist' }, list.slice(0, 300).map((a) => h('button', { title: a.id, onclick: () => close(a.id) },
            (a.title || a.id) + '  ', h('span', { class: 'muted' }, a.id)))));
      }
      function useDirect() {
        const v = direct.value.trim();
        if (!ITEM_ID.test(v)) { toast('올바른 id가 아닙니다 (예: minecraft:story/mine_stone)'); return; }
        close(v);
      }
      search.addEventListener('input', () => { query = search.value.trim(); draw(); });
      direct.addEventListener('keydown', (e) => { if (e.key === 'Enter') useDirect(); });
      box.append(h('header', null, h('h2', null, '발전과제 선택'), search, h('button', { onclick: () => close(null) }, '닫기')), body,
        h('footer', null, h('span', { class: 'spacer' }), direct, h('button', { onclick: useDirect }, '이 id 사용')));
      draw();
      setTimeout(() => search.focus(), 0);
    });
  }

  const questsTab = {
    id: 'quests',
    title: '퀘스트',
    validate: validateQuests,
    render(container) {
      const checks = makeChecks();
      const rerender = () => { renderTabContent(); };
      const b = S.bundle;
      const ui = S.ui;
      const lineKeys = Object.keys(b.quests);
      if (!b.quests[ui.questLine]) ui.questLine = b.quests.main ? 'main' : (lineKeys[0] || null);
      const line = b.quests[ui.questLine];
      const dynamic = [];   // 입력 중에도 갱신할 화면 조각: paint 때마다 호출

      /* ---- 라인 선택 / 추가 ---- */
      const lineSel = h('select', { 'aria-label': '퀘스트 라인', onchange: (e) => { ui.questLine = e.target.value; ui.quest = null; ui.questId = null; rerender(); } },
        lineKeys.map((k) => h('option', { value: k }, (b.quests[k].name || k) + ' (' + k + ')')));
      lineSel.value = ui.questLine || '';
      const addLine = h('button', { onclick: async () => {
        const id = await askText('퀘스트 라인 추가', '라인 id (영문 소문자·숫자·_)', (v) => {
          if (!v) return 'id를 입력하세요';
          if (!QUEST_ID.test(v)) return '영문 소문자, 숫자, _ 만 쓸 수 있습니다';
          return b.quests[v] ? '이미 있는 id입니다' : '';
        });
        if (!id) return;
        const name = await askText('퀘스트 라인 추가', '라인 이름', (v) => (v ? '' : '이름을 입력하세요'), id);
        if (!name) return;
        // main 이 아닌 새 라인은 서브퀘스트 줄로 시작: 주는 NPC 칸이 빈 채로 빨갛게 떠서 고르게 한다 ('없음'을 고르면 메인형).
        b.quests[id] = id === 'main' ? { id, name, quests: [] } : { id, name, giver: '', quests: [] };
        ui.questLine = id; ui.quest = null; ui.questId = null;
        changed(); rerender();
      } }, '+ 라인');
      const lineCard = h('div', { class: 'card' }, h('h2', null, '퀘스트 라인'), h('div', { class: 'field' }, lineSel, addLine));

      if (!line) {
        container.append(h('div', { class: 'stack' }, lineCard, h('div', { class: 'card muted' }, '퀘스트 라인이 없습니다. 위에서 추가하세요.')));
        return checks.paint;
      }
      const quests = line.quests || [];   // 없어도 화면에서 만들어 넣지 않는다 (추가할 때 만든다)
      let q = quests.includes(ui.quest) ? ui.quest : (quests.find((x) => x.id === ui.questId) || quests[0] || null);
      ui.quest = q; ui.questId = q ? q.id : null;

      /* ---- 라인 설정 ---- */
      const lineName = checks.add(h('input', { type: 'text', value: line.name || '', 'aria-label': '라인 이름', oninput: (e) => { line.name = e.target.value; changed(); }, onchange: () => rerender() }),
        () => questRules.lineKey(ui.questLine, line));
      const endText = h('input', { type: 'text', class: 'grow', value: line.endText === undefined ? '' : line.endText, placeholder: DEFAULT_END_TEXT, 'aria-label': '끝 문구',
        oninput: (e) => { line.endText = e.target.value; changed(); } });
      const isMain = ui.questLine === 'main';
      let giverRow = null;
      if (!isMain || line.giver !== undefined) {   // main 에선 숨김 (잘못 들어간 값이 있으면 지울 수 있게만 보인다)
        const NONE = '\u0001none';
        const names = npcNamesOf(b);
        const giverSel = checks.add(h('select', { 'aria-label': '퀘스트 주는 NPC', onchange: (e) => {
          if (e.target.value === NONE) delete line.giver;
          else {
            line.giver = e.target.value;
            if (line.giver) for (const q of line.quests || []) {   // 비어 있는 NPC 트리거에는 새 주는 NPC 를 채운다
              for (const k of ['start', 'complete']) {
                const t = q[k];
                if (t && t.type === 'npc' && !t.npc) t.npc = line.giver;
              }
            }
          }
          changed(); rerender();
        } }, h('option', { value: NONE }, '없음 (메인형: 자동으로 이어짐)'), names.map((n) => h('option', { value: n }, n))),
        () => questRules.giver(b, ui.questLine, line));
        if (line.giver === '') giverSel.prepend(h('option', { value: '' }, '— 선택 —'));
        else if (line.giver !== undefined && !names.includes(line.giver)) giverSel.prepend(h('option', { value: line.giver }, `(없음) ${line.giver}`));
        giverSel.value = line.giver === undefined ? NONE : line.giver;
        giverRow = h('div', { class: 'field' }, h('label', null, '퀘스트 주는 NPC'), giverSel,
          names.length === 0 ? h('span', { class: 'muted' }, 'NPC·대화 탭에서 NPC를 먼저 추가하세요') : null);
      }
      let reqRow = null;
      if (!isMain || line.requires !== undefined) {
        const reqSel = h('select', { 'aria-label': '받을 수 있는 조건', onchange: (e) => {
          if (e.target.value === '') delete line.requires; else line.requires = e.target.value;
          changed(); rerender();
        } }, h('option', { value: '' }, '없음 (바로 받을 수 있음)'),
        lineKeys.filter((k) => k !== ui.questLine).flatMap((k) => (b.quests[k].quests || []).map((x) =>
          h('option', { value: `${k}:${x.id}` }, `${b.quests[k].name || k} › ${x.title || x.id}`))));
        const cur = line.requires || '';
        if (cur && ![...reqSel.options].some((o) => o.value === cur)) reqSel.prepend(h('option', { value: cur }, `(없음) ${cur}`));
        reqSel.value = cur;
        const reqWarn = questRules.requiresWarning(b, line);
        reqRow = h('div', { class: 'field' }, h('label', null, '받을 수 있는 조건'), reqSel,
          h('span', { class: 'muted' }, '이 퀘스트를 끝내야 첫 퀘스트를 받을 수 있습니다'),
          reqWarn ? h('span', { class: 'tag warn' }, reqWarn) : null);
      }
      const lineSettings = h('div', { class: 'card' }, h('h2', null, '라인 설정'),
        h('div', { class: 'field' }, h('label', null, '이름'), lineName, h('span', { class: 'muted' }, 'id: ' + ui.questLine)),
        giverRow, reqRow,
        h('div', { class: 'field' }, h('label', null, '모두 끝난 뒤 문구'), endText));

      /* ---- 왼쪽: 장별 퀘스트 목록 ---- */
      function select(x) { ui.quest = x; ui.questId = x.id; rerender(); }
      function moveQuest(from, to) {
        if (from === to || from < 0 || to < 0 || to >= quests.length) return;
        const [x] = quests.splice(from, 1);
        quests.splice(to, 0, x);
        changed(); rerender();
      }
      let dragFrom = -1;
      const items = [];
      let prevChapter = null;
      quests.forEach((x, i) => {
        const ch = x.chapter || '';
        if (ch !== prevChapter) { items.push(h('div', { class: 'chapter' }, ch || '(장 없음)')); prevChapter = ch; }
        const label = h('span', { class: 'grow' }, '');
        const row = h('div', { class: 'qitem', 'aria-current': String(x === q) },
          h('span', { class: 'handle', title: '끌어서 순서 변경' }, '☰'), label,
          h('button', { class: 'small', title: '위로', 'aria-label': '위로', onclick: (ev) => { ev.stopPropagation(); moveQuest(i, i - 1); } }, '▲'),
          h('button', { class: 'small', title: '아래로', 'aria-label': '아래로', onclick: (ev) => { ev.stopPropagation(); moveQuest(i, i + 1); } }, '▼'));
        row.addEventListener('click', () => { if (x !== q) select(x); });
        const handle = row.firstChild;
        handle.addEventListener('mousedown', () => { row.draggable = true; });
        handle.addEventListener('mouseup', () => { row.draggable = false; });
        row.addEventListener('dragstart', (ev) => { dragFrom = i; row.classList.add('dragging'); ev.dataTransfer.effectAllowed = 'move'; ev.dataTransfer.setData('text/plain', String(i)); });
        row.addEventListener('dragend', () => { dragFrom = -1; row.draggable = false; row.classList.remove('dragging'); document.querySelectorAll('.drop-before,.drop-after').forEach((n) => n.classList.remove('drop-before', 'drop-after')); });
        row.addEventListener('dragover', (ev) => {
          if (dragFrom < 0) return;
          ev.preventDefault();
          const r = row.getBoundingClientRect();
          const before = ev.clientY < r.top + r.height / 2;
          row.classList.toggle('drop-before', before);
          row.classList.toggle('drop-after', !before);
        });
        row.addEventListener('dragleave', () => row.classList.remove('drop-before', 'drop-after'));
        row.addEventListener('drop', (ev) => {
          if (dragFrom < 0) return;
          ev.preventDefault();
          const r = row.getBoundingClientRect();
          let to = ev.clientY < r.top + r.height / 2 ? i : i + 1;
          if (dragFrom < to) to--;
          moveQuest(dragFrom, to);
        });
        dynamic.push(() => {
          label.textContent = `${i + 1}. ${x.title || x.id}`;
          row.classList.toggle('bad', questMessages(b, line, x).length > 0);
        });
        items.push(row);
      });

      const addQuest = h('button', { class: 'primary', onclick: () => {
        const last = quests[quests.length - 1];
        // 서브 줄(주는 NPC 있음)의 새 퀘스트는 그 NPC에게 받고 보고한다. 이미 있는 퀘스트는 건드리지 않는다.
        const trig = () => (line.giver !== undefined ? { type: 'npc', npc: line.giver } : { type: 'auto' });
        const nq = { id: newQuestId(line), chapter: last ? (last.chapter || '') : '', title: '새 퀘스트', description: '',
          start: trig(), complete: trig(), objectives: [], rewards: { gold: 0, items: [], xp: 0 }, completeText: '' };
        (line.quests = line.quests || quests).push(nq);
        ui.quest = nq; ui.questId = nq.id;
        changed(); rerender();
      } }, '+ 퀘스트 추가');
      const dupQuest = h('button', { disabled: !q, onclick: () => {
        const copy = JSON.parse(JSON.stringify(q));
        copy.id = newQuestId(line);
        copy.title = (q.title || '') + ' (복사)';
        quests.splice(quests.indexOf(q) + 1, 0, copy);
        ui.quest = copy; ui.questId = copy.id;
        changed(); rerender();
      } }, '복제');
      const delQuest = h('button', { class: 'danger', disabled: !q, onclick: () => {
        if (!confirm(`퀘스트 '${q.title || q.id}'을(를) 삭제할까요?\n이미 이 퀘스트를 완료한 플레이어는 완료 기록이 그대로 남습니다 (id 기준).`)) return;
        const at = quests.indexOf(q);
        quests.splice(at, 1);
        const nxt = quests[Math.min(at, quests.length - 1)] || null;
        ui.quest = nxt; ui.questId = nxt ? nxt.id : null;
        changed(); rerender();
      } }, '삭제');
      const listCard = h('div', { class: 'card' }, h('h2', null, `퀘스트 (${quests.length})`),
        quests.length ? h('div', { class: 'qlist' }, items) : h('p', { class: 'muted' }, '퀘스트가 없습니다.'),
        h('div', { class: 'field', style: 'margin-top:.5rem' }, addQuest, dupQuest, delQuest));
      const left = h('div', { class: 'stack' }, lineCard, listCard, lineSettings);

      /* ---- 가운데: 선택한 퀘스트 편집 ---- */
      const mid = [];
      if (!q) {
        mid.push(h('div', { class: 'card muted' }, '왼쪽에서 퀘스트를 추가하세요.'));
      } else {
        const locked = savedQuestIds.has(ui.questLine + '/' + q.id);
        const idIn = checks.add(h('input', { type: 'text', value: q.id, 'aria-label': '퀘스트 id', disabled: locked, style: 'width:12rem',
          oninput: (e) => { q.id = e.target.value; ui.questId = q.id; changed(); }, onchange: () => rerender() }), () => questRules.questId(line, q));
        const chapter = h('input', { type: 'text', value: q.chapter || '', 'aria-label': '장', placeholder: '예: 1장. 시작', style: 'width:14rem',
          oninput: (e) => { q.chapter = e.target.value; changed(); }, onchange: () => rerender() });
        const title = h('input', { type: 'text', class: 'grow', value: q.title || '', 'aria-label': '제목', oninput: (e) => { q.title = e.target.value; changed(); } });
        const desc = h('textarea', { rows: 4, class: 'grow', 'aria-label': '설명', value: q.description || '', oninput: (e) => { q.description = e.target.value; changed(); } });
        const done = h('textarea', { rows: 2, class: 'grow', 'aria-label': '완료 대사', value: q.completeText || '', oninput: (e) => { q.completeText = e.target.value; changed(); } });
        mid.push(h('div', { class: 'card' },
          h('div', { class: 'field' }, h('label', null, 'id'), idIn,
            locked ? h('span', { class: 'muted' }, 'id를 바꾸면 새 퀘스트로 취급됩니다 (저장된 퀘스트라 잠겨 있습니다)') : h('span', { class: 'muted' }, '아직 저장 전이라 바꿀 수 있습니다')),
          h('div', { class: 'field' }, h('label', null, '장'), chapter, h('label', null, '제목'), title),
          h('div', { class: 'field' }, h('label', null, '설명')), h('div', { class: 'field' }, desc),
          h('div', { class: 'field' }, h('label', null, '완료 대사')), h('div', { class: 'field' }, done)));

        // 시작 / 완료 방식
        const trigRow = (which, label) => {
          const t = trigOf(q, which);
          const typeSel = h('select', { 'aria-label': label + ' 방식', onchange: (e) => {
            if (e.target.value === 'npc') q[which] = { type: 'npc', npc: npcNamesOf(b)[0] || '' };
            else q[which] = { type: 'auto' };
            changed(); rerender();
          } }, h('option', { value: 'auto' }, '자동'), h('option', { value: 'npc' }, 'NPC와 대화'));
          typeSel.value = t.type === 'npc' ? 'npc' : 'auto';
          if (which === 'complete') checks.add(typeSel, () => questRules.choicesNeedNpc(q));
          let npcSel = null;
          if (t.type === 'npc') {
            npcSel = checks.add(h('select', { 'aria-label': label + ' NPC', onchange: (e) => { q[which].npc = e.target.value; changed(); } },
              npcNamesOf(b).map((n) => h('option', { value: n }, n))), () => questRules.trigger(b, q, which));
            selectValue(npcSel, t.npc || '');
          }
          return h('div', { class: 'field' }, h('label', null, label), typeSel, npcSel);
        };
        mid.push(h('div', { class: 'card' }, trigRow('start', '시작'), trigRow('complete', '완료'),
          npcNamesOf(b).length === 0 ? h('p', { class: 'muted' }, 'NPC 방식을 쓰려면 NPC·대화 탭에서 NPC를 먼저 추가하세요.') : null));

        // 목표
        const objs = q.objectives || [];   // 화면만 볼 때는 데이터를 건드리지 않는다
        const consumeOk = trigOf(q, 'complete').type === 'npc';
        const moveObj = (i, to) => { if (to < 0 || to >= objs.length) return; const [o] = objs.splice(i, 1); objs.splice(to, 0, o); changed(); rerender(); };
        const objRows = objs.map((o, i) => {
          const known = !!OBJ_FIELD[o.type];
          const typeSel = h('select', { 'aria-label': '목표 종류', onchange: (e) => {
            delete o[OBJ_FIELD[o.type]];
            o.type = e.target.value;
            o[OBJ_FIELD[o.type]] = o.type === 'talk' ? (npcNamesOf(b)[0] || '') : '';
            if (o.type !== 'collect') delete o.consume;
            changed(); rerender();
          } }, OBJ_TYPES.map(([v, l]) => h('option', { value: v }, l)));
          typeSel.value = o.type;
          if (typeSel.value !== o.type) { typeSel.prepend(h('option', { value: o.type }, o.type)); typeSel.value = o.type; }
          const tgt = objTarget(o);
          let tgtEl = null;
          let warn = null;
          const pickBtn = (content, fn) => checks.add(h('button', { class: 'icon-btn', onclick: fn }, content), () => questRules.objTarget(o));
          const setTarget = (v) => { o[OBJ_FIELD[o.type]] = v; changed(); rerender(); };
          if (o.type === 'collect') {
            const st = itemStatus(tgt);
            tgtEl = pickBtn([iconEl(tgt, 28), h('span', null, tgt ? (tgt.startsWith('#') ? tgt : itemName(tgt)) : '아이템/태그 선택…')], async () => {
              const id = await pickItem({ allowTags: true, title: '수집할 아이템 또는 태그' });
              if (id) setTarget(id);
            });
            if (st === 'missing') warn = '설치되지 않은 아이템 (저장하면 이 목표/퀘스트가 정리됩니다)';
          } else if (o.type === 'kill') {
            tgtEl = pickBtn(h('span', null, tgt ? (tgt.startsWith('#') ? tgt : entityName(tgt)) : '엔티티/태그 선택…'), async () => {
              const id = await pickEntity({ title: '처치할 엔티티 또는 태그', anyMonster: true });
              if (id) setTarget(id);
            });
            if (missingEntity(tgt)) warn = '설치되지 않은 엔티티 (저장하면 이 목표/퀘스트가 정리됩니다)';
          } else if (o.type === 'advancement') {
            const t = advTitle(tgt);
            tgtEl = pickBtn(h('span', null, tgt ? (t || tgt) : '발전과제 선택…'), async () => {
              const id = await pickAdvancement();
              if (id) setTarget(id);
            });
            if (tgt && !(S.refs.advancements || []).some((a) => a.id === tgt)) warn = '서버의 발전과제 목록에 없는 id';
          } else if (o.type === 'talk') {
            tgtEl = checks.add(h('select', { 'aria-label': '대화할 NPC', onchange: (e) => { o.npc = e.target.value; changed(); } },
              npcNamesOf(b).map((n) => h('option', { value: n }, n))), () => questRules.objTarget(o));
            selectValue(tgtEl, tgt);
          }
          const showCount = o.type === 'collect' || o.type === 'kill';
          const count = showCount ? checks.add(h('input', { type: 'number', min: 1, step: 1, 'aria-label': '개수', style: 'width:5rem',
            value: o.count === undefined ? 1 : (Number.isNaN(o.count) ? '' : o.count),
            oninput: (e) => { o.count = e.target.value === '' ? NaN : Number(e.target.value); changed(); } }), () => questRules.objCount(o)) : null;
          const consume = o.type === 'collect' ? h('label', { class: 'chk', title: consumeOk ? '완료할 때 아이템을 회수합니다' : '완료 방식이 NPC일 때만 쓸 수 있습니다' },
            h('input', { type: 'checkbox', checked: consumeOk && !!o.consume, disabled: !consumeOk, 'aria-label': '회수',
              onchange: (e) => { o.consume = e.target.checked; changed(); } }), ' 회수') : null;
          const label = h('input', { type: 'text', class: 'grow', value: o.label || '', 'aria-label': '라벨', placeholder: known ? autoLabel(o) : '',
            oninput: (e) => { o.label = e.target.value; changed(); } });
          dynamic.push(() => { label.placeholder = known ? autoLabel(o) : ''; });
          return h('div', { class: 'objrow' },
            h('div', { class: 'field' }, typeSel, tgtEl, count, consume, h('span', { class: 'spacer' }),
              h('button', { class: 'small', title: '위로', 'aria-label': '위로', onclick: () => moveObj(i, i - 1) }, '▲'),
              h('button', { class: 'small', title: '아래로', 'aria-label': '아래로', onclick: () => moveObj(i, i + 1) }, '▼'),
              h('button', { class: 'danger small', 'aria-label': '목표 삭제', onclick: () => { objs.splice(i, 1); changed(); rerender(); } }, '삭제')),
            h('div', { class: 'field' }, h('label', null, '라벨'), label),
            warn ? h('div', { class: 'tag bad' }, warn) : null);
        });
        const addObj = h('button', { onclick: () => { (q.objectives = q.objectives || objs).push({ type: 'collect', item: '', count: 1 }); changed(); rerender(); } }, '+ 목표 추가');
        mid.push(h('div', { class: 'card' }, h('h2', null, '목표'), objRows.length ? objRows : h('p', { class: 'muted' }, '목표가 없습니다.'),
          h('div', { class: 'field' }, addObj)));

        // 보상
        const rw = () => (q.rewards = q.rewards || {});   // 값을 바꿀 때만 만든다
        const r0 = q.rewards || {};
        const numField = (key, label, rule) => checks.add(h('input', { type: 'number', min: 0, step: 1, 'aria-label': label,
          value: r0[key] === undefined ? 0 : (Number.isNaN(r0[key]) ? '' : r0[key]),
          oninput: (e) => { rw()[key] = e.target.value === '' ? NaN : Number(e.target.value); changed(); } }), () => rule(q.rewards || {}));
        /** 아이템+개수 목록 편집 (보상 아이템·고르는 보상 공용). remove(i): i번째 삭제. */
        const itemListRows = (list, remove, pickTitle, delLabel) => list.map((it, i) => {
          const st = itemStatus(it.item);
          const btn = checks.add(h('button', { class: 'icon-btn', title: '아이템 바꾸기', onclick: async () => {
            const id = await pickItem({ title: pickTitle });
            if (id) { it.item = id; changed(); rerender(); }
          } }, iconEl(it.item, 28), h('span', null, itemName(it.item))), () => questRules.rewardItem(it));
          const cnt = checks.add(h('input', { type: 'number', min: 1, step: 1, 'aria-label': '개수', style: 'width:5rem',
            value: it.count === undefined ? 1 : (Number.isNaN(it.count) ? '' : it.count),
            oninput: (e) => { it.count = e.target.value === '' ? NaN : Number(e.target.value); changed(); } }), () => questRules.rewardCount(it));
          return h('div', { class: 'field' }, btn, h('span', { class: 'id' }, it.item), cnt,
            st === 'missing' ? h('span', { class: 'tag bad' }, '설치되지 않은 아이템 (저장하면 빠짐)') : null, h('span', { class: 'spacer' }),
            h('button', { class: 'danger small', 'aria-label': delLabel, onclick: () => { remove(i); changed(); rerender(); } }, '삭제'));
        });
        /** 아이템을 골라 ensure()가 돌려준 목록 끝에 1개로 넣는 버튼. */
        const addItemBtn = (label, pickTitle, ensure) => h('button', { onclick: async () => {
          const id = await pickItem({ title: pickTitle });
          if (!id) return;
          ensure().push({ item: id, count: 1 });
          changed(); rerender();
        } }, label);
        const itemRows = itemListRows(r0.items || [], (i) => rw().items.splice(i, 1), '보상 아이템', '보상 삭제');
        const addReward = addItemBtn('+ 보상 아이템', '보상 아이템', () => { const r = rw(); return (r.items = r.items || []); });
        const choiceRows = itemListRows(q.choices || [], (i) => { q.choices.splice(i, 1); if (!q.choices.length) delete q.choices; },
          '고르는 보상 아이템', '고르는 보상 삭제');
        const addChoice = addItemBtn('+ 고르는 보상', '고르는 보상 아이템', () => (q.choices = q.choices || []));
        const choiceWarn = h('div', { class: 'tag bad' });
        dynamic.push(() => { const m = questRules.choicesNeedNpc(q); choiceWarn.textContent = m; choiceWarn.style.display = m ? '' : 'none'; });
        mid.push(h('div', { class: 'card' }, h('h2', null, '보상'),
          h('div', { class: 'field' }, h('label', null, '골드'), numField('gold', '골드', questRules.gold), h('label', null, '경험치 레벨'), numField('xp', '경험치', questRules.xp)),
          itemRows, h('div', { class: 'field' }, addReward)));
        mid.push(h('div', { class: 'card' }, h('h2', null, '고르는 보상'),
          h('p', { class: 'muted' }, 'NPC에게 보고할 때 이 중 하나를 골라 받습니다 (위 보상은 그대로 함께 받음). 비우면 고르기 없음.'),
          choiceWarn, choiceRows, h('div', { class: 'field' }, addChoice)));
      }

      /* ---- 오른쪽: HUD 미리보기 ---- */
      const hud = h('div', { class: 'hud', 'aria-label': 'HUD 미리보기' });
      const screenBox = h('div', { class: 'qscreen' });
      function drawPreview() {
        hud.replaceChildren();
        screenBox.replaceChildren();
        if (!q) return;
        hud.append(h('div', { class: 'hud-title' }, q.chapter ? `[${q.chapter}] ${q.title || ''}` : (q.title || '')));
        for (const o of (q.objectives || [])) {
          if (!OBJ_FIELD[o.type]) continue;
          const n = (o.type === 'collect' || o.type === 'kill') ? (isCountOk(o.count) ? o.count : (o.count === undefined ? 1 : 0)) : 1;
          hud.append(h('div', null, `• ${o.label ? o.label : autoLabel(o)} 0/${n}`));
        }
        if (!(q.objectives || []).length) hud.append(h('div', { class: 'muted' }, '(목표 없음)'));
        const r = q.rewards || {};
        const parts = [];
        if (r.gold) parts.push(`${r.gold} 골드`);
        if (r.xp) parts.push(`경험치 ${r.xp}레벨`);
        for (const it of (r.items || [])) parts.push(`${itemName(it.item)} x${it.count === undefined ? 1 : it.count}`);
        const picks = (q.choices || []).map((it) => `${itemName(it.item)} x${it.count === undefined ? 1 : it.count}`);
        screenBox.append(h('strong', null, q.title || q.id), h('p', null, q.description || ''),
          h('div', { class: 'muted' }, '보상: ' + (parts.length ? parts.join(', ') : '없음')),
          picks.length ? h('div', { class: 'muted' }, '하나 고르기: ' + picks.join(' / ')) : null,
          q.completeText ? h('p', { class: 'muted' }, '완료: ' + q.completeText) : null);
      }
      const right = h('div', { class: 'stack' },
        h('div', { class: 'card' }, h('h2', null, 'HUD 미리보기'), hud, h('p', { class: 'muted' }, '게임 화면 오른쪽에 이렇게 표시됩니다 (진행도는 0부터).')),
        h('div', { class: 'card' }, h('h2', null, '퀘스트 화면'), screenBox));

      container.append(h('div', { class: 'questgrid' }, left, h('div', { class: 'stack' }, mid), right));
      return () => { checks.paint(); dynamic.forEach((f) => f()); drawPreview(); };
    },
  };
  registerTab(questsTab);

  /* ===================== 7c. 보스 지점 탭 · 펫 설정 탭 ===================== */

  const PET_KIND_LABEL = { fighter: '전투', mount: '탈것', flying_mount: '비행' };
  const TIERS = [['iron', '철'], ['gold', '금'], ['diamond', '다이아'], ['netherite', '네더라이트']];
  const COLOR = /^#?([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;
  const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
  const isInt = (v) => Number.isInteger(v);
  /** 숫자 입력 칸의 값. 빈 칸은 NaN (검증에서 빨갛게 표시, 저장 차단). */
  const numOf = (e) => (e.target.value === '' ? NaN : Number(e.target.value));
  const shownNum = (v) => (isNum(v) ? v : '');

  // 서버 EditorValidator(보스 지점)·PetConfig.sanitized(펫 설정)와 같은 범위.
  const bossRules = {
    id(list, i) {
      const id = list[i].id;
      if (!id || !String(id).trim()) return 'id가 비어 있습니다';
      return list.some((p, j) => j !== i && p.id === id) ? '같은 id가 있습니다' : '';
    },
    radius(p) { return isInt(p.radius) && p.radius >= 1 && p.radius <= 128 ? '' : '반경은 1~128 정수여야 합니다'; },
    maxCount(p) { return isInt(p.maxCount) && p.maxCount >= 0 && p.maxCount <= 10 ? '' : '마릿수는 0~10 정수여야 합니다'; },
    respawn(p) { return isInt(p.respawnMinutes) && p.respawnMinutes >= 0 && p.respawnMinutes <= 1440 ? '' : '리스폰은 0~1440분 정수여야 합니다'; },
    bossType(bundle, p) {
      const s = ((bundle.pets && bundle.pets.species) || {})[p.bossType];
      if (!s) return `펫 설정의 종 목록에 없는 종류입니다: ${p.bossType}`;
      return s.boss ? '' : `펫 설정에서 보스로 표시되지 않은 종입니다: ${p.bossType}`;
    },
  };

  function validateBossPoints(bundle) {
    const out = [];
    const list = bundle.boss_points || [];
    list.forEach((p, i) => {
      const w = `보스 지점 ${p.id || '#' + (i + 1)}`;
      for (const m of [bossRules.id(list, i), bossRules.radius(p), bossRules.maxCount(p), bossRules.respawn(p), bossRules.bossType(bundle, p)]) {
        if (m) out.push(`${w}: ${m}`);
      }
    });
    return out;
  }

  const bossPointsTab = {
    id: 'boss_points',
    title: '보스 지점',
    validate: validateBossPoints,
    render(container) {
      const checks = makeChecks();
      const list = S.bundle.boss_points;
      const types = (S.catalog.bossTypes || []).slice();
      const num = (p, key, label, min, max, rule) => checks.add(h('input', { type: 'number', min, max, step: 1, 'aria-label': label, style: 'width:5rem',
        value: shownNum(p[key]), oninput: (e) => { p[key] = numOf(e); changed(); } }), () => rule(p));
      const rows = list.map((p, i) => {
        const opts = types.map((t) => h('option', { value: t }, entityName(t) === t ? t : `${entityName(t)} (${t})`));
        if (!types.includes(p.bossType)) opts.unshift(h('option', { value: p.bossType }, `(목록에 없음) ${p.bossType}`));
        const sel = checks.add(h('select', { 'aria-label': '종류', onchange: (e) => { p.bossType = e.target.value; changed(); } }, opts),
          () => bossRules.bossType(S.bundle, p));
        sel.value = p.bossType;
        const pos = Array.isArray(p.pos) ? p.pos.join(' ') : '';
        const idCell = checks.add(h('span', null, p.id || '(빈 id)'), () => bossRules.id(list, i));
        return h('tr', null,
          h('td', null, idCell),
          h('td', null, h('span', { class: 'id' }, p.dimension)),
          h('td', null, h('span', { class: 'id' }, pos)),
          h('td', null, num(p, 'radius', '반경', 1, 128, bossRules.radius)),
          h('td', null, sel),
          h('td', null, num(p, 'maxCount', '마릿수', 0, 10, bossRules.maxCount)),
          h('td', null, num(p, 'respawnMinutes', '리스폰(분)', 0, 1440, bossRules.respawn)),
          h('td', null, h('button', { class: 'danger small', 'aria-label': '지점 삭제', onclick: () => {
            if (!confirm(`보스 지점 '${p.id}'을(를) 삭제할까요?\n저장하면 이 지점에서 더는 보스가 나오지 않습니다.`)) return;
            list.splice(i, 1); changed(); renderTabContent();
          } }, '삭제')));
      });
      container.append(h('div', { class: 'card' }, h('h2', null, '보스 지점'),
        h('p', { class: 'muted' }, '지점은 게임 안에서 /보스지점 추가 명령으로만 추가합니다 (명령을 쓴 자리의 차원·좌표로 만들어짐). 여기서는 반경·종류·마릿수·리스폰 간격을 고치거나 삭제할 수 있습니다.'),
        list.length ? h('div', { class: 'scroll-x' }, h('table', null,
          h('thead', null, h('tr', null, ['id', '차원', '좌표', '반경', '종류', '마릿수', '리스폰(분)', ''].map((t) => h('th', null, t)))),
          h('tbody', null, rows))) : h('p', { class: 'muted' }, '보스 지점이 없습니다.'),
        S.catalogOk && !types.length ? h('p', { class: 'muted' }, '보스 종류 목록을 불러오지 못했습니다.') : null));
      return () => checks.paint();
    },
  };
  registerTab(bossPointsTab);

  const SPECIES_NUM = [
    ['attack', '공격력'], ['speed', '이동속도'], ['attackSpeed', '공격속도'], ['flySpeed', '비행속도'], ['climbSpeed', '상승속도'],
  ];
  const SPECIES_BOSS_NUM = [['sizeScale', '크기배율'], ['healthMult', '체력배율'], ['attackMult', '공격배율']];

  const petRules = {
    nonNegInt(v) { return isInt(v) && v >= 0 ? '' : '0 이상의 정수여야 합니다'; },
    color(v) { return typeof v === 'string' && COLOR.test(v) ? '' : '#RRGGBB 형식이어야 합니다'; },
    percent(v) { return isNum(v) && v >= 0 && v <= 100 ? '' : '0~100 사이여야 합니다'; },
    maxPercent(c) {
      if (!isNum(c.maxPercent)) return '숫자를 입력하세요';
      const min = isNum(c.minPercent) ? c.minPercent : 0;
      return c.maxPercent >= min && c.maxPercent <= 100 ? '' : `최소(${min})~100 사이여야 합니다`;
    },
    chanceMax(r) {
      if (!isNum(r.max) || r.max < 0 || r.max > 100) return '0~100 사이여야 합니다';
      return !isNum(r.base) || r.max >= r.base ? '' : `기본(${r.base}) 이상이어야 합니다`;
    },
    cooldownTicks(v) { return isInt(v) && v >= 0 && v <= 200 ? '' : '0~200 사이의 정수여야 합니다'; },
    nonNeg(v) { return isNum(v) && v >= 0 ? '' : '0 이상의 숫자여야 합니다'; },
    positive(v) { return isNum(v) && v > 0 ? '' : '0보다 커야 합니다'; },
    jumpHeight(v) { return isNum(v) && v >= 0 && v <= 10 ? '' : '0~10 사이여야 합니다'; },
    petEntity(v) { return ITEM_ID.test(v || '') ? '' : '올바른 엔티티 id가 아닙니다 (예: gettcore:pet_lion)'; },
    kind(v) { return PET_KIND_LABEL[v] ? '' : `알 수 없는 종류입니다: ${v}`; },
  };

  function validatePets(bundle) {
    const out = [];
    const p = bundle.pets;
    const add = (m, where) => { if (m) out.push(`펫 설정 › ${where}: ${m}`); };
    const hb = p.healthbar;
    add(petRules.nonNegInt(hb.showDistance), '체력바 표시 거리');
    add(petRules.nonNegInt(hb.fadeDelayTicks), '체력바 사라지기 전 대기');
    add(petRules.nonNegInt(hb.fadeTicks), '체력바 사라지는 시간');
    add(petRules.color(hb.captureColor), '포획 가능 색');
    const c = p.capture;
    add(petRules.percent(c.minPercent), '포획 최소 체력%');
    add(petRules.maxPercent(c), '포획 최대 체력%');
    add(petRules.cooldownTicks(c.throwCooldownTicks), '던지기 쿨타임');
    for (const [k, label] of TIERS) {
      add(petRules.percent(c.ballChance[k].base), `포획틀 확률(${label}) 기본`);
      add(petRules.chanceMax(c.ballChance[k]), `포획틀 확률(${label}) 최대`);
      add(petRules.percent(c.bossBallChance[k].base), `보스 포획틀 확률(${label}) 기본`);
      add(petRules.chanceMax(c.bossBallChance[k]), `보스 포획틀 확률(${label}) 최대`);
      add(petRules.percent(c.obtainChance[k]), `획득 확률(${label})`);
      add(petRules.percent(c.bossObtainChance[k]), `보스 획득 확률(${label})`);
    }
    for (const [key, s] of Object.entries(p.species)) {
      const w = `종 ${entityName(key)}`;
      add(petRules.kind(s.kind), `${w} 종류`);
      add(petRules.petEntity(s.petEntity), `${w} 펫 엔티티`);
      for (const [f, label] of SPECIES_NUM) add(petRules.nonNeg(s[f]), `${w} ${label}`);
      for (const [f, label] of SPECIES_BOSS_NUM) add(petRules.positive(s[f]), `${w} ${label}`);
      add(petRules.nonNegInt(s.killGold), `${w} 처치골드`);
      add(petRules.jumpHeight(s.jumpHeight), `${w} 점프(칸)`);
      for (const [k, label] of TIERS) {
        const r = (s.ballChance || {})[k];
        if (r) {
          add(petRules.percent(r.base), `${w} 포획(${label}) 기본`);
          add(petRules.chanceMax(r), `${w} 포획(${label}) 최대`);
        }
        const o = (s.obtainChance || {})[k];
        if (o !== undefined) add(petRules.percent(o), `${w} 획득(${label})`);
      }
    }
    return out;
  }

  /** 종 목록에서 "확률" 펼침 영역이 열린 종 (다시 그려도 유지). */
  const petChanceOpen = new Set();

  const petsTab = {
    id: 'pets',
    title: '펫 설정',
    validate: validatePets,
    render(container) {
      const checks = makeChecks();
      const dynamic = [];   // 칠할 때마다 다시 계산하는 것 (종별 칸의 placeholder = 지금 적용될 전역값)
      const p = S.bundle.pets;
      const hb = p.healthbar;
      const c = p.capture;
      const numIn = (obj, key, label, rule, opts) => checks.add(h('input', Object.assign({ type: 'number', step: 'any', 'aria-label': label, style: 'width:5.5rem',
        value: shownNum(obj[key]), oninput: (e) => { obj[key] = numOf(e); changed(); } }, opts || {})), () => rule(obj[key], obj));
      const labeled = (label, el, unit) => [h('label', null, label), el, unit ? h('span', { class: 'muted' }, unit) : null];

      const swatch = h('span', { class: 'swatch', 'aria-hidden': 'true' });
      const paintSwatch = () => {
        const v = hb.captureColor;
        const ok = !petRules.color(v);
        const hex = ok ? v.replace(/^#/, '') : '';
        swatch.style.background = ok ? '#' + (hex.length === 8 ? hex.slice(2) : hex) : 'transparent';
      };
      const colorIn = checks.add(h('input', { type: 'text', style: 'width:6.5rem', 'aria-label': '포획 가능 색', value: hb.captureColor,
        oninput: (e) => { hb.captureColor = e.target.value.trim(); changed(); } }), () => petRules.color(hb.captureColor));
      const hbCard = h('div', { class: 'card' }, h('h2', null, '체력바'),
        h('div', { class: 'field' }, labeled('표시 거리', numIn(hb, 'showDistance', '표시 거리', petRules.nonNegInt, { step: 1, min: 0 }), '블록')),
        h('div', { class: 'field' }, labeled('사라지기 전 대기', numIn(hb, 'fadeDelayTicks', '사라지기 전 대기', petRules.nonNegInt, { step: 1, min: 0 }), '틱'),
          labeled('사라지는 시간', numIn(hb, 'fadeTicks', '사라지는 시간', petRules.nonNegInt, { step: 1, min: 0 }), '틱')),
        h('div', { class: 'field' }, labeled('포획 가능 색', colorIn), swatch),
        h('p', { class: 'muted' }, '20틱 = 1초. 포획 가능 색은 체력이 포획 범위에 들어온 몹의 체력바 색입니다.'));

      // 등급마다 기본(체력 최대%일 때) ~ 최대(체력 최소%일 때). 최대 입력은 기본이 바뀌어도 다시 검사한다.
      const tierRow = (label, map, prefix) => [h('h3', null, label),
        TIERS.map(([k, name]) => h('div', { class: 'field' }, h('label', { style: 'min-width:5rem' }, name),
          labeled('기본', numIn(map[k], 'base', `${prefix} ${name} 기본`, petRules.percent, { min: 0, max: 100 }), '% ~'),
          labeled('최대', numIn(map[k], 'max', `${prefix} ${name} 최대`, (v, o) => petRules.chanceMax(o), { min: 0, max: 100 }), '%')))];
      const obtainRow = (label, map) => [h('h3', null, label),
        TIERS.map(([k, name]) => h('div', { class: 'field' }, h('label', { style: 'min-width:5rem' }, name),
          numIn(map, k, `${label} ${name}`, petRules.percent, { min: 0, max: 100 }), h('span', { class: 'muted' }, '%')))];
      const capCard = h('div', { class: 'card' }, h('h2', null, '포획'),
        h('div', { class: 'field' }, labeled('체력', numIn(c, 'minPercent', '포획 최소 체력%', petRules.percent, { min: 0, max: 100 }), '% ~'),
          numIn(c, 'maxPercent', '포획 최대 체력%', (v, o) => petRules.maxPercent(o), { min: 0, max: 100 }), h('span', { class: 'muted' }, '% 일 때 포획 가능')),
        h('div', { class: 'field' }, labeled('던지기 쿨타임(틱)', numIn(c, 'throwCooldownTicks', '던지기 쿨타임(틱)', petRules.cooldownTicks, { step: 1, min: 0, max: 200 }), '틱 (20틱 = 1초, 모든 포획틀 공통)')),
        tierRow('포획틀 확률', c.ballChance, '포획틀 확률'),
        tierRow('보스 포획틀 확률', c.bossBallChance, '보스 포획틀 확률'),
        h('p', { class: 'muted' }, '포획틀 확률은 체력이 최대%일 때 기본, 최소%일 때 최대이고 그 사이는 체력이 낮을수록 올라갑니다.'),
        obtainRow('획득 확률', c.obtainChance),
        obtainRow('보스 획득 확률', c.bossObtainChance),
        h('p', { class: 'muted' }, '획득 확률은 포획된 몬스터가 펫으로 바뀔 확률이며, 잡을 때 쓴 포획틀 등급의 값을 씁니다.'));

      const head = ['종 (야생 엔티티)', '종류', '펫 엔티티'].concat(SPECIES_NUM.map((x) => x[1]), ['보스'], SPECIES_BOSS_NUM.map((x) => x[1]), ['처치골드', '점프(칸)', '', '확률']);

      /*
       * 종별 확률 덮어쓰기 (펼침 행). 빈칸 = 전역 표 사용 (보스 종이면 보스 표), placeholder 에 그 값을 보인다.
       * 포획: 기본·최대 둘 다 비면 그 등급을 ballChance 에서 뺀다. 하나만 있으면 둘 다 그 값으로 저장하고,
       * 빈 칸의 placeholder 는 (전역값이 아니라) 실제로 저장되는 그 값을 보인다.
       * 획득: 비면 그 등급을 obtainChance 에서 뺀다.
       */
      const speciesChanceRow = (s, name) => {
        const ph = (el, f) => { dynamic.push(() => { el.placeholder = shownNum(f()); }); return el; };
        const cell = (label, value, opts) => h('input', Object.assign({ type: 'number', step: 'any', min: 0, max: 100,
          'aria-label': `${name} ${label}`, style: 'width:4.5rem', value: shownNum(value) }, opts));
        const tiers = TIERS.map(([k, tname]) => {
          const ball = () => (s.boss ? c.bossBallChance : c.ballChance)[k];
          const ob = () => (s.boss ? c.bossObtainChance : c.obtainChance)[k];
          const r = s.ballChance[k];
          const baseEl = cell(`포획(${tname}) 기본`, r && r.base);
          const maxEl = cell(`포획(${tname}) 최대`, r && r.max);
          const syncBall = () => {
            const b = baseEl.value, m = maxEl.value;
            if (b === '' && m === '') delete s.ballChance[k];
            else s.ballChance[k] = { base: Number(b === '' ? m : b), max: Number(m === '' ? b : m) };
            changed();
          };
          baseEl.addEventListener('input', syncBall);
          maxEl.addEventListener('input', syncBall);
          checks.add(ph(baseEl, () => (s.ballChance[k] || ball()).base), () => (s.ballChance[k] ? petRules.percent(s.ballChance[k].base) : ''));
          checks.add(ph(maxEl, () => (s.ballChance[k] || ball()).max), () => (s.ballChance[k] ? petRules.chanceMax(s.ballChance[k]) : ''));
          const obEl = cell(`획득(${tname})`, s.obtainChance[k], { oninput: (e) => {
            if (e.target.value === '') delete s.obtainChance[k]; else s.obtainChance[k] = Number(e.target.value);
            changed();
          } });
          checks.add(ph(obEl, ob), () => (s.obtainChance[k] !== undefined ? petRules.percent(s.obtainChance[k]) : ''));
          return h('div', { class: 'field' }, h('label', { style: 'min-width:5rem' }, tname),
            h('label', null, '포획'), baseEl, h('span', { class: 'muted' }, '% ~'), maxEl, h('span', { class: 'muted' }, '%'),
            h('label', null, '획득'), obEl, h('span', { class: 'muted' }, '%'));
        });
        return h('tr', null, h('td', { colspan: head.length },
          tiers, h('p', { class: 'muted' }, '빈칸은 전역 값 (보스 종은 보스 표). 포획은 체력이 최대%일 때 기본, 최소%일 때 최대.')));
      };

      const kinds = S.catalog.petKinds && S.catalog.petKinds.length ? S.catalog.petKinds : Object.keys(PET_KIND_LABEL);
      const keys = Object.keys(p.species).sort();
      const rows = keys.map((key) => {
        const s = p.species[key];
        const kindOpts = kinds.map((k) => h('option', { value: k }, PET_KIND_LABEL[k] || k));
        if (!kinds.includes(s.kind)) kindOpts.unshift(h('option', { value: s.kind }, `(알 수 없음) ${s.kind}`));
        const kindSel = checks.add(h('select', { 'aria-label': '종류', onchange: (e) => { s.kind = e.target.value; changed(); } }, kindOpts),
          () => petRules.kind(s.kind));
        kindSel.value = s.kind;
        const petIn = checks.add(h('input', { type: 'text', style: 'width:11rem', 'aria-label': '펫 엔티티', value: s.petEntity || '',
          oninput: (e) => { s.petEntity = e.target.value.trim(); changed(); } }), () => petRules.petEntity(s.petEntity));
        const n = (f, label, rule, opts) => h('td', null, numIn(s, f, label, rule, Object.assign({ min: 0, style: 'width:4.5rem' }, opts || {})));
        const bossChk = h('input', { type: 'checkbox', 'aria-label': '보스', checked: !!s.boss, onchange: (e) => { s.boss = e.target.checked; changed(); } });
        const name = entityName(key);
        const open = petChanceOpen.has(key);
        const row = h('tr', null,
          h('td', null, h('div', null, name), name !== key ? h('div', { class: 'id' }, key) : null),
          h('td', null, kindSel), h('td', null, petIn),
          SPECIES_NUM.map(([f, label]) => n(f, label, petRules.nonNeg)),
          h('td', null, bossChk),
          SPECIES_BOSS_NUM.map(([f, label]) => n(f, label, petRules.positive)),
          n('killGold', '처치골드', petRules.nonNegInt, { step: 1 }),
          n('jumpHeight', '점프(칸)', petRules.jumpHeight, { max: 10 }),
          h('td', null, h('button', { class: 'danger small', 'aria-label': '종 삭제', onclick: () => {
            const used = (S.bundle.boss_points || []).filter((b) => b.bossType === key).length;
            if (!confirm(`종 '${name}'을(를) 삭제할까요?` + (used ? `\n이 종을 쓰는 보스 지점이 ${used}개 있어, 고치지 않으면 저장이 거부됩니다.` : ''))) return;
            delete p.species[key]; petChanceOpen.delete(key); changed(); renderTabContent();
          } }, '삭제')),
          h('td', null, h('button', { class: 'small', 'aria-label': '종별 확률', 'aria-expanded': open ? 'true' : 'false',
            onclick: () => { if (open) petChanceOpen.delete(key); else petChanceOpen.add(key); renderTabContent(); } },
            open ? '확률 ▴' : '확률 ▾')));
        return open ? [row, speciesChanceRow(s, name)] : row;
      });
      const addSpecies = h('button', { class: 'primary', onclick: async () => {
        const id = await pickEntity({ allowTags: false, title: '포획할 야생 엔티티' });
        if (!id) return;
        if (p.species[id]) { toast('이미 목록에 있는 종입니다'); return; }
        p.species[id] = newSpecies(id);
        changed(); renderTabContent();
      } }, '+ 종 추가');
      const spCard = h('div', { class: 'card' }, h('h2', null, '종 목록'),
        h('p', { class: 'muted' }, '키는 포획할 야생 엔티티 id입니다. 크기·체력·공격 배율과 처치골드는 보스 종에만 쓰입니다. 점프(칸)은 지상 탈것이 점프 키로 뛰는 높이입니다. 확률 버튼에서 종별 포획/획득 확률을 등급별로 덮어쓸 수 있습니다. 빈칸은 전역 값.'),
        keys.length ? h('div', { class: 'scroll-x' }, h('table', null,
          h('thead', null, h('tr', null, head.map((t) => h('th', null, t)))), h('tbody', null, rows))) : h('p', { class: 'muted' }, '종이 없습니다.'),
        h('div', { class: 'field', style: 'margin-top:.5rem' }, addSpecies));

      container.append(h('div', { class: 'stack' }, h('div', { class: 'petcards' }, hbCard, capCard), spCard));
      return () => { checks.paint(); paintSwatch(); dynamic.forEach((f) => f()); };
    },
  };
  registerTab(petsTab);

  // 서버 EditorValidator(보호구역)와 같은 규칙: id는 비어 있지 않고 겹치지 않아야 한다.
  function protectIdError(list, i) {
    const id = list[i].id;
    if (!id || !String(id).trim()) return 'id가 비어 있습니다';
    return list.some((r, j) => j !== i && r.id === id) ? '같은 id가 있습니다' : '';
  }

  function validateProtect(bundle) {
    const list = (bundle.protect && bundle.protect.regions) || [];
    const out = [];
    list.forEach((r, i) => {
      const m = protectIdError(list, i);
      if (m) out.push(`보호구역 ${r.id || '#' + (i + 1)}: ${m}`);
    });
    return out;
  }

  const PROTECT_FLAGS = [['fireSpread', '불 번짐'], ['bossGrief', '보스 파괴'], ['explosions', '폭발']];

  const protectTab = {
    id: 'protect',
    title: '보호구역',
    validate: validateProtect,
    render(container) {
      const checks = makeChecks();
      const p = S.bundle.protect;
      const list = p.regions;
      const toggle = (key, label) => h('label', { class: 'chk' },
        h('input', { type: 'checkbox', checked: !!p[key], 'aria-label': label, onchange: (e) => { p[key] = e.target.checked; changed(); } }),
        ' ' + label);
      // x/z는 읽기 전용, y만 수정 가능. 정수가 아니면 저장값은 그대로 두고 빨갛게 표시, min>max면 경고(서버가 순서를 바로잡음).
      const posCell = (r, key) => {
        const v = r[key];
        if (!Array.isArray(v)) return h('span', { class: 'id' }, '');
        const inp = h('input', { type: 'number', step: 1, value: v[1], 'aria-label': key === 'min' ? '최소 y' : '최대 y', style: 'width:5rem',
          oninput: (e) => {
            const n = Number(e.target.value);
            if (e.target.value.trim() !== '' && Number.isInteger(n)) { v[1] = n; changed(); }
            checks.paint();
          } });
        checks.add(inp, () => {
          const raw = inp.value.trim();
          if (raw === '' || !Number.isInteger(Number(raw))) return 'y는 정수여야 합니다';
          return r.min[1] > r.max[1] ? '최소 y가 최대 y보다 큽니다 (저장하면 서버가 순서를 바꿉니다)' : '';
        });
        return h('span', { class: 'id' }, v[0] + ' ', inp, ' ' + v[2]);
      };
      const rows = list.map((r, i) => {
        const idCell = checks.add(h('span', null, r.id || '(빈 id)'), () => protectIdError(list, i));
        const flagCells = PROTECT_FLAGS.map(([key, label]) => h('td', null, h('input', { type: 'checkbox', checked: !!r.flags[key],
          'aria-label': label, title: '체크하면 허용', onchange: (e) => { r.flags[key] = e.target.checked; changed(); } })));
        return h('tr', null,
          h('td', null, idCell),
          h('td', null, h('span', { class: 'id' }, r.dimension)),
          h('td', null, posCell(r, 'min')),
          h('td', null, posCell(r, 'max')),
          ...flagCells,
          h('td', null, h('button', { class: 'danger small', 'aria-label': '구역 삭제', onclick: () => {
            if (!confirm(`보호구역 '${r.id}'을(를) 삭제할까요?
저장하면 이 구역은 더는 보호되지 않습니다.`)) return;
            list.splice(i, 1); changed(); renderTabContent();
          } }, '삭제')));
      });
      container.append(h('div', { class: 'stack' },
        h('div', { class: 'card' }, h('h2', null, '전역 규칙'),
          h('div', { class: 'field' }, toggle('explosionsBreakBlocks', '폭발이 블록을 부숨 (전역)')),
          h('div', { class: 'field' }, toggle('endermanPickup', '엔더맨 블록 집기 허용 (전역)'))),
        h('div', { class: 'card' }, h('h2', null, '보호구역'),
          h('p', { class: 'muted' }, '구역 추가는 게임 안에서 WorldEdit 으로 선택 후 /보호구역 추가 <id>'),
          h('p', { class: 'muted' }, '불 번짐·보스 파괴·폭발에 체크하면 그 구역 안에서 허용합니다 (체크 없음 = 막음).'),
          list.length ? h('div', { class: 'scroll-x' }, h('table', null,
            h('thead', null, h('tr', null, ['id', '차원', '최소', '최대'].concat(PROTECT_FLAGS.map((x) => x[1]), ['']).map((t) => h('th', null, t)))),
            h('tbody', null, rows))) : h('p', { class: 'muted' }, '보호구역이 없습니다.'))));
      return () => checks.paint();
    },
  };
  registerTab(protectTab);

  /* ----- 강화 탭 ----- */

  const REINFORCE_MAX_STONES = 999;
  const REINFORCE_MAX_GOLD = 999999999;
  /** ReinforceConfig.defaultGoldAt: 100 × 1.25^n 을 10 단위로 반올림 (Java Math.round 와 같은 반올림). */
  function defaultGold(size) {
    const out = [];
    for (let n = 0; n < size; n++) out.push(Math.round(100 * Math.pow(1.25, n) / 10) * 10);
    return out;
  }

  /** ReinforceConfig.DEFAULT 와 같은 값 (서버 코덱은 기본값과 같은 필드를 JSON에서 뺀다). */
  const REINFORCE_DEFAULTS = {
    maxLevel: 30,
    successPercent: [100, 100, 100, 90, 85, 80, 75, 70, 65, 60, 55, 50, 45, 40, 35, 30, 25, 20, 15, 10,
      10, 9, 9, 8, 8, 7, 7, 6, 6, 5],
    stoneCost: Array(30).fill(1),
    gunDamagePercentPerLevel: 10,
    meleeDamagePercentPerLevel: 10,
    shieldSpeedPercentPerLevel: 1.7,
    armorHealthPerLevel: 0.5,
    armorHealthMax: 60,
    armorDefensePerLevel: 0.05,
    goldCost: defaultGold(30),
    enchantGoldCost: 1000,
    elytra: { firstStoneCost: 32, firstGoldCost: 50000, speedPercentPerLevel: 2, fireworkBoostLevel: 15, fireworkBoostPercent: 50 },
    milestones: [],
  };
  /** 단계 능력 대상 (ReinforceRules.Target 소문자). */
  const REINFORCE_TARGETS = [['gun', '총'], ['melee', '근접'], ['shield', '방패'], ['armor', '방어구']];
  const EFFECT_ID = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/;
  const REINFORCE_MAX_LEVEL = 50;
  const REINFORCE_GLOBALS = [
    ['gunDamagePercentPerLevel', '총 피해', '%/단계'],
    ['meleeDamagePercentPerLevel', '근접 공격력', '%/단계'],
    ['shieldSpeedPercentPerLevel', '방패 이동속도', '%/단계'],
    ['armorHealthPerLevel', '방어구 체력', '/단계 (하트 반 칸 = 1)'],
    ['armorHealthMax', '체력 상한', '(0 = 없음)'],
    ['armorDefensePerLevel', '방어구 방어', '/단계'],
  ];

  /**
   * 단계별 표를 size 칸으로 (서버 ReinforceConfig.sanitized 와 같은 규칙): 길면 자르고, 짧으면 마지막 값을 반복,
   * 비어 있으면 기본 표의 같은 칸 (기본 표보다 길면 그 마지막 값).
   */
  function resizeReinforceList(list, def, size) {
    const src = Array.isArray(list) ? list : [];
    const out = [];
    for (let n = 0; n < size; n++) {
      if (n < src.length) out.push(src[n]);
      else if (src.length) out.push(src[src.length - 1]);
      else out.push(def[Math.min(n, def.length - 1)]);
    }
    return out;
  }

  const validMaxLevel = (v) => Number.isInteger(v) && v >= 1 && v <= REINFORCE_MAX_LEVEL;

  /** 빠진 필드는 기본값, 표 두 개는 최대 단계 길이로 맞춘다 (최대 단계가 범위 밖이면 기본 30칸). */
  function normalizeReinforce(src) {
    const r = Object.assign({}, REINFORCE_DEFAULTS, src || {});
    const size = validMaxLevel(r.maxLevel) ? r.maxLevel : REINFORCE_DEFAULTS.maxLevel;
    r.successPercent = resizeReinforceList(r.successPercent, REINFORCE_DEFAULTS.successPercent, size);
    r.stoneCost = resizeReinforceList(r.stoneCost, REINFORCE_DEFAULTS.stoneCost, size);
    // 골드 표가 없으면(서버 코덱은 기본값과 같은 필드를 뺀다) 최대 단계 길이의 기본 공식.
    r.goldCost = Array.isArray(src && src.goldCost) ? resizeReinforceList(r.goldCost, defaultGold(size), size) : defaultGold(size);
    r.elytra = Object.assign({}, REINFORCE_DEFAULTS.elytra, (src && src.elytra) || {});
    // 단계 능력: 서버 코덱은 빠진 칸을 기본값(target·effect 빈 문자열, level·amplifier 0)으로 읽는다.
    r.milestones = (Array.isArray(r.milestones) ? r.milestones : []).map((m) => Object.assign(
      { target: '', level: 0, effect: '', amplifier: 0 }, m && typeof m === 'object' ? m : {}));
    return r;
  }

  const reinforceRules = {
    maxLevel(v) { return validMaxLevel(v) ? '' : `1~${REINFORCE_MAX_LEVEL} 사이의 정수여야 합니다`; },
    nonNeg(v) { return isNum(v) && v >= 0 ? '' : '0 이상의 숫자여야 합니다'; },
    percent(v) { return isNum(v) && v >= 0 && v <= 100 ? '' : '0~100 사이여야 합니다'; },
    stones(v) { return isInt(v) && v >= 1 && v <= REINFORCE_MAX_STONES ? '' : `1~${REINFORCE_MAX_STONES} 사이의 정수여야 합니다`; },
    gold(v) { return isInt(v) && v >= 0 && v <= REINFORCE_MAX_GOLD ? '' : `0~${REINFORCE_MAX_GOLD} 사이의 정수여야 합니다`; },
    boostLevel(v) { return isInt(v) && v >= 1 && v <= REINFORCE_MAX_LEVEL ? '' : `1~${REINFORCE_MAX_LEVEL} 사이의 정수여야 합니다`; },
    msTarget(m) { return REINFORCE_TARGETS.some(([t]) => t === m.target) ? '' : '대상을 고르세요'; },
    msLevel(m, max) {
      const top = validMaxLevel(max) ? max : REINFORCE_MAX_LEVEL;
      return isInt(m.level) && m.level >= 1 && m.level <= top ? '' : `1~${top} 사이의 정수여야 합니다`;
    },
    msAmplifier(m) { return isInt(m.amplifier) && m.amplifier >= 0 && m.amplifier <= 9 ? '' : '레벨은 1~10 사이의 정수여야 합니다'; },
    msEffect(m) {
      if (typeof m.effect !== 'string' || !EFFECT_ID.test(m.effect)) return '효과 id 형식이 아닙니다 (예: minecraft:speed)';
      const known = S.catalog.mobEffects || [];
      if (known.length && !known.some((e) => e.id === m.effect)) return `효과 목록에 없는 id 입니다: ${m.effect}`;
      return '';
    },
  };
  const milestoneWhere = (i) => `단계 능력 ${i + 1}번`;
  const reinforceStep = (n) => `+${n} → +${n + 1}`;

  function validateReinforce(bundle) {
    const out = [];
    const r = bundle.reinforce;
    const add = (m, where) => { if (m) out.push(`강화 › ${where}: ${m}`); };
    add(reinforceRules.maxLevel(r.maxLevel), '최대 단계');
    for (const [key, label] of REINFORCE_GLOBALS) add(reinforceRules.nonNeg(r[key]), label);
    r.successPercent.forEach((v, n) => add(reinforceRules.percent(v), `${reinforceStep(n)} 성공 확률`));
    r.stoneCost.forEach((v, n) => add(reinforceRules.stones(v), `${reinforceStep(n)} 강화석 개수`));
    r.goldCost.forEach((v, n) => add(reinforceRules.gold(v), `${reinforceStep(n)} 골드`));
    add(reinforceRules.gold(r.enchantGoldCost), '마법부여 골드');
    add(reinforceRules.stones(r.elytra.firstStoneCost), '겉날개 1강 강화석');
    add(reinforceRules.gold(r.elytra.firstGoldCost), '겉날개 1강 골드');
    add(reinforceRules.nonNeg(r.elytra.speedPercentPerLevel), '겉날개 비행 속도');
    add(reinforceRules.boostLevel(r.elytra.fireworkBoostLevel), '겉날개 폭죽 부스트 단계');
    add(reinforceRules.nonNeg(r.elytra.fireworkBoostPercent), '겉날개 폭죽 부스트');
    r.milestones.forEach((m, i) => {
      add(reinforceRules.msTarget(m), `${milestoneWhere(i)} 대상`);
      add(reinforceRules.msLevel(m, r.maxLevel), `${milestoneWhere(i)} 단계`);
      add(reinforceRules.msEffect(m), `${milestoneWhere(i)} 효과`);
      add(reinforceRules.msAmplifier(m), `${milestoneWhere(i)} 레벨`);
    });
    return out;
  }

  const reinforceTab = {
    id: 'reinforce',
    title: '강화',
    validate: validateReinforce,
    render(container) {
      const checks = makeChecks();
      let rowChecks = makeChecks();   // 단계별 표: 최대 단계가 바뀌면 표와 함께 새로 만든다
      const r = S.bundle.reinforce;
      const numIn = (chk, obj, key, label, rule, opts) => chk.add(h('input', Object.assign({ type: 'number', step: 'any', min: 0,
        'aria-label': label, style: 'width:5.5rem', value: shownNum(obj[key]),
        oninput: (e) => { obj[key] = numOf(e); changed(); } }, opts || {})), () => rule(obj[key]));
      const labeled = (label, el, unit) => [h('label', { style: 'min-width:8rem' }, label), el, unit ? h('span', { class: 'muted' }, unit) : null];

      const tbody = h('tbody');
      const drawRows = () => {
        rowChecks = makeChecks();
        tbody.replaceChildren(...r.successPercent.map((v, n) => h('tr', null,
          h('td', null, reinforceStep(n)),
          h('td', null, numIn(rowChecks, r.successPercent, n, `${reinforceStep(n)} 성공 확률`, reinforceRules.percent, { max: 100 }),
            h('span', { class: 'muted' }, ' %')),
          h('td', null, numIn(rowChecks, r.stoneCost, n, `${reinforceStep(n)} 강화석 개수`, reinforceRules.stones, { step: 1, min: 1, max: REINFORCE_MAX_STONES }),
            h('span', { class: 'muted' }, ' 개')),
          h('td', null, numIn(rowChecks, r.goldCost, n, `${reinforceStep(n)} 골드`, reinforceRules.gold, { step: 1, min: 0, max: REINFORCE_MAX_GOLD, style: 'width:7.5rem' }),
            h('span', { class: 'muted' }, ' 골드')))));
        rowChecks.paint();
      };

      // 입력 중("1" → "15")에 표가 잘리지 않도록 표 길이는 change(포커스 이동·Enter·화살표)에서 맞춘다.
      const maxIn = numIn(checks, r, 'maxLevel', '최대 단계', reinforceRules.maxLevel, { step: 1, min: 1, max: REINFORCE_MAX_LEVEL });
      maxIn.addEventListener('change', () => {
        if (!validMaxLevel(r.maxLevel) || r.maxLevel === r.successPercent.length) return;
        r.successPercent = resizeReinforceList(r.successPercent, REINFORCE_DEFAULTS.successPercent, r.maxLevel);
        r.stoneCost = resizeReinforceList(r.stoneCost, REINFORCE_DEFAULTS.stoneCost, r.maxLevel);
        r.goldCost = resizeReinforceList(r.goldCost, defaultGold(r.maxLevel), r.maxLevel);
        drawRows();
        changed();
      });

      const globalCard = h('div', { class: 'card' }, h('h2', null, '전역'),
        h('div', { class: 'field' }, labeled('최대 단계', maxIn, `(1~${REINFORCE_MAX_LEVEL})`)),
        REINFORCE_GLOBALS.map(([key, label, unit]) => h('div', { class: 'field' },
          labeled(label, numIn(checks, r, key, label, reinforceRules.nonNeg), unit))),
        h('p', { class: 'muted' }, '각 수치에 강화 단계를 곱해 적용합니다 (예: 총 피해 10 이면 +3 무기는 +30%). 체력 상한 0 = 상한 없음.'));
      const stepCard = h('div', { class: 'card' }, h('h2', null, '단계별'),
        h('p', { class: 'muted' }, '최대 단계를 바꾸면 표가 따라 늘거나 줄어듭니다. 늘어난 칸은 마지막 값을 복사합니다.'),
        h('div', { class: 'scroll-x' }, h('table', null,
          h('thead', null, h('tr', null, ['단계', '성공 확률', '강화석 개수', '골드'].map((t) => h('th', null, t)))), tbody)));
      const e = r.elytra;
      const elytraCard = h('div', { class: 'card' }, h('h2', null, '겉날개'),
        h('div', { class: 'field' }, labeled('1강 강화석', numIn(checks, e, 'firstStoneCost', '겉날개 1강 강화석', reinforceRules.stones, { step: 1, min: 1, max: REINFORCE_MAX_STONES }), '개')),
        h('div', { class: 'field' }, labeled('1강 골드', numIn(checks, e, 'firstGoldCost', '겉날개 1강 골드', reinforceRules.gold, { step: 1, min: 0, max: REINFORCE_MAX_GOLD, style: 'width:7.5rem' }), '골드')),
        h('div', { class: 'field' }, labeled('비행 속도', numIn(checks, e, 'speedPercentPerLevel', '겉날개 비행 속도', reinforceRules.nonNeg), '%/단계 (2강부터)')),
        h('div', { class: 'field' }, labeled('폭죽 부스트 단계', numIn(checks, e, 'fireworkBoostLevel', '겉날개 폭죽 부스트 단계', reinforceRules.boostLevel, { step: 1, min: 1, max: REINFORCE_MAX_LEVEL }), '강 이상')),
        h('div', { class: 'field' }, labeled('폭죽 부스트', numIn(checks, e, 'fireworkBoostPercent', '겉날개 폭죽 부스트', reinforceRules.nonNeg), '%')),
        h('p', { class: 'muted' }, '1강(내구도 무한)만 위 비용을 쓰고, 성공 확률과 2강 이후 비용은 단계별 표를 따릅니다.'));
      const enchantCard = h('div', { class: 'card' }, h('h2', null, '마법부여 비용'),
        h('div', { class: 'field' }, labeled('1회 골드', numIn(checks, r, 'enchantGoldCost', '마법부여 골드', reinforceRules.gold, { step: 1, min: 0, max: REINFORCE_MAX_GOLD, style: 'width:7.5rem' }), '골드 (+ 마법부여석 1개)')));
      drawRows();

      // 단계 능력: 행 추가/삭제는 이 카드의 표만 다시 그린다.
      let msChecks = makeChecks();
      const msBody = h('tbody');
      const effects = S.catalog.mobEffects || [];
      const msTable = h('div', { class: 'scroll-x' }, h('table', null,
        h('thead', null, h('tr', null, ['대상', '단계', '효과', '레벨', ''].map((t) => h('th', null, t)))), msBody));
      const msEmpty = h('p', { class: 'muted' }, '단계 능력이 없습니다.');
      const drawMilestones = () => {
        msChecks = makeChecks();
        msBody.replaceChildren(...r.milestones.map((m, i) => {
          const where = milestoneWhere(i);
          const tOpts = REINFORCE_TARGETS.map(([t, label]) => h('option', { value: t }, label));
          if (!REINFORCE_TARGETS.some(([t]) => t === m.target)) tOpts.unshift(h('option', { value: m.target }, '(고르세요)'));
          const tSel = msChecks.add(h('select', { 'aria-label': `${where} 대상`, onchange: (e) => { m.target = e.target.value; changed(); } }, tOpts),
            () => reinforceRules.msTarget(m));
          tSel.value = m.target;
          const lvIn = msChecks.add(h('input', { type: 'number', step: 1, min: 1, max: REINFORCE_MAX_LEVEL, 'aria-label': `${where} 단계`,
            style: 'width:5rem', value: shownNum(m.level), oninput: (e) => { m.level = numOf(e); changed(); } }),
          () => reinforceRules.msLevel(m, r.maxLevel));
          let effIn;
          if (effects.length) {
            const eOpts = effects.map((e) => h('option', { value: e.id }, e.name && e.name !== e.id ? `${e.name} (${e.id})` : e.id));
            if (!effects.some((e) => e.id === m.effect)) eOpts.unshift(h('option', { value: m.effect }, m.effect ? `(목록에 없음) ${m.effect}` : '(고르세요)'));
            effIn = h('select', { 'aria-label': `${where} 효과`, onchange: (e) => { m.effect = e.target.value; changed(); } }, eOpts);
            effIn.value = m.effect;
          } else {
            effIn = h('input', { type: 'text', 'aria-label': `${where} 효과`, placeholder: 'minecraft:speed', style: 'width:14rem',
              value: m.effect, oninput: (e) => { m.effect = e.target.value.trim(); changed(); } });
          }
          msChecks.add(effIn, () => reinforceRules.msEffect(m));
          const ampIn = msChecks.add(h('input', { type: 'number', step: 1, min: 1, max: 10, 'aria-label': `${where} 레벨`, style: 'width:4.5rem',
            value: isNum(m.amplifier) ? m.amplifier + 1 : '',
            oninput: (e) => { const v = numOf(e); m.amplifier = isNum(v) ? v - 1 : NaN; changed(); } }),
          () => reinforceRules.msAmplifier(m));
          return h('tr', null,
            h('td', null, tSel),
            h('td', null, h('span', { class: 'muted' }, '+'), lvIn, h('span', { class: 'muted' }, ' 이상')),
            h('td', null, effIn),
            h('td', null, ampIn),
            h('td', null, h('button', { class: 'danger small', 'aria-label': `${where} 삭제`, onclick: () => {
              r.milestones.splice(i, 1); changed(); drawMilestones();
            } }, '삭제')));
        }));
        msTable.hidden = !r.milestones.length;
        msEmpty.hidden = !!r.milestones.length;
        msChecks.paint();
      };
      const milestoneCard = h('div', { class: 'card' }, h('h2', null, '단계 능력'),
        h('p', { class: 'muted' }, '조건: 총·근접은 주손, 방패는 양손 중 하나, 방어구는 착용한 네 부위 모두 해당 단계 이상일 때 효과가 유지됩니다.'),
        msTable, msEmpty,
        S.catalogOk && !effects.length ? h('p', { class: 'muted' }, '효과 목록을 불러오지 못했습니다. 효과 id 를 직접 입력하세요.') : null,
        h('button', { class: 'small', onclick: () => {
          r.milestones.push({ target: 'armor', level: Math.min(10, validMaxLevel(r.maxLevel) ? r.maxLevel : REINFORCE_MAX_LEVEL),
            effect: effects.length ? effects[0].id : 'minecraft:speed', amplifier: 0 });
          changed(); drawMilestones();
        } }, '+ 능력 추가'));
      drawMilestones();

      container.append(h('div', { class: 'stack' }, h('div', { class: 'petcards' }, globalCard, stepCard),
        h('div', { class: 'petcards' }, elytraCard, enchantCard), milestoneCard));
      return () => { checks.paint(); rowChecks.paint(); msChecks.paint(); };
    },
  };
  registerTab(reinforceTab);

  /* ----- 사냥터 탭 ----- */

  // 서버 HuntingConfig.sanitized / EditorValidator 와 같은 범위.
  const HUNT_LIMITS = {
    health: [1, 1024], attack: [0, 2048], speed: [0, 1024], armor: [0, 30], scale: [0.0625, 16],
    weight: [0, 10000], xpMultiplier: [0, 100], maxAlive: [0, 100], refillSeconds: [1, 3600],
  };
  const HUNT_INT = new Set(['weight', 'maxAlive', 'refillSeconds']);
  const HUNT_COLS = [
    ['health', '체력'], ['attack', '공격력'], ['speed', '이동 속도'], ['armor', '방어'], ['scale', '크기'],
    ['weight', '가중치'], ['gold', '골드'], ['xpMultiplier', '경험치 배율'],
  ];
  const AXES = ['x', 'y', 'z'];

  /** 몬스터 카탈로그 (등록된 살아 있는 엔티티와 기본 속성값). id -> {id, name, mod, category, health, attack, speed, armor} */
  function mobIndex() {
    const m = new Map();
    for (const e of (S.catalog.mobs || [])) m.set(e.id, e);
    return m;
  }

  const huntRules = {
    num(key, v) {
      if (key === 'gold') return isInt(v) && v >= 0 ? '' : '0 이상의 정수여야 합니다';
      const [lo, hi] = HUNT_LIMITS[key];
      if (HUNT_INT.has(key) ? !isInt(v) : !isNum(v)) return HUNT_INT.has(key) ? '정수여야 합니다' : '숫자여야 합니다';
      return v >= lo && v <= hi ? '' : `${lo}~${hi} 사이여야 합니다`;
    },
    coord(v) { return isInt(v) ? '' : '정수여야 합니다'; },
    zoneId(list, i) {
      const id = list[i].id;
      if (!id || !String(id).trim()) return 'id가 비어 있습니다';
      return list.some((z, j) => j !== i && z.id === id) ? '같은 id가 있습니다' : '';
    },
    name(z, i) {
      const n = z.entries[i].name;
      if (!n || !String(n).trim()) return '이름이 비어 있습니다';
      return z.entries.some((e, j) => j !== i && e.name === n) ? '같은 이름이 있습니다' : '';
    },
    entity(e) {
      if (!e.entity || !ITEM_ID.test(e.entity)) return '몬스터를 고르세요';
      const known = S.catalog.mobs || [];
      if (known.length && !known.some((m) => m.id === e.entity)) return `몬스터 목록에 없는 id 입니다: ${e.entity}`;
      return '';
    },
  };

  function validateHunting(bundle) {
    const out = [];
    const zones = (bundle.hunting && bundle.hunting.zones) || [];
    zones.forEach((z, i) => {
      const w = `사냥터 ${z.id || '#' + (i + 1)}`;
      const add = (m, where) => { if (m) out.push(`${w}${where ? ' › ' + where : ''}: ${m}`); };
      add(huntRules.zoneId(zones, i));
      for (const k of ['min', 'max']) AXES.forEach((a, n) => add(huntRules.coord(Array.isArray(z[k]) ? z[k][n] : NaN), `${k === 'min' ? '최소' : '최대'} ${a}`));
      add(huntRules.num('maxAlive', z.maxAlive), '최대 수');
      add(huntRules.num('refillSeconds', z.refillSeconds), '채우는 간격');
      z.entries.forEach((e, j) => {
        const ew = `몬스터 ${e.name || '#' + (j + 1)}`;
        add(huntRules.name(z, j), ew + ' 이름');
        add(huntRules.entity(e), ew + ' 종류');
        for (const [key, label] of HUNT_COLS) add(huntRules.num(key, e[key]), `${ew} ${label}`);
      });
    });
    return out;
  }

  const huntingTab = {
    id: 'hunting',
    title: '사냥터',
    validate: validateHunting,
    render(container) {
      const checks = makeChecks();
      const zones = S.bundle.hunting.zones;
      const mobs = mobIndex();
      const mobList = (S.catalog.mobs || []).slice().sort((a, b) => (a.name || a.id).localeCompare(b.name || b.id));
      const mobLabel = (id) => { const m = mobs.get(id); return m && m.name && m.name !== id ? `${m.name} (${id})` : id; };
      const numIn = (obj, key, label, opts) => checks.add(h('input', Object.assign({ type: 'number', step: HUNT_INT.has(key) || key === 'gold' ? 1 : 'any',
        'aria-label': label, style: 'width:5.5rem', value: shownNum(obj[key]),
        oninput: (e) => { obj[key] = numOf(e); changed(); } }, opts || {})), () => huntRules.num(key, obj[key]));
      const coordIn = (arr, n, label) => checks.add(h('input', { type: 'number', step: 1, 'aria-label': label, style: 'width:5.5rem',
        value: shownNum(arr[n]), oninput: (e) => { arr[n] = numOf(e); changed(); } }), () => huntRules.coord(arr[n]));
      const fillFromBase = (e) => {
        const m = mobs.get(e.entity);
        if (!m) return;
        for (const k of ['health', 'attack', 'speed', 'armor']) if (isNum(m[k])) e[k] = m[k];
      };

      const zoneCard = (z, i) => {
        if (!Array.isArray(z.min)) z.min = [0, 0, 0];
        if (!Array.isArray(z.max)) z.max = [0, 0, 0];
        const where = `사냥터 ${z.id}`;
        const rows = z.entries.map((e, j) => {
          const ew = `${where} 몬스터 ${j + 1}`;
          const nameIn = checks.add(h('input', { type: 'text', value: e.name, 'aria-label': `${ew} 이름`, style: 'width:8rem',
            oninput: (ev) => { e.name = ev.target.value; changed(); } }), () => huntRules.name(z, j));
          let entIn;
          if (mobList.length) {
            const opts = mobList.map((m) => h('option', { value: m.id }, mobLabel(m.id)));
            if (!mobs.has(e.entity)) opts.unshift(h('option', { value: e.entity }, e.entity ? `(목록에 없음) ${e.entity}` : '(고르세요)'));
            entIn = h('select', { 'aria-label': `${ew} 종류`, style: 'max-width:14rem', onchange: (ev) => { e.entity = ev.target.value; changed(); } }, opts);
            entIn.value = e.entity;
          } else {
            entIn = h('input', { type: 'text', 'aria-label': `${ew} 종류`, placeholder: 'minecraft:zombie', style: 'width:12rem',
              value: e.entity, oninput: (ev) => { e.entity = ev.target.value.trim(); changed(); } });
          }
          checks.add(entIn, () => huntRules.entity(e));
          return h('tr', null,
            h('td', null, nameIn),
            h('td', null, entIn, ' ', h('button', { class: 'small', title: '이 몬스터의 기본 체력·공격력·이동 속도·방어를 넣습니다', 'aria-label': `${ew} 기본값 넣기`,
              onclick: () => { fillFromBase(e); changed(); renderTabContent(); } }, '기본값')),
            ...HUNT_COLS.map(([key, label]) => h('td', null, numIn(e, key, `${ew} ${label}`))),
            h('td', null, h('button', { class: 'danger small', 'aria-label': `${ew} 삭제`, onclick: () => {
              z.entries.splice(j, 1); changed(); renderTabContent();
            } }, '삭제')));
        });
        const idCell = checks.add(h('strong', null, z.id || '(빈 id)'), () => huntRules.zoneId(zones, i));
        return h('div', { class: 'card' },
          h('h2', null, idCell, ' ', h('span', { class: 'id' }, z.dimension), ' ',
            h('button', { class: 'danger small', 'aria-label': `${where} 삭제`, onclick: () => {
              if (!confirm(`사냥터 '${z.id}'을(를) 삭제할까요?\n저장하면 이 사냥터의 몬스터는 사라지고, 범위 안에 다시 일반 몬스터가 나옵니다.`)) return;
              zones.splice(i, 1); changed(); renderTabContent();
            } }, '사냥터 삭제')),
          h('div', { class: 'field' }, h('label', { style: 'min-width:5rem' }, '최소'), ...AXES.map((a, n) => coordIn(z.min, n, `${where} 최소 ${a}`))),
          h('div', { class: 'field' }, h('label', { style: 'min-width:5rem' }, '최대'), ...AXES.map((a, n) => coordIn(z.max, n, `${where} 최대 ${a}`))),
          h('div', { class: 'field' }, h('label', { style: 'min-width:5rem' }, '최대 수'), numIn(z, 'maxAlive', `${where} 최대 수`, { min: 0, max: 100 }),
            h('span', { class: 'muted' }, '마리 동시에'),
            h('label', { style: 'min-width:5rem; margin-left:1rem' }, '채우는 간격'), numIn(z, 'refillSeconds', `${where} 채우는 간격`, { min: 1, max: 3600 }),
            h('span', { class: 'muted' }, '초마다 한 마리')),
          z.entries.length ? h('div', { class: 'scroll-x' }, h('table', null,
            h('thead', null, h('tr', null, ['이름', '몬스터'].concat(HUNT_COLS.map((c) => c[1]), ['']).map((t) => h('th', null, t)))),
            h('tbody', null, rows))) : h('p', { class: 'muted' }, '몬스터가 없습니다. 몬스터가 없으면 이 사냥터에는 아무것도 나오지 않습니다.'),
          h('button', { class: 'small', onclick: () => {
            let n = z.entries.length + 1;
            while (z.entries.some((e) => e.name === `새 몬스터 ${n}`)) n++;
            const e = Object.assign({ name: `새 몬스터 ${n}`, entity: mobs.has('minecraft:zombie') || !mobList.length ? 'minecraft:zombie' : mobList[0].id }, HUNT_ENTRY_DEFAULTS);
            fillFromBase(e);
            z.entries.push(e); changed(); renderTabContent();
          } }, '+ 몬스터 추가'));
      };

      container.append(h('div', { class: 'stack' },
        h('div', { class: 'card' }, h('h2', null, '사냥터'),
          h('p', { class: 'muted' }, '사냥터 추가는 게임 안에서 WorldEdit 으로 선택 후 /사냥터 추가 <이름>. 사냥터 안에서는 일반 몬스터·동물이 자연히 나오지 않고, 아래 몬스터만 나옵니다.'),
          h('p', { class: 'muted' }, '플레이어가 사냥터(+32블록) 근처에 있을 때만 채우는 간격마다 한 마리씩, 최대 수까지 채웁니다. 능력치는 기본값 배율이 아니라 적은 숫자 그대로입니다 (가중치가 클수록 자주 나옴, 0 = 안 나옴).'),
          h('p', { class: 'muted' }, '바꾼 값은 새로 나오는 몬스터부터 적용됩니다 (이미 나온 몬스터는 그대로). 시험은 /사냥터 소환 <사냥터> <몬스터 이름> — 때리면 채팅에 피해가 표시됩니다.'),
          S.catalogOk && !mobList.length ? h('p', { class: 'muted' }, '몬스터 목록을 불러오지 못했습니다. 엔티티 id 를 직접 입력하세요.') : null,
          zones.length ? null : h('p', { class: 'muted' }, '사냥터가 없습니다.')),
        ...zones.map(zoneCard)));
      return () => checks.paint();
    },
  };
  registerTab(huntingTab);

  /* ----- 몬스터 탭 ----- */

  const MOB_STATS = [['health', '체력', 1, 1024], ['attack', '공격력', 0, 2048], ['speed', '이동 속도', 0, 1024], ['armor', '방어', 0, 30]];
  const MOB_CATEGORY_LABEL = { monster: '적대', creature: '동물', ambient: '주변', axolotls: '아홀로틀', underground_water_creature: '지하 물', water_creature: '물', water_ambient: '물고기', misc: '기타' };

  function mobRule(key, v) {
    if (v === undefined) return '';
    const s = MOB_STATS.find((x) => x[0] === key);
    if (!isNum(v)) return '숫자여야 합니다 (비우면 기본값)';
    return v >= s[2] && v <= s[3] ? '' : `${s[2]}~${s[3]} 사이여야 합니다`;
  }

  function validateMobs(bundle) {
    const out = [];
    for (const [id, o] of Object.entries((bundle.mobs && bundle.mobs.overrides) || {})) {
      for (const [key, label] of MOB_STATS) {
        const m = mobRule(key, o[key]);
        if (m) out.push(`몬스터 ${id} › ${label}: ${m}`);
      }
    }
    return out;
  }

  const mobsUi = { query: '', category: 'monster', onlyChanged: false };

  const mobsTab = {
    id: 'mobs',
    title: '몬스터',
    validate: validateMobs,
    render(container) {
      let rowChecks = makeChecks();
      const overrides = S.bundle.mobs.overrides;
      const catalog = (S.catalog.mobs || []).slice();
      const known = new Set(catalog.map((m) => m.id));
      for (const id of Object.keys(overrides)) if (!known.has(id)) catalog.push({ id, name: '', mod: '', category: '', missing: true });
      catalog.sort((a, b) => (a.name || a.id).localeCompare(b.name || b.id));
      const shown = (v) => (isNum(v) ? String(Math.round(v * 1000) / 1000) : '없음');

      const tbody = h('tbody');
      const count = h('span', { class: 'muted' });
      const draw = () => {
        rowChecks = makeChecks();
        const q = mobsUi.query.trim().toLowerCase();
        const list = catalog.filter((m) => (!mobsUi.onlyChanged || overrides[m.id])
          && (!mobsUi.category || m.missing || m.category === mobsUi.category)
          && (!q || ((m.name || '') + ' ' + m.id + ' ' + (m.mod || '')).toLowerCase().includes(q)));
        count.textContent = `${list.length}종`;
        tbody.replaceChildren(...list.map((m) => {
          const cells = MOB_STATS.map(([key, label]) => {
            const base = m[key];
            const o = overrides[m.id];
            const cur = o ? o[key] : undefined;
            const inp = rowChecks.add(h('input', { type: 'number', step: 'any', style: 'width:5.5rem', 'aria-label': `${m.name || m.id} ${label}`,
              placeholder: shown(base), value: shownNum(cur), disabled: !m.missing && !isNum(base) && cur === undefined,
              oninput: (e) => {
                const raw = e.target.value.trim();
                const row = overrides[m.id] || (overrides[m.id] = {});
                if (raw === '') delete row[key]; else row[key] = Number(raw);
                if (!Object.keys(row).length) delete overrides[m.id];
                changed();
              } }), () => mobRule(key, overrides[m.id] ? overrides[m.id][key] : undefined));
            return h('td', null, inp, h('div', { class: 'id' }, `기본 ${shown(base)}`));
          });
          return h('tr', null,
            h('td', null, h('div', null, m.missing ? '(목록에 없음)' : (m.name || m.id)), h('div', { class: 'id' }, m.id + (m.mod ? ' · ' + m.mod : ''))),
            h('td', null, h('span', { class: 'muted' }, MOB_CATEGORY_LABEL[m.category] || m.category || '')),
            ...cells,
            h('td', null, overrides[m.id] ? h('button', { class: 'small', 'aria-label': `${m.id} 기본값으로`, onclick: () => {
              delete overrides[m.id]; changed(); draw();
            } }, '기본값으로') : null));
        }));
        rowChecks.paint();
      };

      const cats = Array.from(new Set((S.catalog.mobs || []).map((m) => m.category).filter(Boolean))).sort();
      const catSel = h('select', { 'aria-label': '분류', onchange: (e) => { mobsUi.category = e.target.value; draw(); } },
        h('option', { value: '' }, '전체'), ...cats.map((c) => h('option', { value: c }, MOB_CATEGORY_LABEL[c] || c)));
      catSel.value = cats.includes(mobsUi.category) ? mobsUi.category : '';
      mobsUi.category = catSel.value;
      const search = h('input', { type: 'search', placeholder: '이름·id·모드 검색', 'aria-label': '몬스터 검색', value: mobsUi.query, style: 'width:16rem',
        oninput: (e) => { mobsUi.query = e.target.value; draw(); } });
      const onlyChanged = h('label', { class: 'chk' }, h('input', { type: 'checkbox', checked: mobsUi.onlyChanged,
        onchange: (e) => { mobsUi.onlyChanged = e.target.checked; draw(); } }), ' 바꾼 것만');

      container.append(h('div', { class: 'card' }, h('h2', null, '몬스터 기본 능력치'),
        h('p', { class: 'muted' }, '칸에 숫자를 넣으면 그 몬스터 종류의 기본값을 바꿉니다 (빈 칸 = 원래 기본값). 저장하면 새로 나오는 몬스터부터 적용되고, 이미 나와 있는 몬스터는 그대로입니다.'),
        h('p', { class: 'muted' }, '보스는 바꾼 값 위에 보스 배율이 곱해집니다. Gett 펫과 사냥터 몬스터에는 적용되지 않습니다 (사냥터 몬스터는 사냥터 탭의 숫자를 씀). 이동 속도는 속성값(좀비 0.23)입니다.'),
        S.catalogOk && !(S.catalog.mobs || []).length ? h('p', { class: 'muted' }, '몬스터 목록을 불러오지 못했습니다. 바꾼 항목만 보입니다.') : null,
        h('div', { class: 'field' }, search, catSel, onlyChanged, count),
        h('div', { class: 'scroll-x' }, h('table', null,
          h('thead', null, h('tr', null, ['몬스터', '분류'].concat(MOB_STATS.map((s) => s[1]), ['']).map((t) => h('th', null, t)))), tbody))));
      draw();
      return () => rowChecks.paint();
    },
  };
  registerTab(mobsTab);

  /* ----- 장비 탭 ----- */

  // [키, 제목, 최소, 최대, 화면 배율(밀치기 저항은 툴팁처럼 %), 단위, 해당 분류]
  const EQUIP_STATS = [
    ['attackDamage', '공격력', 0, 2048, 1, '', 'melee'],
    ['attackSpeed', '공격 속도', 0, 1024, 1, '', 'melee'],
    ['armor', '방어', 0, 30, 1, '', 'defense'],
    ['toughness', '방어 강도', 0, 20, 1, '', 'defense'],
    ['knockbackResistance', '밀치기 저항', 0, 1, 100, '%', 'defense'],
  ];
  const EQUIP_KIND_LABEL = { melee: '근접 무기', armor: '방어구', shield: '방패' };
  const EQUIP_SLOT_LABEL = { head: '머리', chest: '몸통', legs: '다리', feet: '발', body: '몸(동물)', armor: '방어구 칸', mainhand: '주 손', offhand: '보조 손', hand: '손', any: '아무 칸' };

  function equipRule(key, v) {
    if (v === undefined) return '';
    const s = EQUIP_STATS.find((x) => x[0] === key);
    if (!isNum(v)) return '숫자여야 합니다 (비우면 원래 값)';
    return v >= s[2] && v <= s[3] ? '' : `${s[2] * s[4]}~${s[3] * s[4]}${s[5]} 사이여야 합니다`;
  }

  function validateEquipment(bundle) {
    const out = [];
    for (const [id, o] of Object.entries((bundle.equipment && bundle.equipment.items) || {})) {
      for (const [key, label] of EQUIP_STATS) {
        const m = equipRule(key, o[key]);
        if (m) out.push(`장비 ${id} › ${label}: ${m}`);
      }
    }
    return out;
  }

  const equipUi = { query: '', kind: 'melee', slot: '', mod: '', onlyChanged: false };

  const equipmentTab = {
    id: 'equipment',
    title: '장비',
    validate: validateEquipment,
    render(container) {
      let rowChecks = makeChecks();
      const items = S.bundle.equipment.items;
      const catalog = (S.catalog.gear || []).slice();
      const known = new Set(catalog.map((g) => g.id));
      for (const id of Object.keys(items)) if (!known.has(id)) catalog.push({ id, name: '', mod: '', kind: '', slot: '', missing: true });
      catalog.sort((a, b) => (a.mod || '').localeCompare(b.mod || '') || (a.name || a.id).localeCompare(b.name || b.id));
      const round = (v) => Math.round(v * 1000) / 1000;
      const shown = (v, mul, unit) => (isNum(v) ? String(round(v * mul)) + unit : '없음');
      const relevant = (g, group) => g.missing || (group === 'melee' ? g.kind === 'melee' : g.kind === 'armor' || g.kind === 'shield');

      const tbody = h('tbody');
      const count = h('span', { class: 'muted' });
      const draw = () => {
        rowChecks = makeChecks();
        const q = equipUi.query.trim().toLowerCase();
        const list = catalog.filter((g) => (!equipUi.onlyChanged || items[g.id])
          && (!equipUi.kind || g.missing || g.kind === equipUi.kind)
          && (!equipUi.slot || g.missing || g.slot === equipUi.slot)
          && (!equipUi.mod || g.mod === equipUi.mod)
          && (!q || ((g.name || '') + ' ' + g.id + ' ' + (g.mod || '')).toLowerCase().includes(q)));
        count.textContent = `${list.length}개`;
        tbody.replaceChildren(...list.map((g) => {
          const cells = EQUIP_STATS.map(([key, label, , , mul, unit, group]) => {
            const o = items[g.id];
            const cur = o ? o[key] : undefined;
            if (!relevant(g, group) && cur === undefined) return h('td', null, h('span', { class: 'muted' }, '—'));
            const base = g[key];
            const inp = rowChecks.add(h('input', { type: 'number', step: 'any', style: 'width:5.5rem', 'aria-label': `${g.name || g.id} ${label}`,
              placeholder: shown(base, mul, ''), value: isNum(cur) ? String(round(cur * mul)) : shownNum(cur),
              oninput: (e) => {
                const raw = e.target.value.trim();
                const row = items[g.id] || (items[g.id] = {});
                if (raw === '') delete row[key]; else row[key] = Number(raw) / mul;
                if (!Object.keys(row).length) delete items[g.id];
                changed();
              } }), () => equipRule(key, items[g.id] ? items[g.id][key] : undefined));
            return h('td', null, inp, unit ? h('span', { class: 'muted' }, unit) : null, h('div', { class: 'id' }, `원래 ${shown(base, mul, unit)}`));
          });
          const kindText = g.missing ? '' : (EQUIP_KIND_LABEL[g.kind] || g.kind) + (g.kind === 'armor' ? ' · ' + (EQUIP_SLOT_LABEL[g.slot] || g.slot) : '');
          return h('tr', null,
            h('td', null, h('div', null, g.missing ? '(목록에 없음)' : (g.name || g.id)), h('div', { class: 'id' }, g.id + (g.mod ? ' · ' + g.mod : ''))),
            h('td', null, h('span', { class: 'muted' }, kindText)),
            ...cells,
            h('td', null, items[g.id] ? h('button', { class: 'small', 'aria-label': `${g.id} 원래대로`, onclick: () => {
              delete items[g.id]; changed(); draw();
            } }, '원래대로') : null));
        }));
        rowChecks.paint();
      };

      const kindSel = h('select', { 'aria-label': '분류', onchange: (e) => { equipUi.kind = e.target.value; slotSel.disabled = equipUi.kind !== 'armor'; draw(); } },
        h('option', { value: '' }, '전체'), ...Object.entries(EQUIP_KIND_LABEL).map(([k, t]) => h('option', { value: k }, t)));
      kindSel.value = equipUi.kind;
      const slots = Array.from(new Set((S.catalog.gear || []).filter((g) => g.kind === 'armor').map((g) => g.slot))).sort();
      const slotSel = h('select', { 'aria-label': '부위', onchange: (e) => { equipUi.slot = e.target.value; draw(); } },
        h('option', { value: '' }, '모든 부위'), ...slots.map((s) => h('option', { value: s }, EQUIP_SLOT_LABEL[s] || s)));
      slotSel.value = slots.includes(equipUi.slot) ? equipUi.slot : '';
      equipUi.slot = slotSel.value;
      slotSel.disabled = equipUi.kind !== 'armor';
      const mods = Array.from(new Set((S.catalog.gear || []).map((g) => g.mod).filter(Boolean))).sort();
      const modSel = h('select', { 'aria-label': '모드', onchange: (e) => { equipUi.mod = e.target.value; draw(); } },
        h('option', { value: '' }, '모든 모드'), ...mods.map((m) => h('option', { value: m }, m)));
      modSel.value = mods.includes(equipUi.mod) ? equipUi.mod : '';
      equipUi.mod = modSel.value;
      const search = h('input', { type: 'search', placeholder: '이름·id·모드 검색', 'aria-label': '장비 검색', value: equipUi.query, style: 'width:16rem',
        oninput: (e) => { equipUi.query = e.target.value; draw(); } });
      const onlyChanged = h('label', { class: 'chk' }, h('input', { type: 'checkbox', checked: equipUi.onlyChanged,
        onchange: (e) => { equipUi.onlyChanged = e.target.checked; draw(); } }), ' 바꾼 것만');

      container.append(h('div', { class: 'card' }, h('h2', null, '장비 수치'),
        h('p', { class: 'muted' }, '칸에 숫자를 넣으면 그 아이템의 값을 바꿉니다 (빈 칸 = 원래 값). 숫자는 아이템 설명(툴팁)에 보이는 최종값 그대로입니다 — 예: 다이아몬드 검 공격력 7, 공격 속도 1.6. 밀치기 저항도 툴팁처럼 % 로 적습니다.'),
        h('p', { class: 'muted' }, '모든 모드의 아이템에 똑같이 적용되고, 강화(+n) 보너스는 바꾼 값 위에 그대로 더해집니다. 저장하면 접속 중인 사람의 장착 장비에도 바로 적용됩니다.'),
        S.catalogOk && !(S.catalog.gear || []).length ? h('p', { class: 'muted' }, '장비 목록을 불러오지 못했습니다. 바꾼 항목만 보입니다.') : null,
        h('div', { class: 'field' }, search, kindSel, slotSel, modSel, onlyChanged, count),
        h('div', { class: 'scroll-x' }, h('table', null,
          h('thead', null, h('tr', null, ['아이템', '분류'].concat(EQUIP_STATS.map((s) => s[1]), ['']).map((t) => h('th', null, t)))), tbody))));
      draw();
      return () => rowChecks.paint();
    },
  };
  registerTab(equipmentTab);

  /* ----- 업그레이드 탭 (upgrade.json: 대장장이 '업그레이드' 단계) ----- */

  // 서버 UpgradeConfig.errors() 와 같은 규칙: 앞에서부터 보며, 앞 단계와 겹치거나 앞 단계들과 순환을 만드는 단계가 오류.
  function upgradeRowErrors(steps) {
    const out = steps.map(() => []);
    const next = new Map();
    const reaches = (start, target) => {
      const seen = new Set();
      for (let cur = start; cur !== undefined && !seen.has(cur); cur = next.get(cur)) {
        if (cur === target) return true;
        seen.add(cur);
      }
      return false;
    };
    steps.forEach((s, i) => {
      const e = out[i];
      for (const [key, label] of [['from', '현재 장비'], ['to', '다음 장비'], ['material', '재료']]) {
        if (!ITEM_ID.test(s[key] || '')) e.push(`${label}를 고르세요`);
      }
      if (e.length) return;
      if (s.from === s.to) { e.push('현재 장비와 다음 장비가 같습니다'); return; }
      if (next.has(s.from)) { e.push('같은 장비에서 시작하는 단계가 이미 있습니다'); return; }
      if (reaches(s.to, s.from)) { e.push('단계를 따라가면 다시 이 장비로 돌아옵니다 (순환)'); return; }
      if (!Number.isInteger(s.count) || s.count < 1 || s.count > 2304) e.push('재료 개수는 1~2304 정수여야 합니다');
      if (!Number.isInteger(s.gold) || s.gold < 0) e.push('골드는 0 이상의 정수여야 합니다');
      next.set(s.from, s.to);
    });
    return out;
  }

  function backpackTierErrors(t) {
    const e = [];
    if (!ITEM_ID.test(t.material || '')) e.push('재료를 고르세요');
    if (!Number.isInteger(t.count) || t.count < 1 || t.count > 2304) e.push('재료 개수는 1~2304 정수여야 합니다');
    if (!Number.isInteger(t.gold) || t.gold < 0) e.push('골드는 0 이상의 정수여야 합니다');
    return e;
  }

  function validateUpgrade(bundle) {
    const steps = (bundle.upgrade && bundle.upgrade.steps) || [];
    const out = [];
    upgradeRowErrors(steps).forEach((errs, i) => {
      for (const m of errs) out.push(`업그레이드 ${i + 1}번 (${steps[i].from ? itemName(steps[i].from) : '빈 단계'}): ${m}`);
    });
    ((bundle.upgrade && bundle.upgrade.backpackTiers) || []).forEach((t, i) => {
      for (const m of backpackTierErrors(t)) out.push(`배낭 등급 ${BACKPACK_TIER_LABELS[i]}: ${m}`);
    });
    return out;
  }

  const upgradeUi = { query: '' };

  const upgradeTab = {
    id: 'upgrade',
    title: '업그레이드',
    validate: validateUpgrade,
    render(container) {
      let rowChecks = makeChecks();
      const steps = S.bundle.upgrade.steps;
      const tbody = h('tbody');
      const count = h('span', { class: 'muted' });
      const rowError = (i) => { const e = upgradeRowErrors(steps)[i]; return e ? e.join(' · ') : ''; };
      // 서버·클라이언트 목록에 없는 아이템: 저장은 되지만(모드가 다시 들어오면 쓰임) 지금은 그 단계가 동작하지 않는다.
      const unknownNote = (id) => {
        const st = itemStatus(id);
        return st === 'ok' ? null : h('div', { class: 'id', style: 'color:var(--warn)' },
          st === 'missing' ? '서버에 없는 아이템 (이 단계는 동작하지 않음)' : '아이템 목록에 없음');
      };
      const itemBtn = (s, key, label) => h('div', null,
        h('button', { class: 'icon-btn', title: `${label} 바꾸기`, 'aria-label': `${label} 바꾸기`, onclick: async () => {
          const id = await pickItem({ title: label });
          if (!id) return;
          s[key] = id; changed(); draw();
        } }, iconEl(s[key], 28), h('span', null, s[key] ? itemName(s[key]) : '— 선택 —')),
        h('div', { class: 'id' }, s[key] || ''), unknownNote(s[key]));
      const intIn = (s, key, label, min) => h('input', { type: 'number', min, step: 1, style: 'width:5rem', 'aria-label': label,
        value: Number.isNaN(s[key]) ? '' : s[key],
        oninput: (e) => { s[key] = e.target.value.trim() === '' ? NaN : Number(e.target.value); changed(); } });

      const draw = () => {
        rowChecks = makeChecks();
        const q = upgradeUi.query.trim().toLowerCase();
        const text = (id) => (id ? (itemName(id) + ' ' + id).toLowerCase() : '');
        const shown = [];
        steps.forEach((s, i) => {
          if (q && !(text(s.from) + ' ' + text(s.to) + ' ' + text(s.material)).includes(q)) return;
          shown.push(i);
        });
        count.textContent = `${shown.length} / ${steps.length}단계`;
        tbody.replaceChildren(...shown.map((i) => {
          const s = steps[i];
          const status = rowChecks.add(h('span', { class: 'muted' }, String(i + 1)), () => rowError(i));
          const err = h('div', { class: 'id', style: 'color:var(--bad)' });
          rowChecks.add(err, () => { err.textContent = rowError(i); return ''; });
          return h('tr', null,
            h('td', null, status),
            h('td', null, itemBtn(s, 'from', '현재 장비'), err),
            h('td', null, h('span', { class: 'muted' }, '→')),
            h('td', null, itemBtn(s, 'to', '다음 장비')),
            h('td', null, itemBtn(s, 'material', '재료')),
            h('td', null, rowChecks.add(intIn(s, 'count', '재료 개수', 1), () => (Number.isInteger(s.count) && s.count >= 1 && s.count <= 2304 ? '' : '1~2304 정수'))),
            h('td', null, rowChecks.add(intIn(s, 'gold', '골드', 0), () => (Number.isInteger(s.gold) && s.gold >= 0 ? '' : '0 이상의 정수'))),
            h('td', null, h('button', { class: 'danger small', 'aria-label': '단계 삭제', onclick: () => {
              steps.splice(i, 1); changed(); draw();
            } }, '삭제')));
        }));
        rowChecks.paint();
      };

      const search = h('input', { type: 'search', placeholder: '이름·id 검색 (현재·다음 장비, 재료)', 'aria-label': '업그레이드 검색',
        value: upgradeUi.query, style: 'width:18rem', oninput: (e) => { upgradeUi.query = e.target.value; draw(); } });
      const add = h('button', { onclick: async () => {
        const from = await pickItem({ title: '업그레이드할 장비 (현재 등급)' });
        if (!from) return;
        const to = await pickItem({ title: '업그레이드 결과 장비 (다음 등급)' });
        if (!to) return;
        const material = await pickItem({ title: '재료 아이템' });
        if (!material) return;
        steps.push({ from, to, material, count: 1, gold: 0 });
        upgradeUi.query = '';
        search.value = '';
        changed(); draw();
      } }, '단계 추가');

      container.append(h('div', { class: 'card' }, h('h2', null, '장비 업그레이드'),
        h('p', { class: 'muted' }, '대장장이의 \'업그레이드\' 화면에서 현재 장비를 재료(와 골드)로 다음 장비로 바꿉니다. 한 장비에서 시작하는 단계는 하나뿐이고, 마지막 등급(다음 단계가 없는 장비)은 \'최대 등급\'으로 보입니다.'),
        h('p', { class: 'muted' }, '강화 단계·인챈트·이름 등은 그대로 이어지고, 공격력·내구도 같은 수치는 다음 장비의 것이 됩니다. 재료는 인벤토리에서 자동으로 셉니다.'),
        h('div', { class: 'field' }, search, add, count),
        h('div', { class: 'scroll-x' }, h('table', null,
          h('thead', null, h('tr', null, ['#', '현재 장비', '', '다음 장비', '재료', '개수', '골드', ''].map((t) => h('th', null, t)))), tbody))));

      // 배낭 등급: 배낭은 등급이 올라도 아이템이 그대로라 위 표에 넣을 수 없어 따로 둔다.
      const tierChecks = makeChecks();
      const tiers = S.bundle.upgrade.backpackTiers;
      const tierBody = h('tbody');
      const drawTiers = () => {
        tierBody.replaceChildren(...tiers.map((t, i) => h('tr', null,
          h('td', null, BACKPACK_TIER_LABELS[i]),
          h('td', null, h('button', { class: 'icon-btn', title: '재료 바꾸기', 'aria-label': `${BACKPACK_TIER_LABELS[i]} 재료 바꾸기`, onclick: async () => {
            const id = await pickItem({ title: `재료 (${BACKPACK_TIER_LABELS[i]})` });
            if (!id) return;
            t.material = id; changed(); drawTiers();
          } }, iconEl(t.material, 28), h('span', null, t.material ? itemName(t.material) : '— 선택 —')),
            h('div', { class: 'id' }, t.material || ''), unknownNote(t.material)),
          h('td', null, tierChecks.add(intIn(t, 'count', '재료 개수', 1), () => (Number.isInteger(t.count) && t.count >= 1 && t.count <= 2304 ? '' : '1~2304 정수'))),
          h('td', null, tierChecks.add(intIn(t, 'gold', '골드', 0), () => (Number.isInteger(t.gold) && t.gold >= 0 ? '' : '0 이상의 정수'))))));
        tierChecks.paint();
      };
      container.append(h('div', { class: 'card' }, h('h2', null, '배낭 등급'),
        h('p', { class: 'muted' }, '여행자 배낭(모든 종류)을 대장장이의 \'업그레이드\' 화면에 올리면 한 등급씩 올립니다. 안에 든 물건과 장착한 업그레이드는 그대로입니다.'),
        h('div', { class: 'scroll-x' }, h('table', null,
          h('thead', null, h('tr', null, ['등급', '재료', '개수', '골드'].map((t) => h('th', null, t)))), tierBody))));
      drawTiers();
      draw();
      return () => { rowChecks.paint(); tierChecks.paint(); };
    },
  };
  registerTab(upgradeTab);

  /* ----- 총 탭 · 등장 탭 (서버 월드의 데이터팩 gett_balance 에 모드 원본 파일의 사본을 씀) ----- */

  const round3 = (v) => Math.round(v * 1000) / 1000;
  const dpShown = (v, unit) => (isNum(v) ? String(round3(v)) + (unit || '') : '—');
  const dpMissing = () => h('p', { class: 'banner warn' }, '서버에서 총·등장·Epic Fight·제작법·낚시 목록을 받지 못했습니다. 이 탭의 내용은 저장해도 바뀌지 않습니다 (서버 로그 확인).');

  /**
   * 원본이 사라진 사본(모드를 지웠거나 업데이트로 파일이 없어짐) — 서버 datapackCatalog.orphans = [{kind, id}].
   * "삭제" 를 누르면 datapack.removeOrphans 에 넣고, 저장할 때 서버가 그 사본 파일을 지운다 (그때도 원본이 없는 것만).
   */
  const DP_ORPHAN_KIND = { guns: '총', epicfight: 'Epic Fight', spawnRules: '등장 규칙', spawnMobs: '몹 등장 배율', recipes: '제작법', fishing: '낚시' };
  function dpOrphanCard(container, kinds) {
    const cat = S.dpCatalog;
    const dp = S.bundle.datapack;
    if (!cat || !dp || !Array.isArray(cat.orphans)) return;
    const list = cat.orphans.filter((o) => o && kinds.includes(o.kind));
    if (!list.length) return;
    const same = (a, b) => a.kind === b.kind && a.id === b.id;
    const tbody = h('tbody');
    const draw = () => {
      tbody.replaceChildren(...list.map((o) => {
        const marked = dp.removeOrphans.some((r) => same(r, o));
        return h('tr', null,
          h('td', null, DP_ORPHAN_KIND[o.kind] || o.kind),
          h('td', null, h('span', { class: 'id' }, o.id)),
          h('td', null, marked ? h('span', { class: 'muted' }, '저장하면 삭제') : h('span', { class: 'muted' }, '원본 없음')),
          h('td', null, h('button', { class: 'small', 'aria-label': `${o.id} ${marked ? '삭제 취소' : '삭제'}`, onclick: () => {
            if (marked) {
              dp.removeOrphans = dp.removeOrphans.filter((r) => !same(r, o));
              if (o.kind === 'spawnMobs' && o.prev !== undefined) { dp.spawnMobs[o.id] = o.prev; delete o.prev; }
            } else {
              dp.removeOrphans.push({ kind: o.kind, id: o.id });
              // 몹 배율은 spawnMobs 에 남아 있으면 저장 때 다시 쓰이므로 함께 뺀다.
              if (o.kind === 'spawnMobs' && dp.spawnMobs[o.id] !== undefined) { o.prev = dp.spawnMobs[o.id]; delete dp.spawnMobs[o.id]; }
            }
            changed();
            draw();
          } }, marked ? '삭제 취소' : '원본 없음 · 삭제')));
      }));
    };
    container.append(h('div', { class: 'card' }, h('h2', null, '원본 없는 사본'),
      h('p', { class: 'muted' }, '데이터팩 gett_balance 에 있지만 서버에 원본 파일이 없는 사본입니다 (모드를 지웠거나 업데이트로 파일이 바뀜). 효과가 없으니 지워도 됩니다. "삭제" 를 누르고 저장하면 파일이 지워집니다.'),
      h('div', { class: 'scroll-x' }, h('table', null,
        h('thead', null, h('tr', null, ['종류', 'id', '상태', ''].map((t) => h('th', null, t)))), tbody))));
    draw();
  }

  // [키, 제목, 최소, 최대, 정수?, 단위] — 서버 GunBalance.FIELDS 와 같은 범위. 시간은 초(20틱 = 1초, 가장 가까운 틱으로 반올림).
  const GUN_FIELDS = [
    ['reloadTime', '장전 시간', 0, 600, false, '초'],
    ['reloadStart', '장전 시작', 0, 600, false, '초'],
    ['reloadEnd', '장전 끝', 0, 600, false, '초'],
    ['rate', '연사 간격', 0.05, 60, false, '초'],
    ['maxAmmo', '탄창', 1, 100000, true, '발'],
    ['spread', '퍼짐', 0, 180, false, '°'],
    ['recoilAngle', '반동', -180, 180, false, '°'],
    ['equipTime', '꺼내는 시간', 0, 60, false, '초'],
  ];
  const GUN_DAMAGE_MAX = 100000;
  // 장전 칸에 따로 그리는 항목 (나머지 GUN_FIELDS 는 한 칸씩).
  const GUN_RELOAD_KEYS = ['reloadStart', 'reloadTime', 'reloadEnd'];
  // NTGL LoadingType: 탄창식 = reloadTime 한 번에 전부, 한 발씩 = 장전 시작 → (reloadTime 마다 reloadAmount 발) → 장전 끝.
  const gunPerCartridge = (g) => g.loading === 'per_cartridge';
  function gunFieldLabel(g, key) {
    if (key === 'reloadTime') return gunPerCartridge(g) ? '한 발당 장전' : '장전 시간';
    const f = GUN_FIELDS.find((x) => x[0] === key);
    return f ? f[1] : key;
  }

  function gunRule(key, v) {
    if (v === undefined) return '';
    const f = GUN_FIELDS.find((x) => x[0] === key);
    if (!f) return '모르는 항목입니다';
    if (!isNum(v)) return '숫자여야 합니다 (비우면 원래 값)';
    if (f[4] && !Number.isInteger(v)) return '정수여야 합니다';
    return v >= f[2] && v <= f[3] ? '' : `${f[2]}~${f[3]} 사이여야 합니다`;
  }
  function gunDamageRule(v) {
    if (v === undefined) return '';
    return isNum(v) && v >= 0 && v <= GUN_DAMAGE_MAX ? '' : `0~${GUN_DAMAGE_MAX} 사이 숫자여야 합니다`;
  }

  function validateGuns(bundle) {
    const out = [];
    const guns = (bundle.datapack && bundle.datapack.guns) || {};
    for (const [id, o] of Object.entries(guns)) {
      for (const [key, label] of GUN_FIELDS) {
        const m = gunRule(key, o[key]);
        if (m) out.push(`총 ${id} › ${label}: ${m}`);
      }
      for (const [ammo, v] of Object.entries(o.damage || {})) {
        const m = gunDamageRule(v);
        if (m) out.push(`총 ${id} › ${ammo} 피해: ${m}`);
      }
    }
    return out;
  }

  const gunsUi = { query: '', mod: '', onlyChanged: false };

  const gunsTab = {
    id: 'guns',
    title: '총',
    validate: validateGuns,
    render(container) {
      const card = h('div', { class: 'card' }, h('h2', null, '총 수치'),
        h('p', { class: 'muted' }, 'Create: Gunsmithing·NTGL 총의 무기 파일(data/<모드>/weapons/…) 값을 바꿉니다. 칸에 숫자를 넣으면 바뀌고, 빈 칸은 모드의 원래 값입니다. 시간은 초 단위입니다 (1초 = 20틱, 가장 가까운 틱으로 반올림 — 예: 장전 2.5초 = 50틱).'),
        h('p', { class: 'muted' }, '저장하면 서버 월드의 데이터팩 gett_balance 에 그 총 파일의 사본(모드 원본 그대로 + 바꾼 값만)을 쓰고 /reload 를 자동으로 실행합니다. /reload 가 끝나면 NTGL 이 접속자 모두에게 총 설정을 다시 보내고 매 틱 그 값을 읽으므로, 서버 판정과 장전 애니메이션이 함께 바로 바뀝니다 — 손에 든 총을 다시 들거나 재접속할 필요는 없습니다 (그때 진행 중이던 장전 한 단계만 예전 시간으로 끝남). "원래대로" = 사본 삭제.'),
        h('p', { class: 'muted' }, '장전 방식은 총 파일의 loadingType 으로 정해집니다 (NTGL 과 같은 규칙, 없으면 탄창식). 탄창식은 "장전 시간" 한 번에 탄창 전부를 채웁니다. 한 발씩 장전하는 총은 "장전 시작" → "한 발당 장전" × 채울 발 수 → "장전 끝" 순서이므로, 전체 장전 시간 = 시작 + 한 발당 × 발 수 + 끝 입니다. 장전 시작·끝이 파일에 없으면 NTGL 기본값 0초로 보이고, 바꾸면 사본에 새로 넣습니다.'),
        h('p', { class: 'muted' }, '일부 부착물은 모드 코드가 장전 방식·시간·탄창을 고정 값으로 바꿔 버려 여기 값이 무시됩니다 (총 이름 아래 ⚠ 참고 — 예: 리볼버에 탄띠를 달면 한 발씩·한 발당 1초·12발). 원본 파일에 없는 다른 항목(예: 꺼내는 시간이 없는 총)은 바꿀 수 없어 "—" 로 보입니다. 탄별 피해는 그 총에 넣는 탄(ammoData) 하나하나의 발사체 피해입니다.'));
      container.append(card);
      const cat = S.dpCatalog;
      const dp = S.bundle.datapack;
      if (!cat || !dp) { card.append(dpMissing()); return null; }
      let rowChecks = makeChecks();
      const guns = dp.guns;
      const catalog = (cat.guns || []).slice();
      const known = new Set(catalog.map((g) => g.id));
      for (const id of Object.keys(guns)) if (!known.has(id)) catalog.push({ id, mod: '', item: '', ammo: [], missing: true });

      const cleanup = (id) => {
        const row = guns[id];
        if (row && row.damage && !Object.keys(row.damage).length) delete row.damage;
        if (row && !Object.keys(row).length) delete guns[id];
      };
      const tbody = h('tbody');
      const count = h('span', { class: 'muted' });
      const draw = () => {
        rowChecks = makeChecks();
        const q = gunsUi.query.trim().toLowerCase();
        const list = catalog.filter((g) => (!gunsUi.onlyChanged || guns[g.id])
          && (!gunsUi.mod || g.mod === gunsUi.mod)
          && (!q || (itemName(g.item) + ' ' + g.id + ' ' + g.item).toLowerCase().includes(q)));
        count.textContent = `${list.length}개`;
        tbody.replaceChildren(...list.map((g) => {
          // 한 항목의 입력 칸 (+ 원래 값). 원본에 없고 기본값도 없으면 null.
          const field = (key) => {
            const f = GUN_FIELDS.find((x) => x[0] === key);
            const unit = f[5];
            const label = gunFieldLabel(g, key);
            const base = g[key];
            const o = guns[g.id];
            const cur = o ? o[key] : undefined;
            if (!isNum(base) && cur === undefined) return null;
            const inp = rowChecks.add(h('input', { type: 'number', step: 'any', style: 'width:5rem', 'aria-label': `${itemName(g.item)} ${label}`,
              placeholder: dpShown(base), value: shownNum(cur),
              oninput: (e) => {
                const raw = e.target.value.trim();
                const row = guns[g.id] || (guns[g.id] = {});
                const v = Number(raw);
                if (raw === '' || (isNum(base) && Math.abs(v - base) < 1e-9)) delete row[key]; else row[key] = v;
                cleanup(g.id);
                changed();
              } }), () => gunRule(key, guns[g.id] ? guns[g.id][key] : undefined));
            const dflt = (g.defaulted || []).includes(key) ? ' (파일에 없음 = 기본값)' : '';
            return [inp, h('span', { class: 'muted' }, unit), h('div', { class: 'id' }, `원래 ${dpShown(base, unit)}${dflt}`)];
          };
          const dash = () => h('span', { class: 'muted' }, '—');
          const per = gunPerCartridge(g);
          // 장전 칸: 탄창식은 장전 시간만, 한 발씩은 시작·한 발당·끝 (탄창식인데 시작·끝을 이미 바꿔 둔 경우엔 지울 수 있게 보임).
          const reloadKeys = GUN_RELOAD_KEYS.filter((k) => per || k === 'reloadTime' || (guns[g.id] && guns[g.id][k] !== undefined));
          const reloadCell = h('td', null,
            g.missing ? null : h('div', { class: 'id', style: 'margin-bottom:.2rem' }, per ? '한 발씩 장전' : '탄창식 (한 번에 전부)'),
            ...reloadKeys.map((k) => {
              const parts = field(k);
              return h('div', { style: 'margin-bottom:.25rem' }, h('div', { class: 'muted' }, gunFieldLabel(g, k)), ...(parts || [dash()]));
            }));
          const cells = GUN_FIELDS.filter(([key]) => !GUN_RELOAD_KEYS.includes(key)).map(([key]) => h('td', null, ...(field(key) || [dash()])));
          const ammoCell = h('td', null, ...(g.ammo || []).map((a) => {
            const o = guns[g.id];
            const cur = o && o.damage ? o.damage[a.id] : undefined;
            const inp = rowChecks.add(h('input', { type: 'number', step: 'any', style: 'width:4.5rem', 'aria-label': `${itemName(g.item)} ${itemName(a.id)} 피해`,
              placeholder: dpShown(a.damage), value: shownNum(cur),
              oninput: (e) => {
                const raw = e.target.value.trim();
                const row = guns[g.id] || (guns[g.id] = {});
                const dmg = row.damage || (row.damage = {});
                const v = Number(raw);
                if (raw === '' || Math.abs(v - a.damage) < 1e-9) delete dmg[a.id]; else dmg[a.id] = v;
                cleanup(g.id);
                changed();
              } }), () => gunDamageRule(guns[g.id] && guns[g.id].damage ? guns[g.id].damage[a.id] : undefined));
            return h('div', { class: 'row', style: 'gap:.35rem;align-items:center;margin-bottom:.2rem' },
              iconEl(a.id, 20), inp, h('span', { class: 'id', title: a.id }, `${itemName(a.id)} · 원래 ${dpShown(a.damage)}`));
          }));
          return h('tr', null,
            h('td', null, h('div', { class: 'row', style: 'gap:.4rem;align-items:center' }, g.missing ? null : iconEl(g.item, 24),
              h('div', null, h('div', null, g.missing ? '(서버에 없음 — 저장하면 무시)' : itemName(g.item)), h('div', { class: 'id' }, g.id),
                ...(g.notes || []).map((n) => h('div', { class: 'id', style: 'max-width:22rem;white-space:normal' }, '⚠ ' + n))))),
            reloadCell, ...cells, ammoCell,
            h('td', null, guns[g.id] ? h('button', { class: 'small', 'aria-label': `${g.id} 원래대로`, onclick: () => {
              delete guns[g.id]; changed(); draw();
            } }, '원래대로') : null));
        }));
        rowChecks.paint();
      };

      const mods = Array.from(new Set((cat.guns || []).map((g) => g.mod).filter(Boolean))).sort();
      const modSel = h('select', { 'aria-label': '모드', onchange: (e) => { gunsUi.mod = e.target.value; draw(); } },
        h('option', { value: '' }, '모든 모드'), ...mods.map((m) => h('option', { value: m }, m)));
      modSel.value = mods.includes(gunsUi.mod) ? gunsUi.mod : '';
      gunsUi.mod = modSel.value;
      const search = h('input', { type: 'search', placeholder: '이름·id 검색', 'aria-label': '총 검색', value: gunsUi.query, style: 'width:16rem',
        oninput: (e) => { gunsUi.query = e.target.value; draw(); } });
      const onlyChanged = h('label', { class: 'chk' }, h('input', { type: 'checkbox', checked: gunsUi.onlyChanged,
        onchange: (e) => { gunsUi.onlyChanged = e.target.checked; draw(); } }), ' 바꾼 것만');
      card.append(
        !(cat.guns || []).length ? h('p', { class: 'muted' }, '서버에 총 무기 파일이 없습니다 (NTGL·Create: Gunsmithing 이 설치돼 있는지 확인).') : null,
        h('div', { class: 'field' }, search, modSel, onlyChanged, count),
        h('div', { class: 'scroll-x' }, h('table', null,
          h('thead', null, h('tr', null, ['총', '장전'].concat(GUN_FIELDS.filter((f) => !GUN_RELOAD_KEYS.includes(f[0])).map((f) => f[1]),
            ['탄별 피해', '']).map((t) => h('th', null, t)))), tbody)));
      dpOrphanCard(container, ['guns']);
      draw();
      return () => rowChecks.paint();
    },
  };
  registerTab(gunsTab);

  /* ───────────── 낚시 (Tide) ─────────────
   * datapack.fishing = { fish: {id: 가중치}, loot: {id: 가중치}, crates: {id: 가중치},
   *   tables: {전리품 표 id: {weights: {원본 순번: 가중치(0 = 뺌)}, added: [{item, weight, min, max}]}} }
   * 원래 값은 datapackCatalog.fishing (서버 FishingBalance.catalog). 범위는 서버 FishingBalance 와 같다.
   */
  const FISH_WEIGHT_MAX = 10000;
  const FISH_COUNT_MAX = 64;
  const FISH_GROUPS = { freshwater: '민물', saltwater: '바다', underground: '지하', lava: '용암', void: '공허' };
  const FISH_RARITY = { common: '흔함', uncommon: '드묾', rare: '희귀', very_rare: '매우 희귀', legendary: '전설' };
  const FISH_RARITY_ORDER = ['common', 'uncommon', 'rare', 'very_rare', 'legendary'];
  const FISH_LOOT_NAMES = {
    surface_junk: '지상 쓰레기', surface_treasure: '지상 보물', underground_junk: '지하 쓰레기', underground_treasure: '지하 보물',
    lava_junk: '용암 쓰레기', nether_treasure: '네더 보물', desert_well_treasure: '사막 우물 보물', ocean_monument_treasure: '해저 유적 보물',
    trial_chamber_treasure: '시련의 방 보물', sunflower_rod: '해바라기 낚싯대 보너스',
  };
  const FISH_CRATE_NAMES = {
    surface_freshwater: '지상 민물 상자', surface_saltwater: '지상 바다 상자', surface_lava: '지상 용암 상자', underground: '지하 상자',
    underground_lava: '지하 용암 상자', deep: '깊은 곳 상자', deep_lava: '깊은 곳 용암 상자', nether: '네더 상자', end: '엔드 상자',
  };
  const fishTail = (id) => id.slice(id.lastIndexOf('/') + 1);
  const fishPct = (ratio) => Math.round(ratio * 1000) / 10;

  const fishWeightRule = (v) => (v === undefined || (isNum(v) && v >= 0 && v <= FISH_WEIGHT_MAX) ? '' : `0~${FISH_WEIGHT_MAX} 사이 숫자여야 합니다 (비우면 원래 값)`);
  const fishEntryRule = (v) => (v === undefined || (isInt(v) && v >= 0 && v <= FISH_WEIGHT_MAX) ? '' : `0~${FISH_WEIGHT_MAX} 사이 정수여야 합니다 (비우면 원래 값)`);
  const fishAddedWeightRule = (v) => (isInt(v) && v >= 1 && v <= FISH_WEIGHT_MAX ? '' : `1~${FISH_WEIGHT_MAX} 사이 정수여야 합니다`);
  function fishAddedCountRule(a) {
    const ok = (n) => isInt(n) && n >= 1 && n <= FISH_COUNT_MAX;
    if (!ok(a.min) || !ok(a.max)) return `개수는 1~${FISH_COUNT_MAX} 사이 정수여야 합니다`;
    return a.min <= a.max ? '' : '최소 개수가 최대보다 큽니다';
  }
  /** 표에 남는 항목 수 (원본에서 0 으로 빼지 않은 것 + 추가한 것). catalogTable 이 없으면 알 수 없으므로 1. */
  function fishTableLeft(catalogTable, edits) {
    if (!catalogTable) return 1;
    const kept = (catalogTable.entries || []).filter((en) => (edits.weights || {})[en.key] !== 0).length;
    return kept + (edits.added || []).length;
  }

  function validateFishing(bundle) {
    const out = [];
    const f = bundle.datapack && bundle.datapack.fishing;
    if (!f) return out;
    for (const [key, label] of [['fish', '물고기'], ['loot', '묶음'], ['crates', '상자']]) {
      for (const [id, v] of Object.entries(f[key] || {})) {
        const m = fishWeightRule(v);
        if (m) out.push(`낚시 › ${label} ${id}: ${m}`);
      }
    }
    const tables = (S.dpCatalog && S.dpCatalog.fishing && S.dpCatalog.fishing.tables) || [];
    for (const [id, t] of Object.entries(f.tables || {})) {
      for (const [k, v] of Object.entries(t.weights || {})) {
        const m = fishEntryRule(v);
        if (m) out.push(`낚시 › 내용물 ${id} › ${Number(k) + 1}번째: ${m}`);
      }
      (t.added || []).forEach((a, i) => {
        const m = fishAddedWeightRule(a.weight) || fishAddedCountRule(a);
        if (m) out.push(`낚시 › 내용물 ${id} › 추가한 아이템 ${i + 1}번: ${m}`);
      });
      if (fishTableLeft(tables.find((x) => x.id === id), t) < 1) {
        out.push(`낚시 › 내용물 ${id}: 남는 아이템이 없습니다 — 아예 안 나오게 하려면 묶음 가중치를 0 으로 하세요`);
      }
    }
    return out;
  }

  const fishingUi = { view: 'fish', query: '', group: '', rarity: '', onlyChanged: false };

  const fishingTab = {
    id: 'fishing',
    title: '낚시',
    validate: validateFishing,
    render(container) {
      const card = h('div', { class: 'card' }, h('h2', null, '낚시 확률'),
        h('p', { class: 'muted' }, 'Tide 모드의 낚시 가중치를 바꿉니다. 찌를 건질 때마다 먼저 "물고기(고정 85) · 쓰레기 묶음 · 보물 묶음 · 상자" 중 하나가 가중치 비율로 뽑히고, 물고기가 뽑히면 그 자리(바이옴·깊이·시간 등 조건)에 맞는 물고기끼리 다시 가중치 비율로 뽑힙니다. 숫자가 클수록 자주 나오고 0 이면 아예 안 나옵니다. 빈 칸은 모드의 원래 값입니다.'),
        h('p', { class: 'muted' }, '저장하면 서버 월드의 데이터팩 gett_balance 에 바꾼 파일의 사본만 쓰고 /reload 를 자동으로 실행합니다. 다음에 던지는 찌부터 적용됩니다. "원래대로" = 사본 삭제. 행운(바다의 행운 등)은 묶음·물고기마다 정해진 만큼 가중치를 더하거나 빼는데(보물 +, 쓰레기 −), 가중치를 0 으로 한 것은 행운이 있어도 안 나옵니다.'));
      container.append(card);
      const cat = S.dpCatalog;
      const dp = S.bundle.datapack;
      if (!cat || !dp) { card.append(dpMissing()); return null; }
      const fc = cat.fishing || { fish: [], loot: [], crates: [], tables: [] };
      if (!(fc.fish || []).length && !(fc.loot || []).length) {
        card.append(h('p', { class: 'muted' }, '서버에 Tide 낚시 데이터가 없습니다 (Tide 모드가 설치돼 있는지 확인).'));
        dpOrphanCard(container, ['fishing']);
        return null;
      }
      const fs = dp.fishing;
      let rowChecks = makeChecks();
      const body = h('div');

      // 한 줄짜리 가중치 칸: section = fs.fish / fs.loot / fs.crates
      const weightInput = (section, row, label, onChange) => rowChecks.add(h('input', { type: 'number', step: 'any', min: 0, style: 'width:5.5rem',
        'aria-label': `${label} 가중치`, placeholder: dpShown(row.weight), value: shownNum(section[row.id]),
        oninput: (e) => {
          const raw = e.target.value.trim();
          const v = Number(raw);
          if (raw === '' || Math.abs(v - row.weight) < 1e-9) delete section[row.id]; else section[row.id] = v;
          changed();
          if (onChange) onChange();
        } }), () => fishWeightRule(section[row.id]));
      const resetBtn = (section, row, redraw) => (section[row.id] !== undefined
        ? h('button', { class: 'small', 'aria-label': `${row.id} 원래대로`, onclick: () => { delete section[row.id]; changed(); redraw(); } }, '원래대로') : null);
      const whereText = (row) => (row.where || []).join(' · ');
      const luckNote = (row) => (row.quality > 0 ? `행운 1당 +${dpShown(row.quality)}` : row.quality < 0 ? `행운 1당 ${dpShown(row.quality)}` : '');

      const drawFish = () => {
        const fish = fc.fish || [];
        const tbody = h('tbody');
        const count = h('span', { class: 'muted' });
        const draw = () => {
          rowChecks = makeChecks();
          const q = fishingUi.query.trim().toLowerCase();
          const list = fish.filter((f) => (!fishingUi.onlyChanged || fs.fish[f.id] !== undefined)
            && (!fishingUi.group || f.group === fishingUi.group)
            && (!fishingUi.rarity || f.rarity === fishingUi.rarity)
            && (!q || (itemName(f.item) + ' ' + f.item + ' ' + whereText(f)).toLowerCase().includes(q)));
          count.textContent = `${list.length}종`;
          tbody.replaceChildren(...list.map((f) => {
            const off = () => fs.fish[f.id] === 0;
            const state = h('span', { class: 'tag gray' }, '안 낚임');
            const paintState = () => { state.style.display = off() ? '' : 'none'; };
            paintState();
            return h('tr', null,
              h('td', null, h('div', { style: 'display:flex;gap:.4rem;align-items:center' }, iconEl(f.item, 24),
                h('div', null, h('div', null, itemName(f.item), ' ', state), h('div', { class: 'id' }, f.item)))),
              h('td', null, FISH_RARITY[f.rarity] || f.rarity),
              h('td', null, h('div', null, FISH_GROUPS[f.group] || f.group || '—'), h('div', { class: 'id', style: 'white-space:normal;max-width:20rem' }, whereText(f))),
              h('td', null, weightInput(fs.fish, f, itemName(f.item), paintState),
                h('div', { class: 'id' }, `원래 ${dpShown(f.weight)}${luckNote(f) ? ' · ' + luckNote(f) : ''}`)),
              h('td', null, resetBtn(fs.fish, f, draw)));
          }));
          rowChecks.paint();
        };
        const groups = Array.from(new Set(fish.map((f) => f.group).filter(Boolean))).sort();
        const groupSel = h('select', { 'aria-label': '사는 곳', onchange: (e) => { fishingUi.group = e.target.value; draw(); } },
          h('option', { value: '' }, '모든 곳'), ...groups.map((g) => h('option', { value: g }, FISH_GROUPS[g] || g)));
        groupSel.value = groups.includes(fishingUi.group) ? fishingUi.group : '';
        fishingUi.group = groupSel.value;
        const rarities = FISH_RARITY_ORDER.filter((r) => fish.some((f) => f.rarity === r));
        const raritySel = h('select', { 'aria-label': '희귀도', onchange: (e) => { fishingUi.rarity = e.target.value; draw(); } },
          h('option', { value: '' }, '모든 희귀도'), ...rarities.map((r) => h('option', { value: r }, FISH_RARITY[r])));
        raritySel.value = rarities.includes(fishingUi.rarity) ? fishingUi.rarity : '';
        fishingUi.rarity = raritySel.value;
        const search = h('input', { type: 'search', placeholder: '이름·id·사는 곳 검색', 'aria-label': '물고기 검색', value: fishingUi.query, style: 'width:16rem',
          oninput: (e) => { fishingUi.query = e.target.value; draw(); } });
        const onlyChanged = h('label', { class: 'chk' }, h('input', { type: 'checkbox', checked: fishingUi.onlyChanged,
          onchange: (e) => { fishingUi.onlyChanged = e.target.checked; draw(); } }), ' 바꾼 것만');
        body.replaceChildren(
          h('p', { class: 'muted' }, '물고기끼리의 비율입니다. 같은 자리에서 낚일 수 있는 물고기들의 가중치를 모두 더한 것 중 그 물고기 몫만큼 나옵니다 — 자리마다 후보가 달라서 확률(%)을 하나로 보여 드릴 수는 없고, 희귀도와 사는 곳을 참고하세요. 가중치 0 인 물고기는 도감에는 남지만 낚이지 않습니다.'),
          h('div', { class: 'field' }, search, groupSel, raritySel, onlyChanged, count),
          h('div', { class: 'scroll-x' }, h('table', null,
            h('thead', null, h('tr', null, ['물고기', '희귀도', '사는 곳', '가중치', ''].map((t) => h('th', null, t)))), tbody)));
        draw();
      };

      const drawLoot = () => {
        rowChecks = makeChecks();
        const fishW = isNum(fc.fishGroupWeight) ? fc.fishGroupWeight : 85;
        const blocks = (fc.loot || []).map((g) => {
          const name = FISH_LOOT_NAMES[fishTail(g.id)] || g.id;
          const table = (fc.tables || []).find((t) => t.id === g.table);
          const share = h('span', { class: 'muted' });
          const paintShare = () => {
            const w = fs.loot[g.id] !== undefined ? fs.loot[g.id] : g.weight;
            share.textContent = !isNum(w) ? '' : w <= 0 ? '안 나옴' : `물고기(${fishW})와만 비교하면 약 ${fishPct(w / (fishW + w))}%`;
          };
          paintShare();
          const head = h('div', { class: 'field' }, h('strong', null, name), h('span', { class: 'id' }, whereText(g)),
            h('span', { class: 'spacer' }), h('span', null, '묶음 가중치'), weightInput(fs.loot, g, name, paintShare),
            h('span', { class: 'id' }, `원래 ${dpShown(g.weight)}${luckNote(g) ? ' · ' + luckNote(g) : ''}`), share,
            resetBtn(fs.loot, g, drawLoot));
          const block = h('div', { class: 'card' }, head);
          if (!table) {
            block.append(h('p', { class: 'muted' }, `내용물 표 ${g.table} 을(를) 서버에서 찾지 못했습니다.`));
            return block;
          }
          if (!table.editable) {
            block.append(h('p', { class: 'muted' }, `내용물 표 ${g.table} 은(는) 구조가 달라 여기서 아이템별로 바꿀 수 없습니다 (묶음 가중치만 조절).`));
            return block;
          }
          const edits = () => fs.tables[table.id] || (fs.tables[table.id] = { weights: {}, added: [] });
          const cleanup = () => {
            const t = fs.tables[table.id];
            if (t && !Object.keys(t.weights).length && !t.added.length) delete fs.tables[table.id];
          };
          const cur = () => fs.tables[table.id] || { weights: {}, added: [] };
          const effective = (en) => { const v = cur().weights[en.key]; return v !== undefined ? v : en.weight; };
          const pctCells = [];
          const empty = h('p', { class: 'banner warn' }, '남는 아이템이 없습니다 — 아예 안 나오게 하려면 위의 묶음 가중치를 0 으로 하세요.');
          const paintPct = () => {
            const c = cur();
            let total = 0;
            for (const en of table.entries) { const w = effective(en); if (isNum(w) && w > 0) total += w; }
            for (const a of c.added) if (isNum(a.weight) && a.weight > 0) total += a.weight;
            for (const [el, get] of pctCells) {
              const w = get();
              el.textContent = !isNum(w) || w <= 0 ? '안 나옴' : total > 0 ? `${fishPct(w / total)}%` : '';
            }
            empty.style.display = fishTableLeft(table, c) < 1 ? '' : 'none';
          };
          const pctCell = (get) => { const el = h('td', { class: 'muted' }); pctCells.push([el, get]); return el; };
          const entryName = (en) => (en.item ? itemName(en.item) : `${en.type || '항목'} ${en.name || ''}`.trim());
          const rows = table.entries.map((en) => {
            const inp = rowChecks.add(h('input', { type: 'number', step: 1, min: 0, style: 'width:5rem', 'aria-label': `${name} ${entryName(en)} 가중치`,
              placeholder: String(en.weight), value: shownNum(cur().weights[en.key]),
              oninput: (e) => {
                const raw = e.target.value.trim();
                const v = Number(raw);
                const t = edits();
                if (raw === '' || v === en.weight) delete t.weights[en.key]; else t.weights[en.key] = v;
                cleanup(); changed(); paintPct();
              } }), () => fishEntryRule(cur().weights[en.key]));
            return h('tr', null,
              h('td', null, h('div', { style: 'display:flex;gap:.4rem;align-items:center' }, en.item ? iconEl(en.item, 24) : null,
                h('div', null, h('div', null, entryName(en)), h('div', { class: 'id' }, [en.item || en.name].concat(en.notes || []).filter(Boolean).join(' · '))))),
              h('td', null, inp, h('span', { class: 'id' }, ` 원래 ${en.weight}`)),
              h('td', { class: 'muted' }, '—'),
              pctCell(() => effective(en)),
              h('td', null, cur().weights[en.key] !== undefined ? h('button', { class: 'small', 'aria-label': `${entryName(en)} 원래대로`, onclick: () => {
                delete edits().weights[en.key]; cleanup(); changed(); drawLoot();
              } }, '원래대로') : null));
          });
          const addedRows = cur().added.map((a, i) => {
            const num = (key, label, width, rule) => rowChecks.add(h('input', { type: 'number', step: 1, min: 1, style: `width:${width}`,
              'aria-label': `${name} 추가한 ${itemName(a.item)} ${label}`, value: shownNum(a[key]),
              oninput: (e) => { a[key] = numOf(e); changed(); paintPct(); rowChecks.paint(); } }), rule);
            const status = itemStatus(a.item);
            return h('tr', null,
              h('td', null, h('div', { style: 'display:flex;gap:.4rem;align-items:center' }, iconEl(a.item, 24),
                h('div', null, h('div', null, itemName(a.item), ' ', h('span', { class: 'tag' }, '추가함'),
                  status === 'missing' ? h('span', { class: 'tag bad' }, '설치되지 않은 아이템 (저장하면 건너뜀)') : null),
                  h('div', { class: 'id' }, a.item)))),
              h('td', null, num('weight', '가중치', '5rem', () => fishAddedWeightRule(a.weight))),
              h('td', null, num('min', '최소 개수', '3.5rem', () => fishAddedCountRule(a)), ' ~ ', num('max', '최대 개수', '3.5rem', () => fishAddedCountRule(a)), ' 개'),
              pctCell(() => a.weight),
              h('td', null, h('button', { class: 'small', 'aria-label': `${itemName(a.item)} 삭제`, onclick: () => {
                edits().added.splice(i, 1); cleanup(); changed(); drawLoot();
              } }, '삭제')));
          });
          block.append(
            h('p', { class: 'muted' }, `이 묶음이 뽑혔을 때 나오는 아이템입니다 (전리품 표 ${table.id}). 가중치는 정수이고, 0 으로 하면 그 아이템만 빠집니다.`),
            empty,
            h('div', { class: 'scroll-x' }, h('table', null,
              h('thead', null, h('tr', null, ['아이템', '가중치', '개수', '묶음 안 확률', ''].map((t) => h('th', null, t)))),
              h('tbody', null, ...rows, ...addedRows))),
            h('div', { class: 'field' }, h('button', { onclick: async () => {
              const id = await pickItem({ title: `'${name}' 에 추가할 아이템` });
              if (!id) return;
              edits().added.push({ item: id, weight: 1, min: 1, max: 1 });
              changed(); drawLoot();
            } }, '+ 아이템 추가')));
          paintPct();
          return block;
        });
        body.replaceChildren(
          h('p', { class: 'muted' }, '쓰레기·보물 묶음입니다. "묶음 가중치"는 물고기(85)와 겨루는 값이라, 0 으로 하면 그 묶음(예: 쓰레기)이 통째로 안 나옵니다. 그 아래 표는 묶음이 뽑혔을 때 무엇이 나오는지이며, 강화석 같은 아이템을 직접 추가할 수 있습니다. 지상 보물 표(minecraft:gameplay/fishing/treasure)는 낚싯대 특수 능력 "월척"의 추가 보물에도 쓰입니다.'),
          ...blocks);
        rowChecks.paint();
      };

      const drawCrates = () => {
        rowChecks = makeChecks();
        const tbody = h('tbody', null, ...(fc.crates || []).map((c) => {
          const name = FISH_CRATE_NAMES[fishTail(c.id)] || c.id;
          return h('tr', null,
            h('td', null, h('div', null, name), h('div', { class: 'id' }, c.id)),
            h('td', { class: 'id', style: 'white-space:normal' }, whereText(c)),
            h('td', null, weightInput(fs.crates, c, name), h('span', { class: 'id' }, ` 원래 ${dpShown(c.weight)}`)),
            h('td', null, resetBtn(fs.crates, c, drawCrates)));
        }));
        body.replaceChildren(
          h('p', { class: 'muted' }, '낚시로 건지는 상자입니다. 상자가 얼마나 자주 나오는지 자체는 Tide 설정(config 의 crateWeight)이 정하고, 여기 가중치는 그 자리에서 어떤 상자가 나올지를 정합니다. 0 으로 하면 그 상자는 안 나오며, 한 자리의 상자를 모두 0 으로 하면 그곳에서는 상자가 나오지 않습니다. 상자 안 내용물은 여기서 바꾸지 않습니다.'),
          h('div', { class: 'scroll-x' }, h('table', null,
            h('thead', null, h('tr', null, ['상자', '나오는 곳', '가중치', ''].map((t) => h('th', null, t)))), tbody)));
        rowChecks.paint();
      };

      const views = [['fish', '물고기', drawFish], ['loot', '쓰레기·보물', drawLoot], ['crates', '상자', drawCrates]];
      const switcher = h('div', { class: 'tabs', role: 'tablist', 'aria-label': '낚시 묶음' });
      const show = () => {
        const v = views.find((x) => x[0] === fishingUi.view) || views[0];
        switcher.replaceChildren(...views.map(([id, title]) => h('button', { role: 'tab', 'aria-selected': String(id === v[0]),
          onclick: () => { fishingUi.view = id; show(); } }, title)));
        v[2]();
      };
      card.append(switcher, body);
      show();
      dpOrphanCard(container, ['fishing']);
      return () => rowChecks.paint();
    },
  };
  registerTab(fishingTab);

  /* ----- 등장 탭 ----- */

  const SPAWN_WEIGHT_MAX = 1000;
  const SPAWN_COUNT_MAX = 64;
  const SPAWN_MULT_MAX = 100;
  const SPAWN_KEYS = [['weight', '가중치', SPAWN_WEIGHT_MAX], ['minCount', '최소', SPAWN_COUNT_MAX], ['maxCount', '최대', SPAWN_COUNT_MAX]];

  function spawnIntRule(v, max) {
    if (v === undefined || v === null) return '';
    return Number.isInteger(v) && v >= 1 && v <= max ? '' : `1~${max} 사이 정수여야 합니다`;
  }
  function spawnMultRule(v) {
    if (v === undefined) return '';
    return isNum(v) && v >= 0 && v <= SPAWN_MULT_MAX ? '' : `0~${SPAWN_MULT_MAX} 사이 숫자여야 합니다 (0 = 끄기)`;
  }

  function validateSpawns(bundle) {
    const out = [];
    const dp = bundle.datapack;
    if (!dp) return out;
    for (const [id, o] of Object.entries(dp.spawnRules || {})) {
      (o.spawners || []).forEach((s, i) => {
        if (!s) return;
        for (const [key, label, max] of SPAWN_KEYS) {
          const m = spawnIntRule(s[key], max);
          if (m) out.push(`등장 규칙 ${id} › ${i + 1}번 ${label}: ${m}`);
        }
        if (Number.isInteger(s.minCount) && Number.isInteger(s.maxCount) && s.minCount > s.maxCount) out.push(`등장 규칙 ${id} › ${i + 1}번: 최소 수가 최대 수보다 큽니다`);
      });
    }
    for (const [id, v] of Object.entries(dp.spawnMobs || {})) {
      const m = spawnMultRule(v);
      if (m) out.push(`몹 등장 배율 ${id}: ${m}`);
    }
    return out;
  }

  const spawnUi = { query: '', category: 'monster', onlyChanged: false };

  const spawnsTab = {
    id: 'spawns',
    title: '등장',
    validate: validateSpawns,
    render(container) {
      const cat = S.dpCatalog;
      const dp = S.bundle.datapack;
      const head = h('div', { class: 'card' }, h('h2', null, '몬스터 등장'),
        h('p', { class: 'banner warn' }, '등장 설정은 서버를 재시작해야 적용됩니다. 저장하면 데이터팩 gett_balance 에 파일만 써 두고, 지금 켜져 있는 세계의 등장은 재시작 전까지 그대로입니다 (/reload 로도 바뀌지 않음).'),
        cat && cat.spawnRestartPending ? h('p', { class: 'banner bad' }, '이번 서버 실행 중에 저장한 등장 변경이 있습니다 — 아직 적용되지 않았습니다. 서버를 재시작하세요.') : null);
      container.append(head);
      if (!cat || !dp) { head.append(dpMissing()); return null; }
      let ruleChecks = makeChecks();
      let mobChecks = makeChecks();

      /* (a) 모드가 추가한 등장 규칙 */
      const rules = dp.spawnRules;
      const ruleCat = (cat.spawnRules || []).slice();
      const knownRules = new Set(ruleCat.map((r) => r.id));
      for (const id of Object.keys(rules)) if (!knownRules.has(id)) ruleCat.push({ id, mod: '', biomes: '', spawners: [], missing: true });
      const origSpawners = (r) => (r.spawners || []).map((s) => ({ weight: s.weight, minCount: s.minCount, maxCount: s.maxCount }));
      const sameAsOrig = (r, list) => (list || []).every((s, i) => !s || SPAWN_KEYS.every(([k]) => !r.spawners[i] || s[k] === r.spawners[i][k]));
      const tidyRule = (r) => {
        const st = rules[r.id];
        if (!st) return;
        if (!st.disabled) delete st.disabled;
        if (st.spawners && sameAsOrig(r, st.spawners)) delete st.spawners;
        if (!Object.keys(st).length) delete rules[r.id];
      };
      const ruleBody = h('tbody');
      const drawRules = () => {
        ruleChecks = makeChecks();
        ruleBody.replaceChildren(...ruleCat.map((r) => {
          const st = rules[r.id];
          const off = !!(st && st.disabled);
          const lines = (r.spawners || []).map((s, i) => {
            const inputs = SPAWN_KEYS.map(([key, label, max]) => {
              const cur = st && st.spawners && st.spawners[i] ? st.spawners[i][key] : undefined;
              const shownVal = cur !== undefined && cur !== s[key] ? cur : '';
              return h('label', { class: 'muted', style: 'margin-right:.4rem' }, label + ' ',
                ruleChecks.add(h('input', { type: 'number', step: '1', style: 'width:4.2rem', disabled: off, 'aria-label': `${r.id} ${entityName(s.type)} ${label}`,
                  placeholder: dpShown(s[key]), value: shownVal,
                  oninput: (e) => {
                    const raw = e.target.value.trim();
                    const cur2 = rules[r.id] || (rules[r.id] = {});
                    const list = cur2.spawners || (cur2.spawners = origSpawners(r));
                    list[i][key] = raw === '' ? s[key] : Number(raw);
                    tidyRule(r);
                    changed();
                  } }), () => {
                  const x = rules[r.id] && rules[r.id].spawners && rules[r.id].spawners[i];
                  if (!x) return '';
                  const m = spawnIntRule(x[key], max);
                  if (m) return m;
                  return key !== 'weight' && Number.isInteger(x.minCount) && Number.isInteger(x.maxCount) && x.minCount > x.maxCount ? '최소 수가 최대 수보다 큽니다' : '';
                }));
            });
            return h('div', { style: 'margin-bottom:.25rem' }, h('strong', null, entityName(s.type)), h('span', { class: 'id' }, ` ${s.type} · 원래 가중치 ${dpShown(s.weight)}, ${dpShown(s.minCount)}~${dpShown(s.maxCount)}마리`), h('div', null, ...inputs));
          });
          return h('tr', null,
            h('td', null, h('div', null, r.missing ? '(서버에 없음 — 저장하면 무시)' : r.id), h('div', { class: 'id' }, r.mod)),
            h('td', null, h('span', { class: 'muted' }, r.biomes || '')),
            h('td', null, ...lines),
            h('td', null, h('label', { class: 'chk' }, h('input', { type: 'checkbox', checked: off, 'aria-label': `${r.id} 끄기`,
              onchange: (e) => {
                const cur2 = rules[r.id] || (rules[r.id] = {});
                cur2.disabled = e.target.checked;
                tidyRule(r);
                changed(); drawRules();
              } }), ' 끄기')),
            h('td', null, st ? h('button', { class: 'small', 'aria-label': `${r.id} 원래대로`, onclick: () => {
              delete rules[r.id]; changed(); drawRules();
            } }, '원래대로') : null));
        }));
        ruleChecks.paint();
      };

      /* (b) 몹별 자연 등장 (모든 생물군계) */
      const mobs = dp.spawnMobs;
      const mobCat = (cat.spawnMobs || []).slice();
      const knownMobs = new Set(mobCat.map((m) => m.entity));
      // 서버에 없는 몹의 배율(원본 없음)은 아래 "원본 없는 사본" 칸에만 보인다.
      const orphanMobs = new Set((Array.isArray(cat.orphans) ? cat.orphans : []).filter((o) => o && o.kind === 'spawnMobs').map((o) => o.id));
      for (const id of Object.keys(mobs)) if (!knownMobs.has(id) && !orphanMobs.has(id)) mobCat.push({ entity: id, category: '', biomes: 0 });
      mobCat.sort((a, b) => entityName(a.entity).localeCompare(entityName(b.entity)));
      const mobBody = h('tbody');
      const mobCount = h('span', { class: 'muted' });
      const drawMobs = () => {
        mobChecks = makeChecks();
        const q = spawnUi.query.trim().toLowerCase();
        const list = mobCat.filter((m) => (!spawnUi.onlyChanged || mobs[m.entity] !== undefined)
          && (!spawnUi.category || !m.category || m.category === spawnUi.category)
          && (!q || (entityName(m.entity) + ' ' + m.entity).toLowerCase().includes(q)));
        mobCount.textContent = `${list.length}종`;
        mobBody.replaceChildren(...list.map((m) => {
          const cur = mobs[m.entity];
          const off = cur === 0;
          const inp = mobChecks.add(h('input', { type: 'number', step: 'any', style: 'width:4.5rem', disabled: off, 'aria-label': `${entityName(m.entity)} 등장 배율`,
            placeholder: '1', value: off ? '' : shownNum(cur),
            oninput: (e) => {
              const raw = e.target.value.trim();
              const v = Number(raw);
              if (raw === '' || v === 1) delete mobs[m.entity]; else mobs[m.entity] = v;
              changed();
            } }), () => spawnMultRule(mobs[m.entity]));
          const now = m.biomes ? `${m.biomes}곳 · 가중치 ${m.minWeight === m.maxWeight ? m.minWeight : m.minWeight + '~' + m.maxWeight} · ${m.minCount === m.maxCount ? m.minCount : m.minCount + '~' + m.maxCount}마리` : '지금은 자연 등장 없음';
          return h('tr', null,
            h('td', null, h('div', null, entityName(m.entity)), h('div', { class: 'id' }, m.entity)),
            h('td', null, h('span', { class: 'muted' }, MOB_CATEGORY_LABEL[m.category] || m.category || '')),
            h('td', null, h('span', { class: 'muted' }, now)),
            h('td', null, inp, h('span', { class: 'muted' }, ' 배')),
            h('td', null, h('label', { class: 'chk' }, h('input', { type: 'checkbox', checked: off, 'aria-label': `${m.entity} 끄기`,
              onchange: (e) => { if (e.target.checked) mobs[m.entity] = 0; else delete mobs[m.entity]; changed(); drawMobs(); } }), ' 끄기')),
            h('td', null, cur !== undefined ? h('button', { class: 'small', 'aria-label': `${m.entity} 원래대로`, onclick: () => {
              delete mobs[m.entity]; changed(); drawMobs();
            } }, '원래대로') : null));
        }));
        mobChecks.paint();
      };
      const draw = () => { drawRules(); drawMobs(); };

      const cats = Array.from(new Set(mobCat.map((m) => m.category).filter(Boolean))).sort();
      const catSel = h('select', { 'aria-label': '분류', onchange: (e) => { spawnUi.category = e.target.value; draw(); } },
        h('option', { value: '' }, '전체'), ...cats.map((c) => h('option', { value: c }, MOB_CATEGORY_LABEL[c] || c)));
      catSel.value = cats.includes(spawnUi.category) ? spawnUi.category : '';
      spawnUi.category = catSel.value;
      const search = h('input', { type: 'search', placeholder: '몹 이름·id 검색', 'aria-label': '몹 검색', value: spawnUi.query, style: 'width:16rem',
        oninput: (e) => { spawnUi.query = e.target.value; draw(); } });
      const onlyChanged = h('label', { class: 'chk' }, h('input', { type: 'checkbox', checked: spawnUi.onlyChanged,
        onchange: (e) => { spawnUi.onlyChanged = e.target.checked; draw(); } }), ' 바꾼 것만');

      container.append(
        h('div', { class: 'card' }, h('h2', null, '모드가 추가한 등장 규칙'),
          h('p', { class: 'muted' }, '모드의 등장 규칙 파일(data/<모드>/neoforge/biome_modifier/…, neoforge:add_spawns) 입니다. 가중치·무리 수(최소~최대)를 바꾸거나 규칙을 끌 수 있습니다 (빈 칸 = 원래 값). 저장하면 그 파일의 사본을 데이터팩에 쓰고, "끄기"는 사본의 종류를 neoforge:none 으로 바꿉니다. "원래대로" = 사본 삭제.'),
          ruleCat.length ? h('div', { class: 'scroll-x' }, h('table', null,
            h('thead', null, h('tr', null, ['규칙', '생물군계', '몹 · 가중치 · 무리 수', '', ''].map((t) => h('th', null, t)))), ruleBody))
            : h('p', { class: 'muted' }, '모드가 추가한 등장 규칙이 없습니다.')),
        h('div', { class: 'card' }, h('h2', null, '몹별 자연 등장 (모든 생물군계)'),
          h('p', { class: 'muted' }, '몹 하나의 자연 등장 가중치를 모든 생물군계에서 몇 배로 할지 정합니다 (빈 칸 = 1배, 0 = 끄기). 바닐라 등장과 모드가 추가한 등장 모두에 곱해지고, 가중치는 반올림해 최소 1 입니다. 무리 수는 바꾸지 않습니다.'),
          h('p', { class: 'muted' }, '"지금" 칸은 서버가 마지막으로 시작할 때 적용된 값입니다 (그때의 배율·끄기 포함). 사냥터 범위 안은 이 설정과 관계없이 자연 등장이 막혀 있습니다.'),
          h('div', { class: 'field' }, search, catSel, onlyChanged, mobCount),
          h('div', { class: 'scroll-x' }, h('table', null,
            h('thead', null, h('tr', null, ['몹', '분류', '지금 (생물군계 수 · 가중치 · 무리)', '배율', '', ''].map((t) => h('th', null, t)))), mobBody))));
      dpOrphanCard(container, ['spawnRules', 'spawnMobs']);
      draw();
      return () => { ruleChecks.paint(); mobChecks.paint(); };
    },
  };
  registerTab(spawnsTab);

  /* ----- Epic Fight 탭 (무기 특성: data/<모드>/capabilities/weapons/<아이템>.json 의 사본) ----- */

  // [키, 제목, 최소, 최대, 정수?, 단위] — 서버 EpicFightBalance.FIELDS 와 같은 키·범위.
  const EF_FIELDS = [
    ['damage_bonus', '추가 피해', -100, 1000, false, ''],
    ['speed_bonus', '공격 속도 보정', -4, 4, false, ''],
    ['armor_negation', '방어 무시', 0, 100, false, '%'],
    ['impact', '충격', -10, 100, false, ''],
    ['max_strikes', '최대 타격 수', 0, 50, true, ''],
  ];
  const EF_STYLE_NAMES = { common: '공통', one_hand: '한손', two_hand: '양손' };
  const EF_SHOW_MAX = 150;
  const efStyleName = (s) => EF_STYLE_NAMES[s] || s;

  function efRule(key, v) {
    if (v === undefined) return '';
    const f = EF_FIELDS.find((x) => x[0] === key);
    if (!f) return '모르는 항목입니다';
    if (!isNum(v)) return '숫자여야 합니다 (비우면 원래 값)';
    if (f[4] && !Number.isInteger(v)) return '정수여야 합니다';
    return v >= f[2] && v <= f[3] ? '' : `${f[2]}~${f[3]} 사이여야 합니다`;
  }

  function validateEpicFight(bundle) {
    const out = [];
    const ef = (bundle.datapack && bundle.datapack.epicfight) || {};
    for (const [id, styles] of Object.entries(ef)) {
      for (const [style, o] of Object.entries(styles || {})) {
        for (const [key, label] of EF_FIELDS) {
          const m = efRule(key, (o || {})[key]);
          if (m) out.push(`Epic Fight ${id} › ${efStyleName(style)} › ${label}: ${m}`);
        }
      }
    }
    return out;
  }

  const efUi = { query: '', mod: '', type: '', onlyChanged: false };

  const epicFightTab = {
    id: 'epicfight',
    title: 'Epic Fight',
    validate: validateEpicFight,
    render(container) {
      const card = h('div', { class: 'card' }, h('h2', null, 'Epic Fight 무기 특성'),
        h('p', { class: 'muted' }, 'Epic Fight 가 무기마다 읽는 특성 파일(data/<모드>/capabilities/weapons/…) 값을 바꿉니다. 칸에 숫자를 넣으면 바뀌고, 빈 칸은 모드의 원래 값입니다. "—" 는 원본 파일에 없는 값입니다 (넣으면 새로 추가).'),
        h('p', { class: 'muted' }, '추가 피해 = 공격력에 더함, 공격 속도 보정 = 공격 속도에 더함, 방어 무시 = 상대 방어를 무시하는 %, 충격 = 경직(스턴) 세기, 최대 타격 수 = 한 번 휘둘러 맞히는 대상 수에 더함. 스타일: 공통(모든 자세) / 한손 / 양손 — 원본에 있는 스타일만 보이고, 특성이 하나도 없는 무기는 "공통" 에 새로 넣을 수 있습니다.'),
        h('p', { class: 'muted' }, '저장하면 서버 월드의 데이터팩 gett_balance 에 그 파일의 사본(원본 그대로 + 바꾼 값만)을 쓰고 /reload 를 자동으로 실행합니다. Epic Fight 는 /reload 때 무기 특성을 다시 읽고 접속자에게 다시 보냅니다 — 손에 들고 있던 무기는 다른 칸으로 바꿨다가 다시 들어야 반영될 수 있습니다. "원래대로" = 사본 삭제.'));
      container.append(card);
      const cat = S.dpCatalog;
      const dp = S.bundle.datapack;
      if (!cat || !dp) { card.append(dpMissing()); return null; }
      const ef = dp.epicfight;
      let rowChecks = makeChecks();
      const catalog = (Array.isArray(cat.epicfight) ? cat.epicfight : []).slice();
      const known = new Set(catalog.map((w) => w.id));
      for (const id of Object.keys(ef)) if (!known.has(id)) catalog.push({ id, mod: '', type: '', styles: {}, missing: true });

      const cleanup = (id) => {
        const row = ef[id];
        if (!row) return;
        for (const s of Object.keys(row)) if (!row[s] || !Object.keys(row[s]).length) delete row[s];
        if (!Object.keys(row).length) delete ef[id];
      };
      /** 이 무기에 보여 줄 스타일: 원본의 스타일, 바꾼 값이 있는 스타일, 특성이 없는 무기는 공통. */
      const stylesOf = (w) => {
        const list = Object.keys(w.styles || {});
        for (const s of Object.keys(ef[w.id] || {})) if (!list.includes(s)) list.push(s);
        if (!list.length) list.push('common');
        return list;
      };

      const tbody = h('tbody');
      const count = h('span', { class: 'muted' });
      const draw = () => {
        rowChecks = makeChecks();
        const q = efUi.query.trim().toLowerCase();
        const list = catalog.filter((w) => (!efUi.onlyChanged || ef[w.id])
          && (!efUi.mod || w.mod === efUi.mod)
          && (!efUi.type || w.type === efUi.type)
          && (!q || (itemName(w.id) + ' ' + w.id + ' ' + w.type).toLowerCase().includes(q)));
        const shown = list.slice(0, EF_SHOW_MAX);
        count.textContent = list.length > shown.length ? `${list.length}개 중 ${shown.length}개 표시 — 검색·필터로 좁히세요` : `${list.length}개`;
        tbody.replaceChildren(...shown.map((w) => {
          const styles = stylesOf(w);
          const cells = EF_FIELDS.map(([key, label, , , , unit]) => h('td', null, ...styles.map((style) => {
            const base = w.styles && w.styles[style] ? w.styles[style][key] : undefined;
            const cur = ef[w.id] && ef[w.id][style] ? ef[w.id][style][key] : undefined;
            const inp = rowChecks.add(h('input', { type: 'number', step: 'any', style: 'width:4.5rem',
              'aria-label': `${itemName(w.id)} ${efStyleName(style)} ${label}`,
              placeholder: dpShown(base), value: shownNum(cur),
              oninput: (e) => {
                const raw = e.target.value.trim();
                const row = ef[w.id] || (ef[w.id] = {});
                const st = row[style] || (row[style] = {});
                const v = Number(raw);
                if (raw === '' || (isNum(base) && Math.abs(v - base) < 1e-9)) delete st[key]; else st[key] = v;
                cleanup(w.id);
                changed();
              } }), () => efRule(key, ef[w.id] && ef[w.id][style] ? ef[w.id][style][key] : undefined));
            return h('div', { style: 'margin-bottom:.2rem;white-space:nowrap' }, inp, h('span', { class: 'muted' }, unit),
              h('div', { class: 'id' }, `${styles.length > 1 || style !== 'common' ? efStyleName(style) + ' · ' : ''}원래 ${dpShown(base, unit)}`));
          })));
          return h('tr', null,
            h('td', null, h('div', { class: 'row', style: 'gap:.4rem;align-items:center' }, w.missing ? null : iconEl(w.id, 24),
              h('div', null, h('div', null, w.missing ? '(서버에 없음 — 저장하면 무시)' : itemName(w.id)), h('div', { class: 'id' }, w.id)))),
            h('td', null, h('span', { class: 'id' }, w.type || '—')),
            ...cells,
            h('td', null, ef[w.id] ? h('button', { class: 'small', 'aria-label': `${w.id} 원래대로`, onclick: () => {
              delete ef[w.id]; changed(); draw();
            } }, '원래대로') : null));
        }));
        rowChecks.paint();
      };

      const all = Array.isArray(cat.epicfight) ? cat.epicfight : [];
      const mods = Array.from(new Set(all.map((w) => w.mod).filter(Boolean))).sort();
      const modSel = h('select', { 'aria-label': '모드', onchange: (e) => { efUi.mod = e.target.value; draw(); } },
        h('option', { value: '' }, '모든 모드'), ...mods.map((m) => h('option', { value: m }, m)));
      modSel.value = mods.includes(efUi.mod) ? efUi.mod : '';
      efUi.mod = modSel.value;
      const types = Array.from(new Set(all.map((w) => w.type).filter(Boolean))).sort();
      const typeSel = h('select', { 'aria-label': '무기 종류', onchange: (e) => { efUi.type = e.target.value; draw(); } },
        h('option', { value: '' }, '모든 종류'), ...types.map((t) => h('option', { value: t }, t)));
      typeSel.value = types.includes(efUi.type) ? efUi.type : '';
      efUi.type = typeSel.value;
      const search = h('input', { type: 'search', placeholder: '이름·id·종류 검색', 'aria-label': '무기 검색', value: efUi.query, style: 'width:16rem',
        oninput: (e) => { efUi.query = e.target.value; draw(); } });
      const onlyChanged = h('label', { class: 'chk' }, h('input', { type: 'checkbox', checked: efUi.onlyChanged,
        onchange: (e) => { efUi.onlyChanged = e.target.checked; draw(); } }), ' 바꾼 것만');
      card.append(
        !all.length ? h('p', { class: 'muted' }, '서버에 Epic Fight 무기 특성 파일이 없습니다 (Epic Fight 가 설치돼 있는지 확인).') : null,
        h('div', { class: 'field' }, search, modSel, typeSel, onlyChanged, count),
        h('div', { class: 'scroll-x' }, h('table', null,
          h('thead', null, h('tr', null, ['무기', '종류'].concat(EF_FIELDS.map((f) => f[1]), ['']).map((t) => h('th', null, t)))), tbody)));
      dpOrphanCard(container, ['epicfight']);
      draw();
      return () => rowChecks.paint();
    },
  };
  registerTab(epicFightTab);

  /* ----- 제작법 탭 (모드 장비 제작 막기: data/<ns>/recipe/<경로>.json 의 사본에 "항상 거짓" 조건 neoforge:false) ----- */

  const RECIPE_KINDS = [['weapon', '무기'], ['armor', '방어구'], ['shield', '방패'], ['gun', '총'], ['other', '기타 장비']];
  const RECIPE_MOD_NAMES = { magistuarmory: 'Epic Knights', wom: 'Weapons of Miracles', epicfight: 'Epic Fight',
    cgs: 'Create: Gunsmithing', ntgl: 'NTGL' };
  const recipeKindName = (k) => (RECIPE_KINDS.find((x) => x[0] === k) || [k, k])[1];
  const recipeModName = (m) => (RECIPE_MOD_NAMES[m] ? `${RECIPE_MOD_NAMES[m]} (${m})` : m || '(알 수 없음)');
  const recipeUi = { query: '', onlyBlocked: false, open: {} };

  const recipesTab = {
    id: 'recipes',
    title: '제작법',
    validate() { return []; },
    render(container) {
      const card = h('div', { class: 'card' }, h('h2', null, '모드 장비 제작 막기'),
        h('p', { class: 'muted' }, '모드(Epic Knights·Weapons of Miracles·Epic Fight·Create: Gunsmithing·NTGL 등)가 넣은 무기·방어구·방패·총의 제작법을 막습니다. 바닐라(minecraft) 장비와 탄약·부품·재료는 목록에 없고 그대로 만들 수 있습니다. 결과물이 장비인 제작법이면 종류(작업대·대장장이 작업대·Create 기계 제작·조립 등)와 상관없이 보입니다.'),
        h('p', { class: 'muted' }, '저장하면 서버 월드의 데이터팩 gett_balance 에 그 제작법 파일의 사본(원본 그대로 + 항상 거짓인 불러오기 조건 neoforge:false)을 쓰고 /reload 를 자동으로 실행합니다. 그 뒤 막은 제작법은 서버에 없는 것처럼 되어 만들 수 없습니다. 이미 가진 장비, 상점·보상으로 받는 장비는 그대로입니다. 체크를 풀고 저장 = 사본 삭제(원래대로).'));
      container.append(card);
      const cat = S.dpCatalog;
      const dp = S.bundle.datapack;
      if (!cat || !dp) { card.append(dpMissing()); return null; }
      const blocked = dp.recipes;
      const all = (Array.isArray(cat.recipes) ? cat.recipes : []).slice();
      const known = new Set(all.map((r) => r.id));
      for (const id of Object.keys(blocked)) if (!known.has(id)) all.push({ id, item: '', mod: '', kind: 'other', type: '', missing: true });
      const setBlocked = (id, on) => { if (on) blocked[id] = true; else delete blocked[id]; };

      const body = h('div');
      const count = h('span', { class: 'muted' });
      const draw = () => {
        const q = recipeUi.query.trim().toLowerCase();
        const match = (r) => (!recipeUi.onlyBlocked || blocked[r.id])
          && (!q || (itemName(r.item) + ' ' + r.item + ' ' + r.id + ' ' + r.type + ' ' + recipeModName(r.mod)).toLowerCase().includes(q));
        const list = all.filter(match);
        const nBlocked = all.filter((r) => blocked[r.id]).length;
        count.textContent = `${list.length}개 표시 · 전체 ${all.length}개 중 ${nBlocked}개 막음`;
        const mods = Array.from(new Set(list.map((r) => r.mod))).sort((a, b) => recipeModName(a).localeCompare(recipeModName(b)));
        body.replaceChildren(...mods.map((mod) => {
          const modAll = all.filter((r) => r.mod === mod && !r.missing);
          const modList = list.filter((r) => r.mod === mod);
          const modBlocked = modAll.filter((r) => blocked[r.id]).length;
          const isOpen = recipeUi.open[mod] || !!q || recipeUi.onlyBlocked || mods.length === 1;
          const det = h('details', { class: 'card', style: 'margin:.6rem 0' });
          det.open = isOpen;
          det.addEventListener('toggle', () => { recipeUi.open[mod] = det.open; });
          const bulk = (on) => (e) => {
            e.preventDefault();
            for (const r of modAll) setBlocked(r.id, on);
            changed();
            draw();
          };
          det.append(h('summary', null, h('strong', null, recipeModName(mod)), ' ',
            h('span', { class: 'muted' }, `— 제작법 ${modAll.length}개, ${modBlocked}개 막음`)));
          det.append(h('div', { class: 'row', style: 'gap:.4rem;margin:.4rem 0' },
            modAll.length ? h('button', { class: 'small', onclick: bulk(true) }, '이 모드 장비 제작 모두 막기') : null,
            modAll.length ? h('button', { class: 'small', onclick: bulk(false) }, '모두 풀기') : null));
          for (const [kind, kindName] of RECIPE_KINDS) {
            const rows = modList.filter((r) => (r.kind || 'other') === kind);
            if (!rows.length) continue;
            det.append(h('h3', null, `${kindName} (${rows.length})`),
              h('div', { class: 'scroll-x' }, h('table', null,
                h('thead', null, h('tr', null, ['제작 막기', '결과 아이템', '제작법 id', '제작 종류'].map((t) => h('th', null, t)))),
                h('tbody', null, rows.map((r) => h('tr', null,
                  h('td', null, h('label', { class: 'chk' }, h('input', { type: 'checkbox', checked: !!blocked[r.id],
                    'aria-label': `${r.missing ? r.id : itemName(r.item)} 제작 막기`,
                    onchange: (e) => { setBlocked(r.id, e.target.checked); changed(); draw(); } }), ' 막기')),
                  h('td', null, h('div', { class: 'row', style: 'gap:.4rem;align-items:center' }, r.missing ? null : iconEl(r.item, 24),
                    h('div', null, h('div', null, r.missing ? '(서버에 없음 — 저장하면 무시)' : itemName(r.item)),
                      r.item ? h('div', { class: 'id' }, r.item) : null))),
                  h('td', null, h('span', { class: 'id' }, r.id)),
                  h('td', null, h('span', { class: 'id' }, r.type || '—'))))))));
          }
          return det;
        }));
        if (!list.length) body.append(h('p', { class: 'muted' }, all.length ? '조건에 맞는 제작법이 없습니다.' : '서버에 모드 장비 제작법이 없습니다.'));
      };

      const search = h('input', { type: 'search', placeholder: '아이템 이름·id·제작법 검색', 'aria-label': '제작법 검색', value: recipeUi.query, style: 'width:16rem',
        oninput: (e) => { recipeUi.query = e.target.value; draw(); } });
      const onlyBlocked = h('label', { class: 'chk' }, h('input', { type: 'checkbox', checked: recipeUi.onlyBlocked,
        onchange: (e) => { recipeUi.onlyBlocked = e.target.checked; draw(); } }), ' 막은 것만');
      card.append(h('div', { class: 'field' }, search, onlyBlocked, count), body);
      dpOrphanCard(container, ['recipes']);
      draw();
      return null;
    },
  };
  registerTab(recipesTab);

  /* ===================== 8. 화면 뼈대 ===================== */

  const app = document.getElementById('app');
  let elTabs, elDirty, elErrors, elSave, elResult, elDraft, elMain;

  function allErrors() {
    const out = [];
    for (const t of TABS) for (const m of t.validate(S.bundle)) out.push(m);
    return out;
  }

  function refreshChrome() {
    if (!elDirty) return;
    const dirty = isDirty();
    const errs = allErrors().length;
    elDirty.classList.toggle('hidden', !dirty);
    elErrors.classList.toggle('hidden', errs === 0);
    elErrors.textContent = `오류 ${errs}개`;
    elSave.disabled = S.saving || !dirty;
    elSave.textContent = S.saving ? '저장 중…' : '저장';
  }

  function renderTabContent() {
    elMain.replaceChildren();
    const tab = TABS.find((t) => t.id === S.tab) || TABS[0];
    currentPaint = tab.render(elMain);
    paintCurrent();
    refreshChrome();
  }

  function renderResult() {
    elResult.replaceChildren();
    const r = S.result;
    if (!r) return;
    const list = (arr) => (arr.length ? h('ul', null, arr.map((m) => h('li', null, m))) : null);
    elResult.append(h('div', { class: 'banner ' + r.kind },
      h('div', { class: 'row' }, h('h3', { style: 'margin:0' }, r.title), h('span', { class: 'spacer' }),
        h('button', { class: 'small', onclick: () => { S.result = null; renderResult(); } }, '닫기')),
      r.errors.length ? h('div', null, h('strong', null, `오류 ${r.errors.length}개`), list(r.errors)) : null,
      r.warnings.length ? h('div', null, h('strong', null, `경고 ${r.warnings.length}개`), list(r.warnings)) : null));
  }

  function renderDraftOffer() {
    elDraft.replaceChildren();
    const d = S.draftOffer;
    if (!d) return;
    const stale = d.base !== S.baseline;
    elDraft.append(h('div', { class: 'banner warn' },
      h('div', { class: 'row' },
        h('span', null, '저장하지 못한 이전 편집 내용이 있습니다.' + (stale ? ' (그 사이 서버 데이터가 바뀌었을 수 있어, 복원 후 저장하면 그 변경을 덮어씁니다.)' : '')),
        h('span', { class: 'spacer' }),
        h('button', { class: 'primary small', onclick: () => {
          S.bundle = bundleFrom(d.bundle, S.bundle);
          S.draftOffer = null; renderDraftOffer();
          S.ui.shopId = Object.keys(S.bundle.shops)[0] || null;
          renderTabContent(); scheduleDraft();
        } }, '복원'),
        h('button', { class: 'small', onclick: () => { lsDel(DRAFT_KEY); S.draftOffer = null; renderDraftOffer(); } }, '버리기'))));
  }

  function selectTab(id) {
    S.tab = id;
    if (elTabs) elTabs.querySelectorAll('button').forEach((bt, i) => bt.setAttribute('aria-selected', String(TABS[i].id === S.tab)));
    renderTabContent();
  }

  function renderShell() {
    elTabs = h('div', { class: 'tabs', role: 'tablist' }, TABS.map((t) => h('button', {
      role: 'tab', 'aria-selected': String(t.id === S.tab),
      onclick: () => selectTab(t.id),
    }, t.title)));
    elDirty = h('span', { class: 'badge dirty hidden' }, '저장 안 된 변경 있음');
    elErrors = h('span', { class: 'badge bad hidden', role: 'status' }, '');
    elSave = h('button', { class: 'primary', onclick: save }, '저장');
    elResult = h('div');
    elDraft = h('div');
    elMain = h('main');
    app.replaceChildren(
      h('div', { class: 'topbar' }, h('h1', null, 'Gett 편집기'), elTabs, h('span', { class: 'spacer' }), elDirty, elErrors, elSave),
      elDraft, elResult, elMain);
  }

  async function save() {
    if (S.saving) return;
    const errs = allErrors();
    if (errs.length) {
      S.result = { kind: 'bad', title: '저장하기 전에 아래 오류를 고쳐 주세요', errors: errs, warnings: [] };
      renderResult();
      return;
    }
    S.saving = true; refreshChrome();
    let result = null;
    try {
      const resp = await api('/api/save', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: serialize(S.bundle) });
      if (resp.status === 504) throw new Error('서버가 제때 응답하지 않았습니다 (시간 초과). 게임에 접속해 있는지 확인하고 다시 시도하세요.');
      if (resp.status === 403) throw new Error('접근이 거부되었습니다. 게임에서 /gett 편집 으로 다시 열어 주세요.');
      if (resp.status === 413) throw new Error('저장 데이터가 너무 큽니다.');
      let data = null;
      try { data = await resp.json(); } catch (e) { /* 아래에서 처리 */ }
      if (!resp.ok || !data) throw new Error(`저장 요청이 실패했습니다 (HTTP ${resp.status}${data && data.error ? ', ' + data.error : ''}).`);
      result = data;
    } catch (e) {
      S.result = { kind: 'bad', title: '저장하지 못했습니다', errors: [e.message || String(e)], warnings: [] };
    }
    S.saving = false;
    if (result) {
      const errors = result.errors || [];
      const warnings = result.warnings || [];
      let snap = result.snapshot;
      if (typeof snap === 'string') { try { snap = JSON.parse(snap); } catch (e) { snap = null; } }
      if (result.saved && snap) {
        install(snap);
        refreshMissing();
        lsDel(DRAFT_KEY);
        S.result = { kind: 'ok', title: '저장했습니다', errors: [], warnings };
      } else if (result.saved) {
        S.result = { kind: 'warn', title: '저장했지만 새 데이터를 받지 못했습니다. 페이지를 새로고침하세요', errors: [], warnings };
      } else {
        S.result = { kind: 'bad', title: '저장하지 않았습니다', errors: errors.length ? errors : ['알 수 없는 오류'], warnings };
      }
    }
    renderResult();
    renderTabContent();
  }

  window.addEventListener('beforeunload', (e) => {
    if (S.bundle && isDirty()) { e.preventDefault(); e.returnValue = ''; }
  });

  /*
   * 게임에 "이 창이 아직 열려 있다" 알리기: 15초마다 생존 신호(저장 안 된 변경 여부 포함), 닫힐 때 닫힘 신호.
   * 게임은 닫힘 신호 몇 초 뒤(새로고침이면 새 페이지가 곧 신호를 보내 이어짐), 또는 신호가 60초 끊기면(백그라운드 탭은 30분)
   * 편집기를 끝내고, 저장 안 된 변경이 있었을 때만 채팅으로 알린다.
   */
  const PAGE_ID = 'p' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
  const pageState = () => JSON.stringify({ page: PAGE_ID, dirty: !!(S.bundle && isDirty()), hidden: document.visibilityState === 'hidden' });
  function heartbeat() {
    if (!TOKEN) return;
    api('/api/heartbeat', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: pageState(), keepalive: true }).catch(() => {});
  }
  let closeSent = false;
  function sendClose() {
    if (!TOKEN || closeSent) return;
    closeSent = true;
    // sendBeacon 은 헤더를 못 붙이므로 토큰은 페이지 주소처럼 ?t= 로 보낸다.
    try { if (navigator.sendBeacon && navigator.sendBeacon('/api/close' + TOKEN_Q, pageState())) return; } catch (e) { /* 아래로 */ }
    try { fetch('/api/close' + TOKEN_Q, { method: 'POST', body: pageState(), keepalive: true }); } catch (e) { /* 무시 */ }
  }
  setInterval(heartbeat, 15000);
  heartbeat();
  document.addEventListener('visibilitychange', heartbeat);
  window.addEventListener('pagehide', sendClose);
  window.addEventListener('pageshow', (e) => { if (e.persisted) { closeSent = false; heartbeat(); } });

  async function boot() {
    if (!TOKEN) { app.textContent = '접근 토큰이 없습니다. 게임에서 /gett 편집 으로 열어 주세요.'; return; }
    try {
      const [snapResp, catResp] = await Promise.all([api('/api/snapshot'), api('/api/catalog').catch(() => null)]);
      if (!snapResp.ok) throw new Error('HTTP ' + snapResp.status);
      install(await snapResp.json());
      if (catResp && catResp.ok) {
        try { S.catalog = Object.assign(S.catalog, await catResp.json()); S.catalogOk = true; } catch (e) { /* 카탈로그 없이 진행 */ }
      }
    } catch (e) {
      app.textContent = '데이터를 불러오지 못했습니다: ' + (e.message || e) + ' — 게임이 켜져 있고 서버에 접속해 있는지 확인하세요.';
      return;
    }
    indexCatalog();
    refreshMissing();
    renderShell();
    if (!S.catalogOk) {
      S.result = { kind: 'warn', title: '아이템 목록을 불러오지 못했습니다 (아이템 선택기에서 id를 직접 입력할 수 있습니다)', errors: [], warnings: [] };
      renderResult();
    }
    const raw = lsGet(DRAFT_KEY);
    if (raw) {
      try {
        const d = JSON.parse(raw);
        if (d && d.bundle && serialize(bundleFrom(d.bundle, S.bundle)) !== S.baseline) {
          S.draftOffer = d; renderDraftOffer();
        } else lsDel(DRAFT_KEY);
      } catch (e) { lsDel(DRAFT_KEY); }
    }
    renderTabContent();
  }

  // 다른 탭 모듈이 쓰는 공용 API
  window.GettEditor = { S, h, iconEl, itemName, itemStatus, pickItem, askText, modal, toast, changed, registerTab, selectTab, pickEntity, makeChecks, renderTabContent };

  boot();
})();
