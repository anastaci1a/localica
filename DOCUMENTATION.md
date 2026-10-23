# Documentation standard

Apply this standard to maintained documentation, contributor instructions and templates. The [documentation map](docs/README.md) assigns each topic an authoritative home.

## Required standard

1. Lead with the outcome or decision. Use familiar words and one fact per sentence.
2. Keep paragraphs under 60 words and bullets under 30 words. Use tables for comparisons, parameters, ownership and outcomes. Avoid paragraph-sized bullet points.
3. Give each durable fact one authoritative home. Consolidate duplicate explanations, obsolete instructions, stale completion claims and repeated task histories. Link rather than restating.
4. Write for public readers. Do not refer to the user's personal name anywhere except the license. Describe decisions and responsibilities by project role.
5. Use LaTeX display blocks for mathematical formulas, relations, inequalities, vectors and matrices. Use actual fractions, component/label subscripts, nabla gradients and proper operators. Keep executable commands, API names and literal configuration values in code formatting.
6. Keep current decisions separate from historical evidence. Clearly label proposed, implemented, verified, rejected and incomplete work. Do not rewrite measurements or acceptance history.
7. Record useful work in progress in Git rather than private handoff dumps. Preserve inputs and evidence identities. Exclude credentials, private session records, dependencies and machine-specific runtime state.
8. Make relevant maintained documentation accessible as rendered website pages where project publishing is supported. Documentation links should remain within the rendered documentation, rather than send readers to Markdown on a code host. Keep source-code and primary-reference links explicit.
9. Preserve existing public URLs and deployment boundaries. Do not overwrite unrelated content or introduce a release as part of this documentation task.
10. Validate structure, links, concise prose, mathematical rendering where present, and fresh-checkout build instructions. Do not run the Android/device suite solely for documentation changes.

## Scope and review

The checker inventories root Markdown, `agents/`, maintained `docs/`, issue templates and release templates. `docs/project-history/` and published release notes are historical evidence; preserve their content and acceptance history.

The numbered standard above is retained verbatim. Its longer items are the only maintained-prose exception to the bullet limit. Table cells follow the paragraph limit.

Review meaning as well as syntax. Remove duplicate contracts, stale tense and unnecessary narrative while preserving defaults, exceptions, recovery rules and evidence identities.

Use status words precisely:

| Status | Meaning |
| --- | --- |
| Proposed | Intended work without an implementation claim. |
| Implemented | Present in the named source revision. |
| Verified | Passed named checks against an exact revision and environment. |
| Rejected | Evaluated and not accepted; retain the evidence. |
| Incomplete | Required work or verification remains. |
| Released | Included in an identified distributed build. |

## Checks and publishing

Use the [documentation commands](docs/build.md#documentation) from a fresh checkout. Checks validate prose limits, headings, local files, fragments, image descriptions and rendered navigation.

The website publishes maintained pages under `/localica/docs/`. Existing root pages and other published paths remain outside this deployment's ownership.

Review external source accuracy separately; local link checks do not establish that a remote page remains available or supports a claim. Historical documents are available from explicitly labeled source links.
