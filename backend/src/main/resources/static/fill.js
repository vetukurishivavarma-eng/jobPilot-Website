// JobPilot Autofill: on a Lever or Greenhouse application form that JobPilot prepared, fills the answers (and attaches
// the resume) as soon as the form loads, in the user's own browser; "Fill with JobPilot" fills again. In a tab the
// auto-mode queue opened (background.js) with "Submit for me" on, it also presses Submit; a CAPTCHA challenge or
// anything it couldn't fill goes to the user. On the site's thank-you page the application is marked as applied.
// Same field logic as the server's BrowserFiller, ported to the page.
// SmartRecruiters can't be read in advance: here the questions are read off the page (inside its web components'
// shadow DOM), the server answers them (POST page-form), then they're filled; its "Next" pages are walked in auto mode.
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

  // ---------- shadow DOM ----------
  const SR = location.host === 'jobs.smartrecruiters.com' && location.pathname.includes('/oneclick-ui/');
  function deepAll() {
    const out = [];
    const walk = (r) => { for (const el of r.querySelectorAll('*')) { out.push(el); if (el.shadowRoot) walk(el.shadowRoot) } };
    walk(document);
    return out;
  }
  const visible = (el) => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0 };
  const unstar = (t) => (t || '').replace(/\s+/g, ' ').trim();
  function labelOf(el) {
    const l = el.labels?.[0]?.innerText || el.getAttribute('aria-label') || el.getAttribute('splarialabel') || el.closest?.('label')?.innerText || '';
    return unstar(l);
  }
  // a component's id (first-name-input) is stable; generated ones (spl-form-element_7) aren't, so those go by label
  function keyOf(host, label) {
    return host.id && !/^spl-form-element_|^spl-dropzone/.test(host.id) ? 'id:' + host.id : 'label:' + label.replace(/\*$/, '').trim().toLowerCase();
  }

  /** The questions on the current SmartRecruiters page, as the server's FormField shape. */
  function scanPage() {
    const fields = [], seen = new Set();
    const add = (f) => { if (f.label && !seen.has(f.key)) { seen.add(f.key); fields.push(f) } };
    const els = deepAll();
    for (const el of els) {
      if (!visible(el)) continue;
      const host = el.getRootNode().host || el;
      if ((el.tagName === 'INPUT' && /^(text|email|tel|url|number|)$/.test(el.type)) || el.tagName === 'TEXTAREA') {
        if (/search/i.test(el.getAttribute('aria-label') || '')) continue; // dropdown search boxes
        const label = labelOf(el);
        add({ key: keyOf(host, label), label: label.replace(/\*$/, '').trim(), type: el.tagName === 'TEXTAREA' ? 'TEXTAREA' : 'TEXT', required: /\*$/.test(label) || el.required, options: [] });
      } else if (el.tagName === 'SPL-SELECT' && !/phone-field/.test(el.className)) {
        const label = labelOf(el) || unstar(el.closest('spl-form-field')?.innerText || '');
        const options = [...el.querySelectorAll('spl-select-option')].map((o) => unstar(o.innerText)).filter(Boolean);
        add({ key: keyOf(el, label), label: label.replace(/\*$/, '').trim(), type: 'SELECT', required: /\*$/.test(label), options });
      } else if (el.tagName === 'INPUT' && (el.type === 'radio' || el.type === 'checkbox') && el.name) {
        const group = els.filter((x) => x.tagName === 'INPUT' && x.name === el.name);
        const legend = el.closest('fieldset')?.querySelector('legend')?.innerText || el.closest('[role=radiogroup],[role=group]')?.getAttribute('aria-label') || labelOf(el);
        add({ key: 'name:' + el.name, label: unstar(legend).replace(/\*$/, '').trim(), type: el.type === 'radio' ? 'SELECT' : 'MULTISELECT',
          required: /\*$/.test(unstar(legend)) || el.required, options: group.map((x) => unstar((x.closest('label') || x.parentElement)?.innerText || x.value)) });
      }
    }
    if (els.some((e) => e.tagName === 'INPUT' && e.type === 'file')) add({ key: 'file:resume', label: 'Resume', type: 'FILE', required: true, options: [] });
    return fields;
  }

  // ---------- finding fields ----------
  function find(f) {
    if (f.key.startsWith('id:')) {
      const host = deepAll().find((e) => e.id === f.key.slice(3));
      return host?.shadowRoot?.querySelector('input,textarea') || host || null;
    }
    if (f.key.startsWith('label:')) {
      const want = f.key.slice(6);
      return deepAll().find((e) => (e.tagName === 'INPUT' || e.tagName === 'TEXTAREA' || e.tagName === 'SPL-SELECT') && visible(e)
        && labelOf(e).replace(/\*$/, '').trim().toLowerCase() === want) || null;
    }
    if (f.key.startsWith('name:')) return deepAll().find((e) => e.tagName === 'INPUT' && e.name === f.key.slice(5)) || null;
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
    const proto = Object.getPrototypeOf(el); // the element's own (works inside shadow roots)
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
    el.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
    el.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
    if (blur) el.dispatchEvent(new Event('blur', { bubbles: true, composed: true }));
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

  // SmartRecruiters' <spl-select>: open it, click the option with the wanted text
  async function splSelect(el, v) {
    el.click();
    await sleep(400);
    const o = [...el.querySelectorAll('spl-select-option')].find((x) => eq(unstar(x.innerText), v));
    if (!o) { el.click(); return false }
    o.click();
    await sleep(300);
    return true;
  }

  async function choose(f, values) {
    if (f.key.startsWith('name:')) {
      let done = 0;
      for (const b of deepAll().filter((e) => e.tagName === 'INPUT' && e.name === f.key.slice(5))) {
        const text = unstar((b.closest('label') || b.parentElement)?.innerText || b.value);
        if (values.some((x) => eq(x, text) || eq(x, b.value))) { if (!b.checked) b.click(); done++ }
      }
      return done === values.length;
    }
    const sel = find(f);
    if (sel?.tagName === 'SPL-SELECT') return splSelect(sel, values[0]);
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
    // SmartRecruiters: every dropzone (the first, "Easy Apply", also reads the resume into Experience/Education)
    const inputs = SR ? deepAll().filter((e) => e.tagName === 'INPUT' && e.type === 'file')
      : [document.querySelector(`input[type=file][name="${k}"], input[type=file][id="${k}"]`) || document.querySelector('input[type=file]')].filter(Boolean);
    if (!inputs.length) return false;
    const r = await ask({ type: 'resume', id: appId });
    if (r.error) throw new Error(r.error);
    const bytes = Uint8Array.from(atob(r.b64), (c) => c.charCodeAt(0));
    for (const input of inputs) {
      const dt = new DataTransfer();
      dt.items.add(new File([bytes], r.name, { type: r.type }));
      input.files = dt.files;
      input.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
      input.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
      if (SR) await sleep(6000); // let it read the resume before the next step
    }
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
    if (SR && el.type === 'tel') return setText(el, f.value.replace(/^\+?91[\s-]*/, '').replace(/[^0-9]/g, ''));
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
      let app = r.data;
      if (app.status === 'SUBMITTED') return panel('You already applied to this job (JobPilot shows it as applied).') || 'already applied';
      if (SR) {
        btn.textContent = 'Reading the form…';
        const page = scanPage();
        const rr = await ask({ type: 'pageForm', id: app.id, fields: page });
        if (rr.error) return quiet ? rr.error : panel(rr.error) || rr.error;
        const keys = new Set(page.map((f) => f.key));
        app = { ...rr.data, fields: rr.data.fields.filter((f) => keys.has(f.key)) };
        app.fields.sort((a, b) => (a.type === 'FILE' ? -1 : b.type === 'FILE' ? 1 : 0));
        app.missing = app.fields.filter((f) => f.required && !f.value && f.type !== 'FILE').length;
        btn.textContent = 'Filling…';
      }
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
  function submitButton(re = /submit|send application/i) {
    const vis = (b) => (b.offsetParent || visible(b)) && !b.disabled;
    const all = SR ? deepAll().filter((e) => e.tagName === 'BUTTON' || e.tagName === 'SPL-BUTTON').filter(vis)
      : [...document.querySelectorAll('#btn-submit, button[type=submit], input[type=submit], button')].filter(vis);
    return all.find((b) => re.test((b.innerText || b.value || b.getRootNode().host?.innerText || '').trim()));
  }

  async function auto(role) {
    let res = await fill(true);
    for (let pageNo = 0; SR && Array.isArray(res) && !res.length && pageNo < 5; pageNo++) {
      const next = !submitButton() && submitButton(/^next$/i);
      if (!next) break;
      const before = JSON.stringify(scanPage().map((f) => f.key));
      next.click();
      await sleep(4000);
      if (JSON.stringify(scanPage().map((f) => f.key)) === before)
        return ask({ type: 'needsYou', reason: 'SmartRecruiters wants something on this page (often Experience or Education: press Add). Then press Next.' });
      res = await fill(true);
    }
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
      if (document.querySelector('input[type=file]') || (SR && deepAll().some((e) => e.tagName === 'INPUT' && e.type === 'file'))) {
        btn.onclick = () => fill();
        document.documentElement.appendChild(host);
        // Greenhouse's new boards re-render the whole document after loading, which removes our button: put it back
        setInterval(() => { if (!host.isConnected) document.documentElement.appendChild(host) }, 1000);
        await sleep(1000); // let React finish hydrating before typing
        const role = await ask({ type: 'role' });
        return role.auto ? auto(role) : fill(true);
      }
      await sleep(500);
    }
  })();
})();
