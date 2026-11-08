// JobPilot Autofill: on a Lever or Greenhouse application form that JobPilot prepared, fills the answers (and attaches
// the resume) as soon as the form loads, in the user's own browser; "Fill with JobPilot" fills again. In a tab the
// auto-mode queue opened (background.js) with "Submit for me" on, it also presses Submit; a CAPTCHA challenge or
// anything it couldn't fill goes to the user. On the site's thank-you page the application is marked as applied.
// Same field logic as the server's BrowserFiller, ported to the page.
(() => {
  if (window.__jobpilot) return;
  window.__jobpilot = true;

  const ask = (msg) => new Promise((res) => chrome.runtime.sendMessage(msg, (r) => res(r || { error: chrome.runtime.lastError?.message || 'No answer from the extension' })));
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const eq = (a, b) => (a || '').trim().toLowerCase() === (b || '').trim().toLowerCase();
  const q = (s) => s.replace(/["\\]/g, '\\$&');
  const THANKS_URL = /\/(thanks|confirmation|thank-you|thank_you)(\/|\?|$)/;
  const THANKS_TEXT = /thank you for applying|application (has been )?(submitted|received)|thanks for applying|we('ve| have) received your application/i;

  // ---------- UI ----------
  const host = document.createElement('div');
  host.style.cssText = 'position:fixed;right:20px;bottom:20px;z-index:2147483647;font:14px system-ui,sans-serif;max-width:340px';
  const shadow = host.attachShadow({ mode: 'closed' });
  shadow.innerHTML = `<style>
    button{all:unset;cursor:pointer;background:#111;color:#fff;padding:10px 16px;border-radius:999px;font-weight:600;box-shadow:0 4px 14px #0003}
    button:disabled{opacity:.6;cursor:wait}
    .p{background:#fff;color:#111;border:1px solid #ddd;border-radius:12px;padding:12px 14px;margin-bottom:8px;box-shadow:0 4px 14px #0002;line-height:1.4}
    .p b{display:block;margin-bottom:4px} .p ul{margin:6px 0 0;padding-left:18px;color:#a33} .x{float:right;cursor:pointer;color:#888}
  </style><div id="panel"></div><button id="go">Fill with JobPilot</button>`;
  const btn = shadow.getElementById('go');
  const panelEl = shadow.getElementById('panel');
  function panel(title, items = [], ok = false) {
    panelEl.innerHTML = '';
    const d = document.createElement('div');
    d.className = 'p';
    d.innerHTML = '<span class="x">✕</span><b></b>';
    d.querySelector('b').textContent = title;
    d.querySelector('b').style.color = ok ? '#176b2c' : '#111';
    d.querySelector('.x').onclick = () => (panelEl.innerHTML = '');
    if (items.length) {
      const ul = document.createElement('ul');
      for (const t of items) { const li = document.createElement('li'); li.textContent = t; ul.appendChild(li) }
      d.appendChild(ul);
    }
    panelEl.appendChild(d);
  }

  // ---------- finding fields ----------
  function find(f) {
    if (f.key.startsWith('css:')) return document.querySelector(f.key.slice(4));
    const k = q(f.key);
    const el = document.querySelector(`[name="${k}"]:not([type=hidden]), [id="${k}"]:not([type=hidden])`);
    if (el) return el;
    const want = (f.label || '').trim().toLowerCase().slice(0, 120);
    if (!want) return null;
    for (const l of document.querySelectorAll('label')) {
      if (!l.innerText.trim().toLowerCase().startsWith(want)) continue;
      const c = l.control || l.querySelector('input,textarea,select') || (l.htmlFor && document.getElementById(l.htmlFor));
      if (c) return c;
    }
    return null;
  }

  // React-controlled inputs ignore a plain .value assignment: use the native setter, then fire the events.
  function setText(el, v, blur = true) {
    const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    if (blur) el.dispatchEvent(new Event('blur', { bubbles: true }));
    return el.value === v;
  }

  // Greenhouse's React selects. Live findings (Okta, 2026-11-08): the menu opens on mouse-UP (a mousedown alone does
  // nothing), and the phone widget keeps ~244 hidden role=option country rows on the page, so options must come from
  // this input's own listbox (aria-controls). Searchable ones (country, city) get typed into if nothing matches yet.
  const norm = (x) => (x || '').trim().toLowerCase();
  async function combo(el, v) {
    const ctl = el.closest('.select__control') || el;
    for (const t of ['mousedown', 'mouseup', 'click']) ctl.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, button: 0 }));
    let typed = false;
    for (let i = 0; i < 24; i++) {
      await sleep(250);
      const id = el.getAttribute('aria-controls');
      const opts = id && document.getElementById(id) ? [...document.getElementById(id).querySelectorAll('[role=option]')] : [];
      const o = opts.find((o) => norm(o.innerText) === norm(v))
        || (typed && (opts.find((o) => norm(o.innerText).startsWith(norm(v))) || opts.find((o) => norm(o.innerText).includes(norm(v.split(',')[0])))));
      if (o) {
        o.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
        o.click();
        return true;
      }
      if (!typed && i >= 2) { setText(el, v.split(',')[0], false); typed = true }
    }
    el.blur();
    return false;
  }

  // Lever's location box keeps only a place picked from its suggestions, stored as {name,id} in a hidden field. Its
  // widget ignores synthetic typing, so ask the same search it uses and set both fields.
  async function leverLocation(el, v) {
    const hidden = document.querySelector('[name=selectedLocation]');
    if (!hidden) return false;
    let hits = [];
    try { hits = await (await fetch('/searchLocations?text=' + encodeURIComponent(v))).json() } catch { /* offline */ }
    if (!hits.length) { const city = v.split(',')[0]; if (city !== v) try { hits = await (await fetch('/searchLocations?text=' + encodeURIComponent(city))).json() } catch { /* */ } }
    if (!hits[0]?.id) return false;
    setText(el, hits[0].name, false);
    hidden.value = JSON.stringify({ name: hits[0].name, id: hits[0].id });
    return true;
  }

  async function choose(f, values) {
    const k = q(f.key);
    const boxes = [...document.querySelectorAll(`input[type=radio][name="${k}"], input[type=checkbox][name="${k}"], input[type=checkbox][name="${k}[]"]`)];
    if (boxes.length) {
      let done = 0;
      for (const b of boxes) {
        const text = (b.closest('label') || b.parentElement)?.innerText || '';
        if (values.some((x) => eq(x, b.value) || eq(x, text))) { if (!b.checked) b.click(); done++ }
      }
      return done === values.length;
    }
    const el = find(f);
    if (!el) return false;
    if (el.tagName === 'SELECT') {
      const o = [...el.options].find((o) => eq(o.text, values[0]) || eq(o.value, values[0]));
      if (!o) return false;
      el.value = o.value;
      el.dispatchEvent(new Event('change', { bubbles: true }));
      await sleep(800); // some selects (survey country) re-render the questions after them
      return true;
    }
    for (const v of values) if (!(await combo(el, v))) return false;
    return true;
  }

  async function attach(f, appId) {
    const k = q(f.key);
    const input = document.querySelector(`input[type=file][name="${k}"], input[type=file][id="${k}"]`) || document.querySelector('input[type=file]');
    if (!input) return false;
    const r = await ask({ type: 'resume', id: appId });
    if (r.error) throw new Error(r.error);
    const bytes = Uint8Array.from(atob(r.b64), (c) => c.charCodeAt(0));
    const dt = new DataTransfer();
    dt.items.add(new File([bytes], r.name, { type: r.type }));
    input.files = dt.files;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  }

  async function fillField(f, appId) {
    if (f.type === 'FILE') return attach(f, appId);
    if (f.type === 'SELECT') return choose(f, [f.value]);
    if (f.type === 'MULTISELECT') return choose(f, f.value.split(',').map((s) => s.trim()));
    const el = find(f);
    if (!el) return false;
    if (el.getAttribute('role') === 'combobox') return combo(el, f.value);
    if ((el.className || '').toString().includes('location-input')) return leverLocation(el, f.value);
    return setText(el, f.value);
  }

  // ---------- fill ----------
  // returns the problems list when it filled, or a message why it didn't
  async function fill(quiet = false) {
    btn.disabled = true;
    btn.textContent = 'Filling…';
    try {
      const r = await ask({ type: 'forPage', url: location.href });
      if (r.error) return quiet ? r.error : panel(r.error) || r.error;
      const app = r.data;
      if (app.status === 'SUBMITTED') return panel('You already applied to this job (JobPilot shows it as applied).') || 'already applied';
      if (app.missing > 0) return panel(`JobPilot needs ${app.missing} answer(s) from you first. Open the application in JobPilot, answer them, then press Fill again.`) || 'missing answers';
      const problems = [];
      const texts = [];
      for (const f of app.fields) {
        if (f.type !== 'FILE' && !f.value) continue;
        let ok = false;
        try { ok = await fillField(f, app.id) } catch (e) { ok = false; if (f.type === 'FILE') problems.push('Resume: ' + e.message) }
        if (!ok && !(f.type === 'FILE' && problems.length)) problems.push(f.label);
        if (ok && (f.type === 'TEXT' || f.type === 'TEXTAREA')) texts.push(f);
        if (!ok) { const el = find(f); if (el) el.style.outline = '3px solid #e8590c' }
      }
      // Greenhouse can wipe a field when React finishes loading: re-check and retype once
      await sleep(1500);
      for (const f of texts) {
        const el = find(f);
        if (el && el.getAttribute('role') !== 'combobox' && !(el.className || '').toString().includes('location-input') && el.value !== f.value) setText(el, f.value);
      }
      chrome.storage.local.set({ lastFill: { id: app.id, at: Date.now() } });
      if (problems.length) panel('Filled. Please do these yourself, then check the form and submit:', problems);
      else panel('Filled. Check everything, solve the CAPTCHA if there is one, and press Submit.', [], true);
      return problems;
    } finally {
      btn.disabled = false;
      btn.textContent = 'Fill with JobPilot';
    }
  }

  // ---------- applied detection ----------
  async function markIfThanks() {
    if (!THANKS_URL.test(location.pathname) && !THANKS_TEXT.test(document.body?.innerText || '')) return false;
    let r = await ask({ type: 'forPage', url: location.href });
    let id = r.data?.id;
    if (!id) { // embedded forms' thank-you pages don't carry the job id: use the form filled in the last hour
      const { lastFill } = await chrome.storage.local.get('lastFill');
      if (lastFill && Date.now() - lastFill.at < 3600_000) id = lastFill.id;
    }
    if (!id || r.data?.status === 'SUBMITTED') return true;
    r = await ask({ type: 'submitted', id });
    if (!r.error) { document.documentElement.appendChild(host); btn.style.display = 'none'; panel('Marked as applied in JobPilot ✓', [], true) }
    return true;
  }

  // ---------- auto mode: submit ----------
  function submitButton() {
    const vis = (b) => b.offsetParent && !b.disabled;
    const all = [...document.querySelectorAll('#btn-submit, button[type=submit], input[type=submit], button')].filter(vis);
    return all.find((b) => /submit|send application/i.test(b.innerText || b.value || ''));
  }

  async function auto(role) {
    const res = await fill(true);
    if (!Array.isArray(res)) return ask({ type: 'needsYou', reason: "Couldn't fill this form: " + res });
    if (res.length) return ask({ type: 'needsYou', reason: 'Fill these yourself, then submit: ' + res.join(', ') });
    if (!role.autoSubmit) return ask({ type: 'needsYou', reason: 'Filled. Check it and press Submit (or close the tab to skip).' });
    const b = submitButton();
    if (!b) return ask({ type: 'needsYou', reason: "Filled, but couldn't find the Submit button. Please press it." });
    await sleep(1500);
    b.click();
    // a passing site navigates to its thank-you page (the script restarts there); otherwise a CAPTCHA challenge or a
    // form error is showing: that's for the user
    for (let i = 0; i < 30; i++) { await sleep(1000); if (await markIfThanks()) return }
    ask({ type: 'needsYou', reason: 'Pressed Submit but no confirmation yet. Solve the CAPTCHA or fix the form, then submit.' });
  }

  // ---------- start ----------
  (async () => {
    if (await markIfThanks()) return;
    // the form can render late (Greenhouse is a React app): wait for the resume upload field
    for (let i = 0; i < 40; i++) {
      if (document.querySelector('input[type=file]')) {
        btn.onclick = () => fill();
        document.documentElement.appendChild(host);
        await sleep(1000); // let React finish hydrating before typing
        const role = await ask({ type: 'role' });
        return role.auto ? auto(role) : fill(true);
      }
      await sleep(500);
    }
  })();
})();
