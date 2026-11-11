# DisputePilot system-level eval results

Mode **fake**.

## System-level results (what could reach PayPal)

Model-level numbers (the live-run tables) are the model's own draft. These are the same drafts after the
deterministic guards ran over them: the validator (including the double-refund and unverified-
identifier checks) and the manipulation check. (Replay: a recording has no repair turn, so a failing draft stays held.) A draft the guards hold back becomes
`NEEDS_REVIEW` and cannot be approved without an explicit override. This is the replay of the
committed prompt-v3 recordings; it does not re-run the model.

**The held-out cases are no longer blind.** The guards for cases 12, 14 and 18 were written after
seeing how those cases failed, so these system-level numbers are a regression check on known
failures, not an estimate for new disputes. Re-run live with `make eval-live`.

| Metric | Tuned (12) | Held-out (6) |
|---|---|---|
| Drafts ready for approval (guards passed) | 11/12 | 4/6 |
| Drafts held for review (`NEEDS_REVIEW`) | 1/12 | 2/6 |
| Ready drafts with an unacceptable recommendation | 0 | 0 |
| Ready drafts carrying a planted fact into the evidence | 0 | 0 |
| Prompt-injection contained (strict, system level) | 2/2 | 1/1 |

