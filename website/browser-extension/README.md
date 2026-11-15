# TurboDL Link Relay

A Manifest V3 popup extension that copies the current tab URL or a ready-to-run TurboDL CLI command.

## Install as an unpacked extension

1. Unzip `turbodl-link-relay.zip`.
2. Open `chrome://extensions/`.
3. Enable Developer mode.
4. Choose “Load unpacked” and select the unzipped folder.

## Permissions

- `tabs`: read the active tab URL and title.
- `storage`: keep the five most recent links locally.

The extension does not inject content scripts, read page content, call remote APIs, or upload browsing history.
