import javax.sound.midi.*;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.prefs.Preferences;

public class VocalTrainer extends JFrame {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new VocalTrainer().setVisible(true);
        });
    }

    // -------- НАСТРОЙКИ (Preferences) --------
    private static final Preferences PREFS = Preferences.userNodeForPackage(VocalTrainer.class);
    private static final String KEY_INPUT_DEVICE      = "input_device";
    private static final String KEY_OUTPUT_DEVICE     = "output_device";
    private static final String KEY_MIDI_IN_DEVICE    = "midi_in_device";
    private static final String KEY_MIDI_OUT_DEVICE   = "midi_out_device";
    private static final String KEY_AUTO_SCROLL       = "auto_scroll";
    private static final String KEY_OUTPUT_MODE       = "output_mode";
    private static final String KEY_DETAILED_LOG      = "detailed_log";
    private static final String KEY_VIRTUAL_OCTAVE    = "virtual_octave";

    // -------- ПАРАМЕТРЫ АУДИО --------
    private static final float SAMPLE_RATE = 44100.0f;
    private static final int SAMPLE_SIZE = 16;
    private static final int CHANNELS = 1;
    private static final boolean SIGNED = true;
    private static final boolean BIG_ENDIAN = false;
    private final AudioFormat AUDIO_FORMAT =
            new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE, CHANNELS, SIGNED, BIG_ENDIAN);

    // -------- СОСТОЯНИЕ --------
    private volatile boolean isRunning = false;
    private long sessionStartTime;

    private TargetDataLine microphoneLine;
    private PitchDetector pitchDetector;
    private volatile Float currentPitchMidi = null;
    private volatile String currentVocalNote = "—";
    private final Deque<PitchPoint> pitchHistory = new ArrayDeque<>(500);

    // MIDI вход
    private MidiDevice midiInputDevice;
    private Transmitter midiTransmitter;
    private MidiRouter midiRouter;
    private volatile Integer currentMidiNote = null;
    private volatile Integer currentMidiVelocity = null;

    // Выходные устройства
    private Synthesizer synthesizer;
    private Receiver synthReceiver;
    private MidiDevice midiOutputDevice;
    private Receiver outputReceiver;

    // Визуализация и скролл
    private PianoRollPanel pianoPanel;
    private double viewCenter = 60.0;
    private double targetCenter = 60.0;
    private volatile boolean autoScrollEnabled = true;

    // Виртуальная клавиатура
    private VirtualPianoFrame virtualPianoFrame;
    private int virtualOctave = 4; // начальная октава (C4 = 60)

    // GUI
    private JComboBox<String> audioInputCombo;
    private JComboBox<String> audioOutputCombo;
    private JComboBox<String> midiInCombo;
    private JComboBox<String> midiOutCombo;
    private JRadioButton physicalOutRadio;
    private JRadioButton synthOutRadio;
    private ButtonGroup outGroup;
    private JCheckBox autoScrollCheck;
    private JCheckBox detailedLogCheck;
    private JLabel midiNoteLabel;
    private JLabel midiVelocityLabel;
    private JLabel vocalLabel;
    private JLabel targetLabel;
    private JLabel deviationLabel;
    private JTextArea midiLogArea;
    private JButton startBtn, stopBtn;
    private JButton toggleSettingsBtn;
    private JButton virtualPianoBtn;
    private JPanel settingsPanel;

    private Timer repaintTimer;
    private Timer scrollTimer;

    // Списки MIDI устройств для точного выбора
    private List<MidiDevice.Info> midiInputInfos = new ArrayList<>();
    private List<MidiDevice.Info> midiOutputInfos = new ArrayList<>();

    // -----------------------------------------------------------------------
    public VocalTrainer() {
        super("🎤🎹 Тренер вокала (Java)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1300, 950);
        setLocationRelativeTo(null);
        getContentPane().setBackground(new Color(26, 26, 26));

        pitchDetector = new PitchDetector(AUDIO_FORMAT);
        initSynthesizer();
        initUI();

        rescanDevices();
        loadSettings();

        repaintTimer = new Timer(33, e -> {
            pianoPanel.repaint();
            updateIndicators();
        });
        repaintTimer.start();

        scrollTimer = new Timer(20, e -> smoothScroll());
        scrollTimer.start();

        logMidi("ℹ️ Программа запущена.");

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (virtualPianoFrame != null) virtualPianoFrame.dispose();
                stopProcessing();
                saveSettings();
                closeAllDevices();
            }
        });
    }

    // -------- Публичные методы доступа для виртуальной клавиатуры --------
    public Receiver getMidiRouter() { return midiRouter; }
    public Receiver getSynthReceiver() { return synthReceiver; }
    public boolean isRunning() { return isRunning; }
    public String noteToName(int midi) {
        if (midi < 0 || midi > 127) return "?";
        String[] notes = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        int octave = midi / 12 - 1;
        return notes[midi % 12] + octave;
    }

    // -----------------------------------------------------------------------
    private void closeAllDevices() {
        if (synthesizer != null) synthesizer.close();
        if (midiOutputDevice != null) midiOutputDevice.close();
        if (midiInputDevice != null) midiInputDevice.close();
    }

    // -----------------------------------------------------------------------
    private void initSynthesizer() {
        try {
            if (synthesizer != null) synthesizer.close();
            synthesizer = MidiSystem.getSynthesizer();
            synthesizer.open();
            Soundbank defaultBank = synthesizer.getDefaultSoundbank();
            if (defaultBank != null) {
                synthesizer.loadAllInstruments(defaultBank);
            }
            // Активируем каналы
            MidiChannel[] channels = synthesizer.getChannels();
            for (MidiChannel ch : channels) {
                if (ch != null) {
                    ch.programChange(0);
                }
            }
            synthReceiver = synthesizer.getReceiver();
            logMidi("✅ Синтезатор инициализирован.");
        } catch (MidiUnavailableException e) {
            logMidi("❌ Не удалось открыть синтезатор: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    private void initUI() {
        setLayout(new BorderLayout(10, 10));

        // ============ ВЕРХНЯЯ ПАНЕЛЬ ============
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBackground(new Color(26, 26, 26));

        JLabel title = new JLabel("🎤🎹 VOCAL TRAINER", SwingConstants.CENTER);
        title.setFont(new Font("Helvetica", Font.BOLD, 28));
        title.setForeground(new Color(76, 175, 80));
        title.setBackground(new Color(26, 26, 26));
        title.setOpaque(true);
        title.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));
        topPanel.add(title, BorderLayout.NORTH);

        // Панель кнопок (настройки и виртуальное пианино)
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 5));
        buttonPanel.setBackground(new Color(26, 26, 26));

        toggleSettingsBtn = new JButton("⚙️ Настройки");
        toggleSettingsBtn.setFont(new Font("Arial", Font.BOLD, 14));
        toggleSettingsBtn.setBackground(new Color(60, 60, 60));
        toggleSettingsBtn.setForeground(Color.WHITE);
        toggleSettingsBtn.setFocusPainted(false);
        toggleSettingsBtn.addActionListener(e -> {
            settingsPanel.setVisible(!settingsPanel.isVisible());
            toggleSettingsBtn.setText(settingsPanel.isVisible() ? "▲ Скрыть настройки" : "⚙️ Настройки");
        });
        buttonPanel.add(toggleSettingsBtn);

        virtualPianoBtn = new JButton("🎹 Виртуальное пианино");
        virtualPianoBtn.setFont(new Font("Arial", Font.BOLD, 14));
        virtualPianoBtn.setBackground(new Color(60, 60, 60));
        virtualPianoBtn.setForeground(Color.WHITE);
        virtualPianoBtn.setFocusPainted(false);
        virtualPianoBtn.addActionListener(e -> showVirtualPiano());
        buttonPanel.add(virtualPianoBtn);

        topPanel.add(buttonPanel, BorderLayout.SOUTH);

        add(topPanel, BorderLayout.NORTH);

        // ============ ПАНЕЛЬ НАСТРОЕК ============
        settingsPanel = new JPanel(new GridBagLayout());
        settingsPanel.setBackground(new Color(37, 37, 37));
        settingsPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Color.WHITE),
                "⚙️ Настройки устройств",
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font("Arial", Font.BOLD, 12),
                Color.WHITE
        ));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 10, 8, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        // Микрофон
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 1;
        JLabel micLabel = new JLabel("🎤 Микрофон:");
        micLabel.setFont(new Font("Arial", Font.BOLD, 12));
        micLabel.setForeground(Color.WHITE);
        settingsPanel.add(micLabel, gbc);

        gbc.gridx = 1; gbc.gridwidth = 2;
        audioInputCombo = new JComboBox<>();
        audioInputCombo.setPreferredSize(new Dimension(400, 30));
        audioInputCombo.addItemListener(e -> saveSettings());
        settingsPanel.add(audioInputCombo, gbc);

        // Динамики
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1;
        JLabel audioOutLabel = new JLabel("🔊 Динамики:");
        audioOutLabel.setFont(new Font("Arial", Font.BOLD, 12));
        audioOutLabel.setForeground(Color.WHITE);
        settingsPanel.add(audioOutLabel, gbc);

        gbc.gridx = 1; gbc.gridwidth = 1;
        audioOutputCombo = new JComboBox<>();
        audioOutputCombo.setPreferredSize(new Dimension(300, 30));
        audioOutputCombo.addItemListener(e -> saveSettings());
        settingsPanel.add(audioOutputCombo, gbc);

        gbc.gridx = 2;
        JButton testAudioBtn = new JButton("🔊 Тест");
        testAudioBtn.setFont(new Font("Arial", Font.BOLD, 11));
        testAudioBtn.setBackground(new Color(200, 200, 200));
        testAudioBtn.setForeground(new Color(30, 30, 30));
        testAudioBtn.setFocusPainted(false);
        testAudioBtn.addActionListener(e -> testAudioOutput());
        settingsPanel.add(testAudioBtn, gbc);

        // MIDI вход
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1;
        JLabel midiInLabel = new JLabel("🎹 MIDI In:");
        midiInLabel.setFont(new Font("Arial", Font.BOLD, 12));
        midiInLabel.setForeground(Color.WHITE);
        settingsPanel.add(midiInLabel, gbc);

        gbc.gridx = 1;
        midiInCombo = new JComboBox<>();
        midiInCombo.setPreferredSize(new Dimension(300, 30));
        midiInCombo.addItemListener(e -> saveSettings());
        settingsPanel.add(midiInCombo, gbc);

        gbc.gridx = 2;
        JButton rescanBtn = new JButton("🔄 Обновить");
        rescanBtn.setFont(new Font("Arial", Font.BOLD, 11));
        rescanBtn.setBackground(new Color(200, 200, 200));
        rescanBtn.setForeground(new Color(30, 30, 30));
        rescanBtn.setFocusPainted(false);
        rescanBtn.addActionListener(e -> rescanDevices());
        settingsPanel.add(rescanBtn, gbc);

        // Режим выхода
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 1;
        JLabel outModeLabel = new JLabel("🔊 MIDI Out:");
        outModeLabel.setFont(new Font("Arial", Font.BOLD, 12));
        outModeLabel.setForeground(Color.WHITE);
        settingsPanel.add(outModeLabel, gbc);

        JPanel radioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        radioPanel.setBackground(new Color(37, 37, 37));
        physicalOutRadio = new JRadioButton("Физический порт", false);
        physicalOutRadio.setFont(new Font("Arial", Font.PLAIN, 11));
        physicalOutRadio.setForeground(Color.WHITE);
        physicalOutRadio.setBackground(new Color(37, 37, 37));
        synthOutRadio = new JRadioButton("Синтезатор", true);
        synthOutRadio.setFont(new Font("Arial", Font.PLAIN, 11));
        synthOutRadio.setForeground(Color.WHITE);
        synthOutRadio.setBackground(new Color(37, 37, 37));
        outGroup = new ButtonGroup();
        outGroup.add(physicalOutRadio);
        outGroup.add(synthOutRadio);
        physicalOutRadio.addActionListener(e -> { midiOutCombo.setEnabled(true); saveSettings(); });
        synthOutRadio.addActionListener(e -> { midiOutCombo.setEnabled(false); saveSettings(); });
        radioPanel.add(physicalOutRadio);
        radioPanel.add(synthOutRadio);
        gbc.gridx = 1; gbc.gridwidth = 2;
        settingsPanel.add(radioPanel, gbc);

        // Физический MIDI выход
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 1;
        JLabel midiOutLabel = new JLabel("📤 MIDI Out:");
        midiOutLabel.setFont(new Font("Arial", Font.BOLD, 12));
        midiOutLabel.setForeground(Color.WHITE);
        settingsPanel.add(midiOutLabel, gbc);

        gbc.gridx = 1; gbc.gridwidth = 1;
        midiOutCombo = new JComboBox<>();
        midiOutCombo.setPreferredSize(new Dimension(300, 30));
        midiOutCombo.setEnabled(false);
        midiOutCombo.addItemListener(e -> saveSettings());
        settingsPanel.add(midiOutCombo, gbc);

        gbc.gridx = 2;
        JButton testMidiBtn = new JButton("🎹 Тест MIDI");
        testMidiBtn.setFont(new Font("Arial", Font.BOLD, 11));
        testMidiBtn.setBackground(new Color(200, 200, 200));
        testMidiBtn.setForeground(new Color(30, 30, 30));
        testMidiBtn.setFocusPainted(false);
        testMidiBtn.addActionListener(e -> testMidiOutput());
        settingsPanel.add(testMidiBtn, gbc);

        // Автопрокрутка
        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 1;
        JLabel scrollLabel = new JLabel("🔄 Прокрутка:");
        scrollLabel.setFont(new Font("Arial", Font.BOLD, 12));
        scrollLabel.setForeground(Color.WHITE);
        settingsPanel.add(scrollLabel, gbc);

        gbc.gridx = 1; gbc.gridwidth = 2;
        autoScrollCheck = new JCheckBox("Автопрокрутка за нотой");
        autoScrollCheck.setFont(new Font("Arial", Font.PLAIN, 12));
        autoScrollCheck.setForeground(Color.WHITE);
        autoScrollCheck.setBackground(new Color(37, 37, 37));
        autoScrollCheck.setSelected(autoScrollEnabled);
        autoScrollCheck.addActionListener(e -> {
            autoScrollEnabled = autoScrollCheck.isSelected();
            saveSettings();
            logMidi("🔄 Автопрокрутка " + (autoScrollEnabled ? "включена" : "отключена"));
        });
        settingsPanel.add(autoScrollCheck, gbc);

        // Подробный лог
        gbc.gridx = 0; gbc.gridy = 6; gbc.gridwidth = 1;
        JLabel logLabel = new JLabel("📋 Лог:");
        logLabel.setFont(new Font("Arial", Font.BOLD, 12));
        logLabel.setForeground(Color.WHITE);
        settingsPanel.add(logLabel, gbc);

        gbc.gridx = 1; gbc.gridwidth = 2;
        detailedLogCheck = new JCheckBox("Подробный лог MIDI");
        detailedLogCheck.setFont(new Font("Arial", Font.PLAIN, 12));
        detailedLogCheck.setForeground(Color.WHITE);
        detailedLogCheck.setBackground(new Color(37, 37, 37));
        detailedLogCheck.addActionListener(e -> saveSettings());
        settingsPanel.add(detailedLogCheck, gbc);

        // ============ ЦЕНТРАЛЬНАЯ ОБЛАСТЬ ============
        JPanel centerArea = new JPanel(new BorderLayout());
        centerArea.add(settingsPanel, BorderLayout.NORTH);
        pianoPanel = new PianoRollPanel();
        pianoPanel.setBackground(new Color(15, 15, 15));
        pianoPanel.setPreferredSize(new Dimension(1000, 450));
        centerArea.add(pianoPanel, BorderLayout.CENTER);
        add(centerArea, BorderLayout.CENTER);

        // ============ НИЖНЯЯ ПАНЕЛЬ ============
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBackground(new Color(26, 26, 26));

        // Индикаторы вокала, цели, отклонения
        JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 30, 10));
        infoPanel.setBackground(new Color(26, 26, 26));
        infoPanel.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

        // Ваша нота
        JPanel vocalBox = new JPanel(new BorderLayout());
        vocalBox.setBackground(new Color(26, 26, 26));
        vocalLabel = new JLabel("—", SwingConstants.CENTER);
        vocalLabel.setFont(new Font("Arial", Font.BOLD, 26));
        vocalLabel.setForeground(new Color(0, 204, 255));
        vocalBox.add(vocalLabel, BorderLayout.CENTER);
        JLabel vocalDesc = new JLabel("🎤 Ваша нота", SwingConstants.CENTER);
        vocalDesc.setForeground(new Color(119, 119, 119));
        vocalBox.add(vocalDesc, BorderLayout.SOUTH);
        infoPanel.add(vocalBox);

        // Цель
        JPanel targetBox = new JPanel(new BorderLayout());
        targetBox.setBackground(new Color(26, 26, 26));
        targetLabel = new JLabel("—", SwingConstants.CENTER);
        targetLabel.setFont(new Font("Arial", Font.BOLD, 26));
        targetLabel.setForeground(new Color(255, 215, 0));
        targetBox.add(targetLabel, BorderLayout.CENTER);
        JLabel targetDesc = new JLabel("🎹 Цель", SwingConstants.CENTER);
        targetDesc.setForeground(new Color(119, 119, 119));
        targetBox.add(targetDesc, BorderLayout.SOUTH);
        infoPanel.add(targetBox);

        // Отклонение
        JPanel devBox = new JPanel(new BorderLayout());
        devBox.setBackground(new Color(26, 26, 26));
        deviationLabel = new JLabel("—", SwingConstants.CENTER);
        deviationLabel.setFont(new Font("Arial", Font.BOLD, 26));
        deviationLabel.setForeground(new Color(170, 170, 170));
        devBox.add(deviationLabel, BorderLayout.CENTER);
        JLabel devDesc = new JLabel("📏 Отклонение", SwingConstants.CENTER);
        devDesc.setForeground(new Color(119, 119, 119));
        devBox.add(devDesc, BorderLayout.SOUTH);
        infoPanel.add(devBox);

        bottomPanel.add(infoPanel, BorderLayout.NORTH);

        // MIDI лог
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBackground(new Color(26, 26, 26));
        logPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(255, 82, 82)),
                "📡 MIDI Log",
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font("Arial", Font.BOLD, 12),
                new Color(255, 82, 82)
        ));

        midiLogArea = new JTextArea(6, 80);
        midiLogArea.setEditable(false);
        midiLogArea.setBackground(new Color(10, 10, 10));
        midiLogArea.setForeground(new Color(0, 255, 0));
        midiLogArea.setFont(new Font("Courier New", Font.PLAIN, 11));
        JScrollPane scrollPane = new JScrollPane(midiLogArea);
        scrollPane.setPreferredSize(new Dimension(800, 120));
        logPanel.add(scrollPane, BorderLayout.CENTER);

        bottomPanel.add(logPanel, BorderLayout.SOUTH);

        // Кнопки управления
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 30, 15));
        controlPanel.setBackground(new Color(26, 26, 26));
        controlPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 15, 0));

        startBtn = new JButton("▶️ СТАРТ");
        startBtn.setFont(new Font("Arial", Font.BOLD, 18));
        startBtn.setBackground(new Color(76, 175, 80));
        startBtn.setForeground(Color.WHITE);
        startBtn.setFocusPainted(false);
        startBtn.setBorderPainted(false);
        startBtn.setPreferredSize(new Dimension(160, 55));
        startBtn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(56, 142, 60), 2),
                BorderFactory.createEmptyBorder(5, 15, 5, 15)
        ));
        startBtn.addActionListener(e -> startProcessing());
        controlPanel.add(startBtn);

        stopBtn = new JButton("⏹️ СТОП");
        stopBtn.setFont(new Font("Arial", Font.BOLD, 18));
        stopBtn.setBackground(new Color(244, 67, 54));
        stopBtn.setForeground(Color.WHITE);
        stopBtn.setFocusPainted(false);
        stopBtn.setBorderPainted(false);
        stopBtn.setPreferredSize(new Dimension(160, 55));
        stopBtn.setEnabled(false);
        stopBtn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(183, 28, 28), 2),
                BorderFactory.createEmptyBorder(5, 15, 5, 15)
        ));
        stopBtn.addActionListener(e -> stopProcessing());
        controlPanel.add(stopBtn);

        bottomPanel.add(controlPanel, BorderLayout.SOUTH);

        add(bottomPanel, BorderLayout.SOUTH);
    }

    // -----------------------------------------------------------------------
    public int getVirtualOctave() {
        return virtualOctave;
    }

    public void setVirtualOctave(int octave) {
        if (octave >= 0 && octave <= 8) {
            virtualOctave = octave;
            saveSettings();
        }
    }

    // -----------------------------------------------------------------------
    private void showVirtualPiano() {
        if (virtualPianoFrame == null || !virtualPianoFrame.isVisible()) {
            virtualPianoFrame = new VirtualPianoFrame(this);
            virtualPianoFrame.setOctave(virtualOctave);
            virtualPianoFrame.setVisible(true);
        } else {
            virtualPianoFrame.toFront();
        }
    }

    // -----------------------------------------------------------------------
    private void loadSettings() {
        int savedAudioIn = PREFS.getInt(KEY_INPUT_DEVICE, 0);
        int savedAudioOut = PREFS.getInt(KEY_OUTPUT_DEVICE, 0);
        if (savedAudioIn < audioInputCombo.getItemCount()) audioInputCombo.setSelectedIndex(savedAudioIn);
        if (savedAudioOut < audioOutputCombo.getItemCount()) audioOutputCombo.setSelectedIndex(savedAudioOut);

        String savedMidiIn = PREFS.get(KEY_MIDI_IN_DEVICE, "");
        if (!savedMidiIn.isEmpty()) {
            for (int i = 0; i < midiInCombo.getItemCount(); i++) {
                if (midiInCombo.getItemAt(i).equals(savedMidiIn)) {
                    midiInCombo.setSelectedIndex(i);
                    break;
                }
            }
        }

        String savedMidiOut = PREFS.get(KEY_MIDI_OUT_DEVICE, "");
        if (!savedMidiOut.isEmpty()) {
            for (int i = 0; i < midiOutCombo.getItemCount(); i++) {
                if (midiOutCombo.getItemAt(i).equals(savedMidiOut)) {
                    midiOutCombo.setSelectedIndex(i);
                    break;
                }
            }
        }

        String outMode = PREFS.get(KEY_OUTPUT_MODE, "synth");
        if (outMode.equals("physical")) {
            physicalOutRadio.setSelected(true);
            midiOutCombo.setEnabled(true);
        } else {
            synthOutRadio.setSelected(true);
            midiOutCombo.setEnabled(false);
        }

        autoScrollEnabled = PREFS.getBoolean(KEY_AUTO_SCROLL, true);
        autoScrollCheck.setSelected(autoScrollEnabled);

        boolean detailedLog = PREFS.getBoolean(KEY_DETAILED_LOG, true);
        detailedLogCheck.setSelected(detailedLog);

        virtualOctave = PREFS.getInt(KEY_VIRTUAL_OCTAVE, 4);
    }

    private void saveSettings() {
        if (audioInputCombo.getSelectedIndex() != -1)
            PREFS.putInt(KEY_INPUT_DEVICE, audioInputCombo.getSelectedIndex());
        if (audioOutputCombo.getSelectedIndex() != -1)
            PREFS.putInt(KEY_OUTPUT_DEVICE, audioOutputCombo.getSelectedIndex());
        if (midiInCombo.getSelectedItem() != null)
            PREFS.put(KEY_MIDI_IN_DEVICE, (String) midiInCombo.getSelectedItem());
        if (midiOutCombo.getSelectedItem() != null)
            PREFS.put(KEY_MIDI_OUT_DEVICE, (String) midiOutCombo.getSelectedItem());
        PREFS.put(KEY_OUTPUT_MODE, physicalOutRadio.isSelected() ? "physical" : "synth");
        PREFS.putBoolean(KEY_AUTO_SCROLL, autoScrollEnabled);
        PREFS.putBoolean(KEY_DETAILED_LOG, detailedLogCheck.isSelected());
        PREFS.putInt(KEY_VIRTUAL_OCTAVE, virtualOctave);
    }

    // -----------------------------------------------------------------------
    private void rescanDevices() {
        DataLine.Info targetInfo = new DataLine.Info(TargetDataLine.class, AUDIO_FORMAT);
        DataLine.Info sourceInfo = new DataLine.Info(SourceDataLine.class, AUDIO_FORMAT);
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        List<String> micNames = new ArrayList<>();
        List<String> spkNames = new ArrayList<>();

        for (Mixer.Info info : mixers) {
            Mixer mixer = AudioSystem.getMixer(info);
            if (mixer.isLineSupported(targetInfo)) micNames.add(info.getName());
            if (mixer.isLineSupported(sourceInfo)) spkNames.add(info.getName());
        }

        if (micNames.isEmpty()) micNames.add("❌ Микрофон не найден");
        if (spkNames.isEmpty()) spkNames.add("❌ Динамики не найдены");

        audioInputCombo.setModel(new DefaultComboBoxModel<>(micNames.toArray(new String[0])));
        audioOutputCombo.setModel(new DefaultComboBoxModel<>(spkNames.toArray(new String[0])));

        // MIDI устройства с сохранением Info
        MidiDevice.Info[] midiInfos = MidiSystem.getMidiDeviceInfo();
        midiInputInfos.clear();
        midiOutputInfos.clear();
        List<String> midiInputDisplay = new ArrayList<>();
        List<String> midiOutputDisplay = new ArrayList<>();

        logMidi("🔍 Сканирование MIDI устройств:");
        for (MidiDevice.Info info : midiInfos) {
            try {
                MidiDevice dev = MidiSystem.getMidiDevice(info);
                int maxTx = dev.getMaxTransmitters();
                int maxRx = dev.getMaxReceivers();
                String desc = info.getName() + " (tx=" + maxTx + ", rx=" + maxRx + ")";
                logMidi("   " + desc);
                // Для входа: устройство должно уметь передавать (maxTx != 0)
                if (maxTx != 0) {
                    midiInputInfos.add(info);
                    midiInputDisplay.add(desc);
                }
                // Для выхода: устройство должно уметь принимать (maxRx != 0), исключаем синтезатор Gervill (он дублируется)
                if (maxRx != 0 && !(dev instanceof Synthesizer && info.getName().equals("Gervill"))) {
                    midiOutputInfos.add(info);
                    midiOutputDisplay.add(desc);
                }
            } catch (MidiUnavailableException ignored) {
                logMidi("   ❌ " + info.getName() + " недоступно");
            }
        }

        if (midiInputDisplay.isEmpty()) {
            midiInputDisplay.add("❌ MIDI входы не найдены");
            midiInputInfos.clear();
        }
        midiInCombo.setModel(new DefaultComboBoxModel<>(midiInputDisplay.toArray(new String[0])));

        if (midiOutputDisplay.isEmpty()) {
            midiOutputDisplay.add("❌ MIDI выходы не найдены");
            midiOutputInfos.clear();
        }
        midiOutCombo.setModel(new DefaultComboBoxModel<>(midiOutputDisplay.toArray(new String[0])));

        logMidi("✅ Список устройств обновлён.");
    }

    // -----------------------------------------------------------------------
    private void testAudioOutput() {
        String selectedAudioOut = (String) audioOutputCombo.getSelectedItem();
        if (selectedAudioOut == null || selectedAudioOut.contains("❌")) {
            JOptionPane.showMessageDialog(this, "Выберите корректное аудиоустройство вывода", "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            Mixer.Info[] mixers = AudioSystem.getMixerInfo();
            Mixer selectedMixer = null;
            for (Mixer.Info info : mixers) {
                if (info.getName().equals(selectedAudioOut)) {
                    selectedMixer = AudioSystem.getMixer(info);
                    break;
                }
            }
            if (selectedMixer == null) {
                logMidi("❌ Не найден выбранный аудиовыход");
                return;
            }
            SourceDataLine line = (SourceDataLine) selectedMixer.getLine(new DataLine.Info(SourceDataLine.class, AUDIO_FORMAT));
            line.open(AUDIO_FORMAT);
            line.start();
            byte[] buffer = new byte[(int) (AUDIO_FORMAT.getSampleRate() / 2)];
            for (int i = 0; i < buffer.length / 2; i++) {
                double angle = 2.0 * Math.PI * 440 * i / AUDIO_FORMAT.getSampleRate();
                short sample = (short) (Math.sin(angle) * Short.MAX_VALUE * 0.5);
                buffer[2 * i] = (byte) (sample & 0xFF);
                buffer[2 * i + 1] = (byte) ((sample >> 8) & 0xFF);
            }
            line.write(buffer, 0, buffer.length);
            line.drain();
            line.close();
            logMidi("🔊 Тестовый тон воспроизведён через " + selectedAudioOut);
        } catch (Exception e) {
            logMidi("❌ Ошибка теста аудиовыхода: " + e.getMessage());
        }
    }

    private void testMidiOutput() {
        if (synthReceiver == null) {
            initSynthesizer();
        }
        try {
            if (physicalOutRadio.isSelected()) {
                int outIdx = midiOutCombo.getSelectedIndex();
                if (outIdx < 0 || outIdx >= midiOutputInfos.size()) {
                    JOptionPane.showMessageDialog(this, "Выберите физический MIDI выход", "Ошибка", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                MidiDevice.Info info = midiOutputInfos.get(outIdx);
                MidiDevice tempDevice = MidiSystem.getMidiDevice(info);
                tempDevice.open();
                Receiver tempReceiver = tempDevice.getReceiver();
                ShortMessage onMsg = new ShortMessage();
                onMsg.setMessage(ShortMessage.NOTE_ON, 0, 69, 100);
                tempReceiver.send(onMsg, -1);
                logMidi("🎹 Тест MIDI отправлен (нота Ля) на " + info.getName());

                // Таймер для NOTE_OFF и закрытия
                final Receiver rec = tempReceiver;
                final MidiDevice dev = tempDevice;
                new Timer(500, e -> {
                    try {
                        ShortMessage offMsg = new ShortMessage();
                        offMsg.setMessage(ShortMessage.NOTE_OFF, 0, 69, 0);
                        rec.send(offMsg, -1);
                    } catch (InvalidMidiDataException ex) {
                        logMidi("❌ Ошибка NOTE_OFF: " + ex.getMessage());
                    } catch (IllegalStateException ex) {
                        logMidi("❌ Receiver уже закрыт: " + ex.getMessage());
                    } finally {
                        rec.close();
                        dev.close();
                    }
                }).start();
            } else {
                ShortMessage onMsg = new ShortMessage();
                onMsg.setMessage(ShortMessage.NOTE_ON, 0, 69, 100);
                synthReceiver.send(onMsg, -1);
                logMidi("🎹 Тест MIDI отправлен (нота Ля) на синтезатор");
                new Timer(500, e -> {
                    try {
                        ShortMessage offMsg = new ShortMessage();
                        offMsg.setMessage(ShortMessage.NOTE_OFF, 0, 69, 0);
                        synthReceiver.send(offMsg, -1);
                    } catch (InvalidMidiDataException ex) {
                        logMidi("❌ Ошибка NOTE_OFF: " + ex.getMessage());
                    }
                }).start();
            }
        } catch (Exception e) {
            logMidi("❌ Ошибка теста MIDI: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    private void startProcessing() {
        stopProcessing();
        isRunning = true;
        sessionStartTime = System.currentTimeMillis();
        pitchHistory.clear();
        saveSettings();

        // ---- Микрофон ----
        String micName = (String) audioInputCombo.getSelectedItem();
        if (micName != null && !micName.contains("❌")) {
            try {
                Mixer.Info[] mixers = AudioSystem.getMixerInfo();
                for (Mixer.Info info : mixers) {
                    if (info.getName().equals(micName)) {
                        Mixer mixer = AudioSystem.getMixer(info);
                        microphoneLine = (TargetDataLine) mixer.getLine(
                                new DataLine.Info(TargetDataLine.class, AUDIO_FORMAT));
                        microphoneLine.open(AUDIO_FORMAT, 4096);
                        microphoneLine.start();
                        logMidi("✅ Микрофон запущен: " + micName);
                        break;
                    }
                }
            } catch (Exception e) {
                logMidi("❌ Ошибка открытия микрофона: " + e.getMessage());
            }
        } else {
            logMidi("⚠️ Микрофон не выбран или недоступен");
        }

        // ---- MIDI вход ----
        int midiInIdx = midiInCombo.getSelectedIndex();
        if (midiInIdx >= 0 && midiInIdx < midiInputInfos.size()) {
            MidiDevice.Info info = midiInputInfos.get(midiInIdx);
            try {
                midiInputDevice = MidiSystem.getMidiDevice(info);
                int maxTx = midiInputDevice.getMaxTransmitters();
                logMidi("🔧 MIDI вход: " + info.getName() + ", maxTransmitters=" + maxTx);
                if (maxTx == 0) {
                    logMidi("⚠️ Устройство имеет maxTransmitters=0, но попробуем открыть...");
                }
                midiInputDevice.open();
                logMidi("🔧 MIDI вход открыт: " + info.getName());

                midiRouter = new MidiRouter();
                midiTransmitter = midiInputDevice.getTransmitter();
                midiTransmitter.setReceiver(midiRouter);
                logMidi("✅ MIDI вход подключён к маршрутизатору");
            } catch (MidiUnavailableException e) {
                logMidi("❌ Не удалось открыть MIDI вход: " + e.getMessage());
                if (midiInputDevice != null) {
                    midiInputDevice.close();
                    midiInputDevice = null;
                }
                midiRouter = null;
            }
        } else {
            logMidi("⚠️ MIDI вход не выбран");
        }

        // ---- MIDI выход ----
        if (physicalOutRadio.isSelected()) {
            int midiOutIdx = midiOutCombo.getSelectedIndex();
            if (midiOutIdx >= 0 && midiOutIdx < midiOutputInfos.size()) {
                MidiDevice.Info info = midiOutputInfos.get(midiOutIdx);
                try {
                    midiOutputDevice = MidiSystem.getMidiDevice(info);
                    midiOutputDevice.open();
                    outputReceiver = midiOutputDevice.getReceiver();
                    logMidi("✅ Физический MIDI выход открыт: " + info.getName());
                } catch (MidiUnavailableException e) {
                    logMidi("❌ Не удалось открыть физический MIDI выход: " + e.getMessage());
                }
            } else {
                logMidi("⚠️ Физический MIDI выход не выбран");
            }
        } else {
            // Используем синтезатор
            if (synthReceiver == null) {
                initSynthesizer();
            }
            outputReceiver = synthReceiver;
            logMidi("✅ Используется синтезатор как MIDI выход");
        }

        // Передаём outputReceiver в маршрутизатор
        if (midiRouter != null) {
            midiRouter.setOutputReceiver(outputReceiver);
        }

        // ---- Запуск потока аудио ----
        if (microphoneLine != null) {
            new Thread(new AudioProcessor()).start();
        }

        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);

        logMidi("🟢 Обработка запущена!");
    }

    private void stopProcessing() {
        isRunning = false;

        if (microphoneLine != null) {
            microphoneLine.stop();
            microphoneLine.close();
            microphoneLine = null;
        }

        // Закрываем MIDI вход
        if (midiTransmitter != null) {
            midiTransmitter.close();
            midiTransmitter = null;
        }
        if (midiInputDevice != null) {
            midiInputDevice.close();
            midiInputDevice = null;
        }
        midiRouter = null;

        // Закрываем MIDI выход (физический)
        if (midiOutputDevice != null) {
            midiOutputDevice.close();
            midiOutputDevice = null;
        }
        outputReceiver = null; // синтезатор не закрываем

        currentMidiNote = null;
        currentMidiVelocity = null;
        currentPitchMidi = null;
        currentVocalNote = "—";
        pitchHistory.clear();
        pianoPanel.repaint();

        SwingUtilities.invokeLater(() -> {
            midiNoteLabel.setText("—");
            midiVelocityLabel.setText("громкость: —");
            vocalLabel.setText("—");
            targetLabel.setText("—");
            deviationLabel.setText("—");
        });

        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);

        logMidi("⏹️ Обработка остановлена");
    }

    // -----------------------------------------------------------------------
    private class MidiRouter implements Receiver {
        private Receiver outputReceiver;

        public void setOutputReceiver(Receiver outputReceiver) {
            this.outputReceiver = outputReceiver;
            logMidi("🔀 Маршрутизатор: outputReceiver установлен");
        }

        @Override
        public void send(MidiMessage message, long timeStamp) {
            boolean detailedLog = detailedLogCheck.isSelected();
            if (detailedLog && message instanceof ShortMessage) {
                ShortMessage sm = (ShortMessage) message;
                int command = sm.getCommand();
                int note = sm.getData1();
                int velocity = sm.getData2();
                String msgType;
                if (command == ShortMessage.NOTE_ON) msgType = "NOTE_ON";
                else if (command == ShortMessage.NOTE_OFF) msgType = "NOTE_OFF";
                else msgType = "OTHER";
                logMidi("📩 MIDI raw: " + msgType + " ch=" + sm.getChannel() +
                        " note=" + note + " vel=" + velocity);
            }

            if (isRunning && outputReceiver != null) {
                try {
                    outputReceiver.send(message, timeStamp);
                    if (detailedLog && message instanceof ShortMessage && ((ShortMessage) message).getCommand() == ShortMessage.NOTE_ON) {
                        logMidi("🔊 Перенаправлено на выход");
                    }
                } catch (IllegalStateException e) {
                    logMidi("❌ Ошибка отправки: receiver закрыт");
                }
            } else {
                if (detailedLog) {
                    if (!isRunning) logMidi("⏸️ Пропущено: isRunning=false");
                    if (outputReceiver == null) logMidi("⏸️ Пропущено: outputReceiver=null");
                }
            }

            if (isRunning && message instanceof ShortMessage) {
                ShortMessage sm = (ShortMessage) message;
                int command = sm.getCommand();
                int note = sm.getData1();
                int velocity = sm.getData2();

                if (command == ShortMessage.NOTE_ON && velocity > 0) {
                    SwingUtilities.invokeLater(() -> {
                        currentMidiNote = note;
                        currentMidiVelocity = velocity;
                        if (autoScrollEnabled) {
                            targetCenter = note;
                        }
                        midiNoteLabel.setText(noteToName(note));
                        midiVelocityLabel.setText("громкость: " + velocity);
                        logMidi("🎹 NOTE ON: " + noteToName(note) + " (MIDI " + note + "), velocity=" + velocity);
                    });
                } else if (command == ShortMessage.NOTE_OFF ||
                        (command == ShortMessage.NOTE_ON && velocity == 0)) {
                    if (currentMidiNote != null && currentMidiNote.equals(note)) {
                        SwingUtilities.invokeLater(() -> {
                            currentMidiNote = null;
                            currentMidiVelocity = null;
                            midiNoteLabel.setText("—");
                            midiVelocityLabel.setText("громкость: —");
                        });
                    }
                    logMidi("🎹 NOTE OFF: " + noteToName(note) + " (MIDI " + note + ")");
                }
            }
        }

        @Override
        public void close() {}
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================
    private void updateIndicators() {
        vocalLabel.setText(currentVocalNote);
        targetLabel.setText(currentMidiNote == null ? "—" : noteToName(currentMidiNote));

        if (currentPitchMidi != null && currentMidiNote != null) {
            float cents = (currentPitchMidi - currentMidiNote) * 100;
            String symbol;
            Color color;
            if (Math.abs(cents) < 15) {
                symbol = "✓";
                color = new Color(76, 175, 80);
            } else if (Math.abs(cents) < 40) {
                symbol = "△";
                color = new Color(255, 152, 0);
            } else {
                symbol = "✗";
                color = Color.RED;
            }
            deviationLabel.setText(String.format("%s %+.0f¢", symbol, cents));
            deviationLabel.setForeground(color);
        } else {
            deviationLabel.setText("—");
            deviationLabel.setForeground(new Color(136, 136, 136));
        }
    }

    private void smoothScroll() {
        if (autoScrollEnabled) {
            viewCenter += (targetCenter - viewCenter) * 0.1;
            if (viewCenter < 40) viewCenter = 40;
            if (viewCenter > 90) viewCenter = 90;
        }
    }

    private void logMidi(String message) {
        SwingUtilities.invokeLater(() -> {
            String time = String.format("[%tT]", System.currentTimeMillis());
            midiLogArea.append(time + " " + message + "\n");
            midiLogArea.setCaretPosition(midiLogArea.getDocument().getLength());
            System.out.println("MIDI LOG: " + message);
        });
    }

    // ==================== ДЕТЕКТОР ВЫСОТЫ ТОНА ====================
    private static class PitchDetector {
        private final float sampleRate;
        PitchDetector(AudioFormat format) { this.sampleRate = format.getSampleRate(); }

        public float detectPitch(float[] samples) {
            float maxAbs = 0;
            for (float s : samples) maxAbs = Math.max(maxAbs, Math.abs(s));
            if (maxAbs < 0.02f) return -1;

            int startLag = (int) (sampleRate / 1200);
            int endLag = (int) (sampleRate / 70);
            if (startLag >= endLag || endLag >= samples.length) return -1;

            float[] autocorr = new float[endLag];
            for (int lag = startLag; lag < endLag; lag++) {
                float sum = 0;
                for (int i = 0; i < samples.length - lag; i++)
                    sum += samples[i] * samples[i + lag];
                autocorr[lag] = sum;
            }

            int peakIdx = startLag;
            for (int lag = startLag + 1; lag < endLag; lag++)
                if (autocorr[lag] > autocorr[peakIdx]) peakIdx = lag;

            if (autocorr[peakIdx] < 0.3f * autocorr[0]) return -1;
            return sampleRate / (float) peakIdx;
        }
    }

    // ==================== ПОТОК ОБРАБОТКИ АУДИО ====================
    private class AudioProcessor implements Runnable {
        @Override
        public void run() {
            byte[] buffer = new byte[2048];
            float[] floatBuffer = new float[buffer.length / 2];

            while (isRunning && microphoneLine != null) {
                int bytesRead = microphoneLine.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {
                    for (int i = 0; i < floatBuffer.length; i++) {
                        short s = (short) ((buffer[i * 2] & 0xFF) | (buffer[i * 2 + 1] << 8));
                        floatBuffer[i] = s / 32768.0f;
                    }

                    float freq = pitchDetector.detectPitch(floatBuffer);
                    long currentTime = System.currentTimeMillis();
                    float relativeTime = (currentTime - sessionStartTime) / 1000.0f;

                    if (freq > 0) {
                        double midi = 69 + 12 * Math.log(freq / 440.0) / Math.log(2);
                        float midiFloat = (float) midi;
                        currentPitchMidi = midiFloat;
                        currentVocalNote = noteToName(Math.round(midiFloat));
                        if (currentMidiNote == null && autoScrollEnabled) {
                            targetCenter = midiFloat;
                        }
                        synchronized (pitchHistory) {
                            pitchHistory.add(new PitchPoint(relativeTime, midiFloat));
                            if (pitchHistory.size() > 500) pitchHistory.removeFirst();
                        }
                    } else {
                        currentPitchMidi = null;
                        currentVocalNote = "—";
                        synchronized (pitchHistory) {
                            pitchHistory.add(new PitchPoint(relativeTime, null));
                            if (pitchHistory.size() > 500) pitchHistory.removeFirst();
                        }
                    }
                }
            }
        }
    }

    private static class PitchPoint {
        float time;
        Float pitch;
        PitchPoint(float time, Float pitch) { this.time = time; this.pitch = pitch; }
    }

    // ==================== ПАНЕЛЬ ПИАНИНО-РОЛЛА ====================
    private class PianoRollPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            if (w < 100 || h < 100) return;

            int minMidi = (int) (viewCenter - 18);
            int maxMidi = (int) (viewCenter + 18);
            if (minMidi < 36) minMidi = 36;
            if (maxMidi > 96) maxMidi = 96;
            int noteRange = maxMidi - minMidi;
            float noteHeight = (float) h / noteRange;

            String[] noteNames = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

            for (int i = 0; i <= noteRange; i++) {
                int midi = minMidi + i;
                int y0 = h - (int) ((i + 1) * noteHeight);
                int y1 = h - (int) (i * noteHeight);
                int height = y1 - y0;

                boolean isSharp = (midi % 12 == 1 || midi % 12 == 3 || midi % 12 == 6 ||
                        midi % 12 == 8 || midi % 12 == 10);
                g2.setColor(isSharp ? new Color(34, 34, 34) : new Color(68, 68, 68));
                g2.fillRect(0, y0, w, height);

                if (!isSharp) {
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font("Arial", Font.BOLD, 11));
                    g2.drawString(noteNames[midi % 12] + (midi / 12 - 1), 10, y0 + height / 2 + 4);
                }
            }

            synchronized (pitchHistory) {
                if (pitchHistory.size() > 1) {
                    long now = System.currentTimeMillis() - sessionStartTime;
                    float currentTimeSec = now / 1000.0f;
                    float minTime = currentTimeSec - 5.0f;
                    List<Point> points = new ArrayList<>();
                    for (PitchPoint pp : pitchHistory) {
                        if (pp.time >= minTime && pp.pitch != null &&
                                pp.pitch >= minMidi && pp.pitch <= maxMidi) {
                            int x = (int) ((pp.time - minTime) / 5.0f * w);
                            int y = h - (int) ((pp.pitch - minMidi) / (float) noteRange * h);
                            points.add(new Point(x, y));
                        }
                    }
                    if (points.size() > 1) {
                        g2.setColor(new Color(0, 204, 255));
                        g2.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        for (int i = 0; i < points.size() - 1; i++) {
                            Point p1 = points.get(i);
                            Point p2 = points.get(i + 1);
                            g2.drawLine(p1.x, p1.y, p2.x, p2.y);
                        }
                    }
                }
            }

            if (currentMidiNote != null && currentMidiNote >= minMidi && currentMidiNote <= maxMidi) {
                int y = h - (int) ((currentMidiNote - minMidi) / (float) noteRange * h);
                g2.setColor(new Color(255, 215, 0));
                g2.setStroke(new BasicStroke(6, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
                        0, new float[]{7, 4}, 0));
                g2.drawLine(0, y, w, y);
            }
        }
    }
}

