# JobPilot — product plan

Benchmarked against aiapply.co (public pages read 2026-11-08). Their features are matched and improved here. Nothing is copied: code, wording and branding are all our own.

## What we do better
| | aiapply.co (as stated on its site) | JobPilot |
|---|---|---|
| Truth | Demo "rewritten" resume adds metrics that weren't in the input | Every generated line traces to profile/resume facts; diffs for the user to accept; the AI may reply "need input" instead of guessing |
| Submitting | Submits automatically; no stated per-application review | Approval per application by default; auto-submit is an opt-in toggle with a daily cap |
| Sites | "Supported platforms", none named | Named ATSs (Greenhouse, Lever, then Ashby/Workable/SmartRecruiters); no LinkedIn/Naukri scraping, so no account-ban risk |
| Screening questions | Not described | Answer bank: answer once, reused on every later form |
| CAPTCHA | Not addressed | Never bypassed; a visible browser hands it to the user |
| Cost / privacy | Opaque credits, GPT-4, uploads resumes and interview audio | Local-first, free local LLM (Ollama) or Claude by choice; data stays on the user's machine |

## Deliberately not built
- **Covert live-interview assistant** (answers fed during a real interview while hidden from the interviewer): it deceives the employer, and recording the call may need consent. We build an open mock-interview coach instead.
- "Undetectable AI" claims.

## Roadmap (each step ships on its own)
1. **Auto-apply, step 2**: fill the real form in a visible browser; the user approves and submits (CAPTCHA stays with the user). *(in progress)*
2. **Match score**: 0–100 per job (skills, years, seniority, location, salary, notice), with reasons. Doubles as the "resume vs JD" checker.
3. **Tailored resume and cover letter** per job: facts only, shown as a diff, PDF/DOCX export, which resume went where.
4. **Profile import** from an uploaded resume or LinkedIn's data-export ZIP (the user downloads their own data).
5. **Tracker dashboard**: status timeline per application, follow-up reminders.
6. **Mock interview coach** (role questions, scored feedback) and small tools (follow-up email, interview question generator).
7. **Auto mode**: auto-submit toggle, daily cap, background queue, screenshots of each submitted form.
8. Later: more ATSs, hosted multi-user deployment, billing.
