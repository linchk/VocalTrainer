import javax.sound.midi.*;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.net.URL;
import java.util.*;
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
    private static final String KEY_LANGUAGE          = "language";

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
    private int virtualOctave = 4;

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
    private JComboBox<String> languageCombo;
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

    private javax.swing.Timer repaintTimer;
    private javax.swing.Timer scrollTimer;

    // Списки MIDI устройств
    private List<MidiDevice.Info> midiInputInfos = new ArrayList<>();
    private List<MidiDevice.Info> midiOutputInfos = new ArrayList<>();

    // Языковые ресурсы
    private ResourceBundle messages;
    private Map<String, Locale> availableLocales = new LinkedHashMap<>();
    private String[] languageNames;

    // -----------------------------------------------------------------------
    public VocalTrainer() {
        super();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1300, 950);
        setLocationRelativeTo(null);
        getContentPane().setBackground(new Color(26, 26, 26));

        loadAvailableLanguages(); // сначала определяем доступные языки
        loadLanguageSettings();   // загружаем выбранный язык
        setTitle("🎤🎹 " + tr("app.title") + " (Java)");

        pitchDetector = new PitchDetector(AUDIO_FORMAT);
        initSynthesizer();
        initUI();

        rescanDevices();
        loadSettings();

        repaintTimer = new javax.swing.Timer(33, e -> {
            pianoPanel.repaint();
            updateIndicators();
        });
        repaintTimer.start();

        scrollTimer = new javax.swing.Timer(16, e -> smoothScroll());
        scrollTimer.start();

        logMidi(tr("log.info") + ": " + tr("app.title") + " " + tr("log.start"));

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

    // -------- ЯЗЫКОВЫЕ МЕТОДЫ --------
    private void loadAvailableLanguages() {
        availableLocales.clear();
        // базовый английский всегда доступен
        availableLocales.put("en", Locale.ENGLISH);

        try {
            Enumeration<URL> urls = getClass().getClassLoader().getResources("resources");
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                File dir = new File(url.toURI());
                File[] files = dir.listFiles((d, name) ->
                        name.startsWith("Messages_") && name.endsWith(".properties"));
                if (files != null) {
                    for (File f : files) {
                        String name = f.getName();
                        String langCode = name.substring(9, name.lastIndexOf('.'));
                        // пропускаем, если уже есть (например, если несколько ресурсов)
                        if (!availableLocales.containsKey(langCode)) {
                            Locale loc = new Locale(langCode);
                            availableLocales.put(langCode, loc);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // строим массив названий языков на их родном языке
        languageNames = new String[availableLocales.size()];
        int i = 0;
        for (Locale loc : availableLocales.values()) {
            languageNames[i++] = loc.getDisplayLanguage(loc);
        }
    }

    private void loadLanguageSettings() {
        String lang = PREFS.get(KEY_LANGUAGE, "en");
        Locale locale = availableLocales.getOrDefault(lang, Locale.ENGLISH);
        try {
            messages = ResourceBundle.getBundle("resources.Messages", locale);
        } catch (MissingResourceException e) {
            messages = ResourceBundle.getBundle("resources.Messages", Locale.ENGLISH);
        }
    }

    public String tr(String key) {
        try {
            return messages.getString(key);
        } catch (MissingResourceException e) {
            return "!" + key + "!";
        }
    }

    // -------- ПУБЛИЧНЫЕ МЕТОДЫ ДЛЯ ВИРТУАЛЬНОЙ КЛАВИАТУРЫ --------
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
            MidiChannel[] channels = synthesizer.getChannels();
            for (MidiChannel ch : channels) {
                if (ch != null) {
                    ch.programChange(0);
                }
            }
            synthReceiver = synthesizer.getReceiver();
            logMidi(tr("log.synthInit"));
        } catch (MidiUnavailableException e) {
            logMidi(tr("log.error") + ": " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    private void initUI() {
        setLayout(new BorderLayout(10, 10));

        // ============ ВЕРХНЯЯ ПАНЕЛЬ ============
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBackground(new Color(26, 26, 26));

        JLabel title = new JLabel("🎤🎹 " + tr("app.subtitle"), SwingConstants.CENTER);
        title.setFont(new Font("Helvetica", Font.BOLD, 28));
        title.setForeground(new Color(76, 175, 80));
        title.setBackground(new Color(26, 26, 26));
        title.setOpaque(true);
        title.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));
        topPanel.add(title, BorderLayout.NORTH);

        JPanel iconPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        iconPanel.setBackground(new Color(26, 26, 26));
        iconPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 10));

        toggleSettingsBtn = new JButton("⚙");
        toggleSettingsBtn.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 20));
        if (!toggleSettingsBtn.getFont().canDisplay('⚙')) {
            toggleSettingsBtn.setFont(new Font("Arial Unicode MS", Font.PLAIN, 20));
        }
        toggleSettingsBtn.setToolTipText(tr("menu.settings"));
        toggleSettingsBtn.setBackground(new Color(220, 220, 220));
        toggleSettingsBtn.setForeground(new Color(30, 30, 30));
        toggleSettingsBtn.setFocusPainted(false);
        toggleSettingsBtn.setPreferredSize(new Dimension(40, 40));
        toggleSettingsBtn.addActionListener(e -> {
            settingsPanel.setVisible(!settingsPanel.isVisible());
            toggleSettingsBtn.setToolTipText(settingsPanel.isVisible() ? tr("menu.settings") : tr("menu.settings"));
        });
        iconPanel.add(toggleSettingsBtn);

        virtualPianoBtn = new JButton("♫");
        virtualPianoBtn.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 20));
        if (!virtualPianoBtn.getFont().canDisplay('♫')) {
            virtualPianoBtn.setFont(new Font("Arial Unicode MS", Font.PLAIN, 20));
        }
        virtualPianoBtn.setToolTipText(tr("menu.piano"));
        virtualPianoBtn.setBackground(new Color(220, 220, 220));
        virtualPianoBtn.setForeground(new Color(30, 30, 30));
        virtualPianoBtn.setFocusPainted(false);
        virtualPianoBtn.setPreferredSize(new Dimension(40, 40));
        virtualPianoBtn.addActionListener(e -> showVirtualPiano());
        iconPanel.add(virtualPianoBtn);

        topPanel.add(iconPanel, BorderLayout.SOUTH);
        add(topPanel, BorderLayout.NORTH);

        // ============ ПАНЕЛЬ НАСТРОЕК ============
        settingsPanel = new JPanel(new GridBagLayout());
        settingsPanel.setBackground(new Color(37, 37, 37));
        settingsPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Color.WHITE),
                "⚙️ " + tr("settings.devices"),
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
        JLabel micLabel = new JLabel("🎤 " + tr("settings.mic") + ":");
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
        JLabel audioOutLabel = new JLabel("🔊 " + tr("settings.speakers") + ":");
        audioOutLabel.setFont(new Font("Arial", Font.BOLD, 12));
        audioOutLabel.setForeground(Color.WHITE);
        settingsPanel.add(audioOutLabel, gbc);

        gbc.gridx = 1; gbc.gridwidth = 1;
        audioOutputCombo = new JComboBox<>();
        audioOutputCombo.setPreferredSize(new Dimension(300, 30));
        audioOutputCombo.addItemListener(e -> saveSettings());
        settingsPanel.add(audioOutputCombo, gbc);

        gbc.gridx = 2;
        JButton testAudioBtn = new JButton("🔊 " + tr("settings.test"));
        testAudioBtn.setFont(new Font("Arial", Font.BOLD, 11));
        testAudioBtn.setBackground(new Color(200, 200, 200));
        testAudioBtn.setForeground(new Color(30, 30, 30));
        testAudioBtn.setFocusPainted(false);
        testAudioBtn.addActionListener(e -> testAudioOutput());
        settingsPanel.add(testAudioBtn, gbc);

        // MIDI вход
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1;
        JLabel midiInLabel = new JLabel("🎹 " + tr("settings.midiIn") + ":");
        midiInLabel.setFont(new Font("Arial", Font.BOLD, 12));
        midiInLabel.setForeground(Color.WHITE);
        settingsPanel.add(midiInLabel, gbc);

        gbc.gridx = 1;
        midiInCombo = new JComboBox<>();
        midiInCombo.setPreferredSize(new Dimension(300, 30));
        midiInCombo.addItemListener(e -> saveSettings());
        settingsPanel.add(midiInCombo, gbc);

        gbc.gridx = 2;
        JButton rescanBtn = new JButton("🔄 " + tr("settings.refresh"));
        rescanBtn.setFont(new Font("Arial", Font.BOLD, 11));
        rescanBtn.setBackground(new Color(200, 200, 200));
        rescanBtn.setForeground(new Color(30, 30, 30));
        rescanBtn.setFocusPainted(false);
        rescanBtn.addActionListener(e -> rescanDevices());
        settingsPanel.add(rescanBtn, gbc);

        // Режим выхода
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 1;
        JLabel outModeLabel = new JLabel("🔊 " + tr("settings.midiOut") + ":");
        outModeLabel.setFont(new Font("Arial", Font.BOLD, 12));
        outModeLabel.setForeground(Color.WHITE);
        settingsPanel.add(outModeLabel, gbc);

        JPanel radioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        radioPanel.setBackground(new Color(37, 37, 37));
        physicalOutRadio = new JRadioButton(tr("settings.physical"), false);
        physicalOutRadio.setFont(new Font("Arial", Font.PLAIN, 11));
        physicalOutRadio.setForeground(Color.WHITE);
        physicalOutRadio.setBackground(new Color(37, 37, 37));
        synthOutRadio = new JRadioButton(tr("settings.synth"), true);
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
        JLabel midiOutLabel = new JLabel("📤 " + tr("settings.midiOutPhysical"));
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
        JButton testMidiBtn = new JButton("🎹 " + tr("settings.midiTest"));
        testMidiBtn.setFont(new Font("Arial", Font.BOLD, 11));
        testMidiBtn.setBackground(new Color(200, 200, 200));
        testMidiBtn.setForeground(new Color(30, 30, 30));
        testMidiBtn.setFocusPainted(false);
        testMidiBtn.addActionListener(e -> testMidiOutput());
        settingsPanel.add(testMidiBtn, gbc);

        // Автопрокрутка
        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 1;
        JLabel scrollLabel = new JLabel("🔄 " + tr("settings.autoScroll") + ":");
        scrollLabel.setFont(new Font("Arial", Font.BOLD, 12));
        scrollLabel.setForeground(Color.WHITE);
        settingsPanel.add(scrollLabel, gbc);

        gbc.gridx = 1; gbc.gridwidth = 2;
        autoScrollCheck = new JCheckBox(tr("settings.autoScroll"));
        autoScrollCheck.setFont(new Font("Arial", Font.PLAIN, 12));
        autoScrollCheck.setForeground(Color.WHITE);
        autoScrollCheck.setBackground(new Color(37, 37, 37));
        autoScrollCheck.setSelected(autoScrollEnabled);
        autoScrollCheck.addActionListener(e -> {
            autoScrollEnabled = autoScrollCheck.isSelected();
            saveSettings();
            logMidi(tr("settings.autoScroll") + " " + (autoScrollEnabled ? "ON" : "OFF"));
        });
        settingsPanel.add(autoScrollCheck, gbc);

        // Подробный лог
        gbc.gridx = 0; gbc.gridy = 6; gbc.gridwidth = 1;
        JLabel logLabel = new JLabel("📋 " + tr("settings.detailedLog") + ":");
        logLabel.setFont(new Font("Arial", Font.BOLD, 12));
        logLabel.setForeground(Color.WHITE);
        settingsPanel.add(logLabel, gbc);

        gbc.gridx = 1; gbc.gridwidth = 2;
        detailedLogCheck = new JCheckBox(tr("settings.detailedLog"));
        detailedLogCheck.setFont(new Font("Arial", Font.PLAIN, 12));
        detailedLogCheck.setForeground(Color.WHITE);
        detailedLogCheck.setBackground(new Color(37, 37, 37));
        detailedLogCheck.setSelected(PREFS.getBoolean(KEY_DETAILED_LOG, true));
        detailedLogCheck.addActionListener(e -> saveSettings());
        settingsPanel.add(detailedLogCheck, gbc);

        // Выбор языка
        gbc.gridx = 0; gbc.gridy = 7; gbc.gridwidth = 1;
        JLabel langLabel = new JLabel("🌐 " + tr("settings.language") + ":");
        langLabel.setFont(new Font("Arial", Font.BOLD, 12));
        langLabel.setForeground(Color.WHITE);
        settingsPanel.add(langLabel, gbc);

        gbc.gridx = 1; gbc.gridwidth = 2;
        languageCombo = new JComboBox<>(languageNames);
        languageCombo.setPreferredSize(new Dimension(200, 30));
        // установить текущий язык
        String currentLang = PREFS.get(KEY_LANGUAGE, "en");
        int idx = 0;
        int i = 0;
        for (Map.Entry<String, Locale> entry : availableLocales.entrySet()) {
            if (entry.getKey().equals(currentLang)) {
                idx = i;
                break;
            }
            i++;
        }
        languageCombo.setSelectedIndex(idx);
        languageCombo.addActionListener(e -> {
            int selected = languageCombo.getSelectedIndex();
            String newLang = (String) availableLocales.keySet().toArray()[selected];
            PREFS.put(KEY_LANGUAGE, newLang);
            JOptionPane.showMessageDialog(this,
                    tr("language.change.restart"),
                    tr("info"),
                    JOptionPane.INFORMATION_MESSAGE);
        });
        settingsPanel.add(languageCombo, gbc);

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
        JLabel vocalDesc = new JLabel("🎤 " + tr("indicators.yourNote"), SwingConstants.CENTER);
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
        JLabel targetDesc = new JLabel("🎹 " + tr("indicators.target"), SwingConstants.CENTER);
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
        JLabel devDesc = new JLabel("📏 " + tr("indicators.deviation"), SwingConstants.CENTER);
        devDesc.setForeground(new Color(119, 119, 119));
        devBox.add(devDesc, BorderLayout.SOUTH);
        infoPanel.add(devBox);

        bottomPanel.add(infoPanel, BorderLayout.NORTH);

        // MIDI лог
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBackground(new Color(26, 26, 26));
        logPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(255, 82, 82)),
                "📡 " + tr("log.midiLog"),
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

        startBtn = new JButton("▶️ " + tr("buttons.start"));
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

        stopBtn = new JButton("⏹️ " + tr("buttons.stop"));
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

        if (micNames.isEmpty()) micNames.add("❌ " + tr("error.noDevice"));
        if (spkNames.isEmpty()) spkNames.add("❌ " + tr("error.noDevice"));

        audioInputCombo.setModel(new DefaultComboBoxModel<>(micNames.toArray(new String[0])));
        audioOutputCombo.setModel(new DefaultComboBoxModel<>(spkNames.toArray(new String[0])));

        MidiDevice.Info[] midiInfos = MidiSystem.getMidiDeviceInfo();
        midiInputInfos.clear();
        midiOutputInfos.clear();
        List<String> midiInputDisplay = new ArrayList<>();
        List<String> midiOutputDisplay = new ArrayList<>();

        logMidi("🔍 " + tr("log.midiLog") + ":");
        for (MidiDevice.Info info : midiInfos) {
            try {
                MidiDevice dev = MidiSystem.getMidiDevice(info);
                int maxTx = dev.getMaxTransmitters();
                int maxRx = dev.getMaxReceivers();
                String desc = info.getName() + " (tx=" + maxTx + ", rx=" + maxRx + ")";
                logMidi("   " + desc);
                if (maxTx != 0) {
                    midiInputInfos.add(info);
                    midiInputDisplay.add(desc);
                }
                if (maxRx != 0 && !(dev instanceof Synthesizer && info.getName().equals("Gervill"))) {
                    midiOutputInfos.add(info);
                    midiOutputDisplay.add(desc);
                }
            } catch (MidiUnavailableException ignored) {
                logMidi("   ❌ " + info.getName() + " " + tr("error.deviceNotFound"));
            }
        }

        if (midiInputDisplay.isEmpty()) {
            midiInputDisplay.add("❌ " + tr("error.noDevice"));
            midiInputInfos.clear();
        }
        midiInCombo.setModel(new DefaultComboBoxModel<>(midiInputDisplay.toArray(new String[0])));

        if (midiOutputDisplay.isEmpty()) {
            midiOutputDisplay.add("❌ " + tr("error.noDevice"));
            midiOutputInfos.clear();
        }
        midiOutCombo.setModel(new DefaultComboBoxModel<>(midiOutputDisplay.toArray(new String[0])));

        logMidi("✅ " + tr("log.info"));
    }

    // -----------------------------------------------------------------------
    private void testAudioOutput() {
        String selectedAudioOut = (String) audioOutputCombo.getSelectedItem();
        if (selectedAudioOut == null || selectedAudioOut.contains("❌")) {
            JOptionPane.showMessageDialog(this, tr("error.noDevice"), tr("error.audioTest"), JOptionPane.ERROR_MESSAGE);
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
                logMidi("❌ " + tr("error.deviceNotFound"));
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
            logMidi("🔊 " + tr("settings.test") + " " + selectedAudioOut);
        } catch (Exception e) {
            logMidi("❌ " + tr("error.audioTest") + ": " + e.getMessage());
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
                    JOptionPane.showMessageDialog(this, tr("error.noDevice"), tr("error.midiTest"), JOptionPane.ERROR_MESSAGE);
                    return;
                }
                MidiDevice.Info info = midiOutputInfos.get(outIdx);
                MidiDevice tempDevice = MidiSystem.getMidiDevice(info);
                tempDevice.open();
                Receiver tempReceiver = tempDevice.getReceiver();
                ShortMessage onMsg = new ShortMessage();
                onMsg.setMessage(ShortMessage.NOTE_ON, 0, 69, 100);
                tempReceiver.send(onMsg, -1);
                logMidi("🎹 " + tr("log.midiTestNote") + " " + info.getName());

                final Receiver rec = tempReceiver;
                final MidiDevice dev = tempDevice;
                new javax.swing.Timer(500, e -> {
                    try {
                        ShortMessage offMsg = new ShortMessage();
                        offMsg.setMessage(ShortMessage.NOTE_OFF, 0, 69, 0);
                        rec.send(offMsg, -1);
                    } catch (InvalidMidiDataException ex) {
                        logMidi("❌ " + tr("log.error") + ": " + ex.getMessage());
                    } catch (IllegalStateException ex) {
                        logMidi("❌ " + tr("log.error") + ": " + ex.getMessage());
                    } finally {
                        rec.close();
                        dev.close();
                    }
                }).start();
            } else {
                ShortMessage onMsg = new ShortMessage();
                onMsg.setMessage(ShortMessage.NOTE_ON, 0, 69, 100);
                synthReceiver.send(onMsg, -1);
                logMidi("🎹 " + tr("log.midiTestNote") + " " + tr("settings.synth"));
                new javax.swing.Timer(500, e -> {
                    try {
                        ShortMessage offMsg = new ShortMessage();
                        offMsg.setMessage(ShortMessage.NOTE_OFF, 0, 69, 0);
                        synthReceiver.send(offMsg, -1);
                    } catch (InvalidMidiDataException ex) {
                        logMidi("❌ " + tr("log.error") + ": " + ex.getMessage());
                    }
                }).start();
            }
        } catch (Exception e) {
            logMidi("❌ " + tr("error.midiTest") + ": " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    private void startProcessing() {
        stopProcessing();
        isRunning = true;
        sessionStartTime = System.currentTimeMillis();
        pitchHistory.clear();
        saveSettings();

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
                        logMidi("✅ " + tr("settings.mic") + ": " + micName);
                        break;
                    }
                }
            } catch (Exception e) {
                logMidi("❌ " + tr("error.audioTest") + ": " + e.getMessage());
            }
        } else {
            logMidi("⚠️ " + tr("settings.mic") + " " + tr("error.noDevice"));
        }

        int midiInIdx = midiInCombo.getSelectedIndex();
        if (midiInIdx >= 0 && midiInIdx < midiInputInfos.size()) {
            MidiDevice.Info info = midiInputInfos.get(midiInIdx);
            try {
                midiInputDevice = MidiSystem.getMidiDevice(info);
                int maxTx = midiInputDevice.getMaxTransmitters();
                logMidi("🔧 " + tr("settings.midiIn") + ": " + info.getName() + ", maxTransmitters=" + maxTx);
                if (maxTx == 0) {
                    logMidi("⚠️ " + tr("log.warning"));
                }
                midiInputDevice.open();
                logMidi("🔧 " + tr("settings.midiIn") + " " + tr("log.info"));

                midiRouter = new MidiRouter();
                midiTransmitter = midiInputDevice.getTransmitter();
                midiTransmitter.setReceiver(midiRouter);
                logMidi("✅ " + tr("settings.midiIn") + " " + tr("log.routed"));
            } catch (MidiUnavailableException e) {
                logMidi("❌ " + tr("error.midiTest") + ": " + e.getMessage());
                if (midiInputDevice != null) {
                    midiInputDevice.close();
                    midiInputDevice = null;
                }
                midiRouter = null;
            }
        } else {
            logMidi("⚠️ " + tr("settings.midiIn") + " " + tr("error.noDevice"));
        }

        if (physicalOutRadio.isSelected()) {
            int midiOutIdx = midiOutCombo.getSelectedIndex();
            if (midiOutIdx >= 0 && midiOutIdx < midiOutputInfos.size()) {
                MidiDevice.Info info = midiOutputInfos.get(midiOutIdx);
                try {
                    midiOutputDevice = MidiSystem.getMidiDevice(info);
                    midiOutputDevice.open();
                    outputReceiver = midiOutputDevice.getReceiver();
                    logMidi("✅ " + tr("settings.midiOutPhysical") + " " + info.getName());
                } catch (MidiUnavailableException e) {
                    logMidi("❌ " + tr("error.midiTest") + ": " + e.getMessage());
                }
            } else {
                logMidi("⚠️ " + tr("settings.midiOutPhysical") + " " + tr("error.noDevice"));
            }
        } else {
            if (synthReceiver == null) {
                initSynthesizer();
            }
            outputReceiver = synthReceiver;
            logMidi("✅ " + tr("settings.midiOut") + ": " + tr("settings.synth"));
        }

        if (midiRouter != null) {
            midiRouter.setOutputReceiver(outputReceiver);
        }

        if (microphoneLine != null) {
            new Thread(new AudioProcessor()).start();
        }

        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);

        logMidi("🟢 " + tr("log.start"));
    }

    private void stopProcessing() {
        isRunning = false;

        if (microphoneLine != null) {
            microphoneLine.stop();
            microphoneLine.close();
            microphoneLine = null;
        }

        if (midiTransmitter != null) {
            midiTransmitter.close();
            midiTransmitter = null;
        }
        if (midiInputDevice != null) {
            midiInputDevice.close();
            midiInputDevice = null;
        }
        midiRouter = null;

        if (midiOutputDevice != null) {
            midiOutputDevice.close();
            midiOutputDevice = null;
        }
        outputReceiver = null;

        currentMidiNote = null;
        currentMidiVelocity = null;
        currentPitchMidi = null;
        currentVocalNote = "—";
        pitchHistory.clear();
        pianoPanel.repaint();

        SwingUtilities.invokeLater(() -> {
            if (midiNoteLabel != null) midiNoteLabel.setText("—");
            if (midiVelocityLabel != null) midiVelocityLabel.setText(tr("virtual.octave") + ": —");
            if (vocalLabel != null) vocalLabel.setText("—");
            if (targetLabel != null) targetLabel.setText("—");
            if (deviationLabel != null) deviationLabel.setText("—");
        });

        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);

        logMidi("⏹️ " + tr("log.stop"));
    }

    // -----------------------------------------------------------------------
    private class MidiRouter implements Receiver {
        private Receiver outputReceiver;

        public void setOutputReceiver(Receiver outputReceiver) {
            this.outputReceiver = outputReceiver;
            logMidi("🔀 " + tr("log.routed"));
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
                if (command == ShortMessage.NOTE_ON) msgType = tr("log.midiNoteOn");
                else if (command == ShortMessage.NOTE_OFF) msgType = tr("log.midiNoteOff");
                else msgType = "OTHER";
                logMidi("📩 MIDI: " + msgType + " ch=" + sm.getChannel() +
                        " note=" + note + " vel=" + velocity);
            }

            if (isRunning && outputReceiver != null) {
                try {
                    outputReceiver.send(message, timeStamp);
                    if (detailedLog && message instanceof ShortMessage && ((ShortMessage) message).getCommand() == ShortMessage.NOTE_ON) {
                        logMidi("🔊 " + tr("log.routed"));
                    }
                } catch (IllegalStateException e) {
                    logMidi("❌ " + tr("log.error") + ": receiver closed");
                }
            } else {
                if (detailedLog) {
                    if (!isRunning) logMidi("⏸️ " + tr("log.skipped") + ": " + tr("log.skippedNotRunning"));
                    if (outputReceiver == null) logMidi("⏸️ " + tr("log.skipped") + ": " + tr("log.skippedNoOutput"));
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
                        if (midiNoteLabel != null) midiNoteLabel.setText(noteToName(note));
                        if (midiVelocityLabel != null) midiVelocityLabel.setText(tr("virtual.octave") + ": " + velocity);
                        logMidi("🎹 " + tr("log.midiNoteOn") + ": " + noteToName(note) + " (MIDI " + note + "), " + tr("virtual.octave") + "=" + velocity);
                    });
                } else if (command == ShortMessage.NOTE_OFF ||
                        (command == ShortMessage.NOTE_ON && velocity == 0)) {
                    if (currentMidiNote != null && currentMidiNote.equals(note)) {
                        SwingUtilities.invokeLater(() -> {
                            currentMidiNote = null;
                            currentMidiVelocity = null;
                            if (midiNoteLabel != null) midiNoteLabel.setText("—");
                            if (midiVelocityLabel != null) midiVelocityLabel.setText(tr("virtual.octave") + ": —");
                        });
                    }
                    logMidi("🎹 " + tr("log.midiNoteOff") + ": " + noteToName(note) + " (MIDI " + note + ")");
                }
            }
        }

        @Override
        public void close() {}
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================
    private void updateIndicators() {
        if (vocalLabel != null) vocalLabel.setText(currentVocalNote);
        if (targetLabel != null) targetLabel.setText(currentMidiNote == null ? "—" : noteToName(currentMidiNote));

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
            if (deviationLabel != null) {
                deviationLabel.setText(String.format("%s %+.0f¢", symbol, cents));
                deviationLabel.setForeground(color);
            }
        } else {
            if (deviationLabel != null) {
                deviationLabel.setText("—");
                deviationLabel.setForeground(new Color(136, 136, 136));
            }
        }
    }

    // Улучшенная плавная прокрутка
    private void smoothScroll() {
        if (autoScrollEnabled) {
            double diff = targetCenter - viewCenter;
            if (Math.abs(diff) < 0.01) {
                viewCenter = targetCenter;
            } else {
                double speed = 0.1 + Math.min(0.3, Math.abs(diff) * 0.01);
                viewCenter += diff * speed;
            }
            if (viewCenter < 36) viewCenter = 36;
            if (viewCenter > 96) viewCenter = 96;
        }
    }

    private void logMidi(String message) {
        SwingUtilities.invokeLater(() -> {
            if (midiLogArea != null) {
                String time = String.format("[%tT]", System.currentTimeMillis());
                midiLogArea.append(time + " " + message + "\n");
                midiLogArea.setCaretPosition(midiLogArea.getDocument().getLength());
            }
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
// Виртуальная пианино-клавиатура (локализована)
// =====================================================================
class VirtualPianoFrame extends JFrame {
    private final VocalTrainer parent;
    private VirtualPianoPanel pianoPanel;
    private int octave = 4;
    private JCheckBox alwaysOnTopCheck;
    private JLabel infoLabel;

    public VirtualPianoFrame(VocalTrainer parent) {
        super(parent.tr("menu.piano"));
        this.parent = parent;
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(800, 350);
        setLocationRelativeTo(parent);

        pianoPanel = new VirtualPianoPanel(parent);
        add(pianoPanel, BorderLayout.CENTER);

        JPanel controlPanel = new JPanel(new BorderLayout());
        controlPanel.setBackground(new Color(60, 60, 60));

        infoLabel = new JLabel(parent.tr("virtual.keyHint"));
        infoLabel.setForeground(Color.WHITE);
        infoLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        controlPanel.add(infoLabel, BorderLayout.WEST);

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        topPanel.setBackground(new Color(60, 60, 60));
        alwaysOnTopCheck = new JCheckBox(parent.tr("virtual.alwaysOnTop"));
        alwaysOnTopCheck.setForeground(Color.WHITE);
        alwaysOnTopCheck.setBackground(new Color(60, 60, 60));
        alwaysOnTopCheck.addActionListener(e -> setAlwaysOnTop(alwaysOnTopCheck.isSelected()));
        topPanel.add(alwaysOnTopCheck);

        controlPanel.add(topPanel, BorderLayout.EAST);
        add(controlPanel, BorderLayout.SOUTH);

        pianoPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                pianoPanel.requestFocusInWindow();
            }
        });

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
        requestFocusInWindow();
    }

    public void setOctave(int octave) {
        this.octave = octave;
        pianoPanel.setOctave(octave);
    }
}

