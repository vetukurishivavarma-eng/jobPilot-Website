// Runs on the JobPilot web app: hands the signed-in user's token to the extension, so there's no second login.
let last;
function sync() {
  let t = null;
  try { t = localStorage.getItem('jobpilot.token') } catch { /* blocked storage */ }
  if (t !== last) { last = t; chrome.storage.local.set({ token: t }) }
}
sync();
setInterval(sync, 3000); // picks up a sign-in or sign-out without a reload
