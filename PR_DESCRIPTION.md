# Subjective Tremor Rating

## Summary
Adds functionality for users to rate their tremor severity (1-5 scale) directly from the watch, either manually via a button or through scheduled prompts. Includes file-based calibration data capture during rating sessions.

## Key Features

### Watch App
- **Manual Rating:** "Rate Tremor" button on main screen.
- **Scheduled Prompts:** Random periodic prompts (every 2-3 hours) during active hours.
- **Rating UI:** Custom `ScalingLazyColumn` interface with 1-5 scale and haptic feedback.
- **Calibration:** Captures 10-second sensor clips during ratings directly to file (avoids OOM).
- **Communication:** Sends ratings via MessageClient and calibration files via ChannelClient.

### Phone App
- **Rating Config:** New settings screen for daily limits, active hours, and preferences.
- **Visualization:** Discrete rating points overlaid on tremor charts (color-coded by source).
- **Database:** Stores subjective ratings with timestamps and source labels.
- **Export:** Includes rating history in data exports.

## Technical Changes
- **TremorService:** Integrated `RatingPromptReceiver` and `AlarmManager` for scheduling.
- **CalibrationCaptureManager:** Implemented streaming file capture for stability.
- **Database Schema:** Added `SubjectiveRating` entity and Room DAO.
- **Migration:** Bumped database version to 2.
- **Permissions:** Added runtime request for `POST_NOTIFICATIONS` (Android 13+).

## Verification
- ✅ Build verification (Debug)
- ✅ Manual rating flow
- ✅ Prompt scheduling (verified via logs)
- ✅ Calibration file file transfer
- ✅ Chart visualization

## Versioning
- **Watch/Phone:** Bumped to v0.1.3