class VirtualPianoPanel extends JPanel {
    private final VocalTrainer parent;
    private int octave = 4;
    private final int whiteKeys = 14;
    private final int blackKeys = 10;
    private final int whiteKeyWidth = 50;
    private final int whiteKeyHeight = 150;
    private final int blackKeyWidth = 30;
    private final int blackKeyHeight = 90;

    private static final char[] whiteKeyChars = {'a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l', ';', '\''};
    private static final char[] blackKeyChars = {'w', 'e', 't', 'y', 'u'};

    private final int[] blackXPositions = new int[blackKeys];
    private final boolean[] whitePressed = new boolean[whiteKeys];
    private final boolean[] blackPressed = new boolean[blackKeys];

    public VirtualPianoPanel(VocalTrainer parent) {
        this.parent = parent;
        setPreferredSize(new Dimension(whiteKeys * whiteKeyWidth, whiteKeyHeight + 30));
        setBackground(Color.DARK_GRAY);
        setFocusable(true);

        for (int oct = 0; oct < 2; oct++) {
            int baseWhite = oct * 7;
            for (int i = 0; i < 5; i++) {
                int blackIdx = oct * 5 + i;
                int whiteIndex;
                if (i == 0) whiteIndex = baseWhite;
                else if (i == 1) whiteIndex = baseWhite + 1;
                else if (i == 2) whiteIndex = baseWhite + 3;
                else if (i == 3) whiteIndex = baseWhite + 4;
                else whiteIndex = baseWhite + 5;
                blackXPositions[blackIdx] = (whiteIndex + 1) * whiteKeyWidth - blackKeyWidth / 2;
            }
        }

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
        resetAllKeys();
        repaint();
    }

