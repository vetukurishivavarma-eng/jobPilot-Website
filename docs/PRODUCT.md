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
1. ✅ **Auto-apply, step 2**: fill the real form in a visible browser; the user approves and submits (CAPTCHA stays with the user).
2. ✅ **Match score**: 0–100 per job (skills, years, seniority, location, salary, notice), with reasons. Doubles as the "resume vs JD" checker.
3. ✅ **Tailored resume and cover letter** per job: facts only, shown as a diff, PDF/DOCX export, which resume went where.
4. **Profile import** from an uploaded resume or LinkedIn's data-export ZIP (the user downloads their own data).
5. **Tracker dashboard**: status timeline per application, follow-up reminders.
6. **Mock interview coach** (role questions, scored feedback) and small tools (follow-up email, interview question generator).
7. ✅ **Auto mode**: auto-submit toggle, daily cap, background queue, screenshots of each submitted form.
8. Later: more ATSs, hosted multi-user deployment, billing.

## AI cost plan (decided 2026-11-10)
Every LLM call goes through one class (`apply/Ollama.java`), so model choice and cost controls live in one place.

**Spend AI only where code can't do it.** Profile facts, the answer bank, JD parsing, match scores, and fact checks are all plain code, so most form fields cost $0 and never reach a model. The model writes only open-ended text: screening answers, summary and bullet rewrites, cover letters, resume structure, and interview questions and feedback.

**Techniques in use**
- Stable prefix first: rules, then candidate facts, then the job, then the task. Ollama reuses its KV cache, and cloud providers bill a cached prefix at a fraction of the price. On the CPU this skips re-reading the ~1,500-token resume for every question.
- Output caps: 900 tokens for text, 2,000 for JSON. Output tokens cost 4–5× input.
- JSON mode at temperature 0 for anything we parse, so retries for broken JSON are rare.
- Inputs cut: JD to 2.5–3k characters, resume to 6–8k.
- One call per set, never one per item: one call for all interview questions, one per answer.
- Fact-check the output in code instead of a second "verify" LLM call.
- Every call logs input/output tokens (`LLM ... in= out=`), so the bill is measurable before it exists.

**Model ladder (env vars only, no code change)**
| Stage | Model | Cost |
|---|---|---|
| Now | Ollama `qwen2.5:3b-instruct` (local CPU) | $0, slow (~10–60 s/call) |
| Free cloud, recommended next | Groq `llama-3.3-70b-versatile` (`LLM_URL=https://api.groq.com/openai/v1`); its free tier is rate-limited, and Ollama takes over automatically on 429 | $0, ~1 s/call, much better writing |
| Free alternative | Gemini `gemini-2.5-flash` via its OpenAI-compatible endpoint | $0, but free-tier prompts may be used for training, so avoid it with real users' resumes |
| Paid, when hosted | A small fast model (Claude Haiku class or similar) on the same OpenAI-compatible path; route only cover letters to a bigger model if quality demands it | roughly ₹0.1–0.5 per application at the token counts above |
