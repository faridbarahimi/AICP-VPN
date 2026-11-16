# NPV decryption core provenance

This directory contains the NPV/NPVS decryption implementation copied from
[FrontierTM/Pantegnos](https://github.com/FrontierTM/Pantegnos), exact tag
`v9.4.3`, commit `09ae0699f7d18717f1900643b703021344f6a9fe`.

That exact release is MIT-licensed. The upstream project changed to AGPL-3.0
in later releases; no code from those later releases is included here. The
upstream MIT copyright and license are preserved in `LICENSE`.

Only the `NPVT1` and `NPVS` decryptors, their required white-box tables, the
small registry contract, and the Android bridge are included. This code is
used locally on-device; configuration contents are not uploaded or logged.
