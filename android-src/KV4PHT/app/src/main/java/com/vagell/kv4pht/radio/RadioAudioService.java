package com.vagell.kv4pht.radio;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LiveData;

import com.hoho.android.usbserial.driver.SerialTimeoutException;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.vagell.kv4pht.R;
import com.vagell.kv4pht.aprs.parser.APRSPacket;
import com.vagell.kv4pht.aprs.parser.Parser;
import com.vagell.kv4pht.data.ChannelMemory;
import com.vagell.kv4pht.javAX25.ax25.Afsk1200Modulator;
import com.vagell.kv4pht.javAX25.ax25.Afsk1200MultiDemodulator;
import com.vagell.kv4pht.javAX25.ax25.PacketDemodulator;
import com.vagell.kv4pht.javAX25.ax25.PacketHandler;
import com.vagell.kv4pht.ui.MainActivity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Background service that manages the connection to the ESP32 (to control the radio), and
 * handles playing back any audio received from the radio. This frees up the rest of the
 * application to focus primarily on the setup flows and UI, and ensures that the radio audio
 * continues to play even if the phone's screen is off or the user starts another app.
 */
public class RadioAudioService extends Service {
    // Binder given to clients.
    private final IBinder binder = new RadioBinder();

    // Must match the ESP32 device we support.
    // Idx 0 matches https://www.amazon.com/gp/product/B08D5ZD528
    public static final int[] ESP32_VENDOR_IDS = {4292};
    public static final int[] ESP32_PRODUCT_IDS = {60000};

    private static final int MODE_STARTUP = -1;
    private static final int MODE_RX = 0;
    private static final int MODE_TX = 1;
    private static final int MODE_SCAN = 2;
    private int mode = MODE_STARTUP;

    private enum ESP32Command {
        PTT_DOWN((byte) 1),
        PTT_UP((byte) 2),
        TUNE_TO((byte) 3), // paramsStr contains freq, offset, tone details
        FILTERS((byte) 4); // paramStr contains emphasis, highpass, lowpass (each 0/1)

        private byte commandByte;
        ESP32Command(byte commandByte) {
            this.commandByte = commandByte;
        }

        public byte getByte() {
            return commandByte;
        }
    }

    private static final byte SILENT_BYTE = -128;

    // Callbacks to the Activity that started us
    private RadioMissingCallback radioMissingCallback = null;
    private RadioConnectedCallback radioConnectedCallback = null;
    private AprsCallback aprsCallback = null;

