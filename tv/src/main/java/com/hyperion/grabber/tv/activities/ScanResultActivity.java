package com.hyperion.grabber.tv.activities;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import com.hyperion.grabber.common.network.Hyperion;
import com.hyperion.grabber.common.util.Preferences;
import com.hyperion.grabber.tv.R;

import java.io.IOException;
import java.util.Random;

import nl.dionsegijn.konfetti.xml.KonfettiView;
import nl.dionsegijn.konfetti.core.Party;
import nl.dionsegijn.konfetti.core.PartyFactory;
import nl.dionsegijn.konfetti.core.emitter.Emitter;
import nl.dionsegijn.konfetti.core.emitter.EmitterConfig;
import nl.dionsegijn.konfetti.core.models.Shape;
import nl.dionsegijn.konfetti.core.models.Size;

import java.util.concurrent.TimeUnit;

/** Shows the result of a network scan and allows to save the result to Preferences */
public class ScanResultActivity extends LeanbackActivity {
    public static final String EXTRA_RESULT_HOST_NAME = "EXTRA_RESULT_HOST_NAME";
    public static final String EXTRA_RESULT_PORT = "EXTRA_RESULT_PORT";


    private KonfettiView konfettiView;
    private TextView descriptionText;
    private TextView hostNameText;
    private TextView emojiText;
    private String hostName;
    private int port;

    private final int ORANGE = Color.rgb(253, 104, 40);
    private final int[] COLORS = {Color.RED, Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, ORANGE};
    /** the amount of variation in R, G, B components of a confetti burst */
    private final int BURST_COLOR_SPREAD = 200;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan_result);
        
        konfettiView = findViewById(R.id.konfetti);
        descriptionText = findViewById(R.id.scanResultDescriptionText);
        hostNameText = findViewById(R.id.scanResultHostName);
        emojiText = findViewById(R.id.scanResultEmojiText);

        emojiText.setText("\uD83D\uDC4F"); // 👏Clapping Hands
        String partyPopper = "\uD83C\uDF89";
        descriptionText.setText(getResources().getString(com.hyperion.grabber.common.R.string.scan_result_description, partyPopper));
        Bundle extras = getIntent().getExtras();
        if (extras != null){
            hostName = extras.getString(EXTRA_RESULT_HOST_NAME);
            port = Integer.parseInt(extras.getString(EXTRA_RESULT_PORT));
            hostNameText.setText(hostName);
        } else {
            hostNameText.setText(com.hyperion.grabber.common.R.string.error_no_host_name_extra);
        }

        // Animate the appearance of the scan result
        Animator fadeInAnimator = ObjectAnimator.ofFloat(hostNameText, "alpha", 0f, 1f);
        Animator scaleXAnimator = ObjectAnimator
                .ofFloat(hostNameText, "scaleX", .8f, 1f);
        Animator scaleYAnimator = ObjectAnimator
                .ofFloat(hostNameText, "scaleY", .8f, 1f);
        AnimatorSet hostNameSet = new AnimatorSet();
        hostNameSet.playTogether(fadeInAnimator, scaleXAnimator, scaleYAnimator);
        hostNameSet.setInterpolator(new DecelerateInterpolator());
        hostNameSet.setDuration(6000L);
        hostNameSet.start();

        konfettiView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                startKonfetti();
                konfettiView.removeOnLayoutChangeListener(this);
            }
        });



    }

    @Override
    protected void onPause() {
        super.onPause();
        setHyperionColor(hostName, port, Color.BLACK);

    }

    @Override
    protected void onResume() {
        super.onResume();
        setHyperionColor(hostName, port, Color.MAGENTA);
    }

    public void onClick(View v){
        if (v.getId() == R.id.confirmButton){
            saveResult();

            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish(); // call this to finish the current activity
        } else if (v.getId() == R.id.changeColorButton){
            int color = COLORS[new Random().nextInt(COLORS.length)];
            setHyperionColor(hostName, port, color);
            burstKonfetti(color);
        }
    }

    /** Save scan result to Preferences */
    private void saveResult() {
        Preferences prefs = new Preferences(getApplicationContext());
        prefs.putString(com.hyperion.grabber.common.R.string.pref_key_host, hostName);
        prefs.putInt(com.hyperion.grabber.common.R.string.pref_key_port, port);
    }

    private void startKonfetti() {
        EmitterConfig emitterConfig = new Emitter(Long.MAX_VALUE, TimeUnit.MILLISECONDS).perSecond(300);
        Party party = new PartyFactory(emitterConfig)
                .colors(java.util.Arrays.asList(Color.YELLOW, Color.GREEN, Color.MAGENTA))
                .setSpeedBetween(1f, 5f)
                .fadeOutEnabled(true)
                .timeToLive(400L)
                .shapes(java.util.Arrays.asList(Shape.Square.INSTANCE, Shape.Circle.INSTANCE))
                .sizes(java.util.Arrays.asList(new Size(12, 5f, 0f)))
                .position(-50f, konfettiView.getWidth() + 50f, -50f, -50f)
                .build();
        konfettiView.start(party);
    }

    /** Start a one-shot burst of confetti from a random location of a single color
     *
      * @param color a color int such as Color.RED, NOT a color resource
     */
    private void burstKonfetti(int color){
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);

        Random random = new Random();
        java.util.List<Integer> variations = new java.util.ArrayList<>();
        int halfSpread = BURST_COLOR_SPREAD / 2;
        for (int i = 0; i < 10; i++) {
            variations.add(Color.rgb(
                    colorComponentClamp(r - halfSpread + random.nextInt(BURST_COLOR_SPREAD)),
                    colorComponentClamp(g - halfSpread + random.nextInt(BURST_COLOR_SPREAD)),
                    colorComponentClamp(b - halfSpread + random.nextInt(BURST_COLOR_SPREAD))
            ));
        }

        float posX = (float) (Math.random() * konfettiView.getWidth());
        float posY = (float) (Math.random() * konfettiView.getHeight());

        EmitterConfig emitterConfig = new Emitter(100, TimeUnit.MILLISECONDS).max(100);
        Party party = new PartyFactory(emitterConfig)
                .colors(variations)
                .setSpeedBetween(1f, 5f)
                .fadeOutEnabled(true)
                .timeToLive(400L)
                .shapes(java.util.Arrays.asList(Shape.Square.INSTANCE, Shape.Circle.INSTANCE))
                .sizes(java.util.Arrays.asList(new Size(12, 5f, 0f)))
                .position(posX, posY)
                .build();
        konfettiView.start(party);
    }

    /** A color int , or Color.BLACK to clear */
    private void setHyperionColor(String hostName, int port, int color){
        new Thread(() -> {
            try {
                Hyperion hyperion = new Hyperion(hostName, port);
                if (hyperion.isConnected()){
                    if (color == Color.BLACK){
                        hyperion.clear(50);
                    } else {
                        hyperion.setColor(color, 50);
                    }
                }
                hyperion.disconnect();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    /** [0 -255] */
    private static int colorComponentClamp(int value){
        return Math.min(255, Math.max(0, value));
    }

}
