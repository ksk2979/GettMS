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
 *   7c. 보스 지점 탭, 펫 설정 탭, 보호구역 탭
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
    bundle: null,       // { shops, dialogs, npcs, quests, boss_points, pets, protect } — 서버가 준 모양 그대로 (pets·boss_points·protect만 기본값을 채움)
    refs: { advancements: [], missingItems: [], missingEntities: [] },
    baseline: '',       // 마지막으로 서버와 맞춘 상태의 직렬화
    catalog: { items: [], entities: [], itemTags: [], entityTags: [], bossTypes: [], petKinds: [] },
    catalogOk: false,
    tab: 'shops',
    ui: { shopId: null, tabFilter: '', npcName: null, dialogId: null, questLine: null, quest: null, questId: null },
    result: null,       // 마지막 저장 결과 {kind, title, errors[], warnings[]}
    draftOffer: null,   // 복원 제안 {base, bundle}
    saving: false,
  };

  let savedQuestIds = new Set();

  function serialize(bundle) {
    return JSON.stringify({ shops: bundle.shops, dialogs: bundle.dialogs, npcs: bundle.npcs, quests: bundle.quests,
      boss_points: bundle.boss_points, pets: stripEmptyPetOverrides(bundle.pets), protect: bundle.protect });
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

  /** 전역 획득 확률 표: 표가 없으면 예전 단일 obtainPercent(네 등급 모두), 그것도 없으면 기본값. 빠진 등급은 기본값. */
  function normalizeObtain(def, table, legacy) {
    const src = table || (typeof legacy === 'number' ? allTiers(legacy) : {});
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

  /** 종별 획득 확률 덮어쓰기: 있는 등급만. 표가 없고 예전 obtainPercent > 0 이면 네 등급 모두 그 값. */
  function partialObtain(table, legacy) {
    const src = table || (typeof legacy === 'number' && legacy > 0 ? allTiers(legacy) : {});
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
        obtainChance: partialObtain(s.obtainChance, s.obtainPercent),
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

  /** 입력 요소에 검증 함수를 묶는다. paint 때마다 check()가 오류 문장을 돌려주면 빨갛게 표시. */
  function makeChecks() {
    const list = [];
    return {
      add(el, check) { list.push({ el, check }); return el; },
      paint() {
        for (const { el, check } of list) {
          const m = check();
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
      const head = h('div', { class: 'card' }, h('div', { class: 'field' },
        h('label', null, '이름'), nameInput, h('span', { class: 'muted' }, 'id: ' + shop.id), h('span', { class: 'spacer' }), delShop));

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
    ['open_enchant', '마법부여'], ['quest', '퀘스트'], ['close', '닫기'],
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

  let entityIndex = null;
  function entityName(id) {
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
    return out;
  }

  function validateQuests(bundle) {
    const out = [];
    for (const [key, line] of Object.entries(bundle.quests)) {
      const lp = `퀘스트 라인 ${line.name || key}`;
      const m = questRules.lineKey(key, line);
      if (m) out.push(`${lp}: ${m}`);
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
    const o = Object.assign({ allowTags: true, title: '엔티티 선택' }, opts);
    return modal(o.title, (box, close) => {
      let query = '';
      const search = h('input', { type: 'text', class: 'grow', placeholder: '이름 또는 id 검색 (예: 좀비, zombie)', 'aria-label': '검색' });
      const body = h('div', { class: 'body' });
      const direct = h('input', { type: 'text', placeholder: o.allowTags ? '직접 입력: mod:entity 또는 #mod:tag' : '직접 입력: mod:entity', 'aria-label': '직접 입력' });
      const matches = (text) => query.toLowerCase().split(/\s+/).filter(Boolean).every((w) => text.includes(w));
      function draw() {
        const ents = (S.catalog.entities || []).filter((e) => !query || matches(((e.name || '') + ' ' + e.id).toLowerCase()));
        const tags = o.allowTags ? (S.catalog.entityTags || []).map((t) => t.replace(/^#/, '')).filter((t) => !query || matches(t.toLowerCase())) : [];
        body.replaceChildren(
          ents.length === 0 && tags.length === 0 ? h('p', { class: 'muted' }, S.catalogOk ? '검색 결과가 없습니다.' : '엔티티 목록을 불러오지 못했습니다. 아래에서 id를 직접 입력하세요.') : null,
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
        b.quests[id] = { id, name, quests: [] };
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
      const lineSettings = h('div', { class: 'card' }, h('h2', null, '라인 설정'),
        h('div', { class: 'field' }, h('label', null, '이름'), lineName, h('span', { class: 'muted' }, 'id: ' + ui.questLine)),
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
        const nq = { id: newQuestId(line), chapter: last ? (last.chapter || '') : '', title: '새 퀘스트', description: '',
          start: { type: 'auto' }, complete: { type: 'auto' }, objectives: [], rewards: { gold: 0, items: [], xp: 0 }, completeText: '' };
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
              const id = await pickEntity({ title: '처치할 엔티티 또는 태그' });
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
        const rItems = r0.items || [];
        const itemRows = rItems.map((it, i) => {
          const st = itemStatus(it.item);
          const btn = checks.add(h('button', { class: 'icon-btn', title: '아이템 바꾸기', onclick: async () => {
            const id = await pickItem({ title: '보상 아이템' });
            if (id) { it.item = id; changed(); rerender(); }
          } }, iconEl(it.item, 28), h('span', null, itemName(it.item))), () => questRules.rewardItem(it));
          const cnt = checks.add(h('input', { type: 'number', min: 1, step: 1, 'aria-label': '개수', style: 'width:5rem',
            value: it.count === undefined ? 1 : (Number.isNaN(it.count) ? '' : it.count),
            oninput: (e) => { it.count = e.target.value === '' ? NaN : Number(e.target.value); changed(); } }), () => questRules.rewardCount(it));
          return h('div', { class: 'field' }, btn, h('span', { class: 'id' }, it.item), cnt,
            st === 'missing' ? h('span', { class: 'tag bad' }, '설치되지 않은 아이템 (저장하면 빠짐)') : null, h('span', { class: 'spacer' }),
            h('button', { class: 'danger small', 'aria-label': '보상 삭제', onclick: () => { rw().items.splice(i, 1); changed(); rerender(); } }, '삭제'));
        });
        const addReward = h('button', { onclick: async () => {
          const id = await pickItem({ title: '보상 아이템' });
          if (!id) return;
          const r = rw();
          (r.items = r.items || []).push({ item: id, count: 1 });
          changed(); rerender();
        } }, '+ 보상 아이템');
        mid.push(h('div', { class: 'card' }, h('h2', null, '보상'),
          h('div', { class: 'field' }, h('label', null, '골드'), numField('gold', '골드', questRules.gold), h('label', null, '경험치 레벨'), numField('xp', '경험치', questRules.xp)),
          itemRows, h('div', { class: 'field' }, addReward)));
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
        screenBox.append(h('strong', null, q.title || q.id), h('p', null, q.description || ''),
          h('div', { class: 'muted' }, '보상: ' + (parts.length ? parts.join(', ') : '없음')),
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
       * 포획: 기본·최대 둘 다 비면 그 등급을 ballChance 에서 뺀다. 하나만 있으면 둘 다 그 값으로 저장한다.
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
          checks.add(ph(baseEl, () => ball().base), () => (s.ballChance[k] ? petRules.percent(s.ballChance[k].base) : ''));
          checks.add(ph(maxEl, () => ball().max), () => (s.ballChance[k] ? petRules.chanceMax(s.ballChance[k]) : ''));
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