    private void resetAllKeys() {
        for (int i = 0; i < whiteKeys; i++) {
            if (whitePressed[i]) {
                whitePressed[i] = false;
                int note = getMidiForWhiteKey(i);
                sendNote(note, 0);
            }
        }
        for (int i = 0; i < blackKeys; i++) {
            if (blackPressed[i]) {
                blackPressed[i] = false;
                int note = getMidiForBlackKey(i);
                sendNote(note, 0);
            }
        }
    }

    private int getMidiNoteFromPos(int x, int y) {
        for (int i = 0; i < blackKeys; i++) {
            if (x >= blackXPositions[i] && x < blackXPositions[i] + blackKeyWidth && y < blackKeyHeight) {
                return getMidiForBlackKey(i);
            }
        }
        int whiteIndex = x / whiteKeyWidth;
        if (whiteIndex >= 0 && whiteIndex < whiteKeys) {
            return getMidiForWhiteKey(whiteIndex);
        }
        return -1;
    }

    private int getMidiForWhiteKey(int index) {
        int noteInOctave;
        int octaveOffset;
        if (index < 7) {
            octaveOffset = octave;
            switch (index % 7) {
                case 0: noteInOctave = 0; break;
                case 1: noteInOctave = 2; break;
                case 2: noteInOctave = 4; break;
                case 3: noteInOctave = 5; break;
                case 4: noteInOctave = 7; break;
                case 5: noteInOctave = 9; break;
                case 6: noteInOctave = 11; break;
                default: noteInOctave = 0;
            }
        } else {
            octaveOffset = octave + 1;
            switch ((index - 7) % 7) {
                case 0: noteInOctave = 0; break;
                case 1: noteInOctave = 2; break;
                case 2: noteInOctave = 4; break;
                case 3: noteInOctave = 5; break;
                case 4: noteInOctave = 7; break;
                case 5: noteInOctave = 9; break;
                case 6: noteInOctave = 11; break;
                default: noteInOctave = 0;
            }
        }
        return octaveOffset * 12 + noteInOctave;
    }

