# WeWatch Overlay Feature Porting Guide

This document outlines the overlay features from the Windows Electron app that need to be ported to Android. The overlay provides real-time notifications about media playback events, user connections, and synchronization status.

## Overlay Features

### 1. Event Notifications (Toast Messages)
- **Description**: Displays toast notifications for various events happening in the watch session.
- **Events Covered**:
  - Play/Pause/Seek actions (local and remote)
  - File/media changes
  - User join/disconnect events
  - Synchronization events
  - Generic session events
- **UI Characteristics**:
  - Positioned in top-right corner (over VLC window on Windows)
  - Shows timestamp and message text
  - Maximum 3 toasts visible simultaneously
  - Auto-dismiss after 1.6-8 seconds based on message type
  - Different color gradients for event types:
    - Cyan (default events)
    - Purple (file changes)
    - Orange (play/pause/seek)
    - Red (user left events)
  - Slide-in/slide-out animations
  - Transparent background with blur effect

### 2. Window Management
- **Description**: Creates and manages a transparent overlay window that appears over the media player.
- **Features**:
  - Frameless, transparent window
  - Ignores mouse events (clicks pass through)
  - Always on top
  - Auto-positions based on VLC window bounds (fallback to screen position)
  - Auto-hides after message duration

### 3. IPC Communication
- **Description**: Secure inter-process communication between main app and overlay.
- **Features**:
  - Preload scripts for context isolation
  - Event-driven message passing
  - Payload includes message, kind, duration, timestamp

### 4. Event Detection and Triggering
- **Description**: Detects local and remote events to trigger overlay messages.
- **Features**:
  - Monitors VLC status changes
  - Handles socket messages from server
  - Logs events to both overlay and app log
  - Suppresses duplicate notifications

## Files Containing Overlay Features

### Core Overlay Files
- `src/overlay/index.html` - HTML structure for the overlay window
- `src/overlay/style.css` - CSS styles for toast animations and appearance
- `src/overlay/renderer.js` - JavaScript for managing toast stack and rendering messages
- `src/overlay/preload.js` - Preload script exposing IPC interface to overlay renderer

### Main Process Files
- `src/main/main.js` - Contains overlay window creation, positioning, and message handling functions:
  - `createOsdWindow()`
  - `showOsdMessage()`
  - `positionOsdWindow()`
  - `getVlcWindowBounds()` (Windows-specific)
  - `getFallbackOsdBounds()`

### Renderer Process Files
- `src/renderer/renderer.js` - Contains event detection and overlay triggering logic:
  - `showVlcOsd()`
  - `logSessionEvent()`
  - Event handling in `handleSocketMessage()`
  - Status change detection in `detectLocalVlcChange()`
  - Action handling in `runVlcCommand()` and `applyRemoteControl()`

### Preload Files
- `src/main/preload.js` - Exposes `showOsdMessage` API to renderer process

## Android Porting Considerations

### UI Adaptation
- Replace Electron BrowserWindow with Android View/Overlay
- Use Android Toast or custom overlay views
- Adapt positioning logic for Android screen coordinates
- Implement similar animation effects using Android animations

### Event Integration
- Hook into existing Android event handling (similar to `handleSocketMessage`)
- Integrate with Android media player controls
- Maintain similar message formatting and timing

### Platform Differences
- No VLC window bounds detection needed (use screen positioning)
- Different IPC mechanism (use Android Intents, BroadcastReceivers, or direct method calls)
- Consider Android notification permissions and overlay permissions

### Key Functions to Port
- Message formatting and queuing logic
- Toast creation and dismissal timing
- Event type classification and styling
- Position calculation and window management</content>
<parameter name="filePath">FEATURE_PORTING_GUIDE.md