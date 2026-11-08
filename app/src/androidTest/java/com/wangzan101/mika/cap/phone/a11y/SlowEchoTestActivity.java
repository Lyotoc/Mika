package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 读回晚到的输入框的测试页,重现安卓 10 小红书搜索框上的重复输入:
 * - 「慢回显输入框」:字已经写进去了,但对无障碍报告的文字要过一会儿才更新(不少 App 的搜索框是这样);
 * - 「回显卡住的框」:写进去了,但报告的文字一直不变(读回永远对不上)。
 * 下面一行「实际」始终显示输入框里真正的字,测试用它判断有没有被写成两遍。
 * 测试 APK 的页面运行在测试包自己的进程里、不带 Kotlin 标准库,所以用 Java 写。
 */
public class SlowEchoTestActivity extends Activity {
    /** 真正写入的字,同步显示在旁边,测试读它判断有没有重复。 */
    static final String LABEL = "实际:";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        TextView actual = new TextView(this);
        actual.setText(LABEL);
        actual.setTextSize(18f);

        EditText slow = new SlowEchoEditText(this, 700L, actual);
        slow.setHint("慢回显输入框");
        root.addView(slow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText stuck = new SlowEchoEditText(this, Long.MAX_VALUE, actual);
        stuck.setHint("回显卡住的框");
        root.addView(stuck, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(actual);
        setContentView(root);
    }

    /**
     * 字照常写进编辑器,但 [EditText.getText](无障碍读回用的就是它)在 [delayMs] 之后才报告新内容;
     * [delayMs] 为 [Long.MAX_VALUE] 时永远不报告(读回一直卡住)。
     */
    static class SlowEchoEditText extends EditText {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final long delayMs;
        private CharSequence reported;
        private final Runnable publish = new Runnable() {
            @Override
            public void run() {
                reported = SlowEchoEditText.super.getText();
            }
        };

        SlowEchoEditText(Context context, long delayMs, TextView actual) {
            super(context);
            this.delayMs = delayMs;
            reported = "";
            addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable s) {
                    actual.setText(LABEL + s);
                    handler.removeCallbacks(publish);
                    if (delayMs != Long.MAX_VALUE) handler.postDelayed(publish, delayMs);
                }
            });
        }

        @Override
        public Editable getText() {
            CharSequence r = reported;
            if (r == null) r = "";
            return r instanceof Editable ? (Editable) r : new SpannableStringBuilder(r);
        }
    }
}