    private int getMidiForBlackKey(int index) {
        int noteInOctave;
        int octaveOffset;
        int localIdx = index % 5;
        if (index < 5) {
            octaveOffset = octave;
        } else {
            octaveOffset = octave + 1;
        }
        switch (localIdx) {
            case 0: noteInOctave = 1; break;
            case 1: noteInOctave = 3; break;
            case 2: noteInOctave = 6; break;
            case 3: noteInOctave = 8; break;
            case 4: noteInOctave = 10; break;
            default: noteInOctave = 0;
        }
        return octaveOffset * 12 + noteInOctave;
    }

    private void handleMousePress(int x, int y, boolean press) {
        int note = getMidiNoteFromPos(x, y);
        if (note != -1) {
            boolean found = false;
            for (int i = 0; i < blackKeys && !found; i++) {
                if (x >= blackXPositions[i] && x < blackXPositions[i] + blackKeyWidth && y < blackKeyHeight) {
                    blackPressed[i] = press;
                    found = true;
                }
            }
            if (!found) {
                int whiteIndex = x / whiteKeyWidth;
                if (whiteIndex >= 0 && whiteIndex < whiteKeys) {
                    whitePressed[whiteIndex] = press;
                }
            }
            sendNote(note, press ? 100 : 0);
            repaint();
        }
    }