// =====================================================================
// Виртуальная пианино-клавиатура
// =====================================================================
class VirtualPianoFrame extends JFrame {
    private final VocalTrainer parent;
    private VirtualPianoPanel pianoPanel;
    private int octave = 4;

    public VirtualPianoFrame(VocalTrainer parent) {
        super("Виртуальное пианино");
        this.parent = parent;
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(800, 300);
        setLocationRelativeTo(parent);

        pianoPanel = new VirtualPianoPanel(parent);
        add(pianoPanel, BorderLayout.CENTER);

        JPanel controlPanel = new JPanel();
        controlPanel.setBackground(new Color(60, 60, 60));
        JButton octDown = new JButton("Октава -");
        octDown.addActionListener(e -> {
            if (octave > 0) {
                octave--;
                pianoPanel.setOctave(octave);
                parent.setVirtualOctave(octave);
            }
        });
        JButton octUp = new JButton("Октава +");
        octUp.addActionListener(e -> {
            if (octave < 8) {
                octave++;
                pianoPanel.setOctave(octave);
                parent.setVirtualOctave(octave);
            }
        });
        controlPanel.add(octDown);
        controlPanel.add(octUp);
        add(controlPanel, BorderLayout.SOUTH);

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                pianoPanel.handleKeyPress(e);
            }
            @Override
            public void keyReleased(KeyEvent e) {
                pianoPanel.handleKeyRelease(e);
            }
        });
        setFocusable(true);
    }

    public void setOctave(int octave) {
        this.octave = octave;
        pianoPanel.setOctave(octave);
    }
}

