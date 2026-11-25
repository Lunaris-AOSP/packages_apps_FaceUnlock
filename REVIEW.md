# FaceUnlock code review

Review of the current tree (`0a35109`). Items are ordered by value. "Done" means the
change is in this branch.

## Done in this branch

1. **Face capture animation (iOS Face ID style, HyperOS-style variant).**
   `FaceUnlockAnimationView` draws an animated scan frame, progress ring, scan sweep and
   success/error states with plain `Canvas` primitives, so no new library is needed.
   It follows the system accent color, respects reduced motion, and pauses its loop when
   the window is hidden. Set `config_face_scan_style_ios` in `res/values/bools.xml` to
   `false` for the HyperOS-style pulsing rings. The enrollment screen now uses this view
   instead of the old constantly-invalidating surface drawing.

2. **Enrollment token corruption (high severity).**
   `PreferenceHelper` stored arbitrary challenge bytes with `String(bytes)`, which mangles
   every byte that is not valid UTF-8. Tokens are now Base64-encoded with a prefix;
   legacy values still decode as raw strings for backward compatibility.

3. **Enrollment cannot be retried after pause/resume.**
   A `CancellationSignal` was reused after `cancel()`. A fresh signal is now created on
   every `init()`.

4. **Dead code and stale progress state in `EnrollActivity`.**
   Removed the never-started handler thread, clamped progress, cleared stale vendor
   messages on a good frame, fixed permission-denied handling (it previously marked the
   camera as permitted), and showed the success/error animation before navigating away.

5. **Camera callback leak in `SenseService`.**
   An availability callback was registered for every `authenticate()` and never removed
   on cancel/error. It is now registered once and unregistered when authentication ends;
   duplicate `stopAuthentication()` calls were removed.

6. **Debug logging enabled unconditionally.**
   Face comparison scores were logged on production builds. Logging now uses
   `Log.isLoggable` (`adb shell setprop log.tag.Sense:Utils DEBUG` to opt in).

7. **Predictable challenges.**
   `generateChallenge()` used `Random`; it now uses `SecureRandom`.

8. **Idle timeout started without an enrolled face.**
   `updateTimersAndLockout()` checks actual enrollment and no longer cancels a timer it
   is about to start.

## Highest-value remaining work

1. **Keyguard/lock-screen face icon animation lives in SystemUI, not in this app.**
   This repo owns enrollment, the service and the Settings overlay. To show the same
   animation while unlocking, port `FaceUnlockAnimationView` into the ROM's SystemUI
   keyguard face-unlock view and drive `setProgress`/`setSuccess`/`setError` from the
   biometric callback. `overlay/res/raw/face_education_lottie.json` is the Settings
   education animation and is also owned by the Settings app.

2. **Implement `getFeature()` / `setFeature()` in `SenseService`.**
   Both AIDL methods are stubs, so features such as "require eyes open" cannot be
   toggled even though Settings shows them.

3. **Tighten vendor call error handling.**
   `mVendorImpl!!.compare(...)` and `saveFeature(...)` can throw on camera/session races;
   catch those errors and report `FACE_ERROR_CANCELED` instead of crashing the service.

4. **Add unit tests for token storage and progress state.**
   The Base64 migration and progress clamping are pure logic and can be tested with
   Robolectric in a Gradle harness; the Soong build has no test target today.

5. **Document the ROM integration contract.**
   README now covers build and animation configuration, but the required vendor libs,
   SELinux labels and SystemUI hook points still need a device-specific integration guide.
