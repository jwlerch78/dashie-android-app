# `src/chickadeeStub/java` — the free-edition no-op source set

Compiled into the `chickadee*` flavors only. Mirrors the API of
`src/dashie/java` with no-op implementations, exactly as `speakeridStub` mirrors
`speakeridImpl`.

**Empty as of phase 2b.** Stubs arrive in 2c alongside the seam interfaces.

## Rules for a stub

1. **Same API, inert body.** Never a `TODO()` or a throw — a stub is a shipping
   code path, not a placeholder.
2. **Be loud, not silent.** Where a stub swallows a call that would have done
   something in Dashie, log a distinctive `DROP:` marker (CLAUDE.md "No silent
   drops"). Every bug caught quickly in the 2026-07 postmortem was caught by a
   loud drop; every one that survived was silent.
3. **No commercial strings.** Not even in comments — this source set ships in
   the published repo.
