# <img src="src/main/resources/icons/app.png" width="32"> Modulus for Companion

Scan [Bitfocus Companion](https://bitfocus.io/companion) configuration files and see exactly which modules are used, where, and what actions they trigger.

## Download

Grab the latest release from the [Releases](../../releases) page — no Java installation required.

- **macOS:** open the `.dmg`, drag the app to Applications, done.
- **Windows:** run the `.exe` installer, follow the short wizard, find it in the Start menu.

## Features

- Drag-and-drop or file-picker to open a `.companionconfig`
- Supports YAML, JSON, gzip, and ZIP exports
- Module dropdown lists every module that has at least one action configured
- Table shows page, button, step, and action ID for each usage
- Export the current module as CSV, or export all modules in one file
- Light / dark theme toggle, saved between sessions
- **Cmd+C** / **Ctrl+C** to copy selected rows

## How it works

1. Reads and parses the `.companionconfig` in whatever format it is
2. Collects all module instances and their labels
3. Finds every action tied to a known instance
4. Groups results by module and displays them in a table
