# 🎤🎹 VocalTrainer – Real-Time Vocal Pitch Trainer

[![Java](https://img.shields.io/badge/Java-8%2B-007396?logo=java)](https://www.java.com)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A professional-grade vocal training application that provides real-time visual feedback on pitch accuracy. Sing into your microphone while playing target notes on a MIDI keyboard (physical or virtual), and instantly see your intonation accuracy measured in cents (¢).

![VocalTrainer Interface](https://via.placeholder.com/1200x600/1a1a1a/ffffff?text=VocalTrainer+Interface+Preview)

## ✨ Key Features

- **Real-time pitch detection** – Analyze vocal pitch from microphone input with 30ms latency
- **Cent-accurate feedback** – Visual deviation indicator showing ±15¢ (excellent), ±40¢ (needs correction), >40¢ (significant drift)
- **Dual input support** – Use physical MIDI keyboards or built-in virtual piano (PC keyboard mapping)
- **Piano roll visualization** – See your vocal pitch history overlaid on a piano keyboard (last 5 seconds)
- **Auto-scrolling** – View automatically centers on current pitch for comfortable tracking
- **Device management** – Configure audio/MIDI inputs/outputs with one-click testing
- **Persistent settings** – All device selections and preferences saved between sessions
- **Zero dependencies** – Pure Java implementation using standard `javax.sound` APIs

## ⚙️ Technical Highlights

| Component | Technology |
|-----------|------------|
| **Audio Processing** | `javax.sound.sampled` with autocorrelation-based pitch detection |
| **MIDI Routing** | Custom router forwarding notes to synthesizer or physical devices |
| **UI Framework** | Java Swing with custom-rendered piano roll panel |
| **Pitch Analysis** | Frequency → MIDI conversion with cent-precision deviation calculation |
| **Virtual Piano** | Full 2-octave keyboard with PC key mapping (A-S-D-F-G-H-J for white keys, W-E-T-Y-U for black keys) |

## 🚀 Getting Started

### Requirements
- Java 8 or newer (JRE/JDK)
- Microphone (built-in or external)
- Optional: MIDI keyboard/controller for target notes

### Running the Application
```bash
# Windows (recommended):
run.bat

# Manual compilation & execution:
javac -encoding UTF-8 -source 1.8 -target 1.8 VocalTrainer.java
java VocalTrainer
```

> 💡 **Note for Windows users**: `run.bat` automatically sets UTF-8 encoding (`chcp 65001`) for proper Cyrillic character display in the UI.

## 🎹 Usage Workflow

1. **Configure devices** (click ⚙️ icon):
    - Select microphone input
    - Choose MIDI input (physical keyboard or use virtual piano via ♫ button)
    - Set output mode: *Synthesizer* (built-in) or *Physical port* (external device)

2. **Start training**:
    - Click ▶️ **START** to begin audio/MIDI processing
    - Play a target note on your MIDI keyboard (or virtual piano)
    - Sing the note – watch the **📏 Deviation** indicator:
        - ✅ **Green (✓)**: Within ±15¢ – excellent intonation
        - ⚠️ **Orange (△)**: ±15–40¢ – needs adjustment
        - ❌ **Red (✗)**: >40¢ deviation – significant correction needed

3. **Visual feedback**:
    - **Blue line**: Your vocal pitch history on the piano roll
    - **Yellow dashed line**: Current target note
    - **Auto-scroll**: View follows your pitch (toggle in settings)

4. **Stop session**: Click ⏹️ **STOP** to halt processing

## 🖥️ Interface Overview

```
┌──────────────────────────────────────────────────────┐
│  🎤🎹 VOCAL TRAINER          [⚙️] [♫]               │
├──────────────────────────────────────────────────────┤
│  ┌────────────────────────────────────────────────┐  │
│  │  Piano Roll Visualization                      │  │
│  │  • Blue line = vocal pitch history             │  │
│  │  • Yellow dashed = target note                 │  │
│  └────────────────────────────────────────────────┘  │
├──────────────────────────────────────────────────────┤
│  🎤 Your Note    │  🎹 Target    │  📏 Deviation     │
│  A4              │  C#5          │  ✓ +8¢            │
├──────────────────────────────────────────────────────┤
│  [▶️ START]                     [⏹️ STOP]            │
├──────────────────────────────────────────────────────┤
│  📡 MIDI Log                                         │
│  [14:22:05] ✅ MIDI input connected: Keystation 49   │
│  [14:22:10] 🎹 NOTE ON: C4 (MIDI 60), velocity=92    │
└──────────────────────────────────────────────────────┘
```

## 🎮 Virtual Piano Controls

| Action | Keys |
|--------|------|
| **White keys** | `A S D F G H J K L ; '` |
| **Black keys** | `W E   T Y U` |
| **Octave down** | `-` (main keyboard) or `Num -` |
| **Octave up** | `+` / `=` (with Shift) or `Num +` |
| **Always on top** | Toggle via checkbox in virtual piano window |

## 🛠️ Troubleshooting

| Issue | Solution |
|-------|----------|
| Microphone not detected | Check system audio settings → ensure microphone is enabled and not in use by other apps |
| No sound from synthesizer | Verify "Synthesizer" mode is selected in MIDI Output settings |
| Pitch detection unstable | Increase microphone volume to 70–80% in OS settings; reduce background noise |
| MIDI notes not triggering | Click "🔄 Rescan" in settings after connecting MIDI device |
| UI text appears as squares | Ensure system supports UTF-8; Windows users must run via `run.bat` |

## 📜 License

MIT License – see [LICENSE](LICENSE) for details.

---

> 💡 **Pro Tip**: For best results, use headphones to prevent audio feedback, and train in a quiet environment. Start with sustained single notes before progressing to scales and melodies.

*Developed with ❤️ for vocalists, choir directors, and music educators* 🎶