class VirtualPianoPanel extends JPanel {
    private final VocalTrainer parent;
    private int octave = 4;
    private final int whiteKeys = 14; // от C до B следующей октавы (2 октавы)
    private final int blackKeys = 10;
    private final int whiteKeyWidth = 50;
    private final int whiteKeyHeight = 150;
    private final int blackKeyWidth = 30;
    private final int blackKeyHeight = 90;

    private static final char[] whiteKeyChars = {'a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l', ';', '\''};
    private static final char[] blackKeyChars = {'w', 'e', 't', 'y', 'u'};
    private static final int[] blackKeyOffsets = {1, 3, 6, 8, 10};

    private final boolean[] whitePressed = new boolean[whiteKeys];
    private final boolean[] blackPressed = new boolean[blackKeys];

    public VirtualPianoPanel(VocalTrainer parent) {
        this.parent = parent;
        setPreferredSize(new Dimension(whiteKeys * whiteKeyWidth, whiteKeyHeight + 30));
        setBackground(Color.DARK_GRAY);
        setFocusable(true);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleMousePress(e.getX(), e.getY(), true);
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                handleMousePress(e.getX(), e.getY(), false);
            }
        });
    }

    public void setOctave(int octave) {
        this.octave = octave;
        repaint();
    }

    private int getMidiNoteFromPos(int x, int y) {
        int whiteIndex = x / whiteKeyWidth;
        if (whiteIndex >= whiteKeys) return -1;
        if (y < blackKeyHeight) {
            for (int i = 0; i < blackKeys; i++) {
                int blackX = (blackKeyOffsets[i] + 1) * whiteKeyWidth - blackKeyWidth / 2;
                if (x >= blackX && x < blackX + blackKeyWidth) {
                    return getMidiForBlackKey(i);
                }
            }
        }
        return getMidiForWhiteKey(whiteIndex);
    }

    private int getMidiForWhiteKey(int index) {
        int noteInOctave = 0;
        if (index % 7 == 0) noteInOctave = 0; // C
        else if (index % 7 == 1) noteInOctave = 2; // D
        else if (index % 7 == 2) noteInOctave = 4; // E
        else if (index % 7 == 3) noteInOctave = 5; // F
        else if (index % 7 == 4) noteInOctave = 7; // G
        else if (index % 7 == 5) noteInOctave = 9; // A
        else if (index % 7 == 6) noteInOctave = 11; // B
        int octaveOffset = octave + (index / 7);
        return octaveOffset * 12 + noteInOctave;
    }

    private int getMidiForBlackKey(int index) {
        int[] offsets = {1, 3, 6, 8, 10};
        int noteInOctave = offsets[index];
        int whiteBase = 0;
        if (index == 0) whiteBase = 0;
        else if (index == 1) whiteBase = 1;
        else if (index == 2) whiteBase = 3;
        else if (index == 3) whiteBase = 4;
        else if (index == 4) whiteBase = 5;
        int octaveOffset = octave + (whiteBase / 7);
        return octaveOffset * 12 + noteInOctave;
    }

    private void handleMousePress(int x, int y, boolean press) {
        int note = getMidiNoteFromPos(x, y);
        if (note != -1) {
            sendNote(note, press ? 100 : 0);
            repaint();
        }
    }

    public void handleKeyPress(KeyEvent e) {
        char key = e.getKeyChar();
        for (int i = 0; i < whiteKeyChars.length; i++) {
            if (key == whiteKeyChars[i]) {
                if (!whitePressed[i]) {
                    whitePressed[i] = true;
                    int note = getMidiForWhiteKey(i);
                    sendNote(note, 100);
                }
                break;
            }
        }
        for (int i = 0; i < blackKeyChars.length; i++) {
            if (key == blackKeyChars[i]) {
                if (!blackPressed[i]) {
                    blackPressed[i] = true;
                    int note = getMidiForBlackKey(i);
                    sendNote(note, 100);
                }
                break;
            }
        }
        if (e.getKeyCode() == KeyEvent.VK_Z) {
            if (octave > 0) {
                octave--;
                parent.setVirtualOctave(octave);
                repaint();
            }
        } else if (e.getKeyCode() == KeyEvent.VK_X) {
            if (octave < 8) {
                octave++;
                parent.setVirtualOctave(octave);
                repaint();
            }
        }
    }

    public void handleKeyRelease(KeyEvent e) {
        char key = e.getKeyChar();
        for (int i = 0; i < whiteKeyChars.length; i++) {
            if (key == whiteKeyChars[i]) {
                if (whitePressed[i]) {
                    whitePressed[i] = false;
                    int note = getMidiForWhiteKey(i);
                    sendNote(note, 0);
                }
                break;
            }
        }
        for (int i = 0; i < blackKeyChars.length; i++) {
            if (key == blackKeyChars[i]) {
                if (blackPressed[i]) {
                    blackPressed[i] = false;
                    int note = getMidiForBlackKey(i);
                    sendNote(note, 0);
                }
                break;
            }
        }
    }

    private void sendNote(int note, int velocity) {
        // Используем публичные методы доступа
        Receiver router = parent.getMidiRouter();
        if (router != null) {
            try {
                ShortMessage msg = new ShortMessage();
                if (velocity > 0) {
                    msg.setMessage(ShortMessage.NOTE_ON, 0, note, velocity);
                } else {
                    msg.setMessage(ShortMessage.NOTE_OFF, 0, note, 0);
                }
                router.send(msg, -1);
            } catch (InvalidMidiDataException e) {
                e.printStackTrace();
            }
        } else {
            Receiver synth = parent.getSynthReceiver();
            if (synth != null) {
                try {
                    ShortMessage msg = new ShortMessage();
                    if (velocity > 0) {
                        msg.setMessage(ShortMessage.NOTE_ON, 0, note, velocity);
                    } else {
                        msg.setMessage(ShortMessage.NOTE_OFF, 0, note, 0);
                    }
                    synth.send(msg, -1);
                } catch (InvalidMidiDataException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        for (int i = 0; i < whiteKeys; i++) {
            int x = i * whiteKeyWidth;
            g2.setColor(whitePressed[i] ? Color.LIGHT_GRAY : Color.WHITE);
            g2.fillRect(x, 0, whiteKeyWidth - 1, whiteKeyHeight);
            g2.setColor(Color.BLACK);
            g2.drawRect(x, 0, whiteKeyWidth - 1, whiteKeyHeight);
            int note = getMidiForWhiteKey(i);
            String noteName = parent.noteToName(note);
            g2.setColor(Color.BLACK);
            g2.setFont(new Font("Arial", Font.PLAIN, 12));
            g2.drawString(noteName, x + 5, whiteKeyHeight - 10);
        }

        for (int i = 0; i < blackKeys; i++) {
            int whiteBase = 0;
            if (i == 0) whiteBase = 0;
            else if (i == 1) whiteBase = 1;
            else if (i == 2) whiteBase = 3;
            else if (i == 3) whiteBase = 4;
            else if (i == 4) whiteBase = 5;
            int x = (whiteBase + 1) * whiteKeyWidth - blackKeyWidth / 2;
            g2.setColor(blackPressed[i] ? Color.DARK_GRAY : Color.BLACK);
            g2.fillRect(x, 0, blackKeyWidth, blackKeyHeight);
            g2.setColor(Color.WHITE);
            g2.drawRect(x, 0, blackKeyWidth, blackKeyHeight);
            int note = getMidiForBlackKey(i);
            String noteName = parent.noteToName(note);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial", Font.PLAIN, 10));
            g2.drawString(noteName, x + 5, blackKeyHeight - 10);
        }

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 16));
        g2.drawString("Октава: " + octave, 10, whiteKeyHeight + 20);
    }
}