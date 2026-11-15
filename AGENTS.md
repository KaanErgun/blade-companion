# Project rules

- This is an unofficial Blade companion by Kaan Ergün. Do not imply Vuzix endorsement or a shipped release.
- Keep the core package independent of Android. All user-facing strings belong in Turkish string resources.
- The USB diagnostic command is fixed and read-only. USB descriptors and permission do not prove a responding Blade; display only measured state.
- Do not log USB payloads or identifiers. Close claimed interfaces and connections on every transport exit.
- README.md is the only living status document. Run ./scripts/check.sh locally; no hosted CI.
