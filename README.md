# Paranoid Sense #

An application and service to manage face unlock on Paranoid Android.

This application relies on private libraries that utilize AI techniques
to capture a users face and all information is stored locally on the device.

## Face capture animation

The enrollment screen uses `FaceUnlockAnimationView`, an original canvas-drawn animation
inspired by the Face ID scan indicator and HyperOS face rings. It shows a pulsing scan
frame, a progress ring, a moving scan line, and success/error states. Colors follow the
system accent color and the loop is disabled when animations are turned off.

To use the HyperOS-style pulsing rings instead of the iOS-style brackets, set
`config_face_scan_style_ios` to `false` in `res/values/bools.xml` (a resource overlay can
also override it).

A browser mock-up of both styles is in `docs/animation-preview.html` (preview only; it is
not part of the Soong build).

The lock-screen unlock icon itself is drawn by SystemUI, not by this app. See
[REVIEW.md](REVIEW.md) for the integration point and the rest of the code review.

## Building

Build with the device's Soong build tree (`m FaceUnlock`); the app uses platform APIs and
prebuilt vendor libraries, so it cannot be built as a standalone Gradle project.

Set `log.tag.Sense:Utils` to `DEBUG` with `adb shell setprop` to enable verbose logging.
