# Application releases and tags

`web/src/changelog/version.ts` is the source of the application version. A release changes
`APP_VERSION` and adds the matching English and Polish entry to `web/src/changelog/entries.ts` in
the same commit. Gradle and npm package versions are separate.

## Publishing a new version

1. Merge the reviewed version/changelog change into `master` after its required checks pass.
   Record the full release commit SHA and verify that commit's `APP_VERSION` and newest
   changelog entry match the intended version. Wait for CI on that exact master commit.
2. Create an annotated `v<APP_VERSION>` tag at that explicit SHA, then push that tag only.
   Never let a moving branch name choose the release commit. Existing published tags are
   immutable by convention: do not move, replace or force-push them.
3. Create the GitHub release using `gh release create` with `--verify-tag`. Supply a reviewed
   notes file containing that version's English body, a separator, and its Polish body from
   the tagged changelog. Do not generate product notes from commit messages. Mark only the
   highest stable version as Latest; use `--latest=false` for older releases.
4. Read back the remote tag's peeled commit, release body, draft/prerelease status and Latest
   selection. A local tag or a draft release alone does not complete publication.
5. When deployment is in scope, build from the recorded commit using the
   [run-stack playbook](../skills/run-stack/SKILL.md), verify the displayed version and SHA,
   and preserve existing data. Publishing a GitHub release does not itself deploy containers.

Documentation and test-only changes may retain the app version. They do not create another
release with the same version or move its tag; the displayed commit identifies the newer build.
The existing CI workflows check source/build behavior but do not publish GitHub releases.
Release/tag verification is part of the publishing agent's completion checklist.
