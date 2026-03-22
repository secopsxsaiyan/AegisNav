## Contributing to AegisNav
Thank you for your interest in contributing to AegisNav! This project is built by and for people who care about privacy, civil liberties, and informed navigation. For the alpha release, the entire app operates in purely offline mode - no internet connectivity is required or allowed at any point.
Every contribution helps keep the project alive and growing.

## How to Contribute
We welcome contributions in the following forms:

Bug reports
Feature requests
Code improvements via Pull Requests

## To submit code changes:

Fork the repository
Create a feature branch (git checkout -b feature/my-feature)
Make your changes
Open a Pull Request against the main branch

Note: Detailed development setup, build instructions, and debug tools are not publicly available. Only the release version of the source code is open-sourced. Approved contributors will receive private setup guidance if needed.

## Code Contribution Requirements

All new features or changes must include unit tests
All tests must pass before merging
No bare Log.d/i/v/w/e() calls - use AppLog from com.AegisNav.app.util.AppLog (no-ops in release builds)
Respect the purely offline design: no network calls, no online APIs, no web views. All maps, routing, and data must remain local
Follow existing Kotlin conventions, coroutine patterns, and Hilt + Room architecture

## Reporting Bugs
Open a GitHub issue with:

Clear steps to reproduce
Expected vs. actual behavior
Android version and device model
App version (from Settings → About)

For **security vulnerabilities**, see SECURITY.md - do not open a public issue.

## Feature Requests

Open a GitHub issue describing:

The proposed feature
Your specific use case (why it benefits users)
Any relevant examples or prior art

## Supporting AegisNav
AegisNav is an independent project. If you would like to support development financially, explore sponsorship, partnership, or acquisition opportunities, please reach out to:

📧 admin@aegisnav.com

Thank you for helping make private, fully offline navigation accessible to everyone!