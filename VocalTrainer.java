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
    private static final String KEY_INPUT_DEVICE  = "input_device";
    private static final String KEY_OUTPUT_DEVICE = "output_device";
    private static final String KEY_MIDI_IN_DEVICE   = "midi_in_device";
    private static final String KEY_MIDI_OUT_DEVICE  = "midi_out_device";
    private static final String KEY_AUTO_SCROLL   = "auto_scroll";
    private static final String KEY_OUTPUT_MODE   = "output_mode";

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
    private MidiRouter midiRouter;               // наш маршрутизатор
    private volatile Integer currentMidiNote = null;
    private volatile Integer currentMidiVelocity = null;

    // Выходные устройства
    private Synthesizer synthesizer;
    private Receiver synthReceiver;
    private MidiDevice midiOutputDevice;
    private Receiver outputReceiver;               // общий приёмник для выхода

    // Визуализация и скролл
    private PianoRollPanel pianoPanel;
    private double viewCenter = 60.0;
    private double targetCenter = 60.0;
    private volatile boolean autoScrollEnabled = true;

    // GUI
    private JComboBox<String> audioInputCombo;
    private JComboBox<String> audioOutputCombo;
    private JComboBox<String> midiInCombo;
    private JComboBox<String> midiOutCombo;
    private JRadioButton physicalOutRadio;
    private JRadioButton synthOutRadio;
    private ButtonGroup outGroup;
    private JCheckBox autoScrollCheck;
    private JLabel midiNoteLabel;
    private JLabel midiVelocityLabel;
    private JLabel vocalLabel;
    private JLabel targetLabel;
    private JLabel deviationLabel;
    private JTextArea midiLogArea;
    private JButton startBtn, stopBtn;

    private Timer repaintTimer;
    private Timer scrollTimer;

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
                stopProcessing();
                saveSettings();
                closeAllDevices();
            }
        });
    }

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
                    ch.programChange(0); // Acoustic Grand Piano
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

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBackground(new Color(26, 26, 26));

        JLabel title = new JLabel("🎤🎹 VOCAL TRAINER", SwingConstants.CENTER);
        title.setFont(new Font("Helvetica", Font.BOLD, 28));
        title.setForeground(new Color(76, 175, 80));
        title.setBackground(new Color(26, 26, 26));
        title.setOpaque(true);
        title.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));
        topPanel.add(title, BorderLayout.NORTH);

        JPanel settingsPanel = new JPanel(new GridBagLayout());
        settingsPanel.setBackground(new Color(37, 37, 37));
        settingsPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Color.WHITE),
                "⚙️ Настройки",
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
        testAudioBtn.setBackground(new Color(85, 85, 85));
        testAudioBtn.setForeground(Color.WHITE);
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
        rescanBtn.setBackground(new Color(68, 68, 68));
        rescanBtn.setForeground(Color.WHITE);
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
        testMidiBtn.setBackground(new Color(85, 85, 85));
        testMidiBtn.setForeground(Color.WHITE);
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

        topPanel.add(settingsPanel, BorderLayout.CENTER);

        // Индикатор нажатой MIDI-клавиши
        JPanel midiStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 5));
        midiStatusPanel.setBackground(new Color(26, 26, 26));
        midiStatusPanel.setBorder(BorderFactory.createEmptyBorder(5, 20, 5, 20));

        JLabel midiStatusTitle = new JLabel("🎹 Нажата клавиша:");
        midiStatusTitle.setFont(new Font("Arial", Font.BOLD, 14));
        midiStatusTitle.setForeground(new Color(255, 215, 0));
        midiStatusPanel.add(midiStatusTitle);

        midiNoteLabel = new JLabel("—");
        midiNoteLabel.setFont(new Font("Arial", Font.BOLD, 28));
        midiNoteLabel.setForeground(new Color(255, 215, 0));
        midiNoteLabel.setPreferredSize(new Dimension(100, 40));
        midiStatusPanel.add(midiNoteLabel);

        midiVelocityLabel = new JLabel("громкость: —");
        midiVelocityLabel.setFont(new Font("Arial", Font.PLAIN, 14));
        midiVelocityLabel.setForeground(new Color(170, 170, 170));
        midiStatusPanel.add(midiVelocityLabel);

        topPanel.add(midiStatusPanel, BorderLayout.SOUTH);
        add(topPanel, BorderLayout.NORTH);

        // Пианино-ролл
        pianoPanel = new PianoRollPanel();
        pianoPanel.setBackground(new Color(15, 15, 15));
        pianoPanel.setPreferredSize(new Dimension(1000, 450));
        add(pianoPanel, BorderLayout.CENTER);

        // Нижняя панель
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBackground(new Color(26, 26, 26));

        JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 30, 10));
        infoPanel.setBackground(new Color(26, 26, 26));
        infoPanel.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

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

        // MIDI
        MidiDevice.Info[] midiInfos = MidiSystem.getMidiDeviceInfo();
        List<String> midiInputNames = new ArrayList<>();
        List<String> midiOutputNames = new ArrayList<>();
        for (MidiDevice.Info info : midiInfos) {
            try {
                MidiDevice dev = MidiSystem.getMidiDevice(info);
                if (dev.getMaxTransmitters() != 0) {
                    midiInputNames.add(info.getName());
                }
                if (dev.getMaxReceivers() != 0 && !(dev instanceof Synthesizer)) {
                    midiOutputNames.add(info.getName());
                }
            } catch (MidiUnavailableException ignored) {}
        }

        if (midiInputNames.isEmpty()) midiInputNames.add("❌ MIDI входы не найдены");
        midiInCombo.setModel(new DefaultComboBoxModel<>(midiInputNames.toArray(new String[0])));

        if (midiOutputNames.isEmpty()) midiOutputNames.add("❌ MIDI выходы не найдены");
        midiOutCombo.setModel(new DefaultComboBoxModel<>(midiOutputNames.toArray(new String[0])));

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
        final Receiver[] testReceiver = new Receiver[1];
        final MidiDevice[] tempDevice = new MidiDevice[1];
        try {
            if (physicalOutRadio.isSelected()) {
                String outName = (String) midiOutCombo.getSelectedItem();
                if (outName == null || outName.contains("❌")) {
                    JOptionPane.showMessageDialog(this, "Выберите физический MIDI выход", "Ошибка", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
                for (MidiDevice.Info info : infos) {
                    if (info.getName().equals(outName)) {
                        tempDevice[0] = MidiSystem.getMidiDevice(info);
                        tempDevice[0].open();
                        testReceiver[0] = tempDevice[0].getReceiver();
                        break;
                    }
                }
                if (testReceiver[0] == null) {
                    logMidi("❌ Не удалось открыть физический MIDI выход");
                    return;
                }
            } else {
                if (synthReceiver == null) {
                    logMidi("❌ Синтезатор не доступен");
                    return;
                }
                testReceiver[0] = synthReceiver;
            }

            ShortMessage onMsg = new ShortMessage();
            onMsg.setMessage(ShortMessage.NOTE_ON, 0, 69, 100);
            testReceiver[0].send(onMsg, -1);
            logMidi("🎹 Тест MIDI отправлен (нота Ля)");

            new Timer(500, e -> {
                try {
                    ShortMessage offMsg = new ShortMessage();
                    offMsg.setMessage(ShortMessage.NOTE_OFF, 0, 69, 0);
                    testReceiver[0].send(offMsg, -1);
                    if (tempDevice[0] != null) {
                        tempDevice[0].close();
                    }
                } catch (InvalidMidiDataException ex) {
                    logMidi("❌ Ошибка NOTE_OFF: " + ex.getMessage());
                }
            }).start();

        } catch (Exception e) {
            logMidi("❌ Ошибка теста MIDI: " + e.getMessage());
            if (tempDevice[0] != null) tempDevice[0].close();
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
        String midiInName = (String) midiInCombo.getSelectedItem();
        if (midiInName != null && !midiInName.contains("❌")) {
            try {
                MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
                for (MidiDevice.Info info : infos) {
                    if (info.getName().equals(midiInName)) {
                        midiInputDevice = MidiSystem.getMidiDevice(info);
                        midiInputDevice.open();
                        logMidi("🔧 MIDI вход открыт: " + midiInName);

                        // Создаём маршрутизатор
                        midiRouter = new MidiRouter();
                        midiTransmitter = midiInputDevice.getTransmitter();
                        midiTransmitter.setReceiver(midiRouter);
                        logMidi("✅ MIDI вход подключён к маршрутизатору");
                        break;
                    }
                }
            } catch (MidiUnavailableException e) {
                logMidi("❌ Не удалось открыть MIDI вход: " + e.getMessage());
            }
        } else {
            logMidi("⚠️ MIDI вход не выбран");
        }

        // ---- MIDI выход ----
        if (physicalOutRadio.isSelected()) {
            String midiOutName = (String) midiOutCombo.getSelectedItem();
            if (midiOutName != null && !midiOutName.contains("❌")) {
                try {
                    MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
                    for (MidiDevice.Info info : infos) {
                        if (info.getName().equals(midiOutName)) {
                            midiOutputDevice = MidiSystem.getMidiDevice(info);
                            midiOutputDevice.open();
                            outputReceiver = midiOutputDevice.getReceiver();
                            logMidi("✅ Физический MIDI выход открыт: " + midiOutName);
                            break;
                        }
                    }
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
    // Внутренний класс - маршрутизатор MIDI (аналогично MidiMonitorApp)
    private class MidiRouter implements Receiver {
        private Receiver outputReceiver;

        public void setOutputReceiver(Receiver outputReceiver) {
            this.outputReceiver = outputReceiver;
            logMidi("🔀 Маршрутизатор: outputReceiver установлен");
        }

        @Override
        public void send(MidiMessage message, long timeStamp) {
            // Логируем входящее сообщение
            if (message instanceof ShortMessage) {
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

            // Пересылаем на выход, если есть
            if (isRunning && outputReceiver != null) {
                outputReceiver.send(message, timeStamp);
                if (message instanceof ShortMessage && ((ShortMessage) message).getCommand() == ShortMessage.NOTE_ON) {
                    logMidi("🔊 Перенаправлено на выход");
                }
            } else {
                if (!isRunning) logMidi("⏸️ Пропущено: isRunning=false");
                if (outputReceiver == null) logMidi("⏸️ Пропущено: outputReceiver=null");
            }

            // Обновляем GUI для NOTE_ON/OFF
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

    // -----------------------------------------------------------------------
    private String noteToName(int midi) {
        if (midi < 0 || midi > 127) return "?";
        String[] notes = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        int octave = midi / 12 - 1;
        return notes[midi % 12] + octave;
    }

    // -----------------------------------------------------------------------
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

    // -----------------------------------------------------------------------
    private void smoothScroll() {
        if (autoScrollEnabled) {
            viewCenter += (targetCenter - viewCenter) * 0.1;
            if (viewCenter < 40) viewCenter = 40;
            if (viewCenter > 90) viewCenter = 90;
        }
    }

    // -----------------------------------------------------------------------
    private void logMidi(String message) {
        SwingUtilities.invokeLater(() -> {
            String time = String.format("[%tT]", System.currentTimeMillis());
            midiLogArea.append(time + " " + message + "\n");
            midiLogArea.setCaretPosition(midiLogArea.getDocument().getLength());
            System.out.println("MIDI LOG: " + message);
        });
    }

    // -----------------------------------------------------------------------
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

    // -----------------------------------------------------------------------
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

    // -----------------------------------------------------------------------
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