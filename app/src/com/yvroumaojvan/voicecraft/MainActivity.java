package com.yvroumaojvan.voicecraft;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String PREFS = "voicecraft";
    private static final String KEY_HISTORY = "history";

    private EditText inputText;
    private TextView rateLabel;
    private SeekBar rateBar;
    private TextView statusText;
    private TextView playerTitle;
    private Button playBtn;
    private Button saveBtn;
    private ListView historyList;
    private LinearLayout voiceChips;

    private int selectedVoice = 0;
    private MediaPlayer player;
    private File currentAudioFile;
    private String currentText = "";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final ArrayList<HistoryItem> history = new ArrayList<>();

    // 本地离线兜底
    private TextToSpeech localTts;
    private boolean localTtsReady = false;

    static class HistoryItem {
        String time;
        String text;
        String voiceLabel;
        String fileName;
        HistoryItem(String t, String tx, String v, String f) {
            time = t; text = tx; voiceLabel = v; fileName = f;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inputText = findViewById(R.id.input_text);
        rateLabel = findViewById(R.id.rate_label);
        rateBar = findViewById(R.id.rate_bar);
        statusText = findViewById(R.id.status_text);
        playerTitle = findViewById(R.id.player_title);
        playBtn = findViewById(R.id.btn_play);
        saveBtn = findViewById(R.id.btn_save);
        historyList = findViewById(R.id.history_list);
        voiceChips = findViewById(R.id.voice_chips);

        buildVoiceChips();
        setupRateBar();
        setupButtons();
        loadHistory();

        localTts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    localTts.setLanguage(Locale.CHINESE);
                    localTtsReady = true;
                }
            }
        });
    }

    // ---------- UI 构建 ----------

    private void buildVoiceChips() {
        voiceChips.removeAllViews();
        int[] colors = {0xFF3B82F6, 0xFF8B5CF6, 0xFFEC4899, 0xFFF59E0B, 0xFF10B981};
        for (int i = 0; i < Voices.LIST.length; i++) {
            final int index = i;
            TextView chip = new TextView(this);
            chip.setText(Voices.LABEL[i]);
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), dp(8), dp(14), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(8), 0);
            chip.setLayoutParams(lp);
            updateChipStyle(chip, i == selectedVoice, colors[index % colors.length]);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedVoice = index;
                    for (int j = 0; j < voiceChips.getChildCount(); j++) {
                        updateChipStyle((TextView) voiceChips.getChildAt(j),
                            j == selectedVoice, colors[j % colors.length]);
                    }
                }
            });
            voiceChips.addView(chip);
        }
    }

    private void updateChipStyle(TextView chip, boolean selected, int accent) {
        chip.setTextColor(selected ? Color.WHITE : 0xFF94A3B8);
        chip.setBackgroundResource(selected ? R.drawable.chip_selected : R.drawable.chip_normal);
        chip.setAlpha(selected ? 1f : 0.85f);
    }

    private void setupRateBar() {
        rateBar.setMax(20); // -50% ~ +50%
        rateBar.setProgress(10);
        rateLabel.setText("语速  0%");
        rateBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int rate = (progress - 10) * 5;
                String sign = rate > 0 ? "+" : "";
                rateLabel.setText("语速  " + sign + rate + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void setupButtons() {
        findViewById(R.id.btn_synthesize).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { synthesize(); }
        });
        findViewById(R.id.btn_local).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { speakLocal(); }
        });
        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { inputText.setText(""); }
        });
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(); }
        });
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveToDownloads(); }
        });
        historyList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                playHistory(position);
            }
        });
        historyList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                deleteHistory(position);
                return true;
            }
        });
    }

    // ---------- 合成 ----------

    private void synthesize() {
        currentText = inputText.getText().toString().trim();
        if (TextUtils.isEmpty(currentText)) {
            toast("先输入一点文字吧 ✍️");
            return;
        }
        statusText.setText("⏳ 正在合成…");
        playBtn.setEnabled(false);
        final String voice = Voices.ID[selectedVoice];
        final String rate = ratePercent();
        final String label = Voices.LABEL[selectedVoice];
        bg.execute(new Runnable() {
            @Override public void run() {
                try {
                    byte[] mp3 = EdgeTTS.synthesize(voice, currentText, rate);
                    final File f = new File(getFilesDir(),
                        "voice_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp3");
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(mp3);
                    fos.close();
                    ui.post(new Runnable() {
                        @Override public void run() {
                            onSynthesized(f, label);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            statusText.setText("❌ 合成失败");
                            toast("合成失败：" + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void onSynthesized(File f, String voiceLabel) {
        currentAudioFile = f;
        playerTitle.setText(voiceLabel + " · " + currentTextPreview());
        statusText.setText("✅ 合成完成，点击播放试听");
        playBtn.setEnabled(true);
        saveBtn.setEnabled(true);
        addHistory(voiceLabel);
        playCurrent();
    }

    private String ratePercent() {
        int rate = (rateBar.getProgress() - 10) * 5;
        String sign = rate > 0 ? "+" : "";
        return sign + rate + "%";
    }

    private String currentTextPreview() {
        return currentText.length() > 12 ? currentText.substring(0, 12) + "…" : currentText;
    }

    // ---------- 播放 ----------

    private void playCurrent() {
        if (currentAudioFile == null) return;
        stopPlayback();
        try {
            player = new MediaPlayer();
            player.setDataSource(currentAudioFile.getAbsolutePath());
            player.prepare();
            player.start();
            statusText.setText("▶ 播放中…");
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) {
                    statusText.setText("✅ 播放完毕，可保存或继续合成");
                }
            });
        } catch (Exception e) {
            toast("播放失败：" + e.getMessage());
        }
    }

    private void togglePlay() {
        if (currentAudioFile == null) return;
        if (player != null && player.isPlaying()) {
            player.pause();
            statusText.setText("⏸ 已暂停");
        } else {
            if (player == null) { playCurrent(); return; }
            player.start();
            statusText.setText("▶ 播放中…");
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
                player.release();
            } catch (Exception ignored) {}
            player = null;
        }
    }

    private void playHistory(int position) {
        HistoryItem item = history.get(position);
        File f = new File(getFilesDir(), item.fileName);
        if (!f.exists()) { toast("文件已不存在"); return; }
        currentAudioFile = f;
        currentText = item.text;
        playerTitle.setText(item.voiceLabel + " · " + preview(item.text));
        playBtn.setEnabled(true);
        saveBtn.setEnabled(true);
        playCurrent();
    }

    // ---------- 保存到下载 ----------

    private void saveToDownloads() {
        if (currentAudioFile == null) { toast("还没有可保存的音频"); return; }
        try {
            String displayName = "妙音_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp3";
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/妙音工坊");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) { toast("保存失败：无法写入下载目录"); return; }
            OutputStream os = getContentResolver().openOutputStream(uri);
            FileInputStream fis = new FileInputStream(currentAudioFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) os.write(buf, 0, n);
            fis.close();
            os.close();
            toast("✅ 已保存到 下载/妙音工坊/" + displayName);
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
        }
    }

    // ---------- 本地离线兜底 ----------

    private void speakLocal() {
        String text = inputText.getText().toString().trim();
        if (TextUtils.isEmpty(text)) { toast("先输入一点文字吧 ✍️"); return; }
        if (!localTtsReady) { toast("本地引擎未就绪"); return; }
        stopPlayback();
        localTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "local");
        statusText.setText("🔊 本地离线朗读中（音色为系统引擎）");
    }

    // ---------- 历史记录 ----------

    private void addHistory(String voiceLabel) {
        HistoryItem item = new HistoryItem(
            new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date()),
            currentText, voiceLabel, currentAudioFile.getName());
        history.add(0, item);
        if (history.size() > 30) history.remove(history.size() - 1);
        saveHistory();
        renderHistory();
    }

    private void deleteHistory(int position) {
        File f = new File(getFilesDir(), history.get(position).fileName);
        if (f.exists()) f.delete();
        history.remove(position);
        saveHistory();
        renderHistory();
        toast("已删除该条记录");
    }

    private void renderHistory() {
        ArrayList<String> items = new ArrayList<>();
        for (HistoryItem h : history) {
            items.add(h.time + "  " + h.voiceLabel + "\n" + preview(h.text));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_list_item_2, android.R.id.text1, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView t1 = v.findViewById(android.R.id.text1);
                TextView t2 = v.findViewById(android.R.id.text2);
                t1.setTextColor(0xFFE2E8F0);
                t1.setTextSize(14);
                t2.setTextColor(0xFF64748B);
                t2.setTextSize(12);
                v.setBackgroundColor(Color.TRANSPARENT);
                return v;
            }
        };
        historyList.setAdapter(adapter);
    }

    private void saveHistory() {
        StringBuilder sb = new StringBuilder();
        for (HistoryItem h : history) {
            sb.append(h.time).append('|').append(h.voiceLabel).append('|')
              .append(h.fileName).append('|')
              .append(h.text.replace("\n", " ")).append('\n');
        }
        SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        ed.putString(KEY_HISTORY, sb.toString());
        ed.apply();
    }

    private void loadHistory() {
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_HISTORY, "");
        history.clear();
        if (raw != null && !raw.isEmpty()) {
            for (String line : raw.split("\n")) {
                String[] parts = line.split("\\|", 4);
                if (parts.length >= 4) {
                    history.add(new HistoryItem(parts[0], parts[3], parts[1], parts[2]));
                }
            }
        }
        renderHistory();
    }

    private String preview(String s) {
        return s.length() > 22 ? s.substring(0, 22) + "…" : s;
    }

    // ---------- 工具 ----------

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
        bg.shutdown();
        if (localTts != null) { localTts.stop(); localTts.shutdown(); }
    }
}
