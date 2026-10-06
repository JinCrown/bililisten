package app.bililisten.playback;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** External-UID focus requester; produces no audio and never touches real app data. */
public final class FocusRequestActivity extends Activity {
    private AudioManager audio;
    private AudioFocusRequest focus;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        TextView text = new TextView(this);
        text.setText("BiliListen navigation focus test helper");
        setContentView(text);
        apply(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); apply(intent); }
    private void apply(Intent intent) {
        if (focus != null) { audio.abandonAudioFocusRequest(focus); focus = null; }
        String kind = intent.getStringExtra("kind");
        int result = 0;
        if (!"release".equals(kind)) {
            int gain = "duck".equals(kind) ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                : "transient".equals(kind) ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT : AudioManager.AUDIOFOCUS_GAIN;
            AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage("music".equals(kind) ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType("music".equals(kind) ? AudioAttributes.CONTENT_TYPE_MUSIC : AudioAttributes.CONTENT_TYPE_SPEECH).build();
            focus = new AudioFocusRequest.Builder(gain).setAudioAttributes(attributes).setOnAudioFocusChangeListener(change -> {}).build();
            result = audio.requestAudioFocus(focus);
        }
        String value = kind + ":" + result + ":" + intent.getStringExtra("token");
        try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "focus-result.txt"))) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
        if ("release".equals(kind)) finish();
    }
    @Override protected void onDestroy() {
        if (focus != null) audio.abandonAudioFocusRequest(focus);
        super.onDestroy();
    }
}
