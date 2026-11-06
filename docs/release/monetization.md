# Modrinth distribution and revenue

Checked against public Modrinth metadata and official Rewards Program documentation on 2026-11-06.

## Current project

- Project: [Combat Persistence](https://modrinth.com/mod/combat-persistence), ID `Q56AIoib`.
- Status: approved; public API reports `monetization_status=monetized`.
- Before this release: 417 downloads, one follower and latest version `1.9.0` for Minecraft `26.2`.
- Existing donation link: [Ko-fi](https://ko-fi.com/pottersgame).
- Current environment: dedicated server only; no client installation.
- Fabric API project ID: `P7dR8mSH`, required dependency.

This project already participates in monetization. No enrollment change is needed to start accruing rewards. Only the owner should supply withdrawal details in the private Modrinth revenue dashboard.

## How revenue works

Modrinth's [Rewards Program Information](https://modrinth.com/legal/cmp-info) currently allocates 75% of website/app advertising revenue to creators and 25% to Modrinth. Legitimate project page views and in-app downloads determine each project's share. Modpack downloads can count toward the included projects. Rewards accrue automatically; withdrawal requires payment information. Fees and availability should be checked in the owner's dashboard.

The [Rewards Program Terms](https://modrinth.com/legal/cmp) promise no minimum revenue. Downloads alone do not establish profit, and the public project API does not expose the owner's earnings or costs. No earnings estimate is justified by the available data.

## Changes prepared for this release

- Lead with the server administrator's need: combat logging protection for Fabric PvP and SMP servers.
- Explain the fight, logout, offline death and recovery behavior before presenting authentication extras.
- Keep accurate `management` and `utility` categories; add `game-mechanics` as a supplementary category.
- Provide installation requirements, concrete configuration defaults and direct support/source links.
- Replace stale GitHub links and absolute security/persistence claims with accurate operational limits.
- Retain the existing Ko-fi link without adding ads or payment prompts to gameplay.

Apply `docs/release/project-metadata.json` to the project and use the complete `MODRINTH.md` content for its `body`. Preserve the existing license, ownership, monetization status and dedicated-server environment. Upload only the verified JAR for the Minecraft version actually tested. Mark Fabric API as a required dependency.

## Growth work after release

1. Add real in-game gallery screenshots showing the combat timer, logout mannequin and offline death/recovery flow. Use a test account and hide addresses, coordinates and private chat.
2. Measure legitimate page views, in-app downloads and retained use through Modrinth analytics. Compare equivalent periods after release; do not infer causation from a single day's downloads.
3. Keep supported stable Minecraft versions clearly labelled. Release compatibility updates when verified rather than attaching unsupported version tags.
4. Use issue reports to fix reproducible server failures and clarify setup instructions.
5. Invite relevant server owners to try a verified release through channels where project promotion is permitted. No bots, artificial traffic, spam or unsolicited messages.

These changes improve clarity and discovery; they do not guarantee profit. Do not alter payout, tax, payment or bank settings through release tooling.

## Sources

- [Public project API](https://api.modrinth.com/v2/project/combat-persistence)
- [Public version API](https://api.modrinth.com/v2/project/combat-persistence/version)
- [Rewards Program Information](https://modrinth.com/legal/cmp-info)
- [Rewards Program Terms](https://modrinth.com/legal/cmp)
- [Modify a project API](https://docs.modrinth.com/api/operations/modifyproject/)

The private search returned the official rewards pages. The normal web search tool was unavailable; the official pages and APIs were fetched directly instead.
