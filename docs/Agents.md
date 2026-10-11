# Agent Instructions: ReelCheck

You are helping build ReelCheck, an Android floating-bubble app that fact-checks the Instagram reel currently on screen. Read `PLAN.md` first, then `decisions.md`, before doing anything.

## Rule 1: Record every decision in `decisions.md`

From now on, every meaningful decision goes into `decisions.md` as a new entry, using the format at the top of that file. This includes:
- choosing or changing a library, API, architecture, or file structure
- results of the M0 spikes (works / partial / blocked, and why)
- anything you tried that failed, and why you abandoned it
- trade-offs you made to save time

Rules for the log:
- Append only. Never edit or delete old entries. If a decision changes, add a new entry and mark the old one `Superseded by D-XXX`.
- Number entries sequentially (D-009, D-010, ...).
- Log the decision when you make it, not at the end of the session.
- If unsure whether something counts as a decision, log it.

## Rule 2: Follow the plan order

Work milestone by milestone as defined in `PLAN.md`. Do the M0 spikes first. Do not build later milestones until the spike results are logged. If a spike is blocked, stop and report instead of working around it silently.

## Rule 3: Project conventions

- Repo layout: `/app` (Android, Kotlin), `/backend` (Node + TypeScript + Fastify), `/docs`.
- Package manager: pnpm.
- Keep all Instagram-specific extraction code (caption, username, on-screen text) in a single module so it is easy to patch when Instagram changes.
- The Gemini API key must only ever exist on the backend, loaded from environment variables. Never commit secrets and never put the key in the app.
- Validate every model response against the JSON schema in `PLAN.md` before returning it.

## Rule 4: Privacy

- Process screenshots and audio in memory only. Do not write them to disk, store them, or log them.
- Only read what is on the user's own screen. No scraping, no downloading media from Instagram's servers.

## Rule 5: Keep changes small and verifiable

- Prefer small steps, each tested before moving on.
- After finishing a task, state what you changed, how you tested it, and what is next.
- Ask before adding new dependencies or changing the architecture in `PLAN.md`.