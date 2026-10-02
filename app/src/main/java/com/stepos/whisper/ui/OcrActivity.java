package com.stepos.whisper.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.stepos.whisper.R;
import com.stepos.whisper.databinding.ActivityOcrBinding;
import com.stepos.whisper.ocr.OcrEngine;
import com.stepos.whisper.ocr.OcrResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 系统分享菜单里的“识别截图文字”：收到图片后在本机离线识别，显示并可复制。 */
public class OcrActivity extends AppCompatActivity {
    private static final String TAG = "OcrActivity";

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private ActivityOcrBinding mBinding;
    /** 只在 mExecutor 线程上访问。 */
    private OcrEngine mEngine;
    private String mText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBinding = ActivityOcrBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        mBinding.copy.setOnClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(null, mText));
            Toast.makeText(this, R.string.ocr_copied, Toast.LENGTH_SHORT).show();
        });

        Uri uri = sharedImage(getIntent());
        if (uri == null) {
            mBinding.status.setText(R.string.ocr_no_image);
            return;
        }
        mBinding.status.setText(R.string.ocr_running);
        mExecutor.execute(() -> recognize(uri));
    }

    /** 只接受 content:// —— 本应用以 system 身份运行，不能替调用方读任意文件路径。 */
    @SuppressWarnings("deprecation")
    private static Uri sharedImage(Intent intent) {
        if (!Intent.ACTION_SEND.equals(intent.getAction())) {
            return null;
        }
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        return uri != null && ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) ? uri : null;
    }

    private void recognize(Uri uri) {
        try {
            Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri),
                    (decoder, info, source) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
            if (mEngine == null) {
                mEngine = new OcrEngine();
            }
            OcrResult result = mEngine.recognize(bitmap);
            bitmap.recycle();
            runOnUiThread(() -> show(result));
        } catch (Exception e) {
            Log.e(TAG, "ocr failed", e);
            runOnUiThread(() -> mBinding.status.setText(getString(R.string.status_error, e.getMessage())));
        }
    }

    private void show(OcrResult result) {
        mText = result.getText();
        mBinding.text.setText(mText);
        mBinding.copy.setEnabled(!mText.isEmpty());
        mBinding.status.setText(mText.isEmpty() ? getString(R.string.ocr_empty)
                : getString(R.string.ocr_done, result.lines.size(), result.detectMs, result.recognizeMs));
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mExecutor.execute(() -> {
            if (mEngine != null) {
                mEngine.close();
            }
        });
        mExecutor.shutdown();
    }
}