    public void handleKeyPress(KeyEvent e) {
        char key = e.getKeyChar();
        int keyCode = e.getKeyCode();

        for (int i = 0; i < whiteKeyChars.length && i < whiteKeys; i++) {
            if (key == whiteKeyChars[i]) {
                if (!whitePressed[i]) {
                    whitePressed[i] = true;
                    int note = getMidiForWhiteKey(i);
                    sendNote(note, 100);
                    repaint();
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
                    repaint();
                }
                break;
            }
        }
        if (keyCode == KeyEvent.VK_PLUS || keyCode == KeyEvent.VK_ADD ||
                (keyCode == KeyEvent.VK_EQUALS && e.isShiftDown())) {
            if (octave < 8) {
                octave++;
                setOctave(octave);
                parent.setVirtualOctave(octave);
            }
        } else if (keyCode == KeyEvent.VK_MINUS || keyCode == KeyEvent.VK_SUBTRACT) {
            if (octave > 0) {
                octave--;
                setOctave(octave);
                parent.setVirtualOctave(octave);
            }
        } else if (keyCode == KeyEvent.VK_Z) {
            if (octave > 0) {
                octave--;
                setOctave(octave);
                parent.setVirtualOctave(octave);
            }
        } else if (keyCode == KeyEvent.VK_X) {
            if (octave < 8) {
                octave++;
                setOctave(octave);
                parent.setVirtualOctave(octave);
            }
        }
    }

