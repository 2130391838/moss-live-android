package cn.mosslocal.live;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String PREFS = "moss";
    private static final int REQ_MIC = 1001;

    private static final String LUNA_PROMPT =
            "你是 Luna，一位温柔、活泼、有亲和力的语音助手。"
            + "说话自然口语化，像在打电话，句子短、有停顿、允许使用“嗯”“对呀”这类语气词。"
            + "用户在你说的时候一开口，你就立刻停下来听，不需要他说“等一下”或“打断一下”；"
            + "只有“嗯”“对”这类一两个字的附和、以及他还在思考时的停顿，你才继续说下去。"
            + "被打断后从他实际听到的地方接着说，不要从头重念。"
            + "回答控制在两三句话内，除非用户要求展开。";
    private static final String LUNA_INSTR = "始终用中文口语回答；不要输出 Markdown、列表或表情符号。";

    private static final String LEO_PROMPT =
            "你是 Leo，一位沉稳、可靠、条理清晰的语音助理。"
            + "先给结论再给一句理由，语气平静，不夸张、不寒暄过度。"
            + "用户在你说的时候一开口就立刻停下来听他说完，不需要他喊“等一下”。"
            + "遇到复杂任务先确认关键信息，再分步说明，必要时提醒用户你在后台处理。"
            + "回答简洁，控制在三句话内。";
    private static final String LEO_INSTR = "始终用中文回答；不使用 Markdown、列表或表情符号；必要时主动追问缺失信息。";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private FrameLayout root;
    private View setupPanel;
    private static final float GAIN_MIN = 1.0f;
    private static final float GAIN_MAX = 6.0f;
    private float gainValue = 3.0f;

    private View callPanel;
    private EditText keyInput;
    private TextView lunaCard, leoCard;
    private TextView statusText;
    private TextView transcriptView;
    private OrbView orb;
    private SeekBar volBar;
    private TextView volLabel;
    private Button muteBtn;

    private String personaId = "luna";
    private MossClient client;
    private boolean muted;
    private AudioManager audioManager;

    private final List<String> lines = new ArrayList<>();
    private String lastSpeaker;
    private boolean lastSpeakerDone = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        gainValue = prefs.getFloat("gain", 3.0f);   // 必须在 buildCall() 之前读，否则滑块显示默认值
        personaId = prefs.getString("persona", "luna");
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        buildUi();
    }

    // ------------------------------------------------------------ 界面

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080A);

        setupPanel = buildSetup();
        callPanel = buildCall();
        callPanel.setVisibility(View.GONE);

        root.addView(setupPanel);
        root.addView(callPanel);
        setContentView(root);
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private View buildSetup() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF07080A);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(this, 24);
        box.setPadding(pad, dp(this, 56), pad, dp(this, 32));

        TextView title = new TextView(this);
        title.setText("MOSS Live");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(34);
        title.setTypeface(null, 1);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("全双工实时语音 · 边听边说 · 自然打断");
        sub.setTextColor(0xFF8A8F98);
        sub.setTextSize(13);
        sub.setPadding(0, dp(this, 8), 0, dp(this, 36));
        box.addView(sub);

        TextView keyLabel = label("API Key");
        box.addView(keyLabel);

        keyInput = new EditText(this);
        keyInput.setHint("sk-...（在 platform.moss.cn 获取）");
        keyInput.setHintTextColor(0xFF55595F);
        keyInput.setTextColor(0xFFEDEFF2);
        keyInput.setTextSize(14);
        keyInput.setSingleLine(true);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setBackground(roundRect(0xFF14161A, 0xFF2A2E35, 14));
        keyInput.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        keyInput.setText(prefs.getString("key", ""));
        box.addView(keyInput);

        TextView personaLabel = label("声音角色");
        personaLabel.setPadding(0, dp(this, 28), 0, 0);
        box.addView(personaLabel);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(this, 12), 0, 0);

        lunaCard = personaCard("Luna", "温柔 · 会接话");
        leoCard = personaCard("Leo", "沉稳 · 有条理");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(this, 10);
        row.addView(lunaCard, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(leoCard, lp2);
        box.addView(row);

        lunaCard.setOnClickListener(v -> selectPersona("luna"));
        leoCard.setOnClickListener(v -> selectPersona("leo"));
        applyPersonaStyles();

        Button start = new Button(this);
        start.setText("开始通话");
        start.setTextSize(16);
        start.setTextColor(0xFF1A0B04);
        start.setTypeface(null, 1);
        start.setAllCaps(false);
        start.setBackground(roundRect(0xFFFF7A33, 0xFFFF7A33, 18));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 56));
        blp.topMargin = dp(this, 36);
        box.addView(start, blp);
        start.setOnClickListener(v -> requestStart());

        TextView hint = new TextView(this);
        hint.setText("通话会实时上传麦克风音频到 MOSS 云端；不使用时会彻底释放麦克风。\n"
                + "本应用不依赖电脑，直接用你的 API Key 连接 MOSS。");
        hint.setTextColor(0xFF6B7078);
        hint.setTextSize(11.5f);
        hint.setLineSpacing(dp(this, 4), 1f);
        hint.setPadding(0, dp(this, 24), 0, 0);
        box.addView(hint);

        scroll.addView(box);
        return scroll;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF9AA0A8);
        t.setTextSize(12);
        t.setPadding(0, 0, 0, dp(this, 8));
        return t;
    }

    private TextView personaCard(String name, String desc) {
        TextView t = new TextView(this);
        t.setText(name + "\n" + desc);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(this, 12), dp(this, 20), dp(this, 12), dp(this, 20));
        t.setLineSpacing(dp(this, 4), 1f);
        return t;
    }

    private void selectPersona(String id) {
        personaId = id;
        prefs.edit().putString("persona", id).apply();
        applyPersonaStyles();
    }

    private void applyPersonaStyles() {
        if (lunaCard == null) return;
        boolean luna = "luna".equals(personaId);
        lunaCard.setTextColor(luna ? 0xFFFFC08A : 0xFF9AA0A8);
        lunaCard.setBackground(roundRect(luna ? 0xFF241207 : 0xFF14161A,
                luna ? 0xFFFF7A33 : 0xFF2A2E35, 16));
        leoCard.setTextColor(!luna ? 0xFF9FD8FF : 0xFF9AA0A8);
        leoCard.setBackground(roundRect(!luna ? 0xFF0A1A24 : 0xFF14161A,
                !luna ? 0xFF3EA8E0 : 0xFF2A2E35, 16));
    }

    private View buildCall() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF06070A);
        int pad = dp(this, 20);
        box.setPadding(pad, dp(this, 48), pad, dp(this, 28));

        statusText = new TextView(this);
        statusText.setText("正在连接…");
        statusText.setTextColor(0xFFFFB07A);
        statusText.setTextSize(15);
        statusText.setGravity(Gravity.CENTER);
        box.addView(statusText);

        orb = new OrbView(this);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.35f);
        olp.topMargin = dp(this, 12);
        box.addView(orb, olp);

        transcriptView = new TextView(this);
        transcriptView.setTextColor(0xFFB9BFC7);
        transcriptView.setTextSize(13.5f);
        transcriptView.setLineSpacing(dp(this, 5), 1f);
        ScrollView ts = new ScrollView(this);
        ts.addView(transcriptView);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        tlp.bottomMargin = dp(this, 18);
        box.addView(ts, tlp);

        // ---- 音量行：上游语音本身偏小，这里给软件增益 + 软限幅 ----
        LinearLayout volRow = new LinearLayout(this);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        volRow.setGravity(Gravity.CENTER_VERTICAL);

        volLabel = new TextView(this);
        volLabel.setTextColor(0xFF9AA2AF);
        volLabel.setTextSize(11.5f);
        volLabel.setText("音量 " + String.format(java.util.Locale.US, "%.1f×", gainValue));
        volLabel.setWidth(dp(this, 68));

        volBar = new SeekBar(this);
        volBar.setMax((int) ((GAIN_MAX - GAIN_MIN) * 10));   // 1.0x ~ 6.0x，步进 0.1
        volBar.setProgress((int) ((gainValue - GAIN_MIN) * 10));
        volBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                float v = GAIN_MIN + progress / 10f;
                gainValue = v;
                volLabel.setText(String.format(java.util.Locale.US, "音量 %.1f×", v));
                if (client != null) client.setGain(v);
                prefs.edit().putFloat("gain", v).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        volRow.addView(volLabel);
        volRow.addView(volBar, vlp);
        LinearLayout.LayoutParams vrp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vrp.bottomMargin = dp(this, 14);
        box.addView(volRow, vrp);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        muteBtn = actionButton("静音", 0xFF14161A, 0xFFEDEFF2);
        Button cut = actionButton("打断", 0xFF14161A, 0xFFEDEFF2);
        Button hang = actionButton("挂断", 0xFF7A1E1E, 0xFFFFD9D9);

        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b1.rightMargin = dp(this, 8);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b2.rightMargin = dp(this, 8);
        LinearLayout.LayoutParams b3 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btns.addView(muteBtn, b1);
        btns.addView(cut, b2);
        btns.addView(hang, b3);
        box.addView(btns);

        muteBtn.setOnClickListener(v -> toggleMute());
        cut.setOnClickListener(v -> { if (client != null) client.interruptNow(); });
        hang.setOnClickListener(v -> endCall());

        return box;
    }

    private Button actionButton(String text, int bg, int fg) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setBackground(roundRect(bg, 0xFF2A2E35, 16));
        b.setPadding(0, dp(this, 14), 0, dp(this, 14));
        return b;
    }

    private GradientDrawable roundRect(int fill, int stroke, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(this, radius));
        d.setStroke(dp(this, 1), stroke);
        return d;
    }

    // ------------------------------------------------------------ 通话流程

    private void requestStart() {
        String key = keyInput.getText().toString().trim();
        if (TextUtils.isEmpty(key)) {
            toast("请先填入 API Key");
            return;
        }
        prefs.edit().putString("key", key).apply();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startCall();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] granted) {
        super.onRequestPermissionsResult(code, perms, granted);
        if (code == REQ_MIC) {
            if (granted.length > 0 && granted[0] == PackageManager.PERMISSION_GRANTED) {
                startCall();
            } else {
                toast("没有麦克风权限就无法通话");
            }
        }
    }

    private void startCall() {
        if (client != null) return;
        lines.clear();
        lastSpeaker = null;
        transcriptView.setText("");
        muted = false;
        muteBtn.setText("静音");

        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        boolean luna = "luna".equals(personaId);

        client = new MossClient(
                prefs.getString("key", ""),
                luna ? "Luna" : "Leo",
                luna ? LUNA_PROMPT : LEO_PROMPT,
                luna ? LUNA_INSTR : LEO_INSTR,
                new MossClient.Listener() {
                    @Override public void onState(String state, String detail) {
                        ui.post(() -> applyState(state, detail));
                    }
                    @Override public void onTranscript(String who, String text, boolean done) {
                        ui.post(() -> appendTranscript(who, text, done));
                    }
                    @Override public void onError(String message) {
                        ui.post(() -> toast(message));
                    }
                    @Override public void onLevel(float mic, boolean playing) {
                        ui.post(() -> orb.setLevel(mic, playing));
                    }
                    @Override public void onBargeIn(String source) {
                        ui.post(() -> applyState("listening", ""));
                    }
                    @Override public void onClosed() {
                        ui.post(() -> endCall());
                    }
                });

        setupPanel.setVisibility(View.GONE);
        callPanel.setVisibility(View.VISIBLE);
        client.setGain(gainValue);
        client.connect();
    }

    private void applyState(String state, String detail) {
        switch (state) {
            case "connecting": statusText.setText("正在连接…"); break;
            case "attached": statusText.setText("正在配置会话…"); break;
            case "ready":
            case "listening": statusText.setText("在听你说"); break;
            case "thinking": statusText.setText("正在想…"); break;
            case "speaking": statusText.setText("正在说"); break;
            case "failed":
            case "closed": statusText.setText(detail == null || detail.isEmpty() ? "已断开" : detail); break;
            default: break;
        }
    }

    private void appendTranscript(String who, String text, boolean done) {
        String prefix = "user".equals(who) ? "你：" : ("luna".equals(personaId) ? "Luna：" : "Leo：");
        String line = prefix + text;
        // 同一说话人且上一行还在流式更新中 → 就地替换，避免同一句出现两行
        if (!lines.isEmpty() && who.equals(lastSpeaker) && !lastSpeakerDone) {
            lines.set(lines.size() - 1, line);
        } else {
            lines.add(line);
        }
        lastSpeaker = who;
        lastSpeakerDone = done;
        while (lines.size() > 12) lines.remove(0);
        transcriptView.setText(TextUtils.join("\n", lines));
    }

    private void toggleMute() {
        muted = !muted;
        muteBtn.setText(muted ? "取消静音" : "静音");
        if (client != null) client.setMuted(muted);
    }

    private void endCall() {
        if (client != null) {
            MossClient c = client;
            client = null;
            c.close();
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (audioManager != null) {
            audioManager.setSpeakerphoneOn(false);
            audioManager.setMode(AudioManager.MODE_NORMAL);
        }
        callPanel.setVisibility(View.GONE);
        setupPanel.setVisibility(View.VISIBLE);
        orb.setLevel(0, false);
    }

    @Override
    public void onBackPressed() {
        if (client != null) {
            endCall();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (client != null) {
            MossClient c = client;
            client = null;
            c.close();
        }
        super.onDestroy();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ 光球

    static class OrbView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float level;
        private boolean playing;
        private float smooth;
        private long lastFrameAt;

        OrbView(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(c, 1.5f));
        }

        private static int dp(Context c, float v) {
            return Math.round(v * c.getResources().getDisplayMetrics().density);
        }

        void setLevel(float value, boolean isPlaying) {
            level = value;
            playing = isPlaying;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            long now = SystemClock.uptimeMillis();
            float dt = lastFrameAt == 0 ? 16f : Math.min(64f, now - lastFrameAt);
            lastFrameAt = now;
            smooth += (level - smooth) * Math.min(1f, dt / 110f);

            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float base = Math.min(getWidth(), getHeight()) * 0.19f;
            float t = now / 1000f;
            float breathe = (float) (Math.sin(t * 1.6) * 0.04 + 1.0);

            float radius = base * (1f + smooth * 0.55f) * breathe;

            RadialGradient g = new RadialGradient(cx, cy, radius * 1.5f,
                    new int[]{0xFFFFC79A, 0xFFFF7A33, 0x00FF7A33},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            paint.setShader(g);
            canvas.drawCircle(cx, cy, radius * 1.5f, paint);
            paint.setShader(null);

            // 两圈缓慢扩散的涟漪：说话时更明显
            for (int i = 0; i < 2; i++) {
                float phase = (t * (playing ? 0.85f : 0.4f) + i * 0.5f) % 1f;
                float rr = base * (1.2f + phase * (playing ? 2.1f : 1.2f));
                int alpha = (int) (60 * (1f - phase) * (playing ? 1.4f : 0.7f));
                if (alpha <= 0) continue;
                ring.setColor(Color.argb(Math.min(alpha, 90), 255, 138, 71));
                canvas.drawCircle(cx, cy, rr, ring);
            }

            postInvalidateOnAnimation();
        }
    }
}
