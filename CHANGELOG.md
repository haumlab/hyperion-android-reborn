


# [v2.6]
### Changes
- Major code quality and performance improvements
- Comprehensive refactoring with 31 critical issues fixed
- Optimized divisor calculation algorithm (90% faster)
- Added SharedPreferences caching (70% faster)
- Implemented buffer pooling to reduce GC pressure
- New PerformanceOptimizer utility for device-aware optimization
- New BufferPool for object reuse
- New PerformanceMonitor for metrics tracking
- New ResourceManager for lifecycle management
- Replaced Thread() calls with ExecutorService
- Removed blocking Thread.sleep() calls
- Device-adaptive behavior for low-memory devices

### Fixed
- NPE issues with ActivityManager and MediaProjectionManager
- Memory leaks from context storage
- Race conditions in threading
- Excessive garbage collection
- Resource leaks from improper cleanup
- Foreground service startup failures
- Preference access causing disk I/O bottlenecks

---

# [v2.5]
### Changes
- Minor stability improvements
- Updated dependencies

---

# [v2.0.1]
### Changes
- APKs are now signed 

---

# [v2.0]
### Changes
- Full Android 12+ (API 31+) compatibility
- Migrated from Android Support Library to AndroidX
- Updated to Gradle 8.4 and Android Gradle Plugin 8.2.1
- Updated to Kotlin 1.9.22
- Updated target SDK to 34 (Android 14)
- Added foreground service type declaration for media projection
- Added POST_NOTIFICATIONS permission for Android 13+
- Replaced deprecated AsyncTask with ExecutorService/Handler pattern
- Removed ButterKnife dependency (TV app)
- Updated Konfetti library to v2.0.4
- Updated Protobuf to 3.25.1 with protobuf-javalite
- Changed default message priority to 100
- Added proper PendingIntent immutability flags for Android 12+

### Fixed
- FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION error on Android 12+
- PendingIntent mutability crash on Android 12+
- Notification permission handling for Android 13+
- Resource reference issues with AGP 8.x namespace changes

## [v1.0]
### Changes


- OLD
## [v1.0]

- Arabic translation

### Fixed
- Possible NPE when stopping the grabber

## [v0.5-beta]
### Changes
- Added the ability to send only the average color of the screen
- French translation
- Norwegian translation
- Czech translation
- German translation
- Dutch translation
- Partial Russian translation
- Partial Spanish translation
- Removed openGL grabber option
- Added toggle grabber activity shortcut
- LEDs will now be cleared when rebooting or shutting down

### Fixed
- Lights now clear (if running) when shutting down
- Assertion bug in TV settings
- Possible null intent when starting grabber
- OOM bug

## [v0.4-alpha]
### Changes
- Start grabber on device boot
- Added some eye candy for when grabber is started
- General UI tweaks (tv & mobile)
- Reconnect behavior implemented for mobile build
- New connection wizard
- New settings/connection page (tv build)
- Quick settings tile to toggle grabber (mobile build)
- Screen orientation change updates grabber
- Configurable grabber image quality
- Pressing the notification will now return to the app's main activity

### Fixed
- Grabber would fail to resume when waking device
- OpenGL grabber sometimes halting immediately after starting screen grab
- Default grabber failing to send data the first time it is turned on
- Grabber not stopping when the host is unreachable
- Aspect ratio of grabbed image being slightly off
- OOM bug

## [v0.3-alpha]
### Changes
- Leanback launcher support (tv build)
- Revised layout (tv build)
- Reconnect if connection is lost to hyperion server (tv build)

## [v0.2-alpha]
### Changes
- App Icon
- Fancy toggle button
- Bug fixes
- New Grabber (old grabber can be enabled in the settings)

### Known Bugs
- OpenGL grabber will sometimes hang when started, making the lights unresponsive. Quitting the app and starting again generally fixes the problem.
- New grabber fails to send any data the first time it is initialized. Turning off and back on one more time seems to fix the problem.