# Contributing

Read ARCHITECTURE.md, then MESSAGING-PIPELINE.md, then INVARIANTS.md — in
that order. The invariants are load-bearing: each one is a shipped incident.

Ground rules distilled from this app's history:
- Diagnose from field logs and source (AOSP, Bugle, the vendored engine),
  never from assumption. If the root cause isn't provable, ship
  instrumentation first, not a blind fix.
- One concern per release. Regressions get reverted at the wire level even
  when the display half survives.
- Keypad phones are the design center: every screen must be fully drivable
  by D-pad + softkeys; test focus paths, not just taps.
- The telephony provider is shared ground — write rows other apps can read
  (thread ids, addr rows, no zero-part MMS), and never delete provider rows
  on heuristics.
- Menus: list long-press and in-thread options stay identical (see
  INVARIANTS #10).
