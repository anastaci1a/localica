# Navigation

The app is a row of tabs the user chooses, with one glass selector that follows taps and page swipes. Each tab keeps its own route and scroll position. Back always takes exactly one step, from the most temporary thing on screen to the most permanent. Bottom controls float over content, and lists pad themselves to clear them.

- Default tabs are Home and Library. At least one tab always remains.
- Switching tabs never loses a tab's route, position or loaded artwork.
- Reselecting the current tab returns to its root and top.
- Recreation restores state. A fresh launch never autoplays.
- Status: Implemented.


## Tabs

Available tabs are Home, Library, Songs, Albums, Artists, Album Artists, Folders, Playlists and Queues. Tabs are unique and ordered; Most played is a sort. Four fit the normal phone shelf. Overflow preserves label widths and touch targets. A fresh launch prefers Home, otherwise the first tab.

## Selector

One lens follows taps and page swipes. A fitting bar permits immediate finger-down selection; overflow captures lens drags only from the visible lens. Fit, cell measurement and drag bounds come from one model. Contact, focus and settling raise the pill without enlarging its hit bounds.

Navigation haptics distinguish explicit selection from passive tracking. [NavbarHapticsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/NavbarHapticsTest.kt) specifies the input matrix.

### Reselecting

Reselecting a settled tab returns to its root and top without adding history. In Library, the first tap keeps the filter; a separate tap at the top clears it.

## Page swipes

Horizontal intent must exceed vertical intent twofold. Swipes follow tab order without wrapping and preserve carousel ownership. History commits after settling; detail headings crossfade while main headings travel.

## Back

Back takes one applicable step: end editing and hide the keyboard; dismiss the top modal; clear selection; close overlays; collapse player; scroll to top; clear the filter; leave details/search; retrace tab visits; exit. The order prevents a temporary surface from stranding its parent.

Details also apply the top/filter steps. Return restores the parent's query and pixel offset after matching results load. Tab history defaults on and exit confirmation off. Turning history off retains detail/search Back. Settings shortcuts return to their caller.

## Bottom chrome

Mini-player, tabs and Search/More float over content with a shared shadow. The shelf is 64 dp high; mini-player and circular controls are 51.2 dp. Mini-player artwork is 32 dp with a 16 dp leading inset; Search/More icons are 28 dp.

Lists include measured chrome, selection, return and overflow occupancy plus 16 dp in their end padding. In collection details only the lowered mini-player remains. Library and Playlists offer a creation menu for new or imported playlists.

## State restoration

Typed stacks restore route identity and valid positions after matching data and layout exist. Legacy wrapped strings remain readable. Malformed state returns to the owning screen's fresh root without guessing a collection type. Closing the Activity leaves playback running.

## Tests

[NavigationTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/NavigationTest.kt).

[SlidingTabsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SlidingTabsTest.kt).

[TabPagerTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/TabPagerTest.kt).

[TabResetTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/TabResetTest.kt).

[NavbarHapticsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/NavbarHapticsTest.kt).

[ListBackTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ListBackTest.kt).

[SelectionBackTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SelectionBackTest.kt).

[PopupImeBackTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PopupImeBackTest.kt).

[ShellChromeClearanceTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ShellChromeClearanceTest.kt).

[ListForegroundClearanceTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ListForegroundClearanceTest.kt).

[ListEndContextsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ListEndContextsTest.kt).

[NavigationRecoveryTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/NavigationRecoveryTest.kt).

[SettingsPathRestorationTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SettingsPathRestorationTest.kt).

[BrowsePositionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/BrowsePositionTest.kt).
