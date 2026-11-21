# Security policy

The latest stable release receives fixes. Report suspected vulnerabilities through [GitHub private vulnerability reporting](https://github.com/karam345/JavaDeobfuscatorX/security/advisories/new). Include the version, platform, suspected impact and a minimal synthetic fixture if possible. Keep untrusted or proprietary inputs out of public issues and pull requests.

Archives and reconstructed sources are untrusted. The engine does not launch input JARs or load their classes. Worker JVMs run with the invoking account's filesystem permissions. Heap and time limits bound worker use but provide no operating-system isolation. Use your environment's isolation controls for inputs requiring stronger containment.

Analysis is local. Source, recovered strings and reports can contain sensitive material. Review artifacts before sharing them. The class-only output is for static inspection.
