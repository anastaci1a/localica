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

11. Explain the model before the behaviour. State what the system is, its rules and why they exist. Exhaustive behaviour lives in tests: link the test class instead of listing its cases. Reference pages (file formats, identifier tables) are the only catalogues.
12. Open every page with an overview a reader can stop at: one short paragraph, then at most five bullets, before the first section heading. State there which parts are Proposed.
13. Keep every maintained page at or below 200 source lines. Split a larger page by model, not by length. Targets per page type are in the table below.
14. Update a page in the same change that alters a contract it documents. A change that leaves a documented contract false is incomplete. Each minor release checks the roadmap and retakes the screenshots.

## Scope and review

The checker inventories root Markdown, `agents/`, maintained `docs/`, issue templates and release templates. `docs/project-history/` and published release notes are historical evidence; preserve their content and acceptance history.

Items 1–10 above are retained verbatim; #929 adds items 11–14. Its longer items are the only maintained-prose exception to the bullet limit. Table cells follow the paragraph limit.

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

## Page types and budgets

| Type | Where | Target lines |
| --- | --- | --- |
| Overview | `README.md`, `docs/README.md`, `product.md`, `roadmap.md` | 20–60 |
| Architecture | `docs/architecture/` | 60–150 |
| Interface | `docs/interface/` | 40–120 |
| Development | `docs/development/` | 60–160 |
| Reference | `library-data.md`, `icon-packs.md` | up to 200 |
| Moved page | An old path listing new homes | up to 30 |

Every type shares the 200-line hard cap. Count Markdown source lines, including blanks and tables.

## Checks and publishing

Use the [documentation commands](docs/development/build.md#documentation) from a fresh checkout. Checks validate prose limits, headings, local files, fragments, image descriptions and rendered navigation.

The documentation workflow uploads a rendered site artifact; it does not publish the site. The artifact targets `/localica/docs/`. Existing root pages and other published paths remain outside this deployment's ownership.

Review external source accuracy separately; local link checks do not establish that a remote page remains available or supports a claim. Historical documents are available from explicitly labeled source links.
