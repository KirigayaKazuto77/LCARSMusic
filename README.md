# LCARSMusic

**LCARS-themed Music Player for Android Car Head Units and Tablets**

LCARSMusic is a Star Trek LCARS (*Library Computer Access and Retrieval System*)-inspired music player specifically engineered for Android Car Head Units, Tablets, and Infotainment systems fixed in landscape orientation. Featuring high-contrast Starfleet UI aesthetics, large touch targets, real-time audio visualization, multi-band equalizer controls, and persistent state management.

---

## 🚀 Key Features

* **Authentic LCARS Interface**: High-contrast, color-coded LCARS frame layouts, uppercase typography, and large pill buttons tailored for safe and easy in-vehicle touch interaction.
* **Fixed Landscape Layout**: Dual-panel design optimized for automotive displays:
  * **Left Panel (25% width)**: Houses the interactive Audio Visualizer and Navigation Menu.
  * **Right Panel (75% width)**: Contains the Title Bar, Main Content View (Now Playing, Library, Playlist, Equalizer), and Media Controls.
* **Real-Time Audio Visualizer**:
  * **Frequency Domain Mode**: Spectrum analyzer utilizing FFT data.
  * **Time Domain Mode**: Symmetrical waveform visualizer.
  * **Resource-Optimized**: Visualizer processing is capped at 30 FPS and fully pauses when backgrounded to conserve CPU cycles.
  * **Fallback View**: Rotating Starfleet Insignia when the visualizer is toggled off.
* **Storage & Media Scanner**: Scans both internal storage and attached external USB drives for local audio files.
* **Built-in Equalizer**: 5-band equalizer with custom segmented vertical seek bars, preset selection, and flat reset capability.
* **Background Playback & Foreground Service**: Playback runs via an Android Foreground Service with lockscreen/notification controls to ensure music continues uninterrupted when navigating or using other apps.
* **Audio Focus & Volume Ducking**: Handles transient system sounds (such as GPS navigation announcements) by temporarily ducking media volume without pausing playback.
* **State Persistence**: Remembers the last played song, playback position, active playlist, and custom equalizer settings across app restarts.

---

## 📱 Target Devices & Compatibility

* **Supported Devices**: Android Car Head Units, Android Automotive / Infotainment hardware, Android Tablets fixed in landscape orientation, or smartphones mounted in landscape mode.
* **Minimum Android Version**: **Android 8.0 (Oreo) / API Level 26**
* **Target & Compile SDK**: **Android 16 (Baklava) / API Level 36**
* **Screen Orientation**: Locked to **Landscape** (`android:screenOrientation="landscape"`).
* **Recommended Resolution**: 1024x600, 1280x720, or higher landscape displays.

---

## 🔒 Required Permissions

| Permission | Purpose |
| :--- | :--- |
| `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` | Scans internal storage and connected USB drives for local audio tracks. |
| `RECORD_AUDIO` | Strictly required by Android's `Visualizer` API to capture local audio output session data for rendering waveforms and frequency spectrums. |
| `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Allows background music playback while running navigation or other automotive applications. |
| `POST_NOTIFICATIONS` | Displays media notification controls on Android 13 (API 33) and newer. |

---

## 🛠️ Development & Environment Setup

### Prerequisites & Requirements

To build and run this project in Android Studio, ensure your development environment is configured with the following:

| Configuration Item | Required / Configured Version |
| :--- | :--- |
| **Android Studio** | Android Studio Ladybug / Meerkat (2024.2.1+) or newer |
| **JDK (Java Development Kit)** | **JDK 21** |
| **Android SDK** | SDK Platform **API Level 36** (Android 16 / Baklava) |
| **Build Tools** | **36.0.0** |
| **Android Gradle Plugin (AGP)** | **9.2.1** |
| **Gradle** | **9.4.1** (via Gradle Wrapper) |
| **Kotlin Version** | **2.3.21** |

---

### Step-by-Step Setup Guide

1. **Clone the Repository**
   ```bash
   git clone https://github.com/your-username/LCARSMusic.git
   cd LCARSMusic
   ```

2. **Open in Android Studio**
   * Launch Android Studio.
   * Click **Open** and select the root directory of the project.

3. **Configure Gradle JDK**
   * Go to **Settings / Preferences** `→` **Build, Execution, Deployment** `→` **Build Tools** `→` **Gradle**.
   * Under **Gradle JDK**, select **Java 21 / JDK 21**.

4. **Sync Project**
   * Click **Sync Project with Gradle Files** in the top toolbar to fetch dependencies (`androidx.appcompat`, `androidx.media`, `material`, `gson`, etc.).

5. **Run the Application**
   * Connect an Android device or launch an Android Emulator running API Level 26 or higher.
   * Ensure the deployment target is in landscape orientation.
   * Select the `:app` run configuration and click **Run** (`Shift + F10`).
   * Grant storage, audio recording (for visualizer), and notification permissions when prompted on first launch.

---

## 📄 License

This project is open-source under the MIT License. Star Trek, LCARS, and related marks are trademarks of CBS Studios Inc. / Paramount.
