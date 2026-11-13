# Navigation

The app is a row of tabs the user chooses, with one glass selector that follows taps and page swipes. Each tab keeps its own route and scroll position. Back always takes exactly one step, from the most temporary thing on screen to the most permanent. Bottom controls float over content, and lists pad themselves to clear them.

- Default tabs are Home and Library. At least one tab always remains.
- Switching tabs never loses a tab's route, position or loaded artwork.
- Reselecting the current tab returns to its root and top.
- Recreation restores state. A fresh launch never autoplays.
- Status: Implemented.

## Tabs

Available tabs: Home, Library, Songs, Albums, Artists, Album Artists, Folders, Playlists and Queues. Tabs are unique and ordered. Most played is a sort, not a tab. Four tabs fit the normal phone shelf. Wider sets and large fonts overflow without shrinking touch targets. Fitted cells fit the widest measured label.

## Selector

One lens responds at once to taps and follows manual page swipes magnetically. Icons and labels share an accent mask under the lens.

| Input | Result |
| --- | --- |
| Finger-down on a fitting bar or on the lens | Commits with one weak pulse. Each tab crossing ticks once. |
| A committing release: tap, hold or drag, reselection included | One strong pulse. |
| Overflow origin away from the lens | Silent until its tap release. Other origins scroll hidden tabs. |
| Keyboard or accessible activation | One weak pulse. |
| Page swipes, programmatic tracking, overflow scrolling, cancellation | Silent. |

One fit-or-overflow decision sizes the bar and bounds the lens drag. A fitting bar's lens reaches every tab. An overflowing bar allows lens dragging only from its visible lens. Contact, focus and settling raise the tab pill above neighbouring controls without changing hit bounds.

### Reselecting

Reselecting the settled current tab returns to its root and eases to the top, without adding history. In Library, the first reselect scrolls to the top and keeps the filter; a separate tap at the top clears the filter.

## Page swipes

A page swipe needs at least twice as much horizontal as vertical intent. Carousel ownership is unchanged. Swipes follow tab order without wrapping. Headings travel with their pages; detail headings crossfade. Visit history commits after settling.

## Back

Each system Back, or committed Back gesture, takes the first applicable step:

1. End focused text editing and hide the keyboard, keeping the draft.
2. Dismiss the topmost popup or dialog.
3. Clear a visible media selection.
4. Close the queue overlay, artwork viewer or lyrics.
5. Collapse the player.
6. Scroll the current list to the top.
7. Reset an applicable list filter.
8. Leave the detail or search page.
9. Return through tab visits.
10. Exit.

Steps 6 and 7 also apply inside details. Returning from a detail or search restores the parent query, first item and pixel offset, after matching results load.

Tab history is on by default; exit confirmation is off. Disabling tab history keeps detail and search Back. From a shortcut's initial settings page, system Back returns to the caller.

## Bottom chrome

Three floating pills sit above the content: the full-width mini-player, flexible tabs at the left, and a divided Search/More pill at the right. More offers Scan music and Settings.

| Element | Size |
| --- | --- |
| Tab shelf | 64 dp high |
| Mini-player and standalone circular controls | Four fifths of that: 51.2 dp |
| Mini-player artwork | 32 dp, with a 16 dp leading inset |
| Search and More icons | 28 dp |

- Bottom floating surfaces share one shape-aware shadow, without stacked overlap.
- Lists draw behind the chrome. Bottom padding includes the chrome, selection pill, measured return and overflow controls, and a 16 dp gap.
- Inside album, artist, Album Artist, playlist and folder details, tabs and utility controls leave. The mini-player moves down and stays.
- Library and Playlists have a circular + at mini-player height. It morphs into a creation menu: Create playlist and Import playlist from file.

## State restoration

Typed stacks and valid saved state keep route identity and positions. Restoration accepts current strings and legacy wrapped strings. Malformed or unusable navigation returns to its owning screen's fresh root, without guessing collection types. Positions are restored only after matching results and layout exist.

A fresh launch prefers Home, otherwise the first tab. Closing the Activity leaves playback running.

## Tests

| Topic | Classes |
| --- | --- |
| Tabs and selector | `NavigationTest`, `SlidingTabsTest`, `TabPagerTest`, `TabResetTest`, `NavbarHapticsTest` |
| Back | `ListBackTest`, `SelectionBackTest`, `PopupImeBackTest` |
| Chrome clearance | `ShellChromeClearanceTest`, `ListForegroundClearanceTest`, `ListEndContextsTest` |
| Restoration | `NavigationRecoveryTest`, `SettingsPathRestorationTest`, `BrowsePositionTest` |