    // For transmitting audio to ESP32 / radio
    public static final int AUDIO_SAMPLE_RATE = 44100;
    public static final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    public static final  int audioFormat = AudioFormat.ENCODING_PCM_8BIT;
    public static final  int minBufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, channelConfig, audioFormat) * 2;
    private UsbManager usbManager;
    private UsbDevice esp32Device;
    private UsbSerialPort serialPort;
    private SerialInputOutputManager usbIoManager;
    private static final int TX_AUDIO_CHUNK_SIZE = 512; // Tx audio bytes to send to ESP32 in a single USB write
    private Map<String, Integer> mTones = new HashMap<>();

    // For receiving audio from ESP32 / radio
    private AudioTrack audioTrack;
    private static final int PRE_BUFFER_SIZE = 1000;
    private byte[] rxBytesPrebuffer = new byte[PRE_BUFFER_SIZE];
    private int rxPrebufferIdx = 0;
    private boolean prebufferComplete = false;
    private static final float SEC_BETWEEN_SCANS = 0.5f; // how long to wait during silence to scan to next frequency in scan mode
    private LiveData<List<ChannelMemory>> channelMemoriesLiveData = null;
    private boolean emphasisFilter = false;
    private boolean highpassFilter = false;
    private boolean lowpassFilter = false;

    // Delimiter must match ESP32 code
    private static final byte[] COMMAND_DELIMITER = new byte[] {(byte)0xFF, (byte)0x00, (byte)0xFF, (byte)0x00, (byte)0xFF, (byte)0x00, (byte)0xFF, (byte)0x00};

    // AFSK modem
    private Afsk1200Modulator afskModulator = null;
    private PacketDemodulator afskDemodulator = null;
    private static final int MS_DELAY_BEFORE_DATA_XMIT = 1000;
    private static final int MS_SILENCE_BEFORE_DATA = 300;
    private static final int MS_SILENCE_AFTER_DATA = 700;

    // Radio params and related settings
    private String activeFrequencyStr = "144.000";
    private int squelch = 0;
    private String callsign = null;
    private int consecutiveSilenceBytes = 0; // To determine when to move scan after silence
    private int activeMemoryId = -1; // -1 means we're in simplex mode

    // Safety constants
    private static int RUNAWAY_TX_TIMEOUT_SEC = 180; // Stop runaway tx after 3 minutes
    private long startTxTimeSec = -1;

    // Notification stuff
    private static String MESSAGE_NOTIFICATION_CHANNEL_ID = "aprs_message_notifications";
    private static int MESSAGE_NOTIFICATION_TO_YOU_ID = 0;

    private ThreadPoolExecutor threadPoolExecutor = null;

    /**
     * Class used for the client Binder. This service always runs in the same process as its clients.
     */
    public class RadioBinder extends Binder {
        RadioAudioService getService() {
            // Return this instance of RadioService so clients can call public methods.
            return RadioAudioService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Retrieve necessary parameters from the intent.
        Bundle bundle = intent.getExtras();
        callsign = bundle.getString("callsign");
        squelch = bundle.getInt("squelch");
        emphasisFilter = bundle.getBoolean("emphasisFilter");
        highpassFilter = bundle.getBoolean("highpassFilter");
        lowpassFilter = bundle.getBoolean("lowpassFilter");
        activeMemoryId = bundle.getInt("activeMemoryId");
        activeFrequencyStr = bundle.getString("activeFrequencyStr");

        return binder;
    }

    public void setCallsign(String callsign) {
        this.callsign = callsign;
    }

    public void setSquelch(int squelch) {
        this.squelch = squelch;

        if (activeMemoryId > -1) {
            tuneToMemory(activeMemoryId, squelch);
        } else {
            tuneToFreq(activeFrequencyStr, squelch);
        }
    }

    public void setFilters(boolean emphasis, boolean highpass, boolean lowpass) {
        this.emphasisFilter = emphasis;
        this.highpassFilter = highpass;
        this.lowpassFilter = lowpass;

        setRadioFilters(emphasis, highpass, lowpass);
    }

    public void setActiveMemoryId(int activeMemoryId) {
        this.activeMemoryId = activeMemoryId;

        if (activeMemoryId > -1) {
            tuneToMemory(activeMemoryId, squelch);
        } else {
            tuneToFreq(activeFrequencyStr, squelch);
        }
    }

    public void setActiveFrequencyStr(String activeFrequencyStr) {
        this.activeFrequencyStr = activeFrequencyStr;

        if (activeMemoryId > -1) {
            tuneToMemory(activeMemoryId, squelch);
        } else {
            tuneToFreq(activeFrequencyStr, squelch);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        threadPoolExecutor = new ThreadPoolExecutor(2,
                10, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        createNotificationChannels();
        findESP32Device();
        initAudioTrack();
        setupTones();
        initAFSKModem();
    }

    /**
     * This must be set before any method that requires channels (like scanning or tuning to a
     * memory) is access, or they will just report an error. And it should also be called whenever
     * the active memories have changed (e.g. user selected a different memory group).
     */
    public void setChannelMemories(LiveData<List<ChannelMemory>> channelMemoriesLiveData) {
        this.channelMemoriesLiveData = channelMemoriesLiveData;
    }

    public interface RadioMissingCallback {
        public void radioMissing();
    }

    public void setRadioMissingCallback(RadioMissingCallback callback) {
        radioMissingCallback = callback;
    }

    public interface RadioConnectedCallback {
        public void radioConnected();
    }

    public void setRadioConnectedCallback(RadioConnectedCallback callback) {
        radioConnectedCallback = callback;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }

        if (threadPoolExecutor != null) {
            threadPoolExecutor.shutdownNow();
            threadPoolExecutor = null;
        }
    }

    private void setupTones() {
        mTones.put("None", 0);
        mTones.put("67", 1);
        mTones.put("71.9", 2);
        mTones.put("74.4", 3);
        mTones.put("77", 4);
        mTones.put("79.7", 5);
        mTones.put("82.5", 6);
        mTones.put("85.4", 7);
        mTones.put("88.5", 8);
        mTones.put("91.5", 9);
        mTones.put("94.8", 10);
        mTones.put("97.4", 11);
        mTones.put("100", 12);
        mTones.put("103.5", 13);
        mTones.put("107.2", 14);
        mTones.put("110.9", 15);
        mTones.put("114.8", 16);
        mTones.put("118.8", 17);
        mTones.put("123", 18);
        mTones.put("127.3", 19);
        mTones.put("131.8", 20);
        mTones.put("136.5", 21);
        mTones.put("141.3", 22);
        mTones.put("146.2", 23);
        mTones.put("151.4", 24);
        mTones.put("156.7", 25);
        mTones.put("162.2", 26);
        mTones.put("167.9", 27);
        mTones.put("173.8", 28);
        mTones.put("179.9", 29);
        mTones.put("186.2", 30);
        mTones.put("192.8", 31);
        mTones.put("203.5", 32);
        mTones.put("210.7", 33);
        mTones.put("218.1", 34);
        mTones.put("225.7", 35);
        mTones.put("233.6", 36);
        mTones.put("241.8", 37);
        mTones.put("250.3", 38);
    }

    private void createNotificationChannels() {
        // Notification channel for APRS text chat messages
        NotificationChannel channel = new NotificationChannel(MESSAGE_NOTIFICATION_CHANNEL_ID,
                "Chat messages", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("APRS text chat messages addressed to you");
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    private void restartAudioPrebuffer() {
        prebufferComplete = false;
        rxPrebufferIdx = 0;
    }

    public void setRadioFilters(boolean emphasis, boolean highpass, boolean lowpass) {
        sendCommandToESP32(ESP32Command.FILTERS, (emphasis ? "1" : "0") + (highpass ? "1" : "0") + (lowpass ? "1" : "0"));
    }

    // Tell microcontroller to tune to the given frequency string, which must already be formatted
    // in the style the radio module expects.
    public void tuneToFreq(String frequencyStr, int squelchLevel) {
        mode = MODE_RX;

        if (serialPort != null) {
            sendCommandToESP32(ESP32Command.TUNE_TO, makeSafe2MFreq(activeFrequencyStr) + makeSafe2MFreq(activeFrequencyStr) + "00" + squelchLevel);
        }

        // Reset audio prebuffer
        restartAudioPrebuffer();
    }

    public static String makeSafe2MFreq(String strFreq) {
        Float freq = Float.parseFloat(strFreq);
        freq = Math.min(freq, 148.0f);
        freq = Math.max(freq, 144.0f);

        strFreq = String.format(java.util.Locale.US,"%.3f", freq);
        strFreq = formatFrequency(strFreq);

        return strFreq;
    }

    public static String formatFrequency(String tempFrequency) {
        tempFrequency = tempFrequency.trim();

        // Pad any missing zeroes to match format expected by radio module.
        if (tempFrequency.matches("14[4-8]\\.[0-9][0-9][0-9]")) {
            return tempFrequency;
        } else if (tempFrequency.matches("14[4-8]\\.[0-9][0-9]")) {
            return tempFrequency + "0";
        } else if (tempFrequency.matches("14[4-8]\\.[0-9]")) {
            return tempFrequency + "00";
        } else if (tempFrequency.matches("14[4-8]\\.")) {
            return tempFrequency + "000";
        } else if (tempFrequency.matches("14[4-8]")) {
            return tempFrequency + ".000";
        } else if (tempFrequency.matches("14[4-8][0-9][0-9][0-9]")) {
            return tempFrequency.substring(0, 3) + "." + tempFrequency.substring(3, 6);
        } else if (tempFrequency.matches("14[4-8][0-9][0-9]")) {
            return tempFrequency.substring(0, 3) + "." + tempFrequency.substring(3, 5) + "0";
        } else if (tempFrequency.matches("14[4-8][0-9]")) {
            return tempFrequency.substring(0, 3) + "." + tempFrequency.substring(3, 4) + "00";
        }

        return null;
    }

    private String validateFrequency(String tempFrequency) {
        String newFrequency = formatFrequency(tempFrequency);

        // Resort to the old frequency, the one the user inputted is unsalvageable.
        return newFrequency == null ? activeFrequencyStr : newFrequency;
    }

    private void tuneToMemory(int memoryId, int squelchLevel) {
        if (channelMemoriesLiveData == null) {
            Log.d("DEBUG", "Error: attempted tuneToMemory() but channelMemories was never set.");
            return;
        }
        List<ChannelMemory> channelMemories = channelMemoriesLiveData.getValue();
        for (int i = 0; i < channelMemories.size(); i++) {
            if (channelMemories.get(i).memoryId == memoryId) {
                if (serialPort != null) {
                    tuneToMemory(channelMemories.get(i), squelchLevel);
                }
            }
        }
    }

    public void tuneToMemory(ChannelMemory memory, int squelchLevel) {
        activeFrequencyStr = validateFrequency(memory.frequency);
        activeMemoryId = memory.memoryId;

        if (serialPort != null) {
            sendCommandToESP32(ESP32Command.TUNE_TO,
                    getTxFreq(memory.frequency, memory.offset) + makeSafe2MFreq(memory.frequency) + getToneIdxStr(memory.tone) + squelchLevel);
        }

        // Reset audio prebuffer
        restartAudioPrebuffer();
    }

    private String getToneIdxStr(String toneStr) {
        if (toneStr == null) {
            toneStr = "None";
        }

        Integer toneIdx = mTones.get(toneStr);

        return toneIdx < 10 ? "0" + toneIdx : toneIdx.toString();
    }

    private String getTxFreq(String txFreq, int offset) {
        if (offset == ChannelMemory.OFFSET_NONE) {
            return txFreq;
        } else {
            Float freqFloat = Float.parseFloat(txFreq);
            if (offset == ChannelMemory.OFFSET_UP) {
                freqFloat += .600f;
            } else if (offset == ChannelMemory.OFFSET_DOWN){
                freqFloat -= .600f;
            }
            return makeSafe2MFreq(freqFloat.toString());
        }
    }

    private void checkScanDueToSilence() {
        // Note that we handle scanning explicitly like this rather than using dra->scan() because
        // as best I can tell the DRA818v chip has a defect where it always returns "S=1" (which
        // means there is no signal detected on the given frequency) even when there is. I did
        // extensive debugging and even rewrote large portions of the DRA818v library to determine
        // that this was the case. So in lieu of that, we scan using a timing/silence-based system.
        if (consecutiveSilenceBytes >= (AUDIO_SAMPLE_RATE * SEC_BETWEEN_SCANS)) {
            consecutiveSilenceBytes = 0;
            nextScan();
        }
    }

    private void initAudioTrack() {
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(AUDIO_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBufferSize)
                .build();

        restartAudioPrebuffer();
    }

    public void startPtt(boolean dataMode) {
        if (mode == MODE_TX) {
            return;
        }

        // Setup runaway tx safety measures.
        startTxTimeSec = System.currentTimeMillis() / 1000;
        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(RUNAWAY_TX_TIMEOUT_SEC * 1000);

                    if (mode != MODE_TX) {
                        return;
                    }

                    long elapsedSec = (System.currentTimeMillis() / 1000) - startTxTimeSec;
                    if (elapsedSec > RUNAWAY_TX_TIMEOUT_SEC) { // Check this because multiple tx may have happened with RUNAWAY_TX_TIMEOUT_SEC.
                        Log.d("DEBUG", "Warning: runaway tx timeout reached, PTT stopped.");
                        endPtt();
                    }
                } catch (InterruptedException e) {
                }
            }
        });

        mode = MODE_TX;
        sendCommandToESP32(ESP32Command.PTT_DOWN);
        audioTrack.stop();
    }

    public void endPtt() {
        if (mode == MODE_RX) {
            return;
        }
        mode = MODE_RX;
        sendCommandToESP32(ESP32Command.PTT_UP);
        audioTrack.flush();
        restartAudioPrebuffer();
    }

    private void findESP32Device() {
        Log.d("DEBUG", "findESP32Device()");

        HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();

        for (UsbDevice device : usbDevices.values()) {
            // Check for device's vendor ID and product ID
            if (isESP32Device(device)) {
                esp32Device = device;
                break;
            }
        }

        if (esp32Device == null) {
            Log.d("DEBUG", "No ESP32 detected");
            if (radioMissingCallback != null) {
                radioMissingCallback.radioMissing();
            }
        } else {
            Log.d("DEBUG", "Found ESP32.");
            setupSerialConnection();
        }
    }

    private boolean isESP32Device(UsbDevice device) {
        Log.d("DEBUG", "isESP32Device()");

        int vendorId = device.getVendorId();
        int productId = device.getProductId();
        Log.d("DEBUG", "vendorId: " + vendorId + " productId: " + productId + " name: " + device.getDeviceName());
        for (int i = 0; i < ESP32_VENDOR_IDS.length; i++) {
            if ((vendorId == ESP32_VENDOR_IDS[i]) && (productId == ESP32_PRODUCT_IDS[i])) {
                return true;
            }
        }
        return false;
    }

    public void setupSerialConnection() {
        Log.d("DEBUG", "setupSerialConnection()");

        // Find all available drivers from attached devices.
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        if (availableDrivers.isEmpty()) {
            Log.d("DEBUG", "Error: no available USB drivers.");
            if (radioMissingCallback != null) {
                radioMissingCallback.radioMissing();
            }
            return;
        }

        // Open a connection to the first available driver.
        UsbSerialDriver driver = availableDrivers.get(0);
        UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
        if (connection == null) {
            Log.d("DEBUG", "Error: couldn't open USB device.");
            if (radioMissingCallback != null) {
                radioMissingCallback.radioMissing();
            }
            return;
        }

        serialPort = driver.getPorts().get(0); // Most devices have just one port (port 0)
        Log.d("DEBUG", "serialPort: " + serialPort);
        try {
            serialPort.open(connection);
            serialPort.setParameters(921600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        } catch (Exception e) {
            Log.d("DEBUG", "Error: couldn't open USB serial port.");
            if (radioMissingCallback != null) {
                radioMissingCallback.radioMissing();
            }
            return;
        }

        try { // These settings needed for better data transfer on Adafruit QT Py ESP32-S2
            serialPort.setRTS(true);
            serialPort.setDTR(true);
        } catch (Exception e) {
            // Ignore, may not be supported on all devices.
        }

        usbIoManager = new SerialInputOutputManager(serialPort, new SerialInputOutputManager.Listener() {
            @Override
            public void onNewData(byte[] data) {
                handleESP32Data(data);
            }

            @Override
            public void onRunError(Exception e) {
                Log.d("DEBUG", "Error reading from ESP32.");
                connection.close();
                try {
                    serialPort.close();
                } catch (Exception ex) {
                    // Ignore.
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
                findESP32Device(); // Attempt to reconnect after the brief pause above.
                return;
            }
        });
        usbIoManager.setWriteBufferSize(90000); // Must be large enough that ESP32 can take its time accepting our bytes without overrun.
        usbIoManager.setReadBufferSize(4096); // Must be much larger than ESP32's send buffer (so we never block it)
        usbIoManager.setReadTimeout(1000); // Must not be 0 (infinite) or it may block on read() until a write() occurs.
        usbIoManager.start();

        Log.d("DEBUG", "Connected to ESP32.");

        // After a brief pause (to let it boot), do things with the ESP32 that we were waiting to do.
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                initAfterESP32Connected();
            }
        }, 3000);
    }

    private void initAfterESP32Connected() {
        if (radioConnectedCallback != null) {
            radioConnectedCallback.radioConnected();
        }

        // Start by prebuffering some audio
        restartAudioPrebuffer();

        // Tell the radio about any settings the user set.
        setRadioFilters(emphasisFilter, highpassFilter, lowpassFilter);
        if (activeMemoryId > -1) {
            tuneToMemory(activeMemoryId, squelch);
        } else {
            tuneToFreq(activeFrequencyStr, squelch);
        }

        // Turn off scanning if it was on (e.g. if radio was unplugged briefly and reconnected)
        setScanning(false);
    }

    public void setScanning(boolean scanning, boolean goToRxMode) {
        if (!scanning) {
            // If squelch was off before we started scanning, turn it off again
            if (squelch == 0) {
                tuneToMemory(activeMemoryId, squelch);
            }

            if (goToRxMode) {
                mode = MODE_RX;
            }
        } else { // Start scanning
            mode = MODE_SCAN;
            nextScan();
        }
    }

    public void setScanning(boolean scanning) {
        setScanning(scanning, true);
    }

    private void nextScan() {
        if (mode != MODE_SCAN) {
            return;
        }

        List<ChannelMemory> channelMemories = channelMemoriesLiveData.getValue();
        ChannelMemory memoryToScanNext = null;

        // If we're in simplex, start by scanning to the first memory
        if (activeMemoryId == -1) {
            try {
                memoryToScanNext = channelMemories.get(0);
            } catch (IndexOutOfBoundsException e) {
                return; // No memories to scan.
            }
        }

        if (memoryToScanNext == null) {
            // Find the next memory after the one we last scanned
            for (int i = 0; i < channelMemories.size() - 1; i++) {
                if (channelMemories.get(i).memoryId == activeMemoryId) {
                    memoryToScanNext = channelMemories.get(i + 1);
                    break;
                }
            }
        }

        if (memoryToScanNext == null) {
            // If we hit the end of memories, go back to scanning from the start
            memoryToScanNext = channelMemories.get(0);
        }

        consecutiveSilenceBytes = 0;

        // Log.d("DEBUG", "Scanning to: " + memoryToScanNext.name);
        tuneToMemory(memoryToScanNext, squelch > 0 ? squelch : 1); // If user turned off squelch, set it to 1 during scan.
    }

    public void sendAudioToESP32(byte[] audioBuffer, boolean dataMode) {
        if (audioBuffer.length <= TX_AUDIO_CHUNK_SIZE) {
            sendBytesToESP32(audioBuffer);
        } else {
            // If the audio is fairly long, we need to send it to ESP32 at the same rate
            // as audio sampling. Otherwise, we'll overwhelm its DAC buffer and some audio will
            // be lost.
            final Handler handler = new Handler(Looper.getMainLooper());
            final float msToSendOneChunk = (float) TX_AUDIO_CHUNK_SIZE / (float) AUDIO_SAMPLE_RATE * 1000f;
            float nextSendDelay = 0f;
            for (int i = 0; i < audioBuffer.length; i += TX_AUDIO_CHUNK_SIZE) {
                final int chunkStart = i;
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        android.os.Process.setThreadPriority(
                                android.os.Process.THREAD_PRIORITY_BACKGROUND +
                                        android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE);
                        sendBytesToESP32(Arrays.copyOfRange(audioBuffer, chunkStart,
                                Math.min(audioBuffer.length, chunkStart + TX_AUDIO_CHUNK_SIZE)));
                    }
                }, (int) nextSendDelay);

                nextSendDelay += msToSendOneChunk;
            }

            // In data mode, also schedule PTT up after last audio chunk goes out.
            if (dataMode) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        android.os.Process.setThreadPriority(
                                android.os.Process.THREAD_PRIORITY_BACKGROUND +
                                        android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE);
                        endPtt();
                    }
                }, (int) nextSendDelay);
            }
        }
    }

    public void sendCommandToESP32(ESP32Command command) {
        byte[] commandArray = { COMMAND_DELIMITER[0], COMMAND_DELIMITER[1],
                COMMAND_DELIMITER[2], COMMAND_DELIMITER[3], COMMAND_DELIMITER[4], COMMAND_DELIMITER[5],
                COMMAND_DELIMITER[6], COMMAND_DELIMITER[7], command.getByte() };
        sendBytesToESP32(commandArray);
        Log.d("DEBUG", "Sent command: " + command);
    }

    public void sendCommandToESP32(ESP32Command command, String paramsStr) {
        byte[] commandArray = { COMMAND_DELIMITER[0], COMMAND_DELIMITER[1],
                COMMAND_DELIMITER[2], COMMAND_DELIMITER[3], COMMAND_DELIMITER[4], COMMAND_DELIMITER[5],
                COMMAND_DELIMITER[6], COMMAND_DELIMITER[7], command.getByte() };
        byte[] combined = new byte[commandArray.length + paramsStr.length()];
        ByteBuffer buffer = ByteBuffer.wrap(combined);
        buffer.put(commandArray);
        buffer.put(paramsStr.getBytes(StandardCharsets.US_ASCII));
        combined = buffer.array();

        // Write it in a single call so the params have a better chance (?) to fit in receive buffer on mcu.
        // A little concerned there could be a bug here in rare chance that these bytes span receive
        // buffer size on mcu.
        // TODO implement a more robust way (in mcu code) of ensuring params are received by mcu
        sendBytesToESP32(combined);
        Log.d("DEBUG", "Sent command: " + command + " params: " + paramsStr);
    }

    public synchronized void sendBytesToESP32(byte[] newBytes) {
        try {
            // usbIoManager.writeAsync(newBytes); // On MCUs like the ESP32 S2 this causes USB failures with concurrent USB rx/tx.
            int bytesWritten = 0;
            int totalBytes = newBytes.length;
            final int MAX_BYTES_PER_USB_WRITE = 128;
            int usbRetries = 0;
            do {
                try {
                    byte[] arrayPart = Arrays.copyOfRange(newBytes, bytesWritten, Math.min(bytesWritten + MAX_BYTES_PER_USB_WRITE, totalBytes));
                    serialPort.write(arrayPart, 200);
                    bytesWritten += MAX_BYTES_PER_USB_WRITE;
                    usbRetries = 0;
                } catch (SerialTimeoutException ste) {
                    // Do nothing, we'll try again momentarily. ESP32's serial buffer may be full.
                    usbRetries++;
                    // Log.d("DEBUG", "usbRetries: " + usbRetries);
                }
            } while (bytesWritten < totalBytes && usbRetries < 10);
            // Log.d("DEBUG", "Wrote data: " + Arrays.toString(newBytes));
        } catch (Exception e) {
            e.printStackTrace();
            try {
                serialPort.close();
            } catch (Exception ex) {
                // Ignore.
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
            findESP32Device(); // Attempt to reconnect after the brief pause above.
        }
    }

    private void handleESP32Data(byte[] data) {
            // Log.d("DEBUG", "Got bytes from ESP32: " + Arrays.toString(data));
         /* try {
            String dataStr = new String(data, "UTF-8");
            //if (dataStr.length() < 100 && dataStr.length() > 0)
                Log.d("DEBUG", "Str data from ESP32: " + dataStr);
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            } */
            // Log.d("DEBUG", "Num bytes from ESP32: " + data.length);

        if (mode == MODE_RX || mode == MODE_SCAN) {
            if (prebufferComplete && audioTrack != null) {
                synchronized (audioTrack) {
                    if (afskDemodulator != null) { // Avoid race condition at app start.
                        // Play the audio.
                        audioTrack.write(data, 0, data.length);

                        // Add the audio samples to the AFSK demodulator.
                        float[] audioAsFloats = convertPCM8ToFloatArray(data);
                        afskDemodulator.addSamples(audioAsFloats, audioAsFloats.length);
                    }

                    if (audioTrack != null && audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack.play();
                    }
                }
            } else {
                for (int i = 0; i < data.length; i++) {
                    // Prebuffer the incoming audio data so AudioTrack doesn't run out of audio to play
                    // while we're waiting for more bytes.
                    rxBytesPrebuffer[rxPrebufferIdx++] = data[i];
                    if (rxPrebufferIdx == PRE_BUFFER_SIZE) {
                        prebufferComplete = true;
                        // Log.d("DEBUG", "Rx prebuffer full, writing to audioTrack.");
                        if (audioTrack != null) {
                            if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                                audioTrack.play();
                            }
                            synchronized (audioTrack) {
                                audioTrack.write(rxBytesPrebuffer, 0, PRE_BUFFER_SIZE);
                            }
                        }

                        rxPrebufferIdx = 0;
                        break; // Might drop a few audio bytes from data[], should be very minimal
                    }
                }
            }
        }

        if (mode == MODE_SCAN) {
            // Track consecutive silent bytes, so if we're scanning we can move to next after a while.
            for (int i = 0; i < data.length; i++) {
                if (data[i] == SILENT_BYTE) {
                    consecutiveSilenceBytes++;
                    // Log.d("DEBUG", "consecutiveSilenceBytes: " + consecutiveSilenceBytes);
                    checkScanDueToSilence();
                } else {
                    consecutiveSilenceBytes = 0;
                }
            }
        } else if (mode == MODE_TX) {
            // Print any data we get in MODE_TX (we're not expecting any, this is either leftover rx bytes or debug info).
            /* try {
                String dataStr = new String(data, "UTF-8");
                Log.d("DEBUG", "Unexpected data from ESP32 during MODE_TX: " + dataStr);
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            } */
        }
    }

    private float[] convertPCM8ToFloatArray(byte[] pcm8Data) {
        // Create a float array of the same length as the input byte array
        float[] floatData = new float[pcm8Data.length];

        // Iterate through the byte array and convert each sample
        for (int i = 0; i < pcm8Data.length; i++) {
            // Convert unsigned 8-bit PCM to signed 8-bit value
            int signedValue = (pcm8Data[i] & 0xFF) - 128;

            // Normalize the signed 8-bit value to the range [-1.0, 1.0]
            floatData[i] = signedValue / 128.0f;
        }

        return floatData;
    }

    private byte convertFloatToPCM8(float floatValue) {
        // Clamp the float value to the range [-1.0, 1.0] to prevent overflow
        float clampedValue = Math.max(-1.0f, Math.min(1.0f, floatValue));

        // Convert float value in range [-1.0, 1.0] to signed 8-bit value
        int signedValue = Math.round(clampedValue * 128);

        // Convert signed 8-bit value to unsigned 8-bit PCM (range 0 to 255)
        return (byte) (signedValue + 128);
    }

    public interface AprsCallback {
        public void packetReceived();
    }

    public void setAprsCallback(AprsCallback aprsCallback) {
        this.aprsCallback = aprsCallback;
    }

    private void initAFSKModem() {
        final Context activity = this;

        PacketHandler packetHandler = new PacketHandler() {
            @Override
            public void handlePacket(byte[] data) {
                APRSPacket aprsPacket;
                try {
                    aprsPacket = Parser.parseAX25(data);
                } catch (Exception e) {
                    Log.d("DEBUG", "Unable to parse an APRSPacket, skipping.");
                    return;
                }

                if (aprsCallback != null) {
                    aprsCallback.packetReceived(aprsPacket);
                }
            }
        };

        try {
            afskDemodulator = new Afsk1200MultiDemodulator(AUDIO_SAMPLE_RATE, packetHandler);
            afskModulator = new Afsk1200Modulator(AUDIO_SAMPLE_RATE);
        } catch (Exception e) {
            Log.d("DEBUG", "Unable to create AFSK modem objects.");
        }
    }

    private void showNotification(String notificationChannelId, int notificationTypeId, String title, String message, String tapIntentName) {
        if (notificationChannelId == null || title == null || message == null) {
            Log.d("DEBUG", "Unexpected null in showNotification.");
            return;
        }

        // Has the user disallowed notifications?
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        // If they tap the notification when doing something else, come back to this app
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(tapIntentName);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Notify the user they got a message.
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, notificationChannelId)
                .setSmallIcon(R.drawable.ic_chat_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true) // Dismiss on tap
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.notify(notificationTypeId, builder.build());
    }
}