    public void handleKeyRelease(KeyEvent e) {
        char key = e.getKeyChar();
        for (int i = 0; i < whiteKeyChars.length && i < whiteKeys; i++) {
            if (key == whiteKeyChars[i]) {
                if (whitePressed[i]) {
                    whitePressed[i] = false;
                    int note = getMidiForWhiteKey(i);
                    sendNote(note, 0);
                    repaint();
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
                    repaint();
                }
                break;
            }
        }
    }

    private void sendNote(int note, int velocity) {
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
            g2.setColor(whitePressed[i] ? new Color(200, 200, 200) : Color.WHITE);
            g2.fillRect(x, 0, whiteKeyWidth - 1, whiteKeyHeight);
            g2.setColor(Color.BLACK);
            g2.drawRect(x, 0, whiteKeyWidth - 1, whiteKeyHeight);

            int note = getMidiForWhiteKey(i);
            String noteName = parent.noteToName(note);
            g2.setColor(Color.BLACK);
            g2.setFont(new Font("Arial", Font.PLAIN, 12));
            g2.drawString(noteName, x + 5, whiteKeyHeight - 25);

            if (i < whiteKeyChars.length) {
                String keyCap = String.valueOf(whiteKeyChars[i]).toUpperCase();
                g2.setColor(Color.BLUE);
                g2.setFont(new Font("Arial", Font.BOLD, 12));
                g2.drawString(keyCap, x + 5, whiteKeyHeight - 10);
            }
        }

        for (int i = 0; i < blackKeys; i++) {
            int x = blackXPositions[i];
            g2.setColor(blackPressed[i] ? Color.DARK_GRAY : Color.BLACK);
            g2.fillRect(x, 0, blackKeyWidth, blackKeyHeight);
            g2.setColor(Color.WHITE);
            g2.drawRect(x, 0, blackKeyWidth, blackKeyHeight);

            int note = getMidiForBlackKey(i);
            String noteName = parent.noteToName(note);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial", Font.PLAIN, 10));
            g2.drawString(noteName, x + 5, blackKeyHeight - 25);

            if (i < blackKeyChars.length) {
                String keyCap = String.valueOf(blackKeyChars[i]).toUpperCase();
                g2.setColor(Color.YELLOW);
                g2.setFont(new Font("Arial", Font.BOLD, 10));
                g2.drawString(keyCap, x + 5, blackKeyHeight - 10);
            }
        }

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 16));
        g2.drawString(parent.tr("virtual.octave") + ": " + octave, 10, whiteKeyHeight + 20);
    }
}