# HoldFast changes

## 1.2.0
- Native long-press text highlighting in the terminal
- Keyboard autocorrect, suggestions and swipe typing now work directly at the prompt (through an input proxy).
  You can turn this off in Settings.

## 1.1.1
- Terminal repaints are batched to one per frame, which makes scrolling back much smoother

## 1.1.0
- Tap to pick numbered menus, open links and paste code blocks
- Scroll-back is kept when you switch tabs
- Autocorrect compose box for typing longer text
- Swipe between tabs, plus a close (X) button on each tab

## 1.0.2
- Added a Shift+Tab key to the extra-keys bar

## 1.0.1
- Fixed a crash when opening a terminal
- Added an on-device crash log (never uploaded)

## 1.0.0
First release. Hardening done before release, after an independent code review:
- Fixed a race between closing and connecting; terminal input no longer blocks; the vault is thread-safe
- Share-to-HoldFast requires the app to be unlocked, accepts only `content://` files, asks for confirmation
  and sanitises upload names and paths
- Remote commands run with a time limit; SFTP asks before overwriting and uploads to a `.part` file first
- Battery-optimisation warning banner, and an honest notice when Windows can't resume a session after reconnect
- "It's done" alerts when a tab goes quiet after working, or rings the bell

## Publishing note
This public repo starts from a single clean commit of 1.2.0. Earlier development history was kept private
because it held personal machine details, which have been removed from the code.
