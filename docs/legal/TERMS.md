# Terms of use

These are the terms for using TurboDL. **They do not replace the LICENSE** — the
[MIT License](../../LICENSE), together with its plugin-ecosystem supplemental terms, is the
document that actually grants you the right to use, modify and redistribute this project.
What follows covers different ground: what you are responsible for, how far the software is
offered, and how to think about third-party plugins.

If the two conflict, the `LICENSE` wins.

## What you may do with it

The MIT License is already generous: commercial use, modification, redistribution and closed-source
integration are all fine, as long as the copyright and license notices stay in place. Read
`LICENSE` for the details — it will be shorter than you expect.

## Your responsibility

TurboDL is a neutral download tool: it moves bytes from an address you name to your disk, and it
does not judge what those bytes are.

So:

- What you download belongs to whoever owns it. **Making that download, keeping it, and passing it
  on legally is your responsibility**, along with following the laws where you live and the terms
  of the sites you use.
- Using it to get around paywalls, access controls or terms of service is not a supported use of
  this project.
- Anything you publish or distribute with it is your responsibility as the publisher, not the
  tool author's.

## The software is provided as is

No warranty of any kind — express or implied — including the implied warranties of
merchantability, fitness for a particular purpose and non-infringement. Interrupted transfers,
corrupted files, a site blocking your IP or account, a full disk: all of these are among the
things that can happen.

The authors are not liable for any claim, damages or other liability, whether in an action of
contract, tort or otherwise, arising from or in connection with the software or its use.

## Third-party plugins

The plugin system is built on one premise: **the core stays neutral, each plugin answers for itself.**

- The plugins in this repository (the HLS backend, the JavaScript loader) are maintained here, and
  their source is right there to read.
- **A plugin from outside this repository is someone else's code.** Installing it means trusting
  its author — TurboDL cannot judge that for you. What the runtime does enforce is specific:
  a plugin declares a category, can only attach to the matching extension points, and can register
  only what that category allows. Beyond that, no sandbox-level isolation is promised.
- **This project has no signing or verification pipeline.** There is no such thing as a
  "verified plugin", and neither this site nor the repository will label one that way. Judge a
  plugin by its source and its author.

## About the name

You are welcome to say your project is "built on TurboDL" or "uses TurboDL" — that is a statement
of fact.

Clause 4 of the `LICENSE` supplemental terms puts the rest precisely: the license grants no rights
to the names, logos or trademarks. So please do not let users believe your project is an official
TurboDL release, and do not use the name to endorse your product or service without permission.

## Changes to these terms

These terms evolve with the project. Every change is in the repository's commit history, where you
can see what changed and when. Continuing to use a new version means accepting the revised terms.

## Getting in touch

If something here is unclear, or if these terms seem to be keeping you out of something you should
be able to do, open an [issue](https://github.com/jiayuxuan123/TurboDL/issues) — questions like
that usually make the documentation more accurate.
