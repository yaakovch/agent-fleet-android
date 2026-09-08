# Compact Sessions restoration — 2026-09-08

The `.90` discovery release was prepared from `4f2843f4` without the pending
compact Sessions layout in the original Android checkout. `.91` and `.92`
inherited the larger cards. The owner requested restoration of that layout.

The restoration carries the compact Sessions header, host summary, cards and
search placement into the current release branch. Session cards retain Enter,
Return and More. Host badges scroll independently so New remains visible.
The configuration recovery and registry repair behavior from `.92` is retained.
Other sections keep their existing app bar.

This is Android presentation only. There is no shared protocol, fixture or
Windows workflow change; Android and Windows keep their existing session,
host-discovery and repair behavior. No new specification was requested.

`AgentFleetComposeTest.compactSessionsFitFourCardsAndKeepSearchAndActionsUsable`
uses a 393 × 740 dp content area at normal Android font scale. It requires
ordinary cards to remain at most 140 dp tall, four complete cards with usable
Enter actions above navigation, a visible New action, working search, exactly
one selected-session open and an available More action sheet. It captures a
screenshot and measured layout evidence. The test failed on the `.92` layout:
the first card measured 184.76 dp.
With the restoration, the same test passes with a 117.71 dp card and four
fully visible cards. The before and after screenshots were inspected.

The current release also retains the configuration-recovery, host-pairing and
packaged SSH terminal regressions. Full-suite and publication evidence is
recorded in the wtmux integration handoff and under
`experiments/compact-sessions-2026-09-08/` in that repository.
