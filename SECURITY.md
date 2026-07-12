# Security Policy

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Report them privately through GitHub's [private vulnerability reporting][gh-report]: open the
repository's **Security** tab and choose **Report a vulnerability**. This opens a private advisory
visible only to you and the maintainers.

Please include, as far as you can:

- the affected component (module, endpoint, or wire plane) and version or commit,
- a description of the issue and its impact,
- steps or a minimal proof of concept to reproduce it, and
- any suggested remediation.

We aim to acknowledge a report within a few business days and will keep you updated as we investigate.
Once a fix is available we will coordinate disclosure with you and credit you in the advisory unless you
prefer to remain anonymous.

[gh-report]: https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability

## Scope

Configd is **secure-by-config, not secure-by-default**: TLS, authentication, the audit log, replay
protection, and at-rest encryption are each off until enabled, and the server logs a loud warning while
they are. A default-insecure configuration behaving insecurely is expected behaviour, not a
vulnerability — see the [operator runsheet](docs/operations/operator-runsheet.md),
[deployer-must-know](docs/operations/deployer-must-know.md), and
[known limitations](docs/operations/known-limitations.md) for what the deployment boundary is
responsible for. Reports of the server failing to enforce a control that *is* enabled, or of a way to
bypass one, are in scope and very welcome.
