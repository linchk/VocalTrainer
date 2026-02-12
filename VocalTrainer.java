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

/**
 * VocalTrainer — профессиональный тренажёр вокала на Java.
 * <p>
 * Исправлено:
 * - MIDI-ввод теперь работает со всеми USB-клавиатурами (Minilab и др.)
 * - Плавная прокрутка с возможностью отключения
 * - Полное сохранение настроек
 */
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
    private static final String KEY_MIDI_DEVICE   = "midi_device";
    private static final String KEY_AUTO_SCROLL   = "auto_scroll";

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

    private MidiDevice midiInputDevice;
    private Receiver midiReceiver;               // сохраняем, чтобы не удалялся GC
    private volatile Integer currentMidiNote = null;
    private volatile Integer currentMidiVelocity = null;

    private Synthesizer synthesizer;
    private MidiChannel midiChannel;

    // Визуализация и скролл
    private PianoRollPanel pianoPanel;
    private double viewCenter = 60.0;
    private double targetCenter = 60.0;
    private volatile boolean autoScrollEnabled = true;

    // GUI
    private JComboBox<String> inputCombo;
    private JComboBox<String> outputCombo;
    private JComboBox<String> midiCombo;
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

        // Сначала сканируем устройства
        rescanMIDI();
        // Затем загружаем сохранённые настройки
        loadSettings();

        // Таймеры
        repaintTimer = new Timer(33, e -> {
            pianoPanel.repaint();
            updateIndicators();
        });
        repaintTimer.start();

        scrollTimer = new Timer(20, e -> smoothScroll()); // 20 мс — очень плавно
        scrollTimer.start();

        logMidi("ℹ️ Программа запущена. Выберите MIDI устройство и нажмите СТАРТ.");

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopProcessing();
                saveSettings();
                if (synthesizer != null) synthesizer.close();
            }
        });
    }

    // -----------------------------------------------------------------------
    private void initSynthesizer() {
        try {
            synthesizer = MidiSystem.getSynthesizer();
            synthesizer.open();
            // Принудительно загружаем стандартную звуковую банку
            Soundbank defaultBank = synthesizer.getDefaultSoundbank();
            if (defaultBank != null) {
                synthesizer.loadAllInstruments(defaultBank);
            }
            midiChannel = synthesizer.getChannels()[0];
            midiChannel.programChange(0); // Acoustic Grand Piano
            logMidi("✅ Синтезатор инициализирован, канал готов.");
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

        // Панель настроек
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
        inputCombo = new JComboBox<>();
        inputCombo.setPreferredSize(new Dimension(400, 30));
        inputCombo.addItemListener(e -> saveSettings());
        settingsPanel.add(inputCombo, gbc);

        // Динамики
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1;
        JLabel outLabel = new JLabel("🔊 Динамики:");
        outLabel.setFont(new Font("Arial", Font.BOLD, 12));
        outLabel.setForeground(Color.WHITE);
        settingsPanel.add(outLabel, gbc);

        gbc.gridx = 1; gbc.gridwidth = 1;
        outputCombo = new JComboBox<>();
        outputCombo.setPreferredSize(new Dimension(300, 30));
        outputCombo.addItemListener(e -> saveSettings());
        settingsPanel.add(outputCombo, gbc);

        gbc.gridx = 2;
        JButton testBtn = new JButton("🔊 Тест звука");
        testBtn.setFont(new Font("Arial", Font.BOLD, 11));
        testBtn.setBackground(new Color(85, 85, 85));
        testBtn.setForeground(Color.WHITE);
        testBtn.addActionListener(e -> testOutput());
        settingsPanel.add(testBtn, gbc);

        // MIDI
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1;
        JLabel midiLabel = new JLabel("🎹 MIDI:");
        midiLabel.setFont(new Font("Arial", Font.BOLD, 12));
        midiLabel.setForeground(Color.WHITE);
        settingsPanel.add(midiLabel, gbc);

        gbc.gridx = 1;
        midiCombo = new JComboBox<>();
        midiCombo.setPreferredSize(new Dimension(300, 30));
        midiCombo.addItemListener(e -> saveSettings());
        settingsPanel.add(midiCombo, gbc);

        gbc.gridx = 2;
        JButton rescanBtn = new JButton("🔄 Обновить список");
        rescanBtn.setFont(new Font("Arial", Font.BOLD, 11));
        rescanBtn.setBackground(new Color(68, 68, 68));
        rescanBtn.setForeground(Color.WHITE);
        rescanBtn.addActionListener(e -> rescanMIDI());
        settingsPanel.add(rescanBtn, gbc);

        // Автопрокрутка
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 1;
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

        // ============ ЦЕНТР: ПИАНИНО-РОЛЛ ============
        pianoPanel = new PianoRollPanel();
        pianoPanel.setBackground(new Color(15, 15, 15));
        pianoPanel.setPreferredSize(new Dimension(1000, 450));
        add(pianoPanel, BorderLayout.CENTER);

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
                "📡 MIDI Log (диагностика подключения)",
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
        startBtn.setFont(new Font("Arial", Font.BOLD, 16));
        startBtn.setBackground(new Color(76, 175, 80));
        startBtn.setForeground(Color.WHITE);
        startBtn.setFocusPainted(false);
        startBtn.setBorderPainted(false);
        startBtn.setPreferredSize(new Dimension(160, 55));
        startBtn.addActionListener(e -> startProcessing());
        controlPanel.add(startBtn);

        stopBtn = new JButton("⏹️ СТОП");
        stopBtn.setFont(new Font("Arial", Font.BOLD, 16));
        stopBtn.setBackground(new Color(136, 136, 136));
        stopBtn.setForeground(Color.WHITE);
        stopBtn.setFocusPainted(false);
        stopBtn.setBorderPainted(false);
        stopBtn.setPreferredSize(new Dimension(160, 55));
        stopBtn.setEnabled(false);
        stopBtn.addActionListener(e -> stopProcessing());
        controlPanel.add(stopBtn);

        bottomPanel.add(controlPanel, BorderLayout.SOUTH);

        add(bottomPanel, BorderLayout.SOUTH);
    }

    // -----------------------------------------------------------------------
    private void loadSettings() {
        int savedInput = PREFS.getInt(KEY_INPUT_DEVICE, 0);
        int savedOutput = PREFS.getInt(KEY_OUTPUT_DEVICE, 0);
        if (savedInput < inputCombo.getItemCount()) inputCombo.setSelectedIndex(savedInput);
        if (savedOutput < outputCombo.getItemCount()) outputCombo.setSelectedIndex(savedOutput);

        String savedMidi = PREFS.get(KEY_MIDI_DEVICE, "");
        if (!savedMidi.isEmpty()) {
            for (int i = 0; i < midiCombo.getItemCount(); i++) {
                if (midiCombo.getItemAt(i).equals(savedMidi)) {
                    midiCombo.setSelectedIndex(i);
                    break;
                }
            }
        }

        autoScrollEnabled = PREFS.getBoolean(KEY_AUTO_SCROLL, true);
        autoScrollCheck.setSelected(autoScrollEnabled);
    }

    private void saveSettings() {
        if (inputCombo.getSelectedIndex() != -1)
            PREFS.putInt(KEY_INPUT_DEVICE, inputCombo.getSelectedIndex());
        if (outputCombo.getSelectedIndex() != -1)
            PREFS.putInt(KEY_OUTPUT_DEVICE, outputCombo.getSelectedIndex());
        if (midiCombo.getSelectedItem() != null)
            PREFS.put(KEY_MIDI_DEVICE, (String) midiCombo.getSelectedItem());
        PREFS.putBoolean(KEY_AUTO_SCROLL, autoScrollEnabled);
    }

    // -----------------------------------------------------------------------
    private void rescanMIDI() {
        // Аудио
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

        inputCombo.setModel(new DefaultComboBoxModel<>(micNames.toArray(new String[0])));
        outputCombo.setModel(new DefaultComboBoxModel<>(spkNames.toArray(new String[0])));

        // MIDI
        MidiDevice.Info[] midiInfos = MidiSystem.getMidiDeviceInfo();
        List<String> midiInputNames = new ArrayList<>();
        for (MidiDevice.Info info : midiInfos) {
            try {
                MidiDevice dev = MidiSystem.getMidiDevice(info);
                if (dev.getMaxTransmitters() != 0) midiInputNames.add(info.getName());
            } catch (MidiUnavailableException ignored) {}
        }

        if (midiInputNames.isEmpty()) midiInputNames.add("❌ MIDI устройства не найдены");
        midiCombo.setModel(new DefaultComboBoxModel<>(midiInputNames.toArray(new String[0])));

        logMidi("✅ Список MIDI обновлён. Найдено устройств: " +
                (midiInputNames.size() - (midiInputNames.contains("❌ MIDI устройства не найдены") ? 1 : 0)));
        for (String name : midiInputNames) logMidi("   • " + name);
    }

    // -----------------------------------------------------------------------
    private void testOutput() {
        if (midiChannel != null) {
            midiChannel.noteOn(69, 100);
            new Timer(1000, e -> midiChannel.noteOff(69)).start();
            logMidi("🔊 Тестовый звук воспроизведён (Ля 440 Гц)");
            JOptionPane.showMessageDialog(this,
                    "Воспроизводится тестовая нота (Ля 440 Гц).\nСлышите звук пианино?",
                    "Тест звука", JOptionPane.INFORMATION_MESSAGE);
        } else {
            logMidi("❌ Синтезатор не доступен");
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
        String micName = (String) inputCombo.getSelectedItem();
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

        // ---- MIDI вход - ИСПРАВЛЕНО: перебор транслиттеров ----
        String midiName = (String) midiCombo.getSelectedItem();
        if (midiName != null && !midiName.contains("❌")) {
            try {
                MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
                for (MidiDevice.Info info : infos) {
                    if (info.getName().equals(midiName)) {
                        midiInputDevice = MidiSystem.getMidiDevice(info);
                        midiInputDevice.open();
                        logMidi("🔧 Устройство открыто: " + midiName);

                        // Создаём приёмник
                        midiReceiver = new MidiInputReceiver();

                        // Пробуем получить все транслиттеры
                        List<Transmitter> transmitters = midiInputDevice.getTransmitters();
                        logMidi("🔧 Количество транслиттеров: " + transmitters.size());

                        if (!transmitters.isEmpty()) {
                            // Берём первый транслиттер (обычно он и есть нужный вход)
                            Transmitter t = transmitters.get(0);
                            t.setReceiver(midiReceiver);
                            logMidi("🔧 Receiver установлен на первый транслиттер");
                        } else {
                            // Fallback: создаём транслиттер сами
                            Transmitter t = midiInputDevice.getTransmitter();
                            t.setReceiver(midiReceiver);
                            logMidi("🔧 Использован getTransmitter() fallback");
                        }

                        logMidi("✅ MIDI подключён: " + midiName);
                        break;
                    }
                }
            } catch (MidiUnavailableException e) {
                logMidi("❌ Не удалось открыть MIDI порт: " + e.getMessage());
            }
        } else {
            logMidi("⚠️ MIDI устройство не выбрано — работает только микрофон");
        }

        // ---- Запуск потока аудио ----
        if (microphoneLine != null) {
            new Thread(new AudioProcessor()).start();
        }

        startBtn.setEnabled(false);
        startBtn.setBackground(Color.GRAY);
        stopBtn.setEnabled(true);
        stopBtn.setBackground(new Color(244, 67, 54));

        logMidi("🟢 Обработка запущена! Играйте на клавиатуре и пойте в микрофон.");
    }

    private void stopProcessing() {
        isRunning = false;
        if (microphoneLine != null) {
            microphoneLine.stop();
            microphoneLine.close();
            microphoneLine = null;
        }
        if (midiInputDevice != null) {
            midiInputDevice.close();
            midiInputDevice = null;
            midiReceiver = null;
        }
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
        startBtn.setBackground(new Color(76, 175, 80));
        stopBtn.setEnabled(false);
        stopBtn.setBackground(Color.GRAY);

        logMidi("⏹️ Обработка остановлена");
    }

    // -----------------------------------------------------------------------
    private class MidiInputReceiver implements Receiver {
        @Override
        public void send(MidiMessage message, long timeStamp) {
            // ОТЛАДКА: пишем ВСЕ сообщения, даже если isRunning == false
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

            if (!isRunning) return;

            if (message instanceof ShortMessage) {
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

                    if (midiChannel != null) {
                        midiChannel.noteOn(note, velocity);
                        logMidi("🔊 Воспроизвожу ноту " + noteToName(note) + " на синтезаторе");
                        // Автоматическое выключение через 2 секунды, если не пришёл NOTE_OFF
                        new Timer(2000, e -> midiChannel.noteOff(note)).start();
                    }

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
                    if (midiChannel != null) {
                        midiChannel.noteOff(note);
                    }
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

            // Клавиши
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

            // График голоса
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

            // Целевая нота
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