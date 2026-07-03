# Nitflex — Development To-Do

A running list of development tasks. Move items between sections as work progresses.
Format: `- [ ]` open, `- [x]` done.

---

## 🔜 Now / In Progress

- [ ] **Nav revamp + My List tab** (mobile only) — _code complete, needs Android Studio build + on-device test_
  - [x] **Explore tab**: merge Movies + TV Shows behind a visible toggle (default = Movies); reuses existing Movies/TvShows ViewModels
  - [x] **My List tab**: 3 buckets — **Watchlist** (isFavorite), **Continue** (in-progress), **Reviewed** (rated)
  - [x] User **rating (1–10) + comment** on movies + TV shows: DB cols `userRating`/`userReview`, migration v8→v9, DAO `upsertReview`, rate/review bottom sheet
  - [x] Remove Favorites + Continue Watching rows from mobile Home (filtered in `HomeMobileFragment`; TV Home untouched)
  - [x] Nav plumbing: menu items + icons, nav graph destinations, bottom-nav visibility wiring
  - [ ] **Build in Android Studio + verify on device** (no JDK/SDK in WSL to compile here)
  - [ ] Optional polish: Explore prefetches both Movies & TV on open (double network); make TV tab lazy if it matters
- [ ] Finish the **Streamflix Reborn → Nitflex** rebrand
  - [ ] Update `README.md` (still titled "Streamflix Reborn", old repo links & credits)
  - [ ] Audit user-facing strings / app name references for leftover "Streamflix"
  - [ ] Replace logo / launcher icons where still using old branding

## 🐛 Bugs

- [ ] _(none tracked yet — add as found)_

## ✨ Features / Enhancements

- [ ] _(add planned features here)_

## 🧹 Tech Debt / Cleanup

- [ ] _(refactors, lint, dependency upgrades)_

## 📦 Build & Release

- [ ] Verify release signing flow end-to-end (`keystore.properties`)
- [ ] _(Play Store / GitHub release tasks)_

## 💡 Ideas / Backlog

- [ ] _(unscheduled ideas)_

---

## ✅ Done

- [x] Accept both TMDB v3 API key and v4 Read Access Token (fix 401)
- [x] Distinct debug app name "Nitflex Dev" + optional release signing
- [x] Fix watch-party crash on open (removed Material Components widgets)
- [x] Show app name as "Nitflex" instead of "Nitflex - debug"
