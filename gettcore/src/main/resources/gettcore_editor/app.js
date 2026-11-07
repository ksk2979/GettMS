/* Gett 편집기 — 순수 JS, 외부 의존성 없음.
 *
 * 구조 (한 파일 안의 섹션):
 *   1. 공용 도구      h(), api(), toast(), askText()
 *   2. 상태           S, install(), serialize(), changed()
 *   3. 아이템 정보    카탈로그 조회, 아이콘, 상태(설치 안 됨 / 아이콘 없음)
 *   4. 아이템 선택기  pickItem({allowTags}) -> Promise<id | null>   (다른 탭에서도 재사용)
 *   5. 탭 등록        registerTab({id, title, render(container) -> paint?, validate(bundle) -> string[]})
 *   6. 상점 탭
 *   7. 준비 중 탭     NPC·대화(Task 11), 퀘스트(Task 14)가 같은 방식으로 registerTab 한다
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
    bundle: null,       // { shops, dialogs, npcs, quests } — 서버가 준 모양 그대로, 편집 안 하는 부분은 손대지 않는다
    refs: { advancements: [], missingItems: [], missingEntities: [] },
    baseline: '',       // 마지막으로 서버와 맞춘 상태의 직렬화
    catalog: { items: [], entities: [], itemTags: [], entityTags: [] },
    catalogOk: false,
    tab: 'shops',
    ui: { shopId: null, tabFilter: '' },
    result: null,       // 마지막 저장 결과 {kind, title, errors[], warnings[]}
    draftOffer: null,   // 복원 제안 {base, bundle}
    saving: false,
  };

  function serialize(bundle) {
    return JSON.stringify({ shops: bundle.shops, dialogs: bundle.dialogs, npcs: bundle.npcs, quests: bundle.quests });
  }

  function isDirty() { return serialize(S.bundle) !== S.baseline; }

  function install(snap) {
    S.refs = Object.assign({ advancements: [], missingItems: [], missingEntities: [] }, snap.refs || {});
    S.bundle = {
      shops: snap.shops || {},
      dialogs: snap.dialogs || {},
      npcs: snap.npcs || EMPTY_NPCS(),
      quests: snap.quests || {},
    };
    for (const s of Object.values(S.bundle.shops)) {
      s.tabs = s.tabs || [];
      s.entries = s.entries || [];
    }
    S.baseline = serialize(S.bundle);
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

  /* ===================== 7. 준비 중 탭 ===================== */

  function placeholderTab(id, title, note) {
    return {
      id, title,
      validate() { return []; },
      render(container) { container.append(h('div', { class: 'card' }, h('h2', null, title), h('p', { class: 'muted' }, note))); return null; },
    };
  }
  registerTab(placeholderTab('npcs', 'NPC·대화', '준비 중입니다.'));
  registerTab(placeholderTab('quests', '퀘스트', '준비 중입니다.'));

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
          S.bundle = { shops: d.bundle.shops || {}, dialogs: d.bundle.dialogs || {}, npcs: d.bundle.npcs || EMPTY_NPCS(), quests: d.bundle.quests || {} };
          for (const s of Object.values(S.bundle.shops)) { s.tabs = s.tabs || []; s.entries = s.entries || []; }
          S.draftOffer = null; renderDraftOffer();
          S.ui.shopId = Object.keys(S.bundle.shops)[0] || null;
          renderTabContent(); scheduleDraft();
        } }, '복원'),
        h('button', { class: 'small', onclick: () => { lsDel(DRAFT_KEY); S.draftOffer = null; renderDraftOffer(); } }, '버리기'))));
  }

  function renderShell() {
    elTabs = h('div', { class: 'tabs', role: 'tablist' }, TABS.map((t) => h('button', {
      role: 'tab', 'aria-selected': String(t.id === S.tab),
      onclick: () => { S.tab = t.id; elTabs.querySelectorAll('button').forEach((b, i) => b.setAttribute('aria-selected', String(TABS[i].id === S.tab))); renderTabContent(); },
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
        if (d && d.bundle && JSON.stringify({ shops: d.bundle.shops, dialogs: d.bundle.dialogs, npcs: d.bundle.npcs, quests: d.bundle.quests }) !== S.baseline) {
          S.draftOffer = d; renderDraftOffer();
        } else lsDel(DRAFT_KEY);
      } catch (e) { lsDel(DRAFT_KEY); }
    }
    renderTabContent();
  }

  // 다른 탭 모듈이 쓰는 공용 API
  window.GettEditor = { S, h, iconEl, itemName, itemStatus, pickItem, askText, modal, toast, changed, registerTab, makeChecks, renderTabContent };

  boot();
})();
