// Talks to the local JobPilot API. Content scripts can't (the job site's page origin and Chrome's local-network
// rules block it), so they send messages here and this worker calls the API with the user's token.
const API = 'http://localhost:8080/api';

async function call(path, opts = {}) {
  const { token } = await chrome.storage.local.get('token');
  if (!token) throw new Error('Open JobPilot (http://localhost:5173) and sign in once, so the extension can use your account.');
  let res;
  try {
    res = await fetch(API + path, { ...opts, headers: { Authorization: 'Bearer ' + token } });
  } catch {
    throw new Error("Can't reach JobPilot on this PC. Is it running? (start.ps1)");
  }
  if (res.status === 401) throw new Error('Your JobPilot sign-in expired. Open http://localhost:5173 and sign in again.');
  if (!res.ok) {
    let m = 'JobPilot error ' + res.status;
    try { m = (await res.json()).message || m } catch { /* not JSON */ }
    throw new Error(m);
  }
  return res;
}

async function resume(id) {
  const res = await call(`/applications/${id}/resume`);
  const cd = res.headers.get('Content-Disposition') || '';
  const star = cd.match(/filename\*=UTF-8''([^;]+)/i);
  const plain = cd.match(/filename="([^"]+)"/i);
  const name = star ? decodeURIComponent(star[1]) : plain ? plain[1] : 'Resume.pdf';
  const buf = new Uint8Array(await res.arrayBuffer());
  let s = '';
  for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode(...buf.subarray(i, i + 0x8000));
  return { name, type: res.headers.get('Content-Type') || 'application/pdf', b64: btoa(s) };
}

chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  (async () => {
    try {
      if (msg.type === 'forPage') reply({ data: await (await call('/applications/for-page?url=' + encodeURIComponent(msg.url))).json() });
      else if (msg.type === 'resume') reply(await resume(msg.id));
      else if (msg.type === 'submitted') reply({ data: await (await call(`/applications/${msg.id}/submitted`, { method: 'POST' })).json() });
      else reply({ error: 'unknown message' });
    } catch (e) {
      reply({ error: e.message });
    }
  })();
  return true; // reply asynchronously
});
