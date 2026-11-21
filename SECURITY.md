# Security policy

## Supported versions

Only the latest release is maintained. Update to it before reporting.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability. Report it privately through GitHub's private vulnerability reporting: the repository's Security tab, then Report a vulnerability. The report stays private until a fix ships.

Include the Floridex version, whether it is the web or the Android app, your device and browser, and the steps to reproduce.

## Scope

Floridex promises that photos and progress never leave the device: the model runs locally and nothing is uploaded. These are vulnerabilities:

- Anything that sends a photo, a capture or any progress off the device, or makes the web app load code or data from a host other than its own.
- Anything that lets another app or site read Floridex's stored photos or progress.
- Anything that makes the Android app's share target or file picker read files the user did not pick.
- Anything that executes content from a shared or uploaded file.

Model mistakes (a flower named wrongly) are bugs, not vulnerabilities: open a normal issue.

## Response

This is a single-maintainer project. I aim to acknowledge a report within a week and to tell you what I plan to do about it soon after. I cannot promise a fixed fix time.
