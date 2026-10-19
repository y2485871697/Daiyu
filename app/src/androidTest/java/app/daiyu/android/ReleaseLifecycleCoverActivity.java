package app.daiyu.android;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.Parcel;
import android.os.ResultReceiver;

/** Runs in the test APK's own process, without the target APK's Kotlin runtime. */
public final class ReleaseLifecycleCoverActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ResultReceiver reply;
    private boolean readySent;
    private final Runnable expiry = new Runnable() {
        @Override public void run() { finish(); }
    };
    private final ResultReceiver control = new ResultReceiver(handler) {
        @Override protected void onReceiveResult(int code, Bundle data) {
            if (code == 0) finish();
        }
    };

    @SuppressWarnings("deprecation")
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Parcelable candidate = getIntent().getParcelableExtra("reply");
        if (!(candidate instanceof ResultReceiver)) {
            finish();
            return;
        }
        reply = (ResultReceiver) candidate;
        handler.postDelayed(expiry, 15_000L);
        Bundle data = new Bundle();
        Parcel parcel = Parcel.obtain();
        try {
            control.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            data.putParcelable("finish", ResultReceiver.CREATOR.createFromParcel(parcel));
        } finally {
            parcel.recycle();
        }
        data.putInt("task", getTaskId());
        data.putInt("pid", android.os.Process.myPid());
        data.putInt("uid", android.os.Process.myUid());
        reply.send(0, data);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && reply != null && !isFinishing() && !readySent) {
            readySent = true;
            reply.send(1, null);
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(expiry);
        super.onDestroy();
        if (reply != null) reply.send(2, null);
    }
}
