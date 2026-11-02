# Release operations

When asked to create, publish, monitor, or retry an internal testing release, follow
[.github/DEPLOYMENT.md](.github/DEPLOYMENT.md), including its agent CLI procedure.

# Visual evidence for UI changes

A pull request that changes what a screen looks like carries rendered evidence before it merges.
[.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md) says what that evidence is for
a static screen, for an interaction, and for a capture that was blocked.

# Main screen headings

Gaten (Home), Settings, and Analytics must all remain SVG artwork with the same lettering style,
weight, and visible letter height. Preserve the approved Gaten wordmark and the matching tab-title
letterforms; never replace them with plain text or a font substitute. For sizing fixes, share SVG
scale, line-box, and font-scaling rules across all three headings, accounting for viewBox whitespace.
Preserve artwork proportions when resizing or adjusting the SVGs.
