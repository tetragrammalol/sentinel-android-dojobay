# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub's private vulnerability reporting.

1. Open the **Security** tab of this repository.
2. Choose **Report a vulnerability**.
3. Fill in the advisory form.

Do not open a public issue for a vulnerability. This is especially important for anything that could expose funds, keys, seeds or user privacy.

Private vulnerability reporting has to be enabled on the repository (a maintainer action in Settings > Security) before the Security tab will accept a report. Until that is on, please do not file the finding as a public issue; wait for the private path, or use the optional contact below once it is published.

### Optional contact

An email address or PGP key for reports outside GitHub has not been decided yet.

<!-- Maintainer placeholder: publish a contact only after it has been agreed. Do not invent an address or key. -->

- Email: _not yet published_
- PGP key: _not yet published_

## Supported versions

| Version | Supported |
| --- | --- |
| 6.0.x | Yes |
| 5.x and earlier, including staging and RC builds (for example `v5.1.3-dojobay.*`) | No |

Once 6.0.0 is released, only the 6.0.x line is supported.

## What to include in a report

- The affected version or build (`versionName`, `versionCode`, or commit)
- A clear description of the issue and its impact (funds, keys, privacy, or availability)
- Steps to reproduce, or a proof of concept, if you can share them privately
- Any workaround you already know of

## Response expectations

This project is maintained by volunteers. We will do our best to:

- Acknowledge a report within 7 days
- Aim to complete initial triage within 14 days

We may take longer when the finding is complex, or when we need more information from the reporter.

## Coordinated disclosure

We prefer to fix a confirmed vulnerability in private, ship a release that includes the fix, and then publish the advisory (and a CVE if appropriate). We will credit the reporter if they want to be named.

Please give us a reasonable time to fix and release before any public disclosure.

## Scope

This policy covers the Sentinel Android application in this repository.

It does not cover:

- Dojo or Dojo Bay servers, or other operators' infrastructure. Report those to the project or operator that runs them.
- Third-party libraries such as kmp-tor or Tor. Report those upstream. We track version bumps in this repository when they affect the app (for example #572 for Tor 0.4.9.17).

## Triage rule

- Anything that could expose funds, keys, seeds or user privacy is handled as a private advisory. Do not file those as public issues.
- Low-risk hardening that does not by itself expose funds, keys, seeds or privacy may be discussed in public issues (for example #606 tapjacking, #607 passphrase warning).
