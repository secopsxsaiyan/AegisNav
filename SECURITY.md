# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| v2026.03.21 | ✅ Supported |

## Reporting a Vulnerability

**Please do NOT open a public GitHub issue for security vulnerabilities.**

If you discover a security vulnerability in AegisNav, please report it privately via email:

📧 **admin@aegisnav.com**

### What to Include

Please include the following in your report:

1. **Description** - A clear and concise description of the vulnerability
2. **Steps to Reproduce** - Detailed steps to reproduce the issue
3. **Impact Assessment** - Your assessment of the potential impact (data exposure, privilege escalation, etc.)
4. **Affected Component** - Which part of the app is affected (see Scope below)
5. **Environment** - Android version, device model, app version if known
6. **Proof of Concept** - If applicable, include any code, screenshots, or recordings

### Response Timeline

AegisNav is an alpha-stage independent project maintained by a small team. We will make our best effort to:

- Acknowledge receipt within **5 business days**
- Provide an initial assessment within **2 weeks**
- Release a fix for critical vulnerabilities as quickly as resources allow

We appreciate your patience - this is an alpha project and resources are very limited.

## Scope

The following components are in scope for security reports:

- **Android application** - The AegisNav app itself (data handling, permissions, local storage)
- **Encrypted database** - SQLCipher-backed local storage and key management
- **Signature matching logic** - ALPR and police equipment detection algorithms
- **Correlation engines**

Out of scope: third-party dependencies (please report those to their respective maintainers).

## Responsible Disclosure

We ask that you:
- Give us reasonable time to fix the issue before public disclosure
- Avoid accessing or modifying user data beyond what's needed to demonstrate the vulnerability
- Act in good faith

Thank you for helping keep AegisNav and its users safe.
